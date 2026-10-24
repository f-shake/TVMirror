package app.tvmirror.phone;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;

/**
 * 采集手机正在播放的音频，直接以 PCM 发送。
 *
 * 为什么不用 AAC：电视端的 AAC 解码器对 csd / ADTS 都很挑，实测三种封装都被拒收
 * （unknown buffer）。PCM 只是把采集到的采样原样搬过去，电视端不需要任何解码配置，
 * 代价是约 1.4 Mbps 带宽——局域网上完全无所谓，换来的是可靠和更低的音频延迟。
 */
public final class AudioEncoder implements Runnable {

    public interface Sink {
        void onAudio(byte[] pcm, long ptsUs);

        /** PCM 方案不需要编码配置，保留接口以兼容旧的调用方。 */
        void onAudioConfig(byte[] csd);
    }

    public static final int SAMPLE_RATE = 44100;
    public static final int CHANNELS = 2;
    private static final int CHUNK_BYTES = 4096;

    private final MediaProjection projection;
    private final Sink sink;
    private volatile boolean running;
    private Thread thread;

    public AudioEncoder(MediaProjection projection, Sink sink) {
        this.projection = projection;
        this.sink = sink;
    }

    public void start() {
        running = true;
        thread = new Thread(this, "tvmirror-audio-enc");
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    @Override
    public void run() {
        AudioRecord record = null;
        try {
            AudioPlaybackCaptureConfiguration config =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();

            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            record = new AudioRecord.Builder()
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuf, CHUNK_BYTES) * 2)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();

            record.startRecording();
            byte[] buffer = new byte[CHUNK_BYTES];
            while (running) {
                int read = record.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    continue;
                }
                byte[] chunk = new byte[read];
                System.arraycopy(buffer, 0, chunk, 0, read);
                sink.onAudio(chunk, android.os.SystemClock.elapsedRealtimeNanos() / 1000);
            }
        } catch (Exception e) {
            android.util.Log.w("TVMirror", "音频采集不可用: " + e.getMessage());
        } finally {
            if (record != null) {
                try {
                    record.stop();
                    record.release();
                } catch (Exception ignored) {
                    // 释放失败不影响退出
                }
            }
        }
    }
}
