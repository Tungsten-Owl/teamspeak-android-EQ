package com.webspeak.nativeclient.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Typed projection of the gateway's public JSON messages.
 *
 * <p>The web client validates every message before it reaches UI state; this
 * parser enforces the same required fields so a malformed or future message is
 * dropped instead of corrupting the directory or chat history. Unknown message
 * types (for example screen sharing, which this client does not implement) are
 * ignored deliberately.
 */
public final class ServerMessages {

    public enum Type {
        CONNECTED, SERVER_INFO, MEMBER_ENTER, MEMBER_LEAVE, MEMBER_AVATAR, CHANNEL_LIST,
        CHAT_MESSAGE, POKE_RECEIVED, SERVER_EVENT, CHANNEL_SWITCHED, AUDIO_STATS, LATENCY_PONG,
        DISCONNECTED, RECONNECTING, RECONNECTED, CONNECTION_FAILED, RECONNECT_FAILED,
        AUDIO_ERROR, WEBRTC_ERROR, WHISPER_TARGETS, VOICE_ACTIVITY, COMMAND_COMPLETED, ERROR
    }

    /** Common envelope; {@code requestId} correlates acknowledged commands. */
    public abstract static class Message {
        public final Type type;
        public final String requestId;

        Message(Type type, String requestId) {
            this.type = type;
            this.requestId = requestId;
        }
    }

    public static final class Connected extends Message {
        public final int tsClientId;
        public final List<Member> members;
        public final List<ServerEventEntry> serverEventLog;
        public final boolean webrtcAvailable;
        public final String identity;
        public final int[] whisperTargetIds;
        public final boolean whisperActive;
        public final String pluginSession;

        Connected(String requestId, int tsClientId, List<Member> members, List<ServerEventEntry> log,
                  boolean webrtcAvailable, String identity, int[] whisperTargetIds, boolean whisperActive,
                  String pluginSession) {
            super(Type.CONNECTED, requestId);
            this.tsClientId = tsClientId;
            this.members = members;
            this.serverEventLog = log;
            this.webrtcAvailable = webrtcAvailable;
            this.identity = identity;
            this.whisperTargetIds = whisperTargetIds;
            this.whisperActive = whisperActive;
            this.pluginSession = pluginSession;
        }
    }

    public static final class ServerInfo extends Message {
        public final String name;
        public final String icon;
        public final String bannerUrl;
        public final String welcomeMessage;

        ServerInfo(String requestId, String name, String icon, String bannerUrl, String welcomeMessage) {
            super(Type.SERVER_INFO, requestId);
            this.name = name;
            this.icon = icon;
            this.bannerUrl = bannerUrl;
            this.welcomeMessage = welcomeMessage;
        }
    }

    public static final class MemberEnter extends Message {
        public final Member member;

        MemberEnter(String requestId, Member member) {
            super(Type.MEMBER_ENTER, requestId);
            this.member = member;
        }
    }

    public static final class MemberLeave extends Message {
        public final int id;

        MemberLeave(String requestId, int id) {
            super(Type.MEMBER_LEAVE, requestId);
            this.id = id;
        }
    }

    public static final class MemberAvatar extends Message {
        public final int id;
        public final String uid;
        public final String avatar;

        MemberAvatar(String requestId, int id, String uid, String avatar) {
            super(Type.MEMBER_AVATAR, requestId);
            this.id = id;
            this.uid = uid;
            this.avatar = avatar;
        }
    }

    public static final class ChannelList extends Message {
        public final List<ChannelInfo> channels;

        ChannelList(String requestId, List<ChannelInfo> channels) {
            super(Type.CHANNEL_LIST, requestId);
            this.channels = channels;
        }
    }

    public static final class ChatMessage extends Message {
        public final String message;
        public final Integer invokerId;
        public final String invokerName;
        public final Long timestamp;
        public final String scope;
        public final String senderUid;
        public final String targetId;

