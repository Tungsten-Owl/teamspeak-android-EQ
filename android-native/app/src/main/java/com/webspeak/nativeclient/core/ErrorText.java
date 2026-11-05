package com.webspeak.nativeclient.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Chinese explanations for gateway, TeamSpeak and transport failures.
 *
 * <p>Codes come from three places: the join-ticket HTTP response, structured
 * {@code connectionFailed}/{@code reconnectFailed} messages, and the WebSocket
 * close frame. The browser client keeps the same mapping; keeping it here means a
 * failed connection always says something actionable instead of a bare code.
 */
public final class ErrorText {

    private static final Map<String, String> MESSAGES = new LinkedHashMap<>();
    private static final Map<Integer, String> CLOSE_CODES = new LinkedHashMap<>();
    private static final Map<String, String> ALIASES = new LinkedHashMap<>();

    static {
        ALIASES.put("PASSWORD_REQUIRED", "SERVER_PASSWORD_REQUIRED");
        ALIASES.put("INVALID_PASSWORD", "INVALID_SERVER_PASSWORD");
        ALIASES.put("AUTHENTICATION_FAILED", "INVALID_SERVER_PASSWORD");
        ALIASES.put("GATEWAY_FULL", "SERVER_REJECTED");
        ALIASES.put("TS_CONNECT_FAILED", "CONNECTION_FAILED");
        ALIASES.put("TEAM_SPEAK_CONNECT_FAILED", "CONNECTION_FAILED");

        MESSAGES.put("ORIGIN_REJECTED", "请求来源不受信任：网关拒绝了本次加入请求，请检查网关地址是否与访问入口一致");
        MESSAGES.put("NOT_INITIALIZED", "WebSpeak 网关尚未完成配置，请联系管理员");
        MESSAGES.put("RATE_LIMITED", "请求过于频繁，请稍后重试");
        MESSAGES.put("INVALID_TARGET", "TeamSpeak 服务器地址无效");
        MESSAGES.put("INVALID_NICKNAME", "昵称不符合要求：至少 3 个字符，且不超过 30 个字符");
        MESSAGES.put("TARGET_NOT_ALLOWED", "此 TeamSpeak 服务器地址不允许连接");
        MESSAGES.put("HOST_NOT_FOUND", "找不到 TeamSpeak 服务器主机名，请检查地址");
        MESSAGES.put("INVITE_INVALID", "邀请链接已失效或已被撤销");
        MESSAGES.put("ACCELERATION_UNAVAILABLE", "当前中继加速不可用，请联系管理员或关闭加速");
        MESSAGES.put("UNREACHABLE", "无法到达 TeamSpeak 服务器，请检查网络或地址");
        MESSAGES.put("CONNECTION_REFUSED", "TeamSpeak 服务器拒绝了连接，请检查端口和服务状态");
        MESSAGES.put("CONNECTION_RESET", "TeamSpeak 连接被服务器或网络重置，请稍后重试");
        MESSAGES.put("TIMEOUT", "连接 TeamSpeak 超时，请检查网络或服务器状态");
        MESSAGES.put("SERVER_PASSWORD_REQUIRED", "该服务器需要密码，请输入密码后重试");
        MESSAGES.put("INVALID_SERVER_PASSWORD", "服务器密码错误，请重新输入");
        MESSAGES.put("PROTOCOL_NEGOTIATION_FAILED", "TeamSpeak 协议协商失败");
        MESSAGES.put("SERVER_REJECTED", "TeamSpeak 服务器拒绝了连接");
        MESSAGES.put("CHANNEL_PASSWORD_REQUIRED", "该频道需要密码");
        MESSAGES.put("NICKNAME_IN_USE", "该昵称已被服务器上的其他用户占用，请更换昵称");
        MESSAGES.put("IDENTITY_SECURITY_LEVEL_TOO_LOW", "你的身份安全等级低于该服务器要求");
        MESSAGES.put("IDENTITY_LIMIT_REACHED", "该身份建立的连接数已达上限，请关闭其他连接后重试");
        MESSAGES.put("CLIENT_VERSION_OUTDATED", "客户端版本过旧，服务器拒绝连接");
        MESSAGES.put("FLOOD_PROTECTION", "操作过于频繁，已被服务器洪水防护暂时拒绝，请稍后重试");
        MESSAGES.put("BANNED", "你已被该服务器封禁，无法连接");
        MESSAGES.put("KICKED", "你已被服务器移出");
        MESSAGES.put("SERVER_SHUTTING_DOWN", "TeamSpeak 服务器正在关闭，暂时无法连接");
        MESSAGES.put("CONNECTION_INITIALISATION_FAILED", "TeamSpeak 服务器未能完成连接初始化，请检查地址、端口或稍后重试");
        MESSAGES.put("SERVER_FULL", "服务器当前已满，请稍后重试");
        MESSAGES.put("INVALID_PARAMETER", "TeamSpeak 服务器拒绝了参数，通常是昵称长度或格式不合规");
        MESSAGES.put("IDENTITY_IN_USE", "此 TeamSpeak 身份已在另一条连接中使用，请关闭另一条连接或取消“保持身份”后重试");
        MESSAGES.put("CONNECTION_FAILED", "TeamSpeak 连接失败，请检查地址、网络或服务器状态");
        MESSAGES.put("IDENTITY_INVALID", "语音网关拒绝了本次连接：身份无效，请取消“保持身份”后重新进入");
        MESSAGES.put("IDENTITY_REJECTED", "语音网关拒绝了本次连接：身份无效或无法在此客户端使用");
        MESSAGES.put("JOIN_TICKET_REQUIRED", "语音会话票据缺失或已过期，请重新连接");
        MESSAGES.put("JOIN_TICKET_INVALID", "语音会话票据无效，请重新连接");
        MESSAGES.put("TEAM_SPEAK_CLIENT_UNAVAILABLE", "语音网关未能创建 TeamSpeak 客户端（服务器可能已关闭或地址不可达）");
        MESSAGES.put("GATEWAY_NETWORK_LOST", "与语音网关的网络连接异常中断，请检查网络后重新连接");
        MESSAGES.put("GATEWAY_SESSION_ENDED", "语音网关会话意外结束，请重新连接");
        MESSAGES.put("REQUEST_TIMEOUT", "等待 WebSpeak 网关响应超时，请检查网络后重试");
        MESSAGES.put("REQUEST_FAILED", "无法连接 WebSpeak 网关，请检查地址与网络");
        MESSAGES.put("GATEWAY_TIMEOUT", "网关未能在预期时间内完成 TeamSpeak 握手，请稍后重试");
        MESSAGES.put("GATEWAY_UNAVAILABLE", "WebSpeak 网关不可用，请检查地址与端口");
        MESSAGES.put("TLS_ERROR", "网关的 HTTPS 证书无法验证；如果是自签名证书，请勾选“信任自签名证书”");
        MESSAGES.put("PROTOCOL_ERROR", "语音网关返回了无法识别的数据");
        MESSAGES.put("NOT_CONNECTED", "尚未连接到语音网关");
        MESSAGES.put("INVALID_AUDIO_FRAME", "音频帧格式无效，网关已丢弃");
        MESSAGES.put("AUDIO_ENCODER_UNAVAILABLE", "网关无法编码麦克风音频（Opus 编码器不可用）");
        MESSAGES.put("WEBRTC_NEGOTIATION_FAILED", "实时语音（WebRTC）协商失败，已回退至兼容传输");

        CLOSE_CODES.put(4_000, "CONNECTION_FAILED");
        CLOSE_CODES.put(4_001, "JOIN_TICKET_REQUIRED");
        CLOSE_CODES.put(4_002, "INVALID_TARGET");
        CLOSE_CODES.put(4_003, "IDENTITY_REJECTED");
        CLOSE_CODES.put(4_004, "SERVER_REJECTED");
        CLOSE_CODES.put(4_005, "IDENTITY_IN_USE");
        CLOSE_CODES.put(4_006, "ACCELERATION_UNAVAILABLE");
        CLOSE_CODES.put(1_006, "GATEWAY_NETWORK_LOST");
        CLOSE_CODES.put(1_011, "GATEWAY_SESSION_ENDED");
    }

