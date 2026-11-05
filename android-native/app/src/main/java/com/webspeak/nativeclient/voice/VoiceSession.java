package com.webspeak.nativeclient.voice;

import com.webspeak.nativeclient.core.ChatEntry;
import com.webspeak.nativeclient.core.Directory;
import com.webspeak.nativeclient.core.ErrorText;
import com.webspeak.nativeclient.core.Json;
import com.webspeak.nativeclient.core.Member;
import com.webspeak.nativeclient.core.PokeEvent;
import com.webspeak.nativeclient.core.ServerEventEntry;
import com.webspeak.nativeclient.core.ServerMessages;
import com.webspeak.nativeclient.core.SessionConfig;
import com.webspeak.nativeclient.core.Wire;
import com.webspeak.nativeclient.net.HttpCall;
import com.webspeak.nativeclient.net.WebSocketClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One browser-equivalent voice session: join ticket, WebSocket, commands, chat and
 * the compatibility audio path.
 *
 * <p>This class intentionally mirrors the invariants of the web client:
 * a session owns its socket and command deadlines; late callbacks from a replaced
 * attempt never touch newer state; a reconnect keeps the socket but releases audio
 * and command acknowledgements; the microphone only runs once the gateway reports
 * a ready TeamSpeak session and only through the gateway's 20 ms PCM contract.
 *
 * <p>Everything here is plain Java. The Android layer supplies the audio engine,
 * the main-thread dispatcher and the UI; tests supply fakes. All public callbacks
 * run through {@link Dispatcher}.
 */
public final class VoiceSession {

    public interface Listener {
        void onState(VoiceState state);

        void onServerInfo(String name, String welcomeMessage);

        void onDirectoryChanged();

        void onChat(ChatEntry entry);

        void onServerEvent(ServerEventEntry event);

        void onPoke(PokeEvent poke);

        void onWhisperTargets(int[] targetIds, boolean active);

        /** Non-fatal notices (audio degradation, rejected background command, ...). */
        void onNotice(String message);
    }

    private static final int MAX_EVENTS = 200;
    private static final int MAX_CHAT = 500;

    private final SessionConfig config;
    private final Dispatcher dispatcher;
    private final AudioBridge audio;
    private final Listener listener;
    private final Directory directory = new Directory();
    private final List<ChatEntry> chat = Collections.synchronizedList(new ArrayList<>());
    private final List<ServerEventEntry> events = Collections.synchronizedList(new ArrayList<>());
    private final Map<Integer, Float> memberVolumes = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicLong requestSequence = new AtomicLong();
    private final ExecutorService io;
    private final ScheduledExecutorService scheduler;

    private int generation;
    private VoiceState state = VoiceState.idle();
    private WebSocketClient socket;
    private HttpCall ticketCall;
    private ScheduledFuture<?> ticketWatchdog;
    private ScheduledFuture<?> connectWatchdog;
    private boolean failureDelivered;
    private int selfId;
    private String serverName = "";
    private String welcomeMessage = "";
    private String identity = "";
    private boolean muted;
    private boolean unsupportedCodecNotified;
    private int[] whisperTargetIds = new int[0];
    private boolean whisperActive;
    private boolean foreground = true;
    private boolean destroyed;

