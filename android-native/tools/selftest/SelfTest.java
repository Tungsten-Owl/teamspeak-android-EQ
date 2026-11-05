package selftest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.webspeak.nativeclient.core.ChatEntry;
import com.webspeak.nativeclient.core.Directory;
import com.webspeak.nativeclient.core.ErrorText;
import com.webspeak.nativeclient.core.Json;
import com.webspeak.nativeclient.core.Member;
import com.webspeak.nativeclient.core.ServerMessages;
import com.webspeak.nativeclient.core.SessionConfig;
import com.webspeak.nativeclient.core.Wire;
import com.webspeak.nativeclient.net.WebSocketFrames;
import com.webspeak.nativeclient.voice.AudioBridge;
import com.webspeak.nativeclient.voice.CommandCallback;
import com.webspeak.nativeclient.voice.Dispatcher;
import com.webspeak.nativeclient.voice.PcmSink;
import com.webspeak.nativeclient.voice.VoiceSession;
import com.webspeak.nativeclient.voice.VoiceState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Desktop verification for the Android client's shared layers.
 *
 * <p>Runs without Android or a real TeamSpeak server: a local fake gateway serves
 * the join-ticket endpoint over HTTP and the voice protocol over a raw WebSocket
 * server, so the join flow, Origin requirement, audio framing, command
 * acknowledgements, timeouts, ping/pong and close-code mapping are exercised end
 * to end.
 */
public final class SelfTest {

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        jsonTests();
        frameTests();
        commandTests();
        messageTests();
        directoryTests();
        errorTests();
        sessionTests();
        rejectedTicketTests();

        System.out.println();
        System.out.println("通过 " + passed + " 项检查");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 项：");
            for (String failure : failures) System.out.println("  - " + failure);
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ JSON

    private static void jsonTests() {
        section("JSON");
        try {
            Object parsed = Json.parse("{\"a\":1,\"b\":[true,null,\"x\\ny\"],\"c\":{\"d\":-2.5e2}}");
            Map<String, Object> object = Json.asObject(parsed);
            check("object parsed", object != null);
            check("integer", Json.intOr(object, "a", -1) == 1);
            List<Object> array = Json.array(object, "b");
            check("array length", array != null && array.size() == 3);
            check("escaped newline", "x\ny".equals(array.get(2)));
            check("nested number", Json.number(Json.object(object, "c"), "d", 0) == -250d);
            String roundTrip = Json.stringify(parsed);
            Object reparsed = Json.parse(roundTrip);
            check("round trip", Json.stringify(reparsed).equals(roundTrip));
            check("quote escaping", Json.stringify("a\"b\\c").equals("\"a\\\"b\\\\c\""));
            check("control escaping", Json.stringify("\u0001").equals("\"\\u0001\""));
            expectThrows("trailing garbage", () -> Json.parse("{} x"));
            expectThrows("unterminated string", () -> Json.parse("\"abc"));
            expectThrows("unquoted key", () -> Json.parse("{a:1}"));
        } catch (Exception error) {
            fail("JSON tests threw " + error);
        }
    }

    // ------------------------------------------------------------------ frames

