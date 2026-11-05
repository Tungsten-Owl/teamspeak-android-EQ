package com.webspeak.nativeclient.net;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal RFC 6455 client tailored to the WebSpeak voice socket.
 *
 * <p>Only the pieces this protocol needs are implemented: text/binary messages,
 * optional fragmentation, ping replies (the gateway heartbeats with WebSocket
 * pings every 30 seconds and terminates a client that misses two), the close
 * handshake, and TLS for {@code wss}. Control frames never reach the listener;
 * PING is answered inline on the reader thread.
 *
 * <p>Listener callbacks run on the reader thread. Callers that touch UI must
 * dispatch to their own main-thread handoff.
 */
public final class WebSocketClient {

    public interface Listener {
        void onOpen();

        void onText(String text);

        void onBinary(byte[] data);

        void onClose(int code, String reason, boolean remote);

        void onError(Throwable error);
    }

    // Gateways may include avatar data URLs inside channelList snapshots, so the
    // inbound ceiling is much larger than the control frames this client sends.
    private static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    private static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_HEADER_BYTES = 16 * 1024;

    private final String url;
    private final String host;
    private final int port;
    private final String path;
    private final boolean secure;
    private final boolean trustSelfSigned;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private final Object writeLock = new Object();
    private final AtomicBoolean closeDelivered = new AtomicBoolean();
    private volatile boolean open;
    private volatile boolean closedByCaller;
    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private Thread readerThread;

