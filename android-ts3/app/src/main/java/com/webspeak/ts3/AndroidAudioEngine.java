package com.webspeak.ts3;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.Equalizer;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;

import com.webspeak.ts3.core.PcmSink;
import com.webspeak.ts3.core.VoiceEngine;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Android PCM capture and playback for the direct TS3 voice engine. */
public final class AndroidAudioEngine implements PcmSink {
    private static final int FRAME = VoiceEngine.FRAME_SAMPLES;
    private static final int SAMPLE_RATE = VoiceEngine.SAMPLE_RATE;

    private final Map<Integer, ArrayDeque<short[]>> speakerQueues = new ConcurrentHashMap<>();
    private final AudioManager audioManager;

    private VoiceEngine voice;
    private AudioTrack track;
    private Equalizer equalizer;
    private volatile boolean useEarpiece = true;
    private volatile boolean voiceOptimization = true;
    private volatile Thread playbackThread;
    private volatile boolean playbackRunning;

    private AudioRecord record;
    private volatile Thread microphoneThread;
    private volatile boolean microphoneRunning;
    private final ArrayDeque<short[]> captureQueue = new ArrayDeque<>();
    private final Object captureLock = new Object();
    private volatile Thread encoderThread;
    private volatile boolean encoderRunning;
    private int captureRate = SAMPLE_RATE;
    private double resamplePosition;
    private short resampleLast;
    private final short[] resampleFrame = new short[FRAME];
    private int resampleCount;
    private short[] denoiseDry = new short[FRAME];
    private final short[] rnnoiseDryDelay = new short[480];
    private int rnnoiseDryDelayPos;
    // Wind/rumble suppression state; see windSuppress().
    private static final float[] WIND_HPF_GENTLE = highPassCoefficients(110f);
    private static final float[] WIND_HPF_STRONG = highPassCoefficients(350f);
    private final float[] windGentleState = new float[4];
    private final float[] windStrongState = new float[4];
    private float windLowPass;
    private float windMix;
    private final float[] lpX1 = new float[2];
    private final float[] lpX2 = new float[2];
    private final float[] lpY1 = new float[2];
    private final float[] lpY2 = new float[2];
    private volatile boolean qqVoiceMode = true;
    private volatile boolean rawMicrophone;
    private final float[] playLpX1 = new float[2];
    private final float[] playLpX2 = new float[2];
    private final float[] playLpY1 = new float[2];
    private final float[] playLpY2 = new float[2];
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    public static final String MODEL_RNNOISE = "rnnoise";
    public static final String MODEL_DEEPFILTER = "deepfilternet3";
    /** a2heng/lightweight-denoise-48k, MIT, run through ONNX Runtime. */
    public static final String MODEL_PUREVOX = "lightweight";
    /** Alibaba DFSMN ans 48 kHz causal, exported by DakeQQ/Audio-Denoiser-ONNX. */
    public static final String MODEL_DFSMN = "dfsmn";

    private Rnnoise denoiser;
    private DeepFilterNet deepFilterNet;
    private PureVoxDenoiser pureVox;
    private boolean pureVoxUnavailable;
    private DfsmnDenoiser dfsmn;
    private boolean dfsmnUnavailable;
    private volatile String denoiseModel = MODEL_RNNOISE;
    private volatile float dfnAttenuationDb = 30f;
    private volatile float dfnPostFilter;
    private volatile float rnnoiseWet = 1f;
    private volatile float rnnoiseGate;
    private volatile boolean noiseSuppressionEnabled;
    /** Static capture level, 0..2. Applied after every other stage. */
    private volatile float micGain = 1f;
    /** Three-band voice EQ in dB, applied to the capture path. */
    private volatile float eqLowDb;
    private volatile float eqMidDb;
    private volatile float eqHighDb;
    private volatile int eqVersion;
    private int eqApplied = -1;
    private boolean eqFlat = true;
    private final float[][] eqCoefficients = { new float[5], new float[5], new float[5] };
    private final float[][] eqState = new float[3][4];

    private final Context appContext;

    public AndroidAudioEngine(Context context) {
        Context app = context.getApplicationContext();
        appContext = app;
        audioManager = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
    }

    public void setVoiceEngine(VoiceEngine voice) {
        this.voice = voice;
    }

    public void setUseEarpiece(boolean enabled) {
        useEarpiece = enabled;
    }

    public boolean isUseEarpiece() {
        return useEarpiece;
    }