    private ErrorText() {
    }

    /** Uppercase, bounded form of a code provided by the gateway or an exception name. */
    public static String normalizeCode(String raw) {
        if (raw == null) return "";
        String upper = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_-]+", "_");
        while (upper.startsWith("_")) upper = upper.substring(1);
        while (upper.endsWith("_")) upper = upper.substring(0, upper.length() - 1);
        return upper.length() > 64 ? upper.substring(0, 64) : upper;
    }

    public static String forCode(String rawCode, String detail) {
        String code = normalizeCode(rawCode);
        code = ALIASES.getOrDefault(code, code);
        String message = MESSAGES.get(code);
        String safeDetail = sanitize(detail);
        if (message == null) {
            String base = code.isEmpty() ? "连接失败" : "连接失败（错误代码：" + code + "）";
            return safeDetail.isEmpty() ? base + "，请检查输入、网络或服务器状态" : base + "：" + safeDetail;
        }
        return safeDetail.isEmpty() ? message : message + "（" + safeDetail + "）";
    }

    /** Maps a WebSocket close frame, preferring an explainable code in its reason. */
    public static String forClose(int code, String reason) {
        String fromReason = normalizeCode(reason);
        if (MESSAGES.containsKey(fromReason) || ALIASES.containsKey(fromReason)) {
            return forCode(fromReason, null);
        }
        String mapped = CLOSE_CODES.get(code);
        if (mapped != null) return forCode(mapped, null);
        return "与语音网关的连接已关闭（关闭码 " + code + "），请重新连接";
    }

    /** Command/protocol failures: the gateway's own message is usually specific. */
    public static String forOperation(String code, String serverMessage) {
        String message = sanitize(serverMessage);
        if (!message.isEmpty()) return message;
        String normalized = normalizeCode(code);
        String known = MESSAGES.get(ALIASES.getOrDefault(normalized, normalized));
        return known != null ? known : "操作失败（错误代码：" + (normalized.isEmpty() ? "OPERATION_FAILED" : normalized) + "）";
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("[\\u0000-\\u001f\\u007f]", " ").replaceAll("\\s+", " ").trim();
        return cleaned.length() > 160 ? cleaned.substring(0, 160) : cleaned;
    }
}
