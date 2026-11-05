package ai.nc.varcospaces;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Short local PCM loops, loaded once into static native buffers. Never auto-resumes. */
final class AudioEngine implements AutoCloseable {
    interface Listener {
        boolean mayPlay();
        void changed(String error);
        default void focusLost(int change) { }
        default void timerExpired() { }
    }
    private final Context context;
    private final Handler handler;
    private final Listener listener;
    private final AudioManager audioManager;
    private final boolean debug;
    private final List<Layer> players = new ArrayList<>();
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "varco-audio-load"); thread.setDaemon(true); return thread;
    });
    private Future<?> prepareTask;
    private Runnable preparationTimeout;
    private AudioFocusRequest focus;
    private int generation;
    private boolean playing, preparing, closed;
    private float duck = 1;
    private final float[] volumes = {0.65f, 0.35f, 0.2f};
    private int timerMinutes = 10;
    private long remainingMs = 600_000;
    private long lastTick;
    private final Runnable tick = new Runnable() {
        public void run() {
            if (!playing) return;
            updateTime();
            if (remainingMs <= 0) { listener.timerExpired(); pause(); return; }
            if (!listener.mayPlay()) { pause(); return; }
            for (Layer layer : players) {
                if (layer.track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                    failPlayback("음원 재생이 중단되었어요. 다시 시도해 주세요."); return;
                }
            }
            listener.changed(""); handler.postDelayed(this, Math.min(1000, remainingMs));
        }
    };
    private static final class Source {
        final String file; final int index;
        Source(String file, int index) { this.file = file; this.index = index; }
    }
    private static final class Prepared {
        final PcmWave pcm; final int index;
        Prepared(PcmWave pcm, int index) { this.pcm = pcm; this.index = index; }
    }
    private static final class Layer {
        final AudioTrack track; final int index;
        Layer(AudioTrack track, int index) { this.track = track; this.index = index; }
    }
    AudioEngine(Context context, Handler handler, Listener listener) {
        this.context = context; this.handler = handler; this.listener = listener;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        debug = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }
    void play(JSONObject scene) {
        pause();
        if (closed) return;
        if (!listener.mayPlay()) { listener.changed("주차 상태에서 이용할 수 있어요."); return; }
        if (scene == null || !scene.optBoolean("available", false)) {
            listener.changed("최종 VARCO 에셋이 아직 등록되지 않았어요."); return;
        }
        final int token = ++generation;
        final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        try {
            List<Source> sources = new ArrayList<>();
            JSONArray layers = scene.getJSONArray("layers");
            for (int i = 0; i < Math.min(3, layers.length()); i++) {
                JSONObject data = layers.getJSONObject(i);
                if (!data.optBoolean("available", false)) continue;
                String file = data.optString("file", "");
                if (!file.startsWith("content/") || !SafetyPolicy.isPackagedPath(file)) throw new IOException("Invalid audio path");
                sources.add(new Source(file, i));
            }
            if (sources.isEmpty()) throw new IOException("No packaged audio");
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(change -> {
                    if (token != generation) return;
                    log("focus_change=" + change + " playing=" + playing + " preparing=" + preparing);
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        listener.focusLost(change); pause();
                    }
                    else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                        duck = 0.18f; if (!applyVolumes()) failPlayback("음량을 변경하지 못했어요.");
                    } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                        duck = 1; if (!applyVolumes()) failPlayback("음량을 변경하지 못했어요.");
                    }
                }, handler).build();
            if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                failPlayback("다른 오디오가 사용 중이에요. 잠시 후 다시 시도해 주세요."); return;
            }
            preparing = true;
            preparationTimeout = () -> {
                if (token == generation && preparing) failPlayback("음원 준비 시간이 초과되었어요. 다시 시도해 주세요.");
            };
            handler.postDelayed(preparationTimeout, 8000);
            // Only PCM arrays cross the thread boundary. A cancelled callback owns no
            // native AudioTrack, even if Activity destruction removes pending messages.
            prepareTask = loader.submit(() -> {
                try {
                    List<Prepared> ready = new ArrayList<>();
                    for (Source source : sources) {
                        try (InputStream input = context.getAssets().open(source.file)) {
                            ready.add(new Prepared(PcmWave.read(input), source.index));
                        }
                    }
                    if (!Thread.currentThread().isInterrupted()) handler.post(() -> startWhenReady(token, attributes, ready));
                } catch (IOException | RuntimeException error) {
                    handler.post(() -> {
                        if (token == generation && preparing) failPlayback("음원 파일을 확인할 수 없어요.");
                    });
                }
            });
            listener.changed("");
        } catch (Exception error) { failPlayback("음원 파일을 확인할 수 없어요."); }
    }
    private void startWhenReady(int token, AudioAttributes attributes, List<Prepared> ready) {
        if (token != generation || !preparing || closed) return;
        clearPreparationTimeout(); prepareTask = null;
        if (!listener.mayPlay()) { pause(); return; }
        try {
            for (Prepared item : ready) {
                PcmWave pcm = item.pcm;
                AudioTrack track = new AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(pcm.sampleRate).setChannelMask(pcm.channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.samples.length * 2).build();
                players.add(new Layer(track, item.index));
                if (track.write(pcm.samples, 0, pcm.samples.length, AudioTrack.WRITE_BLOCKING) != pcm.samples.length
                    || track.getState() != AudioTrack.STATE_INITIALIZED
                    || track.setLoopPoints(0, pcm.frames(), -1) != AudioTrack.SUCCESS) throw new IOException("Static buffer setup failed");
                log("static_prepared layer=" + item.index + " session=" + track.getAudioSessionId() + " frames=" + pcm.frames() + " rate=" + pcm.sampleRate);
            }
            if (!listener.mayPlay()) { pause(); return; }
            if (!applyVolumes()) throw new IOException("Volume setup failed");
            if (remainingMs <= 0) remainingMs = timerMinutes * 60_000L;
            lastTick = SystemClock.elapsedRealtime();
            for (Layer layer : players) layer.track.play();
            preparing = false; playing = true;
            handler.post(tick); listener.changed("");
        } catch (IOException | RuntimeException error) { failPlayback("재생을 시작하지 못했어요."); }
    }
    private void failPlayback(String message) { pause(); listener.changed(message); }
    private void clearPreparationTimeout() {
        if (preparationTimeout != null) handler.removeCallbacks(preparationTimeout);
        preparationTimeout = null;
    }
    void pause() {
        updateTime(); ++generation; preparing = false; playing = false; handler.removeCallbacks(tick);
        clearPreparationTimeout();
        if (prepareTask != null) prepareTask.cancel(true);
        prepareTask = null;
        for (Layer layer : players) {
            try {
                log("static_release layer=" + layer.index + " session=" + layer.track.getAudioSessionId() + " underruns=" + layer.track.getUnderrunCount());
                // Stop immediately: static buffers must not continue after the parked gate closes.
                layer.track.pause(); layer.track.stop();
            } catch (RuntimeException ignored) { }
            finally { try { layer.track.release(); } catch (RuntimeException ignored) { } }
        }
        players.clear();
        if (focus != null) { try { audioManager.abandonAudioFocusRequest(focus); } catch (RuntimeException ignored) { } }
        focus = null; duck = 1; listener.changed("");
    }
    private void updateTime() {
        if (playing) { long now = SystemClock.elapsedRealtime(); remainingMs = Math.max(0, remainingMs - (now - lastTick)); lastTick = now; }
    }
    void setVolume(int index, double volume) {
        if (index < 0 || index >= 3) return;
        volumes[index] = SafetyPolicy.volume(volume);
        if (!applyVolumes()) failPlayback("음량을 변경하지 못했어요.");
    }
    static float[] layerGains(float[] values, boolean[] present, float attenuation) {
        float[] gains = new float[3]; float sum = 0;
        for (int i = 0; i < 3; i++) if (present[i]) sum += SafetyPolicy.volume(values[i]);
        float scale = 0.8f / Math.max(1f, sum) * SafetyPolicy.volume(attenuation);
        for (int i = 0; i < 3; i++) if (present[i]) gains[i] = SafetyPolicy.volume(values[i]) * scale;
        return gains;
    }
    private boolean applyVolumes() {
        boolean[] present = new boolean[3];
        for (Layer layer : players) present[layer.index] = true;
        float[] gains = layerGains(volumes, present, duck);
        try {
            for (Layer layer : players) {
                if (layer.track.setVolume(gains[layer.index]) != AudioTrack.SUCCESS) return false;
                log("volume layer=" + layer.index + " gain=" + gains[layer.index] + " duck=" + duck);
            }
            return true;
        } catch (RuntimeException error) { return false; }
    }
    void setTimer(int minutes) {
        if (minutes != 5 && minutes != 10 && minutes != 15) return;
        timerMinutes = minutes; remainingMs = minutes * 60_000L; lastTick = SystemClock.elapsedRealtime(); listener.changed("");
    }
    void selectScene(JSONObject scene) {
        pause();
        JSONArray layers = scene == null ? null : scene.optJSONArray("layers");
        for (int i = 0; i < 3; i++) {
            JSONObject layer = layers == null ? null : layers.optJSONObject(i);
            volumes[i] = layer == null || !layer.optBoolean("available", false) ? 0 : SafetyPolicy.volume(layer.optDouble("volume", 0.5));
        }
        remainingMs = timerMinutes * 60_000L; listener.changed("");
    }
    int getTimer() { return timerMinutes; }
    int remainingSeconds() { updateTime(); return (int)Math.ceil(remainingMs / 1000.0); }
    boolean isPlaying() { return playing; }
    boolean isPreparing() { return preparing; }
    JSONArray volumesJson() { JSONArray a = new JSONArray(); for (float v : volumes) a.put(Float.valueOf(v)); return a; }
    private void log(String message) { if (debug) Log.d("VarcoAudio", message); }
    @Override public void close() { closed = true; pause(); loader.shutdownNow(); }

    /** Bounded, strict RIFF PCM16 reader; supports metadata chunks and odd-byte padding. */
    static final class PcmWave {
        static final int MAX_PCM_BYTES = 8 * 1024 * 1024;
        final short[] samples; final int sampleRate, channels;
        PcmWave(short[] samples, int sampleRate, int channels) { this.samples = samples; this.sampleRate = sampleRate; this.channels = channels; }
        int frames() { return samples.length / channels; }
        static PcmWave read(InputStream input) throws IOException {
            byte[] header = new byte[12]; readFully(input, header, 0, 12);
            if (!tag(header, 0, "RIFF") || !tag(header, 8, "WAVE")) throw new IOException("Not RIFF WAVE");
            long remaining = u32(header, 4) - 4;
            if (remaining < 36 || remaining > MAX_PCM_BYTES + 1024 * 1024) throw new IOException("WAV size outside budget");
            boolean formatSeen = false; int rate = 0, channels = 0, alignment = 0; short[] samples = null;
            while (remaining >= 8) {
                byte[] chunk = new byte[8]; readFully(input, chunk, 0, 8); remaining -= 8;
                long length = u32(chunk, 4), padded = length + (length & 1);
                if (padded > remaining) throw new IOException("Chunk exceeds RIFF size");
                if (tag(chunk, 0, "fmt ")) {
                    if (formatSeen || length < 16 || length > 4096) throw new IOException("Invalid format chunk");
                    byte[] format = new byte[(int)length]; readFully(input, format, 0, format.length);
                    channels = u16(format, 2); rate = (int)u32(format, 4); alignment = u16(format, 12);
                    if (u16(format, 0) != 1 || u16(format, 14) != 16 || (channels != 1 && channels != 2)
                        || rate < 8000 || rate > 96000 || alignment != channels * 2 || u32(format, 8) != (long)rate * alignment)
                        throw new IOException("Expected mono/stereo PCM16 WAV");
                    formatSeen = true;
                } else if (tag(chunk, 0, "data")) {
                    if (!formatSeen || samples != null || length < alignment * 16L || length > MAX_PCM_BYTES || length % alignment != 0)
                        throw new IOException("Invalid PCM data size");
                    samples = new short[(int)length / 2]; byte[] block = new byte[8192]; int offset = 0;
                    while (offset < samples.length) {
                        int bytes = Math.min(block.length, (samples.length - offset) * 2); readFully(input, block, 0, bytes);
                        for (int i = 0; i < bytes; i += 2) samples[offset++] = (short)u16(block, i);
                    }
                } else { skipFully(input, length); }
                if ((length & 1) != 0) skipFully(input, 1);
                remaining -= padded;
            }
            if (remaining != 0 || samples == null || input.read() != -1) throw new IOException("Incomplete or trailing WAV data");
            return new PcmWave(samples, rate, channels);
        }
        private static boolean tag(byte[] data, int offset, String text) {
            for (int i = 0; i < 4; i++) if ((data[offset + i] & 255) != text.charAt(i)) return false;
            return true;
        }
        private static int u16(byte[] data, int offset) { return (data[offset] & 255) | (data[offset + 1] & 255) << 8; }
        private static long u32(byte[] data, int offset) { return (long)u16(data, offset) | (long)u16(data, offset + 2) << 16; }
        private static void readFully(InputStream input, byte[] data, int offset, int size) throws IOException {
            while (size > 0) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Audio load cancelled");
                int read = input.read(data, offset, size);
                if (read < 0) throw new EOFException("Truncated WAV");
                if (read == 0) { int value = input.read(); if (value < 0) throw new EOFException("Truncated WAV"); data[offset++] = (byte)value; size--; }
                else { offset += read; size -= read; }
            }
        }
        private static void skipFully(InputStream input, long size) throws IOException {
            byte[] discard = new byte[8192];
            while (size > 0) { int count = (int)Math.min(size, discard.length); readFully(input, discard, 0, count); size -= count; }
        }
    }
}
