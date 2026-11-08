package com.xinmin.karaoke;

/*
 * 电视端离线 K 歌机 —— Android 4.0.4 / API 15，零依赖、零网络、零电脑。
 * 面向 64 岁老人：单屏、大字、图标+文字、数字键直达，无二级菜单层级。
 *
 * U 盘插电视 USB 口 -> 按文件名升序扫描 .mp3/.wav/.m4a/.ogg + 同名 .lrc
 * -> U 盘目录本身就是「已点歌单」，开机自动接着上次那首唱。
 *
 * 单屏结构：顶栏（歌名/进度/状态/音量小卡） + 中间内容区（歌词 | 歌单 | 调音卡）
 *          + 全曲进度条 + 四个大图标按钮 + 一行底部提示。永远只有一层。
 * 歌词区：前奏用 5 个音符按时间染金黄；歌词三行等距排列，当前句唱完整体上滚
 *          一格（前一句 / 当前句 / 下一句同步变换）；当前句逐字染色，不另加进度条。
 *
 * 按钮：1 原唱/伴奏  2 重唱（整首从头）  3 切歌  4 歌单  5 音量（顶栏小卡）
 * 主屏方向键不绑功能（留给歌单选歌 / 调音卡调音量），OK 播放暂停。
 *
 * 两种音源布局：
 *   1) 双轨混音（推荐）：歌名.mp3 + 歌名.lrc + 歌名.人声.mp3 + 歌名.伴奏.mp3
 *      JLayer 解两路 MP3 成 PCM，按增益相加后写单个 AudioTrack。
 *   2) 单轨兜底：歌名.mp3 + 歌名.lrc [+ 歌名.伴奏.mp3]
 *      在「原唱文件 / 伴奏文件」之间切换，兼容旧曲库。
 *
 * 分轨由 Mac 端 prepare_song.sh 离线生成：Demucs --other-method minus
 * 保证「人声 + 伴奏 = 原曲」，两轨同参数 CBR，才能共享播放时钟不漂移。
 *
 * 音频输出走电视自身扬声器 / 3.5mm 输出；本机不提供麦克风输入，
 * 你自己唱的声音仍由原有无线麦克风链路负责，见 README 的「音频接线」一节。
 */
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KaraokeActivity extends Activity {

    private static final String TAG = "Karaoke";
    private static final String PREFS = "karaoke_v4";
    private static final String[] ROOTS = {
        "/mnt/sdcard", "/mnt/usbdisk", "/mnt/usb", "/sdcard",
        "/storage/sdcard0", "/data/local/tmp/karaoke"
    };
    private static final Pattern LRC_TAG =
        Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern LRC_OFFSET =
        Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE);
    /* LRC 前几行常是标题 / 作词 / 作曲，不能拿来当前奏倒数的基准 */
    private static final Pattern LRC_META = Pattern.compile(
        "作词|作曲|演唱|编曲|监制|出品|原唱|歌名|专辑|歌手|词\\s*[:：]|曲\\s*[:：]|歌词|LRC");

    private static final String[] INST_SUFFIXES = {
        ".伴奏", "-伴奏", "_伴奏", "(伴奏)", " (伴奏)",
        ".inst", "-inst", "_inst", " (inst)"
    };
    private static final String[] VOCAL_SUFFIXES = {
        ".人声", "-人声", "_人声", "(人声)", " (人声)",
        ".vocal", "-vocal", "_vocal", ".导唱", "-导唱"
    };

    /* 简洁 / 优雅 / 精致：纯色底、单一强调色、四级字号、统一圆角。
       API 15 的 Canvas 每 100ms 重绘一次，去掉渐变与描边还有实际性能收益。 */
    private static final int BG       = 0xFF0B1120;
    private static final int CARD     = 0xFF161F33;
    private static final int CARD_ON  = 0xFF243352;
    private static final int INK      = 0xFFF2F5FB;   // 主文字：米白
    private static final int INK_SOFT = 0xFFC3CEE2;   // 辅助：高对比浅灰
    private static final int INK_DIM  = 0xFF8898B4;   // 弱化信息 / 已唱过
    private static final int GOLD     = 0xFFFFC94A;   // 唯一强调色
    private static final int TEAL     = 0xFF7FE3D0;
    private static final int BAD      = 0xFFFF8A80;

    private static final float FS_LYRIC = 0.050f;   // 64px @1280
    private static final float FS_BTN   = 0.027f;   // 34px
    private static final float FS_NAME  = 0.024f;   // 30px
    private static final float FS_SUB   = 0.020f;   // 26px

    private static final int NOTES = 5;             // 前奏音符个数
    private static final int NOTE_OFF = 0xFF44527A; // 未走到的音符
    /** 跑马灯欢迎语：默认通用、不含任何私人信息。
        改文案不用改代码：U 盘根目录放 karaoke.conf，写一行 welcome=你的欢迎语，
        App 每次扫描曲库时读取；格式见 karaoke.conf.example。 */
    private static final String WELCOME_DEFAULT = "欢迎来到 K 歌台，";
    private String welcomeText = WELCOME_DEFAULT;
    private static final int MARQUEE_MS = 50;       // 顶部跑马灯 20fps，匀速横移
    private static final int INTRO_FADE_MS = 450;   // 开唱后前奏音符淡出上浮的时长
    private static final long ROLL_MS = 450;        // 歌词上滚一格时长
    private static final long ROLL_LEAD = 600;      // 换句前多久开始提速采样
    private static final int ROLL_FAST = 25;

    private static final String[] BTN_LABELS = { "原唱", "重唱", "切歌", "歌单" };
    private static final int ICON_VOICE = 0, ICON_REPLAY = 1, ICON_SKIP = 2,
                             ICON_LIST = 3, ICON_VOLUME = 4;

    static class Line {
        long ms;
        String text;
        Line(long m, String t) { ms = m; text = t; }
    }

    static class Song {
        String name;
        String path;
        String instPath;      // 伴奏分轨；null 表示只有原唱
        String vocalPath;     // 导唱人声分轨；null 表示不支持双轨混音
        boolean hasLrc;
        ArrayList<Line> lines = new ArrayList<Line>();
    }

    private ArrayList<Song> songs = new ArrayList<Song>();
    private int cur = 0;
    private int listCursor = 0;
    private int libTop = 0;
    private boolean showList = false;      // 中间内容区切成歌单
    private boolean volCard = false;       // 中间内容区切成调音卡
    private long volTouchAt = 0;           // 停手 2 秒自动收起
    private int activeBtn = 0;             // 按钮条上唯一的金黄焦点
    private long focusSwitchAt = 0;        // 焦点切换的 120ms 底色过渡
    private int rollLastLine = Integer.MIN_VALUE;  // 上一帧的当前歌词行
    private long rollStartAt = 0;          // 上滚开始时刻，0 = 没在滚
    private boolean playing = false;
    private boolean useInst = false;       // 单轨模式：是否播伴奏文件
    private boolean mixMode = false;       // 当前歌是否走双轨混音
    private float gVocal = 1.0f;           // 导唱人声增益 0..1
    private float gBack = 1.0f;            // 伴奏增益 0..1
    private int seekFloor = 0;
    private long seekFloorUntil = 0;
    private boolean backArmed = false;
    private String err = "";
    private String rootHint = "";

    /* 大字反馈 / 唱完过场 / 拔盘 */
    private long bannerUntil = 0;
    private String bannerText = "";
    private long interludeUntil = 0;
    private String interludeName = "";
    private int pendingIdx = 0;
    private boolean usbGone = false;
    private long lastRecoverAt = 0;

    /* 开机记忆：只存文件名，不存序号（以后往 U 盘加歌序号会漂） */
    private SharedPreferences prefs;
    private String lastSong = "";

    private MediaPlayer player;
    private MixEngine engine;
    private KView view;
    private final Handler handler = new Handler();
    private final Runnable nextSong = new Runnable() {
        public void run() { startInterlude(); }
    };
    private PowerManager.WakeLock wake;
    private BroadcastReceiver usbReceiver;

    private final Paint pBg = new Paint();
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);  // 弹窗细边框
    private final Paint pCard = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCardOn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGold = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFocus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pPrev = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCur = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pNext = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBarOn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pIco = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pNote = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pNum = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBreath = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFocusBg = new Paint(Paint.ANTI_ALIAS_FLAG);  // 焦点过渡专用，别污染 pCardOn
    private final Paint pMeter = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Runnable tick = new Runnable() {
        public void run() {
            // 20 秒只是兜底；正常路径是按 5 收起，或按 1~4 直接去干别的（卡片自动让路）
            if (volCard && System.currentTimeMillis() - volTouchAt > 20000) volCard = false;
            if (view != null) view.invalidate();
            // 换句前后 40fps；平时 20fps 供顶部跑马灯匀速横移（其余画面不常重绘）
            boolean rolling = rollStartAt > 0
                && System.currentTimeMillis() - rollStartAt < ROLL_MS;
            handler.postDelayed(this, (rolling || nearLineChange()) ? ROLL_FAST : MARQUEE_MS);
        }
    };

    /** 换句前提前提速采样。空闲时 100ms 才刷一帧，会让「该翻了」晚半拍被发现，
        看起来就是卡一下再猛跳一格；提前进 40fps 才能接上平滑的滚动。 */
    private boolean nearLineChange() {
        if (!playing || songs.isEmpty() || showList || volCard) return false;
        if (usbGone || System.currentTimeMillis() < interludeUntil) return false;
        Song s = songs.get(cur);
        if (!s.hasLrc || s.lines.isEmpty()) return false;
        int pos = position();
        int i = lineAt(s, pos);
        if (i + 1 >= s.lines.size()) return false;
        long left = s.lines.get(i + 1).ms - pos;
        return left > 0 && left < ROLL_LEAD;
    }

    private final Runnable disarmBack = new Runnable() {
        public void run() { backArmed = false; }
    };

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                             WindowManager.LayoutParams.FLAG_FULLSCREEN);
        initPaints();
        view = new KView(this);
        view.setKeepScreenOn(true);
        setContentView(view);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, TAG);
        wake.acquire();

        engine = new MixEngine();
        engine.setListener(new MixEngine.Listener() {
            public void onEnd() { handler.post(nextSong); }
            public void onError(String msg) {
                if (usbGone) return;
                // 解码线程里绝不能直接碰 View（4.0.4 会直接杀掉整个 App）
                handler.post(new Runnable() {
                    public void run() {
                        long now = System.currentTimeMillis();
                        if (now - lastRecoverAt < 3000) return;
                        lastRecoverAt = now;
                        showBanner("播放已自动恢复");
                        play(cur);
                    }
                });
            }
        });

        loadPrefs();
        probeMic();
        rescan();
        restoreLast();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_MEDIA_MOUNTED);
        f.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        f.addAction(Intent.ACTION_MEDIA_EJECT);
        f.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        f.addDataScheme("file");
        usbReceiver = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                final String act = i.getAction();
                Log.i(TAG, "USB 事件: " + act);
                boolean removed = Intent.ACTION_MEDIA_EJECT.equals(act)
                    || Intent.ACTION_MEDIA_UNMOUNTED.equals(act)
                    || Intent.ACTION_MEDIA_BAD_REMOVAL.equals(act);
                if (removed) {
                    handler.post(new Runnable() {
                        public void run() { onUsbRemoved(); }
                    });
                } else {
                    handler.postDelayed(new Runnable() {
                        public void run() { onUsbMounted(); }
                    }, 1500);
                }
            }
        };
        registerReceiver(usbReceiver, f);
        handler.postDelayed(new Runnable() {
            public void run() { if (songs.isEmpty()) rescan(); }
        }, 4000);
        handler.post(tick);
    }

    private void initPaints() {
        pBg.setColor(BG);
        pCard.setColor(CARD);
        pCardOn.setColor(CARD_ON);
        pGold.setColor(GOLD);
        pFocus.setStyle(Paint.Style.STROKE);
        pFocus.setStrokeWidth(5f);
        pFocus.setColor(GOLD);
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeWidth(2f);
        pLine.setColor(0xB3FFC94A);   // 淡金细线，不再用 5px 焦点粗框
        pBar.setColor(0xFF34415E);
        pBarOn.setColor(GOLD);

        pTitle.setTypeface(Typeface.DEFAULT_BOLD);
        pTitle.setColor(INK);
        pTitle.setTextAlign(Paint.Align.LEFT);
        pSub.setColor(INK_SOFT);
        pSub.setTextAlign(Paint.Align.LEFT);
        pPrev.setTextAlign(Paint.Align.CENTER);
        pPrev.setColor(INK_SOFT);
        pCur.setTextAlign(Paint.Align.CENTER);
        pCur.setColor(INK);
        pCur.setTypeface(Typeface.DEFAULT_BOLD);
        pCur.setShadowLayer(6, 0, 3, 0xAA000000);
        pNext.setTextAlign(Paint.Align.CENTER);
        pNext.setColor(INK_SOFT);
        pHint.setTextAlign(Paint.Align.CENTER);
        pHint.setColor(INK_SOFT);
        pBreath.setTextAlign(Paint.Align.CENTER);
        pBreath.setTypeface(Typeface.DEFAULT_BOLD);
        pNote.setStyle(Paint.Style.FILL);
        pNote.setColor(NOTE_OFF);
        pIco.setStyle(Paint.Style.STROKE);
        pIco.setStrokeCap(Paint.Cap.ROUND);
        pIco.setStrokeJoin(Paint.Join.ROUND);
        pIco.setColor(INK);
        pNum.setTextAlign(Paint.Align.CENTER);
        pNum.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /* ---------------- 记忆 ---------------- */

    private void loadPrefs() {
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        gVocal = prefs.getFloat("gv", 1.0f);
        gBack = prefs.getFloat("gb", 1.0f);
        lastSong = prefs.getString("last", "");
        Log.i(TAG, "读取偏好 导唱=" + gVocal + " 伴奏=" + gBack + " 上次=" + lastSong);
    }

    private void savePrefs() {
        if (prefs == null) return;
        SharedPreferences.Editor e = prefs.edit();
        e.putFloat("gv", gVocal);
        e.putFloat("gb", gBack);
        e.putString("last", lastSong);
        e.commit();
    }

    /** 开机定位到上次那首并直接唱；找不到就回第 1 首。 */
    private void restoreLast() {
        int idx = 0;
        if (lastSong.length() > 0) {
            for (int i = 0; i < songs.size(); i++) {
                if (songs.get(i).name.equals(lastSong)) { idx = i; break; }
            }
        }
        cur = idx;
        listCursor = idx;
        syncLibTop();
        if (!songs.isEmpty()) {
            Log.i(TAG, "接着上次唱: " + songs.get(idx).name + " (第 " + (idx + 1) + " 首)");
            play(idx);
        }
    }

    /* ---------------- 麦克风自检（只读 logcat） ---------------- */

    private void probeMic() {
        new Thread(new Runnable() {
            public void run() { doProbeMic(); }
        }, "mic-probe").start();
    }

    private void doProbeMic() {
        AudioRecord ar = null;
        try {
            int min = AudioRecord.getMinBufferSize(8000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            ar = new AudioRecord(MediaRecorder.AudioSource.MIC, 8000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min, 4096));
            ar.startRecording();
            short[] buf = new short[800];
            int n = ar.read(buf, 0, buf.length);
            int peak = 0;
            for (int i = 0; i < n; i++) if (Math.abs(buf[i]) > peak) peak = Math.abs(buf[i]);
            Log.i(TAG, "MIC 采样 n=" + n + " peak=" + peak
                + " => " + (n > 0 && peak == 0 ? "通道存在但恒为静音" : "通道有数据"));
        } catch (Throwable t) {
            Log.i(TAG, "MIC 不可用: " + t);
        } finally {
            if (ar != null) {
                try { ar.stop(); } catch (Exception e) { }
                try { ar.release(); } catch (Exception e) { }
            }
        }
    }

    /* ---------------- 曲库扫描（按文件名升序，U 盘目录即歌单） ---------------- */

    private String canon(File f) {
        try { return f.getCanonicalPath(); } catch (Exception e) { return f.getAbsolutePath(); }
    }

    private void collect(File dir, List<File> out, int depth) {
        if (depth > 5) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isHidden()) continue;
            if (f.isDirectory()) collect(f, out, depth + 1);
            else {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".mp3") || n.endsWith(".wav")
                    || n.endsWith(".m4a") || n.endsWith(".ogg")) out.add(f);
            }
        }
    }

    private void addMountRoots(ArrayList<File> roots, HashSet<String> seen) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/mounts"));
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split("\\s+");
                if (p.length < 3) continue;
                String mnt = p[1], fs = p[2].toLowerCase();
                if (!(fs.contains("fat") || fs.contains("ntfs") || fs.contains("fuse"))) continue;
                if (!(mnt.startsWith("/mnt/") || mnt.startsWith("/storage/"))) continue;
                File f = new File(mnt);
                if (f.isDirectory() && seen.add(canon(f))) roots.add(f);
            }
        } catch (Exception e) {
            Log.w(TAG, "读挂载表失败: " + e);
        } finally {
            if (br != null) { try { br.close(); } catch (Exception e) { } }
        }
    }

    private boolean isAudio(String n) {
        String s = n.toLowerCase();
        return s.endsWith(".mp3") || s.endsWith(".wav")
            || s.endsWith(".m4a") || s.endsWith(".ogg");
    }

    private String stemOfName(String n) {
        int d = n.lastIndexOf('.');
        return d > 0 ? n.substring(0, d) : n;
    }

    private String keyOf(File f) {
        File d = f.getParentFile();
        return (d == null ? "" : d.getAbsolutePath()) + "/" + stemOfName(f.getName());
    }

    private String ownerKeyFor(File f, String stem, String[] suffixes) {
        for (String suf : suffixes) {
            if (!stem.endsWith(suf)) continue;
            String base = stem.substring(0, stem.length() - suf.length());
            if (base.length() == 0) continue;
            File dir = f.getParentFile();
            File[] fs = dir == null ? null : dir.listFiles();
            if (fs == null) continue;
            for (File g : fs) {
                if (g.equals(f) || !isAudio(g.getName())) continue;
                if (stemOfName(g.getName()).equals(base)) return keyOf(g);
            }
        }
        return null;
    }

    private boolean isMix(Song s) {
        return s != null && s.vocalPath != null && s.instPath != null;
    }

    private String audioPath(Song s) {
        return (useInst && s.instPath != null) ? s.instPath : s.path;
    }

    /** 读 U 盘根目录的 karaoke.conf（UTF-8），一行 welcome=xxx 覆盖跑马灯欢迎语。
        文件不存在、没这行、读失败，一律回落到通用默认，绝不影响唱歌。 */
    private void loadWelcome() {
        welcomeText = WELCOME_DEFAULT;
        for (String r : ROOTS) {
            File f = new File(r, "karaoke.conf");
            if (!f.isFile()) continue;
            BufferedReader br = null;
            try {
                br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), "UTF-8"), 512);
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.length() > 0 && line.charAt(0) == '\uFEFF')
                        line = line.substring(1);          // 记事本 UTF-8 BOM
                    line = line.trim();
                    if (line.length() == 0 || line.startsWith("#")) continue;
                    if (!line.startsWith("welcome=")) continue;
                    String v = line.substring("welcome=".length()).trim();
                    if (v.length() > 0) welcomeText = v;
                    break;
                }
            } catch (Exception ignored) {
            } finally {
                try { if (br != null) br.close(); } catch (Exception ignored) {}
            }
            Log.i(TAG, "跑马灯欢迎语=" + welcomeText);
            return;                                       // 第一个找到的配置生效
        }
    }

    private void rescan() {
        loadWelcome();
        HashSet<String> seen = new HashSet<String>();
        ArrayList<File> roots = new ArrayList<File>();
        for (String r : ROOTS) {
            File f = new File(r);
            if (f.isDirectory() && seen.add(canon(f))) roots.add(f);
        }
        addMountRoots(roots, seen);
        ArrayList<File> files = new ArrayList<File>();
        for (File d : roots) collect(d, files, 0);
        Collections.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        HashMap<String, String> instMap = new HashMap<String, String>();
        HashMap<String, String> vocalMap = new HashMap<String, String>();
        ArrayList<File> mains = new ArrayList<File>();
        for (File f : files) {
            String stem = stemOfName(f.getName());
            String vkey = ownerKeyFor(f, stem, VOCAL_SUFFIXES);
            if (vkey != null) {
                if (!vocalMap.containsKey(vkey)) vocalMap.put(vkey, f.getAbsolutePath());
                continue;
            }
            String ikey = ownerKeyFor(f, stem, INST_SUFFIXES);
            if (ikey != null) {
                if (!instMap.containsKey(ikey)) instMap.put(ikey, f.getAbsolutePath());
            } else {
                mains.add(f);
            }
        }

        ArrayList<Song> fresh = new ArrayList<Song>();
        String foundDir = "";
        int withInst = 0, withMix = 0;
        for (File f : mains) {
            if (foundDir.length() == 0 && f.getParentFile() != null)
                foundDir = f.getParentFile().getAbsolutePath();
            Song s = new Song();
            s.path = f.getAbsolutePath();
            s.name = stemOfName(f.getName());
            String key = keyOf(f);
            s.instPath = instMap.get(key);
            s.vocalPath = vocalMap.get(key);
            if (s.instPath != null) withInst++;
            if (isMix(s)) withMix++;
            File lrc = findLrc(f);
            if (lrc != null) {
                s.lines = loadLrc(lrc);
                s.hasLrc = !s.lines.isEmpty();
            }
            fresh.add(s);
        }
        songs = fresh;
        if (cur >= songs.size()) cur = 0;
        if (listCursor >= songs.size()) listCursor = 0;
        syncLibTop();
        rootHint = foundDir.length() > 0 ? ("曲库: " + foundDir)
                 : ("已扫描 " + roots.size() + " 个位置，未找到歌曲");
        Log.i(TAG, "扫描到 " + songs.size() + " 首歌（" + withInst + " 首有伴奏版，"
                + withMix + " 首可双轨混音）；" + rootHint);
        if (view != null) view.invalidate();
    }

    private File findLrc(File audio) {
        String stem = audio.getName().replaceFirst("\\.[^.]+$", "");
        File dir = audio.getParentFile();
        if (dir == null) return null;
        File exact = new File(dir, stem + ".lrc");
        if (exact.isFile()) return exact;
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            String n = f.getName();
            if (n.equalsIgnoreCase(stem + ".lrc") || n.equalsIgnoreCase(stem + ".LRC")) return f;
        }
        return null;
    }

    private byte[] readAll(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private String decode(byte[] raw) {
        try {
            String s = new String(raw, "UTF-8");
            if (s.indexOf('\uFFFD') < 0) {
                if (s.length() > 0 && s.charAt(0) == '\uFEFF') return s.substring(1);
                return s;
            }
        } catch (Exception e) { }
        try { return new String(raw, "GBK"); } catch (Exception e) { return new String(raw); }
    }

    private ArrayList<Line> loadLrc(File f) {
        ArrayList<Line> lines = new ArrayList<Line>();
        String txt = decode(readAll(f));
        long offset = 0;
        Matcher om = LRC_OFFSET.matcher(txt);
        if (om.find()) {
            try { offset = -Long.parseLong(om.group(1)); } catch (Exception e) { }
        }
        String[] rows = txt.split("\\r?\\n");
        for (String row : rows) {
            Matcher m = LRC_TAG.matcher(row);
            ArrayList<Long> times = new ArrayList<Long>();
            int end = 0;
            while (m.find()) {
                long ms = Long.parseLong(m.group(1)) * 60000L
                        + Long.parseLong(m.group(2)) * 1000L;
                String frac = m.group(3);
                if (frac != null) {
                    if (frac.length() == 1) ms += Long.parseLong(frac) * 100L;
                    else if (frac.length() == 2) ms += Long.parseLong(frac) * 10L;
                    else ms += Long.parseLong(frac);
                }
                times.add(ms);
                end = m.end();
            }
            if (times.isEmpty()) continue;
            String text = row.substring(end).replaceAll("\\[[^\\]]*\\]", "").trim();
            if (text.length() == 0) continue;
            for (Long t : times) lines.add(new Line(Math.max(0L, t + offset), text));
        }
        Collections.sort(lines, new Comparator<Line>() {
            public int compare(Line a, Line b) { return a.ms < b.ms ? -1 : (a.ms == b.ms ? 0 : 1); }
        });
        /* 头部的「歌手 - 歌名 / 作词 / 作曲」不是歌词；三行上滚时会当成上一句
           露出来，所以只清洗最前面几行，遇到第一句真歌词就停。 */
        int cut = 0;
        while (cut < lines.size() && cut < 8) {
            Line l = lines.get(cut);
            boolean meta = LRC_META.matcher(l.text).find();
            boolean title = l.ms < 1000 && (l.text.indexOf('-') >= 0
                || l.text.indexOf('－') >= 0 || l.text.indexOf('—') >= 0);
            if (meta || title) cut++;
            else break;
        }
        if (cut > 0) lines = new ArrayList<Line>(lines.subList(cut, lines.size()));
        return lines;
    }

    /* ---------------- 歌单 / 动作 ---------------- */

    private String songName(int idx) {
        return (idx >= 0 && idx < songs.size()) ? songs.get(idx).name : "—";
    }

    /** 没有队列：U 盘升序列表里，当前首的下一首就是预告。 */
    private int peekNext() {
        return songs.isEmpty() ? 0 : (cur + 1) % songs.size();
    }

    private void syncLibTop() {
        int rows = 6;
        if (listCursor < libTop) libTop = listCursor;
        if (listCursor >= libTop + rows) libTop = listCursor - rows + 1;
        int maxTop = Math.max(0, songs.size() - rows);
        if (libTop > maxTop) libTop = maxTop;
    }

    private void showBanner(String s) {
        bannerText = s;
        bannerUntil = System.currentTimeMillis() + 2200;
        if (view != null) view.invalidate();
    }

    private void setActive(int n) {
        activeBtn = n;
        focusSwitchAt = System.currentTimeMillis();
    }

    /** 五个数字键直达：全部动作都印在同一屏上，不换页、不记层级。 */
    private void act(int n) {
        if (n >= 1 && n <= 4) setActive(n - 1);
        switch (n) {
            case 1: switchTrack(); break;
            case 2: replaySong(); break;
            case 3: skip(); break;
            case 4: toggleList(); break;
            case 5: toggleVol(); break;
        }
    }

    private void toggleList() {
        if (songs.isEmpty()) { rescan(); return; }
        showList = !showList;
        volCard = false;
        if (showList) {
            listCursor = cur;
            syncLibTop();
        }
        if (view != null) view.invalidate();
    }

    private void toggleVol() {
        volCard = !volCard;
        showList = false;
        volTouchAt = System.currentTimeMillis();
        if (view != null) view.invalidate();
    }

    /** 唱完自动切：先给 2 秒大字过场，再按升序播下一首。 */
    private void advance(boolean withInterlude) {
        if (songs.isEmpty() || usbGone) return;
        final int n = (cur + 1) % songs.size();
        if (!withInterlude) { play(n); return; }
        interludeName = songName(n);
        interludeUntil = System.currentTimeMillis() + 2000;
        pendingIdx = n;
        showList = false;
        volCard = false;
        stopPlayer();
        playing = false;
        Log.i(TAG, "过场 -> " + interludeName);
        handler.postDelayed(new Runnable() {
            public void run() {
                interludeUntil = 0;
                if (usbGone) return;
                play(pendingIdx);
            }
        }, 2000);
    }

    private void startInterlude() { advance(true); }

    private void skip() {
        if (songs.isEmpty() || usbGone) return;
        showBanner("已切歌 → " + songName(peekNext()));
        advance(false);
    }

    /** 重唱＝整首从头，不动歌单位置。 */
    private void replaySong() {
        if (songs.isEmpty()) return;
        play(cur, 0, true);
        showBanner("从头再唱一遍");
        Log.i(TAG, "重唱整首 " + songName(cur));
    }

    private void onUsbRemoved() {
        if (usbGone) return;
        usbGone = true;
        stopPlayer();
        playing = false;
        showList = false;
        volCard = false;
        interludeUntil = 0;
        err = "";
        Log.i(TAG, "U 盘拔出：停止播放");
        if (view != null) view.invalidate();
    }

    private void onUsbMounted() {
        boolean was = usbGone;
        usbGone = false;
        rescan();
        if (was && !songs.isEmpty()) {
            restoreLast();
            showBanner("U 盘已插回，接着上次唱");
            Log.i(TAG, "U 盘插回：已自动恢复播放");
        }
    }

    /* ---------------- 播放控制 ---------------- */

    private void stopPlayer() {
        if (engine != null) engine.stop();
        if (player != null) {
            try { player.stop(); } catch (Exception e) { }
            try { player.release(); } catch (Exception e) { }
            player = null;
        }
        playing = false;
        mixMode = false;
    }

    private MediaPlayer createPlayer(String path, final String tag) throws Exception {
        MediaPlayer mp = new MediaPlayer();
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mp.setDataSource(path);
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer m, int what, int extra) {
                err = "播放失败 " + tag + " (" + what + "/" + extra + ")";
                stopPlayer();
                if (view != null) view.invalidate();
                return true;
            }
        });
        mp.prepare();
        return mp;
    }

    private void applyGains() {
        if (mixMode && engine != null) engine.setGains(gVocal, gBack);
        savePrefs();
        if (view != null) view.invalidate();
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return Math.round(v * 10f) / 10f;
    }

    private void adjustGain(boolean vocal, int steps) {
        // 单轨歌也允许改数值并记住，下次切到双轨歌时生效；卡片里会写明
        if (vocal) gVocal = clamp01(gVocal + steps * 0.1f);
        else gBack = clamp01(gBack + steps * 0.1f);
        applyGains();
        Log.i(TAG, "MIX 增益 人声=" + gVocal + " 伴奏=" + gBack);
    }

    private void play(final int idx) { play(idx, 0, true); }

    private void play(final int idx, int startAt, boolean autoStart) {
        if (songs.isEmpty() || usbGone) return;
        stopPlayer();
        seekFloor = 0;
        rollLastLine = Integer.MIN_VALUE;   // 换歌 / 重唱 / 换音轨：歌词直接就位，不滚
        rollStartAt = 0;
        cur = ((idx % songs.size()) + songs.size()) % songs.size();
        listCursor = cur;
        syncLibTop();
        final Song s = songs.get(cur);
        err = "";
        mixMode = isMix(s);
        lastSong = s.name;
        savePrefs();
        showList = false;
        if (mixMode) {
            if (!new File(s.vocalPath).isFile() || !new File(s.instPath).isFile()) {
                err = "分轨文件不见了，请插回 U 盘";
                playing = false;
                if (view != null) view.invalidate();
                return;
            }
            engine.start(s.vocalPath, s.instPath, startAt);
            playing = autoStart;
            engine.setPaused(!autoStart);
            engine.setGains(gVocal, gBack);
            Log.i(TAG, "MIX 播放 " + s.name + " [双轨混音] 人声=" + gVocal
                    + " 伴奏=" + gBack + " 起点=" + startAt + "ms");
            if (view != null) view.invalidate();
            return;
        }
        try {
            player = createPlayer(audioPath(s), "音源");
            // 单轨与双轨统一走「2 秒过场 -> 下一首」，别硬切
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp) { handler.post(nextSong); }
            });
            int target = startAt;
            if (target > 0) {
                int dur = player.getDuration();
                if (dur > 0 && target > dur - 500) target = Math.max(0, dur - 500);
            }
            if (autoStart) {
                player.start();
                playing = true;
            } else if (target > 0) {
                player.start();
                player.pause();
            }
            if (target > 0) {
                final MediaPlayer mp = player;
                final int to = target;
                seekFloor = to;
                seekFloorUntil = System.currentTimeMillis() + 800;
                mp.seekTo(to);
                handler.postDelayed(new Runnable() {
                    public void run() {
                        try {
                            if (mp != player) return;
                            mp.seekTo(to);
                            Log.i(TAG, "切音轨补 seek -> " + mp.getCurrentPosition() + "ms");
                        } catch (Exception e) { }
                    }
                }, 350);
            }
            Log.i(TAG, "播放 " + s.name + " [单轨]");
        } catch (Exception e) {
            err = "无法播放：" + e.getClass().getSimpleName();
            Log.e(TAG, err, e);
            stopPlayer();
        }
        if (view != null) view.invalidate();
    }

    private void toggle() {
        if (songs.isEmpty()) { rescan(); return; }
        if (mixMode) {
            if (engine == null || !engine.isRunning()) { play(cur); return; }
            playing = !playing;
            engine.setPaused(!playing);
            view.invalidate();
            return;
        }
        if (player == null) { play(cur); return; }
        if (playing) { player.pause(); playing = false; }
        else { player.start(); playing = true; }
        view.invalidate();
    }

    /** 按钮 1 / 快捷键 0：混音模式切导唱开关，单轨模式换音源文件。 */
    private void switchTrack() {
        if (songs.isEmpty()) return;
        if (mixMode) {
            gVocal = gVocal > 0.05f ? 0.0f : 1.0f;
            applyGains();
            showBanner(gVocal > 0 ? "现在是：原唱" : "现在是：伴奏");
            Log.i(TAG, "MIX 切换 " + (gVocal > 0 ? "原唱" : "纯伴奏"));
            return;
        }
        Song s = songs.get(cur);
        if (s.instPath == null) {
            showBanner("这首歌没有伴奏版");
            return;
        }
        int pos = position();
        boolean wasPlaying = playing;
        useInst = !useInst;
        play(cur, pos, wasPlaying);
        showBanner(useInst ? "现在是：伴奏" : "现在是：原唱");
        Log.i(TAG, "单轨切换 " + (useInst ? "伴奏版" : "原唱版") + "；保持位置 " + pos + "ms");
    }

    /** 按钮 1 的文字显示「现在是什么」，不是「按了会变什么」。 */
    private boolean accompanimentNow() {
        if (songs.isEmpty()) return false;
        return mixMode ? (gVocal < 0.05f) : useInst;
    }

    private void seekTo(int ms) {
        if (mixMode) engine.seekTo(ms);
        else play(cur, ms, playing);
        if (view != null) view.invalidate();
    }

    private void seek(int delta) {
        if (mixMode) {
            int to = position() + delta;
            if (to < 0) to = 0;
            if (duration() > 0 && to > duration() - 300) to = Math.max(0, duration() - 300);
            engine.seekTo(to);
            view.invalidate();
            return;
        }
        if (player == null) return;
        try {
            int dur = player.getDuration();
            int to = player.getCurrentPosition() + delta;
            if (to < 0) to = 0;
            if (dur > 0 && to > dur - 300) to = Math.max(0, dur - 300);
            player.seekTo(to);
            seekFloor = to;
            seekFloorUntil = System.currentTimeMillis() + 800;
        } catch (Exception e) { }
        view.invalidate();
    }

    private int lineAt(Song s, long pos) {
        ArrayList<Line> ls = s.lines;
        int lo = 0, hi = ls.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            if (ls.get(mid).ms <= pos) { ans = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return ans;
    }

    private int position() {
        if (mixMode) return engine == null ? 0 : engine.position();
        try {
            int p = player == null ? 0 : player.getCurrentPosition();
            if (p < seekFloor && System.currentTimeMillis() < seekFloorUntil) return seekFloor;
            return p;
        } catch (Exception e) { return 0; }
    }

    private int duration() {
        if (mixMode) return engine == null ? 0 : engine.duration();
        try { return player == null ? 0 : player.getDuration(); }
        catch (Exception e) { return 0; }
    }

    /* ---------------- 遥控器 ---------------- */

    @Override
    public boolean onKeyDown(int key, KeyEvent e) {
        if (usbGone) {
            if (key == KeyEvent.KEYCODE_MENU) { onUsbMounted(); return true; }
            if (key == KeyEvent.KEYCODE_BACK) { finish(); return true; }
            return true;
        }
        if (volCard) return onVolKey(key);
        if (showList) return onListKey(key);
        switch (key) {
            case KeyEvent.KEYCODE_1: act(1); return true;
            case KeyEvent.KEYCODE_2: act(2); return true;
            case KeyEvent.KEYCODE_3: act(3); return true;
            case KeyEvent.KEYCODE_4: act(4); return true;
            case KeyEvent.KEYCODE_5: act(5); return true;
            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_M:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                setActive(0); switchTrack(); return true;
            /* 主屏方向键一律不响应：↑↓ 换歌、←→ 快进退，会跟「歌单里 ↑↓ 选歌」
               和「调音卡里 ←→ 调音量」抢同一组键，老人按一下就误切歌。
               换歌请按 3，选歌按 4，重唱按 2，原唱/伴奏按 1。 */
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                toggle(); return true;
            case KeyEvent.KEYCODE_MENU:
                setActive(3); toggleList(); return true;
            case KeyEvent.KEYCODE_BACK:
                if (backArmed) { finish(); return true; }
                backArmed = true;
                handler.postDelayed(disarmBack, 2000);
                Toast.makeText(this, "再按一次返回键退出 K 歌", Toast.LENGTH_SHORT).show();
                return true;
            default:
                return super.onKeyDown(key, e);
        }
    }

    /** 歌单：↑↓ 选、OK 马上唱、4 / 菜单 / 返回 收起。列表默认落在当前首。 */
    private boolean onListKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_4:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BACK:
                showList = false; view.invalidate(); return true;
            case KeyEvent.KEYCODE_DPAD_UP: {
                int n = Math.max(1, songs.size());
                listCursor = (listCursor - 1 + n) % n;
                syncLibTop(); view.invalidate(); return true;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int n = Math.max(1, songs.size());
                listCursor = (listCursor + 1) % n;
                syncLibTop(); view.invalidate(); return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                showList = false;
                play(listCursor);
                showBanner("马上唱：" + songName(listCursor));
                return true;
            // 歌单里按别的数字键也能直接用，不用先退出
            case KeyEvent.KEYCODE_1: showList = false; act(1); return true;
            case KeyEvent.KEYCODE_2: showList = false; act(2); return true;
            case KeyEvent.KEYCODE_3: showList = false; act(3); return true;
            case KeyEvent.KEYCODE_5: showList = false; toggleVol(); return true;
            default:
                return true;
        }
    }

    /** 调音卡：←→ 调原唱、↑↓ 调伴奏，停手 2 秒自动收起，播放不中断。 */
    private boolean onVolKey(int key) {
        volTouchAt = System.currentTimeMillis();
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:  adjustGain(true, -1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: adjustGain(true, +1); return true;
            case KeyEvent.KEYCODE_DPAD_UP:    adjustGain(false, +1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:  adjustGain(false, -1); return true;
            case KeyEvent.KEYCODE_1: volCard = false; act(1); return true;
            case KeyEvent.KEYCODE_2: volCard = false; act(2); return true;
            case KeyEvent.KEYCODE_3: volCard = false; act(3); return true;
            case KeyEvent.KEYCODE_4: volCard = false; act(4); return true;
            case KeyEvent.KEYCODE_5:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                volCard = false; view.invalidate(); return true;
            default:
                return true;
        }
    }

    /* ---------------- 生命周期 ---------------- */

    @Override
    protected void onPause() {
        super.onPause();
        if (mixMode && engine != null && playing) {
            engine.setPaused(true);
            playing = false;
        } else if (player != null && playing) {
            player.pause();
            playing = false;
        }
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onDestroy() {
        stopPlayer();
        savePrefs();
        handler.removeCallbacks(tick);
        handler.removeCallbacks(disarmBack);
        if (usbReceiver != null) { try { unregisterReceiver(usbReceiver); } catch (Exception e) { } }
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    /* ---------------- 全屏绘制 ---------------- */

    private static String mmss(int ms) {
        int t = Math.max(0, ms) / 1000;
        return String.format("%02d:%02d", t / 60, t % 60);
    }

    private static String pct(float g) { return Math.round(g * 100) + "%"; }

    private void fit(Paint p, String s, float maxW, float start) {
        float size = start;
        p.setTextSize(size);
        while (p.measureText(s) > maxW && size > 22) {
            size -= 2;
            p.setTextSize(size);
        }
    }

    /** 歌名这类「可截断」文字：超出可用宽度就按字符截掉尾部并补省略号，
        保证绝不压到跑马灯、状态标签等相邻区域。字号保持不变。 */
    private String ellipsize(Paint p, String s, float maxW) {
        if (s == null) return "";
        if (p.measureText(s) <= maxW) return s;
        String ell = "…";
        float room = maxW - p.measureText(ell);
        int n = s.length();
        while (n > 0 && p.measureText(s.substring(0, n)) > room) n--;
        return n > 0 ? s.substring(0, n) + ell : ell;
    }

    /** 歌词这类「不能截断、不能换行」的文字：一次按宽度算准字号，
        不设 22px 硬下限，再长的整句也能完整放进屏幕（代价是字变小）。 */
    private void fitExact(Paint p, String s, float maxW, float start) {
        p.setTextSize(start);
        float w = p.measureText(s);
        if (w > maxW && w > 0f)
            p.setTextSize(Math.max(10f, start * maxW / w * 0.99f));
    }

    private boolean bannerActive() {
        return bannerText.length() > 0 && System.currentTimeMillis() <= bannerUntil;
    }

    /** 统一圆角块：大卡片保留常规圆角；细进度条自动收敛为半圆端（胶囊）。
        老 WebKit/Skia 在 rx、ry 大于半高时不会裁切，会把圆角画成尖锐楔形，
        所以这里按自身宽高封顶，短条（刚开唱）也不会露尖。 */
    private static void card(Canvas c, Paint p, float x, float y, float w, float h, int W) {
        float r = Math.min(W * 0.0094f, Math.min(w, h) * 0.5f);
        c.drawRoundRect(new RectF(x, y, x + w, y + h), r, r, p);
    }

    /** 手绘矢量图标 + 汉字，不用 emoji（API 15 字库不稳、颜色不可控）。 */
    private void icon(Canvas c, int kind, float cx, float cy, float s, int W) {
        float sw = Math.max(3f, W * 0.0032f);
        pIco.setStyle(Paint.Style.STROKE);
        pIco.setStrokeWidth(sw);
        if (kind == ICON_VOICE) {
            // 麦克风：胶囊 + U 形支架 + 底座；伴奏态加一道斜杠
            c.drawRoundRect(new RectF(cx - s * 0.34f, cy - s * 0.92f,
                                      cx + s * 0.34f, cy + s * 0.12f),
                            s * 0.34f, s * 0.34f, pIco);
            Path u = new Path();
            u.moveTo(cx - s * 0.68f, cy - s * 0.10f);
            u.quadTo(cx - s * 0.68f, cy + s * 0.52f, cx, cy + s * 0.52f);
            u.quadTo(cx + s * 0.68f, cy + s * 0.52f, cx + s * 0.68f, cy - s * 0.10f);
            c.drawPath(u, pIco);
            c.drawLine(cx, cy + s * 0.52f, cx, cy + s * 0.86f, pIco);
            c.drawLine(cx - s * 0.38f, cy + s * 0.86f, cx + s * 0.38f, cy + s * 0.86f, pIco);
            if (accompanimentNow())
                c.drawLine(cx - s * 0.92f, cy - s * 0.86f, cx + s * 0.92f, cy + s * 0.86f, pIco);
        } else if (kind == ICON_REPLAY) {
            // 回转箭头：一段圆弧 + 端点 V 形箭头，全程圆头描边。
            // 旧版用 FILL 实心三角压在细线圆弧上，尖角与线宽不一致，接缝处显毛刺。
            float r = s * 0.72f, deg0 = 35f, sweep = 290f;
            c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r),
                      deg0, sweep, false, pIco);
            double a = Math.toRadians(deg0 + sweep);       // 圆弧终点（右上）
            float ex = cx + (float) Math.cos(a) * r, ey = cy + (float) Math.sin(a) * r;
            float tx = -(float) Math.sin(a), ty = (float) Math.cos(a);  // 切线
            float nx = -ty, ny = tx;                                    // 法线
            float L = s * 0.44f;
            float tipX = ex + tx * L * 0.45f, tipY = ey + ty * L * 0.45f;
            float bx = ex - tx * L * 0.55f, by = ey - ty * L * 0.55f;
            c.drawLine(tipX, tipY, bx + nx * L * 0.52f, by + ny * L * 0.52f, pIco);
            c.drawLine(tipX, tipY, bx - nx * L * 0.52f, by - ny * L * 0.52f, pIco);
        } else if (kind == ICON_SKIP) {
            Path t1 = new Path();
            t1.moveTo(cx - s * 0.82f, cy - s * 0.62f);
            t1.lineTo(cx - s * 0.82f, cy + s * 0.62f);
            t1.lineTo(cx - s * 0.02f, cy);
            t1.close();
            Path t2 = new Path();
            t2.moveTo(cx + s * 0.02f, cy - s * 0.62f);
            t2.lineTo(cx + s * 0.02f, cy + s * 0.62f);
            t2.lineTo(cx + s * 0.62f, cy);
            t2.close();
            pIco.setStyle(Paint.Style.FILL);
            c.drawPath(t1, pIco);
            c.drawPath(t2, pIco);
            pIco.setStyle(Paint.Style.STROKE);
            c.drawLine(cx + s * 0.78f, cy - s * 0.62f, cx + s * 0.78f, cy + s * 0.62f, pIco);
        } else if (kind == ICON_LIST) {
            for (int i = 0; i < 3; i++) {
                float y = cy - s * 0.50f + i * s * 0.50f;
                pIco.setStyle(Paint.Style.FILL);
                c.drawCircle(cx - s * 0.62f, y, sw * 0.9f, pIco);
                pIco.setStyle(Paint.Style.STROKE);
                c.drawLine(cx - s * 0.28f, y, cx + s * 0.80f, y, pIco);
            }
        } else if (kind == ICON_VOLUME) {
            Path h = new Path();
            h.moveTo(cx - s * 0.82f, cy - s * 0.26f);
            h.lineTo(cx - s * 0.34f, cy - s * 0.26f);
            h.lineTo(cx + s * 0.12f, cy - s * 0.72f);
            h.lineTo(cx + s * 0.12f, cy + s * 0.72f);
            h.lineTo(cx - s * 0.34f, cy + s * 0.26f);
            h.lineTo(cx - s * 0.82f, cy + s * 0.26f);
            h.close();
            c.drawPath(h, pIco);
            c.drawArc(new RectF(cx + s * 0.10f, cy - s * 0.42f,
                                cx + s * 0.80f, cy + s * 0.42f), -58f, 116f, false, pIco);
        }
        pIco.setStyle(Paint.Style.STROKE);
    }

    class KView extends View {
        KView(Context c) { super(c); }

        @Override
        protected void onDraw(Canvas c) {
            int W = getWidth(), H = getHeight();
            if (W <= 0 || H <= 0) return;
            float pad = W * 0.045f;

            c.drawRect(0, 0, W, H, pBg);

            if (usbGone) { drawUsbGone(c, W, H); return; }
            if (System.currentTimeMillis() < interludeUntil) { drawInterlude(c, W, H); return; }

            drawTopBar(c, W, H, pad);
            drawMarquee(c, W, H);

            if (volCard) drawVolCard(c, W, H, pad);
            else if (showList) drawLibList(c, W, H, pad);
            else drawLyricsArea(c, W, H, pad);

            int dur = duration();
            float bw = W - 2 * pad, by = H * 0.775f, bh = Math.max(10, H * 0.011f);
            card(c, pBar, pad, by, bw, bh, W);
            if (dur > 0)
                card(c, pBarOn, pad, by, bw * (position() / (float) dur), bh, W);

            drawButtonBar(c, W, H, pad);
            drawBanner(c, W, H);
        }

        /* ---------- 顶栏：歌名 / 进度 / 状态 / 弱化的音量小卡 ---------- */

        private void drawTopBar(Canvas c, int W, int H, float pad) {
            String title = songs.isEmpty() ? "K 歌 · 等待曲库" : songs.get(cur).name;
            float chipW = W * 0.150f, chipX = W - pad - chipW;

            pTitle.setTextSize(W * FS_NAME);
            // 固定字号；超长截尾加省略号，0.30W 保证终点在跑马灯窗口 0.355W 之前
            c.drawText(ellipsize(pTitle, title, W * 0.30f), pad, H * 0.098f, pTitle);

            pSub.setTextSize(W * FS_SUB);
            String meta = (songs.isEmpty() ? "0 / 0" : ("第 " + (cur + 1) + " / " + songs.size() + " 首"))
                + "      " + mmss(position()) + " / "
                + (duration() > 0 ? mmss(duration()) : "--:--");
            c.drawText(meta, pad, H * 0.158f, pSub);

            // 唯一一行操作提示，放在顶栏右侧空处：不占独立一行、不压按钮条
            pSub.setTextAlign(Paint.Align.RIGHT);
            pSub.setColor(err.length() > 0 ? BAD : INK_DIM);
            fit(pSub, hintText(), W * 0.44f, W * FS_SUB);
            c.drawText(hintText(), chipX - W * 0.020f, H * 0.158f, pSub);
            pSub.setColor(INK_SOFT);

            String flags = playing ? "▶ 播放中" : "‖ 已暂停";
            c.drawText(flags, chipX - W * 0.020f, H * 0.098f, pSub);
            pSub.setTextAlign(Paint.Align.LEFT);

            // 音量：低频操作，压成顶栏右端一张低对比小卡，不占主按钮大格
            float chipY = H * 0.045f, chipH = H * 0.115f;
            card(c, pCard, chipX, chipY, chipW, chipH, W);
            pIco.setColor(INK_DIM);
            icon(c, ICON_VOLUME, chipX + W * 0.024f, chipY + chipH / 2, W * 0.010f, W);
            pIco.setColor(INK);
            float lx = chipX + W * 0.048f, lw = chipW - W * 0.070f;
            float lh = Math.max(5, H * 0.009f);
            card(c, pBar, lx, chipY + chipH * 0.34f, lw, lh, W);
            card(c, pCardOn, lx, chipY + chipH * 0.34f, lw * gVocal, lh, W);
            card(c, pBar, lx, chipY + chipH * 0.62f, lw, lh, W);
            card(c, pCardOn, lx, chipY + chipH * 0.62f, lw * gBack, lh, W);
            // 数字 5 角标，说明这是第 5 个动作
            pNum.setColor(CARD_ON);
            pNum.setStyle(Paint.Style.FILL);
            c.drawCircle(chipX + chipW - W * 0.012f, chipY + W * 0.012f, W * 0.011f, pNum);
            pNum.setColor(INK_SOFT);
            pNum.setTextSize(W * 0.014f);
            c.drawText("5", chipX + chipW - W * 0.012f, chipY + W * 0.017f, pNum);
        }

        /* ---------- 顶部中间：欢迎语 + 下一首，右→左无缝循环 ---------- */

        private void drawMarquee(Canvas c, int W, int H) {
            String tail = songs.isEmpty() ? "" : ("下一首：" + songName(peekNext()));
            float lx = W * 0.355f, rx = W * 0.685f, base = H * 0.098f;
            pSub.setTextAlign(Paint.Align.LEFT);
            pSub.setTextSize(W * 0.019f);
            float hw = pSub.measureText(welcomeText);
            float unit = hw + pSub.measureText(tail) + W * 0.060f;  // 文本 + 轮次间隔
            float speed = W * 0.00007f;                              // ≈90px/s @1280
            long period = Math.max(1L, (long) (unit / speed));
            float off = (System.currentTimeMillis() % period) * speed;
            c.save();
            c.clipRect(lx, H * 0.055f, rx, H * 0.118f);
            // 多画几份副本，保证窗口左端与右端同时有字，循环处不会突然空白
            for (int k = -2; k <= 2; k++) {
                float x = rx - off + k * unit;
                pSub.setColor(INK_DIM);
                c.drawText(welcomeText, x, base, pSub);
                if (tail.length() > 0) {
                    pSub.setColor(GOLD);
                    c.drawText(tail, x + hw, base, pSub);
                }
            }
            c.restore();
            pSub.setColor(INK_SOFT);
            pSub.setTextSize(W * FS_SUB);
        }

        /* ---------- 四个等宽大按钮：图标 + 汉字 + 数字角标 ---------- */

        private void drawButtonBar(Canvas c, int W, int H, float pad) {
            float gap = W * 0.012f;
            float bw = (W - 2 * pad - 3 * gap) / 4f;
            float bh = H * 0.145f, y0 = H * 0.805f;
            boolean barFocus = !showList && !volCard && !bannerActive();
            long elapsed = System.currentTimeMillis() - focusSwitchAt;
            float anim = elapsed < 120f ? ((float) elapsed / 120f) : 1f;
            if (anim < 0f) anim = 0f;
            if (anim > 1f) anim = 1f;

            for (int k = 0; k < 4; k++) {
                float x = pad + k * (bw + gap);
                boolean on = barFocus && k == activeBtn;
                // 焦点底色 120ms 过渡：唯一保留的动效；用独立 Paint 避免串色
                Paint bg = pCard;
                if (on) {
                    pFocusBg.setARGB(
                        (int) (Color.alpha(CARD) + (Color.alpha(CARD_ON) - Color.alpha(CARD)) * anim),
                        (int) (Color.red(CARD) + (Color.red(CARD_ON) - Color.red(CARD)) * anim),
                        (int) (Color.green(CARD) + (Color.green(CARD_ON) - Color.green(CARD)) * anim),
                        (int) (Color.blue(CARD) + (Color.blue(CARD_ON) - Color.blue(CARD)) * anim));
                    bg = pFocusBg;
                }
                card(c, bg, x, y0, bw, bh, W);
                if (on) card(c, pFocus, x, y0, bw, bh, W);  // 全屏唯一的金黄焦点框

                // 数字角标
                pNum.setStyle(Paint.Style.FILL);
                pNum.setColor(on ? GOLD : INK_DIM);
                c.drawCircle(x + W * 0.020f, y0 + W * 0.020f, W * 0.013f, pNum);
                pNum.setColor(BG);
                pNum.setTextSize(W * 0.017f);
                c.drawText(String.valueOf(k + 1), x + W * 0.020f,
                           y0 + W * 0.026f, pNum);

                // 图标
                pIco.setColor(on ? GOLD : INK);
                int kind = (k == 0) ? ICON_VOICE : (k == 1) ? ICON_REPLAY
                         : (k == 2) ? ICON_SKIP : ICON_LIST;
                icon(c, kind, x + bw / 2, y0 + bh * 0.30f, W * 0.016f, W);
                pIco.setColor(INK);

                // 汉字（按钮 1 显示当前状态：原唱 / 伴奏）
                String label = (k == 0) ? (accompanimentNow() ? "伴奏" : "原唱") : BTN_LABELS[k];
                pCur.setTextAlign(Paint.Align.CENTER);
                pCur.setTextSize(W * FS_BTN);
                pCur.setColor(on ? GOLD : INK);
                fit(pCur, label, bw * 0.90f, W * FS_BTN);
                c.drawText(label, x + bw / 2, y0 + bh * 0.86f, pCur);
                pCur.setColor(INK);
            }
        }

        /* ---------- 顶栏一行提示：只留最必要的三条 ---------- */

        private String hintText() {
            if (err.length() > 0) return err;
            if (volCard) return "←→ 原唱音量    ↑↓ 伴奏音量    5 收起";
            if (showList) return "↑↓ 选歌    OK 马上唱    4 返回";
            return "OK 播放 / 暂停    1-5 直达";
        }

        /* ---------- 内容区 A：歌词（逐字染色 / 前奏音符 / 上滚切行） ---------- */

        private void drawLyricsArea(Canvas c, int W, int H, float pad) {
            if (songs.isEmpty()) { drawEmpty(c, W, H); return; }
            Song s = songs.get(cur);
            if (!s.hasLrc) { drawNoLrc(c, W, H); return; }
            drawLyrics(c, W, H, s, position());
        }

        private long firstRealMs(Song s) {
            for (int k = 0; k < s.lines.size(); k++) {
                Line l = s.lines.get(k);
                if (l.ms < 1000) continue;
                if (LRC_META.matcher(l.text).find()) continue;
                return l.ms;
            }
            return 0;
        }

        private long nextLineMs(Song s, int i) {
            long start = (i >= 0 && i < s.lines.size()) ? s.lines.get(i).ms : 0;
            if (i >= 0 && i + 1 < s.lines.size()) return s.lines.get(i + 1).ms;
            return Math.max(start + 1000L, (long) duration());
        }

        private float lineFrac(Song s, long pos, int i) {
            if (i < 0) return 0f;
            long start = s.lines.get(i).ms, end = nextLineMs(s, i);
            if (end <= start) return 1f;
            float f = (pos - start) / (float) (end - start);
            return f < 0f ? 0f : (f > 1f ? 1f : f);
        }

        /* 前奏：NOTES 个手绘音符按时间推进，走过的整颗金黄、正在走的局部染金。
           不显示「间奏 / 换气」：老人看不懂，且 LRC 只有整句时间轴，
           那两个词本来也只是按句间空档猜出来的，不如让歌词自己静止等着。 */

        /** 八分音符：符头 + 符干 + 符尾，纯矢量，不依赖系统字体里的 ♪ 字形。 */
        private void drawNote(Canvas c, float cx, float cy, float s, Paint p) {
            c.save();
            c.translate(cx, cy);
            p.setStyle(Paint.Style.FILL);
            c.save();
            c.rotate(-20f);
            c.drawOval(new RectF(-s * 0.42f, s * 0.14f, s * 0.16f, s * 0.46f), p);
            c.restore();
            c.drawRect(new RectF(s * 0.10f, -s * 0.60f,
                                 s * 0.10f + Math.max(3f, s * 0.055f), s * 0.30f), p);
            Path tail = new Path();
            tail.moveTo(s * 0.15f, -s * 0.60f);
            tail.quadTo(s * 0.54f, -s * 0.48f, s * 0.47f, -s * 0.10f);
            tail.quadTo(s * 0.37f, -s * 0.34f, s * 0.15f, -s * 0.40f);
            tail.close();
            c.drawPath(tail, p);
            c.restore();
        }

        /** 前奏音符条：固定画在歌词第一句上方；fade<1 时缩小、上浮、淡出。
            走过的整颗金黄、正在走的局部染金，与歌词逐字染色同一套裁剪。
            倒计时只留一个数字，紧接在末颗音符后面，整组一起居中、一起淡出。 */
        private void drawIntroNotes(Canvas c, int W, int H, long pos, long first,
                                    float fade, float cy) {
            if (fade <= 0f) return;
            float s = W * 0.0333f * (0.88f + 0.12f * fade);   // 较初版缩小 1/3
            float gap = W * 0.0173f;                           // 间距同比例缩小，不变散
            float notesW = NOTES * s + (NOTES - 1) * gap;
            float ts = W * 0.026f;
            long left = Math.max(0L, first - pos);       // 淡出那 450ms 不出现负数
            long n = left / 1000 + 1;
            String sec = (n < 10 ? "0" : "") + n;        // 固定两位，09 / 08 …
            pNext.setTextSize(ts);
            // 按两位宽度占位，数字变化时不带动音符条左右抖动
            float numGap = W * 0.018f, numW = pNext.measureText("00");
            float x0 = (W - (notesW + numGap + numW)) / 2f;
            float progress = first <= 0 ? 0f : pos / (float) first;
            if (progress < 0f) progress = 0f;
            if (progress > 1f) progress = 1f;
            int alpha = (int) (255 * fade);
            for (int k = 0; k < NOTES; k++) {
                float cx = x0 + k * (s + gap) + s * 0.45f;
                float frac = progress * NOTES - k;
                if (frac < 0f) frac = 0f;
                if (frac > 1f) frac = 1f;
                pNote.setColor(NOTE_OFF);
                pNote.setAlpha(alpha);
                drawNote(c, cx, cy, s, pNote);
                if (frac > 0f) {
                    c.save();
                    c.clipRect(cx - s * 0.50f, cy - s * 0.72f,
                               cx - s * 0.50f + s * 1.05f * frac, cy + s * 0.56f);
                    pNote.setColor(GOLD);
                    pNote.setAlpha(alpha);
                    drawNote(c, cx, cy, s, pNote);
                    c.restore();
                }
            }
            pNote.setAlpha(255);
            // 纯数字倒计时：不再有「还有 N 秒开唱」这类文字
            pNext.setTextAlign(Paint.Align.LEFT);
            pNext.setColor(INK_SOFT);
            pNext.setAlpha(alpha);
            c.drawText(sec, x0 + notesW + numGap, cy + ts * 0.36f, pNext);
            pNext.setAlpha(255);
            pNext.setTextAlign(Paint.Align.CENTER);
        }

        private int mixColor(int a, int b, float t) {
            if (t < 0f) t = 0f;
            if (t > 1f) t = 1f;
            return Color.argb(
                Color.alpha(a) + (int) ((Color.alpha(b) - Color.alpha(a)) * t),
                Color.red(a)   + (int) ((Color.red(b)   - Color.red(a))   * t),
                Color.green(a) + (int) ((Color.green(b) - Color.green(a)) * t),
                Color.blue(a)  + (int) ((Color.blue(b)  - Color.blue(a))  * t));
        }

        /** 已唱部分染成金黄，未唱留白；普通逐行 LRC 只能按句时长线性推进。 */
        private void drawHighlightText(Canvas c, String s, float cx, float y,
                                       float frac, Paint p, int W, int gold) {
            if (s.length() == 0) return;
            float tw = p.measureText(s), left = cx - tw / 2;
            float top = y - W * 0.06f, bottom = y + W * 0.025f;
            int old = p.getColor();
            c.save();
            c.clipRect(left, top, left + tw * frac, bottom);
            p.setColor(gold);
            c.drawText(s, cx, y, p);
            c.restore();
            c.save();
            c.clipRect(left + tw * frac, top, left + tw + 2, bottom);
            p.setColor(old);
            c.drawText(s, cx, y, p);
            c.restore();
            p.setColor(old);
        }

        /** 当前句唱完，三行整体上滚一格：前一句 / 当前句 / 下一句同步变换。
            只在「自然往后走一句」时滚；快进、快退、换歌都直接就位。 */
        private void drawLyrics(Canvas c, int W, int H, Song s, int pos) {
            if (s.lines.isEmpty()) return;
            long first = firstRealMs(s);
            boolean intro = first > 0 && pos < first;
            // 前奏时第一句是「预备态」灰字，开唱后随音符淡出同步点亮成主文字
            float lit = 1f;
            if (first > 0) {
                lit = intro ? 0f
                     : Math.min(1f, Math.max(0f, (pos - first) / (float) INTRO_FADE_MS));
            }

            int i = lineAt(s, pos);
            if (i < 0) i = 0;
            if (i != rollLastLine) {
                // 上一滚没结束又来新句（短句连唱）就直接就位，别叠加动画
                boolean busy = rollStartAt > 0
                    && System.currentTimeMillis() - rollStartAt < ROLL_MS;
                boolean roll = playing && !busy && rollLastLine >= 0 && i == rollLastLine + 1;
                rollStartAt = roll ? System.currentTimeMillis() : 0;
                rollLastLine = i;
            }
            float p = 1f;
            if (rollStartAt > 0) {
                long e = System.currentTimeMillis() - rollStartAt;
                if (e < ROLL_MS) {
                    float q = e / (float) ROLL_MS;
                    // 余弦 ease-in-out：慢起、中间匀、慢收，不再起步猛地一窜
                    p = (float) (0.5 - 0.5 * Math.cos(Math.PI * q));
                } else {
                    rollStartAt = 0;
                }
            }

            float cy = H * 0.46f, gap = H * 0.165f;
            float shift = (1f - p) * gap;           // p=0 时整列还在上一句的位置
            c.save();
            c.clipRect(0, H * 0.205f, W, H * 0.735f);
            pCur.setTextAlign(Paint.Align.CENTER);
            for (int d = -2; d <= 2; d++) {
                int k = i + d;
                if (k < 0 || k >= s.lines.size()) continue;
                float y = cy + d * gap + shift;
                float off = Math.abs(y - cy) / gap;
                // 滚出内容区上下边界时淡出，不留下被裁断的半行字
                float fade = Math.min((y - H * 0.205f) / (H * 0.085f),
                                      (H * 0.735f - y) / (H * 0.085f));
                if (fade <= 0f) continue;
                if (fade > 1f) fade = 1f;
                float near = (1f - Math.min(1f, off)) * fade;   // 1 = 正中
                String txt = s.lines.get(k).text;
                if (txt.length() == 0) continue;
                fitExact(pCur, txt, W * 0.88f,
                         W * (0.026f + (FS_LYRIC - 0.026f) * near));

                int base;
                if (k == i - 1) {
                    // 刚唱完的上一句：满金色，随上滚褪成灰字
                    base = mixColor(mixColor(INK_DIM, INK_SOFT, near), GOLD, 1f - p);
                } else if (k == i) {
                    base = mixColor(INK_SOFT, INK, near * lit);
                } else {
                    base = mixColor(INK_DIM, INK_SOFT, near);
                }
                pCur.setColor(mixColor(BG, base, fade));

                if (k == i) {
                    float frac = lineFrac(s, pos, i) * p;
                    drawHighlightText(c, txt, W / 2, y, frac, pCur, W,
                                      mixColor(BG, GOLD, fade));
                } else {
                    c.drawText(txt, W / 2, y, pCur);
                }
            }
            c.restore();

            /* 前奏音符钉在第一句上方（cy - gap）。开唱不再硬切：
               450ms 内淡出 + 上浮 + 略缩，第一句同时由灰转亮，完成交接。 */
            float fade = 0f;
            if (intro) fade = 1f;
            else if (first > 0 && pos >= first && pos - first < INTRO_FADE_MS)
                fade = 1f - (pos - first) / (float) INTRO_FADE_MS;
            if (fade > 0f) {
                float notesY = H * 0.46f - H * 0.165f - (1f - fade) * H * 0.035f;
                drawIntroNotes(c, W, H, pos, first, fade, notesY);
            }
        }

        /* ---------- 内容区 B：歌单（U 盘目录即已点歌单，三态） ---------- */

        private void drawLibList(Canvas c, int W, int H, float pad) {
            float x = pad, w = W - 2 * pad, y0 = H * 0.215f, rh = H * 0.088f;
            syncLibTop();
            pSub.setTextSize(W * FS_SUB);
            pSub.setTextAlign(Paint.Align.LEFT);
            c.drawText("全部 " + songs.size() + " 首  ·  灰=已唱过  金黄=正在唱",
                       x, H * 0.198f, pSub);
            int rest = songs.size() - libTop - 6;
            if (rest > 0) {
                pSub.setTextAlign(Paint.Align.RIGHT);
                c.drawText("下面还有 " + rest + " 首，继续按 ↓", x + w, H * 0.198f, pSub);
                pSub.setTextAlign(Paint.Align.LEFT);
            }

            pCur.setTextAlign(Paint.Align.LEFT);
            for (int k = 0; k < 6; k++) {
                int idx = libTop + k;
                if (idx >= songs.size()) break;
                float y = y0 + k * rh, h = rh * 0.86f;
                boolean on = idx == listCursor;
                boolean sung = idx < cur;
                boolean now = idx == cur;
                if (now) card(c, pCardOn, x, y, w, h, W);
                else card(c, pCard, x, y, w, h, W);
                if (on && !bannerActive()) card(c, pFocus, x, y, w, h, W);

                // 序号
                pSub.setTextAlign(Paint.Align.LEFT);
                pSub.setTextSize(W * FS_SUB);
                pSub.setColor(sung ? INK_DIM : (now ? GOLD : INK_SOFT));
                c.drawText(String.format("%02d", idx + 1), x + W * 0.018f, y + h * 0.66f, pSub);

                // 歌名
                pCur.setTextSize(W * FS_BTN);
                pCur.setColor(sung ? INK_DIM : (now ? GOLD : INK));
                String nm = songs.get(idx).name;
                fit(pCur, nm, w * 0.62f, W * FS_BTN);
                c.drawText(ellipsize(pCur, nm, w * 0.62f),
                           x + W * 0.075f, y + h * 0.66f, pCur);

                // 三态标签
                String tag = now ? "▶ 正在唱" : (sung ? "已唱过" : "");
                if (tag.length() > 0) {
                    pSub.setTextAlign(Paint.Align.RIGHT);
                    pSub.setTextSize(W * FS_SUB);
                    pSub.setColor(sung ? INK_DIM : GOLD);
                    c.drawText(tag, x + w - W * 0.018f, y + h * 0.66f, pSub);
                }
            }
            pCur.setTextAlign(Paint.Align.CENTER);
            pCur.setColor(INK);
            pSub.setTextAlign(Paint.Align.LEFT);
        }

        /* ---------- 内容区 C：调音卡（低频操作，2 秒自动收起） ---------- */

        private void drawVolCard(Canvas c, int W, int H, float pad) {
            float cw = W * 0.62f, ch = H * 0.50f;
            float x = (W - cw) / 2, y = H * 0.225f;
            card(c, pCard, x, y, cw, ch, W);
            if (!bannerActive()) card(c, pLine, x, y, cw, ch, W);

            pTitle.setTextAlign(Paint.Align.CENTER);
            pTitle.setTextSize(W * FS_NAME);
            c.drawText("音  量", W / 2, y + H * 0.075f, pTitle);
            pTitle.setTextAlign(Paint.Align.LEFT);

            drawMeter(c, W, x + W * 0.05f, y + H * 0.135f, cw - W * 0.10f,
                      "原唱", gVocal, true);
            drawMeter(c, W, x + W * 0.05f, y + H * 0.285f, cw - W * 0.10f,
                      "伴奏", gBack, false);

            pSub.setTextAlign(Paint.Align.CENTER);
            pSub.setTextSize(W * FS_SUB);
            pSub.setColor(INK_DIM);
            String note = (songs.isEmpty() || !isMix(songs.get(cur)))
                ? "这首歌只有单音轨，调好的音量会在分轨歌曲上生效"
                : "调好自动记住，下次开机还是这个音量";
            fit(pSub, note, cw * 0.92f, W * FS_SUB);
            c.drawText(note, W / 2, y + H * 0.435f, pSub);
            pSub.setTextAlign(Paint.Align.LEFT);
            pSub.setColor(INK_SOFT);
        }

        private void drawMeter(Canvas c, int W, float x, float y, float w,
                               String label, float g, boolean vocal) {
            pSub.setTextAlign(Paint.Align.LEFT);
            pSub.setTextSize(W * FS_NAME);
            pSub.setColor(INK);
            c.drawText(label, x, y, pSub);
            pSub.setTextAlign(Paint.Align.RIGHT);
            pCur.setTextAlign(Paint.Align.RIGHT);
            pCur.setTextSize(W * 0.042f);
            pCur.setColor(vocal ? TEAL : GOLD);
            c.drawText(pct(g), x + w, y, pCur);
            pCur.setTextAlign(Paint.Align.CENTER);

            float bh = Math.max(16, W * 0.018f);
            card(c, pBar, x, y + W * 0.014f, w, bh, W);
            pMeter.setColor(vocal ? TEAL : GOLD);
            card(c, pMeter, x, y + W * 0.014f, w * g, bh, W);
            pSub.setTextAlign(Paint.Align.LEFT);
            pSub.setColor(INK_SOFT);
        }

        /* ---------- 大字反馈 / 预告 / 过场 / 拔盘 / 空库 ---------- */

        private void drawBanner(Canvas c, int W, int H) {
            if (!bannerActive()) return;
            float bw = W * 0.60f, bh = H * 0.100f, x = (W - bw) / 2, y = H * 0.20f;
            card(c, pCardOn, x, y, bw, bh, W);
            card(c, pFocus, x, y, bw, bh, W);
            pBreath.setTextAlign(Paint.Align.CENTER);
            pBreath.setTextSize(W * 0.026f);
            pBreath.setColor(Color.WHITE);
            fit(pBreath, bannerText, bw * 0.92f, W * 0.026f);
            c.drawText(ellipsize(pBreath, bannerText, bw * 0.92f),
                       W / 2, y + bh * 0.66f, pBreath);
        }

        private void drawInterlude(Canvas c, int W, int H) {
            pCur.setTextAlign(Paint.Align.CENTER);
            pCur.setTextSize(W * 0.032f);
            pCur.setColor(INK_SOFT);
            c.drawText("接  下  来", W / 2, H * 0.36f, pCur);
            pCur.setTextSize(W * 0.048f);
            pCur.setColor(GOLD);
            fit(pCur, interludeName, W * 0.86f, W * 0.048f);
            c.drawText(ellipsize(pCur, interludeName, W * 0.86f),
                       W / 2, H * 0.53f, pCur);
            pNext.setTextSize(W * 0.024f);
            pNext.setColor(INK_SOFT);
            c.drawText("马上开始…", W / 2, H * 0.65f, pNext);
        }

        private void drawUsbGone(Canvas c, int W, int H) {
            card(c, pCard, W * 0.16f, H * 0.30f, W * 0.68f, H * 0.38f, W);
            pCur.setTextAlign(Paint.Align.CENTER);
            pCur.setTextSize(W * 0.042f);
            pCur.setColor(GOLD);
            c.drawText("U 盘 已 拔 出", W / 2, H * 0.45f, pCur);
            pNext.setTextSize(W * 0.026f);
            pNext.setColor(INK);
            c.drawText("请插回 U 盘，插回后自动接着唱", W / 2, H * 0.55f, pNext);
        }

        private void drawNoLrc(Canvas c, int W, int H) {
            pCur.setTextSize(W * 0.040f);
            pCur.setColor(INK);
            c.drawText("♪  正在播放  ♪", W / 2, H * 0.45f, pCur);
            pNext.setTextSize(W * 0.024f);
            c.drawText("放一个和歌曲同名的 .lrc 到 U 盘即可显示歌词", W / 2, H * 0.56f, pNext);
        }

        private void drawEmpty(Canvas c, int W, int H) {
            pCur.setTextSize(W * 0.038f);
            pCur.setColor(INK);
            c.drawText("未找到歌曲", W / 2, H * 0.40f, pCur);
            pNext.setTextSize(W * 0.024f);
            c.drawText("把 MP3 和同名 .lrc 放进 U 盘，插到电视 USB 口", W / 2, H * 0.52f, pNext);
            c.drawText("插好后按 菜单 重新扫描", W / 2, H * 0.59f, pNext);
        }
    }
}
