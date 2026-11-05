package com.webspeak.ts3.core;

import com.github.manevolent.ts3j.audio.Microphone;
import com.github.manevolent.ts3j.enums.CodecType;
import com.github.manevolent.ts3j.protocol.packet.PacketBody0Voice;
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket;
import com.github.manevolent.ts3j.util.Pair;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Opus encode/decode, a small adaptive jitter buffer and the microphone queue
 * expected by ts3j.
 *
 * <p>PCM is 48 kHz mono signed 16-bit. Downlink packets are buffered per
 * speaker, reordered by the TS3 voice packet id, decoded on a fixed 20 ms
 * clock, recovered with Opus in-band FEC when one frame is missing, and
 * concealed with Opus PLC for isolated losses. The buffer starts at roughly
 * 60 ms and grows to 120 ms on a lossy path; that is the main
 * quality/latency trade-off for UDP voice.
 */
public final class VoiceEngine {
    public static final int SAMPLE_RATE = 48_000;
    public static final int FRAME_SAMPLES = 960;

    private final PcmSink sink;
    private final ArrayDeque<byte[]> microphone = new ArrayDeque<>();
    private final Map<Integer, Downlink> downlinks = new HashMap<>();
    private final Object downlinkLock = new Object();

    private final short[] pending = new short[FRAME_SAMPLES];
    private int pendingCount;
    private OpusEncoder encoder;
    private byte[] encodeBuffer = new byte[4_096];

    private boolean microphoneMuted;
    private volatile boolean speakerMuted = true;
    private float volume = 1.0f;
    private volatile boolean closed = true;
    private volatile int networkRttMs;
    private volatile int networkJitterMs;
    private volatile int currentBitrate = 40_000;
    /** Channel codec (4 = Opus Voice, 5 = Opus Music) and quality (0..10). */
    private volatile int channelCodec = 4;
    private volatile int channelQuality = 8;
    /** IP/UDP/TS3 framing at 50 packets per second, in KiB/s. */
    private static final double VOICE_OVERHEAD_KIB = 1.7;
    private volatile LocalTeamspeakClientSocket voiceSocket;
    private volatile Thread networkThread;
    private volatile boolean networkRunning;

    private Thread jitterThread;
    private volatile boolean jitterRunning;

    public VoiceEngine(PcmSink sink) {
        this.sink = sink;
    }

    /** Installs the downlink handler before the socket is connected. */
    public synchronized void attachReceiver(LocalTeamspeakClientSocket client) {
        closed = false;
        voiceSocket = client;
        client.setVoiceHandler(this::onVoicePacket);
        startJitter();
        startNetworkMonitor();
    }

    public synchronized void attach(LocalTeamspeakClientSocket client) {
        attachReceiver(client);
        encoder = null;
        pendingCount = 0;
        microphone.clear();
        synchronized (downlinkLock) {
            downlinks.clear();
        }
        client.setMicrophone(new Microphone() {
            @Override
            public boolean isMuted() {
                return isMicrophoneMuted();
            }

            @Override
            public boolean isReady() {
                return hasMicrophonePacket();
            }

            @Override
            public CodecType getCodec() {
                return channelCodec == 5 ? CodecType.OPUS_MUSIC : CodecType.OPUS_VOICE;
            }

            @Override
            public byte[] provide() {
                return pollMicrophonePacket();
            }
        });
    }

    public synchronized void detach(LocalTeamspeakClientSocket client) {
        // A late failure from an older reconnect attempt must not tear down the
        // voice pipeline that a newer socket has already installed. Without
        // this owner check, closing the abandoned socket stops the new jitter
        // thread and empties its queues, which is heard as choppy playback.
        if (client != null && voiceSocket != null && voiceSocket != client) {
            return;
        }
        if (client != null) {
            client.setMicrophone(null);
            client.setVoiceHandler(null);
        }
        closed = true;
        voiceSocket = null;
        stopNetworkMonitor();
        stopJitter();
        microphone.clear();
        synchronized (downlinkLock) {
            downlinks.clear();
        }
        pendingCount = 0;
    }

    public void pushPcm(short[] samples, int length) {
        if (samples == null || length <= 0) return;
        synchronized (this) {
            if (closed || microphoneMuted) return;
            int offset = 0;
            while (offset < length) {
                int copy = Math.min(length - offset, FRAME_SAMPLES - pendingCount);
                System.arraycopy(samples, offset, pending, pendingCount, copy);
                pendingCount += copy;
                offset += copy;
                if (pendingCount == FRAME_SAMPLES) {
                    encodeFrame();
                    pendingCount = 0;
                }
            }
        }
    }