    public VoiceSession(SessionConfig config, Dispatcher dispatcher, AudioBridge audio, Listener listener) {
        this.config = config;
        this.dispatcher = dispatcher;
        this.audio = audio;
        this.listener = listener;
        this.io = Executors.newSingleThreadExecutor(daemonFactory("voice-io"));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory("voice-timer"));
    }

    public SessionConfig config() {
        return config;
    }

    public Directory directory() {
        return directory;
    }

    public synchronized VoiceState state() {
        return state;
    }

    public synchronized int selfId() {
        return selfId;
    }

    public synchronized String serverName() {
        return serverName;
    }

    public synchronized String welcomeMessage() {
        return welcomeMessage;
    }

    public synchronized String identity() {
        return identity;
    }

    public synchronized boolean isConnected() {
        return state.phase == VoiceState.Phase.CONNECTED;
    }

    public synchronized boolean isMuted() {
        return muted;
    }

    public synchronized int[] whisperTargetIds() {
        return whisperTargetIds.clone();
    }

    public synchronized boolean whisperActive() {
        return whisperActive;
    }

    public float memberVolume(int clientId) {
        Float volume = memberVolumes.get(clientId);
        return volume == null ? 1f : volume;
    }

    public List<ChatEntry> chatHistory() {
        synchronized (chat) {
            return Collections.unmodifiableList(new ArrayList<>(chat));
        }
    }

    public List<ServerEventEntry> serverEvents() {
        synchronized (events) {
            return Collections.unmodifiableList(new ArrayList<>(events));
        }
    }

    public void clearChatHistory() {
        chat.clear();
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts a fresh ticket + socket attempt. A no-op while an attempt is active. */
    public void connect() {
        final int gen;
        synchronized (this) {
            if (destroyed) return;
            if (state.phase == VoiceState.Phase.TICKET || state.phase == VoiceState.Phase.CONNECTING
                    || state.phase == VoiceState.Phase.CONNECTED || state.phase == VoiceState.Phase.RECONNECTING) {
                return;
            }
            gen = ++generation;
            failureDelivered = false;
            unsupportedCodecNotified = false;
            selfId = 0;
            serverName = "";
            welcomeMessage = "";
            whisperTargetIds = new int[0];
            whisperActive = false;
            directory.reset();
            chat.clear();
            events.clear();
            memberVolumes.clear();
            audio.clearMemberVolumes();
            failAllPending("语音连接已重新开始");            setStateLocked(VoiceState.Phase.TICKET, "正在申请会话票据…", 0);
        }
        dispatchState();
        ticketWatchdog = scheduler.schedule(() -> {
            HttpCall call;
            synchronized (this) {
                if (gen != generation || state.phase != VoiceState.Phase.TICKET) return;
                call = ticketCall;
            }
            if (call != null) call.abort();
            fail(gen, "REQUEST_TIMEOUT", null);
        }, config.ticketTimeoutMs, TimeUnit.MILLISECONDS);
        io.execute(() -> requestTicket(gen));
    }

    /** User-visible disconnect. Safe to call repeatedly. */
    public void disconnect() {
        WebSocketClient previous;
        synchronized (this) {
            if (destroyed) return;
            generation++;
            previous = socket;
            socket = null;
            cancelWatchdogsLocked();
            ticketCall = null;
            setStateLocked(VoiceState.Phase.CLOSED, "已断开连接", 0);
        }
        releaseAudio();
        failAllPending("语音连接已关闭");
        if (previous != null) previous.close(1000, "client disconnect");
        dispatchState();
    }

    /** Releases executors after {@link #disconnect()}; the session cannot be reused. */
    public void destroy() {
        disconnect();
        HttpCall pendingTicket;
        synchronized (this) {
            destroyed = true;
            pendingTicket = ticketCall;
            ticketCall = null;
        }
        if (pendingTicket != null) pendingTicket.abort();
        scheduler.shutdownNow();
        io.shutdownNow();
        audio.release();
    }

    // ------------------------------------------------------------------ ticket + socket

    private void requestTicket(int gen) {
        HttpCall call;
        try {
            call = new HttpCall("POST", config.gatewayUrl + "/api/join-ticket", config.trustSelfSigned);
        } catch (IllegalArgumentException error) {
            fail(gen, "GATEWAY_UNAVAILABLE", null);
            return;
        }
        call.header("Accept", "application/json");
        call.header("Content-Type", "application/json");
        // The gateway validates the browser origin; a native client must present the
        // same value the browser would send, which is the gateway's own origin.
        call.header("Origin", config.origin());
        synchronized (this) {
            if (gen != generation) return;
            ticketCall = call;
        }
        HttpCall.Result result;
        try {
            result = call.execute(config.ticketBody());
        } catch (IOException error) {
            fail(gen, isTlsFailure(error) ? "TLS_ERROR" : "REQUEST_FAILED", null);
            return;
        } finally {
            synchronized (this) {
                if (ticketCall == call) ticketCall = null;
            }
        }
        if (!isCurrent(gen)) return;
        String ticket = null;
        String voiceEndpoint = null;
        String failureCode = null;
        String failureDetail = null;
        try {
            Map<String, Object> body = Json.asObject(Json.parse(result.body));
            if (body != null) {
                ticket = Json.string(body, "ticket");
                voiceEndpoint = Json.string(body, "voiceEndpoint");
                failureCode = Json.string(body, "code");
                failureDetail = Json.string(body, "detail");
            }
        } catch (Json.JsonException ignored) {
            // A non-JSON error page keeps the HTTP status as the failure signal.
        }
        if (ticket == null || ticket.isEmpty()) {
            String code = failureCode != null ? failureCode : "REQUEST_FAILED";
            fail(gen, code, failureDetail != null ? failureDetail : "HTTP " + result.status);
            return;
        }

        WebSocketClient client;
        try {
            client = new WebSocketClient(config.voiceSocketUrl(ticket, voiceEndpoint), config.trustSelfSigned,
                    new SocketListener(gen));
        } catch (IllegalArgumentException error) {
            fail(gen, "GATEWAY_UNAVAILABLE", error.getMessage());
            return;
        }
        synchronized (this) {
            if (gen != generation) {
                client.close(1000, "stale");
                return;
            }
            socket = client;
            cancelTicketWatchdogLocked();
            if (state.phase == VoiceState.Phase.TICKET) {
                setStateLocked(VoiceState.Phase.CONNECTING, "正在连接语音网关…", 0);
            }
        }
        dispatchState();
        try {
            client.connect(10_000);
        } catch (IOException error) {
            fail(gen, isTlsFailure(error) ? "TLS_ERROR" : "GATEWAY_UNAVAILABLE", null);
            return;
        }
        synchronized (this) {
            if (gen != generation) {
                client.close(1000, "stale");
                return;
            }
            if (state.phase != VoiceState.Phase.CONNECTING) {
                // A concurrent failure or `connected` already settled this attempt.
                return;
            }
            setStateLocked(VoiceState.Phase.CONNECTING, "正在等待 TeamSpeak 握手…", 0);
            connectWatchdog = scheduler.schedule(() -> {
                synchronized (VoiceSession.this) {
                    if (gen != generation || state.phase != VoiceState.Phase.CONNECTING) return;
                }
                fail(gen, "GATEWAY_TIMEOUT", null);
            }, config.connectTimeoutMs, TimeUnit.MILLISECONDS);
        }
        dispatchState();
    }

    private final class SocketListener implements WebSocketClient.Listener {
        private final int gen;

        SocketListener(int gen) {
            this.gen = gen;
        }

        @Override
        public void onOpen() {
            // The gateway publishes `connected` after its TeamSpeak handshake.
        }

        @Override
        public void onText(String text) {
            if (!isCurrent(gen)) return;
            ServerMessages.Message message = ServerMessages.parse(text);
            if (message != null) handleMessage(gen, message);
        }

        @Override
        public void onBinary(byte[] data) {
            if (!isCurrent(gen)) return;
            handleAudio(data);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            if (!isCurrent(gen)) return;
            synchronized (VoiceSession.this) {
                if (failureDelivered || state.phase == VoiceState.Phase.CLOSED) return;
            }
            failWithMessage(gen, ErrorText.forClose(code, reason));
        }

        @Override
        public void onError(Throwable error) {
            // The close callback that follows carries the actionable message.
        }
    }

    // ------------------------------------------------------------------ inbound messages

    private void handleMessage(int gen, ServerMessages.Message message) {
        switch (message.type) {
            case CONNECTED: {
                ServerMessages.Connected connected = (ServerMessages.Connected) message;
                boolean capture;
                synchronized (this) {
                    if (gen != generation) return;
                    failureDelivered = false;
                    selfId = connected.tsClientId;
                    if (connected.identity != null && !connected.identity.isEmpty()) identity = connected.identity;
                    whisperTargetIds = connected.whisperTargetIds.clone();
                    whisperActive = connected.whisperActive;
                    cancelWatchdogsLocked();
                    directory.reset();
                    directory.setSelfId(selfId);
                    directory.applyMembers(connected.members);
                    synchronized (events) {
                        events.clear();
                        for (ServerEventEntry event : connected.serverEventLog) events.add(event);
                        trimEvents();
                    }
                    setStateLocked(VoiceState.Phase.CONNECTED, "已连接", 0);
                    capture = foreground && !muted;
                }
                audio.setMuted(muted);
                audio.setOutputVolume(1f);
                if (capture) audio.startCapture(this::sendPcmFrame);
                try {
                    sendQuietly(Wire.setMicrophoneMuted(muted));
                } catch (IllegalStateException ignored) {
                    // The socket closed while the state was being published.
                }
                dispatchState();
                dispatch(() -> listener.onDirectoryChanged());
                dispatch(() -> listener.onWhisperTargets(whisperTargetIds, whisperActive));
                break;
            }
            case SERVER_INFO: {
                ServerMessages.ServerInfo info = (ServerMessages.ServerInfo) message;
                synchronized (this) {
                    if (gen != generation) return;
                    serverName = info.name;
                    welcomeMessage = info.welcomeMessage == null ? "" : info.welcomeMessage;
                    setStateLocked(state.phase, state.message, state.reconnectAttempt);
                }
                dispatchState();
                dispatch(() -> listener.onServerInfo(info.name, info.welcomeMessage == null ? "" : info.welcomeMessage));
                break;
            }
            case CHANNEL_LIST: {
                ServerMessages.ChannelList list = (ServerMessages.ChannelList) message;
                synchronized (this) {
                    if (gen != generation) return;
                    directory.applyChannels(list.channels);
                    setStateLocked(state.phase == VoiceState.Phase.CONNECTED ? VoiceState.Phase.CONNECTED : state.phase,
                            state.message, state.reconnectAttempt);
                }
                dispatchState();
                dispatch(() -> listener.onDirectoryChanged());
                break;
            }
            case MEMBER_ENTER: {
                ServerMessages.MemberEnter enter = (ServerMessages.MemberEnter) message;
                synchronized (this) {
                    if (gen != generation) return;
                    directory.memberEnter(enter.member);
                }
                dispatch(() -> listener.onDirectoryChanged());
                break;
            }
            case MEMBER_LEAVE: {
                ServerMessages.MemberLeave leave = (ServerMessages.MemberLeave) message;
                synchronized (this) {
                    if (gen != generation) return;
                    directory.memberLeave(leave.id);
                }
                dispatch(() -> listener.onDirectoryChanged());
                break;
            }
            case CHAT_MESSAGE: {
                ServerMessages.ChatMessage chatMessage = (ServerMessages.ChatMessage) message;
                synchronized (this) {
                    if (gen != generation) return;
                    if (chatMessage.invokerId != null && chatMessage.invokerId == selfId) return;
                }
                String scope = normalizeScope(chatMessage.scope);
                ChatEntry entry = new ChatEntry(scope, chatMessage.targetId,
                        chatMessage.invokerId == null ? 0 : chatMessage.invokerId,
                        chatMessage.senderUid, chatMessage.invokerName, chatMessage.message,
                        chatMessage.timestamp == null ? System.currentTimeMillis() : chatMessage.timestamp,
                        false, "private".equals(scope) ? chatMessage.invokerName : null);
                appendChat(entry);
                break;
            }
            case POKE_RECEIVED: {
                ServerMessages.PokeReceived poke = (ServerMessages.PokeReceived) message;
                PokeEvent event = new PokeEvent(poke.invokerId == null ? 0 : poke.invokerId, poke.invokerUid,
                        poke.invokerName, poke.message, poke.timestamp == null ? System.currentTimeMillis() : poke.timestamp);
                dispatch(() -> listener.onPoke(event));
                break;
            }
            case SERVER_EVENT: {
                ServerMessages.ServerEvent event = (ServerMessages.ServerEvent) message;
                synchronized (events) {
                    events.add(event.event);
                    trimEvents();
                }
                dispatch(() -> listener.onServerEvent(event.event));
                break;
            }
            case CHANNEL_SWITCHED: {
                ServerMessages.ChannelSwitched switched = (ServerMessages.ChannelSwitched) message;
                if (switched.requestId != null) settleSuccess(switched.requestId);
                synchronized (this) {
                    if (gen != generation) return;
                    setStateLocked(state.phase, state.message, state.reconnectAttempt);
                }
                dispatchState();
                dispatch(() -> listener.onDirectoryChanged());
                break;
            }
            case WHISPER_TARGETS: {
                ServerMessages.WhisperTargets targets = (ServerMessages.WhisperTargets) message;
                synchronized (this) {
                    whisperTargetIds = targets.targetIds.clone();
                    whisperActive = targets.active;
                }
                int[] ids = targets.targetIds.clone();
                boolean active = targets.active;
                dispatch(() -> listener.onWhisperTargets(ids, active));
                break;
            }
            case COMMAND_COMPLETED: {
                if (message.requestId != null) settleSuccess(message.requestId);
                break;
            }
            case ERROR: {
                ServerMessages.Error error = (ServerMessages.Error) message;
                if (message.requestId != null) {
                    settleError(message.requestId, error.code, error.message);
                } else {
                    dispatch(() -> listener.onNotice(ErrorText.forOperation(error.code, error.message)));
                }
                break;
            }
            case DISCONNECTED: {
                ServerMessages.Disconnected disconnected = (ServerMessages.Disconnected) message;
                releaseAudio();
                synchronized (this) {
                    if (gen != generation) return;
                    if (disconnected.recoverable) {
                        setStateLocked(VoiceState.Phase.RECONNECTING, "TeamSpeak 连接中断，正在重连…", 0);
                    } else {
                        failureDelivered = true;
                        setStateLocked(VoiceState.Phase.FAILED, "TeamSpeak 连接已断开", 0);
                    }
                }
                failAllPending("TeamSpeak 连接已断开");
                dispatchState();
                break;
            }
            case RECONNECTING: {
                ServerMessages.Reconnecting reconnecting = (ServerMessages.Reconnecting) message;
                releaseAudio();
                synchronized (this) {
                    if (gen != generation) return;
                    int attempt = reconnecting.attempt > 0 ? reconnecting.attempt : state.reconnectAttempt + 1;
                    setStateLocked(VoiceState.Phase.RECONNECTING,
                            "TeamSpeak 连接中断，正在重连（第 " + attempt + " 次）…", attempt);
                }
                failAllPending("TeamSpeak 连接正在重建");
                dispatchState();
                break;
            }
            case RECONNECTED: {
                synchronized (this) {
                    if (gen != generation) return;
                    setStateLocked(VoiceState.Phase.CONNECTED, "已重新连接", 0);
                }
                dispatchState();
                break;
            }
            case CONNECTION_FAILED:
            case RECONNECT_FAILED: {
                ServerMessages.Failure failure = (ServerMessages.Failure) message;
                fail(gen, failure.code == null ? "CONNECTION_FAILED" : failure.code, failure.detail);
                break;
            }
            case AUDIO_ERROR: {
                ServerMessages.Failure failure = (ServerMessages.Failure) message;
                String text = ErrorText.forOperation(failure.code, failure.detail);
                dispatch(() -> listener.onNotice(text));
                break;
            }
            case WEBRTC_ERROR: {
                ServerMessages.Failure failure = (ServerMessages.Failure) message;
                dispatch(() -> listener.onNotice(ErrorText.forOperation(failure.code, failure.detail)));
                break;
            }
            default:
                // Directory avatars, audio statistics and WebRTC-only messages are
                // intentionally ignored by this client.
                break;
        }
    }

    private void handleAudio(byte[] packet) {
        // Compatibility downlink: [codec:1][clientId:2 big endian][opus payload].
        if (packet.length < 4) return;
        int codec = packet[0] & 0xFF;
        // This client (like the browser's WebCodecs path) decodes Opus only. A
        // legacy TeamSpeak client may still send Speex/CELP; dropping those frames
        // is honest, decoding them as Opus would produce noise.
        if (codec != 4 && codec != 5) {
            boolean notify = false;
            synchronized (this) {
                if (!unsupportedCodecNotified) {
                    unsupportedCodecNotified = true;
                    notify = true;
                }
            }
            if (notify) {
                String text = "收到非 Opus 语音帧（codec " + codec + "），已跳过；该成员可能使用旧版编解码器";
                dispatch(() -> listener.onNotice(text));
            }
            return;
        }
        int clientId = ((packet[1] & 0xFF) << 8) | (packet[2] & 0xFF);
        int self;
        synchronized (this) {
            self = selfId;
        }
        if (clientId == self || clientId <= 0) return;
        byte[] opus = Arrays.copyOfRange(packet, 3, packet.length);
        audio.playOpus(clientId, opus);
    }

    /** Capture callback: the frame is already exactly 20 ms of 48 kHz mono Int16. */
    private void sendPcmFrame(byte[] frame) {
        if (frame == null || frame.length != Wire.PCM_FRAME_BYTES) return;
        WebSocketClient current;
        synchronized (this) {
            if (state.phase != VoiceState.Phase.CONNECTED) return;
            current = socket;
        }
        if (current == null || !current.isOpen()) return;
        try {
            current.sendBinary(frame);
        } catch (IOException ignored) {
            // The reader thread observes the socket failure and reports it once.
        }
    }

    // ------------------------------------------------------------------ commands

    public void setMuted(boolean value) {
        boolean changed;
        boolean connected;
        boolean captureAllowed;
        synchronized (this) {
            changed = muted != value;
            muted = value;
            connected = state.phase == VoiceState.Phase.CONNECTED;
            captureAllowed = connected && foreground;
        }
        audio.setMuted(value);
        if (captureAllowed) {
            // Closing the capture device while muted avoids an active microphone
            // indicator for a client that is not transmitting.
            if (value) audio.stopCapture();
            else audio.startCapture(this::sendPcmFrame);
        }
        if (changed && connected) {
            try {
                sendQuietly(Wire.setMicrophoneMuted(value));
            } catch (IllegalStateException ignored) {
                // The socket closed between the state check and the send.
            }
        }
    }

    public void setOutputVolume(float volume) {
        audio.setOutputVolume(Math.max(0f, Math.min(2f, volume)));
    }

    /**
     * Keeps microphone capture tied to a visible screen. The Android client has no
     * foreground service yet, so capturing while the room is in the background
     * would only surprise the user (and the OS may suspend the process anyway).
     */
    public void setForeground(boolean active) {
        boolean connected;
        boolean mutedNow;
        synchronized (this) {
            foreground = active;
            connected = state.phase == VoiceState.Phase.CONNECTED;
            mutedNow = muted;
        }
        if (!connected) return;
        if (active && !mutedNow) audio.startCapture(this::sendPcmFrame);
        else audio.stopCapture();
    }

    public void setMemberVolume(int clientId, float volume) {
        float clamped = Math.max(0f, Math.min(4f, volume));
        memberVolumes.put(clientId, clamped);
        audio.setMemberVolume(clientId, clamped);
        synchronized (this) {
            if (state.phase != VoiceState.Phase.CONNECTED) return;
        }
        try {
            sendQuietly(Wire.setMemberVolume(clientId, clamped, null));
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            // Only affects the local mixer when the gateway is not connected or the
            // WebRTC volume path is unavailable; this client mixes locally.
        }
    }

    public void switchChannel(String channelId, String password, CommandCallback callback) {
        withAck(callback, requestId -> Wire.switchChannel(channelId, password, requestId));
    }

    public void moveClient(int clientId, String channelId, CommandCallback callback) {
        withAck(callback, requestId -> Wire.moveClient(clientId, channelId, requestId));
    }

    public void sendChannelMessage(String text, String channelId, CommandCallback callback) {
        String body = text == null ? "" : text.trim();
        withAck(callback, requestId -> Wire.sendTextMessage(body, channelId, requestId),
                () -> appendChat(new ChatEntry("channel", channelId, selfId, null, ownNickname(), body,
                        System.currentTimeMillis(), true, null)));
    }

    public void sendServerMessage(String text, CommandCallback callback) {
        String body = text == null ? "" : text.trim();
        withAck(callback, requestId -> Wire.sendServerMessage(body, requestId),
                () -> appendChat(new ChatEntry("server", null, selfId, null, ownNickname(), body,
                        System.currentTimeMillis(), true, null)));
    }

    public void sendPrivateMessage(int clientId, String text, CommandCallback callback) {
        String body = text == null ? "" : text.trim();
        Member recipient = directory.member(clientId);
        String uid = recipient == null ? null : recipient.uid;
        String name = recipient == null ? ("成员 " + clientId) : recipient.nickname;
        withAck(callback, requestId -> Wire.sendPrivateMessage(clientId, body, uid, requestId),
                () -> appendChat(new ChatEntry("private", Integer.toString(clientId), selfId, null, ownNickname(),
                        body, System.currentTimeMillis(), true, name)));
    }

    public void poke(int clientId, String text, CommandCallback callback) {
        withAck(callback, requestId -> Wire.poke(clientId, text == null ? "" : text.trim(), requestId));
    }

    public void setAway(boolean away, String message, CommandCallback callback) {
        withAck(callback, requestId -> Wire.setAway(away, message == null ? "" : message.trim(), requestId));
    }

    public void setWhisperTargets(int[] clientIds, CommandCallback callback) {
        try {
            sendQuietly(Wire.setWhisperTargets(clientIds));
            if (callback != null) dispatch(callback::onSuccess);
        } catch (IllegalArgumentException error) {
            if (callback != null) dispatch(() -> callback.onError("INVALID_WHISPER_TARGETS", error.getMessage()));
        } catch (IllegalStateException error) {
            if (callback != null) dispatch(() -> callback.onError("NOT_CONNECTED", "尚未连接到语音网关"));
        }
    }

    public void setWhisperActive(boolean active, CommandCallback callback) {
        try {
            sendQuietly(Wire.setWhisperActive(active));
            if (callback != null) dispatch(callback::onSuccess);
        } catch (IllegalStateException error) {
            if (callback != null) dispatch(() -> callback.onError("NOT_CONNECTED", "尚未连接到语音网关"));
        }
    }

    private String ownNickname() {
        Member self = directory.member(selfId());
        return self == null ? config.nickname : self.nickname;
    }

    private interface WireBuilder {
        String build(String requestId);
    }

    private void withAck(CommandCallback callback, WireBuilder builder) {
        withAck(callback, builder, null);
    }

    private void withAck(CommandCallback callback, WireBuilder builder, Runnable afterSuccess) {
        CommandCallback effective = callback == null ? new CommandCallback() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onError(String code, String message) {
            }
        } : callback;
        final String requestId;
        final String json;
        final int gen;
        boolean ready;
        synchronized (this) {
            ready = state.phase == VoiceState.Phase.CONNECTED && socket != null;
            gen = generation;
            requestId = "command-" + Long.toString(System.currentTimeMillis(), 36) + "-" + requestSequence.incrementAndGet();
        }
        if (!ready) {
            dispatch(() -> effective.onError("NOT_CONNECTED", ErrorText.forCode("NOT_CONNECTED", null)));
            return;
        }
        try {
            json = builder.build(requestId);
        } catch (IllegalArgumentException error) {
            dispatch(() -> effective.onError("INVALID_ARGUMENT", error.getMessage()));
            return;
        }
        pending.put(requestId, new Pending(effective, gen, afterSuccess));
        scheduler.schedule(() -> settleError(requestId, "TIMEOUT", "操作超时，请稍后重试"),
                config.commandTimeoutMs, TimeUnit.MILLISECONDS);
        try {
            sendQuietly(json);
        } catch (IllegalStateException error) {
            settleError(requestId, "SEND_FAILED", "语音连接尚未就绪");
        }
    }

    private void sendQuietly(String json) {
        WebSocketClient current;
        synchronized (this) {
            current = socket;
            if (current == null) throw new IllegalStateException("语音连接尚未就绪");
        }
        try {
            current.sendText(json);
        } catch (IOException error) {
            throw new IllegalStateException("语音连接尚未就绪", error);
        }
    }

    private void settleSuccess(String requestId) {
        Pending entry = pending.remove(requestId);
        if (entry == null) return;
        dispatch(() -> {
            if (entry.afterSuccess != null) entry.afterSuccess.run();
            entry.callback.onSuccess();
        });
    }

    private void settleError(String requestId, String code, String message) {
        Pending entry = pending.remove(requestId);
        if (entry == null) return;
        String text = ErrorText.forOperation(code, message);
        dispatch(() -> entry.callback.onError(code == null ? "OPERATION_FAILED" : code, text));
    }

    private void failAllPending(String reason) {
        if (pending.isEmpty()) return;
        List<Pending> entries = new ArrayList<>(pending.values());
        pending.clear();
        for (Pending entry : entries) {
            dispatch(() -> entry.callback.onError("SESSION_CLOSED", reason));
        }
    }

    // ------------------------------------------------------------------ helpers

    private boolean isCurrent(int gen) {
        synchronized (this) {
            return gen == generation && !destroyed;
        }
    }

    private void fail(int gen, String code, String detail) {
        failWithMessage(gen, ErrorText.forCode(code, detail));
    }

    private void failWithMessage(int gen, String message) {
        WebSocketClient previous;
        synchronized (this) {
            if (gen != generation) return;
            if (state.phase == VoiceState.Phase.CLOSED) return;
            failureDelivered = true;
            previous = socket;
            socket = null;
            cancelWatchdogsLocked();
            setStateLocked(VoiceState.Phase.FAILED, message, 0);
        }
        releaseAudio();
        failAllPending("语音连接失败");
        if (previous != null) previous.close(1000, "failed");
        dispatchState();
    }

    private void releaseAudio() {
        try {
            audio.stopCapture();
        } catch (RuntimeException ignored) {
            // Audio teardown must not abort the session teardown.
        }
        try {
            audio.stopPlayback();
        } catch (RuntimeException ignored) {
            // Same.
        }
    }

    private void appendChat(ChatEntry entry) {
        synchronized (chat) {
            chat.add(entry);
            while (chat.size() > MAX_CHAT) chat.remove(0);
        }
        dispatch(() -> listener.onChat(entry));
    }

    private void trimEvents() {
        while (events.size() > MAX_EVENTS) events.remove(0);
    }

    private static String normalizeScope(String scope) {
        if ("channel".equals(scope) || "server".equals(scope) || "private".equals(scope)) return scope;
        return "system";
    }

    private static boolean isTlsFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof javax.net.ssl.SSLException) return true;
            current = current.getCause();
        }
        return false;
    }

    private void setStateLocked(VoiceState.Phase phase, String message, int attempt) {
        state = new VoiceState(phase, message, selfId, serverName, directory.selfChannelName(), attempt);
    }

    private void dispatchState() {
        VoiceState snapshot;
        synchronized (this) {
            snapshot = state;
        }
        dispatch(() -> listener.onState(snapshot));
    }

    private void dispatch(Runnable action) {
        try {
            dispatcher.post(action);
        } catch (RuntimeException ignored) {
            // A dispatched UI callback must never take the session down.
        }
    }

    private void cancelWatchdogsLocked() {
        cancelTicketWatchdogLocked();
        if (connectWatchdog != null) {
            connectWatchdog.cancel(false);
            connectWatchdog = null;
        }
    }

    private void cancelTicketWatchdogLocked() {
        if (ticketWatchdog != null) {
            ticketWatchdog.cancel(false);
            ticketWatchdog = null;
        }
    }

    private static ThreadFactory daemonFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class Pending {
        final CommandCallback callback;
        final int generation;
        final Runnable afterSuccess;

        Pending(CommandCallback callback, int generation, Runnable afterSuccess) {
            this.callback = callback;
            this.generation = generation;
            this.afterSuccess = afterSuccess;
        }
    }
}
