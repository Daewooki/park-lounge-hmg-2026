package ai.nc.varcospaces;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Host tests for PCM parsing and gain math; no claim of Android hardware/lifecycle QA. */
public final class AudioEngineTest {
    private static int assertions;
    private static void check(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
    private static void near(float actual, float expected, String label) { check(Math.abs(actual - expected) < 0.000001, label + ": " + actual); }
    private static byte[] chunk(String tag, byte[] payload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag.getBytes("US-ASCII")); out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.length).array());
        out.write(payload); if ((payload.length & 1) != 0) out.write(0); return out.toByteArray();
    }
    private static byte[] format(int encoding, int channels, int rate, int bits) {
        int align = channels * bits / 8;
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putShort((short)encoding).putShort((short)channels)
            .putInt(rate).putInt(rate * align).putShort((short)align).putShort((short)bits).array();
    }
    private static byte[] riff(byte[]... chunks) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream(); for (byte[] c : chunks) body.write(c);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write("RIFF".getBytes("US-ASCII"));
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size() + 4).array());
        out.write("WAVE".getBytes("US-ASCII")); out.write(body.toByteArray()); return out.toByteArray();
    }
    private static AudioEngine.PcmWave parse(byte[] data) throws Exception { return AudioEngine.PcmWave.read(new ByteArrayInputStream(data)); }
    private static void reject(byte[] data, String reason) throws Exception {
        try { parse(data); throw new AssertionError("Accepted " + reason); }
        catch (IOException expected) { assertions++; }
    }
    public static void main(String[] args) throws Exception {
        byte[] fmt = chunk("fmt ", format(1, 1, 48000, 16));
        byte[] pcm = new byte[64]; ByteBuffer b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 32; i++) b.putShort((short)(i % 2 == 0 ? -12000 : 23000));
        byte[] valid = riff(chunk("JUNK", new byte[]{1,2,3}), fmt, chunk("data", pcm), chunk("LIST", new byte[]{4,5,6}));
        AudioEngine.PcmWave wave = parse(valid);
        check(wave.frames() == 32 && wave.channels == 1 && wave.sampleRate == 48000, "Read valid PCM format and frames");
        check(wave.samples[0] == -12000 && wave.samples[1] == 23000, "Signed little-endian samples preserved");
        check(wave.samples[31] == 23000, "Metadata excluded from PCM loop buffer");
        AudioEngine.PcmWave stereo = parse(riff(chunk("fmt ", format(1, 2, 44100, 16)), chunk("data", pcm)));
        check(stereo.frames() == 16 && stereo.samples.length == 32, "Loop endpoint is frames, not stereo sample count");
        reject(Arrays.copyOf(valid, valid.length - 1), "truncated metadata padding");
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1); reject(trailing, "bytes outside RIFF length");
        reject(riff(fmt, chunk("data", new byte[0])), "empty PCM");
        reject(riff(fmt, chunk("data", new byte[65])), "incomplete sample frame");
        reject(riff(chunk("data", pcm), fmt), "data before format");
        reject(riff(fmt, fmt, chunk("data", pcm)), "duplicate format");
        reject(riff(fmt, chunk("data", pcm), chunk("data", pcm)), "duplicate data");
        reject(riff(chunk("fmt ", format(3, 1, 48000, 32)), chunk("data", pcm)), "float WAV");
        reject(riff(chunk("fmt ", format(1, 1, 48000, 24)), chunk("data", pcm)), "unsupported PCM width");
        reject(riff(chunk("fmt ", format(1, 3, 48000, 16)), chunk("data", pcm)), "unsupported channel count");
        reject(riff(chunk("fmt ", format(1, 1, 0, 16)), chunk("data", pcm)), "zero sample rate");
        byte[] badAlign = format(1, 1, 48000, 16); badAlign[12] = 4;
        reject(riff(chunk("fmt ", badAlign), chunk("data", pcm)), "invalid block alignment");
        byte[] huge = valid.clone(); ByteBuffer.wrap(huge).order(ByteOrder.LITTLE_ENDIAN).putInt(4, Integer.MAX_VALUE);
        reject(huge, "unbounded RIFF allocation");
        byte[] badMagic = valid.clone(); badMagic[0] = 'X'; reject(badMagic, "bad magic");
        Thread.currentThread().interrupt();
        try { reject(valid, "cancelled read"); } finally { Thread.interrupted(); }
        float[] normal = AudioEngine.layerGains(new float[]{.65f,.35f,.2f}, new boolean[]{true,true,true}, 1);
        near(normal[0], .43333333f, "Rain default gain"); near(normal[1], .23333333f, "Forest default gain"); near(normal[2], .13333333f, "Drops default gain");
        float[] muted = AudioEngine.layerGains(new float[]{0,.35f,.2f}, new boolean[]{true,true,true}, 1);
        near(muted[0], 0, "Muted layer remains silent"); near(muted[1], .28f, "Other layer stays audible");
        float[] absent = AudioEngine.layerGains(new float[]{.65f,1,1}, new boolean[]{true,false,false}, 1);
        near(absent[0], .52f, "Absent layers do not attenuate an available layer"); near(absent[1], 0, "Absent layer silent");
        float[] ducked = AudioEngine.layerGains(new float[]{.65f,.35f,.2f}, new boolean[]{true,true,true}, .18f);
        for (int i=0;i<3;i++) near(ducked[i], normal[i]*.18f, "Focus duck factor " + i);
        float[] full = AudioEngine.layerGains(new float[]{1,1,1}, new boolean[]{true,true,true}, 1);
        near(full[0]+full[1]+full[2], .8f, "Worst case mix reserves headroom");
        float[] invalid = AudioEngine.layerGains(new float[]{Float.NaN,Float.POSITIVE_INFINITY,-1}, new boolean[]{true,true,true}, 1);
        near(invalid[0]+invalid[1]+invalid[2], 0, "Invalid values never create NaN mixer gain");
        for (String path : args) {
            try (InputStream input = Files.newInputStream(Paths.get(path))) {
                AudioEngine.PcmWave actual = AudioEngine.PcmWave.read(input);
                check(actual.sampleRate == 48000 && actual.channels == 1 && actual.frames() > 48000, "Production loop PCM metadata");
                int peak = 0; for (short sample : actual.samples) peak = Math.max(peak, Math.abs((int)sample));
                check(peak > 0 && peak < 32767, "Production loop non-silent and no PCM clipping");
                System.out.println("Decoded " + Paths.get(path).getFileName() + ": frames=" + actual.frames() + " peak=" + peak);
            }
        }
        System.out.println("Audio PCM and mixer tests passed: " + assertions + " assertions.");
    }
}