        ChatMessage(String requestId, String message, Integer invokerId, String invokerName, Long timestamp,
                    String scope, String senderUid, String targetId) {
            super(Type.CHAT_MESSAGE, requestId);
            this.message = message;
            this.invokerId = invokerId;
            this.invokerName = invokerName;
            this.timestamp = timestamp;
            this.scope = scope;
            this.senderUid = senderUid;
            this.targetId = targetId;
        }
    }

    public static final class PokeReceived extends Message {
        public final String message;
        public final Integer invokerId;
        public final String invokerName;
        public final Long timestamp;
        public final String invokerUid;

        PokeReceived(String requestId, String message, Integer invokerId, String invokerName, Long timestamp,
                     String invokerUid) {
            super(Type.POKE_RECEIVED, requestId);
            this.message = message;
            this.invokerId = invokerId;
            this.invokerName = invokerName;
            this.timestamp = timestamp;
            this.invokerUid = invokerUid;
        }
    }

    public static final class ServerEvent extends Message {
        public final ServerEventEntry event;

        ServerEvent(String requestId, ServerEventEntry event) {
            super(Type.SERVER_EVENT, requestId);
            this.event = event;
        }
    }

    public static final class ChannelSwitched extends Message {
        public final String channelId;

        ChannelSwitched(String requestId, String channelId) {
            super(Type.CHANNEL_SWITCHED, requestId);
            this.channelId = channelId;
        }
    }

    public static final class AudioStats extends Message {
        public final String sequence;
        public final Map<String, Object> stats;

        AudioStats(String requestId, String sequence, Map<String, Object> stats) {
            super(Type.AUDIO_STATS, requestId);
            this.sequence = sequence;
            this.stats = stats;
        }
    }

    public static final class LatencyPong extends Message {
        public final String sequence;
        public final Double latencyMs;
        public final boolean reachable;

        LatencyPong(String requestId, String sequence, Double latencyMs, boolean reachable) {
            super(Type.LATENCY_PONG, requestId);
            this.sequence = sequence;
            this.latencyMs = latencyMs;
            this.reachable = reachable;
        }
    }

    public static final class Disconnected extends Message {
        public final boolean recoverable;

        Disconnected(String requestId, boolean recoverable) {
            super(Type.DISCONNECTED, requestId);
            this.recoverable = recoverable;
        }
    }

    public static final class Reconnecting extends Message {
        public final int attempt;
        public final long delayMs;

        Reconnecting(String requestId, int attempt, long delayMs) {
            super(Type.RECONNECTING, requestId);
            this.attempt = attempt;
            this.delayMs = delayMs;
        }
    }

    public static final class Reconnected extends Message {
        Reconnected(String requestId) {
            super(Type.RECONNECTED, requestId);
        }
    }

    /** connectionFailed / reconnectFailed / audioError / webrtcError. */
    public static final class Failure extends Message {
        public final String code;
        public final String detail;

        Failure(Type type, String requestId, String code, String detail) {
            super(type, requestId);
            this.code = code;
            this.detail = detail;
        }
    }

    public static final class WhisperTargets extends Message {
        public final int[] targetIds;
        public final boolean active;

        WhisperTargets(String requestId, int[] targetIds, boolean active) {
            super(Type.WHISPER_TARGETS, requestId);
            this.targetIds = targetIds;
            this.active = active;
        }
    }

    public static final class VoiceActivity extends Message {
        public final int[] clientIds;

        VoiceActivity(String requestId, int[] clientIds) {
            super(Type.VOICE_ACTIVITY, requestId);
            this.clientIds = clientIds;
        }
    }

    public static final class CommandCompleted extends Message {
        CommandCompleted(String requestId) {
            super(Type.COMMAND_COMPLETED, requestId);
        }
    }

    public static final class Error extends Message {
        public final String code;
        public final String message;
        public final boolean recoverable;

        Error(String requestId, String code, String message, boolean recoverable) {
            super(Type.ERROR, requestId);
            this.code = code;
            this.message = message;
            this.recoverable = recoverable;
        }
    }

    private ServerMessages() {
    }

