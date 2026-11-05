package com.webspeak.nativeclient.core;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable connection parameters plus gateway URL normalization.
 *
 * <p>The native client talks to the same HTTP/WebSocket gateway as the browser:
 * it first posts the join form to {@code /api/join-ticket} and then presents the
 * returned single-use ticket to {@code /ws/voice}. The gateway only accepts the
 * ticket request when the {@code Origin} header matches its own host, so the
 * normalized base URL doubles as the {@code Origin} value.
 */
public final class SessionConfig {

    public final String gatewayUrl;      // scheme://host[:port], no trailing slash
    public final String target;          // optional TeamSpeak address (open access mode)
    public final String nickname;
    public final String channel;         // optional channel name
    public final String serverPassword;
    public final String invite;
    public final String identity;
    public final boolean rememberIdentity;
    public final boolean trustSelfSigned;
    public final int commandTimeoutMs;
    public final int ticketTimeoutMs;
    public final int connectTimeoutMs;

    private SessionConfig(Builder builder) {
        this.gatewayUrl = builder.gatewayUrl;
        this.target = builder.target;
        this.nickname = builder.nickname;
        this.channel = builder.channel;
        this.serverPassword = builder.serverPassword;
        this.invite = builder.invite;
        this.identity = builder.identity;
        this.rememberIdentity = builder.rememberIdentity;
        this.trustSelfSigned = builder.trustSelfSigned;
        this.commandTimeoutMs = builder.commandTimeoutMs;
        this.ticketTimeoutMs = builder.ticketTimeoutMs;
        this.connectTimeoutMs = builder.connectTimeoutMs;
    }

    /** Value for the {@code Origin} header required by {@code /api/join-ticket}. */
    public String origin() {
        return gatewayUrl;
    }

