package com.webspeak.ts3.core;

import java.util.concurrent.Executor;

/** Measures Leave latency and verifies the server removes the departing client. */
public final class DisconnectProbe {
    private static final Executor DIRECT = Runnable::run;

    public static void main(String[] args) throws Exception {
        Ts3Session observer = new Ts3Session(DIRECT, new VoiceEngine(null));
        observer.connect(new Ts3Config("127.0.0.1", 9987, "Observer", "", "", "",
                Ts3Identity.generate(8)));
        Ts3Session victim = new Ts3Session(DIRECT, new VoiceEngine(null));
        victim.connect(new Ts3Config("127.0.0.1", 9987, "Victim", "", "", "",
                Ts3Identity.generate(8)));

        for (int i = 0; i < 40 && observer.getMembers().size() < 2; i++) Thread.sleep(100);
        System.out.println("BEFORE members=" + observer.getMembers().size());
        long started = System.nanoTime();
        victim.disconnect();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        System.out.println("DISCONNECT_MS=" + elapsedMs);

        boolean removed = false;
        for (int i = 0; i < 50; i++) {
            boolean found = false;
            for (MemberInfo member : observer.getMembers()) {
                if ("Victim".equals(member.nickname)) found = true;
            }
            if (!found) {
                removed = true;
                break;
            }
            Thread.sleep(100);
        }
        System.out.println("REMOVED=" + removed
                + " after=" + ((System.nanoTime() - started) / 1_000_000L) + "ms"
                + " members=" + observer.getMembers().size());
        observer.disconnect();
        System.exit(removed && elapsedMs < 1_000 ? 0 : 1);
    }
}
