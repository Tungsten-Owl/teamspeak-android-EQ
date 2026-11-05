package com.webspeak.ts3.core;

import com.github.manevolent.ts3j.api.Client;
import com.github.manevolent.ts3j.event.ChannelCreateEvent;
import com.github.manevolent.ts3j.event.ChannelDeletedEvent;
import com.github.manevolent.ts3j.event.ChannelDescriptionEditedEvent;
import com.github.manevolent.ts3j.event.ChannelEditedEvent;
import com.github.manevolent.ts3j.event.ChannelListEvent;
import com.github.manevolent.ts3j.event.ChannelMovedEvent;
import com.github.manevolent.ts3j.event.ChannelPasswordChangedEvent;
import com.github.manevolent.ts3j.event.ClientJoinEvent;
import com.github.manevolent.ts3j.event.ClientLeaveEvent;
import com.github.manevolent.ts3j.event.ClientMovedEvent;
import com.github.manevolent.ts3j.event.ClientPokeEvent;
import com.github.manevolent.ts3j.event.ClientUpdatedEvent;
import com.github.manevolent.ts3j.event.ConnectedEvent;
import com.github.manevolent.ts3j.event.DisconnectedEvent;
import com.github.manevolent.ts3j.event.ServerEditedEvent;
import com.github.manevolent.ts3j.event.TS3Listener;
import com.github.manevolent.ts3j.event.TextMessageEvent;
import com.github.manevolent.ts3j.command.Command;
import com.github.manevolent.ts3j.command.SingleCommand;
import com.github.manevolent.ts3j.command.parameter.CommandSingleParameter;
import com.github.manevolent.ts3j.identity.LocalIdentity;
import com.github.manevolent.ts3j.protocol.ProtocolRole;
import com.github.manevolent.ts3j.protocol.client.ClientConnectionState;
import com.github.manevolent.ts3j.protocol.packet.PacketBody2Command;
import com.github.manevolent.ts3j.protocol.socket.client.AbstractTeamspeakClientSocket;
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One direct TeamSpeak 3 session.
 *
 * <p>The directory comes from the server's welcome sequence and live
 * notifications. Guest clients are not allowed to run {@code channellist} or
 * {@code clientlist} on a default server, so this class never issues those
 * commands: it listens to the channel list rows and client enter/leave events
 * the server sends after {@code clientinit}.
 */
public final class Ts3Session {
    static {
        // ts3j's 30 s silence timeout made a dropped link look alive for half a
        // minute. The client pings once per second, so 6 s is already several
        // missed pongs and still leaves room for a short lossy burst.
        AbstractTeamspeakClientSocket.TIMEOUT = 6_000L;
    }

    /**
     * Delay after a file transfer socket closes, before the next
     * {@code ftinitdownload} may be issued.
     *
     * <p>The server releases its single transfer slot when it observes the
     * socket close, not when the last byte leaves the client. Firing the next
     * request straight away makes it answer "already in progress", so a batch
     * of avatar downloads used to yield exactly one image.
     */
    private static final long TRANSFER_SETTLE_MS = 250L;

    public enum State {
        DISCONNECTED,
        CONNECTING,
        RECONNECTING,
        CONNECTED,
        ERROR
    }

    public interface Listener {
        void onStateChanged(State state, String detail);

        void onDirectoryChanged();

        void onChatMessage(ChatMessage message);

        void onSessionEvent(SessionEvent event);

        /**
         * A client entered the channel this client is currently in.
         *
         * <p>Default no-op: the connection screen and the desktop probes never
         * show a room, so they have nothing to react to. It also fires only for
         * live notifications, never for the roster the welcome sequence replays.
         */
        default void onMemberJoined(MemberInfo member) {
        }

        /**
         * A client that was in this client's channel left it.
         *
         * @param disconnected {@code true} when the client dropped off the server,
         *                     {@code false} when it merely switched to another
         *                     channel
         */
        default void onMemberLeft(MemberInfo member, boolean disconnected) {
        }
    }

    private final Object lock = new Object();
    private final Map<Integer, ChannelInfo> channels = new LinkedHashMap<>();
    private final Map<Integer, MemberInfo> members = new LinkedHashMap<>();
    private final List<ChatMessage> chatHistory = new ArrayList<>();
    private final List<SessionEvent> events = new ArrayList<>();

    private final Executor callbackExecutor;
    private final VoiceEngine voiceEngine;

