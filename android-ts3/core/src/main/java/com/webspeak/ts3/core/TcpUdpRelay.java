package com.webspeak.ts3.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Optional UDP-over-TCP fallback.
 *
 * <p>The TS3 client still speaks its normal UDP protocol, but to a loopback
 * port owned by this bridge. Datagrams are length-framed over one TCP
 * connection to a relay, which forwards them to the real TS3 server. This
 * survives networks that block or heavily throttle UDP at the cost of extra
 * latency (and TCP head-of-line blocking).
 */
public final class TcpUdpRelay {
    private static final int MAX_DATAGRAM = 65_535;

    private final String relayHost;
    private final int relayPort;
    private final String token;
    private final String targetHost;
    private final int targetPort;

    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private DatagramSocket local;
    private Thread uplink;
    private Thread downlink;
    private volatile boolean running;
    private volatile SocketAddress lastClient;

    public TcpUdpRelay(String relayHost, int relayPort, String token,
                       String targetHost, int targetPort) {
        this.relayHost = relayHost;
        this.relayPort = relayPort;
        this.token = token == null ? "" : token.trim();
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    public int start() throws IOException {
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(10_000);
        socket.connect(new InetSocketAddress(relayHost, relayPort), 10_000);
        input = new DataInputStream(socket.getInputStream());
        output = new DataOutputStream(socket.getOutputStream());

        String handshake = "TS3R1 " + (token.isEmpty() ? "-" : token)
                + " " + targetHost + " " + targetPort + "\n";
        output.write(handshake.getBytes(StandardCharsets.UTF_8));
        output.flush();

        int status = input.read();
        if (status != 0) {
            String message = "relay rejected the connection";
            try {
                int length = input.readUnsignedShort();
                byte[] bytes = new byte[length];
                input.readFully(bytes);
                message = new String(bytes, StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
            }
            close();
            throw new IOException(message);
        }

        local = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        socket.setSoTimeout(0);
        running = true;

        uplink = new Thread(this::pumpUplink, "ts3-relay-uplink");
        uplink.setDaemon(true);
        uplink.start();
        downlink = new Thread(this::pumpDownlink, "ts3-relay-downlink");
        downlink.setDaemon(true);
        downlink.start();
        return local.getLocalPort();
    }

    private void pumpUplink() {
        byte[] buffer = new byte[MAX_DATAGRAM];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                local.receive(packet);
                lastClient = packet.getSocketAddress();
                synchronized (output) {
                    output.writeInt(packet.getLength());
                    output.write(packet.getData(), packet.getOffset(), packet.getLength());
                    output.flush();
                }
            } catch (Throwable error) {
                if (running) close();
                return;
            }
        }
    }

    private void pumpDownlink() {
        while (running) {
            try {
                int length = input.readInt();
                if (length < 0 || length > MAX_DATAGRAM) {
                    close();
                    return;
                }
                byte[] payload = new byte[length];
                input.readFully(payload);
                SocketAddress client = lastClient;
                if (client != null) {
                    local.send(new DatagramPacket(payload, length, client));
                }
            } catch (EOFException error) {
                close();
                return;
            } catch (Throwable error) {
                if (running) close();
                return;
            }
        }
    }

    public void close() {
        running = false;
        try {
            if (local != null) local.close();
        } catch (Throwable ignored) {
        }
        try {
            if (socket != null) socket.close();
        } catch (Throwable ignored) {
        }
        local = null;
        socket = null;
        input = null;
        output = null;
        Thread up = uplink;
        Thread down = downlink;
        uplink = null;
        downlink = null;
        if (up != null) up.interrupt();
        if (down != null) down.interrupt();
    }
}
