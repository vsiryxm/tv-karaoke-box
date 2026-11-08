package com.xinmin.karaoke;

/*
 * 双轨 PCM 混音引擎 —— 绕开这台海信的 MTK 私有 MediaPlayer。
 *
 * 真机实测：这台的 MediaPlayer 走 CmpbPlayer / MTK_MW 独占会话，两个实例
 * 同时 start 时后一个会把前一个顶成 PLAY_DONE，触发无限切歌。所以不能用
 * 两个 MediaPlayer 混音。这里改成：JLayer 纯 Java 解两路 MP3 -> 逐样本按
 * 增益相加 -> 只写一个标准 AudioTrack（AudioFlinger）。样本级同步，不漂移，
 * 且同一个输出流，不受独占会话影响。
 *
 * 分轨由 prepare_song.sh 生成，固定 CBR 128k / 44.1kHz / 立体声 / 无 Xing：
 * 因此时长可由文件大小精确算出，seek 也能按字节偏移直接跳。
 */
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

public class MixEngine {

    public interface Listener {
        void onEnd();
        void onError(String msg);
    }

    private static final String TAG = "KaraokeMix";
    private static final int SR = 44100;
    private static final int BITRATE = 128000;      // prepare_song.sh 固定 CBR 128k
    private static final int BYTES_PER_MS = BITRATE / 8000;   // = 16
    private static final int REWIND = 8192;         // seek 时回退，交给 JLayer 做帧同步

    /** 只认我们自己的 CBR 素材帧；伪同步常被误判成 Layer I，交给 JLayer 会数组越界。 */
    private static boolean validFrame(Header h) {
        if (h == null) return false;
        try {
            return h.version() == Header.MPEG1 && h.layer() == 3
                && h.frequency() == SR && h.framesize >= 400 && h.framesize <= 450;
        } catch (Throwable t) { return false; }
    }

    private volatile boolean quit = false;
    private volatile boolean paused = false;
    private volatile boolean running = false;
    private volatile float gVocal = 1f;
    private volatile float gBack = 1f;
    private volatile int headFrames = 0;
    private volatile int clockOffsetMs = 0;
    private volatile int durationMs = 0;
    private volatile AudioTrack active;
    private volatile Thread worker;
    private Listener listener;
    private String vocalPath;
    private String backPath;

    public void setListener(Listener l) { listener = l; }

    public boolean isRunning() { return running; }

    public int position() {
        return clockOffsetMs + (int) (headFrames * 1000L / SR);
    }

    public int duration() { return durationMs; }

    public void setGains(float v, float b) {
        gVocal = v;
        gBack = b;
    }

    public void setPaused(boolean p) { paused = p; }

    /** 从头或指定位置起播；seek 也走这里，重建解码流但保住歌词时钟。 */
    public void start(String vocal, String backing, int startMs) {
        stop();
        vocalPath = vocal;
        backPath = backing;
        final int from = Math.max(0, startMs);
        quit = false;
        paused = false;
        running = true;
        headFrames = 0;
        clockOffsetMs = from;
        long size = new File(backing).length();
        durationMs = (int) (size * 8000L / BITRATE);
        Thread t = new Thread(new Runnable() {
            public void run() { decode(from); }
        }, "karaoke-mix");
        worker = t;
        t.start();
    }

    public void seekTo(int ms) { start(vocalPath, backPath, ms); }

    public void stop() {
        quit = true;
        running = false;
        paused = false;
        AudioTrack a = active;
        if (a != null) {
            try { a.pause(); } catch (Throwable t) { }
            try { a.flush(); } catch (Throwable t) { }   // 唤醒阻塞在 write() 的解码线程
        }
        Thread t = worker;
        worker = null;
        if (t != null) {
            t.interrupt();
            try { t.join(400); } catch (InterruptedException e) { }
        }
    }

