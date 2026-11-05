package com.webspeak.ts3.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;

/** Downloads the avatar of the SDK-hosted "AvatarHost" client. */
public final class AvatarProbe {
    private static final Executor DIRECT = Runnable::run;

    public static void main(String[] args) throws Exception {
        Ts3Session session = new Ts3Session(DIRECT, new VoiceEngine(null));
        session.connect(new Ts3Config("127.0.0.1", 9987, "AvatarProbe", "", "", "",
                Ts3Identity.generate(8)));
        MemberInfo host = null;
        for (int i = 0; i < 40 && host == null; i++) {
            for (MemberInfo member : session.getMembers()) {
                if ("AvatarHost".equals(member.nickname)) {
                    host = member;
                    break;
                }
            }
            if (host == null) Thread.sleep(250);
        }
        if (host == null) {
            System.out.println("HOST NOT FOUND");
            session.disconnect();
            System.exit(1);
        }
        System.out.println("HOST uid=" + host.uniqueId
                + " avatarKey=" + host.avatarKey
                + " avatarHash=" + host.avatarHash
                + " file=" + AvatarFileName.forMember(host));
        byte[] data = session.downloadAvatar(host);
        if (data == null) {
            System.out.println("AVATAR NULL");
            session.disconnect();
            System.exit(1);
        }
        System.out.println("AVATAR BYTES=" + data.length
                + " signature=" + String.format("%02x%02x%02x%02x",
                data[0], data[1], data[2], data[3]));
        Files.write(Path.of("avatar-downloaded.png"), data);
        session.disconnect();
        System.exit(data.length > 100 ? 0 : 1);
    }
}
