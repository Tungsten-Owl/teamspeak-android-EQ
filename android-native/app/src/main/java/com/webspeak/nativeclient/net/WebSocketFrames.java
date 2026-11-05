package com.webspeak.nativeclient.net;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * RFC 6455 frame codec.
 *
 * <p>Kept separate from the socket so framing, masking, extended lengths and the
 * handshake accept value can be exercised by unit tests without a network.
 * Client-to-server frames are always masked; server frames must not be.
 */
public final class WebSocketFrames {

    public static final int OPCODE_CONTINUATION = 0x0;
    public static final int OPCODE_TEXT = 0x1;
    public static final int OPCODE_BINARY = 0x2;
    public static final int OPCODE_CLOSE = 0x8;
    public static final int OPCODE_PING = 0x9;
    public static final int OPCODE_PONG = 0xA;

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public static final class Frame {
        public final boolean fin;
        public final int opcode;
        public final byte[] payload;

        public Frame(boolean fin, int opcode, byte[] payload) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    private WebSocketFrames() {
    }

    /**
     * Reads one frame.
     *
     * @return the frame, or {@code null} when the peer closed the stream cleanly
     *         at a frame boundary.
     */
    public static Frame read(InputStream input, int maxPayload) throws IOException {
        int first = input.read();
        if (first == -1) return null;
        boolean fin = (first & 0x80) != 0;
        int rsv = first & 0x70;
        int opcode = first & 0x0F;
        if (rsv != 0) throw new IOException("WebSocket 扩展位未协商");
        int second = readByte(input);
        boolean masked = (second & 0x80) != 0;
        long length = second & 0x7F;
        if (length == 126) {
            length = ((long) readByte(input) << 8) | readByte(input);
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) length = (length << 8) | readByte(input);
            if (length < 0) throw new IOException("WebSocket 帧长度无效");
        }
        boolean control = opcode >= 0x8;
        if (control && (!fin || length > 125)) throw new IOException("WebSocket 控制帧无效");
        if (length > maxPayload) throw new IOException("WebSocket 帧过大");
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(input, mask, 0, 4);
            if (control) {
                // A masked server frame is a protocol violation, but applying the mask
                // keeps the stream readable and the close handling predictable.
            }
        }
        byte[] payload = new byte[(int) length];
        readFully(input, payload, 0, payload.length);
        if (mask != null) applyMask(payload, mask);
        return new Frame(fin, opcode, payload);
    }

    public static void write(OutputStream output, int opcode, byte[] payload, boolean mask, SecureRandom random)
            throws IOException {
        byte[] data = payload == null ? new byte[0] : payload;
        int length = data.length;
        byte[] header = new byte[length < 126 ? 2 : length <= 0xFFFF ? 4 : 10];
        header[0] = (byte) (0x80 | (opcode & 0x0F));
        int index;
        if (length < 126) {
            header[1] = (byte) (length | (mask ? 0x80 : 0));
            index = 2;
        } else if (length <= 0xFFFF) {
            header[1] = (byte) (126 | (mask ? 0x80 : 0));
            header[2] = (byte) (length >>> 8);
            header[3] = (byte) length;
            index = 4;
        } else {
            header[1] = (byte) (127 | (mask ? 0x80 : 0));
            long value = length;
            for (int i = 0; i < 8; i++) {
                header[2 + i] = (byte) (value >>> (56 - 8 * i));
            }
            index = 10;
        }
        if (mask) {
            byte[] key = new byte[4];
            random.nextBytes(key);
            byte[] copy = data.clone();
            applyMask(copy, key);
            byte[] frame = new byte[index + 4 + copy.length];
            System.arraycopy(header, 0, frame, 0, index);
            System.arraycopy(key, 0, frame, index, 4);
            System.arraycopy(copy, 0, frame, index + 4, copy.length);
            output.write(frame);
        } else {
            output.write(header, 0, index);
            output.write(data);
        }
        output.flush();
    }

    public static void writeClose(OutputStream output, int code, String reason, boolean mask, SecureRandom random)
            throws IOException {
        byte[] text = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + text.length];
        payload[0] = (byte) (code >>> 8);
        payload[1] = (byte) code;
        System.arraycopy(text, 0, payload, 2, text.length);
        write(output, OPCODE_CLOSE, payload, mask, random);
    }

    public static void applyMask(byte[] payload, byte[] mask) {
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i & 3];
        }
    }

    public static String newKey(SecureRandom random) {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        return Base64.getEncoder().encodeToString(nonce);
    }

    public static String acceptValue(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-1 unavailable", error);
        }
    }

    public static String closeReason(byte[] payload) {
        if (payload == null || payload.length <= 2) return "";
        return new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
    }

    public static int closeCode(byte[] payload, int fallback) {
        if (payload == null || payload.length < 2) return fallback;
        return ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value == -1) throw new EOFException("WebSocket 连接已关闭");
        return value;
    }

    private static void readFully(InputStream input, byte[] buffer, int offset, int length) throws IOException {
        int done = 0;
        while (done < length) {
            int read = input.read(buffer, offset + done, length - done);
            if (read == -1) throw new EOFException("WebSocket 数据不完整");
            done += read;
        }
    }
}
