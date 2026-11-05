package com.webspeak.ts3.core;

/** Everything needed to open one direct TeamSpeak 3 client connection. */
public final class Ts3Config {
    public final String host;
    public final int port;
    public final String nickname;
    public final String serverPassword;
    public final String channel;
    public final String privilegeKey;
    public final String identity;
    /** Optional UDP-over-TCP relay endpoint. Empty means a direct UDP connection. */
    public final String relayHost;
    public final int relayPort;
    public final String relayToken;

    public Ts3Config(String host, int port, String nickname, String serverPassword,
                     String channel, String privilegeKey, String identity) {
        this(host, port, nickname, serverPassword, channel, privilegeKey, identity, "", 0, "");
    }

    public Ts3Config(String host, int port, String nickname, String serverPassword,
                     String channel, String privilegeKey, String identity,
                     String relayHost, int relayPort, String relayToken) {
        this.host = host == null ? "" : host.trim();
        this.port = port;
        this.nickname = nickname == null ? "" : nickname.trim();
        this.serverPassword = serverPassword == null ? "" : serverPassword;
        this.channel = channel == null ? "" : channel.trim();
        this.privilegeKey = privilegeKey == null ? "" : privilegeKey.trim();
        this.identity = identity == null ? "" : identity.trim();
        this.relayHost = relayHost == null ? "" : relayHost.trim();
        this.relayPort = relayPort;
        this.relayToken = relayToken == null ? "" : relayToken.trim();
    }

    public boolean usesRelay() {
        return !relayHost.isEmpty() && relayPort > 0 && relayPort <= 65535;
    }
}