    /**
     * Parses one control frame.
     *
     * @return the typed message, or {@code null} when the frame is malformed or its
     *         type is not part of this client's supported surface.
     */
    public static Message parse(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        Object value;
        try {
            value = Json.parse(raw);
        } catch (Json.JsonException error) {
            return null;
        }
        Map<String, Object> record = Json.asObject(value);
        if (record == null) return null;
        String type = Json.string(record, "type");
        if (type == null) return null;
        String requestId = Json.string(record, "requestId");
        if (requestId != null && requestId.length() > 64) return null;

        switch (type) {
            case "connected": {
                Integer tsClientId = Json.integer(record, "tsClientId");
                if (tsClientId == null || !validClientId(tsClientId)) return null;
                List<Member> members = new ArrayList<>();
                List<Object> rawMembers = Json.array(record, "members");
                if (rawMembers != null) {
                    for (Object item : rawMembers) {
                        Member member = parseMember(Json.asObject(item));
                        if (member == null) return null;
                        members.add(member);
                    }
                }
                List<ServerEventEntry> events = new ArrayList<>();
                List<Object> rawEvents = Json.array(record, "serverEventLog");
                if (rawEvents != null) {
                    for (Object item : rawEvents) {
                        ServerEventEntry event = parseEvent(Json.asObject(item));
                        if (event == null) return null;
                        events.add(event);
                    }
                }
                String identity = Json.string(record, "identity");
                if (identity != null && identity.length() > 8192) return null;
                String pluginSession = Json.string(record, "pluginSession");
                if (pluginSession != null && !pluginSession.matches("[A-Za-z0-9_-]{43}")) return null;
                // The gateway always sends this list, but an older one may omit it.
                int[] whisperTargets = optionalIntArray(Json.array(record, "whisperTargetIds"));
                if (whisperTargets == null) return null;
                return new Connected(requestId, tsClientId, Collections.unmodifiableList(members),
                        Collections.unmodifiableList(events), Json.bool(record, "webrtcAvailable", false),
                        identity, whisperTargets, Json.bool(record, "whisperActive", false), pluginSession);
            }
            case "serverInfo": {
                String name = Json.string(record, "name");
                if (name == null || name.length() > 256) return null;
                String icon = Json.string(record, "icon");
                if (icon != null && icon.length() > 180_000) return null;
                String banner = Json.string(record, "bannerUrl");
                if (banner != null && !safeHttpUrl(banner)) return null;
                String welcome = Json.string(record, "welcomeMessage");
                if (welcome != null && welcome.length() > 4_096) return null;
                return new ServerInfo(requestId, name, icon, banner, welcome);
            }
            case "memberEnter": {
                Member member = parseMember(record);
                return member == null ? null : new MemberEnter(requestId, member);
            }
            case "memberLeave": {
                Integer id = Json.integer(record, "id");
                return id == null || !validClientId(id) ? null : new MemberLeave(requestId, id);
            }
            case "memberAvatar": {
                Integer id = Json.integer(record, "id");
                if (id == null || !validClientId(id)) return null;
                String uid = Json.string(record, "uid");
                String avatar = Json.string(record, "avatar");
                if (avatar != null && avatar.length() > 180_000) return null;
                return new MemberAvatar(requestId, id, uid, avatar);
            }
            case "channelList": {
                List<Object> rawChannels = Json.array(record, "channels");
                if (rawChannels == null) return null;
                List<ChannelInfo> channels = new ArrayList<>();
                for (Object item : rawChannels) {
                    ChannelInfo channel = parseChannel(Json.asObject(item));
                    if (channel == null) return null;
                    channels.add(channel);
                }
                return new ChannelList(requestId, Collections.unmodifiableList(channels));
            }
            case "chatMessage": {
                String message = Json.string(record, "message");
                if (message == null) return null;
                Integer invokerId = Json.integer(record, "invokerId");
                String invokerName = Json.string(record, "invokerName");
                Long timestamp = longOrNull(record, "timestamp");
                String scope = Json.string(record, "scope");
                String senderUid = Json.string(record, "senderUid");
                String targetId = channelIdString(record.get("targetId"));
                if (record.containsKey("targetId") && targetId == null) return null;
                return new ChatMessage(requestId, message, invokerId, invokerName, timestamp, scope, senderUid, targetId);
            }
            case "pokeReceived": {
                String message = Json.string(record, "message");
                if (message == null) return null;
                return new PokeReceived(requestId, message, Json.integer(record, "invokerId"),
                        Json.string(record, "invokerName"), longOrNull(record, "timestamp"),
                        Json.string(record, "invokerUid"));
            }
            case "serverEvent": {
                ServerEventEntry event = parseEvent(Json.object(record, "event"));
                return event == null ? null : new ServerEvent(requestId, event);
            }
            case "channelSwitched": {
                String channelId = channelIdString(record.get("channelId"));
                return channelId == null ? null : new ChannelSwitched(requestId, channelId);
            }
            case "audioStats": {
                String sequence = Json.string(record, "sequence");
                Map<String, Object> stats = Json.object(record, "stats");
                if (sequence == null || sequence.length() > 64 || stats == null) return null;
                return new AudioStats(requestId, sequence, stats);
            }
            case "latencyPong": {
                String sequence = Json.string(record, "sequence");
                if (sequence == null) return null;
                Double latency = null;
                Object rawLatency = record.get("teamSpeakLatencyMs");
                if (rawLatency instanceof Double) latency = (Double) rawLatency;
                else if (rawLatency != null) return null;
                Object reachable = record.get("teamSpeakReachable");
                if (!(reachable instanceof Boolean)) return null;
                return new LatencyPong(requestId, sequence, latency, (Boolean) reachable);
            }
            case "disconnected":
                return new Disconnected(requestId, Json.bool(record, "recoverable", false));
            case "reconnecting": {
                Integer attempt = Json.integer(record, "attempt");
                long delay = (long) Json.number(record, "delayMs", 0);
                return new Reconnecting(requestId, attempt == null ? 0 : attempt, delay);
            }
            case "reconnected":
                return new Reconnected(requestId);
            case "connectionFailed":
                return new Failure(Type.CONNECTION_FAILED, requestId, Json.string(record, "code"), Json.string(record, "detail"));
            case "reconnectFailed":
                return new Failure(Type.RECONNECT_FAILED, requestId, Json.string(record, "code"), Json.string(record, "detail"));
            case "audioError":
                return new Failure(Type.AUDIO_ERROR, requestId, Json.string(record, "code"), Json.string(record, "detail"));
            case "webrtcError":
                return new Failure(Type.WEBRTC_ERROR, requestId, Json.string(record, "code"), Json.string(record, "detail"));
            case "whisperTargets": {
                int[] targets = optionalIntArray(Json.array(record, "targetIds"));
                Object active = record.get("active");
                if (targets == null || !(active instanceof Boolean)) return null;
                return new WhisperTargets(requestId, targets, (Boolean) active);
            }
            case "voiceActivity": {
                List<Object> values = Json.array(record, "clientIds");
                if (values == null) return null;
                int[] ids = intArray(values);
                return ids == null ? null : new VoiceActivity(requestId, ids);
            }
            case "commandCompleted":
                return requestId == null ? null : new CommandCompleted(requestId);
            case "error": {
                Map<String, Object> detail = Json.object(record, "error");
                String code = detail == null ? null : Json.string(detail, "code");
                String message = detail == null ? Json.string(record, "message") : Json.string(detail, "message");
                boolean recoverable = detail != null && Json.bool(detail, "recoverable", false);
                if (message == null) message = "操作失败";
                return new Error(requestId, code == null ? "OPERATION_FAILED" : code, message, recoverable);
            }
            default:
                return null;
        }
    }