    public WebSocketClient(String url, boolean trustSelfSigned, Listener listener) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception error) {
            throw new IllegalArgumentException("语音网关地址无效");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"ws".equals(scheme) && !"wss".equals(scheme)) throw new IllegalArgumentException("语音网关地址无效");
        if (uri.getHost() == null) throw new IllegalArgumentException("语音网关地址缺少主机名");
        this.url = url;
        this.secure = "wss".equals(scheme);
        this.host = stripBrackets(uri.getHost());
        this.port = uri.getPort() != -1 ? uri.getPort() : (this.secure ? 443 : 80);
        String rawPath = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        this.path = uri.getRawQuery() == null ? rawPath : rawPath + "?" + uri.getRawQuery();
        this.trustSelfSigned = trustSelfSigned;
        this.listener = listener;
    }

    public String url() {
        return url;
    }

    public boolean isOpen() {
        return open;
    }

    /** Performs the opening handshake and starts the reader thread. */
    public void connect(int timeoutMs) throws IOException {
        if (open) throw new IllegalStateException("连接已建立");
        Socket raw = new Socket();
        raw.setTcpNoDelay(true);
        try {
            raw.connect(new InetSocketAddress(host, port), timeoutMs);
            raw.setSoTimeout(timeoutMs);
            Socket channel = secure ? Tls.wrap(raw, host, port, trustSelfSigned) : raw;
            this.socket = channel;
            this.input = channel.getInputStream();
            this.output = channel.getOutputStream();
            handshake(timeoutMs);
            channel.setSoTimeout(0);
            open = true;
            readerThread = new Thread(this::readLoop, "webspeak-ws-reader");
            readerThread.setDaemon(true);
            readerThread.start();
            listener.onOpen();
        } catch (IOException error) {
            closeQuietly(raw);
            throw error;
        }
    }

    public void sendText(String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        send(WebSocketFrames.OPCODE_TEXT, payload);
    }

    public void sendBinary(byte[] data) throws IOException {
        send(WebSocketFrames.OPCODE_BINARY, data);
    }

    private void send(int opcode, byte[] payload) throws IOException {
        synchronized (writeLock) {
            if (!open || output == null) throw new IOException("语音连接尚未就绪");
            try {
                WebSocketFrames.write(output, opcode, payload, true, random);
            } catch (IOException error) {
                notifyError(error);
                finishClose(1006, "", false);
                throw error;
            }
        }
    }

    /** Client-initiated close; the listener receives the close callback exactly once. */
    public void close(int code, String reason) {
        closedByCaller = true;
        synchronized (writeLock) {
            if (open && output != null) {
                try {
                    WebSocketFrames.writeClose(output, code, reason, true, random);
                } catch (IOException ignored) {
                    // The socket may already be gone; closing below is authoritative.
                }
            }
        }
        finishClose(code, reason == null ? "" : reason, false);
    }

    // ------------------------------------------------------------------ internals

    private void handshake(int timeoutMs) throws IOException {
        String key = WebSocketFrames.newKey(random);
        String hostHeader = (host.indexOf(':') >= 0 ? "[" + host + "]" : host)
                + (port == (secure ? 443 : 80) ? "" : ":" + port);
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "User-Agent: WebSpeakNative/1.0\r\n"
                + "\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();

        String statusLine = readHeaderLine(input, 0);
        if (statusLine == null) throw new IOException("语音网关未返回 WebSocket 握手响应");
        int status = parseStatus(statusLine);
        Map<String, String> headers = new HashMap<>();
        int total = statusLine.length();
        while (true) {
            String line = readHeaderLine(input, total);
            if (line == null) throw new IOException("WebSocket 握手响应不完整");
            total += line.length() + 2;
            if (total > MAX_HEADER_BYTES) throw new IOException("WebSocket 握手响应过大");
            if (line.isEmpty()) break;
            int separator = line.indexOf(':');
            if (separator <= 0) continue;
            headers.put(line.substring(0, separator).trim().toLowerCase(Locale.ROOT),
                    line.substring(separator + 1).trim());
        }
        if (status != 101) {
            throw new IOException("语音网关拒绝了 WebSocket 握手（HTTP " + status + "）");
        }
        String upgrade = headers.get("upgrade");
        if (upgrade == null || !upgrade.toLowerCase(Locale.ROOT).contains("websocket")) {
            throw new IOException("语音网关未升级到 WebSocket");
        }
        String accept = headers.get("sec-websocket-accept");
        if (accept == null || !accept.equals(WebSocketFrames.acceptValue(key))) {
            throw new IOException("WebSocket 握手校验失败");
        }
    }

    private void readLoop() {
        ByteArrayOutputStream fragmented = null;
        int fragmentedOpcode = 0;
        try {
            while (open) {
                WebSocketFrames.Frame frame = WebSocketFrames.read(input, MAX_FRAME_BYTES);
                if (frame == null) break;
                switch (frame.opcode) {
                    case WebSocketFrames.OPCODE_PING:
                        synchronized (writeLock) {
                            if (open && output != null) {
                                WebSocketFrames.write(output, WebSocketFrames.OPCODE_PONG, frame.payload, true, random);
                            }
                        }
                        break;
                    case WebSocketFrames.OPCODE_PONG:
                        break;
                    case WebSocketFrames.OPCODE_CLOSE: {
                        int code = WebSocketFrames.closeCode(frame.payload, 1005);
                        String reason = WebSocketFrames.closeReason(frame.payload);
                        replyClose(code);
                        finishClose(code, reason, true);
                        return;
                    }
                    case WebSocketFrames.OPCODE_TEXT:
                    case WebSocketFrames.OPCODE_BINARY: {
                        if (fragmented != null) throw new IOException("WebSocket 分片消息顺序错误");
                        if (frame.fin) {
                            deliver(frame.opcode, frame.payload);
                        } else {
                            fragmented = new ByteArrayOutputStream();
                            fragmentedOpcode = frame.opcode;
                            appendFragment(fragmented, frame.payload);
                        }
                        break;
                    }
                    case WebSocketFrames.OPCODE_CONTINUATION: {
                        if (fragmented == null) throw new IOException("WebSocket 续帧没有起始帧");
                        appendFragment(fragmented, frame.payload);
                        if (frame.fin) {
                            byte[] complete = fragmented.toByteArray();
                            fragmented = null;
                            deliver(fragmentedOpcode, complete);
                        }
                        break;
                    }
                    default:
                        throw new IOException("WebSocket 未知操作码 " + frame.opcode);
                }
            }
            finishClose(1006, "网关关闭了连接", true);
        } catch (EOFException error) {
            finishClose(1006, "", true);
        } catch (IOException error) {
            notifyError(error);
            finishClose(1006, "", true);
        } catch (RuntimeException error) {
            notifyError(error);
            finishClose(1011, "", true);
        }
    }

    private void appendFragment(ByteArrayOutputStream buffer, byte[] chunk) throws IOException {
        if (buffer.size() + chunk.length > MAX_MESSAGE_BYTES) throw new IOException("WebSocket 消息过大");
        buffer.write(chunk, 0, chunk.length);
    }

    private void deliver(int opcode, byte[] payload) {
        if (opcode == WebSocketFrames.OPCODE_TEXT) {
            listener.onText(new String(payload, StandardCharsets.UTF_8));
        } else {
            listener.onBinary(payload);
        }
    }

    private void replyClose(int code) {
        synchronized (writeLock) {
            if (!open || output == null) return;
            try {
                WebSocketFrames.writeClose(output, code == 1005 || code == 1006 ? 1000 : code, "", true, random);
            } catch (IOException ignored) {
                // Best effort; the socket is closed below.
            }
        }
    }

    private void finishClose(int code, String reason, boolean remote) {
        open = false;
        closeQuietly(socket);
        if (closeDelivered.compareAndSet(false, true)) {
            listener.onClose(code, reason, remote);
        }
    }

    private void notifyError(Throwable error) {
        try {
            listener.onError(error);
        } catch (RuntimeException ignored) {
            // A listener failure must not prevent socket cleanup.
        }
    }

    private static String stripBrackets(String host) {
        if (host != null && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static int parseStatus(String statusLine) {
        String[] parts = statusLine.split(" ");
        if (parts.length < 2) return 0;
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static String readHeaderLine(InputStream input, int consumed) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value == -1) {
                if (line.size() == 0) return null;
                throw new EOFException("WebSocket 握手响应不完整");
            }
            if (value == '\n') break;
            if (value != '\r') line.write(value);
            if (consumed + line.size() > MAX_HEADER_BYTES) throw new IOException("WebSocket 握手响应过大");
        }
        return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }
}
