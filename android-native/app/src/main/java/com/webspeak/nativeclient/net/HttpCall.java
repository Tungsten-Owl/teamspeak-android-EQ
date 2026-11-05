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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal HTTP/1.1 client for the gateway's JSON endpoints.
 *
 * <p>{@code HttpURLConnection} is intentionally not used: the JDK treats
 * {@code Origin} as a restricted header and silently drops it, while
 * {@code /api/join-ticket} rejects any request whose {@code Origin} does not
 * match the gateway host. Writing the request over a socket keeps the exact
 * browser-equivalent headers (including {@code Origin}) on both Android and the
 * desktop verification JVM.
 *
 * <p>Only the shapes the gateway returns are supported: a status line, headers,
 * and a {@code Content-Length} or chunked body.
 */
public final class HttpCall {

    public static final class Result {
        public final int status;
        public final String body;
        public final String contentType;

        public Result(int status, String body, String contentType) {
            this.status = status;
            this.body = body;
            this.contentType = contentType;
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final int MAX_HEADER_BYTES = 32 * 1024;

    private final String method;
    private final String host;
    private final int port;
    private final boolean secure;
    private final String path;
    private final boolean trustSelfSigned;
    private final Map<String, String> headers = new java.util.LinkedHashMap<>();
    private volatile Socket socket;
    private volatile boolean aborted;

    public HttpCall(String method, String url, boolean trustSelfSigned) {
        this.method = method;
        this.trustSelfSigned = trustSelfSigned;
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception error) {
            throw new IllegalArgumentException("网关地址无效");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("网关地址必须以 http:// 或 https:// 开头");
        }
        if (uri.getHost() == null) throw new IllegalArgumentException("网关地址缺少主机名");
        this.secure = "https".equals(scheme);
        this.host = stripBrackets(uri.getHost());
        this.port = uri.getPort() != -1 ? uri.getPort() : (secure ? 443 : 80);
        String rawPath = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        this.path = uri.getRawQuery() == null ? rawPath : rawPath + "?" + uri.getRawQuery();
    }

    public void header(String name, String value) {
        headers.put(name, value);
    }

    public Result execute(String body) throws IOException {
        Socket raw = new Socket();
        socket = raw;
        if (aborted) throw new IOException("请求已取消");
        try {
            raw.setTcpNoDelay(true);
            raw.connect(new InetSocketAddress(host, port), 10_000);
            raw.setSoTimeout(15_000);
            Socket channel = secure ? Tls.wrap(raw, host, port, trustSelfSigned) : raw;
            OutputStream output = channel.getOutputStream();
            InputStream input = channel.getInputStream();
            byte[] bodyBytes = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
            output.write(requestHeaders(bodyBytes).getBytes(StandardCharsets.UTF_8));
            if (bodyBytes != null) output.write(bodyBytes);
            output.flush();
            return readResponse(input);
        } finally {
            closeQuietly(raw);
            socket = null;
        }
    }

    /** Called from a watchdog thread to unblock a pending request. */
    public void abort() {
        aborted = true;
        closeQuietly(socket);
    }

    private String requestHeaders(byte[] body) {
        String hostHeader = (host.indexOf(':') >= 0 ? "[" + host + "]" : host)
                + (port == (secure ? 443 : 80) ? "" : ":" + port);
        StringBuilder request = new StringBuilder();
        request.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(hostHeader).append("\r\n");
        request.append("Connection: close\r\n");
        request.append("Accept-Encoding: identity\r\n");
        request.append("User-Agent: WebSpeakNative/1.0\r\n");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            request.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        if (body != null) request.append("Content-Length: ").append(body.length).append("\r\n");
        request.append("\r\n");
        return request.toString();
    }

    private static Result readResponse(InputStream input) throws IOException {
        String statusLine = readLine(input);
        if (statusLine == null) throw new EOFException("网关未返回响应");
        int status = parseStatus(statusLine);
        Map<String, String> responseHeaders = new HashMap<>();
        int consumed = statusLine.length();
        while (true) {
            String line = readLine(input);
            if (line == null) throw new EOFException("网关响应不完整");
            consumed += line.length() + 2;
            if (consumed > MAX_HEADER_BYTES) throw new IOException("网关响应头过大");
            if (line.isEmpty()) break;
            int separator = line.indexOf(':');
            if (separator <= 0) continue;
            responseHeaders.put(line.substring(0, separator).trim().toLowerCase(Locale.ROOT),
                    line.substring(separator + 1).trim());
        }
        String transferEncoding = responseHeaders.get("transfer-encoding");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            readChunked(input, body);
        } else {
            String contentLength = responseHeaders.get("content-length");
            if (contentLength != null) {
                int length;
                try {
                    length = Integer.parseInt(contentLength.trim());
                } catch (NumberFormatException error) {
                    throw new IOException("网关响应长度无效");
                }
                if (length < 0 || length > MAX_BODY_BYTES) throw new IOException("网关响应过大");
                copy(input, body, length);
            } else {
                copyToEof(input, body);
            }
        }
        String contentType = responseHeaders.get("content-type");
        return new Result(status, new String(body.toByteArray(), StandardCharsets.UTF_8),
                contentType == null ? "" : contentType);
    }

    private static void readChunked(InputStream input, ByteArrayOutputStream body) throws IOException {
        while (true) {
            String sizeLine = readLine(input);
            if (sizeLine == null) throw new EOFException("分块响应不完整");
            int semicolon = sizeLine.indexOf(';');
            String sizeText = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();
            int size;
            try {
                size = Integer.parseInt(sizeText, 16);
            } catch (NumberFormatException error) {
                throw new IOException("分块长度无效");
            }
            if (size == 0) {
                // Consume trailers until the terminating blank line.
                while (true) {
                    String trailer = readLine(input);
                    if (trailer == null || trailer.isEmpty()) break;
                }
                return;
            }
            if (body.size() + size > MAX_BODY_BYTES) throw new IOException("网关响应过大");
            copy(input, body, size);
            readLine(input); // trailing CRLF after each chunk
        }
    }

    private static void copy(InputStream input, ByteArrayOutputStream output, int length) throws IOException {
        byte[] buffer = new byte[8 * 1024];
        int remaining = length;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read == -1) throw new EOFException("网关响应长度不足");
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void copyToEof(InputStream input, ByteArrayOutputStream output) throws IOException {
        byte[] buffer = new byte[8 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > MAX_BODY_BYTES) throw new IOException("网关响应过大");
            output.write(buffer, 0, read);
        }
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

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') line.write(value);
        }
        if (value == -1 && line.size() == 0) return null;
        return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static String stripBrackets(String host) {
        if (host != null && host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
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