    public static Member parseMember(Map<String, Object> record) {
        if (record == null) return null;
        Integer id = Json.integer(record, "id");
        String nickname = Json.string(record, "nickname");
        if (id == null || !validClientId(id) || nickname == null) return null;
        for (String key : new String[]{"uid", "avatar", "awayMessage"}) {
            if (record.get(key) != null && !(record.get(key) instanceof String)) return null;
        }
        for (String key : new String[]{"isSelf", "away", "inputMuted", "outputMuted", "channelCommander"}) {
            if (record.get(key) != null && !(record.get(key) instanceof Boolean)) return null;
        }
        return new Member(id, nickname, Json.string(record, "uid"), Json.bool(record, "isSelf", false),
                Json.bool(record, "away", false), Json.string(record, "awayMessage"),
                Json.bool(record, "inputMuted", false), Json.bool(record, "outputMuted", false),
                Json.bool(record, "channelCommander", false));
    }

    public static ChannelInfo parseChannel(Map<String, Object> record) {
        if (record == null) return null;
        Object rawId = record.get("id");
        Object rawParent = record.get("parentID");
        String name = Json.string(record, "name");
        if (!(rawId instanceof String) || !isChannelId((String) rawId)) return null;
        if (!(rawParent instanceof String) || !isChannelId((String) rawParent)) return null;
        if (name == null) return null;
        String order = Json.string(record, "order");
        String description = Json.string(record, "description");
        Integer maxClients = Json.integer(record, "maxClients");
        Boolean maxClientsLimited = booleanOrNull(record.get("maxClientsLimited"));
        Boolean passwordProtected = booleanOrNull(record.get("passwordProtected"));
        Boolean permanent = booleanOrNull(record.get("isPermanent"));
        if ((maxClients == null) != (maxClientsLimited == null)) return null;
        if (maxClients != null && (maxClients < 0 || maxClients > 65_535)) return null;
        List<Member> members = null;
        List<Object> rawMembers = Json.array(record, "members");
        if (rawMembers != null) {
            members = new ArrayList<>(rawMembers.size());
            for (Object item : rawMembers) {
                Member member = parseMember(Json.asObject(item));
                if (member == null) return null;
                members.add(member);
            }
        }
        return new ChannelInfo((String) rawId, (String) rawParent, name, order, description,
                maxClients, maxClientsLimited, passwordProtected, permanent, members);
    }