    private void decode(final int startMs) {
        AudioTrack at = null;
        FileInputStream fv = null, fb = null;
        Bitstream bv = null, bb = null;
        try {
            long off = (long) startMs * BYTES_PER_MS;
            // CBR 的字节偏移只是估算，直接 seek 经常落在帧中间。回退一段让
            // Bitstream 自己找同步头，再把回退部分的帧只读不写丢弃，保证两轨
            // 从同一个逻辑帧起播（误差 < 1 帧 ≈ 26ms）。
            long rewind = Math.min(off, (long) REWIND);
            long from = off - rewind;
            fv = new FileInputStream(vocalPath);
            fb = new FileInputStream(backPath);
            long skippedV = fv.skip(from);
            long skippedB = fb.skip(from);
            Log.i(TAG, "start " + startMs + "ms from=" + from
                    + " skip=" + skippedV + "/" + skippedB
                    + " bytes dur=" + durationMs + "ms");

            bv = new Bitstream(fv);
            bb = new Bitstream(fb);
            int drop = (int) (rewind / 418);
            for (int k = 0; k < drop && !quit; k++) {
                Header a = bv.readFrame(), b = bb.readFrame();
                if (a == null || b == null) break;
                bv.closeFrame();
                bb.closeFrame();
            }
            int minBuf = AudioTrack.getMinBufferSize(SR,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            at = new AudioTrack(AudioManager.STREAM_MUSIC, SR,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf * 2, 32768), AudioTrack.MODE_STREAM);
            active = at;
            at.play();

            Decoder dv = new Decoder(), db = new Decoder();
            SampleBuffer sv = new SampleBuffer(SR, 2), sb = new SampleBuffer(SR, 2);
            dv.setOutputBuffer(sv);
            db.setOutputBuffer(sb);
            short[] mix = new short[8192];
            int badFrames = 0;

            while (!quit && Thread.currentThread() == worker) {
                while (paused && !quit) {
                    if (at.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) at.pause();
                    try { Thread.sleep(60); } catch (InterruptedException e) { break; }
                }
                if (quit) break;
                if (at.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) at.play();

                Header hv = bv.readFrame();
                Header hb = bb.readFrame();
                if (hv == null || hb == null) break;
                if (!validFrame(hv) || !validFrame(hb)) {
                    bv.closeFrame();
                    bb.closeFrame();
                    if (++badFrames > 200) break;
                    continue;
                }
                sv.clear_buffer();
                sb.clear_buffer();
                try {
                    dv.decodeFrame(hv, bv);
                    db.decodeFrame(hb, bb);
                } catch (Throwable frameErr) {
                    Log.w(TAG, "跳过坏帧", frameErr);
                    bv.closeFrame();
                    bb.closeFrame();
                    if (++badFrames > 200) break;
                    continue;
                }
                badFrames = 0;
                int n = Math.min(sv.getBufferLength(), sb.getBufferLength());
                bv.closeFrame();
                bb.closeFrame();
                if (n <= 0) continue;
                if (n > mix.length) n = mix.length;

                short[] pv = sv.getBuffer(), pb = sb.getBuffer();
                float gv = gVocal, gb = gBack;   // 一帧内固定，避免每样本读 volatile
                for (int i = 0; i < n; i++) {
                    int x = (int) (pv[i] * gv + pb[i] * gb);
                    mix[i] = (short) (x > 32767 ? 32767 : (x < -32768 ? -32768 : x));
                }
                int done = 0;
                while (done < n && !quit) {
                    int w = at.write(mix, done, n - done);
                    if (w <= 0) break;
                    done += w;
                }
                if (quit) break;
                headFrames = at.getPlaybackHeadPosition();
            }

            boolean ended = !quit && Thread.currentThread() == worker;
            Log.i(TAG, "decode end ended=" + ended + " pos=" + position() + "ms");
            if (ended && listener != null) listener.onEnd();
        } catch (Throwable t) {
            Log.e(TAG, "decode failed", t);
            if (!quit && listener != null) listener.onError(t.getClass().getSimpleName());
        } finally {
            try { if (bv != null) bv.closeFrame(); } catch (Throwable t) { }
            try { if (bb != null) bb.closeFrame(); } catch (Throwable t) { }
            try { if (fv != null) fv.close(); } catch (Throwable t) { }
            try { if (fb != null) fb.close(); } catch (Throwable t) { }
            if (at != null) {
                try { at.stop(); } catch (Throwable t) { }
                try { at.flush(); } catch (Throwable t) { }
                try { at.release(); } catch (Throwable t) { }
            }
            if (active == at) active = null;
            running = false;
        }
    }
}