    public void setVoiceOptimization(boolean enabled) {
        voiceOptimization = enabled;
    }

    public boolean isVoiceOptimization() {
        return voiceOptimization;
    }

    public synchronized void startPlayback() {
        if (playbackRunning) return;
        resetPlaybackProcessing();
        applyCommunicationMode();
        int min = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int buffer = Math.max(min, FRAME * 2 * 4);
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(useEarpiece
                            ? AudioAttributes.USAGE_MEDIA
                            : AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            track = new AudioTrack(attributes, format, buffer,
                    AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                track = null;
                return;
            }
            applyOutputRoute(track);
            enableVoiceProcessing(track.getAudioSessionId());
            track.play();
        } catch (Throwable error) {
            track = null;
            return;
        }
        playbackRunning = true;
        playbackThread = new Thread(this::playbackLoop, "ts3-playback");
        playbackThread.start();
    }

    public synchronized void stopPlayback() {
        playbackRunning = false;
        Thread thread = playbackThread;
        playbackThread = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (track != null) {
            try {
                track.stop();
            } catch (Throwable ignored) {
            }
            track.release();
            track = null;
        }
        releaseVoiceProcessing();
        speakerQueues.clear();
        restoreAudioMode();
    }

    /**
     * A reconnect can leave an AudioTrack that still reports running but whose
     * stream buffer is poisoned or whose queued frames belong to the old
     * session. Recreate the track and clear the per-speaker queues.
     */
    public synchronized void restartPlayback() {
        stopPlayback();
        startPlayback();
    }

    private void applyCommunicationMode() {
        if (audioManager == null) return;
        try {
            // The voice-communication route is often narrowband and applies
            // call DSP, which makes speech sound muffled. Earpiece playback
            // uses the media path with an explicit earpiece device; only the
            // speaker path needs communication mode for echo control.
            audioManager.setMode(useEarpiece
                    ? AudioManager.MODE_NORMAL
                    : AudioManager.MODE_IN_COMMUNICATION);
        } catch (Throwable ignored) {
        }
        applyOutputRoute(track);
    }

