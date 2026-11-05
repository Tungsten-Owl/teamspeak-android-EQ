package com.webspeak.nativeclient.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builders for the browser-to-gateway command frames.
 *
 * <p>Every builder validates the same bounds the gateway's
 * {@code parseClientCommand} enforces, so an invalid value is rejected in the
 * UI instead of producing a protocol error round-trip. Commands that are
 * acknowledged by the gateway are sent with a {@code requestId}; the
 * fire-and-forget state notifications ({@code setWhisperTargets},
 * {@code setWhisperActive}) are acknowledged by their dedicated
 * {@code whisperTargets} reply instead.
 */
public final class Wire {

    public static final int MAX_TEXT_MESSAGE = 500;
    public static final int MAX_POKE_MESSAGE = 200;
    public static final int MAX_AWAY_MESSAGE = 200;
    public static final int MAX_CHANNEL_PASSWORD = 512;
    public static final int MAX_WHISPER_TARGETS = 8;

    private Wire() {
    }

    public static String command(String type, Map<String, Object> payload, String requestId) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("payload", payload);
        if (requestId != null) message.put("requestId", requestId);
        return Json.stringify(message);
    }

    public static String switchChannel(String channelId, String password, String requestId) {
        requireChannelId(channelId);
        Map<String, Object> payload = Json.object("channelId", channelId);
        if (password != null && !password.isEmpty()) {
            if (password.length() > MAX_CHANNEL_PASSWORD) throw new IllegalArgumentException("频道密码过长");
            payload.put("password", password);
        }
        return command("switchChannel", payload, requestId);
    }

    public static String moveClient(int clientId, String channelId, String requestId) {
        requireClientId(clientId);
        requireChannelId(channelId);
        return command("moveClient", Json.object("clientId", clientId, "channelId", channelId), requestId);
    }

    public static String sendTextMessage(String message, String channelId, String requestId) {
        requireText(message, MAX_TEXT_MESSAGE, "消息内容不能为空且不能超过 500 个字符");
        Map<String, Object> payload = Json.object("message", message);
        if (channelId != null && !channelId.isEmpty()) {
            requireChannelId(channelId);
            payload.put("channelId", channelId);
        }
        return command("sendTextMessage", payload, requestId);
    }

    public static String sendServerMessage(String message, String requestId) {
        requireText(message, MAX_TEXT_MESSAGE, "消息内容不能为空且不能超过 500 个字符");
        return command("sendServerMessage", Json.object("message", message), requestId);
    }

    public static String sendPrivateMessage(int clientId, String message, String clientUid, String requestId) {
        requireClientId(clientId);
        requireText(message, MAX_TEXT_MESSAGE, "消息内容不能为空且不能超过 500 个字符");
        Map<String, Object> payload = Json.object("clientId", clientId, "message", message);
        if (clientUid != null && !clientUid.isEmpty()) {
            if (clientUid.length() > 128) throw new IllegalArgumentException("成员身份标识无效");
            payload.put("clientUid", clientUid);
        }
        return command("sendPrivateMessage", payload, requestId);
    }

    public static String poke(int clientId, String message, String requestId) {
        requireClientId(clientId);
        if (message == null) message = "";
        if (message.length() > MAX_POKE_MESSAGE) throw new IllegalArgumentException("戳一戳消息不能超过 200 个字符");
        return command("poke", Json.object("clientId", clientId, "message", message), requestId);
    }

    public static String setAway(boolean away, String message, String requestId) {
        Map<String, Object> payload = Json.object("away", away);
        if (message != null && !message.isEmpty()) {
            if (message.length() > MAX_AWAY_MESSAGE) throw new IllegalArgumentException("离开状态消息不能超过 200 个字符");
            payload.put("message", message);
        }
        return command("setAway", payload, requestId);
    }

    public static String setWhisperTargets(int[] targetIds) {
        if (targetIds == null) targetIds = new int[0];
        if (targetIds.length > MAX_WHISPER_TARGETS) throw new IllegalArgumentException("私语目标最多 8 个");
        java.util.LinkedHashSet<Integer> unique = new java.util.LinkedHashSet<>();
        for (int clientId : targetIds) {
            requireClientId(clientId);
            if (!unique.add(clientId)) throw new IllegalArgumentException("私语目标不能重复");
        }
        return command("setWhisperTargets", Json.object("targetIds", targetIds), null);
    }

    public static String setWhisperActive(boolean active) {
        return command("setWhisperActive", Json.object("active", active), null);
    }

    public static String setMicrophoneMuted(boolean muted) {
        return command("setMicrophoneMuted", Json.object("muted", muted), null);
    }

    public static String setMemberVolume(int clientId, float volume, String requestId) {
        requireClientId(clientId);
        if (!(volume >= 0f && volume <= 4f)) throw new IllegalArgumentException("成员音量必须在 0 到 4 之间");
        // The wire type is a number; Float would serialize without the fractional
        // part and JSON.parse still yields a Double on the gateway.
        return command("setMemberVolume", Json.object("clientId", clientId, "volume", (double) volume), requestId);
    }

    public static String audioStatsProbe(String sequence) {
        requireSequence(sequence);
        return command("audioStatsProbe", Json.object("sequence", sequence), null);
    }

    public static String latencyProbe(String sequence) {
        requireSequence(sequence);
        return command("latencyProbe", Json.object("sequence", sequence), null);
    }

    /** 20 ms of 48 kHz mono Int16 PCM, exactly the frame the browser sends. */
    public static final int PCM_FRAME_BYTES = 1_920;

    private static void requireText(String message, int max, String error) {
        if (message == null || message.trim().isEmpty() || message.length() > max) {
            throw new IllegalArgumentException(error);
        }
    }

    private static void requireChannelId(String channelId) {
        if (channelId == null || !channelId.matches("\\d{1,20}")) throw new IllegalArgumentException("频道标识无效");
    }

    private static void requireClientId(int clientId) {
        if (clientId <= 0 || clientId > 65_535) throw new IllegalArgumentException("成员标识无效");
    }

    private static void requireSequence(String sequence) {
        if (sequence == null || sequence.length() > 64) throw new IllegalArgumentException("采样标识无效");
    }
}