    private void encodeFrame() {
        try {
            if (encoder == null) {
                // Opus Voice and Opus Music are different encoder applications,
                // so the channel's codec decides how the encoder is configured.
                encoder = new OpusEncoder(SAMPLE_RATE, 1, channelCodec == 5
                        ? OpusApplication.OPUS_APPLICATION_AUDIO
                        : OpusApplication.OPUS_APPLICATION_VOIP);
                encoder.setBitrate(currentBitrate);
                // Opus complexity 9 is too expensive for pure-Java Concentus on
                // a phone: a slow frame delays the next microphone read and the
                // 20 ms sender then has no packet. Complexity 3 keeps the encode
                // comfortably below the frame budget at this bitrate.
                encoder.setComplexity(3);
                // FEC costs a little bandwidth but lets the receiver recover an
                // isolated lost frame from the next packet. 20% reserved far more
                // than a normal path loses and starved the speech itself.
                encoder.setUseInbandFEC(true);
                encoder.setPacketLossPercent(10);
            }
            int targetBitrate = targetBitrate();
            if (targetBitrate != currentBitrate) {
                currentBitrate = targetBitrate;
                encoder.setBitrate(targetBitrate);
            }
            if (encodeBuffer.length < 4_000) encodeBuffer = new byte[4_000];
            int length = encoder.encode(pending, 0, FRAME_SAMPLES, encodeBuffer, 0, encodeBuffer.length);
            if (length > 0) {
                microphone.addLast(Arrays.copyOf(encodeBuffer, length));
                while (microphone.size() > 32) microphone.removeFirst();
            }
        } catch (Throwable ignored) {
            // A failed frame is not worth dropping the call; the next 20 ms retries.
        }
    }

    private synchronized boolean hasMicrophonePacket() {
        return !microphoneMuted && !microphone.isEmpty();
    }

    private synchronized byte[] pollMicrophonePacket() {
        byte[] packet = microphone.pollFirst();
        return packet == null ? new byte[0] : packet;
    }

    private void onVoicePacket(PacketBody0Voice packet) {
        try {
            if (packet == null || closed || speakerMuted) return;
            CodecType codec = packet.getCodecType();
            if (codec != CodecType.OPUS_VOICE && codec != CodecType.OPUS_MUSIC) return;
            byte[] data = packet.getCodecData();
            if (data == null || data.length == 0) return;

            int clientId = packet.getClientId();
            synchronized (downlinkLock) {
                Downlink downlink = downlinks.get(clientId);
                if (downlink == null) {
                    downlink = new Downlink();
                    downlinks.put(clientId, downlink);
                }
                downlink.offer(packet.getPacketId(), data, System.nanoTime());
            }
        } catch (Throwable ignored) {
            // Corrupt or late voice packets are dropped; audio continues.
        }
    }

    private synchronized void startJitter() {
        if (jitterThread != null && jitterThread.isAlive()) return;
        jitterRunning = true;
        jitterThread = new Thread(this::jitterLoop, "ts3-jitter");
        jitterThread.setDaemon(true);
        jitterThread.start();
    }

    private synchronized void startNetworkMonitor() {
        if (networkThread != null && networkThread.isAlive()) return;
        networkRunning = true;
        networkThread = new Thread(this::networkLoop, "ts3-network-monitor");
        networkThread.setDaemon(true);
        networkThread.start();
    }