    private static void frameTests() {
        section("WebSocket frames");
        try {
            SecureRandom random = new SecureRandom();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] payload = new byte[300];
            for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
            WebSocketFrames.write(buffer, WebSocketFrames.OPCODE_BINARY, payload, true, random);
            WebSocketFrames.Frame frame = WebSocketFrames.read(
                    new java.io.ByteArrayInputStream(buffer.toByteArray()), 1024);
            check("binary frame round trip", frame != null && frame.opcode == WebSocketFrames.OPCODE_BINARY
                    && frame.fin && Arrays.equals(payload, frame.payload));

            buffer.reset();
            WebSocketFrames.write(buffer, WebSocketFrames.OPCODE_TEXT, "hello".getBytes(StandardCharsets.UTF_8), false, random);
            WebSocketFrames.Frame text = WebSocketFrames.read(new java.io.ByteArrayInputStream(buffer.toByteArray()), 1024);
            check("text frame length", text != null && text.payload.length == 5);

            buffer.reset();
            byte[] longPayload = new byte[70_000];
            WebSocketFrames.write(buffer, WebSocketFrames.OPCODE_BINARY, longPayload, true, random);
            WebSocketFrames.Frame large = WebSocketFrames.read(new java.io.ByteArrayInputStream(buffer.toByteArray()), 100_000);
            check("64-bit length frame", large != null && large.payload.length == 70_000);

            check("RFC accept vector", "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=".equals(
                    WebSocketFrames.acceptValue("dGhlIHNhbXBsZSBub25jZQ==")));

            buffer.reset();
            WebSocketFrames.writeClose(buffer, 4005, "IDENTITY_IN_USE", true, random);
            WebSocketFrames.Frame close = WebSocketFrames.read(new java.io.ByteArrayInputStream(buffer.toByteArray()), 1024);
            check("close code", WebSocketFrames.closeCode(close.payload, 0) == 4005);
            check("close reason", "IDENTITY_IN_USE".equals(WebSocketFrames.closeReason(close.payload)));

            expectThrows("oversized frame rejected", () -> WebSocketFrames.read(
                    new java.io.ByteArrayInputStream(buffer.toByteArray()), 10));
        } catch (Exception error) {
            fail("frame tests threw " + error);
        }
    }

    // ------------------------------------------------------------------ commands

    private static void commandTests() {
        section("Wire commands");
        try {
            Map<String, Object> switchChannel = parse(Wire.switchChannel("12", "pw", "req-1"));
            check("switchChannel type", "switchChannel".equals(switchChannel.get("type")));
            check("switchChannel requestId", "req-1".equals(switchChannel.get("requestId")));
            Map<String, Object> payload = Json.object(switchChannel, "payload");
            check("switchChannel channelId", "12".equals(Json.string(payload, "channelId")));
            check("switchChannel password", "pw".equals(Json.string(payload, "password")));

            Map<String, Object> text = parse(Wire.sendTextMessage("你好", "9", "req-2"));
            check("text message keeps channel guard", "9".equals(
                    Json.string(Json.object(text, "payload"), "channelId")));

            Map<String, Object> whisper = parse(Wire.setWhisperTargets(new int[]{9, 10}));
            check("whisper has no requestId", !whisper.containsKey("requestId"));
            check("whisper targets", String.valueOf(Json.stringify(whisper.get("payload")))
                    .contains("9") && String.valueOf(Json.stringify(whisper.get("payload"))).contains("10"));

            Map<String, Object> volume = parse(Wire.setMemberVolume(9, 1.5f, null));
            check("volume is a JSON number", Json.object(volume, "payload").get("volume") instanceof Double);

            expectThrows("empty chat rejected", () -> Wire.sendTextMessage("   ", null, null));
            expectThrows("channel id rejected", () -> Wire.switchChannel("abc", null, null));
            expectThrows("client id rejected", () -> Wire.poke(0, "x", null));
            expectThrows("duplicate whisper rejected", () -> Wire.setWhisperTargets(new int[]{9, 9}));
            expectThrows("too many whisper targets", () -> Wire.setWhisperTargets(
                    new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9}));
        } catch (Exception error) {
            fail("command tests threw " + error);
        }
    }

    // ------------------------------------------------------------------ messages

    private static void messageTests() {
        section("Server messages");
        try {
            ServerMessages.Message connected = ServerMessages.parse("{\"type\":\"connected\",\"tsClientId\":7,"
                    + "\"members\":[{\"id\":7,\"nickname\":\"me\",\"isSelf\":true}],\"whisperTargetIds\":[9],"
                    + "\"whisperActive\":false}");
            check("connected parsed", connected instanceof ServerMessages.Connected);
            ServerMessages.Connected typed = (ServerMessages.Connected) connected;
            check("connected self id", typed.tsClientId == 7);
            check("connected whisper targets", typed.whisperTargetIds.length == 1);

            check("channel list parsed", ServerMessages.parse("{\"type\":\"channelList\",\"channels\":["
                    + "{\"id\":\"1\",\"parentID\":\"0\",\"name\":\"Lobby\",\"members\":[]}]}")
                    instanceof ServerMessages.ChannelList);
            check("chat parsed", ServerMessages.parse("{\"type\":\"chatMessage\",\"invokerId\":9,"
                    + "\"invokerName\":\"A\",\"message\":\"hi\"}") instanceof ServerMessages.ChatMessage);
            check("error parsed", ServerMessages.parse("{\"type\":\"error\",\"error\":{\"code\":\"X\",\"message\":\"m\"}}")
                    instanceof ServerMessages.Error);
            check("malformed connected dropped", ServerMessages.parse("{\"type\":\"connected\"}") == null);
            check("bad channel id dropped", ServerMessages.parse("{\"type\":\"channelList\",\"channels\":["
                    + "{\"id\":\"x\",\"parentID\":\"0\",\"name\":\"A\"}]}") == null);
            check("unknown type ignored", ServerMessages.parse("{\"type\":\"screenShareList\",\"streams\":[]}") == null);
            check("bad json ignored", ServerMessages.parse("{oops") == null);
            check("oversized request id dropped", ServerMessages.parse(
                    "{\"type\":\"commandCompleted\",\"requestId\":\"" + repeat('a', 65) + "\"}") == null);
        } catch (Exception error) {
            fail("message tests threw " + error);
        }
    }

    // ------------------------------------------------------------------ directory

    private static void directoryTests() {
        section("Directory projection");
        Directory directory = new Directory();
        directory.setSelfId(7);
        directory.applyChannels(Arrays.asList(
                channel("1", "0", "大厅", "1", member(7, "我"), member(9, "Alice")),
                channel("2", "1", "子频道", "2", member(10, "Bob")),
                channel("3", "0", "无父频道", "3"),
                channel("4", "4", "自循环", "4"),
                channel("5", "999", "缺失父级", "5"),
                channel("6", "7", "循环 A", "6"),
                channel("7", "6", "循环 B", "7")));
        List<com.webspeak.nativeclient.core.ChannelNode> roots = directory.roots();
        int rootCount = roots.size();
        check("cycle and missing parents promoted to roots", rootCount == 6);
        check("self channel", "1".equals(directory.selfChannelId()));
        check("self channel name", "大厅".equals(directory.selfChannelName()));
        check("channel member count", directory.channelMemberCount("1") == 2);
        boolean childNested = roots.stream().anyMatch(node -> node.channel.id.equals("1")
                && node.children.size() == 1 && node.children.get(0).channel.id.equals("2"));
        check("child nested under parent", childNested);

        int headerRows = 0;
        for (com.webspeak.nativeclient.core.MemberRow row : directory.memberRows()) {
            if (row.isHeader()) headerRows++;
        }
        check("member rows include every channel header", headerRows == 7);

        directory.memberEnter(member(11, "Carol"));
        check("member enter without placement kept", directory.member(11) != null
                && directory.unplacedMembers().size() == 1);
        directory.memberLeave(11);
        check("member leave removes", directory.member(11) == null);

        // A complete snapshot replaces the directory; a partial one keeps placement.
        directory.applyChannels(Arrays.asList(channel("1", "0", "大厅", "1", member(7, "我"))));
        check("complete snapshot replaced members", directory.channelMemberCount("1") == 1
                && directory.member(9) == null);
    }

    // ------------------------------------------------------------------ errors

    private static void errorTests() {
        section("Error text");
        check("identity in use mapped", ErrorText.forClose(4005, "IDENTITY_IN_USE").contains("身份"));
        check("gateway full mapped", ErrorText.forClose(4004, "").contains("拒绝"));
        check("network lost mapped", ErrorText.forClose(1006, "").contains("网络"));
        check("explicit reason wins", ErrorText.forClose(1006, "JOIN_TICKET_REQUIRED").contains("票据"));
        check("unknown close code", ErrorText.forClose(4321, "").contains("4321"));
        check("detail appended", ErrorText.forCode("SERVER_PASSWORD_REQUIRED", "bad").contains("bad"));
    }

    // ------------------------------------------------------------------ session

    private static void sessionTests() throws Exception {
        section("Session end to end");
        FakeGateway gateway = new FakeGateway();
        QueueDispatcher dispatcher = new QueueDispatcher();
        FakeAudio audio = new FakeAudio();
        Recorder recorder = new Recorder();
        SessionConfig config = new SessionConfig.Builder()
                .gatewayUrl("http://127.0.0.1:" + gateway.httpPort() + "/")
                .target("ts.example.com:9987")
                .nickname("测试用户")
                .channel("大厅")
                .serverPassword("secret")
                .commandTimeoutMs(400)
                .build();
        VoiceSession session = new VoiceSession(config, dispatcher, audio, recorder);
        try {
            session.connect();
            check("gateway URL normalized", config.gatewayUrl.equals(
                    "http://127.0.0.1:" + gateway.httpPort()));
            check("ticket received", gateway.awaitTicketRequest(3000));
            check("origin header equals gateway", gateway.ticketOrigin != null
                    && gateway.ticketOrigin.equals(config.gatewayUrl));
            check("content type is json", gateway.ticketContentType != null
                    && gateway.ticketContentType.toLowerCase(Locale.ROOT).contains("application/json"));
            Map<String, Object> ticketBody = Json.asObject(Json.parse(gateway.ticketBody));
            check("ticket body nickname", "测试用户".equals(Json.string(ticketBody, "nickname")));
            check("ticket body target", "ts.example.com:9987".equals(Json.string(ticketBody, "target")));
            check("ticket body channel", "大厅".equals(Json.string(ticketBody, "channel")));
            check("ticket body password", "secret".equals(Json.string(ticketBody, "serverPassword")));

            check("voice socket opened", gateway.awaitSocket(4000));
            check("socket ticket query", ("ticket=" + gateway.ticket).equals(gateway.socketQuery));

            gateway.send(connectedFixture());
            gateway.send(channelFixture());
            waitFor(() -> session.state().phase == VoiceState.Phase.CONNECTED
                    && "大厅".equals(session.directory().selfChannelName()), dispatcher, 3000);
            check("session connected", session.state().phase == VoiceState.Phase.CONNECTED);
            check("self id", session.selfId() == 7);
            check("directory members", session.directory().members().size() == 2);
            check("audio capture started", audio.captureStarted);
            check("mute state sent on connect", gateway.expectTextContains("setMicrophoneMuted", 2000) != null);

            audio.emitPcm(new byte[Wire.PCM_FRAME_BYTES]);
            FakeGateway.Frame micFrame = gateway.expectFrame(frame -> frame.opcode == WebSocketFrames.OPCODE_BINARY
                    && frame.payload.length == Wire.PCM_FRAME_BYTES, 3000);
            check("microphone frame forwarded", micFrame != null && micFrame.payload.length == 1920);

            gateway.sendBinary(new byte[]{0, 0, 9, 1, 2});
            Thread.sleep(120);
            check("non-Opus frame skipped", audio.played.isEmpty());

            gateway.sendBinary(new byte[]{4, 0, 9, 1, 2, 3, 4});
            waitFor(() -> audio.played.size() == 1, dispatcher, 2000);
            check("downlink decoded to bridge", audio.played.size() == 1 && audio.played.get(0).clientId == 9
                    && Arrays.equals(audio.played.get(0).opus, new byte[]{1, 2, 3, 4}));

            gateway.send("{\"type\":\"chatMessage\",\"invokerId\":9,\"invokerName\":\"Alice\","
                    + "\"message\":\"欢迎\",\"scope\":\"channel\",\"timestamp\":123}");
            waitFor(() -> !recorder.chat.isEmpty(), dispatcher, 2000);
            check("remote chat recorded", recorder.chat.size() == 1 && "欢迎".equals(recorder.chat.get(0).text)
                    && !recorder.chat.get(0).self);

            boolean[] commandDone = new boolean[1];
            session.sendChannelMessage("大家好", "1", new CommandCallback() {
                @Override
                public void onSuccess() {
                    commandDone[0] = true;
                }

                @Override
                public void onError(String code, String message) {
                    fail("channel message failed: " + code + " " + message);
                }
            });
            FakeGateway.Frame command = gateway.expectTextContains("\"sendTextMessage\"", 2000);
            check("channel message sent", command != null);
            Map<String, Object> commandJson = command == null ? null
                    : Json.asObject(Json.parse(command.text()));
            check("channel message payload", commandJson != null
                    && "大家好".equals(Json.string(Json.object(commandJson, "payload"), "message"))
                    && "1".equals(Json.string(Json.object(commandJson, "payload"), "channelId")));
            String requestId = commandJson == null ? null : Json.string(commandJson, "requestId");
            check("command has request id", requestId != null);
            gateway.send("{\"type\":\"commandCompleted\",\"requestId\":\"" + requestId + "\"}");
            waitFor(() -> commandDone[0], dispatcher, 2000);
            check("command acknowledged", commandDone[0]);
            waitFor(() -> session.chatHistory().size() == 2, dispatcher, 2000);
            check("own message appended after ack", session.chatHistory().size() == 2
                    && session.chatHistory().get(1).self);

            boolean[] switched = new boolean[1];
            session.switchChannel("2", null, new CommandCallback() {
                @Override
                public void onSuccess() {
                    switched[0] = true;
                }

                @Override
                public void onError(String code, String message) {
                    fail("switch channel failed: " + code + " " + message);
                }
            });
            FakeGateway.Frame switchFrame = gateway.expectTextContains("\"switchChannel\"", 2000);
            String switchId = switchFrame == null ? null
                    : Json.string(Json.asObject(Json.parse(switchFrame.text())), "requestId");
            gateway.send("{\"type\":\"channelSwitched\",\"requestId\":\"" + switchId + "\",\"channelId\":\"2\"}");
            gateway.send(channelFixtureAfterSwitch());
            waitFor(() -> switched[0] && "房间".equals(session.directory().selfChannelName()), dispatcher, 2000);
            check("channel switch acknowledged", switched[0]);
            check("directory updated after switch", "房间".equals(session.directory().selfChannelName()));

            String[] timeoutError = new String[1];
            session.poke(9, "hi", new CommandCallback() {
                @Override
                public void onSuccess() {
                    fail("poke unexpectedly succeeded");
                }

                @Override
                public void onError(String code, String message) {
                    timeoutError[0] = code + ":" + message;
                }
            });
            gateway.expectTextContains("\"poke\"", 2000);
            waitFor(() -> timeoutError[0] != null, dispatcher, 3000);
            check("command timeout reported", timeoutError[0] != null && timeoutError[0].startsWith("TIMEOUT:"));

            gateway.sendPing("keepalive".getBytes(StandardCharsets.UTF_8));
            FakeGateway.Frame pong = gateway.expectFrame(frame -> frame.opcode == WebSocketFrames.OPCODE_PONG, 2000);
            check("ping answered with pong", pong != null
                    && "keepalive".equals(new String(pong.payload, StandardCharsets.UTF_8)));

            gateway.sendClose(4005, "IDENTITY_IN_USE");
            waitFor(() -> session.state().phase == VoiceState.Phase.FAILED, dispatcher, 3000);
            check("close failure surfaced", session.state().phase == VoiceState.Phase.FAILED
                    && session.state().message.contains("身份"));
            check("audio capture stopped on failure", !audio.captureStarted);
        } finally {
            session.destroy();
            gateway.close();
        }
    }

    private static void rejectedTicketTests() throws Exception {
        section("Ticket rejection");
        FakeGateway gateway = new FakeGateway();
        gateway.rejectTicketCode = "ORIGIN_REJECTED";
        QueueDispatcher dispatcher = new QueueDispatcher();
        FakeAudio audio = new FakeAudio();
        Recorder recorder = new Recorder();
        SessionConfig config = new SessionConfig.Builder()
                .gatewayUrl("http://127.0.0.1:" + gateway.httpPort())
                .nickname("测试用户")
                .build();
        VoiceSession session = new VoiceSession(config, dispatcher, audio, recorder);
        try {
            session.connect();
            waitFor(() -> session.state().phase == VoiceState.Phase.FAILED, dispatcher, 3000);
            check("rejected ticket failed", session.state().phase == VoiceState.Phase.FAILED);
            check("origin rejection explained", session.state().message.contains("来源"));
            check("no audio started after rejection", !audio.captureStarted);
        } finally {
            session.destroy();
            gateway.close();
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static String connectedFixture() {
        return "{\"type\":\"connected\",\"tsClientId\":7,\"members\":[{\"id\":7,\"nickname\":\"测试用户\","
                + "\"uid\":\"self-uid\",\"isSelf\":true},{\"id\":9,\"nickname\":\"Alice\",\"uid\":\"alice-uid\"}],"
                + "\"serverEventLog\":[{\"id\":\"e1\",\"kind\":\"connection\",\"message\":\"已连接\",\"timestamp\":1}],"
                + "\"whisperTargetIds\":[],\"whisperActive\":false,\"webrtcAvailable\":true}";
    }

    private static String channelFixture() {
        return "{\"type\":\"channelList\",\"channels\":["
                + "{\"id\":\"1\",\"parentID\":\"0\",\"name\":\"大厅\",\"order\":\"1\","
                + "\"members\":[{\"id\":7,\"nickname\":\"测试用户\",\"isSelf\":true}]},"
                + "{\"id\":\"2\",\"parentID\":\"0\",\"name\":\"房间\",\"order\":\"2\","
                + "\"members\":[{\"id\":9,\"nickname\":\"Alice\"}]}]}";
    }

    private static String channelFixtureAfterSwitch() {
        return "{\"type\":\"channelList\",\"channels\":["
                + "{\"id\":\"1\",\"parentID\":\"0\",\"name\":\"大厅\",\"order\":\"1\","
                + "\"members\":[{\"id\":9,\"nickname\":\"Alice\"}]},"
                + "{\"id\":\"2\",\"parentID\":\"0\",\"name\":\"房间\",\"order\":\"2\","
                + "\"members\":[{\"id\":7,\"nickname\":\"测试用户\",\"isSelf\":true}]}]}";
    }

    private static com.webspeak.nativeclient.core.ChannelInfo channel(String id, String parent, String name,
                                                                     String order, Member... members) {
        return new com.webspeak.nativeclient.core.ChannelInfo(id, parent, name, order, null, null, null, null,
                null, Arrays.asList(members));
    }

    private static Member member(int id, String nickname) {
        return new Member(id, nickname, "uid-" + id, false, false, null, false, false, false);
    }

    // ------------------------------------------------------------------ assertions

    private static void section(String name) {
        System.out.println("== " + name);
    }

    private static void check(String label, boolean condition) {
        if (condition) {
            passed++;
        } else {
            fail(label);
        }
    }

    private static void fail(String label) {
        failures.add(label);
        System.out.println("  FAIL " + label);
    }

    private static void expectThrows(String label, ThrowingRunnable action) {
        try {
            action.run();
            fail(label + " (no exception)");
        } catch (Exception expected) {
            passed++;
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) throws Json.JsonException {
        return Json.asObject(Json.parse(json));
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) builder.append(value);
        return builder.toString();
    }

    private static void waitFor(BooleanSupplier condition, QueueDispatcher dispatcher, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            dispatcher.drain();
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        dispatcher.drain();
    }

    // ------------------------------------------------------------------ fakes

    private static final class QueueDispatcher implements Dispatcher {
        private final ConcurrentLinkedQueue<Runnable> actions = new ConcurrentLinkedQueue<>();

        @Override
        public void post(Runnable action) {
            actions.add(action);
        }

        void drain() {
            Runnable action;
            while ((action = actions.poll()) != null) {
                try {
                    action.run();
                } catch (RuntimeException error) {
                    fail("dispatched callback threw " + error);
                }
            }
        }
    }

    private static final class FakeAudio implements AudioBridge {
        volatile boolean captureStarted;
        volatile PcmSink sink;
        volatile boolean muted;
        final List<Played> played = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<Integer> cleared = new java.util.concurrent.CopyOnWriteArrayList<>();

        static final class Played {
            final int clientId;
            final byte[] opus;

            Played(int clientId, byte[] opus) {
                this.clientId = clientId;
                this.opus = opus;
            }
        }

        @Override
        public void startCapture(PcmSink sink) {
            this.sink = sink;
            captureStarted = true;
        }

        @Override
        public void stopCapture() {
            captureStarted = false;
            sink = null;
        }

        @Override
        public void playOpus(int clientId, byte[] opus) {
            played.add(new Played(clientId, opus));
        }

        @Override
        public void stopPlayback() {
        }

        @Override
        public void setMuted(boolean muted) {
            this.muted = muted;
        }

        @Override
        public void setMemberVolume(int clientId, float volume) {
        }

        @Override
        public void clearMemberVolumes() {
        }

        @Override
        public void setOutputVolume(float volume) {
        }

        @Override
        public void release() {
            stopCapture();
        }

        void emitPcm(byte[] frame) {
            PcmSink current = sink;
            if (current == null) throw new IllegalStateException("capture is not running");
            current.sendPcm(frame);
        }
    }

    private static final class Recorder implements VoiceSession.Listener {
        final List<ChatEntry> chat = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> notices = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile int directoryChanges;
        volatile int[] whisperIds = new int[0];

        @Override
        public void onState(VoiceState state) {
        }

        @Override
        public void onServerInfo(String name, String welcomeMessage) {
        }

        @Override
        public void onDirectoryChanged() {
            directoryChanges++;
        }

        @Override
        public void onChat(ChatEntry entry) {
            chat.add(entry);
        }

        @Override
        public void onServerEvent(com.webspeak.nativeclient.core.ServerEventEntry event) {
        }

        @Override
        public void onPoke(com.webspeak.nativeclient.core.PokeEvent poke) {
        }

        @Override
        public void onWhisperTargets(int[] targetIds, boolean active) {
            whisperIds = targetIds;
        }

        @Override
        public void onNotice(String message) {
            notices.add(message);
        }
    }

    // ------------------------------------------------------------------ fake gateway

    private static final class FakeGateway {
        static final class Frame {
            final int opcode;
            final byte[] payload;

            Frame(int opcode, byte[] payload) {
                this.opcode = opcode;
                this.payload = payload;
            }

            String text() {
                return new String(payload, StandardCharsets.UTF_8);
            }
        }

        private final HttpServer http;
        private final ServerSocket socketServer;
        private final Thread acceptThread;
        private final SecureRandom random = new SecureRandom();
        private final Object sendLock = new Object();
        private final LinkedBlockingQueue<Frame> received = new LinkedBlockingQueue<>();
        private final AtomicInteger directoryChanges = new AtomicInteger();
        private volatile Socket clientSocket;
        private volatile OutputStream clientOutput;
        private volatile boolean open;
        private volatile boolean ticketRequested;
        private volatile boolean socketAccepted;

        volatile String ticket = "test-ticket-0123456789";
        volatile String ticketOrigin;
        volatile String ticketContentType;
        volatile String ticketBody = "";
        volatile String socketQuery = "";
        volatile String rejectTicketCode;

        FakeGateway() throws IOException {
            http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            http.createContext("/api/join-ticket", this::handleTicket);
            http.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "fake-http");
                thread.setDaemon(true);
                return thread;
            }));
            http.start();

            socketServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            acceptThread = new Thread(this::acceptLoop, "fake-ws-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        int httpPort() {
            return http.getAddress().getPort();
        }

        private void handleTicket(HttpExchange exchange) throws IOException {
            ticketOrigin = exchange.getRequestHeaders().getFirst("Origin");
            ticketContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            ticketBody = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
            ticketRequested = true;
            if (rejectTicketCode != null) {
                byte[] body = ("{\"ok\":false,\"code\":\"" + rejectTicketCode + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(403, body.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(body);
                }
                return;
            }
            String json = "{\"ok\":true,\"ticket\":\"" + ticket + "\",\"voiceEndpoint\":\"http://127.0.0.1:"
                    + socketServer.getLocalPort() + "\"}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }

        private void acceptLoop() {
            try {
                Socket socket = socketServer.accept();
                clientSocket = socket;
                socket.setTcpNoDelay(true);
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                String requestLine = readLine(input);
                if (requestLine == null) return;
                int queryIndex = requestLine.indexOf('?');
                if (queryIndex >= 0) {
                    int end = requestLine.indexOf(' ', queryIndex);
                    socketQuery = requestLine.substring(queryIndex + 1, end < 0 ? requestLine.length() : end);
                }
                String key = null;
                while (true) {
                    String line = readLine(input);
                    if (line == null || line.isEmpty()) break;
                    int separator = line.indexOf(':');
                    if (separator <= 0) continue;
                    String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
                    String value = line.substring(separator + 1).trim();
                    if ("sec-websocket-key".equals(name)) key = value;
                }
                if (key == null) return;
                String response = "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + WebSocketFrames.acceptValue(key) + "\r\n\r\n";
                output.write(response.getBytes(StandardCharsets.US_ASCII));
                output.flush();
                clientOutput = output;
                open = true;
                socketAccepted = true;
                while (open) {
                    WebSocketFrames.Frame frame = WebSocketFrames.read(input, 1024 * 1024);
                    if (frame == null) break;
                    if (frame.opcode == WebSocketFrames.OPCODE_PING) {
                        synchronized (sendLock) {
                            WebSocketFrames.write(clientOutput, WebSocketFrames.OPCODE_PONG, frame.payload, false, random);
                        }
                        continue;
                    }
                    if (frame.opcode == WebSocketFrames.OPCODE_CLOSE) {
                        open = false;
                        received.add(new Frame(frame.opcode, frame.payload));
                        break;
                    }
                    if (frame.opcode == WebSocketFrames.OPCODE_TEXT || frame.opcode == WebSocketFrames.OPCODE_BINARY
                            || frame.opcode == WebSocketFrames.OPCODE_PONG) {
                        received.add(new Frame(frame.opcode, frame.payload));
                    }
                }
            } catch (IOException ignored) {
                // Test teardown closes the socket.
            }
        }

        boolean awaitTicketRequest(long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (ticketRequested) return true;
                Thread.sleep(10);
            }
            return ticketRequested;
        }

        boolean awaitSocket(long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (socketAccepted) return true;
                Thread.sleep(10);
            }
            return socketAccepted;
        }

        void send(String text) throws IOException {
            sendFrame(WebSocketFrames.OPCODE_TEXT, text.getBytes(StandardCharsets.UTF_8));
        }

        void sendBinary(byte[] data) throws IOException {
            sendFrame(WebSocketFrames.OPCODE_BINARY, data);
        }

        void sendPing(byte[] payload) throws IOException {
            sendFrame(WebSocketFrames.OPCODE_PING, payload);
        }

        void sendClose(int code, String reason) throws IOException {
            synchronized (sendLock) {
                WebSocketFrames.writeClose(clientOutput, code, reason, false, random);
            }
            open = false;
        }

        private void sendFrame(int opcode, byte[] payload) throws IOException {
            synchronized (sendLock) {
                if (clientOutput == null) throw new IOException("socket not ready");
                WebSocketFrames.write(clientOutput, opcode, payload, false, random);
            }
        }

        Frame expectTextContains(String needle, long timeoutMs) throws InterruptedException {
            return expectFrame(frame -> frame.opcode == WebSocketFrames.OPCODE_TEXT && frame.text().contains(needle),
                    timeoutMs);
        }

        Frame expectFrame(Predicate<Frame> predicate, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                Frame frame = received.poll(20, TimeUnit.MILLISECONDS);
                if (frame == null) continue;
                if (predicate.test(frame)) return frame;
            }
            return null;
        }

        void close() {
            open = false;
            try {
                if (clientSocket != null) clientSocket.close();
            } catch (IOException ignored) {
                // Test teardown.
            }
            try {
                socketServer.close();
            } catch (IOException ignored) {
                // Test teardown.
            }
            http.stop(0);
        }

        private static byte[] readAll(InputStream input) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = input.read(chunk)) != -1) buffer.write(chunk, 0, read);
            return buffer.toByteArray();
        }

        private static String readLine(InputStream input) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int value;
            while ((value = input.read()) != -1) {
                if (value == '\n') break;
                if (value != '\r') line.write(value);
            }
            if (value == -1 && line.size() == 0) return null;
            return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }
}
