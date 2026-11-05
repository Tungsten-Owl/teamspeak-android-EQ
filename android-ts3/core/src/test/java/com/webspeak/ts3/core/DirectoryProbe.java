package com.webspeak.ts3.core;

import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Verifies that a fast Leave -> Connect does not leave two server-side clones. */
public final class DirectoryProbe {
    private static final Executor DIRECT = Runnable::run;
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 9987;

    public static void main(String[] args) throws Exception {
        String observerIdentity = Ts3Identity.generate(8);
        String victimIdentity = Ts3Identity.generate(8);

        Ts3Session observer = new Ts3Session(DIRECT, new VoiceEngine(null));
        CountDownLatch observerReady = new CountDownLatch(1);
        observer.setListener(new Ts3Session.Listener() {
            @Override
            public void onStateChanged(Ts3Session.State state, String detail) {
                System.out.println("observer " + state + " " + detail);
                if (state == Ts3Session.State.CONNECTED) observerReady.countDown();
            }

            @Override
            public void onDirectoryChanged() {
            }

            @Override
            public void onChatMessage(ChatMessage message) {
            }

            @Override
            public void onSessionEvent(SessionEvent event) {
                System.out.println("observer event: " + event.text);
            }
        });
        observer.connect(new Ts3Config(HOST, PORT, "DirObserver", "", "", "",
                observerIdentity));
        observerReady.await(20, TimeUnit.SECONDS);

        Ts3Session victim = new Ts3Session(DIRECT, new VoiceEngine(null));
        victim.connect(new Ts3Config(HOST, PORT, "FastReconnect", "", "", "",
                victimIdentity));
        Thread.sleep(1_500L);

        String uid = null;
        for (MemberInfo member : victim.getMembers()) {
            if (member.id == victim.getSelfId()) {
                uid = member.uniqueId;
                System.out.println("SELF id=" + member.id + " cid=" + member.channelId
                        + " name=" + member.nickname);
            }
        }
        System.out.println("BEFORE channels=" + observer.getChannels().size()
                + " members=" + observer.getMembers().size()
                + " selfUid=" + uid);
        for (MemberInfo member : observer.getMembers()) {
            System.out.println("MEMBER id=" + member.id + " cid=" + member.channelId
                    + " name=" + member.nickname + " uid=" + member.uniqueId);
        }

        long started = System.currentTimeMillis();
        victim.disconnect();
        victim.connect(new Ts3Config(HOST, PORT, "FastReconnect", "", "", "",
                victimIdentity));
        long reconnectMs = System.currentTimeMillis() - started;
        Thread.sleep(1_500L);

        int clones = 0;
        for (MemberInfo member : observer.getMembers()) {
            if (uid != null && uid.equals(member.uniqueId)) clones++;
        }
        System.out.println("RECONNECT_MS=" + reconnectMs
                + " CLONES=" + clones
                + " members=" + observer.getMembers().size());
        for (MemberInfo member : observer.getMembers()) {
            System.out.println("AFTER id=" + member.id + " cid=" + member.channelId
                    + " name=" + member.nickname + " uid=" + member.uniqueId);
        }

        victim.disconnect();
        observer.disconnect();
        Thread.sleep(2_500L);
        System.out.println(clones == 1 ? "DIRECTORY OK" : "DIRECTORY CLONES");
        System.exit(clones == 1 ? 0 : 1);
    }
}
