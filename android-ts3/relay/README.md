# UDP-over-TCP relay (optional fallback)

The direct client normally uses UDP 9987. Some networks block or throttle UDP;
this relay carries the same TS3 datagrams inside one TCP connection. It is a
fallback, not the default: it adds latency and TCP head-of-line blocking.

## Run

```sh
node relay/server.js
# or
RELAY_HOST=0.0.0.0 RELAY_PORT=40000 RELAY_TOKEN=change-me node relay/server.js
```

Open `RELAY_PORT` in the firewall. If `RELAY_TOKEN` is set, the client's token
must match. Without a token the relay is an open UDP proxy — do not expose it
publicly that way.

## Client

In the app's connection form fill:

- **Relay host** / **Relay port**: e.g. `vps.example.com` / `40000`
- **Relay token**: same as `RELAY_TOKEN`

Leave relay host empty to connect directly. The app opens a loopback UDP port,
connects ts3j to it, and tunnels those datagrams over TCP to this relay, which
forwards them to the TeamSpeak server.

Protocol: one line `TS3R1 <token|-> <host> <port>\n`, one status byte, then
4-byte big-endian length-prefixed UDP payloads in both directions.