    private volatile State state = State.DISCONNECTED;
    private volatile Listener listener;
    private volatile LocalTeamspeakClientSocket client;
    private volatile LocalTeamspeakClientSocket connectingSocket;
    private volatile int selfId;
    private volatile String identityText = "";
    private volatile Ts3Config config;
    private volatile TcpUdpRelay relay;
    private volatile TS3Listener socketListener;
    private final AtomicLong connectionGeneration = new AtomicLong();
    private final Object connectionLock = new Object();
    private volatile boolean userDisconnect;
    private volatile boolean hadConnection;
    private volatile CountDownLatch disconnectLatch;
    private volatile int reconnectAttempt;
    private volatile int lastChannelId;
    private volatile String lastChannelPassword = "";
    /**
     * True once the welcome sequence and the first clientlist have been merged.
     * Until then a client notification is part of the initial roster, not a real
     * arrival, so callers must not be told about it.
     */
    private volatile boolean liveMembers;
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private volatile ScheduledFuture<?> reconnectFuture;
    private final ScheduledExecutorService reconnectExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ts3-reconnect");
                thread.setDaemon(true);
                return thread;
            });
    private final ScheduledExecutorService watchdogExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ts3-watchdog");
                thread.setDaemon(true);
                return thread;
            });
    /** Serialises per-client detail queries so a join burst cannot flood the socket. */
    private final ExecutorService memberInfoExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ts3-memberinfo");
                thread.setDaemon(true);
                return thread;
            });

    public Ts3Session(Executor callbackExecutor, VoiceEngine voiceEngine) {
        this.callbackExecutor = callbackExecutor;
        this.voiceEngine = voiceEngine;
        watchdogExecutor.scheduleWithFixedDelay(this::watchdogTick, 1, 1, TimeUnit.SECONDS);
    }

    private void watchdogTick() {
        try {
            if (state != State.CONNECTED || !hadConnection || userDisconnect) return;
            LocalTeamspeakClientSocket socket = client;
            if (socket != null && socket.getState() == ClientConnectionState.DISCONNECTED) {
                // ts3j reaches this state after 30 s of silence without firing a
                // disconnect notification; the watchdog turns it into a retry.
                handleConnectionLost("Connection timed out", false);
            }
        } catch (Throwable ignored) {
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public State getState() {
        return state;
    }

    public int getSelfId() {
        return selfId;
    }

    public String getIdentityText() {
        return identityText;
    }

    public Ts3Config getConfig() {
        return config;
    }

    public VoiceEngine getVoiceEngine() {
        return voiceEngine;
    }

    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    public void connect(Ts3Config config) throws Exception {
        if (isConnected()) throw new IllegalStateException("already connected");
        CountDownLatch pendingDisconnect = disconnectLatch;
        if (pendingDisconnect != null) {
            // A fast Leave -> Connect must not overlap the old UDP session,
            // otherwise the server briefly sees two clients with the same UID.
            try {
                pendingDisconnect.await(2_500L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            disconnectLatch = null;
        }
        userDisconnect = false;
        hadConnection = false;
        reconnectAttempt = 0;
        cancelReconnect();
        openConnection(config, false);
    }

    /** Called by the Android network callback when connectivity returns. */
    public void reconnectNow() {
        if (userDisconnect || !hadConnection) return;
        if (state == State.RECONNECTING) {
            // Abort a connect attempt that may be stuck on the old interface,
            // then retry immediately on the network that just came up.
            connectionGeneration.incrementAndGet();
            cancelReconnect();
            LocalTeamspeakClientSocket connecting = connectingSocket;
            if (connecting != null) {
                try {
                    connecting.close();
                } catch (Throwable ignored) {
                }
            }
            reconnectAttempt = 0;
            scheduleReconnect();
        } else if (state == State.CONNECTED) {
            handleConnectionLost("Network changed", false);
        }
    }

    private void openConnection(Ts3Config config, boolean reconnect) throws Exception {
        final long generation = connectionGeneration.incrementAndGet();
        if (reconnect && userDisconnect) throw new java.io.IOException("reconnect cancelled");
        LocalTeamspeakClientSocket stale = null;
        TS3Listener staleListener = null;
        synchronized (lock) {
            if (client != null) {
                if (state == State.CONNECTED) {
                    throw new IllegalStateException("already connected");
                }
                stale = client;
                staleListener = socketListener;
                socketListener = null;
                client = null;
            }
            this.config = config;
            state = reconnect ? State.RECONNECTING : State.CONNECTING;
            // Every connect replays the roster; re-arm the suppression so the
            // first clientlist of this attempt is not mistaken for arrivals.
            liveMembers = false;
        }
        if (stale != null) retireSocket(stale, staleListener);
        closeRelay();
        fireState(state, (reconnect ? "Reconnecting to " : "Connecting to ")
                + config.host + ":" + config.port);

        LocalTeamspeakClientSocket socket = null;
        try {
            LocalIdentity identity = Ts3Identity.load(config.identity);
            identityText = identity.export();

            socket = new LocalTeamspeakClientSocket();
            connectingSocket = socket;
            socket.setIdentity(identity);
            socket.setNickname(config.nickname.isEmpty() ? "Android TS3" : config.nickname);
            if (!config.serverPassword.isEmpty()) {
                // clientinit carries base64(SHA1(password)), not the clear password.
                socket.setOption("client.server_password",
                        com.github.manevolent.ts3j.util.Ts3Crypt.hashPassword(config.serverPassword));
            }
            if (!config.channel.isEmpty()) {
                socket.setOption("client.default_channel", config.channel);
            }
            if (!config.privilegeKey.isEmpty()) {
                socket.setOption("client.default_token", config.privilegeKey);
            }
            EventAdapter adapter = new EventAdapter(socket);
            socketListener = adapter;
            socket.addListener(adapter);

            synchronized (lock) {
                client = socket;
            }
            voiceEngine.attachReceiver(socket);

            InetSocketAddress remote;
            if (config.usesRelay()) {
                TcpUdpRelay bridge = new TcpUdpRelay(config.relayHost, config.relayPort,
                        config.relayToken, config.host, config.port);
                int localPort = bridge.start();
                relay = bridge;
                remote = new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), localPort);
                postEvent(SessionEvent.INFO, "Relay " + config.relayHost + ":"
                        + config.relayPort + " -> " + config.host + ":" + config.port);
            } else {
                remote = new InetSocketAddress(config.host, config.port);
            }

            socket.connect(remote, null, 10_000L);
            if (generation != connectionGeneration.get() || userDisconnect) {
                throw new java.io.IOException("connection cancelled");
            }

            // Attach the microphone only after the socket is connected: ts3j
            // starts its 20 ms microphone timer when setMicrophone() sees a
            // live state. Attaching earlier created an unstarted timer that a
            // second attach would reuse, so no audio was ever sent.
            synchronized (connectionLock) {
                if (generation != connectionGeneration.get() || userDisconnect) {
                    throw new java.io.IOException("connection cancelled");
                }
                voiceEngine.attach(socket);
            }
            if (generation != connectionGeneration.get() || userDisconnect) {
                throw new java.io.IOException("connection cancelled");
            }

            selfId = socket.getClientId();
            // The welcome notification for our own client does not always
            // include ctid; without this query the UI can count ourselves but
            // still place us in channel 0, leaving every channel count at 0.
            try {
                Client selfInfo = socket.getClientInfo(selfId);
                if (selfInfo != null) {
                    synchronized (lock) {
                        MemberInfo self = memberOf(selfInfo.getMap(), members.get(selfId));
                        self.id = selfId;
                        members.put(selfId, self);
                    }
                }
            } catch (Throwable error) {
                postEvent(SessionEvent.WARNING, "Could not query own client info: "
                        + describe(error));
            }
            int currentChannel = currentChannelId();
            if (lastChannelId > 0 && currentChannel != lastChannelId) {
                try {
                    socket.joinChannel(lastChannelId,
                            lastChannelPassword.isEmpty() ? null : lastChannelPassword);
                } catch (Throwable error) {
                    postEvent(SessionEvent.WARNING, "Could not rejoin channel "
                            + lastChannelId + ": " + describe(error));
                }
            } else if (lastChannelId == 0) {
                lastChannelId = currentChannel;
            }
            dedupeSelf();
            if (generation != connectionGeneration.get() || userDisconnect) {
                throw new java.io.IOException("connection cancelled");
            }
            state = State.CONNECTED;
            hadConnection = true;
            reconnectAttempt = 0;
            if (connectingSocket == socket) connectingSocket = null;
            postEvent(SessionEvent.INFO, "Connected as " + socket.getNickname()
                    + " (client " + selfId + ", uid " + selfUid() + ")");
            fireState(State.CONNECTED, "Connected");
            notifyDirectory();
            // Run after the preferred-channel rejoin: joining a channel can
            // reset the client's channel subscriptions.
            startChannelSubscription(socket, generation);
        } catch (Exception error) {
            boolean current = generation == connectionGeneration.get() && !userDisconnect;
            state = current
                    ? (reconnect ? State.RECONNECTING : State.ERROR)
                    : State.DISCONNECTED;
            if (socket != null) {
                if (socketListener != null) {
                    try {
                        socket.removeListener(socketListener);
                    } catch (Throwable ignored) {
                    }
                    socketListener = null;
                }
                try {
                    voiceEngine.detach(socket);
                } catch (Throwable ignored) {
                }
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
            synchronized (lock) {
                if (client == socket) client = null;
            }
            if (connectingSocket == socket) connectingSocket = null;
            closeRelay();
            if (current) {
                postEvent(SessionEvent.ERROR, "Connection failed: " + describe(error));
                fireState(state, describe(error));
                throw error;
            }
            if (!reconnect) throw new java.io.IOException("connection cancelled");
        }
    }

    public void disconnect() {
        userDisconnect = true;
        hadConnection = false;
        connectionGeneration.incrementAndGet();
        cancelReconnect();
        LocalTeamspeakClientSocket socket;
        TS3Listener listener;
        synchronized (lock) {
            socket = client;
            client = null;
            listener = socketListener;
            socketListener = null;
        }
        LocalTeamspeakClientSocket connecting = connectingSocket;
        connectingSocket = null;
        if (connecting != null && connecting != socket) {
            try {
                connecting.close();
            } catch (Throwable ignored) {
            }
        }
        if (socket != null && listener != null) {
            try {
                socket.removeListener(listener);
            } catch (Throwable ignored) {
            }
        }
        closeRelay();
        if (socket == null) {
            state = State.DISCONNECTED;
            fireState(State.DISCONNECTED, "Disconnected");
            return;
        }
        try {
            voiceEngine.detach(socket);
        } catch (Throwable ignored) {
        }

        // ts3j's disconnect() sends clientdisconnect and then waits up to 30 s
        // for the server's state change. Doing that on the UI thread made the
        // Leave button appear to do nothing (and could keep the app on screen
        // for half a minute). Detach the UI state immediately and finish the
        // protocol exchange on daemon threads.
        final CountDownLatch disconnectComplete = new CountDownLatch(1);
        disconnectLatch = disconnectComplete;
        state = State.DISCONNECTED;
        postEvent(SessionEvent.INFO, "Disconnected");
        fireState(State.DISCONNECTED, "Disconnected");

        Thread worker = new Thread(() -> {
            try {
                if (socket.getState() == ClientConnectionState.CONNECTED) {
                    socket.disconnect();
                } else {
                    // ts3j's disconnect() sends nothing when its local state is
                    // already DISCONNECTED, but the server may still hold the
                    // client during a reconnect race. Send the command anyway.
                    sendClientDisconnect(socket);
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
                disconnectComplete.countDown();
            }
        }, "ts3-disconnect");
        worker.setDaemon(true);
        worker.start();

        // If the server or network never acknowledges clientdisconnect, close
        // the UDP socket and wake any pending state wait. The command was
        // already written by disconnect(); this only bounds the teardown.
        Thread timeout = new Thread(() -> {
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                disconnectComplete.countDown();
                return;
            }
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
            try {
                socket.setState(ClientConnectionState.DISCONNECTED);
            } catch (Throwable ignored) {
            }
            disconnectComplete.countDown();
        }, "ts3-disconnect-timeout");
        timeout.setDaemon(true);
        timeout.start();
    }

    private void cancelReconnect() {
        reconnectScheduled.set(false);
        ScheduledFuture<?> future = reconnectFuture;
        reconnectFuture = null;
        if (future != null) future.cancel(false);
    }

    private void scheduleReconnect() {
        if (userDisconnect || !hadConnection) return;
        if (!reconnectScheduled.compareAndSet(false, true)) return;
        int attempt = ++reconnectAttempt;
        if (attempt > 10) {
            reconnectScheduled.set(false);
            state = State.ERROR;
            postEvent(SessionEvent.ERROR, "Reconnect gave up after 10 attempts");
            fireState(State.ERROR, "Reconnect failed");
            return;
        }
        long delay = Math.min(8_000L, 250L << Math.min(4, attempt - 1));
        postEvent(SessionEvent.WARNING, "Reconnecting in " + delay + "ms");
        reconnectFuture = reconnectExecutor.schedule(() -> {
            reconnectFuture = null;
            reconnectScheduled.set(false);
            if (userDisconnect) return;
            try {
                openConnection(config, true);
            } catch (Throwable ignored) {
                scheduleReconnect();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    public void sendChannelMessage(String message) throws Exception {
        LocalTeamspeakClientSocket socket = requireClient();
        int channelId = currentChannelId();
        socket.sendChannelMessage(channelId, message);
        ChatMessage outgoing = new ChatMessage();
        outgoing.targetMode = ChatMessage.TARGET_CHANNEL;
        outgoing.targetId = channelId;
        outgoing.invokerId = selfId;
        outgoing.invokerName = socket.getNickname();
        outgoing.invokerUid = selfUid();
        outgoing.message = message;
        outgoing.outgoing = true;
        addChat(outgoing);
    }

    public void sendServerMessage(String message) throws Exception {
        LocalTeamspeakClientSocket socket = requireClient();
        socket.sendServerMessage(message);
        ChatMessage outgoing = new ChatMessage();
        outgoing.targetMode = ChatMessage.TARGET_SERVER;
        outgoing.invokerId = selfId;
        outgoing.invokerName = socket.getNickname();
        outgoing.invokerUid = selfUid();
        outgoing.message = message;
        outgoing.outgoing = true;
        addChat(outgoing);
    }

    public void sendPrivateMessage(int clientId, String message) throws Exception {
        LocalTeamspeakClientSocket socket = requireClient();
        socket.sendPrivateMessage(clientId, message);
        ChatMessage outgoing = new ChatMessage();
        outgoing.targetMode = ChatMessage.TARGET_CLIENT;
        outgoing.targetId = clientId;
        outgoing.invokerId = selfId;
        outgoing.invokerName = socket.getNickname();
        outgoing.invokerUid = selfUid();
        outgoing.message = message;
        outgoing.outgoing = true;
        addChat(outgoing);
    }

    public void joinChannel(int channelId, String password) throws Exception {
        requireClient().joinChannel(channelId, password == null || password.isEmpty() ? null : password);
        lastChannelId = channelId;
        lastChannelPassword = password == null ? "" : password;
    }

    /**
     * Downloads a client avatar through channel 0's file area.
     *
     * <p>TeamSpeak 3 and 6 use the same transfer; only the UID length differs
     * (20 bytes vs 32 bytes), which {@link AvatarFileName} handles. Returns
     * null when the client has no avatar or the server refuses the transfer.
     */
    public byte[] downloadAvatar(MemberInfo member) throws Exception {
        if (member == null) return null;
        String fileName = AvatarFileName.forMember(member);
        if (fileName.isEmpty()) return null;

        LocalTeamspeakClientSocket socket = requireClient();
        Command command = new SingleCommand("ftinitdownload", ProtocolRole.CLIENT);
        int transferId = (int) (System.nanoTime() & 0xFFFF);
        if (transferId <= 0) transferId = 1;
        command.add(new CommandSingleParameter("clientftfid", Integer.toString(transferId)));
        command.add(new CommandSingleParameter("cid", "0"));
        command.add(new CommandSingleParameter("name", "/" + fileName));
        command.add(new CommandSingleParameter("cpw", ""));
        command.add(new CommandSingleParameter("seekpos", "0"));
        command.add(new CommandSingleParameter("proto", "0"));

        SingleCommand row = null;
        for (SingleCommand candidate : socket.executeCommand(command).get(10_000L)) {
            row = candidate;
            break;
        }
        if (row == null || !row.toMap().containsKey("ftkey")) {
            SingleCommand notification = socket.awaitFileTransferStart(transferId, 5_000L);
            if (notification != null) row = notification;
        }
        if (row == null) return null;
        Map<String, String> result = row.toMap();
        long size;
        try {
            size = Long.parseLong(result.getOrDefault("size", "0"));
        } catch (NumberFormatException error) {
            return null;
        }
        if (size <= 0 || size > 256 * 1024) return null;

        String key = result.get("ftkey");
        if (key == null || key.isEmpty()) return null;
        String host = result.get("ip");
        if (host == null || host.isEmpty() || "0.0.0.0".equals(host)) {
            host = config == null ? "" : config.host;
        }
        int port;
        try {
            port = Integer.parseInt(result.getOrDefault("port", "30033"));
        } catch (NumberFormatException error) {
            port = 30033;
        }
        if (port < 1 || port > 65535) return null;

        byte[] data;
        try (Socket transfer = new Socket()) {
            transfer.connect(new InetSocketAddress(host, port), 8_000);
            transfer.setSoTimeout(10_000);
            OutputStream output = transfer.getOutputStream();
            output.write(key.getBytes(StandardCharsets.US_ASCII));
            output.flush();
            data = new byte[(int) size];
            DataInputStream input = new DataInputStream(transfer.getInputStream());
            input.readFully(data);
        } catch (IOException error) {
            return null;
        }
        // Wait for the server to retire this transfer before the caller asks for
        // the next one; see TRANSFER_SETTLE_MS.
        try {
            Thread.sleep(TRANSFER_SETTLE_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return data;
    }

    public void poke(int clientId, String message) throws Exception {
        requireClient().clientPoke(clientId, message);
    }

    /**
     * Posts a line to the session event log.
     *
     * <p>The app layer uses this for diagnostics that would otherwise only be
     * visible in logcat, which is not reachable when the device is the only
     * thing running the build.
     */
    public void postDiagnostic(int level, String text) {
        postEvent(level, text);
    }

    /**
     * Fetches one client's details when a notification left something out.
     *
     * <p>{@code cliententerview} rows routinely omit {@code client_flag_avatar},
     * and the one-off sweep after the first clientlist only covers clients that
     * already existed at that moment. Anyone who joined later therefore kept the
     * initials avatar for the whole session, even though a clientinfo query
     * would have returned the marker.
     */
    private void refreshMemberAvatar(int clientId, LocalTeamspeakClientSocket socket) {
        memberInfoExecutor.execute(() -> {
            if (client != socket) return;
            try {
                Client info = socket.getClientInfo(clientId);
                if (info == null) return;
                boolean changed = false;
                synchronized (lock) {
                    MemberInfo member = members.get(clientId);
                    if (member == null) return;
                    String beforeKey = member.avatarKey == null ? "" : member.avatarKey;
                    String beforeUid = member.uniqueId == null ? "" : member.uniqueId;
                    memberOf(info.getMap(), member);
                    changed = !beforeKey.equals(member.avatarKey == null ? "" : member.avatarKey)
                            || !beforeUid.equals(member.uniqueId == null ? "" : member.uniqueId);
                }
                if (changed) notifyDirectory();
            } catch (Throwable error) {
                postEvent(SessionEvent.WARNING, "Client info for " + clientId
                        + " unavailable: " + describe(error));
            }
        });
    }

    public int currentChannelId() {
        synchronized (lock) {
            MemberInfo self = members.get(selfId);
            return self == null ? 0 : self.channelId;
        }
    }

    public List<ChannelInfo> getChannels() {
        synchronized (lock) {
            return new ArrayList<>(channels.values());
        }
    }

    public List<MemberInfo> getMembers() {
        synchronized (lock) {
            return new ArrayList<>(members.values());
        }
    }

    public MemberInfo getMember(int clientId) {
        synchronized (lock) {
            return members.get(clientId);
        }
    }

    public List<ChatMessage> getChatHistory() {
        synchronized (lock) {
            return new ArrayList<>(chatHistory);
        }
    }

    public List<SessionEvent> getEvents() {
        synchronized (lock) {
            return new ArrayList<>(events);
        }
    }

    private void closeRelay() {
        TcpUdpRelay current = relay;
        relay = null;
        if (current != null) current.close();
    }

    /**
     * Retires a replaced socket. The old server-side client must be told to
     * leave; merely closing our UDP socket leaves a visible ghost on the
     * server until its timeout. Sending the command is best-effort because the
     * old path may already be dead.
     */
    private void retireSocket(LocalTeamspeakClientSocket stale, TS3Listener listener) {
        if (stale == null) return;
        if (listener != null) {
            try {
                stale.removeListener(listener);
            } catch (Throwable ignored) {
            }
        }
        try {
            voiceEngine.detach(stale);
        } catch (Throwable ignored) {
        }
        Thread thread = new Thread(() -> {
            try {
                sendClientDisconnect(stale);
            } catch (Throwable ignored) {
            }
            try {
                stale.close();
            } catch (Throwable ignored) {
            }
        }, "ts3-retire");
        thread.setDaemon(true);
        thread.start();
    }

    private void sendClientDisconnect(LocalTeamspeakClientSocket socket)
            throws java.io.IOException, java.util.concurrent.TimeoutException {
        socket.writePacket(new PacketBody2Command(ProtocolRole.CLIENT,
                new SingleCommand("clientdisconnect", ProtocolRole.CLIENT)));
    }

    private LocalTeamspeakClientSocket requireClient() {        LocalTeamspeakClientSocket socket = client;
        if (socket == null || state != State.CONNECTED) {
            throw new IllegalStateException("not connected");
        }
        return socket;
    }

    private String selfUid() {
        synchronized (lock) {
            MemberInfo self = members.get(selfId);
            return self == null ? "" : self.uniqueId;
        }
    }

    private void dedupeSelf() {
        boolean changed = false;
        synchronized (lock) {
            MemberInfo self = members.get(selfId);
            if (self == null || self.uniqueId == null || self.uniqueId.isEmpty()) return;
            java.util.Iterator<Map.Entry<Integer, MemberInfo>> iterator =
                    members.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, MemberInfo> entry = iterator.next();
                if (entry.getKey() != selfId
                        && self.uniqueId.equals(entry.getValue().uniqueId)) {
                    iterator.remove();
                    changed = true;
                }
            }
        }
        if (changed) notifyDirectory();
    }

    /**
     * Some servers only push client-enter notifications for a visible subset.
     * A one-shot clientlist fills in the channel of every connected client when
     * the guest has permission; a refusal is harmless and leaves the
     * notification-built directory in place.
     */
    private void startChannelSubscription(LocalTeamspeakClientSocket socket, long generation) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(700L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
            if (generation != connectionGeneration.get() || this.client != socket
                    || userDisconnect) {
                return;
            }
            try {
                socket.subscribeAll();
                postEvent(SessionEvent.INFO, "Subscribed to all channels");
            } catch (Throwable error) {
                boolean alone;
                synchronized (lock) {
                    alone = members.size() <= 1;
                }
                if (alone) {
                    postEvent(SessionEvent.WARNING,
                            "Could not subscribe to all channels: " + describe(error));
                }
            }
            refreshClientList(socket, generation);
        }, "ts3-subscribe");
        thread.setDaemon(true);
        thread.start();
    }

    private void refreshClientList(LocalTeamspeakClientSocket socket, long generation) {
        Thread thread = new Thread(() -> {
            Throwable error = null;
            try {
                for (Client client : socket.listClients()) {
                    if (generation != connectionGeneration.get() || this.client != socket) {
                        return;
                    }
                    Map<String, String> map = client.getMap();
                    int id = intOf(map, "clid", client.getId());
                    if (id == 0) continue;
                    synchronized (lock) {
                        MemberInfo member = memberOf(map, members.get(id));
                        member.id = id;
                        members.put(id, member);
                    }
                }
            } catch (Throwable thrown) {
                error = thrown;
            }
            dedupeSelf();
            notifyDirectory();
            // The roster is complete now: from here on a client notification is
            // a genuine arrival or departure rather than a replayed member.
            liveMembers = generation == connectionGeneration.get() && this.client == socket;
            refreshMissingAvatarMetadata(socket, generation);
            boolean alone;
            synchronized (lock) {
                alone = members.size() <= 1;
            }
            if (alone && error != null) {
                postEvent(SessionEvent.WARNING,
                        "Client list unavailable: " + describe(error));
            } else if (alone) {
                postEvent(SessionEvent.WARNING,
                        "Client list returned only the local client; "
                                + "other channels may be hidden by server permissions");
            }
        }, "ts3-clientlist");
        thread.setDaemon(true);
        thread.start();
    }

    private void refreshMissingAvatarMetadata(LocalTeamspeakClientSocket socket, long generation) {
        List<MemberInfo> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(members.values());
        }
        boolean changed = false;
        for (MemberInfo existing : snapshot) {
            if (generation != connectionGeneration.get() || this.client != socket) return;
            if (existing.avatarKey != null && !existing.avatarKey.isEmpty()) continue;
            try {
                Client info = socket.getClientInfo(existing.id);
                if (info == null) continue;
                synchronized (lock) {
                    MemberInfo member = memberOf(info.getMap(), members.get(existing.id));
                    member.id = existing.id;
                    members.put(existing.id, member);
                }
                changed = true;
            } catch (Throwable ignored) {
                // b_client_info_view may be denied; the other members still load.
            }
        }
        if (changed) notifyDirectory();
    }

    private void addChat(ChatMessage message) {
        List<ChatMessage> copy;
        synchronized (lock) {
            chatHistory.add(message);
            while (chatHistory.size() > 250) chatHistory.remove(0);
            copy = new ArrayList<>(chatHistory);
        }
        postChat(message);
    }

    /** The server echoes a sender's own chat; keep the locally recorded line only once. */
    private boolean isDuplicateOutgoing(ChatMessage incoming) {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            for (int i = chatHistory.size() - 1; i >= 0 && i >= chatHistory.size() - 8; i--) {
                ChatMessage previous = chatHistory.get(i);
                if (!previous.outgoing) continue;
                if (previous.targetMode != incoming.targetMode) continue;
                if (previous.targetId != incoming.targetId) continue;
                if (!previous.message.equals(incoming.message)) continue;
                if (now - previous.timestamp > 3_000L) continue;
                return true;
            }
        }
        return false;
    }

    private void postEvent(int level, String text) {
        SessionEvent event = new SessionEvent(level, text);
        synchronized (lock) {
            events.add(event);
            while (events.size() > 250) events.remove(0);
        }
        final Listener current = listener;
        if (current != null) {
            callbackExecutor.execute(() -> current.onSessionEvent(event));
        }
    }

    private void fireState(State newState, String detail) {
        final Listener current = listener;
        if (current != null) {
            callbackExecutor.execute(() -> current.onStateChanged(newState, detail));
        }
    }

    private void postChat(ChatMessage message) {
        final Listener current = listener;
        if (current != null) {
            callbackExecutor.execute(() -> current.onChatMessage(message));
        }
    }

    private void fireMemberJoined(MemberInfo member) {
        final Listener current = listener;
        if (current != null) {
            callbackExecutor.execute(() -> current.onMemberJoined(member));
        }
    }

    private void fireMemberLeft(MemberInfo member, boolean disconnected) {
        final Listener current = listener;
        if (current != null) {
            callbackExecutor.execute(() -> current.onMemberLeft(member, disconnected));
        }
    }

    private void notifyDirectory() {
        synchronized (lock) {
            for (ChannelInfo channel : channels.values()) channel.memberCount = 0;
            for (MemberInfo member : members.values()) {
                ChannelInfo channel = channels.get(member.channelId);
                if (channel != null) channel.memberCount++;
            }
        }
        final Listener current = listener;
        if (current != null) {
            callbackExecutor.execute(current::onDirectoryChanged);
        }
        syncChannelAudio();
    }

    /**
     * Applies the current channel's codec and quality to the voice engine.
     *
     * <p>Every directory change runs this because a channel edit by an operator,
     * a move to another channel and a fresh welcome all arrive that way. The
     * engine ignores an unchanged profile, so the cost is a map lookup.
     */
    private void syncChannelAudio() {
        int channelId = currentChannelId();
        ChannelInfo channel;
        synchronized (lock) {
            channel = channels.get(channelId);
        }
        // Only Opus channels are honoured. A legacy Speex or CELT channel keeps
        // the default profile instead of asking for a bitrate it cannot carry.
        if (channel == null || channel.codec < 4) return;
        voiceEngine.setChannelProfile(channel.codec, channel.codecQuality);
    }

    private static String describe(Throwable error) {
        if (error == null) return "unknown error";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private final class EventAdapter implements TS3Listener {
        private final LocalTeamspeakClientSocket source;

        EventAdapter(LocalTeamspeakClientSocket source) {
            this.source = source;
        }

        private boolean active() {
            return client == source;
        }

        @Override
        public void onChannelList(ChannelListEvent event) {
            if (!active()) return;
            mergeChannel(event.getMap(), true);
            notifyDirectory();
        }

        @Override
        public void onClientJoin(ClientJoinEvent event) {
            if (!active()) return;
            Map<String, String> map = event.getMap();
            int id = intOf(map, "clid", 0);
            if (id == 0) id = event.getClientId();
            MemberInfo member = memberOf(map, null);
            member.id = id;
            boolean known;
            synchronized (lock) {
                known = members.containsKey(id);
                members.put(id, member);
            }
            dedupeSelf();
            notifyDirectory();
            // A newcomer's cliententerview usually carries no avatar marker. The
            // welcome sweep has already run by now, so ask for this client alone.
            if (liveMembers && (member.avatarKey == null || member.avatarKey.isEmpty())) {
                refreshMemberAvatar(id, source);
            }
            int myChannel = currentChannelId();
            if (liveMembers && !known && id != selfId && myChannel > 0
                    && member.channelId == myChannel) {
                fireMemberJoined(member);
            }
        }

        @Override
        public void onClientLeave(ClientLeaveEvent event) {
            if (!active()) return;
            Map<String, String> map = event.getMap();
            int id = intOf(map, "clid", 0);
            // ctid is the channel the client moved to; zero means it left the
            // server instead of switching channels.
            boolean disconnected = intOf(map, "ctid", 0) == 0;
            int myChannel = currentChannelId();
            MemberInfo removed;
            synchronized (lock) {
                removed = members.remove(id);
            }
            if (removed != null) {
                postEvent(SessionEvent.INFO, removed.nickname + " left");
                notifyDirectory();
                if (liveMembers && id != selfId && myChannel > 0
                        && removed.channelId == myChannel) {
                    fireMemberLeft(removed, disconnected);
                }
            }
        }

        @Override
        public void onClientMoved(ClientMovedEvent event) {
            if (!active()) return;
            Map<String, String> map = event.getMap();
            int id = intOf(map, "clid", event.getClientId());
            int target = intOf(map, "ctid", event.getTargetChannelId());
            int myChannel = currentChannelId();
            int previous;
            MemberInfo member;
            synchronized (lock) {
                member = members.get(id);
                previous = member == null ? 0 : member.channelId;
                if (member != null) member.channelId = target;
            }
            if (id == selfId) {
                lastChannelId = target;
                postEvent(SessionEvent.INFO, "Joined channel " + target);
            }
            notifyDirectory();
            // Switching channels arrives as a clientmoved, not as a leave plus a
            // join, so the prompts have to be driven from here as well; without
            // this branch only a fresh connect or a disconnect was ever heard.
            if (!liveMembers || member == null || id == selfId || myChannel <= 0) return;
            if (target == myChannel && previous != myChannel) {
                fireMemberJoined(member);
            } else if (previous == myChannel && target != myChannel) {
                fireMemberLeft(member, false);
            }
        }

        @Override
        public void onClientChanged(ClientUpdatedEvent event) {
            if (!active()) return;
            Map<String, String> map = event.getMap();
            int id = intOf(map, "clid", event.getClientId());
            synchronized (lock) {
                MemberInfo member = members.get(id);
                if (member != null) mergeMember(map, member);
            }
            dedupeSelf();
            notifyDirectory();
        }

        @Override
        public void onChannelCreate(ChannelCreateEvent event) {
            if (!active()) return;
            mergeChannel(event.getMap(), false);
            notifyDirectory();
        }

        @Override
        public void onChannelEdit(ChannelEditedEvent event) {
            if (!active()) return;
            mergeChannel(event.getMap(), false);
            notifyDirectory();
        }

        @Override
        public void onChannelDeleted(ChannelDeletedEvent event) {
            if (!active()) return;
            int id = intOf(event.getMap(), "cid", event.getChannelId());
            synchronized (lock) {
                channels.remove(id);
            }
            notifyDirectory();
        }

        @Override
        public void onChannelMoved(ChannelMovedEvent event) {
            if (!active()) return;
            mergeChannel(event.getMap(), false);
            notifyDirectory();
        }

        @Override
        public void onChannelDescriptionChanged(ChannelDescriptionEditedEvent event) {
            if (!active()) return;
            mergeChannel(event.getMap(), false);
            notifyDirectory();
        }

        @Override
        public void onChannelPasswordChanged(ChannelPasswordChangedEvent event) {
            if (!active()) return;
            Map<String, String> map = event.getMap();
            int id = intOf(map, "cid", 0);
            synchronized (lock) {
                ChannelInfo channel = channels.get(id);
                if (channel != null) channel.password = boolOf(map, "channel_flag_password", true);
            }
            notifyDirectory();
        }

        @Override
        public void onTextMessage(TextMessageEvent event) {
            if (!active()) return;
            ChatMessage message = new ChatMessage();
            message.targetMode = intOf(event.getMap(), "targetmode", ChatMessage.TARGET_CHANNEL);
            message.targetId = intOf(event.getMap(), "target", 0);
            message.invokerId = intOf(event.getMap(), "invokerid", event.getInvokerId());
            message.invokerName = strOf(event.getMap(), "invokername", event.getInvokerName());
            message.invokerUid = strOf(event.getMap(), "invokeruid", "");
            if (message.invokerUid.isEmpty()) {
                MemberInfo member = getMember(message.invokerId);
                if (member != null) message.invokerUid = member.uniqueId;
            }
            message.message = event.getMessage();
            if (message.invokerId == selfId) {
                message.outgoing = true;
                if (isDuplicateOutgoing(message)) return;
            }
            addChat(message);
        }

        @Override
        public void onClientPoke(ClientPokeEvent event) {
            if (!active()) return;
            Map<String, String> map = event.getMap();
            String invoker = strOf(map, "invokername", event.getInvokerName());
            String message = strOf(map, "msg", "");
            postEvent(SessionEvent.WARNING, invoker + " poked you: " + message);
        }

        @Override
        public void onServerEdit(ServerEditedEvent event) {
            if (!active()) return;
            postEvent(SessionEvent.INFO, "Server settings changed");
        }

        @Override
        public void onConnected(ConnectedEvent event) {
            if (!active()) return;
            postEvent(SessionEvent.INFO, "Handshake complete");
        }

        @Override
        public void onDisconnected(DisconnectedEvent event) {
            if (!active()) return;
            String reason = strOf(event.getMap(), "reasonmsg", "");
            String lower = reason.toLowerCase();
            int reasonId = intOf(event.getMap(), "reasonid", 0);
            boolean kicked = reasonId == 5 || reasonId == 6
                    || lower.contains("kick") || lower.contains("ban");
            handleConnectionLost(reason, kicked);
        }
    }

    private void handleConnectionLost(String reason, boolean terminal) {
        LocalTeamspeakClientSocket socket;
        boolean reconnect;
        synchronized (lock) {
            if (state == State.RECONNECTING) return;
            reconnect = hadConnection && !userDisconnect && !terminal;
            state = reconnect ? State.RECONNECTING : State.DISCONNECTED;
            socket = client;
            channels.clear();
            members.clear();
        }
        if (socket != null) {
            try {
                voiceEngine.detach(socket);
            } catch (Throwable ignored) {
            }
        }
        closeRelay();
        postEvent(SessionEvent.WARNING, "Disconnected" + (reason.isEmpty() ? "" : ": " + reason));
        fireState(state, reason.isEmpty()
                ? (reconnect ? "Connection lost, reconnecting…" : "Disconnected")
                : reason);
        if (reconnect) {
            scheduleReconnect();
        } else {
            hadConnection = false;
        }
        notifyDirectory();
    }

    private void mergeChannel(Map<String, String> map, boolean welcomeRow) {
        int id = intOf(map, "cid", 0);
        if (id == 0) return;
        synchronized (lock) {
            ChannelInfo channel = channels.get(id);
            if (channel == null) {
                channel = new ChannelInfo();
                channel.id = id;
                channels.put(id, channel);
            }
            channel.parentId = intOf(map, "cpid", channel.parentId);
            channel.order = intOf(map, "channel_order", channel.order);
            channel.name = strOf(map, "channel_name", channel.name);
            channel.topic = strOf(map, "channel_topic", channel.topic);
            channel.codec = intOf(map, "channel_codec", channel.codec);
            channel.codecQuality = intOf(map, "channel_codec_quality", channel.codecQuality);
            channel.maxClients = intOf(map, "channel_maxclients", channel.maxClients);
            channel.neededTalkPower = intOf(map, "channel_needed_talk_power", channel.neededTalkPower);
            channel.password = boolOf(map, "channel_flag_password", channel.password);
            channel.isDefault = boolOf(map, "channel_flag_default", channel.isDefault);
            channel.permanent = boolOf(map, "channel_flag_permanent", channel.permanent);
        }
    }

    private MemberInfo memberOf(Map<String, String> map, MemberInfo existing) {
        MemberInfo member = existing == null ? new MemberInfo() : existing;
        mergeMember(map, member);
        return member;
    }

    private void mergeMember(Map<String, String> map, MemberInfo member) {
        member.channelId = intOf(map, "ctid", intOf(map, "cid", member.channelId));
        member.nickname = strOf(map, "client_nickname", member.nickname);
        member.databaseId = intOf(map, "client_database_id", member.databaseId);
        member.uniqueId = strOf(map, "client_unique_identifier", member.uniqueId);
        member.avatarKey = strOf(map, "client_flag_avatar", member.avatarKey);
        member.avatarHash = strOf(map, "client_base64HashClientUID", member.avatarHash);
        member.type = intOf(map, "client_type", member.type);
        member.channelGroupId = intOf(map, "client_channel_group_id", member.channelGroupId);
        member.inputMuted = boolOf(map, "client_input_muted", member.inputMuted);
        member.outputMuted = boolOf(map, "client_output_muted", member.outputMuted);
        member.outputOnlyMuted = boolOf(map, "client_outputonly_muted", member.outputOnlyMuted);
        member.away = boolOf(map, "client_away", member.away);
        member.talking = boolOf(map, "client_is_talker", member.talking);
        member.prioritySpeaker = boolOf(map, "client_is_priority_speaker", member.prioritySpeaker);
        member.channelCommander = boolOf(map, "client_is_channel_commander", member.channelCommander);
        member.recording = boolOf(map, "client_is_recording", member.recording);
        member.description = strOf(map, "client_description", member.description);
        String groups = map.get("client_servergroups");
        if (groups != null && !groups.isEmpty()) {
            member.serverGroups = parseInts(groups);
        }
    }

    private static int[] parseInts(String text) {
        List<Integer> values = new ArrayList<>();
        for (String part : text.split("[,\\s]+")) {
            try {
                values.add(Integer.parseInt(part));
            } catch (NumberFormatException ignored) {
            }
        }
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    private static int intOf(Map<String, String> map, String key, int fallback) {
        String value = map.get(key);
        if (value == null || value.isEmpty()) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean boolOf(Map<String, String> map, String key, boolean fallback) {
        String value = map.get(key);
        if (value == null || value.isEmpty()) return fallback;
        return "1".equals(value);
    }

    private static String strOf(Map<String, String> map, String key, String fallback) {
        String value = map.get(key);
        return value == null ? fallback : value;
    }
}