    private void applyOutputRoute(AudioTrack output) {
        if (audioManager == null) return;
        AudioDeviceInfo target = findOutputDevice(useEarpiece
                ? AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                : AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
        try {
            audioManager.setSpeakerphoneOn(!useEarpiece);
        } catch (Throwable ignored) {
        }
        if (!useEarpiece && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && target != null) {
            try {
                audioManager.setCommunicationDevice(target);
            } catch (Throwable ignored) {
            }
        }
        if (output != null && target != null) {
            try {
                output.setPreferredDevice(target);
            } catch (Throwable ignored) {
            }
        }
    }

    private AudioDeviceInfo findOutputDevice(int type) {
        if (audioManager == null) return null;
        try {
            for (AudioDeviceInfo device : audioManager.getDevices(
                    AudioManager.GET_DEVICES_OUTPUTS)) {
                if (device.getType() == type) return device;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void restoreAudioMode() {
        if (audioManager == null || playbackRunning || microphoneRunning) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice();
            }
            audioManager.setMode(AudioManager.MODE_NORMAL);
        } catch (Throwable ignored) {
        }
    }

    /** Gentle speech-shaping equalizer; no loudness boost to avoid clipping. */
    private void enableVoiceProcessing(int sessionId) {
        if (!voiceOptimization || sessionId == 0) return;
        releaseVoiceProcessing();
        try {
            Equalizer eq = new Equalizer(0, sessionId);
            short[] range = eq.getBandLevelRange();
            int bands = eq.getNumberOfBands();
            for (short band = 0; band < bands; band++) {
                int hz = eq.getCenterFreq(band) / 1000;
                int level;
                if (hz < 300) level = -300;
                else if (hz < 800) level = -100;
                else if (hz < 2_500) level = 100;
                else if (hz < 6_000) level = 200;
                else level = 0;
                level = Math.max(range[0], Math.min(range[1], level));
                eq.setBandLevel(band, (short) level);
            }
            eq.setEnabled(true);
            equalizer = eq;
        } catch (Throwable ignored) {
        }
    }

    private void releaseVoiceProcessing() {
        if (equalizer != null) {
            try {
                equalizer.setEnabled(false);
                equalizer.release();
            } catch (Throwable ignored) {
            }
            equalizer = null;
        }
    }

    private void playbackLoop() {
        Thread self = Thread.currentThread();
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        short[] mix = new short[FRAME];
        long next = System.currentTimeMillis();
        while (playbackRunning && playbackThread == self) {
            Arrays.fill(mix, (short) 0);
            boolean any = false;
            for (ArrayDeque<short[]> queue : speakerQueues.values()) {
                short[] frame = queue.poll();
                if (frame == null) continue;
                any = true;
                for (int i = 0; i < FRAME && i < frame.length; i++) {
                    int value = mix[i] + frame[i];
                    if (value > 32767) value = 32767;
                    if (value < -32768) value = -32768;
                    mix[i] = (short) value;
                }
            }
            AudioTrack output = track;
            if (output != null) {
                if (qqVoiceMode) applyPlaybackLowPass(mix, FRAME);
                try {
                    output.write(mix, 0, FRAME);
                } catch (Throwable ignored) {
                    // A routing change can fail one write; the next frame retries.
                }
            }
            next += 20;
            long sleep = next - System.currentTimeMillis();
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else if (sleep < -100) {
                next = System.currentTimeMillis();
            }
            if (!any) {
                // Silence keeps the track fed, so the next speaker starts cleanly.
            }
        }
    }

    private void applyPlaybackLowPass(short[] samples, int length) {
        // Same non-resonant 8 kHz pair as the capture path.
        final float b0 = 0.155050f;
        final float b1 = 0.310100f;
        final float b2 = 0.155050f;
        final float a1 = -0.620213f;
        final float a2 = 0.240399f;
        for (int i = 0; i < length; i++) {
            float x = samples[i] / 32768f;
            for (int stage = 0; stage < 2; stage++) {
                float y = b0 * x + b1 * playLpX1[stage] + b2 * playLpX2[stage]
                        - a1 * playLpY1[stage] - a2 * playLpY2[stage];
                playLpX2[stage] = playLpX1[stage];
                playLpX1[stage] = x;
                playLpY2[stage] = playLpY1[stage];
                playLpY1[stage] = y;
                x = y;
            }
            int value = Math.round(x * 32767f);
            if (value > 32767) value = 32767;
            if (value < -32768) value = -32768;
            samples[i] = (short) value;
        }
    }

    private void resetPlaybackProcessing() {
        java.util.Arrays.fill(playLpX1, 0f);
        java.util.Arrays.fill(playLpX2, 0f);
        java.util.Arrays.fill(playLpY1, 0f);
        java.util.Arrays.fill(playLpY2, 0f);
    }

    @Override
    public void onPcm(int clientId, short[] samples, int length) {
        if (samples == null || length <= 0) return;
        ArrayDeque<short[]> queue = speakerQueues.computeIfAbsent(clientId,
                key -> new ArrayDeque<>());
        int offset = 0;
        while (offset < length) {
            short[] frame = new short[FRAME];
            int copy = Math.min(FRAME, length - offset);
            System.arraycopy(samples, offset, frame, 0, copy);
            queue.addLast(frame);
            while (queue.size() > 4) queue.removeFirst();
            offset += copy;
        }
    }

    public synchronized boolean startMicrophone(Context context) {
        if (microphoneRunning) return true;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        captureRate = SAMPLE_RATE;
        applyCommunicationMode();
        record = openRecord(SAMPLE_RATE);
        if (record == null) {
            captureRate = 44_100;
            record = openRecord(captureRate);
        }
        if (record == null) return false;

        try {
            int session = record.getAudioSessionId();
            try {
                if (!rawMicrophone && !useEarpiece && AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(session);
                    if (echoCanceler != null) echoCanceler.setEnabled(true);
                }
            } catch (Throwable ignored) {
                echoCanceler = null;
            }
            try {
                if (NoiseSuppressor.isAvailable()) {
                    // RNNoise already suppresses noise on the encoder thread;
                    // keep the platform effect only as a fallback.
                    if (!rawMicrophone
                            && !(noiseSuppressionEnabled && Rnnoise.isAvailable())) {
                        noiseSuppressor = NoiseSuppressor.create(session);
                        if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
                    }
                }
            } catch (Throwable ignored) {
                noiseSuppressor = null;
            }
            record.startRecording();
        } catch (Throwable error) {
            releaseMicrophone();
            return false;
        }
        resamplePosition = 0;
        resampleLast = 0;
        resampleCount = 0;
        resetMicrophoneProcessing();
        microphoneRunning = true;
        startEncoder();
        microphoneThread = new Thread(this::microphoneLoop, "ts3-microphone");
        microphoneThread.start();
        return true;
    }

    @SuppressLint("MissingPermission") // caller checked RECORD_AUDIO in startMicrophone
    private AudioRecord openRecord(int rate) {
        int[] sources;
        if (rawMicrophone) {
            sources = new int[]{MediaRecorder.AudioSource.UNPROCESSED,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION};
        } else if (useEarpiece) {
            sources = new int[]{MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.UNPROCESSED,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION};
        } else {
            sources = new int[]{MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.MIC};
        }
        for (int source : sources) {
            int min = AudioRecord.getMinBufferSize(rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int buffer = Math.max(min, FRAME * 2 * 4);
            try {
                AudioRecord candidate = new AudioRecord(source, rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer);
                if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                    return candidate;
                }
                candidate.release();
            } catch (Throwable ignored) {
                // Try the next source or sample rate.
            }
        }
        return null;
    }

    private void microphoneLoop() {
        Thread self = Thread.currentThread();
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        // Read exactly one Opus frame at a time. Reading a large block (the old
        // 4096 samples) delivered 80 ms of PCM in one burst, so the encoder
        // produced several packets at once and ts3j's 20 ms sender saw an
        // empty queue between bursts. That bursty pacing was audible as
        // choppy speech to other clients.
        short[] buffer = new short[FRAME];
        VoiceEngine engine = voice;
        while (microphoneRunning && microphoneThread == self) {
            AudioRecord input = record;
            if (input == null) break;
            int read;
            try {
                read = input.read(buffer, 0, buffer.length);
            } catch (Throwable error) {
                break;
            }
            if (read <= 0 || engine == null) continue;
            // Keep capture non-blocking: encoding happens on a separate thread.
            short[] frame = new short[read];
            System.arraycopy(buffer, 0, frame, 0, read);
            synchronized (captureLock) {
                captureQueue.addLast(frame);
                while (captureQueue.size() > 16) captureQueue.removeFirst();
                captureLock.notifyAll();
            }
        }
    }

    private synchronized void startEncoder() {
        if (encoderThread != null && encoderThread.isAlive()) return;
        encoderRunning = true;
        encoderThread = new Thread(this::encoderLoop, "ts3-encoder");
        encoderThread.setDaemon(true);
        encoderThread.start();
    }

    private synchronized void stopEncoder() {
        encoderRunning = false;
        Thread thread = encoderThread;
        encoderThread = null;
        synchronized (captureLock) {
            captureLock.notifyAll();
            captureQueue.clear();
        }
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (denoiser != null) {
            denoiser.close();
            denoiser = null;
        }
        if (deepFilterNet != null) {
            deepFilterNet.close();
            deepFilterNet = null;
        }
        // The ONNX session owns a native handle; release it with the encoder
        // thread stopped so no hop can be in flight.
        if (pureVox != null) {
            pureVox.close();
            pureVox = null;
        }
        if (dfsmn != null) {
            dfsmn.close();
            dfsmn = null;
        }
    }

    private void encoderLoop() {
        Thread self = Thread.currentThread();
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        VoiceEngine engine = voice;
        while (encoderRunning && encoderThread == self) {
            short[] frame;
            synchronized (captureLock) {
                while (encoderRunning && encoderThread == self && captureQueue.isEmpty()) {
                    try {
                        captureLock.wait(100);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                frame = captureQueue.pollFirst();
            }
            if (frame == null) continue;
            if (engine != null) {
                if (captureRate == SAMPLE_RATE) pushFiltered(frame, frame.length, engine);
                else resampleAndPush(frame, frame.length, engine);
            }
        }
    }

    private void pushFiltered(short[] samples, int length, VoiceEngine engine) {
        if (!rawMicrophone) {
            sharpen(samples, length);
            applyMicEq(samples, length);
        }
        // Level control stays available even in raw mode: it is a user-facing
        // volume, not a processing stage.
        applyMicGain(samples, length);
        engine.pushPcm(samples, length);
    }

    /** Wind suppression, the selected denoiser and the band limit, in that order. */
    private void sharpen(short[] samples, int length) {
        // Always on: wind and rumble are not an optional "clarity" feature, and
        // a gust that reaches the encoder also pumps every later stage.
        windSuppress(samples, length);
        if (noiseSuppressionEnabled && MODEL_PUREVOX.equals(denoiseModel)) {
            // Creating the ONNX Runtime session reads a ~10 MB model, so it is
            // done on the encoder thread at the first hop instead of on the UI
            // thread. A failure leaves the lighter denoisers in charge.
            if (pureVox == null && !pureVoxUnavailable) {
                try {
                    pureVox = PureVoxDenoiser.load(appContext);
                } catch (Throwable error) {
                    pureVoxUnavailable = true;
                }
            }
            if (pureVox != null) {
                pureVox.process(samples, length);
                postProcessMicrophone(samples, length);
                return;
            }
        }
        if (noiseSuppressionEnabled && MODEL_DFSMN.equals(denoiseModel)) {
            // A causal 48 kHz model: the driver slides a window over the last
            // few hops and keeps the hop that has the full window as history.
            if (dfsmn == null && !dfsmnUnavailable) {
                try {
                    dfsmn = DfsmnDenoiser.load(appContext);
                } catch (Throwable error) {
                    dfsmnUnavailable = true;
                }
            }
            if (dfsmn != null) {
                dfsmn.process(samples, length);
                postProcessMicrophone(samples, length);
                return;
            }
        }
        if (noiseSuppressionEnabled
                && MODEL_DEEPFILTER.equals(denoiseModel)
                && DeepFilterNet.isAvailable()) {
            if (deepFilterNet == null) {
                DeepFilterNet created = new DeepFilterNet();
                created.setAttenuationLimit(dfnAttenuationDb);
                created.setPostFilter(dfnPostFilter);
                deepFilterNet = created;
            }
            if (deepFilterNet.handleAvailable()) {
                deepFilterNet.process(samples, length);
                postProcessMicrophone(samples, length);
                return;
            }
        }
        if (noiseSuppressionEnabled && Rnnoise.isAvailable()) {
            if (denoiser == null) denoiser = new Rnnoise();
            if (denoiser.handleAvailable()) {
                if (denoiseDry.length < length) denoiseDry = new short[length];
                System.arraycopy(samples, 0, denoiseDry, 0, length);
                float vad = denoiser.process(samples, length);
                float wet = rnnoiseWet;
                float gate = rnnoiseGate;
                for (int i = 0; i < length; i++) {
                    // RNNoise overlap-add output is one 10 ms frame late.
                    // Mixing it with undelayed dry audio creates a 50 Hz comb
                    // (the metallic/electric sound); align the dry path.
                    short delayedDry = rnnoiseDryDelay[rnnoiseDryDelayPos];
                    rnnoiseDryDelay[rnnoiseDryDelayPos] = denoiseDry[i];
                    rnnoiseDryDelayPos++;
                    if (rnnoiseDryDelayPos >= rnnoiseDryDelay.length) {
                        rnnoiseDryDelayPos = 0;
                    }
                    float mixed = samples[i] * wet + delayedDry * (1f - wet);
                    if (gate > 0f && vad < gate) {
                        mixed *= Math.max(0.05f, vad / gate);
                    }
                    int value = Math.round(mixed);
                    if (value > 32767) value = 32767;
                    if (value < -32768) value = -32768;
                    samples[i] = (short) value;
                }
            }
        }
        postProcessMicrophone(samples, length);
    }

    /**
     * Static microphone level, applied last so every earlier stage sees the
     * natural level. No dynamics: an automatic gain here is exactly what used to
     * make the "s" of "4" break.
     */
    private void applyMicGain(short[] samples, int length) {
        float gain = micGain;
        if (gain == 1f) return;
        for (int i = 0; i < length; i++) {
            int value = Math.round(samples[i] * gain);
            if (value > 32767) value = 32767;
            if (value < -32768) value = -32768;
            samples[i] = (short) value;
        }
    }

    /**
     * Three-band voice EQ: low shelf at 200 Hz, presence peak at 1 kHz and high
     * shelf at 4 kHz, each ±12 dB.
     *
     * <p>Biquads in the capture chain rather than Android's
     * {@code android.media.audiofx.Equalizer}: capture-session effects are
     * device-dependent and are silently ignored on many phones, while these
     * coefficients give the same response everywhere and cost three multiplies
     * per sample. A slider movement only bumps a version counter; the
     * coefficients are rebuilt on the encoder thread before the next frame, so
     * no frame is ever filtered with half-updated coefficients.
     */
    private void applyMicEq(short[] samples, int length) {
        if (eqVersion != eqApplied) {
            eqApplied = eqVersion;
            buildMicEq();
        }
        if (eqFlat) return;
        for (int i = 0; i < length; i++) {
            float x = samples[i] / 32768f;
            for (int band = 0; band < 3; band++) {
                float[] c = eqCoefficients[band];
                float[] s = eqState[band];
                float y = c[0] * x + c[1] * s[0] + c[2] * s[1] - c[3] * s[2] - c[4] * s[3];
                s[1] = s[0];
                s[0] = x;
                s[3] = s[2];
                s[2] = y;
                x = y;
            }
            int value = Math.round(x * 32767f);
            if (value > 32767) value = 32767;
            if (value < -32768) value = -32768;
            samples[i] = (short) value;
        }
    }

    private void buildMicEq() {
        float low = eqLowDb;
        float mid = eqMidDb;
        float high = eqHighDb;
        eqFlat = low == 0f && mid == 0f && high == 0f;
        if (eqFlat) return;
        shelf(eqCoefficients[0], 200f, low, true);
        peak(eqCoefficients[1], 1_000f, 1.0, mid);
        shelf(eqCoefficients[2], 4_000f, high, false);
    }

    /** RBJ shelf biquad, Q = 0.7071, normalised by a0. */
    private static void shelf(float[] target, float hz, float gainDb, boolean lowShelf) {
        double a = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * hz / SAMPLE_RATE;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / 2.0 * Math.sqrt(2.0);
        double twoSqrtAAlpha = 2.0 * Math.sqrt(a) * alpha;
        double b0, b1, b2, a0, a1, a2;
        if (lowShelf) {
            b0 = a * ((a + 1) - (a - 1) * cos + twoSqrtAAlpha);
            b1 = 2 * a * ((a - 1) - (a + 1) * cos);
            b2 = a * ((a + 1) - (a - 1) * cos - twoSqrtAAlpha);
            a0 = (a + 1) + (a - 1) * cos + twoSqrtAAlpha;
            a1 = -2 * ((a - 1) + (a + 1) * cos);
            a2 = (a + 1) + (a - 1) * cos - twoSqrtAAlpha;
        } else {
            b0 = a * ((a + 1) + (a - 1) * cos + twoSqrtAAlpha);
            b1 = -2 * a * ((a - 1) + (a + 1) * cos);
            b2 = a * ((a + 1) + (a - 1) * cos - twoSqrtAAlpha);
            a0 = (a + 1) - (a - 1) * cos + twoSqrtAAlpha;
            a1 = 2 * ((a - 1) - (a + 1) * cos);
            a2 = (a + 1) - (a - 1) * cos - twoSqrtAAlpha;
        }
        target[0] = (float) (b0 / a0);
        target[1] = (float) (b1 / a0);
        target[2] = (float) (b2 / a0);
        target[3] = (float) (a1 / a0);
        target[4] = (float) (a2 / a0);
    }

    /** RBJ peaking biquad, normalised by a0. */
    private static void peak(float[] target, float hz, double q, float gainDb) {
        double a = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * hz / SAMPLE_RATE;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * q);
        double a0 = 1.0 + alpha / a;
        target[0] = (float) ((1.0 + alpha * a) / a0);
        target[1] = (float) ((-2.0 * cos) / a0);
        target[2] = (float) ((1.0 - alpha * a) / a0);
        target[3] = (float) ((-2.0 * cos) / a0);
        target[4] = (float) ((1.0 - alpha / a) / a0);
    }

    /**
     * QQ-like voice mode: an 8 kHz low-pass removes the high-frequency buzz
     * that makes a poor phone microphone sound electric.
     *
     * <p>There is deliberately no gain stage here. The earlier implementation
     * rescaled every 20 ms frame to a fixed peak, so the gain changed once per
     * frame with no lookahead and no interpolation between frames. That step
     * landed on the loudest attack of the sentence (the /s/ of "4", for
     * example) and was heard as a broken syllable. A low-pass on its own cannot
     * clip or modulate, so this mode no longer damages speech.
     */
    private void postProcessMicrophone(short[] samples, int length) {
        if (!qqVoiceMode || length <= 0) return;
        // Two non-resonant sections at 8 kHz for a 48 kHz sample rate. The
        // previous coefficients had Q = 1.22 per section, which put a +3.5 dB
        // resonance right on the 5-8 kHz band where a sibilant lives.
        final float b0 = 0.155050f;
        final float b1 = 0.310100f;
        final float b2 = 0.155050f;
        final float a1 = -0.620213f;
        final float a2 = 0.240399f;
        for (int i = 0; i < length; i++) {
            float x = samples[i] / 32768f;
            for (int stage = 0; stage < 2; stage++) {
                float y = b0 * x + b1 * lpX1[stage] + b2 * lpX2[stage]
                        - a1 * lpY1[stage] - a2 * lpY2[stage];
                lpX2[stage] = lpX1[stage];
                lpX1[stage] = x;
                lpY2[stage] = lpY1[stage];
                lpY1[stage] = y;
                x = y;
            }
            int value = Math.round(x * 32767f);
            if (value > 32767) value = 32767;
            if (value < -32768) value = -32768;
            samples[i] = (short) value;
        }
    }

    public void setQqVoiceMode(boolean enabled) {
        qqVoiceMode = enabled;
    }

    public boolean isQqVoiceMode() {
        return qqVoiceMode;
    }

    public void setRawMicrophone(boolean enabled) {
        rawMicrophone = enabled;
    }

    public boolean isRawMicrophone() {
        return rawMicrophone;
    }

    /** Static capture gain, 0..2. 1 is unity. */
    public void setMicGain(float gain) {
        micGain = Math.max(0f, Math.min(2f, gain));
    }

    public float getMicGain() {
        return micGain;
    }

    /** Three-band voice EQ gains in dB, each clamped to ±12. */
    public void setMicEq(float lowDb, float midDb, float highDb) {
        eqLowDb = Math.max(-12f, Math.min(12f, lowDb));
        eqMidDb = Math.max(-12f, Math.min(12f, midDb));
        eqHighDb = Math.max(-12f, Math.min(12f, highDb));
        eqVersion++;
    }

    public float getEqLowDb() {
        return eqLowDb;
    }

    public float getEqMidDb() {
        return eqMidDb;
    }

    public float getEqHighDb() {
        return eqHighDb;
    }

    public void setRnnoiseWet(float wet) {
        rnnoiseWet = Math.max(0f, Math.min(1f, wet));
    }

    public float getRnnoiseWet() {
        return rnnoiseWet;
    }

    public void setRnnoiseGate(float gate) {
        rnnoiseGate = Math.max(0f, Math.min(1f, gate));
    }

    public float getRnnoiseGate() {
        return rnnoiseGate;
    }

    public void setDenoiseModel(String model) {
        if (MODEL_DEEPFILTER.equals(model)) denoiseModel = MODEL_DEEPFILTER;
        else if (MODEL_PUREVOX.equals(model)) denoiseModel = MODEL_PUREVOX;
        else if (MODEL_DFSMN.equals(model)) denoiseModel = MODEL_DFSMN;
        else denoiseModel = MODEL_RNNOISE;
    }

    public String getDenoiseModel() {
        return denoiseModel;
    }

    public void setDfnAttenuationDb(float db) {
        dfnAttenuationDb = Math.max(0f, Math.min(100f, db));
    }

    public float getDfnAttenuationDb() {
        return dfnAttenuationDb;
    }

    public void setDfnPostFilter(float beta) {
        dfnPostFilter = Math.max(0f, Math.min(0.05f, beta));
    }

    public float getDfnPostFilter() {
        return dfnPostFilter;
    }

    /**
     * Wind and room rumble suppression.
     *
     * <p>A gentle 110 Hz high-pass is always applied. While low-frequency energy
     * dominates the frame - the signature of a wind gust or a hand over the
     * microphone - a second 350 Hz corner is crossfaded in, so up to 24 dB/oct
     * of rumble is removed only while it is actually there.
     *
     * <p>This is deliberately not a gain stage. Wind lives below about 500 Hz
     * and a sibilant lives at 5-8 kHz, so filtering can remove the gust without
     * touching the "s" of "4". Both paths are biquads and the crossfade is
     * interpolated across the frame, so the waveform is never stepped.
     */
    private void windSuppress(short[] samples, int length) {
        if (length <= 0) return;
        float lowSum = 0f;
        float totalSum = 0f;
        for (int i = 0; i < length; i++) {
            float x = samples[i] / 32768f;
            windLowPass += (x - windLowPass) * 0.019444f;
            lowSum += windLowPass * windLowPass;
            totalSum += x * x;
        }
        float level = (float) Math.sqrt(totalSum / length);
        float lowRatio = totalSum > 1e-9f ? lowSum / totalSum : 0f;
        // Even a deep voice keeps most of its energy above the rumble band, so
        // dominance plus a level floor separates a gust from ordinary speech.
        float target = lowRatio > 0.6f && level > 0.01f ? 1f : 0f;
        float from = windMix;
        windMix += (target - windMix) * 0.2f;
        float delta = windMix - from;
        for (int i = 0; i < length; i++) {
            float x = samples[i] / 32768f;
            float gentle = applyBiquad(x, WIND_HPF_GENTLE, windGentleState);
            float strong = applyBiquad(gentle, WIND_HPF_STRONG, windStrongState);
            float mix = from + delta * ((i + 1f) / length);
            int value = Math.round((gentle + (strong - gentle) * mix) * 32767f);
            if (value > 32767) value = 32767;
            if (value < -32768) value = -32768;
            samples[i] = (short) value;
        }
    }

    /** RBJ high-pass biquad with Q = 0.7071, normalised by a0. */
    private static float[] highPassCoefficients(float cornerHz) {
        double w0 = 2.0 * Math.PI * cornerHz / SAMPLE_RATE;
        double cos = Math.cos(w0);
        double sin = Math.sin(w0);
        double alpha = sin / (2.0 * 0.7071067811865476);
        double a0 = 1.0 + alpha;
        float b0 = (float) (((1.0 + cos) / 2.0) / a0);
        float b1 = (float) (-(1.0 + cos) / a0);
        float a1 = (float) (-2.0 * cos / a0);
        float a2 = (float) ((1.0 - alpha) / a0);
        return new float[] { b0, b1, b0, a1, a2 };
    }

    private static float applyBiquad(float x, float[] coefficients, float[] state) {
        float y = coefficients[0] * x + coefficients[1] * state[0] + coefficients[2] * state[1]
                - coefficients[3] * state[2] - coefficients[4] * state[3];
        state[1] = state[0];
        state[0] = x;
        state[3] = state[2];
        state[2] = y;
        return y;
    }

    private void resetMicrophoneProcessing() {
        java.util.Arrays.fill(windGentleState, 0f);
        for (float[] band : eqState) java.util.Arrays.fill(band, 0f);
        java.util.Arrays.fill(windStrongState, 0f);
        windLowPass = 0f;
        windMix = 0f;
        rnnoiseDryDelayPos = 0;
        java.util.Arrays.fill(rnnoiseDryDelay, (short) 0);
        java.util.Arrays.fill(lpX1, 0f);
        java.util.Arrays.fill(lpX2, 0f);
        java.util.Arrays.fill(lpY1, 0f);
        java.util.Arrays.fill(lpY2, 0f);
    }

    public void setNoiseSuppressionEnabled(boolean enabled) {
        noiseSuppressionEnabled = enabled;
        if (noiseSuppressor != null) {
            try {
                noiseSuppressor.setEnabled(enabled && !Rnnoise.isAvailable());
            } catch (Throwable ignored) {
            }
        }
    }

    public boolean isNoiseSuppressionEnabled() {
        return noiseSuppressionEnabled;
    }

    /** Linear 44.1 kHz -> 48 kHz conversion for devices without 48 kHz capture. */
    private void resampleAndPush(short[] input, int length, VoiceEngine engine) {
        double step = (double) SAMPLE_RATE / captureRate;
        for (int i = 0; i < length; i++) {
            short current = input[i];
            while (resamplePosition < 1.0) {
                double fraction = resamplePosition;
                int value = (int) Math.round(resampleLast
                        + (current - resampleLast) * fraction);
                if (value > 32767) value = 32767;
                if (value < -32768) value = -32768;
                resampleFrame[resampleCount++] = (short) value;
                if (resampleCount == FRAME) {
                    pushFiltered(resampleFrame, FRAME, engine);
                    resampleCount = 0;
                }
                resamplePosition += step;
            }
            resamplePosition -= 1.0;
            resampleLast = current;
        }
    }

    public synchronized void stopMicrophone() {
        microphoneRunning = false;
        Thread thread = microphoneThread;
        microphoneThread = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        stopEncoder();
        releaseMicrophone();
        restoreAudioMode();
    }

    private void releaseMicrophone() {
        if (record != null) {
            try {
                record.stop();
            } catch (Throwable ignored) {
            }
            record.release();
            record = null;
        }
        if (echoCanceler != null) {
            try {
                echoCanceler.release();
            } catch (Throwable ignored) {
            }
            echoCanceler = null;
        }
        if (noiseSuppressor != null) {
            try {
                noiseSuppressor.release();
            } catch (Throwable ignored) {
            }
            noiseSuppressor = null;
        }
    }

    public boolean isMicrophoneRunning() {
        return microphoneRunning;
    }

    public synchronized void close() {
        stopMicrophone();
        stopPlayback();
    }
}