    public String ticketBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!target.isEmpty()) body.put("target", target);
        body.put("nickname", nickname);
        if (!channel.isEmpty()) body.put("channel", channel);
        if (!serverPassword.isEmpty()) body.put("serverPassword", serverPassword);
        if (!invite.isEmpty()) body.put("invite", invite);
        if (rememberIdentity && !identity.isEmpty()) {
            body.put("identity", identity);
            body.put("rememberIdentity", true);
        }
        return Json.stringify(body);
    }

    /**
     * Builds the voice socket URL from the ticket and the optional relay endpoint
     * returned by the gateway, mirroring the browser's endpoint validation: a
     * secure gateway may not downgrade to an insecure voice endpoint.
     */
    public String voiceSocketUrl(String ticket, String voiceEndpoint) {
        String base = voiceEndpoint == null || voiceEndpoint.isEmpty() ? gatewayUrl : voiceEndpoint.trim();
        URI uri;
        try {
            uri = new URI(base);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("语音网关地址无效");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        boolean secureGateway = gatewayUrl.startsWith("https:");
        if (!"http".equals(scheme) && !"https".equals(scheme)) throw new IllegalArgumentException("语音网关地址无效");
        if (uri.getUserInfo() != null || uri.getHost() == null) throw new IllegalArgumentException("语音网关地址无效");
        String path = uri.getPath();
        if ((path != null && !path.isEmpty() && !"/".equals(path)) || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("语音网关地址无效");
        }
        if (secureGateway && !"https".equals(scheme)) throw new IllegalArgumentException("语音网关地址必须使用 HTTPS");
        String wsScheme = "https".equals(scheme) ? "wss" : "ws";
        StringBuilder url = new StringBuilder();
        url.append(wsScheme).append("://").append(formatHost(uri.getHost()));
        if (uri.getPort() != -1) url.append(':').append(uri.getPort());
        url.append("/ws/voice?ticket=").append(encode(ticket));
        return url.toString();
    }

    private static String formatHost(String host) {
        if (host == null) return "";
        if (host.startsWith("[") || host.indexOf(':') < 0) return host;
        return "[" + host + "]";
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException error) {
            throw new IllegalStateException(error);
        }
    }

    public Builder toBuilder() {
        return new Builder()
                .gatewayUrl(gatewayUrl)
                .target(target)
                .nickname(nickname)
                .channel(channel)
                .serverPassword(serverPassword)
                .invite(invite)
                .identity(identity)
                .rememberIdentity(rememberIdentity)
                .trustSelfSigned(trustSelfSigned)
                .commandTimeoutMs(commandTimeoutMs)
                .ticketTimeoutMs(ticketTimeoutMs)
                .connectTimeoutMs(connectTimeoutMs);
    }

    public static final class Builder {
        private String gatewayUrl = "";
        private String target = "";
        private String nickname = "";
        private String channel = "";
        private String serverPassword = "";
        private String invite = "";
        private String identity = "";
        private boolean rememberIdentity;
        private boolean trustSelfSigned;
        private int commandTimeoutMs = 8_000;
        private int ticketTimeoutMs = 15_000;
        private int connectTimeoutMs = 45_000;

        public Builder gatewayUrl(String value) {
            this.gatewayUrl = value == null ? "" : value;
            return this;
        }

        public Builder target(String value) {
            this.target = value == null ? "" : value.trim();
            return this;
        }

        public Builder nickname(String value) {
            this.nickname = value == null ? "" : value.trim();
            return this;
        }

        public Builder channel(String value) {
            this.channel = value == null ? "" : value.trim();
            return this;
        }

        public Builder serverPassword(String value) {
            this.serverPassword = value == null ? "" : value;
            return this;
        }

        public Builder invite(String value) {
            this.invite = value == null ? "" : value.trim();
            return this;
        }

        public Builder identity(String value) {
            this.identity = value == null ? "" : value.trim();
            return this;
        }

        public Builder rememberIdentity(boolean value) {
            this.rememberIdentity = value;
            return this;
        }

        public Builder trustSelfSigned(boolean value) {
            this.trustSelfSigned = value;
            return this;
        }

        public Builder commandTimeoutMs(int value) {
            this.commandTimeoutMs = value;
            return this;
        }

        public Builder ticketTimeoutMs(int value) {
            this.ticketTimeoutMs = value;
            return this;
        }

        public Builder connectTimeoutMs(int value) {
            this.connectTimeoutMs = value;
            return this;
        }

        public SessionConfig build() {
            this.gatewayUrl = normalizeGatewayUrl(gatewayUrl);
            if (nickname.isEmpty()) throw new IllegalArgumentException("请输入昵称");
            if (nickname.length() < 3) throw new IllegalArgumentException("昵称至少需要 3 个字符（TeamSpeak 要求）");
            if (nickname.length() > 30) throw new IllegalArgumentException("昵称不能超过 30 个字符");
            if (channel.length() > 100) throw new IllegalArgumentException("频道名不能超过 100 个字符");
            if (serverPassword.length() > 512) throw new IllegalArgumentException("服务器密码过长");
            if (invite.length() > 128) throw new IllegalArgumentException("邀请码过长");
            if (identity.length() > 8192) throw new IllegalArgumentException("身份字符串过长");
            return new SessionConfig(this);
        }
    }

    /**
     * Accepts {@code host:port}, {@code http(s)://host[:port][/]} and returns the
     * canonical origin used for both the ticket request and the socket.
     */
    public static String normalizeGatewayUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("请输入网关地址");
        if (!value.contains("://")) value = "http://" + value;
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("网关地址无效");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("网关地址必须以 http:// 或 https:// 开头");
        }
        if (uri.getHost() == null) throw new IllegalArgumentException("网关地址缺少主机名");
        if (uri.getUserInfo() != null) throw new IllegalArgumentException("网关地址不能包含用户名或密码");
        String path = uri.getPath();
        if ((path != null && !path.isEmpty() && !"/".equals(path)) || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("网关地址只能包含主机和端口");
        }
        StringBuilder url = new StringBuilder();
        url.append(scheme).append("://").append(formatHost(uri.getHost()));
        if (uri.getPort() != -1) url.append(':').append(uri.getPort());
        return url.toString();
    }
}
