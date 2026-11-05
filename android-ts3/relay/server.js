/*
 * Minimal UDP-over-TCP relay for the TS3 Native Android client.
 *
 * Run:  RELAY_PORT=40000 RELAY_TOKEN=secret node relay/server.js
 *
 * The client opens one TCP connection, sends:
 *   TS3R1 <token|-> <targetHost> <targetPort>\n
 * The relay replies with byte 0 on success, then length-framed UDP payloads
 * flow in both directions. This is a fallback for networks that block or
 * throttle UDP; it adds latency and TCP head-of-line blocking.
 */
"use strict";

const net = require("net");
const dgram = require("dgram");
const dns = require("dns");

const listenPort = Number(process.env.RELAY_PORT || 40000);
const listenHost = process.env.RELAY_HOST || "0.0.0.0";
const requiredToken = process.env.RELAY_TOKEN || "";
const MAX_FRAME = 65535;

function frame(payload) {
    const header = Buffer.alloc(4);
    header.writeUInt32BE(payload.length, 0);
    return Buffer.concat([header, payload]);
}

const server = net.createServer((socket) => {
    socket.setNoDelay(true);
    let buffer = Buffer.alloc(0);
    let ready = false;
    let handshaking = false;
    let udp = null;
    let target = null;

    function fail(message) {
        try {
            const body = Buffer.from(message, "utf8");
            const response = Buffer.alloc(3 + body.length);
            response[0] = 1;
            response.writeUInt16BE(body.length, 1);
            body.copy(response, 3);
            socket.write(response);
        } catch (ignored) {
        }
        socket.destroy();
    }

    function sendFrame(payload) {
        if (!socket.destroyed) socket.write(frame(payload));
    }

    function processFrames() {
        while (buffer.length >= 4) {
            const length = buffer.readUInt32BE(0);
            if (length > MAX_FRAME) {
                fail("frame too large");
                return;
            }
            if (buffer.length < 4 + length) return;
            const payload = buffer.subarray(4, 4 + length);
            buffer = buffer.subarray(4 + length);
            udp.send(payload, target.port, target.address, (error) => {
                if (error) fail(String(error));
            });
        }
    }

    function handshake() {
        if (handshaking) return;
        const newline = buffer.indexOf(10);
        if (newline < 0) {
            if (buffer.length > 4096) fail("handshake too long");
            return;
        }
        const line = buffer.subarray(0, newline).toString("utf8").trim();
        buffer = buffer.subarray(newline + 1);
        const parts = line.split(/\s+/);
        if (parts.length !== 4 || parts[0] !== "TS3R1") {
            fail("bad handshake");
            return;
        }
        handshaking = true;
        const token = parts[1];
        if (requiredToken && token !== requiredToken) {
            fail("bad token");
            return;
        }
        const host = parts[2];
        const port = Number(parts[3]);
        if (!Number.isInteger(port) || port < 1 || port > 65535) {
            fail("bad port");
            return;
        }
        dns.lookup(host, { family: 4 }, (error, address) => {
            if (error) {
                fail("resolve failed: " + error.message);
                return;
            }
            target = { address, port };
            udp = dgram.createSocket("udp4");
            udp.on("message", (message) => sendFrame(message));
            udp.on("error", (udpError) => fail(String(udpError)));
            socket.write(Buffer.from([0]));
            ready = true;
            processFrames();
        });
    }

    socket.on("data", (chunk) => {
        buffer = Buffer.concat([buffer, chunk]);
        if (!ready) handshake();
        if (ready) processFrames();
    });
    socket.on("error", () => {
        if (udp) udp.close();
    });
    socket.on("close", () => {
        if (udp) udp.close();
    });
});

server.listen(listenPort, listenHost, () => {
    console.log(`TS3 UDP relay listening on ${listenHost}:${listenPort}`
        + (requiredToken ? " (token required)" : " (no token)"));
});