    private synchronized void stopNetworkMonitor() {
        networkRunning = false;
        Thread thread = networkThread;
        networkThread = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void networkLoop() {
        Thread self = Thread.currentThread();
        while (networkRunning && networkThread == self) {
            LocalTeamspeakClientSocket socket = voiceSocket;
            if (socket != null) {
                try {
                    Pair<Double, Double> ping = socket.getPing();
                    double rtt = ping.getKey() * 1000D;
                    double jitter = ping.getValue() * 1000D;
                    // ts3j returns TIMEOUT (6000) before its first PONG; that
                    // sentinel is not a real 6-second RTT.
                    if (rtt >= 0D && rtt < 2_000D) networkRttMs = (int) rtt;
                    if (jitter >= 0D && jitter < 1_000D) networkJitterMs = (int) jitter;
                } catch (Throwable ignored) {
                }
            }
            try {
                Thread.sleep(4_000L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Follows the channel's codec and quality, which is what a TeamSpeak client
     * is expected to do.
     *
     * <p>Quality 10 on Opus Music is 11.87 KiB/s per client, roughly three times
     * what a voice channel reserves, so ignoring the setting left most of the
     * channel's budget unused.
     */
    public synchronized void setChannelProfile(int codec, int quality) {
        int nextCodec = codec == 5 ? 5 : 4;
        int nextQuality = Math.max(0, Math.min(10, quality));
        if (nextCodec == channelCodec && nextQuality == channelQuality) return;
        boolean applicationChanged = nextCodec != channelCodec;
        channelCodec = nextCodec;
        channelQuality = nextQuality;
        // A bitrate change is applied to the live encoder; a different
        // application cannot be, so the next frame rebuilds it.
        if (applicationChanged) encoder = null;
    }

    public int getChannelCodec() {
        return channelCodec;
    }

    public int getChannelQuality() {
        return channelQuality;
    }

    private int targetBitrate() {
        return Math.min(channelBitrate(), networkCeiling());
    }

    /**
     * Audio bitrate implied by the channel settings.
     *
     * <p>ts3j documents each codec's per-client bandwidth at quality 0 and 10
     * (Opus Voice 2.73/7.71 KiB/s, Opus Music 3.08/11.87 KiB/s). The table is
     * interpolated per quality step, a fixed allowance is subtracted for the
     * 50 packets per second of IP/UDP/TS3 framing, and the rest is the encoder
     * bitrate.
     */
    private int channelBitrate() {
        double perClientKiB = channelCodec == 5
                ? 3.08 + (11.87 - 3.08) * channelQuality / 10.0
                : 2.73 + (7.71 - 2.73) * channelQuality / 10.0;
        double audioKiB = Math.max(1.0, perClientKiB - VOICE_OVERHEAD_KIB);
        int bitrate = (int) Math.round(audioKiB * 1024.0 * 8.0);
        return Math.max(16_000, Math.min(96_000, bitrate));
    }

    /**
     * Ceiling for a degraded path. The channel may allow 11.87 KiB/s, but a
     * 300 ms RTT or a 120 ms jitter path cannot carry it without loss.
     */
    private int networkCeiling() {
        int rtt = networkRttMs;
        int jitter = networkJitterMs;
        if (rtt > 300 || jitter > 120) return 24_000;
        if (rtt > 180 || jitter > 60) return 48_000;
        return 96_000;
    }

    private synchronized void stopJitter() {
        jitterRunning = false;
        Thread thread = jitterThread;
        jitterThread = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void jitterLoop() {
        Thread self = Thread.currentThread();
        long next = System.nanoTime();
        while (jitterRunning && jitterThread == self) {
            try {
                pump();
            } catch (Throwable ignored) {
                // One bad frame must not stop playback for everyone else.
            }
            next += 20_000_000L;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else if (sleep < -100_000_000L) {
                next = System.nanoTime();
            }
        }
    }

    private void pump() {
        if (speakerMuted) return;
        List<Output> outputs = new ArrayList<>();
        synchronized (downlinkLock) {
            Iterator<Map.Entry<Integer, Downlink>> iterator = downlinks.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, Downlink> entry = iterator.next();
                Downlink downlink = entry.getValue();
                short[] pcm = downlink.nextFrame(System.nanoTime());
                if (pcm != null && pcm.length > 0) {
                    outputs.add(new Output(entry.getKey(), pcm));
                }
                if (downlink.inactive(System.nanoTime())) iterator.remove();
            }
        }
        for (Output output : outputs) {
            float gain = volume;
            if (gain != 1.0f) {
                for (int i = 0; i < output.pcm.length; i++) {
                    int value = (int) (output.pcm[i] * gain);
                    if (value > 32767) value = 32767;
                    if (value < -32768) value = -32768;
                    output.pcm[i] = (short) value;
                }
            }
            if (sink != null) sink.onPcm(output.clientId, output.pcm, output.pcm.length);
        }
    }

    public synchronized boolean isMicrophoneMuted() {
        return microphoneMuted;
    }

    public synchronized void setMicrophoneMuted(boolean muted) {
        microphoneMuted = muted;
        if (muted) microphone.clear();
    }

    public synchronized boolean isSpeakerMuted() {
        return speakerMuted;
    }

    public synchronized void setSpeakerMuted(boolean muted) {
        speakerMuted = muted;
        if (muted) {
            synchronized (downlinkLock) {
                downlinks.clear();
            }
        }
    }

    public synchronized float getVolume() {
        return volume;
    }

    public synchronized void setVolume(float volume) {
        this.volume = Math.max(0f, Math.min(2f, volume));
    }

    private static final class Output {
        final int clientId;
        final short[] pcm;

        Output(int clientId, short[] pcm) {
            this.clientId = clientId;
            this.pcm = pcm;
        }
    }

    /** One speaker's reorder buffer, decoder and adaptive target depth. */
    private static final class Downlink {
        private static final int MIN_DEPTH = 3;   // 60 ms
        private static final int MAX_DEPTH = 8;   // 160 ms on a lossy path

        private final TreeMap<Long, byte[]> packets = new TreeMap<>();
        private OpusDecoder decoder;
        private boolean initialized;
        private int highestSequence;
        private long highestUnwrapped;
        private long expected;
        private long lastPacketAt;
        private int targetDepth = MIN_DEPTH;
        private int goodFrames;
        private boolean started;

        synchronized void offer(int sequence, byte[] data, long now) {
            if (!initialized) {
                initialized = true;
                highestSequence = sequence;
                highestUnwrapped = sequence;
                expected = sequence;
            } else {
                int forward = (sequence - highestSequence) & 0xFFFF;
                if (forward == 0) return;
                if (forward < 0x8000) {
                    highestUnwrapped += forward;
                    highestSequence = sequence;
                } else {
                    int back = (highestSequence - sequence) & 0xFFFF;
                    if (back > 128) return;
                    long older = highestUnwrapped - back;
                    if (older >= expected) packets.put(older, data);
                    lastPacketAt = now;
                    return;
                }
            }
            packets.put(highestUnwrapped, data);
            lastPacketAt = now;
            if (highestUnwrapped - expected > 200) {
                expected = highestUnwrapped - targetDepth;
            }
            while (packets.size() > 64) packets.pollFirstEntry();
        }

        synchronized short[] nextFrame(long now) {
            if (decoder == null) {
                try {
                    decoder = new OpusDecoder(SAMPLE_RATE, 1);
                } catch (Throwable error) {
                    return silence();
                }
            }
            if (!started) {
                if (packets.size() < targetDepth) {
                    if (packets.isEmpty() && now - lastPacketAt > 250_000_000L) {
                        // Never started; let the caller drop this speaker.
                    }
                    return null;
                }
                started = true;
            }

            // Cap latency on a burst: drop the oldest frames instead of
            // growing delay without bound.
            while (packets.size() > targetDepth + 4) {
                Long first = packets.firstKey();
                if (first == null) break;
                packets.pollFirstEntry();
                expected = Math.max(expected, first + 1);
            }

            byte[] data = packets.get(expected);
            if (data != null) {
                packets.remove(expected);
                expected++;
                short[] pcm = decode(data, false);
                goodFrames++;
                if (goodFrames > 50 && targetDepth > MIN_DEPTH) {
                    targetDepth--;
                    goodFrames = 0;
                }
                return pcm == null ? silence() : pcm;
            }

            // One missing frame: Opus FEC carries a coarse copy of the previous
            // frame inside the next packet.
            Map.Entry<Long, byte[]> next = packets.ceilingEntry(expected);
            if (next != null && next.getKey() == expected + 1) {
                short[] recovered = decode(next.getValue(), true);
                if (recovered != null) {
                    expected++;
                    targetDepth = Math.min(MAX_DEPTH, targetDepth + 1);
                    goodFrames = 0;
                    return recovered;
                }
            }

            // No recoverable packet: Opus packet-loss concealment.
            expected++;
            targetDepth = Math.min(MAX_DEPTH, targetDepth + 1);
            goodFrames = 0;
            return decodePlc();
        }

        synchronized boolean inactive(long now) {
            return packets.isEmpty() && now - lastPacketAt > 300_000_000L;
        }

        private short[] decode(byte[] data, boolean fec) {
            try {
                short[] pcm = new short[FRAME_SAMPLES * 6];
                int samples = decoder.decode(data, 0, data.length, pcm, 0, pcm.length, fec);
                if (samples <= 0) return null;
                short[] out = new short[samples];
                System.arraycopy(pcm, 0, out, 0, samples);
                return out;
            } catch (Throwable error) {
                return null;
            }
        }

        private short[] decodePlc() {
            try {
                short[] pcm = new short[FRAME_SAMPLES];
                int samples = decoder.decode(null, 0, 0, pcm, 0, pcm.length, false);
                if (samples <= 0) return silence();
                short[] out = new short[samples];
                System.arraycopy(pcm, 0, out, 0, samples);
                return out;
            } catch (Throwable error) {
                return silence();
            }
        }

        private static short[] silence() {
            return new short[FRAME_SAMPLES];
        }
    }
}