    private static ServerEventEntry parseEvent(Map<String, Object> record) {
        if (record == null) return null;
        String id = Json.string(record, "id");
        String kind = Json.string(record, "kind");
        String message = Json.string(record, "message");
        Double timestamp = record.get("timestamp") instanceof Double ? (Double) record.get("timestamp") : null;
        if (id == null || kind == null || message == null || timestamp == null) return null;
        return new ServerEventEntry(id, kind, message, (long) (double) timestamp);
    }

    private static boolean validClientId(int id) {
        return id > 0 && id <= 65_535;
    }

    private static boolean isChannelId(String value) {
        return value != null && value.matches("\\d{1,20}");
    }

    private static String channelIdString(Object value) {
        if (value instanceof String) return isChannelId((String) value) ? (String) value : null;
        if (value instanceof Double) {
            double number = (Double) value;
            if (number == Math.rint(number) && number >= 0 && number <= 9_007_199_254_740_991d) {
                return Long.toString((long) number);
            }
        }
        return null;
    }

    private static Boolean booleanOrNull(Object value) {
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static Long longOrNull(Map<String, Object> record, String key) {
        Object value = record.get(key);
        if (!(value instanceof Double)) return null;
        return (long) (double) (Double) value;
    }

    /** Null when any element is invalid. */
    private static int[] intArray(List<Object> values) {
        if (values == null) return null;
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Object item = values.get(i);
            if (!(item instanceof Double)) return null;
            double number = (Double) item;
            if (number != Math.rint(number) || number < 1 || number > 65_535) return null;
            result[i] = (int) number;
        }
        return result;
    }

    /** Absent means empty; invalid content is rejected. */
    private static int[] optionalIntArray(List<Object> values) {
        return values == null ? new int[0] : intArray(values);
    }

    private static boolean safeHttpUrl(String value) {
        if (value.length() > 2_048) return false;
        try {
            java.net.URI uri = java.net.URI.create(value);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

}
