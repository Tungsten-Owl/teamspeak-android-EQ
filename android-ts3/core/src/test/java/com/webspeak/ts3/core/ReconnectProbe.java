package com.webspeak.ts3.core;

import java.util.concurrent.Executor;

/** Verifies the watchdog-driven auto reconnect after a server outage. */
public final class ReconnectProbe {
    private static final Executor DIRECT = Runnable::run;

    public static void main(String[] args) throws Exception {
        Ts3Session session = new Ts3Session(DIRECT, new VoiceEngine(null));
        session.setListener(new Ts3Session.Listener() {
            @Override
            public void onStateChanged(Ts3Session.State state, String detail) {
                System.out.println("STATE " + state + " " + detail);
            }

            @Override
            public void onDirectoryChanged() {
            }

            @Override
            public void onChatMessage(ChatMessage message) {
            }

            @Override
            public void onSessionEvent(SessionEvent event) {
                System.out.println("EVENT " + event.text);
            }
        });
        session.connect(new Ts3Config("127.0.0.1", 9987, "ReconnectProbe", "", "", "",
                Ts3Identity.generate(8)));
        System.out.println("CONNECTED initial");
        Thread.sleep(180_000);
        System.out.println("FINAL state=" + session.getState());
        session.disconnect();
        System.out.println("DONE");
        System.exit(0);
    }
}

