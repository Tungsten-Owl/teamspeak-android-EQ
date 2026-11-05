package com.webspeak.ts3.core;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Desktop integration probe for the direct TS3 core against a local server. */
public final class CoreProbe {
    private static final Executor DIRECT = Runnable::run;
    private static final AtomicInteger receivedSamples = new AtomicInteger();
    private static final AtomicInteger receivedChats = new AtomicInteger();
    private static volatile double peakRms;
    private static final CountDownLatch connected = new CountDownLatch(1);

    public static void main(String[] args) throws Exception {
        String relayHost = System.getenv().getOrDefault("TS3_RELAY_HOST", "");
        int relayPort = Integer.parseInt(System.getenv().getOrDefault("TS3_RELAY_PORT", "0"));
        String relayToken = System.getenv().getOrDefault("TS3_RELAY_TOKEN", "");
        System.out.println("relay=" + (relayHost.isEmpty() ? "direct" : relayHost + ":" + relayPort));
        int level = 8;
        String identity = Ts3Identity.generate(level);
        System.out.println("identity length=" + identity.length()
                + " level=" + Ts3Identity.securityLevel(Ts3Identity.load(identity)));

        VoiceEngine receiverVoice = new VoiceEngine((clientId, samples, length) -> {
            receivedSamples.addAndGet(length);
            double sum = 0;
            for (int i = 0; i < length; i++) {
                double value = samples[i] / 32768d;
                sum += value * value;
            }
            double rms = Math.sqrt(sum / Math.max(1, length));
            if (rms > peakRms) peakRms = rms;
        });
        receiverVoice.setSpeakerMuted(false);
        Ts3Session receiver = new Ts3Session(DIRECT, receiverVoice);
        receiver.setListener(new Ts3Session.Listener() {
            @Override
            public void onStateChanged(Ts3Session.State state, String detail) {
                System.out.println("receiver state " + state + " " + detail);
                if (state == Ts3Session.State.CONNECTED) connected.countDown();
            }

            @Override
            public void onDirectoryChanged() {
            }

            @Override
            public void onChatMessage(ChatMessage message) {
                receivedChats.incrementAndGet();
                System.out.println("receiver chat from " + message.invokerName + ": " + message.message);
            }

            @Override
            public void onSessionEvent(SessionEvent event) {
            }
        });
        receiver.connect(new Ts3Config("127.0.0.1", 9987, "CoreReceiver", "", "", "", identity,
                relayHost, relayPort, relayToken));
        connected.await(5, TimeUnit.SECONDS);

        VoiceEngine senderVoice = new VoiceEngine(null);
        Ts3Session sender = new Ts3Session(DIRECT, senderVoice);
        sender.setListener(new Ts3Session.Listener() {
            @Override
            public void onStateChanged(Ts3Session.State state, String detail) {
            }

            @Override
            public void onDirectoryChanged() {
            }

            @Override
            public void onChatMessage(ChatMessage message) {
            }

            @Override
            public void onSessionEvent(SessionEvent event) {
                System.out.println("sender event: " + event.text);
            }
        });
        sender.connect(new Ts3Config("127.0.0.1", 9987, "CoreSender", "", "", "",
                Ts3Identity.generate(8), relayHost, relayPort, relayToken));
        Thread.sleep(1500);

        System.out.println("self receiver=" + receiver.getSelfId()
                + " channel=" + receiver.currentChannelId());
        for (ChannelInfo channel : receiver.getChannels()) {
            System.out.println("channel " + channel.id + " parent=" + channel.parentId
                    + " name=" + channel.name + " members=" + channel.memberCount
                    + " default=" + channel.isDefault);
        }
        List<MemberInfo> members = receiver.getMembers();
        for (MemberInfo member : members) {
            System.out.println("member " + member.id + " " + member.nickname
                    + " channel=" + member.channelId + " type=" + member.type);
        }

        sender.sendChannelMessage("core probe");
        Thread.sleep(800);

        short[] pcm = new short[960];
        long end = System.currentTimeMillis() + 3_500;
        int phase = 0;
        while (System.currentTimeMillis() < end) {
            for (int i = 0; i < pcm.length; i++) {
                pcm[i] = (short) (Math.sin(2 * Math.PI * 440 * (phase + i) / 48_000.0) * 7_000);
            }
            phase += pcm.length;
            senderVoice.pushPcm(pcm, pcm.length);
            Thread.sleep(20);
        }
        Thread.sleep(800);

        System.out.println("channels=" + receiver.getChannels().size()
                + " members=" + receiver.getMembers().size()
                + " chats=" + receivedChats.get()
                + " voiceSamples=" + receivedSamples.get()
                + " peakRms=" + peakRms);
        boolean ok = receiver.getChannels().size() >= 1
                && receiver.getMembers().size() >= 2
                && receivedChats.get() >= 1
                && receivedSamples.get() > 48_000
                && peakRms > 0.02;
        System.out.println("CORE " + (ok ? "OK" : "FAILED"));

        sender.disconnect();
        receiver.disconnect();
        System.out.flush();
        System.exit(ok ? 0 : 1);
    }
}
