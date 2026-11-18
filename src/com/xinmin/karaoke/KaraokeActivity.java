// Copyright 2026 Simon Yang <https://github.com/vsiryxm/tv-karaoke-box>
// SPDX-License-Identifier: MIT
package com.xinmin.karaoke;

/*
 * 电视端离线 K 歌机 —— Android 4.0.4 / API 15，零依赖、零网络、零电脑。
 * 面向老人：单屏、大字、图标+文字、数字键 1/2/3/0 直达，无二级菜单层级。
 *
 * 单主干双 flavor：本文件是唯一真源。演出模式相关字段 / 方法 / 分支全部包在
 *   LIVE 块开始标记 ... LIVE 块结束标记
 * 标记块里；默认构建由 flavor.py 在编译前整块剥离，产出纯 K 歌的开源版；
 * build.sh --flavor live 才保留，编译出定制版。删块后源码必须仍是合法 Java，
 * 所以块内一律用「早返回 / 覆盖赋值 / if-else 接后续语句」三种写法，不破坏语法。
 *
 * 曲库严格三件套（U 盘同目录，只认 .mp3 / .lrc）：
 *   歌名.mp3          原唱
 *   歌名.lrc          歌词
 *   歌名.伴奏.mp3     伴奏（可选；缺了这首歌就只有原唱）
 * 旧版 .人声 / -inst / .vocal / .导唱 等分轨文件一律忽略，不会混进歌单。
 *
 * 排序：累计点歌次数降序 -> 文件名升序。次数存在 karaoke_counts 偏好里，
 * 每次从头唱一首记一次（切音轨 / seek / 开机恢复不计），扫描曲库时生效。
 *
 * 按钮：1 原唱/伴奏  2 重唱（整首从头）  3 歌单  0 切歌（数字键直达 / 鼠标点均可）
 *       直播模式下 3 变「节目」、0 变「切节目」，顶栏跑马灯预告的是下一个节目。
 * 键盘 / 遥控器：OK 播放暂停，M 切原唱伴奏，菜单开歌单，← → 双击快退快进 10 秒。
 * 音频输出走电视自身扬声器 / 3.5mm 输出；本机不提供麦克风输入。
 */
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
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
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KaraokeActivity extends Activity {

    /* 代码分区（按出现顺序，便于定位，改动时不必通读全文）：
       1  常量与可配置项        2  数据模型 Line / Song       3  界面状态字段
       4  生命周期 onCreate     5  偏好与 karaoke.conf 读取   6  曲库扫描与 LRC 解析
       7  播放控制（play/seek） 8  按键与鼠标                  9  绘制辅助
       10 KView：绘制、触摸、全部画面
    */

    private static final String TAG = "Karaoke";
    private static final String PREFS = "karaoke_v4";
    private static final String PREFS_COUNT = "karaoke_counts";
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

    /** 伴奏只认「歌名.伴奏.mp3」这一种写法。 */
    private static final String INST_SUFFIX = ".伴奏";
    /** 旧版分轨残留：不进歌单，避免歌名里混出「歌名.人声」之类条目。 */
    private static final String[] LEGACY_SUFFIXES = {
        ".人声", "-人声", "_人声", "(人声)", " (人声)",
        ".vocal", "-vocal", "_vocal", ".导唱", "-导唱",
        "-inst", "_inst", ".inst", " (inst)", "(inst)"
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
    /* ---------- 可配置项：karaoke.conf（APK 内 assets 作默认，U 盘根目录再覆盖） ----------
       一行一个 key=value，井号开头是注释，键名大小写不敏感，值里用 \n 表示换行。
       开源版只认两个通用键：
         app_name       台名（启动页、顶栏、开机欢迎语都用它）
         welcome        跑马灯欢迎语，不写就用「欢迎来到 <台名>，」
       定制版额外支持的键、默认值与校验规则见 karaoke.conf.example 和 README。
       构建期默认值由 build.sh 从 karaoke.conf 白名单写进 APK 的 assets/karaoke.conf，
       两个 flavor 的白名单不同：开源版绝不写入任何演出模式专属键。 */
    private static final String APP_NAME_FALLBACK = "K歌台";
    private String appName = APP_NAME_FALLBACK;
    private String welcomeText = "";

    /* LIVE-BEGIN */
    /* ---------- 演出模式（定制版专属） ----------
       普通模式 = 纯 K 歌；直播模式 = 严格按 U 盘节目单走，含报幕词与四种提醒。
       双击 # 在两种模式间切换，阈值比快进快退宽松；默认每次启动回普通模式，
       节目进度落在 prefs 里，下次进入直播模式接着演。 */
    private static final long POUND_DOUBLE_MS = 600L;
    private long lastPoundAt = 0L;
    private boolean liveMode = false;
    private boolean programWaiting = false;          // 当前是弹奏类：无音频，停住等 OK
    private boolean programEnded = false;            // 整份节目单已演完
    private long programEnterAt = 0L;
    private int programIdx = 0;
    private int programSkipped = 0;
    private boolean programLoaded = false;
    private static final String PROGRAM_FILE = "karaoke_program.txt";
    private static final String OPENINGS_FILE = "karaoke_openings.txt";

    static class ProgramItem {
        String type;                                  // 演唱 / 弹奏
        String key;                                   // 演唱=磁盘文件名 stem，弹奏=节目名
        int songIdx = -1;
        String miss = "";                             // 缺歌曲 / 缺歌词 / 缺伴奏
        boolean skip = false;                         // mp3 与 lrc 同时缺才跳过
    }
    private ArrayList<ProgramItem> program = new ArrayList<ProgramItem>();

    /* 报幕词：按节目名 / 歌名从 U 盘台词文件取，与顺序配置解耦 */
    private HashMap<String, String> openings = new HashMap<String, String>();
    private boolean openingHold = false;
    private int openingSecs = 30;
    private ArrayList<String> openRows = null;
    private String openRowsFor = "";
    private float openRowsW = -1f, openRowsFs = -1f;
    private static final String NO_LINE_START = "，。、；：！？）》」』”’…·%";
    private static final String NO_LINE_END = "（《「『“‘";

    /* 四种提醒（现统一走 Toast，不再常驻卡片）：1 开播 / 2 中场休息 / 3 下播预告 / 4 下播提醒。
        开播看 live_start；下播预告看节目单倒数第 2 个；
        下播提醒 = 已过 live_end 且节目单完整走完一场（走全屏礼花庆祝页，不是 Toast）。
        位次版下播提醒：live_end_program = 第几个节目唱完后弹（1 起；默认 0 = 按整场演完）。
        该节目唱完 → 弹全屏庆祝层并停住（下一首不播）；任意键先关庆祝层，
        再按 OK 才继续下一首。收起后仍停住等 OK。
        配置值超出节目单范围时回退到默认行为（整场演完才弹）。 */
    private int endNoticeProg = 0;
    private boolean endNoticeShown = false;      // 本场是否已弹过位次版下播提醒
    private boolean endNoticeHold = false;       // 位次版提醒已弹、停住等 OK 继续下一首
    private static final long CELEBRATE_MS = 15000L;   // 下播庆祝层停留：15 秒自行收起
    private boolean liveEnabled = false;
    private int liveStartMin = -1, liveEndMin = -1;
    private String liveStartTxt = "", liveEndTxt = "";
    private String noticeStart = "直播开始啦", noticeBreak = "中场休息一下";
    private String noticeWarn = "还有 %d 个节目", noticeEnd = "直播结束啦";
    private long lastLiveMinute = -1;

    /* 直播就绪：停在某个节目上还没开演，等主播按 OK */
    private boolean liveArmed = false;
    /* 记忆：上一场是否已完整走完（1）；本轮开演时刻（>0 表示这一场已经开演过） */
    private static final String P_DONE = "program_done";
    private static final String P_STARTED = "live_started_at";

    /* 下播庆祝：全屏 + 礼花 */
    private boolean celebrating = false;
    private long celebrateAt = 0L, celebrateUntil = 0L;
    private static final int FW_BURSTS = 16, FW_PARTS = 22;
    private static final long FW_SPAN_MS = 12500L;     // 爆炸铺满 12.5 秒，配合 2.4 秒寿命刚好接满 15 秒
    private static final int[] FW_PALETTE = {
        0xFFFFC94A, 0xFFFF6B6B, 0xFF7BE07B, 0xFF6FB3FF,
        0xFFE07BE0, 0xFF63E0DA, 0xFFFFA24A, 0xFFFFFFFF
    };
    private final float[] fwT = new float[FW_BURSTS];      // 各次爆炸的时刻偏移 ms
    private final float[] fwX = new float[FW_BURSTS];      // 爆点横坐标（占屏宽比例）
    private final float[] fwY = new float[FW_BURSTS];      // 爆点纵坐标（占屏高比例）
    private final int[] fwC = new int[FW_BURSTS];          // 每次爆炸的颜色
    private final float[][] fwVX = new float[FW_BURSTS][FW_PARTS];
    private final float[][] fwVY = new float[FW_BURSTS][FW_PARTS];
    private final float[] fwR = new float[FW_PARTS];       // 粒子半径（占屏宽比例）
    private boolean fwReady = false;
    /* LIVE-END */
    private static final int MARQUEE_MS = 50;       // 顶部跑马灯 20fps，匀速横移
    private static final int INTRO_FADE_MS = 450;   // 开唱后前奏音符淡出上浮的时长
    private static final long ROLL_MS = 450;        // 歌词上滚一格时长
    private static final long ROLL_LEAD = 600;      // 换句前多久开始提速采样
    private static final int ROLL_FAST = 25;

    private static final String[] BTN_LABELS = { "原唱", "重唱", "歌单", "切歌" };
    /** 按钮角标：第 4 位是「切歌」，快捷键用 0，不印 4。 */
    private static final String[] BTN_KEYS = { "1", "2", "3", "0" };
    private static final int ICON_VOICE = 0, ICON_REPLAY = 1, ICON_SKIP = 2,
                             ICON_LIST = 3;

    static class Line {
        long ms;
        String text;
        /** LRC 里紧跟这一行的纯空行时间＝「这句唱完了」。0 表示文件没写，扫字终点退回下一行行首。 */
        long endMs = 0;
        Line(long m, String t) { ms = m; text = t; }
    }

    static class Song {
        String name;
        String path;        // 原唱：歌名.mp3
        String instPath;    // 伴奏：歌名.伴奏.mp3；null 表示只有原唱
        int plays;          // 累计点歌次数
        boolean hasLrc;
        ArrayList<Line> lines = new ArrayList<Line>();
    }

    /** 排序：累计点歌次数降序 -> 文件名升序。会话内顺序固定，切歌不乱跳。 */
    private final Comparator<Song> SONG_ORDER = new Comparator<Song>() {
        public int compare(Song a, Song b) {
            if (a.plays != b.plays) return a.plays > b.plays ? -1 : 1;
            int r = a.name.compareToIgnoreCase(b.name);
            return r != 0 ? r : a.name.compareTo(b.name);
        }
    };

    private ArrayList<Song> songs = new ArrayList<Song>();
    private int cur = 0;
    private int listCursor = 0;
    private int libTop = 0;
    private boolean showList = false;      // 中间内容区切成歌单
    private int activeBtn = 0;             // 按钮条上唯一的金黄焦点
    private long focusSwitchAt = 0;        // 焦点切换的 120ms 底色过渡
    private int rollLastLine = Integer.MIN_VALUE;  // 上一帧的当前歌词行
    private long rollStartAt = 0;          // 上滚开始时刻，0 = 没在滚
    private boolean playing = false;
    private boolean useInst = false;       // true=播「歌名.伴奏.mp3」，false=播「歌名.mp3」
    private int seekFloor = 0;
    private long seekFloorUntil = 0;
    /* 调试用：鼠标点/拖全曲进度条快进快退。dragMs >= 0 表示正在拖动，
       画面按目标时间走；电视没有触摸屏，真机上不会触发。 */
    private int dragMs = -1;
    /* 主屏双击 ← / → 快退 / 快进：单击仍不响应（防老人误触），
       350ms 内连按同一个键才生效，步长 10 秒。 */
    private static final long DOUBLE_TAP_MS = 350L;
    private static final int SEEK_STEP_MS = 10000;
    private int lastDirKey = -1;
    private long lastDirAt = 0L;
    private int pressedBtn = -1;                      // 鼠标按下的按钮索引，UP 才触发
    private static final int[] BTN_ACTS = { 1, 2, 3, 0 };  // 按钮 k -> act 的数字键
    /* 鼠标：歌单滚轮 / 按住拖动滚动 / 点击点歌。列表由 listCursor 驱动 libTop，
       滚动必须同步移动选中行，否则每帧 syncLibTop() 会把 libTop 拉回去。 */
    private boolean listTouched = false;
    private float listDownY = 0f;
    private int listDownCursor = 0;
    private int pressedRow = -1;                      // 按下时的行索引，UP 仍在原行才算点击
    private boolean backArmed = false;
    private String err = "";       // 顶栏红字（错误说明），同时弹一条红字 Toast
    private String rootHint = "";

    /* ---------- 统一气泡提示（Toast）---------- */
    /* 全 App 的临时提示都走这里：从屏幕右边往左滑入（避开顶栏）→ 停留 → 淡出。
       新的提示直接替换旧的，同一时刻只显示一条。
       停留时长：一般 5 秒；两类要紧的直播时间提示 15 秒（信息要紧，多留一会儿）。 */
    private static final int TOAST_IN_MS = 320;              // 淡入
    private static final int TOAST_OUT_MS = 320;             // 淡出
    private static final long TOAST_HOLD_MS = 5000L;         // 通用停留
    private static final long TOAST_HOLD_LONG_MS = 15000L;   // 两类要紧的直播时间提示
    private boolean toastOn = false;
    private String toastTag = "";        // 小标签（金色小字）
    private String toastMain = "";       // 大字
    private String toastSub = "";        // 小字
    private long toastStart = 0L;
    private long toastHold = TOAST_HOLD_MS;
    private boolean toastErr = false;    // 错误态：红字

    /* 唱完过场 / 拔盘 */
    private long interludeUntil = 0;
    private String interludeName = "";
    private int pendingIdx = 0;
    private boolean usbGone = false;
    private long lastRecoverAt = 0;

    /* 点歌次数：点歌时只挂「待结算」，自然播到结尾才 +1。
       切歌 / 重唱 / 换音轨 / 退出都不结算；拖到结尾也不算（countVetoed）。 */
    private Song pendingCount = null;
    private boolean countVetoed = false;

    /* 开机记忆：只存文件名，不存序号（以后往 U 盘加歌序号会漂） */
    private SharedPreferences prefs;
    private SharedPreferences counts;
    private String lastSong = "";

    /* 启动过渡页：首帧先画「台名 / 启动中」，曲库扫完才切到主界面。
       首扫没找到歌（U 盘没插）就一直停在启动页提示插盘，每 2 秒重扫。
       scannedHint / scannedWith 由后台扫描线程写、主线程读，只存简单值。 */
    private boolean booting = true;
    private boolean waitingUsb = false;
    private boolean scanning = false;
    private static final int USB_WAIT_RETRY_MS = 2000;
    private final Runnable retryScan = new Runnable() {
        public void run() { if (booting) rescan(); }
    };
    private String scannedHint = "";
    private int scannedWith = 0;

    private MediaPlayer player;
    /** MediaPlayer 实例代数：每次 stopPlayer 自增，旧实例的回调一律忽略。
        老电视的 MediaPlayer 在 release() 时会补发一次 onCompletion，
        不加这道闸，切歌/重唱/换音轨会被误判成「唱完了」，自动跳到下一首。 */
    private int playerGen = 0;
    /** 过场令牌：用户中途操作后作废旧的「2 秒过场 -> 下一首」，
        免得刚按下重唱又被过场覆盖。 */
    private int advanceToken = 0;
    private KView view;
    private final Handler handler = new Handler();
    private final Runnable nextSong = new Runnable() {
        public void run() { startInterlude(); }
    };
    private PowerManager.WakeLock wake;
    private BroadcastReceiver usbReceiver;

    private final Paint pBg = new Paint();
    private final Paint pCard = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCardOn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGold = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFocus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFade = new Paint();          // 跑马灯两端渐隐专用（只改 shader）
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
    private final Paint pGlow = new Paint(Paint.ANTI_ALIAS_FLAG);     // 进度条末端发光亮点专用
    /* 统一气泡提示（Toast）专用：顶部居中、淡入淡出、自动消失 */
    private final Paint pToastBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pToastEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pToastAccent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pToastTag = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pToastMain = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pToastSub = new Paint(Paint.ANTI_ALIAS_FLAG);
    /* LIVE-BEGIN */
    private final Paint pOpenBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pOpenTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pOpenText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLiveBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLiveEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLiveTag = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLiveMain = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLiveSub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLiveFoot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCelEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCelTag = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCelMain = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCelSub = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCelFoot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFw = new Paint(Paint.ANTI_ALIAS_FLAG);
    /* LIVE-END */
    private final Runnable tick = new Runnable() {
        public void run() {
        /* LIVE-BEGIN */
        checkLiveNotice();
        if (celebrating && System.currentTimeMillis() > celebrateUntil) closeCelebration();
        /* LIVE-END */
            if (view != null) view.invalidate();
            // 换句前后 40fps；平时 20fps 供顶部跑马灯匀速横移（其余画面不常重绘）
            boolean rolling = rollStartAt > 0
                && System.currentTimeMillis() - rollStartAt < ROLL_MS;
            handler.postDelayed(this, (rolling || nearLineChange()
            /* LIVE-BEGIN */
                    || celebrating
            /* LIVE-END */
                    ) ? ROLL_FAST : MARQUEE_MS);
        }
    };

    /** 换句前提前提速采样。空闲时 100ms 才刷一帧，会让「该翻了」晚半拍被发现，
        看起来就是卡一下再猛跳一格；提前进 40fps 才能接上平滑的滚动。 */
    private boolean nearLineChange() {
        if (!playing || songs.isEmpty() || showList) return false;
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

        loadPrefs();
        probeMic();
        resolveAppName();      // 先定台名，启动页第一帧就能显示正确的名字
        loadConf();            // 再叠 U 盘配置（台名 / 欢迎语）

        /* 启动过渡画面：booting=true 时 onDraw 只画「台名 / 启动中」，
           曲库扫描放到后台线程，冷启动不再黑屏干等 2~3 秒。
           延迟 120ms 是让首帧先上屏，否则扫描抢在主线程前面又会看到黑屏。 */
        handler.postDelayed(new Runnable() {
            public void run() { rescan(); }
        }, 120);

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
        pBar.setColor(0xFF34415E);
        pBarOn.setColor(GOLD);
        pGlow.setColor(GOLD);

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

        /* 统一 Toast：深底卡片 + 左侧强调条 + 小标签/大字/小字三层 */
        pToastBg.setColor(CARD);
        pToastEdge.setStyle(Paint.Style.STROKE);
        pToastEdge.setStrokeWidth(4f);
        pToastEdge.setColor(0xFF2A3550);
        pToastAccent.setStyle(Paint.Style.FILL);
        pToastTag.setTypeface(Typeface.DEFAULT_BOLD);
        pToastTag.setTextAlign(Paint.Align.LEFT);
        pToastMain.setTypeface(Typeface.DEFAULT_BOLD);
        pToastMain.setTextAlign(Paint.Align.LEFT);
        pToastSub.setTextAlign(Paint.Align.LEFT);

        /* LIVE-BEGIN */
        pLiveBg.setColor(0xF21B2740);
        pLiveEdge.setStyle(Paint.Style.STROKE);
        pLiveEdge.setStrokeWidth(5f);
        pLiveEdge.setColor(GOLD);
        pLiveTag.setColor(GOLD);
        pLiveTag.setTextAlign(Paint.Align.LEFT);
        pLiveTag.setTypeface(Typeface.DEFAULT_BOLD);
        pLiveMain.setColor(Color.WHITE);
        pLiveMain.setTextAlign(Paint.Align.LEFT);
        pLiveMain.setTypeface(Typeface.DEFAULT_BOLD);
        pLiveSub.setColor(INK_SOFT);
        pLiveSub.setTextAlign(Paint.Align.LEFT);
        pLiveFoot.setColor(INK_DIM);
        pLiveFoot.setTextAlign(Paint.Align.LEFT);
        pOpenBg.setColor(0xFFFFFFFF);
        pOpenTitle.setTypeface(Typeface.DEFAULT_BOLD);
        pOpenTitle.setTextAlign(Paint.Align.LEFT);
        pOpenText.setTextAlign(Paint.Align.LEFT);
        pOpenText.setColor(0xFF152238);
        pCelBg.setColor(0xFF0A1020);
        pCelEdge.setStyle(Paint.Style.STROKE);
        pCelEdge.setStrokeWidth(5f);
        pCelEdge.setColor(GOLD);
        pCelTag.setColor(GOLD);
        pCelTag.setTextAlign(Paint.Align.CENTER);
        pCelTag.setTypeface(Typeface.DEFAULT_BOLD);
        pCelMain.setColor(Color.WHITE);
        pCelMain.setTextAlign(Paint.Align.CENTER);
        pCelMain.setTypeface(Typeface.DEFAULT_BOLD);
        pCelSub.setColor(GOLD);
        pCelSub.setTextAlign(Paint.Align.CENTER);
        pCelSub.setTypeface(Typeface.DEFAULT_BOLD);
        pCelFoot.setColor(INK_SOFT);
        pCelFoot.setTextAlign(Paint.Align.CENTER);
        pFw.setStyle(Paint.Style.FILL);
        /* LIVE-END */
    }

    /* ---------------- 记忆 ---------------- */

    private void loadPrefs() {
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        counts = getSharedPreferences(PREFS_COUNT, MODE_PRIVATE);
        lastSong = prefs.getString("last", "");
        /* LIVE-BEGIN */
        programIdx = prefs.getInt("program_idx", 0);
        /* LIVE-END */
        Log.i(TAG, "读取偏好 上次=" + lastSong);
    }

    private void savePrefs() {
        if (prefs == null) return;
        SharedPreferences.Editor e = prefs.edit();
        e.putString("last", lastSong);
        /* LIVE-BEGIN */
        e.putInt("program_idx", programIdx);
        /* LIVE-END */
        e.commit();
    }

    /** 记一次点歌：只写偏好，不重排当前会话列表，避免切歌顺序跳变。 */
    private void bumpPlay(Song s) {
        if (counts == null) return;
        s.plays = counts.getInt(s.name, 0) + 1;
        counts.edit().putInt(s.name, s.plays).commit();
        Log.i(TAG, "点歌计数 " + s.name + " = " + s.plays);
    }

    /** 唱完整首才结算：自然播到结尾 +1；切歌 / 重唱 / 中途跳到结尾都不算。 */
    private void settleCount(Song s) {
        if (pendingCount == null || !pendingCount.name.equals(s.name)) return;
        Song done = pendingCount;
        pendingCount = null;
        if (countVetoed) {
            countVetoed = false;
            Log.i(TAG, "不算点歌（没完整唱完）: " + done.name);
            return;
        }
        bumpPlay(done);
        if (view != null) view.invalidate();
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
            play(idx, 0, true, false);   // 开机恢复不算一次点歌
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

    /* ---------------- 曲库扫描（三文件规则 + 点歌次数双排序） ---------------- */

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
                // 只认 .mp3：曲库规则就是「歌名.mp3 / 歌名.lrc / 歌名.伴奏.mp3」
                if (f.getName().toLowerCase().endsWith(".mp3")) out.add(f);
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

    private String stemOfName(String n) {
        int d = n.lastIndexOf('.');
        return d > 0 ? n.substring(0, d) : n;
    }

    /** 伴奏只认同目录下的「歌名.伴奏.mp3」，大小写不敏感。 */
    private File findInst(File main) {
        String stem = stemOfName(main.getName());
        File dir = main.getParentFile();
        if (dir == null) return null;
        File exact = new File(dir, stem + INST_SUFFIX + ".mp3");
        if (exact.isFile()) return exact;
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        String want = (stem + INST_SUFFIX + ".mp3").toLowerCase();
        for (File f : fs) {
            if (f.isFile() && f.getName().toLowerCase().equals(want)) return f;
        }
        return null;
    }

    /** 旧版分轨残留一律不进歌单。 */
    private boolean legacyStem(String stem) {
        String s = stem.toLowerCase();
        for (String suf : LEGACY_SUFFIXES) {
            if (s.endsWith(suf.toLowerCase())) return true;
        }
        return false;
    }

    private String audioPath(Song s) {
        return (useInst && s.instPath != null) ? s.instPath : s.path;
    }

    /** 台名先取 Manifest 里的应用名（构建期注入），取不到就用兜底常量。
        U 盘配置能覆盖它，见 loadConf()。 */
    private void resolveAppName() {
        try {
            CharSequence cs = getApplicationInfo().loadLabel(getPackageManager());
            if (cs != null && cs.toString().trim().length() > 0)
                appName = cs.toString().trim();
        } catch (Exception ignored) {
        }
    }

    /** 解析一份 conf（一行一个 key=value），写进 kv；后读的覆盖先读的。 */
    private void parseConf(BufferedReader br, HashMap<String, String> kv) throws IOException {
        String line;
        while ((line = br.readLine()) != null) {
            if (line.length() > 0 && line.charAt(0) == '\uFEFF')
                line = line.substring(1);          // 记事本 UTF-8 BOM
            line = line.trim();
            if (line.length() == 0 || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim().toLowerCase();
            String v = line.substring(eq + 1).trim();
            if (v.length() > 1 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"')
                v = v.substring(1, v.length() - 1).trim();   // 容忍手滑加的引号
            if (k.length() > 0 && v.length() > 0) kv.put(k, v.replace("\\n", "\n"));
        }
    }

    /** 读 APK 内 assets/karaoke.conf（build.sh 生成的构建期默认值）。没有就跳过。 */
    private void readConfAsset(HashMap<String, String> kv) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                getAssets().open("karaoke.conf"), "UTF-8"), 512);
            parseConf(br, kv);
        } catch (Exception ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Exception ignored) {}
        }
    }

    /** 配置 = assets 默认值 + U 盘根目录 karaoke.conf 覆盖（只在第一个找到的文件里读）。 */
    private HashMap<String, String> readConf() {
        HashMap<String, String> kv = new HashMap<String, String>();
        readConfAsset(kv);
        for (String r : ROOTS) {
            File f = new File(r, "karaoke.conf");
            if (!f.isFile()) continue;
            BufferedReader br = null;
            try {
                br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), "UTF-8"), 512);
                parseConf(br, kv);
            } catch (Exception ignored) {
            } finally {
                try { if (br != null) br.close(); } catch (Exception ignored) {}
            }
            break;
        }
        return kv;
    }

    /** 应用配置：台名 / 欢迎语；定制版另有演出流程与提醒配置。 */
    private void loadConf() {
        HashMap<String, String> kv = readConf();
        String name = kv.get("app_name");
        if (name != null) appName = name;
        String welcome = kv.get("welcome");
        welcomeText = welcome != null ? welcome : ("欢迎来到 " + appName + "，");
        Log.i(TAG, "配置: 台名=" + appName + "，欢迎语=" + welcomeText);

        /* LIVE-BEGIN */
        String secs = kv.get("opening_secs");
        if (secs != null) {
            try {
                int n = Integer.parseInt(secs.trim());
                if (n > 0 && n <= 300) openingSecs = n;
            } catch (Exception ignored) { }
        }
        String hold = kv.get("opening_hold");
        if (hold != null) {
            String v = hold.trim().toLowerCase();
            openingHold = v.equals("1") || v.equals("on") || v.equals("true") || v.equals("yes");
        }
        noticeStart = confText(kv, "live_start_text", noticeStart);
        noticeBreak = confText(kv, "live_break_text", noticeBreak);
        noticeWarn = confText(kv, "live_warn_text", noticeWarn);
        noticeEnd = confText(kv, "live_end_text", noticeEnd);

        /* 下播提醒弹出位：第几个节目唱完后弹（1 起；0 = 整场演完才弹，默认）。
           超出节目单范围 → 回退 0（整场演完）。 */
        endNoticeProg = 0;
        String ep = kv.get("live_end_program");
        if (ep != null) {
            try {
                int n = Integer.parseInt(ep.trim());
                if (n > 0) endNoticeProg = n;
            } catch (Exception ignored) { }
        }

        liveEnabled = false;
        liveStartMin = liveEndMin = -1;
        liveStartTxt = liveEndTxt = "";
        String ls = kv.get("live_start"), le = kv.get("live_end");
        if (ls != null && le != null) {
            int a = parseHhMm(ls), b = parseHhMm(le);
            if (a >= 0 && b >= 0 && b > a) {
                liveEnabled = true;
                liveStartMin = a; liveEndMin = b;
                liveStartTxt = ls.trim(); liveEndTxt = le.trim();
            } else {
                Log.w(TAG, "开播提醒未启用：live_start/live_end 须为 HH:mm 且结束晚于开始（"
                        + ls + " / " + le + "）");
            }
        }
        lastLiveMinute = -1;
        loadOpenings();
        loadProgram();
        endNoticeShown = false;
        Log.i(TAG, "直播配置: 台词=" + openings.size() + " 条，节目单=" + program.size()
                + " 个，提醒=" + (liveEnabled
                    ? liveStartTxt + "-" + liveEndTxt
                      + "（下播提醒："
                      + (endNoticeProg > 0 ? "第 " + endNoticeProg + " 个节目唱完后弹"
                                          : "整场演完后弹") + "）" : "未启用"));
        /* LIVE-END */
    }
    /* LIVE-BEGIN */
    /** 严格 HH:mm：恰好 5 个字符、第 3 位半角冒号、其余半角数字，小时 00-23、分钟 00-59。 */
    private static int parseHhMm(String raw) {
        if (raw == null) return -1;
        String s = raw.trim();
        if (s.length() != 5 || s.charAt(2) != ':') return -1;
        for (int i = 0; i < 5; i++) {
            if (i == 2) continue;
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return -1;
        }
        int h = (s.charAt(0) - '0') * 10 + (s.charAt(1) - '0');
        int m = (s.charAt(3) - '0') * 10 + (s.charAt(4) - '0');
        if (h > 23 || m > 59) return -1;
        return h * 60 + m;
    }

    private static String confText(HashMap<String, String> kv, String key, String fallback) {
        String v = kv.get(key);
        return (v != null && v.trim().length() > 0) ? v.trim() : fallback;
    }

    /** 每分钟判一次：开播 / 下播提醒；只在直播模式显示，每天各一次。
        开播：到 live_start 就定位第 1 个节目、停下等 OK（这一场已开演过就不打断）；
        下播：已过 live_end **且** 节目单完整走完一场 —— 不靠时间到点，靠节目走完。 */
    private void checkLiveNotice() {
        if (!liveMode || !liveEnabled || prefs == null) return;
        long minute = System.currentTimeMillis() / 60000L;
        if (minute == lastLiveMinute) return;
        lastLiveMinute = minute;
        Calendar cal = Calendar.getInstance();
        int now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        String day = cal.get(Calendar.YEAR) + "-" + (cal.get(Calendar.MONTH) + 1)
                + "-" + cal.get(Calendar.DAY_OF_MONTH);
        SharedPreferences.Editor e = prefs.edit();
        /* 下播提醒（整场演完版）：已过下播时间 + 节目单完整走完一场。
           配置了 live_end_program（位次版）时此路径跳过，避免同场弹两次。 */
        if (endNoticeProg <= 0
                && now >= liveEndMin && programDone()
                && !day.equals(prefs.getString("live_end_day", ""))) {
            e.putString("live_end_day", day).commit();
            startCelebration();
            Log.i(TAG, "下播提醒：节目单已演完且已过 " + liveEndTxt);
            return;
        }
        /* 开播提醒：到点就位（Toast 停留 15 秒，信息要紧） */
        if (now >= liveStartMin && now < liveEndMin
                && !day.equals(prefs.getString("live_start_day", ""))) {
            e.putString("live_start_day", day).commit();
            showToast("开播提醒", noticeStart, liveStartTxt + " - " + liveEndTxt,
                      false, TOAST_HOLD_LONG_MS);
            if (showStarted()) {
                Log.i(TAG, "开播提醒：" + liveStartTxt + "-" + liveEndTxt + "（本场已在进行，不打断）");
            } else {
                armFirstProgram();
                Log.i(TAG, "开播提醒：" + liveStartTxt + "-" + liveEndTxt
                        + "，已定位第 1 个节目，等 OK 开演");
            }
        }
    }

    /** 上一场是否已经完整走完（program_done=1）。落 prefs：关机重启也记得。 */
    private boolean programDone() {
        return prefs != null && prefs.getInt(P_DONE, 0) == 1;
    }

    /** 这一场是否已经开演过（有人按过 OK 开始第一个节目）。 */
    private boolean showStarted() {
        return prefs != null && prefs.getLong(P_STARTED, 0L) > 0L;
    }

    /** 直播就绪：定位到第 1 个节目，停下不播，等主播按 OK 开演。 */
    private void armFirstProgram() {
        if (program.isEmpty()) return;
        programIdx = 0;
        prefs.edit().putInt("program_idx", 0).commit();
        programEnded = false;
        programWaiting = false;
        liveArmed = true;
        stopPlayer();
        playing = false;
        showList = false;
        listCursor = 0;
        syncLibTop();
        programEnterAt = System.currentTimeMillis();
        Log.i(TAG, "直播就绪：定位到第 1 个节目，等 OK 开演");
        if (view != null) view.invalidate();
    }

    /** 就绪态下「切节目」：挪到下一个节目，但仍然不播，等 OK。 */
    private void armNextProgram() {
        if (program.isEmpty()) return;
        int nxt = nextPlayable(programIdx);
        if (nxt == programIdx) { showBanner("已是最后一个节目"); return; }
        programIdx = nxt;
        prefs.edit().putInt("program_idx", nxt).commit();
        listCursor = nxt;
        syncLibTop();
        /* 换了节目，报幕词从这一刻重新计时 */
        programEnterAt = System.currentTimeMillis();
        showBanner("就绪 · 第 " + (nxt + 1) + " / " + program.size() + " 个节目");
        Log.i(TAG, "就绪态切节目 -> 第 " + (nxt + 1) + " 个");
        if (view != null) view.invalidate();
    }

    /** 中场休息：走到 floor(N/2)+1 个节目时弹一次（N=12 → 第 7 个），不中断推进。 */
    private void checkBreakNotice(int idx) {
        if (program.isEmpty() || prefs == null) return;
        if (idx != program.size() / 2) return;
        Calendar cal = Calendar.getInstance();
        String day = cal.get(Calendar.YEAR) + "-" + (cal.get(Calendar.MONTH) + 1)
                + "-" + cal.get(Calendar.DAY_OF_MONTH);
        if (day.equals(prefs.getString("live_break_day", ""))) return;
        SharedPreferences.Editor e = prefs.edit();
        e.putString("live_break_day", day);
        e.putString("live_start_day", day);       // 中场已过，不用再补开播提醒
        e.commit();
        showToast("中场休息", noticeBreak, "歇一歇，喝口水", false, TOAST_HOLD_MS);
        Log.i(TAG, "中场休息提醒：第 " + (idx + 1) + " / " + program.size() + " 个节目");
    }

    /** 下播预告：倒数第 2 个节目开始时弹一次（不再看 live_end 时间）。 */
    private void checkWarnNotice(int idx) {
        if (program.isEmpty() || prefs == null) return;
        if (idx != program.size() - 2) return;
        Calendar cal = Calendar.getInstance();
        String day = cal.get(Calendar.YEAR) + "-" + (cal.get(Calendar.MONTH) + 1)
                + "-" + cal.get(Calendar.DAY_OF_MONTH);
        if (day.equals(prefs.getString("live_warn_day", ""))) return;
        SharedPreferences.Editor e = prefs.edit();
        e.putString("live_warn_day", day).commit();
        int left = program.size() - idx;             // 还剩几个（含当前这个）
        showToast("下播预告", String.format(noticeWarn, left),
                  "最后一个节目马上到", false, TOAST_HOLD_LONG_MS);
        Log.i(TAG, "下播预告：第 " + (idx + 1) + "/" + program.size()
                + " 个节目开始，还有 " + left + " 个节目");
    }

    /** 位次版下播提醒：第 endNoticeProg 个节目唱完后弹庆祝层并停住（下一首不播）。
        只在直播模式、配置生效且本场未弹过时触发；任意键关层后按 OK 继续。
        每场一次（开演时复位标记）。 */
    private void checkEndNoticeAt(int idx) {
        if (!liveMode || endNoticeProg <= 0 || endNoticeShown) return;
        if (idx != endNoticeProg - 1) return;         // 配置「第几个」→ 数组下标
        endNoticeShown = true;
        endNoticeHold = true;
        stopPlayer(); playing = false;               // 当前这首已唱完，停住、不播下一首
        startCelebration();
        Log.i(TAG, "下播提醒（位次版）：第 " + (idx + 1) + " 个节目唱完，停住等 OK 继续");
    }


    /* ---------- 下播庆祝：全屏 + 礼花 ---------- */

    /** 开始下播庆祝：全屏礼花，15 秒后自动收起；任意键可提前收。
        不停播不拦截推进，庆祝层与下一个节目并行播放。 */
    private void startCelebration() {
        celebrating = true;
        celebrateAt = System.currentTimeMillis();
        celebrateUntil = celebrateAt + CELEBRATE_MS;
        initFireworks();
        Log.i(TAG, "下播庆祝：全屏礼花，15 秒后自动收起（不影响继续演出）");
        if (view != null) view.invalidate();
    }

    private void closeCelebration() {
        if (!celebrating) return;
        celebrating = false;
        Log.i(TAG, "下播庆祝已收起");
        if (view != null) view.invalidate();
    }

    /** 预生成礼花：16 次爆炸、每次 22 颗粒子。固定种子，每次放出来一模一样。
        爆炸时刻铺满整个庆祝时长（12.5 秒），不会放完就没了。 */
    private void initFireworks() {
        java.util.Random rnd = new java.util.Random(20261114L);
        float step = FW_SPAN_MS / (float) FW_BURSTS;
        for (int b = 0; b < FW_BURSTS; b++) {
            fwT[b] = 120 + b * step + rnd.nextInt(180);
            fwX[b] = 0.10f + 0.80f * rnd.nextFloat();
            fwY[b] = 0.13f + 0.22f * rnd.nextFloat();
            fwC[b] = FW_PALETTE[b % FW_PALETTE.length];
            for (int j = 0; j < FW_PARTS; j++) {
                double a = Math.PI * 2.0 * j / FW_PARTS + (rnd.nextFloat() - 0.5) * 0.34;
                double sp = 0.085 + 0.185 * rnd.nextFloat();
                fwVX[b][j] = (float) (Math.cos(a) * sp);
                fwVY[b][j] = (float) (Math.sin(a) * sp);
                fwR[j] = 0.0030f + 0.0026f * rnd.nextFloat();
            }
        }
        fwReady = true;
    }

    /* ---------- 报幕词文件 ---------- */

    /** 台词文件的键：去掉扩展名、去首尾空白、统一小写。 */
    private static String openingKey(String name) {
        if (name == null) return "";
        String s = name.trim();
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        return s.trim().toLowerCase();
    }

    /** 容忍带序号的歌名：再比一次「序号-」之后的纯名。 */
    private static String openingLooseKey(String key) {
        int i = 0, n = key.length();
        while (i < n) {
            char ch = key.charAt(i);
            if (Character.isDigit(ch) || ch == '-' || ch == '_' || ch == ' ' || ch == '.') i++;
            else break;
        }
        return i > 0 && i < n ? key.substring(i) : key;
    }

    /** 读 U 盘根目录台词文件：## 名字 + 下面多行正文；# 单行是注释。 */
    private void loadOpenings() {
        openings.clear();
        for (String r : ROOTS) {
            File f = new File(r, OPENINGS_FILE);
            if (!f.isFile()) continue;
            BufferedReader br = null;
            try {
                br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), "UTF-8"), 1024);
                String line, key = null;
                StringBuilder body = new StringBuilder();
                while ((line = br.readLine()) != null) {
                    if (line.length() > 0 && line.charAt(0) == '\uFEFF')
                        line = line.substring(1);
                    String t = line.trim();
                    if (t.startsWith("##")) {
                        putOpening(key, body);
                        key = openingKey(t.substring(2));
                        body.setLength(0);
                    } else if (t.startsWith("#")) {
                        continue;
                    } else if (key != null && t.length() > 0) {
                        if (body.length() > 0) body.append('\n');
                        body.append(t);
                    }
                }
                putOpening(key, body);
            } catch (Exception ignored) { }
            finally { try { if (br != null) br.close(); } catch (Exception ignored) {} }
            break;
        }
    }

    private void putOpening(String key, StringBuilder body) {
        if (key == null || key.length() == 0 || body.length() == 0) return;
        String text = body.toString();
        openings.put(key, text);
        String loose = openingLooseKey(key);
        if (!loose.equals(key) && !openings.containsKey(loose)) openings.put(loose, text);
    }

    /** 按名字取报幕词；没有就返回 null。 */
    private String openingForName(String name) {
        if (name == null || openings.isEmpty()) return null;
        String k = openingKey(name);
        String v = openings.get(k);
        if (v == null) v = openings.get(openingLooseKey(k));
        return v;
    }

    /** 日志用：报幕词命中情况（命中则截前 12 字，便于肉眼核对对没对上台词）。 */
    private String openingInfo(String name) {
        String t = openingForName(name);
        if (t == null || t.length() == 0) return "报幕词：缺";
        String head = t.length() > 12 ? t.substring(0, 12) + "…" : t;
        return "报幕词：有「" + head + "」";
    }

    /* ---------- 节目单 ---------- */

    /** 读 U 盘根目录 karaoke_program.txt：演唱|磁盘歌名 / 弹奏|节目名，# 注释，严格行序。
        这里只解析文本；曲库扫完后由 applySongs 调用 rebindProgram() 做文件映射。 */
    private void loadProgram() {
        program.clear();
        programLoaded = true;
        for (String r : ROOTS) {
            File f = new File(r, PROGRAM_FILE);
            if (!f.isFile()) continue;
            BufferedReader br = null;
            try {
                br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), "UTF-8"), 1024);
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.length() > 0 && line.charAt(0) == '\uFEFF')
                        line = line.substring(1);
                    String t = line.trim();
                    if (t.length() == 0 || t.startsWith("#")) continue;
                    int bar = t.indexOf('|');
                    if (bar <= 0) continue;
                    String type = t.substring(0, bar).trim();
                    String key = t.substring(bar + 1).trim();
                    if (key.length() == 0) continue;
                    if (!type.equals("演唱") && !type.equals("弹奏")) type = "演唱";
                    ProgramItem it = new ProgramItem();
                    it.type = type;
                    it.key = key;
                    program.add(it);
                }
            } catch (Exception e) {
                Log.w(TAG, "节目单读取失败: " + e);
            } finally { try { if (br != null) br.close(); } catch (Exception ignored) {} }
            break;
        }
    }

    /** 曲库可能在 U 盘子目录：从已扫描歌曲的父目录里找同名文件。 */
    private boolean programFileExists(String name) {
        HashSet<String> dirs = new HashSet<String>();
        for (String r : ROOTS) dirs.add(r);
        for (Song s : songs) {
            File p = new File(s.path).getParentFile();
            if (p != null) dirs.add(p.getAbsolutePath());
        }
        for (String d : dirs) if (new File(d, name).isFile()) return true;
        return false;
    }

    /** 三文件都查、缺哪个提示哪个；只有 mp3 与 lrc 同时缺才跳过。
        弹奏类天然无音频文件，不参与检查。只缺伴奏照常播原唱。 */
    private void rebindProgram() {
        programSkipped = 0;
        for (ProgramItem it : program) {
            it.songIdx = -1; it.miss = ""; it.skip = false;
            if (!it.type.equals("演唱")) continue;
            for (int i = 0; i < songs.size(); i++) {
                if (songs.get(i).name.equals(it.key)) { it.songIdx = i; break; }
            }
            boolean mp3 = it.songIdx >= 0 || programFileExists(it.key + ".mp3");
            boolean lrc = it.songIdx >= 0 ? songs.get(it.songIdx).hasLrc
                                          : programFileExists(it.key + ".lrc");
            boolean inst = it.songIdx >= 0 ? songs.get(it.songIdx).instPath != null
                                           : programFileExists(it.key + ".伴奏.mp3");
            if (!mp3) it.miss = "缺歌曲";
            if (!lrc) it.miss += (it.miss.length() > 0 ? " / " : "") + "缺歌词";
            if (!inst) it.miss += (it.miss.length() > 0 ? " / " : "") + "缺伴奏";
            if (!mp3 && !lrc) it.skip = true;
            if (it.miss.length() > 0)
                Log.i(TAG, "节目单 " + it.key + "：" + it.miss + (it.skip ? "，跳过" : ""));
            if (it.skip) programSkipped++;
        }
        Log.i(TAG, "节目单 " + program.size() + " 个节目（演唱 "
                + countType("演唱") + " / 弹奏 " + countType("弹奏") + "），跳过 "
                + programSkipped + " 个");
    }

    private int countType(String type) {
        int n = 0;
        for (ProgramItem it : program) if (it.type.equals(type)) n++;
        return n;
    }

    private int nextPlayable(int from) {
        int n = program.size();
        if (n == 0) return from;
        for (int k = 1; k <= n; k++) {
            int j = (from + k) % n;
            if (!program.get(j).skip) return j;
        }
        return from;
    }

    /** 进入第 idx 个节目：演唱类开播（不计点歌次数），弹奏类停住等 OK。 */
    private void enterProgram(int idx) {
        if (program.isEmpty()) { showBanner("U 盘里没有节目单"); return; }
        if (idx < 0 || idx >= program.size()) idx = 0;
        programIdx = idx;
        prefs.edit().putInt("program_idx", programIdx).commit();
        ProgramItem it = program.get(programIdx);
        liveArmed = false;
        /* 新一场开演：位次版下播提醒的「本场已弹」标记复位（从第 1 个节目重新计数）。 */
        if (programIdx == 0 && !showStarted()) {
            endNoticeShown = false;
            endNoticeHold = false;
        }
        checkBreakNotice(programIdx);
        checkWarnNotice(programIdx);
        showList = false;
        listCursor = programIdx;
        syncLibTop();
        programEnterAt = System.currentTimeMillis();
        if (it.skip) {
            int next = nextPlayable(programIdx);
            if (next == programIdx) { showBanner(it.key + " " + it.miss + "，无可用节目"); return; }
            Log.i(TAG, "跳过节目 " + it.key + "：" + it.miss);
            showBanner(it.key + " " + it.miss + "，已跳过");
            enterProgram(next);
            return;
        }
        /* 本轮开演：记开演时刻；上一场已演完的话清掉「已演完」标记 */
        SharedPreferences.Editor pe = prefs.edit();
        if (programDone()) {
            pe.putInt(P_DONE, 0);
            Log.i(TAG, "开始新的一场直播");
        }
        pe.putLong(P_STARTED, System.currentTimeMillis()).commit();
        if (it.type.equals("弹奏") || it.songIdx < 0) {
            stopPlayer();
            playing = false;
            programWaiting = true;
            programEnded = false;
            Log.i(TAG, "节目 " + (programIdx + 1) + "/" + program.size() + " ["
                    + it.type + "] " + it.key
                    + (it.miss.length() > 0 ? "（" + it.miss + "）" : "")
                    + "，" + openingInfo(it.key) + "，等待 OK");
            if (view != null) view.invalidate();
            return;
        }
        programWaiting = false;
        programEnded = false;
        play(it.songIdx, 0, true, false);       // 固定节目单不计点歌次数
        Log.i(TAG, "节目 " + (programIdx + 1) + "/" + program.size() + " [演唱] " + it.key
                + (it.miss.length() > 0 ? "（" + it.miss + "）" : "")
                + "，" + openingInfo(it.key));
    }

    /** 推进到下一个节目；到末尾停住，不自动循环重播整场。 */
    private void advanceProgram(boolean withInterlude) {
        if (program.isEmpty()) return;
        /* 位次版下播提醒：第 endNoticeProg 个节目唱完后弹，并停住不推进。
           庆祝层任意键关、再按 OK 才继续（见 onKeyDown 的 endNoticeHold 分支）。 */
        checkEndNoticeAt(programIdx);
        if (endNoticeHold) {                 // 本次触发了位次版下播提醒：停住，不播下一首
            programWaiting = false;
            if (view != null) view.invalidate();
            return;
        }
        if (programIdx + 1 >= program.size()) {
            stopPlayer(); playing = false;
            programWaiting = false;
            programEnded = true;
            liveArmed = false;
            /* 整场走完：落「已演完」标记（关机重启也记得），本轮开演时刻清零 */
            prefs.edit().putInt(P_DONE, 1).putLong(P_STARTED, 0L).commit();
            showBanner("节目单已演完，按 OK 从头开始");
            Log.i(TAG, "节目单演完，停在第 " + program.size() + " 个节目");
            if (view != null) view.invalidate();
            return;
        }
        final int nxt = nextPlayable(programIdx);
        if (nxt == programIdx) return;
        if (!withInterlude) { enterProgram(nxt); return; }
        interludeName = program.get(nxt).key;
        interludeUntil = System.currentTimeMillis() + 2000;
        pendingIdx = nxt;
        showList = false;
        stopPlayer(); playing = false;
        programWaiting = false;
        Log.i(TAG, "过场 -> " + interludeName);
        final int tok = ++advanceToken;      // 用户中途操作会让它失效
        handler.postDelayed(new Runnable() {
            public void run() {
                if (tok != advanceToken) return;   // 已被用户操作取消
                interludeUntil = 0;
                if (usbGone || !liveMode) return;
                enterProgram(nxt);
            }
        }, 2000);
    }

    /** 双击 # 切换普通 / 直播模式。开机默认普通；进度从 prefs 续演。 */
    private void toggleLiveMode() {
        if (liveMode) { exitLiveMode(); return; }
        enterLiveMode();
    }

    /** 进入直播模式：定位到上次节目（上一场已演完则回第 1 个），停下等 OK 开演。 */
    private void enterLiveMode() {
        if (!programLoaded) loadProgram();
        if (program.isEmpty()) { showBanner("U 盘里没有 karaoke_program.txt"); return; }
        liveMode = true;
        rebindProgram();
        programIdx = prefs.getInt("program_idx", 0);
        if (programIdx < 0 || programIdx >= program.size()) programIdx = 0;
        if (program.get(programIdx).skip) {
            int nx = nextPlayable(programIdx);
            if (nx != programIdx) programIdx = nx;
        }
        boolean done = programDone();
        if (done) {
            /* 上一场完整走完了一遍：回第 1 个节目，进入下一个直播就绪态 */
            programIdx = 0;
            prefs.edit().putInt("program_idx", 0).commit();
        }
        programEnded = false;
        programWaiting = false;
        liveArmed = true;                 // 就绪：停下等 OK，不自动开演
        endNoticeHold = false;
        stopPlayer();
        playing = false;
        showList = false;
        listCursor = programIdx;
        syncLibTop();
        programEnterAt = System.currentTimeMillis();
        showBanner(done ? "上一场已演完 · 回到第 1 个节目，就绪"
                        : "直播模式 · 第 " + (programIdx + 1) + " / " + program.size() + " 个 · 就绪");
        Log.i(TAG, "切换 -> 直播模式（" + program.size() + " 个节目，"
                + (done ? "上一场已演完，回到第 1 个" : "续演第 " + (programIdx + 1) + " 个")
                + "，就绪等待 OK）");
        if (view != null) view.invalidate();
    }

    /** 切回普通模式：接着唱手边的歌。 */
    private void exitLiveMode() {
        liveMode = false;
        programWaiting = false;
        programEnded = false;
        liveArmed = false;
        endNoticeHold = false;
        showBanner("普通模式");
        Log.i(TAG, "切换 -> 普通模式");
        if (!songs.isEmpty()) play(cur, 0, true, false);
        if (view != null) view.invalidate();
    }

    /** 直播模式下展开的是严格顺序的节目单：↑↓ 选、OK 直接进入。 */
    private boolean onProgramKey(int key) {
        int n = program.size();
        switch (key) {
            case KeyEvent.KEYCODE_3:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BACK:
                showList = false; view.invalidate(); return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (n > 0) { listCursor = (listCursor - 1 + n) % n; syncLibTop(); }
                Log.i(TAG, "节目单光标 -> 第 " + (listCursor + 1) + " 个");
                view.invalidate(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (n > 0) { listCursor = (listCursor + 1) % n; syncLibTop(); }
                Log.i(TAG, "节目单光标 -> 第 " + (listCursor + 1) + " 个");
                view.invalidate(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (n > 0) { showList = false; enterProgram(listCursor); }
                return true;
            case KeyEvent.KEYCODE_1:
            case KeyEvent.KEYCODE_2:
                showList = false; act(key == KeyEvent.KEYCODE_1 ? 1 : 2); return true;
            case KeyEvent.KEYCODE_0:
                showList = false; act(0); return true;
            default:
                return true;
        }
    }
    /* LIVE-END */
    /** 后台扫曲库：文件遍历与 LRC 解析都放到工作线程，扫完回主线程一次性替换列表。
        扫描期间界面照常重绘（启动页 / 旧画面），不会卡住两秒。
        返回后直接替换的是整个 songs 引用，UI 线程读到的要么是旧列表要么是新列表。 */
    private void rescan() {
        if (scanning) return;                    // 上一轮还没扫完就别再起线程
        loadConf();        // 配置解析放主线程：后台线程清空列表会和 onDraw 遍历撞车
        scanning = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    final ArrayList<Song> fresh = scanSongs();
                    handler.post(new Runnable() {
                        public void run() { applySongs(fresh); }
                    });
                } finally {
                    scanning = false;
                }
            }
        }, "rescan").start();
    }

    /** 只做 IO 与解析，不碰 UI 状态（cur / listCursor / songs 一律留给 applySongs）。 */
    private ArrayList<Song> scanSongs() {
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

        ArrayList<Song> fresh = new ArrayList<Song>();
        String foundDir = "";
        int withInst = 0;
        for (File f : files) {
            String stem = stemOfName(f.getName());
            if (stem.toLowerCase().endsWith(INST_SUFFIX.toLowerCase())) continue; // 伴奏不算主歌
            if (legacyStem(stem)) continue;                                       // 旧分轨不算主歌
            if (foundDir.length() == 0 && f.getParentFile() != null)
                foundDir = f.getParentFile().getAbsolutePath();
            Song s = new Song();
            s.path = f.getAbsolutePath();
            s.name = stem;
            File inst = findInst(f);
            s.instPath = inst == null ? null : inst.getAbsolutePath();
            if (s.instPath != null) withInst++;
            s.plays = counts == null ? 0 : counts.getInt(s.name, 0);
            File lrc = findLrc(f);
            if (lrc != null) {
                s.lines = loadLrc(lrc);
                s.hasLrc = !s.lines.isEmpty();
            }
            fresh.add(s);
        }
        scannedWith = withInst;
        scannedHint = foundDir.length() > 0 ? ("曲库: " + foundDir)
                   : ("已扫描 " + roots.size() + " 个位置，未找到歌曲");
        return fresh;
    }

    /** 主线程应用扫描结果：排序、替换、修正游标；首扫完成才关掉启动页并接着上次唱。 */
    private void applySongs(ArrayList<Song> fresh) {
        Collections.sort(fresh, SONG_ORDER);   // 点歌次数降序 -> 文件名升序
        songs = fresh;
        /* LIVE-BEGIN */
        if (liveMode) rebindProgram();
        /* LIVE-END */
        if (cur >= songs.size()) cur = 0;
        if (listCursor >= songs.size()) listCursor = 0;
        syncLibTop();
        rootHint = scannedHint;
        int anchors = 0;
        for (Song s : songs) for (Line l : s.lines) if (l.endMs > 0) anchors++;
        Log.i(TAG, "扫描到 " + songs.size() + " 首歌（" + scannedWith
                + " 首带伴奏），空行锚点 " + anchors + " 处，已按点歌次数排序；" + rootHint);
        if (booting) {
            if (songs.isEmpty()) {
                /* 一首都没扫到，多半是 U 盘没插或还没挂载：留在启动页提示插盘，
                   2 秒后自动重扫，插好就自己进主界面，不用按任何键。 */
                if (!waitingUsb) Log.i(TAG, "未找到歌曲，停在启动页等待 U 盘");
                waitingUsb = true;
                handler.postDelayed(retryScan, USB_WAIT_RETRY_MS);
            } else {
                booting = false;                // 先关启动页，再让它 invalidate
                waitingUsb = false;
                restoreLast();
            }
        }
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
            if (text.length() == 0) {
                /* 纯空行不是歌词，但它是上一句的「唱完」锚点：
                   记成上一行的 endMs，扫字到这里就扫满，不再一路拖到下一句行首。
                   连续多个空行只认第一个；文件开头的空行没有上一行，忽略。 */
                if (!lines.isEmpty()) {
                    Line prev = lines.get(lines.size() - 1);
                    if (prev.endMs <= 0) prev.endMs = Math.max(0L, times.get(0) + offset);
                }
                continue;
            }
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

    /** 没有队列：歌单顺序里，当前首的下一首就是预告。 */
    private int peekNext() {
        return songs.isEmpty() ? 0 : (cur + 1) % songs.size();
    }

    private void syncLibTop() {
        /* LIVE-BEGIN */
        if (liveMode) {
            int rows = 6;
            if (listCursor < libTop) libTop = listCursor;
            if (listCursor >= libTop + rows) libTop = listCursor - rows + 1;
            int maxTop = Math.max(0, program.size() - rows);
            if (libTop > maxTop) libTop = maxTop;
            return;
        }
        /* LIVE-END */
        int rows = 6;
        if (listCursor < libTop) libTop = listCursor;
        if (listCursor >= libTop + rows) libTop = listCursor - rows + 1;
        int maxTop = Math.max(0, songs.size() - rows);
        if (libTop > maxTop) libTop = maxTop;
    }

    /** 统一气泡提示（Toast）：顶部居中淡入 → 停留 → 淡出（苹果式，无位移）。
        新的直接替换旧的，同一时刻只显示一条。停留 5 秒，错误态红字。 */
    private void showToast(String tag, String main, String sub, boolean err, long hold) {
        toastOn = true;
        toastTag = (tag == null) ? "" : tag;
        toastMain = (main == null) ? "" : main;
        toastSub = (sub == null) ? "" : sub;
        toastErr = err;
        toastStart = System.currentTimeMillis();
        toastHold = hold;
        if (view != null) view.invalidate();
    }

    /** 旧入口：单行提示走 Toast，5 秒自动消失（23 处调用点一行不用改）。 */
    private void showBanner(String s) {
        showToast("", s, "", false, TOAST_HOLD_MS);
    }

    /** 错误提示：顶栏红字照旧，同时弹一条红字 Toast。 */
    private void toastError(String m) {
        err = (m == null) ? "" : m;
        showToast("", m, "", true, TOAST_HOLD_MS);
    }

    private void setActive(int n) {
        activeBtn = n;
        focusSwitchAt = System.currentTimeMillis();
    }

    /** 数字键直达 / 鼠标点按钮：全部动作都印在同一屏上，不换页、不记层级。 */
    private void act(int n) {
        /* LIVE-BEGIN */
        if (liveMode) {
            if (trackDisabled()) {
                if (n == 1) { showBanner(trackBlockedMsg()); return; }
                if (n == 2) { showBanner(liveArmed ? trackBlockedMsg()
                                                  : "弹奏节目没有音频，按 OK 继续"); return; }
            }
            /* 就绪态下「切节目」只是挪位置，不开演 */
            if (liveArmed && n == 0) { armNextProgram(); return; }
        }
        /* LIVE-END */
        switch (n) {
            case 1: setActive(0); switchTrack(); break;
            case 2: setActive(1); replaySong(); break;
            case 3: setActive(2); toggleList(); break;
            case 0: setActive(3); skip(); break;
        }
    }

    private void toggleList() {
        /* LIVE-BEGIN */
        if (liveMode && program.isEmpty()) loadProgram();
        /* LIVE-END */
        if (songs.isEmpty()) { rescan(); return; }
        showList = !showList;
        if (showList) {
            listCursor = cur;
            /* LIVE-BEGIN */
            if (liveMode) listCursor = programIdx;
            /* LIVE-END */
            syncLibTop();
        }
        /* LIVE-BEGIN */
        Log.i(TAG, (showList ? "展开" : "收起") + (liveMode ? "节目单" : "歌单")
                + "，第 " + (listCursor + 1) + " 项");
        /* LIVE-END */
        if (view != null) view.invalidate();
    }

    /** 唱完自动切：先给 2 秒大字过场，再按升序播下一首。 */
    private void advance(boolean withInterlude) {
        /* LIVE-BEGIN */
        if (liveMode) { advanceProgram(withInterlude); return; }
        /* LIVE-END */
        if (songs.isEmpty() || usbGone) return;
        final int n = (cur + 1) % songs.size();
        if (!withInterlude) { play(n); return; }
        interludeName = songName(n);
        interludeUntil = System.currentTimeMillis() + 2000;
        pendingIdx = n;
        showList = false;
        stopPlayer();
        playing = false;
        Log.i(TAG, "过场 -> " + interludeName);
        final int tok = ++advanceToken;      // 用户中途操作会让它失效
        handler.postDelayed(new Runnable() {
            public void run() {
                if (tok != advanceToken) return;   // 已被用户操作取消
                interludeUntil = 0;
                if (usbGone) return;
                play(pendingIdx);
            }
        }, 2000);
    }

    private void startInterlude() { advance(true); }

    private void skip() {
        /* LIVE-BEGIN */
        if (liveMode) {
            if (liveArmed) { armNextProgram(); return; }
            if (programEnded) enterProgram(0); else advanceProgram(false);
            return;
        }
        /* LIVE-END */
        if (songs.isEmpty() || usbGone) return;
        showBanner("已切歌 → " + songName(peekNext()));
        advance(false);
    }

    /** 重唱＝整首从头，不动歌单位置。 */
    private void replaySong() {
        if (songs.isEmpty()) return;
        play(cur);
        showBanner("从头再唱一遍");
        Log.i(TAG, "重唱整首 " + songName(cur));
    }

    private void onUsbRemoved() {
        if (usbGone) return;
        usbGone = true;
        stopPlayer();
        playing = false;
        showList = false;
        interludeUntil = 0;
        pendingCount = null;          // 没唱完就拔盘，不结算
        countVetoed = false;
        err = "";
        Log.i(TAG, "U 盘拔出：停止播放");
        if (view != null) view.invalidate();
    }

    private void onUsbMounted() {
        boolean was = usbGone;
        usbGone = false;
        rescan();
        if (was && !songs.isEmpty()) {
            /* LIVE-BEGIN */
            if (liveMode) {
                showBanner("U 盘插回，继续节目单");
                enterProgram(programIdx);
                return;
            }
            /* LIVE-END */
            restoreLast();
            showBanner("U 盘已插回，接着上次唱");
            Log.i(TAG, "U 盘插回：已自动恢复播放");
        }
    }

    /* ---------------- 播放控制 ---------------- */

    private void stopPlayer() {
        if (player != null) {
            // 先摘掉完成回调：老电视的 MediaPlayer 在 stop/release 时会补发一次
            // onCompletion，不摘掉会被当成「这首唱完了」，自动跳下一首（直播模式会跳节目）。
            try { player.setOnCompletionListener(null); } catch (Exception e) { }
            try { player.setOnErrorListener(null); } catch (Exception e) { }
            try { player.stop(); } catch (Exception e) { }
            try { player.release(); } catch (Exception e) { }
            player = null;
        }
        playerGen++;                 // 作废所有旧实例的回调
        playing = false;
    }

    private MediaPlayer createPlayer(String path, final String tag) throws Exception {
        MediaPlayer mp = new MediaPlayer();
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mp.setDataSource(path);
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer m, int what, int extra) {
                toastError("播放失败 " + tag + " (" + what + "/" + extra + ")");
                stopPlayer();
                if (view != null) view.invalidate();
                return true;
            }
        });
        mp.prepare();
        return mp;
    }

    /** 从头唱一首：算一次点歌。 */
    private void play(final int idx) { play(idx, 0, true, true); }

    /** 切音轨 / seek：保持位置，不算点歌。 */
    private void play(final int idx, int startAt, boolean autoStart) {
        play(idx, startAt, autoStart, false);
    }

    private void play(final int idx, int startAt, boolean autoStart, boolean countPlay) {
        if (songs.isEmpty() || usbGone) return;
        advanceToken++;                 // 作废还在路上的「2 秒过场 -> 下一首」
        interludeUntil = 0;
        stopPlayer();
        seekFloor = 0;
        rollLastLine = Integer.MIN_VALUE;   // 换歌 / 重唱 / 换音轨：歌词直接就位，不滚
        rollStartAt = 0;
        cur = ((idx % songs.size()) + songs.size()) % songs.size();
        listCursor = cur;
        syncLibTop();
        final Song s = songs.get(cur);
        err = "";
        lastSong = s.name;
        if (countPlay) {
            /* 先挂待结算，等这首歌自然播到结尾再 +1；中途切走就不算。 */
            pendingCount = s;
            countVetoed = false;
        }
        savePrefs();
        showList = false;
        try {
            player = createPlayer(audioPath(s), "音源");
            final int myGen = playerGen;      // 只认当前这一个实例的完成回调
            // 单轨与双轨统一走「2 秒过场 -> 下一首」，别硬切
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer mp) {
                    if (myGen != playerGen) return;   // 已被 stopPlayer 作废，丢弃
                    settleCount(s);              // 唱完整首，才算一次点歌
                    handler.post(nextSong);
                }
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
            Log.i(TAG, "播放 " + s.name + (useInst ? " [伴奏]" : " [原唱]")
                    + " 累计点歌 " + s.plays + " 次");
        } catch (Exception e) {
            toastError("无法播放：" + e.getClass().getSimpleName());
            Log.e(TAG, err, e);
            stopPlayer();
        }
        if (view != null) view.invalidate();
    }

    private void toggle() {
        /* LIVE-BEGIN */
        if (liveMode) {
            if (liveArmed) {
                /* 直播就绪：按 OK 才开演 */
                enterProgram(programIdx);
                return;
            }
            if (programWaiting || programEnded) {
                if (programEnded) enterProgram(0); else advanceProgram(false);
                return;
            }
        }
        /* LIVE-END */
        if (songs.isEmpty()) { rescan(); return; }
        if (player == null) { play(cur, 0, true, false); return; }
        if (playing) { player.pause(); playing = false; }
        else { player.start(); playing = true; }
        view.invalidate();
    }

    /** 按钮 1 / 快捷键 0：在「歌名.mp3」与「歌名.伴奏.mp3」之间切换，保持位置。 */
    /* LIVE-BEGIN */
    /** 直播模式下当前不能切原唱/伴奏的两种状态：
        弹奏类节目（没有音频）、直播就绪（还没开演）。 */
    private boolean trackDisabled() {
        return liveMode && (programWaiting || liveArmed);
    }

    /** 不能切音轨时给一句人话提示。 */
    private String trackBlockedMsg() {
        if (liveMode && liveArmed) return "直播就绪，按 OK 开始";
        return "弹奏节目没有音频，1 键已禁用";
    }
    /* LIVE-END */

    private void switchTrack() {
        /* LIVE-BEGIN */
        /* 兜底闸：弹奏类节目没有音频，切原唱/伴奏无意义。
           这里挡住的是 M / 静音键那条不进 act() 的路，
           不挡的话会把上一首歌重新播起来。 */
        if (trackDisabled()) {
            showBanner(trackBlockedMsg());
            return;
        }
        /* LIVE-END */
        if (songs.isEmpty()) return;
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
        return useInst && songs.get(cur).instPath != null;
    }

    private void seekTo(int ms) {
        play(cur, ms, playing);
        if (view != null) view.invalidate();
    }

    private void seek(int delta) {
        if (player == null) return;
        try {
            int dur = player.getDuration();
            int to = player.getCurrentPosition() + delta;
            if (to < 0) to = 0;
            if (dur > 0 && to > dur - 300) to = Math.max(0, dur - 300);
            if (dur > 0 && to >= dur * 4 / 5) countVetoed = true;   // 直接跳到尾声：不算完整唱完
            player.seekTo(to);
            seekFloor = to;
            seekFloorUntil = System.currentTimeMillis() + 800;
        } catch (Exception e) { }
        view.invalidate();
    }

    /** 调试用：跳到绝对位置。不重建播放器、不算点歌，沿用 seekFloor 防抖。 */
    private void seekAbsolute(int ms) {
        if (player == null) return;
        try {
            int dur = player.getDuration();
            if (dur <= 0) return;
            int to = ms < 0 ? 0 : (ms > dur - 300 ? Math.max(0, dur - 300) : ms);
            if (to >= dur * 4 / 5) countVetoed = true;   // 拖到尾声：不算完整唱完
            player.seekTo(to);
            seekFloor = to;
            seekFloorUntil = System.currentTimeMillis() + 800;
            Log.i(TAG, "进度条 seek -> " + mmss(to));
        } catch (Exception e) { }
        if (view != null) view.invalidate();
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
        try {
            int p = player == null ? 0 : player.getCurrentPosition();
            if (p < seekFloor && System.currentTimeMillis() < seekFloorUntil) return seekFloor;
            return p;
        } catch (Exception e) { return 0; }
    }

    private int duration() {
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
        /* LIVE-BEGIN */
        /* 位次版下播提醒：庆祝层浮着时，任意键只关层并吞掉本次，
           连第一下 OK 也只关层，不会顺手触发暂停/点歌（等第二下 OK 才继续）。 */
        if (celebrating) { closeCelebration(); return true; }
        /* 庆祝层已关、仍停住等 OK：只有 OK 键推进下一首，其余键吞掉保持停住。 */
        if (endNoticeHold) {
            boolean okKey = (key == KeyEvent.KEYCODE_DPAD_CENTER
                    || key == KeyEvent.KEYCODE_ENTER
                    || key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    || key == KeyEvent.KEYCODE_SPACE);
            if (okKey) {
                if (e.getRepeatCount() > 0) return true;
                endNoticeHold = false;
                advanceProgram(false);
                Log.i(TAG, "下播提醒后按 OK：继续下一首");
            }
            return true;
        }
        /* 双击 # 在两种模式间切换；单击不动作，防老人误触。 */
        if (key == KeyEvent.KEYCODE_POUND) {
            if (e.getRepeatCount() > 0) return true;
            long now = System.currentTimeMillis();
            if (now - lastPoundAt <= POUND_DOUBLE_MS) {
                lastPoundAt = 0;
                toggleLiveMode();
            } else {
                lastPoundAt = now;
            }
            return true;
        }
        /* LIVE-END */
        if (showList) return onListKey(key);
        switch (key) {
            case KeyEvent.KEYCODE_1: act(1); return true;
            case KeyEvent.KEYCODE_2: act(2); return true;
            case KeyEvent.KEYCODE_3: act(3); return true;
            case KeyEvent.KEYCODE_0: act(0); return true;      // 切歌
            case KeyEvent.KEYCODE_M:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                /* LIVE-BEGIN */
                if (trackDisabled()) {
                    showBanner(trackBlockedMsg());
                    return true;
                }
                /* LIVE-END */
                setActive(0); switchTrack(); return true;      // 原唱/伴奏
            /* 主屏 ↑↓ 与媒体键仍一律不响应：会和「歌单里 ↑↓ 选歌」抢键。
               ← / → 改成双击快退 / 快进，单击什么都不做，保留防误触。 */
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (e.getRepeatCount() == 0) {
                    long now = System.currentTimeMillis();
                    if (lastDirKey == key && now - lastDirAt <= DOUBLE_TAP_MS) {
                        int step = (key == KeyEvent.KEYCODE_DPAD_RIGHT)
                                 ? SEEK_STEP_MS : -SEEK_STEP_MS;
                        seek(step);
                        showBanner(step > 0 ? "快进 10 秒" : "快退 10 秒");
                        Log.i(TAG, "双击 " + (step > 0 ? "→ 快进" : "← 快退") + " 10 秒");
                        lastDirKey = -1;
                        lastDirAt = 0;
                    } else {
                        lastDirKey = key;
                        lastDirAt = now;
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                /* 空格 = 暂停 / 播放。长按会连发，只认第一次按下，
                   否则按住不放会让播放状态来回抖。 */
                if (key == KeyEvent.KEYCODE_SPACE && e.getRepeatCount() > 0) return true;
                toggle(); return true;
            case KeyEvent.KEYCODE_MENU:
                act(3); return true;
            case KeyEvent.KEYCODE_BACK:
                if (backArmed) { finish(); return true; }
                backArmed = true;
                handler.postDelayed(disarmBack, 2000);
                showBanner("再按一次返回键退出 K 歌");
                return true;
            default:
                return super.onKeyDown(key, e);
        }
    }

    /** 歌单：↑↓ 选、OK 马上唱、3 / 菜单 / 返回 收起；0 直接切歌。列表默认落在当前首。 */
    private boolean onListKey(int key) {
        /* LIVE-BEGIN */
        if (liveMode) return onProgramKey(key);
        /* LIVE-END */
        switch (key) {
            case KeyEvent.KEYCODE_3:
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
            case KeyEvent.KEYCODE_0: showList = false; act(0); return true;
            default:
                return true;
        }
    }

    /* ---------------- 生命周期 ---------------- */

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && playing) {
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
        if (!toastOn) return false;
        long t = System.currentTimeMillis() - toastStart;
        return t < TOAST_IN_MS + toastHold + TOAST_OUT_MS;
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
        }
        pIco.setStyle(Paint.Style.STROKE);
    }

    class KView extends View {
        KView(Context c) { super(c); }

        /* ---------- 调试用：鼠标点/拖全曲进度条快进快退 ---------- */
        private boolean barHit(float x, float y) {
            int W = getWidth(), H = getHeight();
            float pad = W * 0.045f;
            float by = H * 0.775f, bh = Math.max(10, H * 0.011f);
            return x >= pad - W * 0.02f && x <= W - pad + W * 0.02f
                && y >= by - H * 0.045f && y <= by + bh + H * 0.015f;
        }

        private int barTargetMs(float x) {
            int W = getWidth();
            float pad = W * 0.045f, bw = W - 2 * pad;
            int dur = duration();
            if (dur <= 0 || bw <= 0) return -1;
            float r = (x - pad) / bw;
            if (r < 0f) r = 0f;
            else if (r > 1f) r = 1f;
            return (int) (r * dur);
        }

        /** 鼠标落在四个大按钮的哪一个上；缝隙与按钮区外返回 -1。
            几何必须与 drawButtonBar 完全一致。 */
        private int btnAt(float x, float y) {
            int W = getWidth(), H = getHeight();
            float pad = W * 0.045f, gap = W * 0.012f;
            float bw = (W - 2 * pad - 3 * gap) / 4f;
            float bh = H * 0.145f, y0 = H * 0.805f;
            if (y < y0 || y > y0 + bh) return -1;
            for (int k = 0; k < 4; k++) {
                float left = pad + k * (bw + gap);
                if (x >= left && x <= left + bw) return k;
            }
            return -1;
        }

        /** 歌单六行列表区（几何与 drawLibList 一致）。 */
        private boolean listHit(float x, float y) {
            int W = getWidth(), H = getHeight();
            float pad = W * 0.045f, y0 = H * 0.215f, rh = H * 0.088f;
            return x >= pad && x <= W - pad && y >= y0 && y <= y0 + 6 * rh;
        }

        /** 屏幕 y 落在列表第几行对应的歌曲索引；行外 / 超出曲库返回 -1。 */
        private int listRowAt(float y) {
            int H = getHeight();
            float y0 = H * 0.215f, rh = H * 0.088f;
            int k = (int) ((y - y0) / rh);
            if (k < 0 || k >= 6) return -1;
            int idx = libTop + k;
            return idx < songs.size() ? idx : -1;
        }

        /** 鼠标滚轮走 generic motion（不是 onTouchEvent）：一格一行，滚到顶 / 底停住。 */
        @Override
        public boolean onGenericMotionEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_SCROLL && showList && !songs.isEmpty()
                    && listHit(e.getX(), e.getY())) {
                float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (v == 0f) return true;
                int n = songs.size();
                int to = listCursor + (v > 0f ? -1 : 1);
                listCursor = to < 0 ? 0 : (to >= n ? n - 1 : to);
                syncLibTop();
                Log.i(TAG, "歌单滚轮 -> 第 " + (listCursor + 1) + " / " + n + " 首");
                invalidate();
                return true;
            }
            return super.onGenericMotionEvent(e);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getAction() & MotionEvent.ACTION_MASK;
            if (a == MotionEvent.ACTION_DOWN) {
                pressedBtn = -1;
                listTouched = false;
                pressedRow = -1;
                if (showList && !songs.isEmpty() && listHit(e.getX(), e.getY())) {
                    listTouched = true;
                    listDownY = e.getY();
                    listDownCursor = listCursor;
                    pressedRow = listRowAt(e.getY());
                    return true;
                }
                if (!songs.isEmpty() && !usbGone && duration() > 0
                        && System.currentTimeMillis() >= interludeUntil
                        && barHit(e.getX(), e.getY())) {
                    dragMs = barTargetMs(e.getX());
                    if (dragMs >= 0) { invalidate(); return true; }
                }
                pressedBtn = btnAt(e.getX(), e.getY());
                return pressedBtn >= 0;
            }
            if (listTouched) {
                float rh = getHeight() * 0.088f;
                if (a == MotionEvent.ACTION_MOVE) {
                    int delta = (int) ((listDownY - e.getY()) / rh);
                    int n = songs.size();
                    int to = listDownCursor + delta;
                    int nc = to < 0 ? 0 : (to >= n ? n - 1 : to);
                    if (nc != listCursor) {
                        listCursor = nc;
                        syncLibTop();
                        Log.i(TAG, "歌单拖动 -> 第 " + (listCursor + 1) + " / " + n + " 首");
                        invalidate();
                    }
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    listTouched = false;
                    /* 位移不到 0.4 行才算点击；拖动滚动不会误点歌。 */
                    if (a == MotionEvent.ACTION_UP && pressedRow >= 0
                            && Math.abs(e.getY() - listDownY) < rh * 0.4f) {
                        Log.i(TAG, "歌单点击 -> 第 " + (pressedRow + 1) + " 首 "
                                + songName(pressedRow));
                        play(pressedRow);
                        showBanner("马上唱：" + songName(pressedRow));
                    }
                    pressedRow = -1;
                    invalidate();
                }
                return true;
            }
            if (dragMs >= 0) {
                if (a == MotionEvent.ACTION_MOVE) {
                    /* 拖动中只更新金色条与顶栏目标时间，不真正 seek：
                       连续 seekTo 会让 MediaPlayer 卡顿，松手时只跳一次。 */
                    int t = barTargetMs(e.getX());
                    if (t >= 0) dragMs = t;
                    invalidate();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    int to = dragMs;
                    dragMs = -1;
                    if (a == MotionEvent.ACTION_UP) seekAbsolute(to);
                    else invalidate();
                }
                return true;
            }
            if (pressedBtn >= 0) {
                if (a == MotionEvent.ACTION_UP) {
                    int b = pressedBtn;
                    pressedBtn = -1;
                    /* 松手仍在同一个按钮里才算点击，拖出去可取消。 */
                    if (btnAt(e.getX(), e.getY()) == b) {
                        Log.i(TAG, "鼠标点击按钮 " + BTN_LABELS[b]);
                        act(BTN_ACTS[b]);
                    }
                } else if (a == MotionEvent.ACTION_CANCEL) {
                    pressedBtn = -1;
                }
                return true;
            }
            return false;
        }

        @Override
        protected void onDraw(Canvas c) {
            int W = getWidth(), H = getHeight();
            if (W <= 0 || H <= 0) return;
            float pad = W * 0.045f;

            if (booting) { drawSplash(c, W, H); return; }

            c.drawRect(0, 0, W, H, pBg);

            if (usbGone) { drawUsbGone(c, W, H); return; }
            if (System.currentTimeMillis() < interludeUntil) { drawInterlude(c, W, H); return; }

            drawTopBar(c, W, H, pad);
            drawMarquee(c, W, H);

            /* LIVE-BEGIN */
            if (showList && liveMode) drawProgramList(c, W, H, pad);
            else
            /* LIVE-END */
            if (showList) drawLibList(c, W, H, pad);
            else {
                /* LIVE-BEGIN */
                if (liveMode && (programWaiting || programEnded || liveArmed)) drawProgramStage(c, W, H, pad);
                else
                /* LIVE-END */
                drawLyricsArea(c, W, H, pad);
                /* LIVE-BEGIN */
                if (liveMode) drawOpening(c, W, H, pad);
                /* LIVE-END */
            }

            int dur = duration();
            float bw = W - 2 * pad, by = H * 0.775f, bh = Math.max(10, H * 0.011f);
            card(c, pBar, pad, by, bw, bh, W);
            int shown = dragMs >= 0 ? dragMs : position();
            if (dur > 0) {
                float frac = shown / (float) dur;
                if (frac < 0f) frac = 0f;
                if (frac > 1f) frac = 1f;
                card(c, pBarOn, pad, by, bw * frac, bh, W);
                drawGlowDot(c, pad + bw * frac, by + bh * 0.5f, bh, dragMs >= 0);
            }

            drawButtonBar(c, W, H, pad);
            drawToast(c, W, H);
            /* LIVE-BEGIN */
            if (liveMode && celebrating) drawCelebration(c, W, H, pad);
            /* LIVE-END */
        }

        /** 进度条末端发光亮点：多层递减透明圆叠出柔光 + 亮核，纯 drawCircle，
            不依赖 BlurMaskFilter / Shader，老 Skia 与硬件加速下都稳；带轻微呼吸。 */
        private void drawGlowDot(Canvas c, float cx, float cy, float bh, boolean dragging) {
            float pulse = 0.94f + 0.06f * (float) Math.sin(System.currentTimeMillis() / 480.0);
            float k = (dragging ? 1.18f : 1f) * pulse;
            for (int n = 6; n >= 1; n--) {
                float r = bh * (0.45f + n * 0.31f) * k;
                int a = (int) ((dragging ? 20 : 14) * (7 - n) * pulse);
                pGlow.setColor(Color.argb(a, 255, 198, 76));
                c.drawCircle(cx, cy, r, pGlow);
            }
            pGlow.setColor(0xFFFFE9A8);
            c.drawCircle(cx, cy, bh * 0.60f * k, pGlow);
            pGlow.setColor(0xFFFFFFFF);
            c.drawCircle(cx, cy, bh * 0.24f * k, pGlow);
        }

        /* ---------- 顶栏：歌名 / 进度 / 状态 ---------- */

        private void drawTopBar(Canvas c, int W, int H, float pad) {
            String title = songs.isEmpty() ? (appName + " · 等待曲库") : songs.get(cur).name;
            /* LIVE-BEGIN */
            if (liveMode && !program.isEmpty()) title = program.get(programIdx).key;
            /* LIVE-END */
            float rightX = W - pad;

            pTitle.setTextSize(W * FS_NAME);
            // 固定字号；超长截尾加省略号。跑马灯已整体移到右半屏（0.50W 起），
            // 歌名可用宽从 0.30W 放宽到 0.44W，长歌名不再只剩两三个字。
            c.drawText(ellipsize(pTitle, title, W * 0.44f), pad, H * 0.098f, pTitle);

            pSub.setTextSize(W * FS_SUB);
            String meta = (songs.isEmpty() ? "0 / 0" : ("第 " + (cur + 1) + " / " + songs.size() + " 首"))
                + "      " + mmss(dragMs >= 0 ? dragMs : position()) + " / "
                + (duration() > 0 ? mmss(duration()) : "--:--");
            /* LIVE-BEGIN */
            if (liveMode && !program.isEmpty())
                meta = "第 " + (programIdx + 1) + " / " + program.size() + " 个节目      "
                     + mmss(dragMs >= 0 ? dragMs : position()) + " / "
                     + (duration() > 0 ? mmss(duration()) : "--:--");
            /* LIVE-END */
            c.drawText(meta, pad, H * 0.158f, pSub);

            /* 第二行右侧：操作提示靠左、播放状态贴最右。
               状态原本在第一行最右，跑马灯占掉那半屏后只能挪到这一行。
               顺序先画最右的状态（量出宽度），再把它左边的提示右对齐到剩余空间。 */
            String flags = playing ? "▶ 播放中" : "‖ 已暂停";
            /* LIVE-BEGIN */
            /* 模式标识跟在「播放中 / 已暂停」后面：先说状态，再说当前是哪种模式。 */
            flags = flags + (liveMode ? " · 直播模式" : " · 普通模式");
            /* LIVE-END */
            pSub.setTextAlign(Paint.Align.RIGHT);
            pSub.setColor(playing ? GOLD : INK_DIM);
            float flagW = pSub.measureText(flags);
            c.drawText(flags, rightX, H * 0.158f, pSub);

            /* 提示为空（歌单展开）就整段不画，只留右侧状态，别占位置。 */
            String hint = hintText();
            if (hint.length() > 0) {
                pSub.setColor(err.length() > 0 ? BAD : INK_DIM);
                /* LIVE-BEGIN */
                if (liveMode) { fit(pSub, hint, W * 0.34f, W * FS_SUB); }
                else
                /* LIVE-END */
                fit(pSub, hint, W * 0.42f, W * FS_SUB);
                c.drawText(hint, rightX - flagW - W * 0.028f, H * 0.158f, pSub);
            }
            pSub.setColor(INK_SOFT);
            pSub.setTextAlign(Paint.Align.LEFT);
        }

        /* ---------- 顶部中间：欢迎语 + 下一首，右→左无缝循环 ---------- */

        private void drawMarquee(Canvas c, int W, int H) {
            String tail = "";
            /* LIVE-BEGIN */
            if (liveMode && !program.isEmpty()) {
                /* 直播模式按节目单走：预告的是「下一个节目」，
                   不是点歌排序里的下一首（那个在直播里没有意义）。
                   缺文件被跳过的节目不预告，直接找下一个真能演的。 */
                int ni = liveArmed ? programIdx : programIdx + 1;
                while (ni < program.size() && program.get(ni).skip) ni++;
                tail = (ni < program.size()) ? ("下一个节目：" + program.get(ni).key)
                                            : "已是最后一个节目";
            }
            /* LIVE-END */
            if (tail.length() == 0 && !songs.isEmpty())
                tail = "下一首：" + songName(peekNext());
            /* 靠右、占半屏：左半屏整块让给歌名（0.045W~0.485W），
               右端与顶栏右边界齐平（W - 0.045W）。 */
            float lx = W * 0.500f, rx = W * 0.955f, base = H * 0.098f;
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
            /* 窗口两端各 0.030W 渐隐：文字滚进来 / 滚出去都是淡入淡出，
               不在边界被硬裁成半个字——否则滚到左沿的「下一首：…」会紧贴
               歌名尾部，看着像歌名多长出了几个金色字。 */
            float fw = W * 0.030f, fy0 = H * 0.055f, fy1 = H * 0.118f;
            pFade.setShader(new LinearGradient(lx, fy0, lx + fw, fy0,
                    BG, BG & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(lx, fy0, lx + fw, fy1, pFade);
            pFade.setShader(new LinearGradient(rx - fw, fy0, rx, fy0,
                    BG & 0x00FFFFFF, BG, Shader.TileMode.CLAMP));
            c.drawRect(rx - fw, fy0, rx, fy1, pFade);
            pFade.setShader(null);
            c.restore();
            pSub.setColor(INK_SOFT);
            pSub.setTextSize(W * FS_SUB);
        }

        /* ---------- 四个等宽大按钮：图标 + 汉字 + 数字角标 ---------- */

        private void drawButtonBar(Canvas c, int W, int H, float pad) {
            float gap = W * 0.012f;
            float bw = (W - 2 * pad - 3 * gap) / 4f;
            float bh = H * 0.145f, y0 = H * 0.805f;
            boolean barFocus = !showList && !bannerActive();
            long elapsed = System.currentTimeMillis() - focusSwitchAt;
            float anim = elapsed < 120f ? ((float) elapsed / 120f) : 1f;
            if (anim < 0f) anim = 0f;
            if (anim > 1f) anim = 1f;

            for (int k = 0; k < 4; k++) {
                float x = pad + k * (bw + gap);
                boolean on = (barFocus && k == activeBtn) || k == pressedBtn;
                /* LIVE-BEGIN */
                /* 弹奏类节目没有音频：1 键整条禁用——不聚焦、不高亮、整块压暗。 */
                boolean dis = trackDisabled() && k == 0;
                if (dis) on = false;
                /* LIVE-END */
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

                // 数字角标（1/2/3/0 直达）
                pNum.setStyle(Paint.Style.FILL);
                pNum.setColor(on ? GOLD : INK_DIM);
                c.drawCircle(x + W * 0.020f, y0 + W * 0.020f, W * 0.013f, pNum);
                pNum.setColor(BG);
                pNum.setTextSize(W * 0.017f);
                c.drawText(BTN_KEYS[k], x + W * 0.020f,
                           y0 + W * 0.026f, pNum);

                // 图标
                pIco.setColor(on ? GOLD : INK);
                /* LIVE-BEGIN */
                if (dis) pIco.setColor(INK_DIM);
                /* LIVE-END */
                int kind = (k == 0) ? ICON_VOICE : (k == 1) ? ICON_REPLAY
                         : (k == 2) ? ICON_LIST : ICON_SKIP;
                icon(c, kind, x + bw / 2, y0 + bh * 0.30f, W * 0.016f, W);
                pIco.setColor(INK);

                // 汉字（按钮 1 显示当前状态：原唱 / 伴奏）
                String label = (k == 0) ? (accompanimentNow() ? "伴奏" : "原唱") : BTN_LABELS[k];
                /* LIVE-BEGIN */
                if (k == 2 && liveMode) label = "节目";
                if (k == 3 && liveMode) label = "切节目";
                if (dis) label = "原唱";
                /* LIVE-END */
                pCur.setTextAlign(Paint.Align.CENTER);
                pCur.setTextSize(W * FS_BTN);
                pCur.setColor(on ? GOLD : INK);
                /* LIVE-BEGIN */
                if (dis) pCur.setColor(INK_DIM);
                /* LIVE-END */
                fit(pCur, label, bw * 0.90f, W * FS_BTN);
                c.drawText(label, x + bw / 2, y0 + bh * 0.86f, pCur);
                pCur.setColor(INK);
            }
        }

        /* ---------- 顶栏一行提示：只留最必要的三条 ---------- */

        private String hintText() {
            if (err.length() > 0) return err;
            /* 歌单展开时顶栏不留提示：选歌 / 马上唱已并入列表头那一行，
               再在这里写一遍就成了两行字挤在一起。 */
            if (showList) return "";
            /* LIVE-BEGIN */
            if (liveMode && programWaiting) return "OK 进入下一个节目";
            if (liveMode && programEnded) return "OK 从头再演一遍";
            /* LIVE-END */
            return "OK 播放 / 暂停";
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
            boolean hasNext = i >= 0 && i + 1 < s.lines.size();
            long next = hasNext ? s.lines.get(i + 1).ms : -1L;
            /* 有「唱完」锚点就按锚点收尾——这是歌词文件里真实的演唱结束时间，
               不做任何字数估算。夹两下只防呆：不越过下一句行首、至少留 800ms。 */
            if (i >= 0 && i < s.lines.size()) {
                long anchor = s.lines.get(i).endMs;
                if (anchor > 0) {
                    long end = anchor;
                    if (hasNext && next > 0 && end > next) end = next;
                    if (end < start + 800L) end = start + 800L;
                    return end;
                }
            }
            if (hasNext) return next;
            return Math.max(start + 1000L, (long) duration());
        }

        /* 扫字进度完全由 LRC 时间轴决定：本句行首 -> 下一行行首。
           不做字数估算、不做 8 秒封顶，节奏交给歌词文件本身。 */
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
            float x = pad, w = W - 2 * pad, y0 = H * 0.222f, rh = H * 0.088f;
            syncLibTop();
            /* 头部只留一行：左边排序说明，右边操作提示 + 剩余数量。
               操作键（3 收起 / 0 切歌）不再写字——四个大按钮的角标已经印着，
               顶栏第二行也不再重复一遍，避免两行提示挤在一起。 */
            pSub.setTextSize(W * FS_SUB);
            pSub.setTextAlign(Paint.Align.LEFT);
            pSub.setColor(INK_SOFT);
            String head = "按点歌次数 / 歌名排序";
            fit(pSub, head, w * 0.42f, W * FS_SUB);
            c.drawText(head, x, H * 0.198f, pSub);

            String ops = "↑↓ 选歌　　OK 马上唱";
            int rest = songs.size() - libTop - 6;
            if (rest > 0) ops += "　　·　　还有 " + rest + " 首 ↓";
            pSub.setTextAlign(Paint.Align.RIGHT);
            fit(pSub, ops, w * 0.58f, W * FS_SUB);   // 剩余数到三位也不溢出
            c.drawText(ops, x + w, H * 0.198f, pSub);
            pSub.setTextAlign(Paint.Align.LEFT);

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

                // 三态标签 + 点歌次数（能直接看到「记下来了」）
                String tag = now ? "▶ 正在唱" : (sung ? "已唱过" : "");
                int pc = songs.get(idx).plays;
                if (pc > 0) tag = tag.length() > 0 ? (tag + "  " + pc + " 次") : (pc + " 次");
                if (tag.length() > 0) {
                    pSub.setTextAlign(Paint.Align.RIGHT);
                    pSub.setTextSize(W * FS_SUB);
                    pSub.setColor(sung ? INK_DIM : GOLD);
                    fit(pSub, tag, w * 0.32f, W * FS_SUB);
                    c.drawText(tag, x + w - W * 0.018f, y + h * 0.66f, pSub);
                }
            }
            pCur.setTextAlign(Paint.Align.CENTER);
            pCur.setColor(INK);
            pSub.setTextAlign(Paint.Align.LEFT);
        }

        /* ---------- 大字反馈 / 预告 / 过场 / 拔盘 / 空库 ---------- */

        /* LIVE-BEGIN */
        /* ---------- 下播：全屏庆祝页 + 礼花 ---------- */

        /** 全屏庆祝：深底 + 金边 + 「直播结束啦 / 主播辛苦了！」+ 礼花。
            下播时间未必准，所以这一页由「节目单走完」触发，不是时间到点。 */
        private void drawCelebration(Canvas c, int W, int H, float pad) {
            c.drawRect(0, 0, W, H, pCelBg);
            drawFireworks(c, W, H);

            float m = W * 0.032f;
            pCelEdge.setStyle(Paint.Style.STROKE);
            pCelEdge.setStrokeWidth(Math.max(4f, W * 0.0045f));
            pCelEdge.setColor(GOLD);
            c.drawRect(m, H * 0.085f, W - m, H * 0.915f, pCelEdge);

            float cx = W / 2f;
            pCelTag.setTextAlign(Paint.Align.CENTER);
            pCelTag.setTextSize(W * 0.020f);
            pCelTag.setColor(GOLD);
            c.drawText("下播提醒", cx, H * 0.235f, pCelTag);

            pCelMain.setTextAlign(Paint.Align.CENTER);
            pCelMain.setTextSize(W * 0.072f);
            pCelMain.setColor(Color.WHITE);
            fit(pCelMain, noticeEnd, W * 0.84f, W * 0.072f);
            c.drawText(ellipsize(pCelMain, noticeEnd, W * 0.84f), cx, H * 0.385f, pCelMain);

            pCelSub.setTextAlign(Paint.Align.CENTER);
            pCelSub.setTextSize(W * 0.038f);
            pCelSub.setColor(GOLD);
            c.drawText("主播辛苦了！", cx, H * 0.505f, pCelSub);

            pCelFoot.setTextAlign(Paint.Align.CENTER);
            pCelFoot.setTextSize(W * 0.020f);
            pCelFoot.setColor(INK_SOFT);
            if (endNoticeProg > 0) {
                /* 位次版：唱完指定节目后停住，关层后按 OK 才继续 */
                c.drawText("下一首已停住 · 关层后按 OK 继续", cx, H * 0.605f, pCelFoot);
            } else {
                c.drawText("今天演了 " + program.size() + " 个节目  ·  " + liveEndTxt + " 已到点",
                           cx, H * 0.605f, pCelFoot);
            }
            c.drawText("按任意键关闭", cx, H * 0.850f, pCelFoot);
        }

        /** 礼花：爆炸点与粒子提前生成好，绘制时按时间解析求位置 ——
            不改状态、不依赖帧率，20fps 和 40fps 放出来一样。 */
        private void drawFireworks(Canvas c, int W, int H) {
            if (!fwReady) return;
            long t = System.currentTimeMillis() - celebrateAt;
            float life = 2.4f;                       // 单次爆炸的寿命（秒）
            for (int b = 0; b < FW_BURSTS; b++) {
                float dt = (t - fwT[b]) / 1000f;
                if (dt < 0f || dt > life) continue;
                float ox = fwX[b] * W, oy = fwY[b] * H;
                float g = H * 0.52f;                 // 重力
                float fade = 1f - dt / life;
                int a = (int) (235 * fade);
                if (a <= 0) continue;
                int col = fwC[b];
                if (dt < 0.16f) {                   // 爆炸瞬间的亮核
                    pFw.setColor(0xFFFFFFFF);
                    c.drawCircle(ox, oy, W * 0.018f * (1f - dt / 0.16f), pFw);
                }
                for (int j = 0; j < FW_PARTS; j++) {
                    float px = ox + fwVX[b][j] * W * dt;
                    float py = oy + fwVY[b][j] * H * dt + 0.5f * g * dt * dt;
                    pFw.setColor(Color.argb(a, Color.red(col), Color.green(col), Color.blue(col)));
                    c.drawCircle(px, py, fwR[j] * W * (0.5f + 0.7f * fade), pFw);
                }
            }
        }

        /* ---------- 弹奏类节目：无音频，停住等 OK ---------- */
        private void drawProgramStage(Canvas c, int W, int H, float pad) {
            if (programIdx < 0 || programIdx >= program.size()) return;
            ProgramItem it = program.get(programIdx);
            float x = W * 0.355f, w = W * 0.60f;
            float y = H * 0.245f, h = H * 0.44f;
            card(c, pCardOn, x, y, w, h, W);
            card(c, pFocus, x, y, w, h, W);
            pCur.setTextAlign(Paint.Align.CENTER);
            pCur.setColor(GOLD);
            pCur.setTextSize(W * 0.020f);
            String head;
            if (liveArmed) head = "直播就绪  ·  第 " + (programIdx + 1) + " / " + program.size() + " 个节目";
            else if (programEnded) head = "全部节目已演完";
            else head = "第 " + (programIdx + 1) + " / " + program.size() + " 个节目  ·  " + it.type;
            c.drawText(head, x + w / 2, y + h * 0.20f, pCur);
            pCur.setColor(INK);
            pCur.setTextSize(W * 0.048f);
            fit(pCur, it.key, w * 0.90f, W * 0.048f);
            c.drawText(ellipsize(pCur, it.key, w * 0.90f), x + w / 2, y + h * 0.52f, pCur);
            pCur.setColor(INK_SOFT);
            pCur.setTextSize(W * 0.022f);
            String foot;
            if (liveArmed) foot = "按 OK 开始直播";
            else if (programEnded) foot = "按 OK 从头再演一遍";
            else foot = "按 OK 进入下一个节目";
            c.drawText(foot, x + w / 2, y + h * 0.80f, pCur);
            if (it.miss.length() > 0) {
                pCur.setColor(BAD);
                pCur.setTextSize(W * 0.019f);
                c.drawText(it.miss, x + w / 2, y + h * 0.92f, pCur);
            }
            pCur.setColor(INK);
        }

        /* ---------- 直播模式的「节目」列表（严格顺序） ---------- */
        private void drawProgramList(Canvas c, int W, int H, float pad) {
            float x = pad, w = W - 2 * pad, y0 = H * 0.222f, rh = H * 0.088f;
            syncLibTop();
            pSub.setTextSize(W * FS_SUB);
            pSub.setTextAlign(Paint.Align.LEFT);
            pSub.setColor(INK_SOFT);
            String head = "直播节目单 " + program.size() + " 个　·　严格按顺序";
            fit(pSub, head, w * 0.42f, W * FS_SUB);
            c.drawText(head, x, H * 0.198f, pSub);

            String ops = "↑↓ 选节目　　OK 直接进入";
            int rest = program.size() - libTop - 6;
            if (rest > 0) ops += "　　·　　还有 " + rest + " 个 ↓";
            pSub.setTextAlign(Paint.Align.RIGHT);
            fit(pSub, ops, w * 0.58f, W * FS_SUB);
            c.drawText(ops, x + w, H * 0.198f, pSub);
            pSub.setTextAlign(Paint.Align.LEFT);

            pCur.setTextAlign(Paint.Align.LEFT);
            for (int k = 0; k < 6; k++) {
                int idx = libTop + k;
                if (idx >= program.size()) break;
                float y = y0 + k * rh, h = rh * 0.86f;
                ProgramItem it = program.get(idx);
                boolean on = idx == listCursor, now = idx == programIdx;
                boolean sung = idx < programIdx;
                card(c, now ? pCardOn : pCard, x, y, w, h, W);
                if (on && !bannerActive()) card(c, pFocus, x, y, w, h, W);

                pSub.setTextAlign(Paint.Align.LEFT);
                pSub.setTextSize(W * FS_SUB);
                pSub.setColor(sung ? INK_DIM : (now ? GOLD : INK_SOFT));
                c.drawText(String.format("%02d", idx + 1), x + W * 0.018f, y + h * 0.66f, pSub);

                pCur.setTextSize(W * FS_BTN);
                pCur.setColor(sung ? INK_DIM : (now ? GOLD : INK));
                fit(pCur, it.key, w * 0.62f, W * FS_BTN);
                c.drawText(ellipsize(pCur, it.key, w * 0.62f),
                           x + W * 0.075f, y + h * 0.66f, pCur);

                String tag = it.type;
                if (it.skip) tag += " · 跳过";
                else if (it.miss.length() > 0) tag += " · " + it.miss;
                if (now) tag = "▶ 当前 · " + tag;
                pSub.setTextAlign(Paint.Align.RIGHT);
                pSub.setTextSize(W * FS_SUB);
                pSub.setColor(it.skip ? BAD : (sung ? INK_DIM : GOLD));
                fit(pSub, tag, w * 0.32f, W * FS_SUB);
                c.drawText(tag, x + w - W * 0.018f, y + h * 0.66f, pSub);
            }
            pCur.setTextAlign(Paint.Align.CENTER);
            pCur.setColor(INK);
            pSub.setTextAlign(Paint.Align.LEFT);
            pSub.setColor(INK_SOFT);
        }
        /* LIVE-END */
        /** 统一气泡提示（Toast）：从屏幕右边往左滑入、停在右上（避开顶栏）、停留后淡出。
            新的覆盖旧的，同一时刻只一条。解析动画，不依赖帧率。 */
        private void drawToast(Canvas c, int W, int H) {
            long t = System.currentTimeMillis() - toastStart;
            long life = TOAST_IN_MS + toastHold + TOAST_OUT_MS;
            if (t < 0 || t >= life) { toastOn = false; return; }
            float pad = W * 0.045f;
            int accent = toastErr ? BAD : GOLD;
            boolean simple = (toastTag.length() == 0 && toastSub.length() == 0);

            /* 卡片尺寸：单行提示自适应宽度（最长沿用旧横幅 0.62W），带标签/小字则紧凑侧卡 */
            float bw, bh;
            if (simple) {
                pToastMain.setTextSize(W * 0.028f);
                float tw = pToastMain.measureText(toastMain);
                bw = Math.min(W * 0.62f, tw + pad * 0.9f);
                bw = Math.max(bw, W * 0.18f);
                bh = H * 0.085f;
            } else {
                bw = W * 0.30f;
                bh = H * 0.165f;   /* 三行结构(标签+大字+时段)需要更高卡片，避免时段贴底 */
            }
            float by = H * 0.175f;                 // 顶栏之下（副信息约到 0.158，留足间隙避免压字）
            float x = (W - bw) / 2f;                // 顶部居中（苹果式 Toast）

            /* 动画：淡入 → 停留 → 淡出，无位移 */
            float alpha;
            if (t < TOAST_IN_MS) {
                alpha = (float) t / TOAST_IN_MS;
            } else if (t < TOAST_IN_MS + toastHold) {
                alpha = 1f;
            } else {
                long u = t - (TOAST_IN_MS + toastHold);
                alpha = 1f - (float) u / TOAST_OUT_MS;
            }
            if (alpha <= 0.01f) return;
            int a = (int) (255f * Math.max(0f, Math.min(1f, alpha)));

            /* 背景 + 左侧强调条 + 描边 */
            pToastBg.setAlpha(a);
            card(c, pToastBg, x, by, bw, bh, W);
            pToastAccent.setColor(accent);
            pToastAccent.setAlpha(a);
            card(c, pToastAccent, x + pad * 0.25f, by + pad * 0.25f,
                 W * 0.006f, bh - pad * 0.5f, W);
            pToastEdge.setStrokeWidth(Math.max(3f, W * 0.0028f));
            pToastEdge.setColor(accent);
            pToastEdge.setAlpha(a);
            card(c, pToastEdge, x, by, bw, bh, W);

            float g = W * 0.016f;
            float tx = x + g * 2.4f;
            int base = toastErr ? BAD : INK;
            if (simple) {
                pToastMain.setTextAlign(Paint.Align.LEFT);
                pToastMain.setColor(base);
                pToastMain.setAlpha(a);
                fit(pToastMain, toastMain, bw - g * 3f, W * 0.028f);
                c.drawText(ellipsize(pToastMain, toastMain, bw - g * 3f),
                           tx, by + bh * 0.62f, pToastMain);
            } else {
                /* 三行按各自真实字体度量排布：先 fit 定字号，再按 ascent/descent 逐行算
                   baseline，整块在卡片内垂直居中。旧版标签从 g 起算、主文案用 bh*0.52，
                   主文案字号大、上沿正好压到标签行，出现叠字。 */
                pToastTag.setTextSize(W * 0.0145f);
                pToastTag.setColor(accent);
                pToastTag.setAlpha(a);
                pToastMain.setTextAlign(Paint.Align.LEFT);
                pToastMain.setColor(base);
                pToastMain.setAlpha(a);
                fit(pToastMain, toastMain, bw - g * 3f, W * 0.026f);
                pToastSub.setColor(toastErr ? BAD : INK_SOFT);
                pToastSub.setAlpha(a);
                fit(pToastSub, toastSub, bw - g * 3f, W * 0.017f);

                Paint.FontMetrics ft = pToastTag.getFontMetrics();
                Paint.FontMetrics fm = pToastMain.getFontMetrics();
                Paint.FontMetrics fs = pToastSub.getFontMetrics();
                float tagH  = ft.descent - ft.ascent;
                float mainH = fm.descent - fm.ascent;
                float subH  = fs.descent - fs.ascent;
                float gap   = Math.max(g * 0.35f, W * 0.004f);
                float total = tagH + mainH + subH + gap * 2f;
                float top   = by + Math.max(g * 0.3f, (bh - total) / 2f);
                float tagBase  = top - ft.ascent;
                float tagBottom = top + tagH;
                float mainBase = tagBottom + gap - fm.ascent;
                float mainBottom = mainBase + fm.descent;
                float subBase  = mainBottom + gap - fs.ascent;
                c.drawText(toastTag, tx, tagBase, pToastTag);
                c.drawText(ellipsize(pToastMain, toastMain, bw - g * 3f),
                           tx, mainBase, pToastMain);
                c.drawText(ellipsize(pToastSub, toastSub, bw - g * 3f),
                           tx, subBase, pToastSub);
            }
        }

        /** 居中画一行字：对齐 / 字号 / 颜色一次设好，不依赖上一处调用留下的 Paint 状态。 */
        private void centerAt(Canvas c, Paint p, String s, float x, float y,
                              float size, int color) {
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(size);
            p.setColor(color);
            c.drawText(s, x, y, p);
        }

        private void drawInterlude(Canvas c, int W, int H) {
            float cx = W / 2f;
            centerAt(c, pCur, "接  下  来", cx, H * 0.36f, W * 0.032f, INK_SOFT);
            pCur.setTextSize(W * 0.048f);
            pCur.setColor(GOLD);
            fit(pCur, interludeName, W * 0.86f, W * 0.048f);
            c.drawText(ellipsize(pCur, interludeName, W * 0.86f), cx, H * 0.53f, pCur);
            centerAt(c, pNext, "马上开始…", cx, H * 0.65f, W * 0.024f, INK_SOFT);
        }

        private void drawUsbGone(Canvas c, int W, int H) {
            float cx = W / 2f;
            card(c, pCard, W * 0.16f, H * 0.30f, W * 0.68f, H * 0.38f, W);
            centerAt(c, pCur, "U 盘 已 拔 出", cx, H * 0.45f, W * 0.042f, GOLD);
            centerAt(c, pNext, "请插回 U 盘，插回后自动接着唱", cx, H * 0.55f, W * 0.026f, INK);
        }

        private void drawNoLrc(Canvas c, int W, int H) {
            float cx = W / 2f;
            centerAt(c, pCur, "♪  正在播放  ♪", cx, H * 0.45f, W * 0.040f, INK);
            centerAt(c, pNext, "放一个和歌曲同名的 .lrc 到 U 盘即可显示歌词",
                     cx, H * 0.56f, W * 0.024f, INK_SOFT);
        }

        private void drawEmpty(Canvas c, int W, int H) {
            float cx = W / 2f;
            centerAt(c, pCur, "未找到歌曲", cx, H * 0.40f, W * 0.038f, INK);
            centerAt(c, pNext, "U 盘里放 歌名.mp3 + 歌名.lrc [+ 歌名.伴奏.mp3]",
                     cx, H * 0.52f, W * 0.024f, INK_SOFT);
            centerAt(c, pNext, "插好后按 菜单 重新扫描", cx, H * 0.59f, W * 0.024f, INK_SOFT);
        }

        /* LIVE-BEGIN */
        /* ---------- 报幕词：浮在内容区左侧的白卡 ---------- */
        private int openingUntilMs() {
            int ms = openingSecs * 1000;
            if (openingHold) return ms;
            if (cur >= 0 && cur < songs.size()) {
                Song s = songs.get(cur);
                if (s.hasLrc && !s.lines.isEmpty()) {
                    long first = firstRealMs(s);
                    if (first > 0 && first + 2000 < ms)
                        ms = (int) Math.max(6000L, first + 2000);
                }
            }
            return ms;
        }

        private void drawOpening(Canvas c, int W, int H, float pad) {
            if (!liveMode || songs.isEmpty() || showList || usbGone) return;
            if (System.currentTimeMillis() < interludeUntil) return;
            /* 报幕词卡固定在左侧，与播放态使用同一套纵向定位，避免就绪态 / 弹奏待演 /
               全部演完时位置跳动。右侧节目大字卡在 x=0.355W 起，左侧 0.345W 内无横叠风险。 */
            String name = null;
            /* 就绪态和弹奏待演都没有播放器，节目名要从节目单里取。 */
            if ((programWaiting || liveArmed) && programIdx >= 0 && programIdx < program.size())
                name = program.get(programIdx).key;
            else if (cur >= 0 && cur < songs.size()) name = songs.get(cur).name;
            if (name == null) return;
            String text = openingForName(name);
            if (text == null || text.length() == 0) return;

            long pos;
            int until;
            if (programWaiting || liveArmed) {
                /* 没有播放器（就绪态 / 弹奏类）：按进入的墙钟计时，
                   否则 position() 恒为 0，报幕词永远淡不进来。 */
                pos = System.currentTimeMillis() - programEnterAt;
                until = openingSecs * 1000;
            } else {
                pos = position();
                until = openingUntilMs();
            }
            if (pos < 0 || pos >= until) return;

            float a = 1f;
            if (pos < 350) a = pos / 350f;
            else if (pos > until - 700) a = (until - pos) / 700f;
            if (a <= 0.02f) return;
            int alpha = (int) (255 * Math.min(1f, a));

            float bw = W * 0.300f, bp = W * 0.016f;
            float titleFs = W * 0.0155f;
            float fsMax = W * 0.0185f, fsMin = W * 0.0130f, fs = fsMax;
            float topLimit = H * 0.180f;   // 顶栏副信息基线在 0.158，留足间隙；与播放态一致
            float maxH = H * 0.735f - topLimit;
            ArrayList<String> rows;
            float lh, bh;
            while (true) {
                lh = fs * 1.62f;
                pOpenText.setTextSize(fs);
                rows = openingRows(pOpenText, text, bw - bp * 2);
                bh = bp + titleFs * 1.6f + rows.size() * lh + bp * 0.7f;
                if (bh <= maxH || fs <= fsMin) break;
                fs = Math.max(fsMin, fs * 0.92f);
            }
            int cap = (int) ((maxH - bp - titleFs * 1.6f - bp * 0.7f) / lh);
            if (cap >= 1 && rows.size() > cap) {
                rows = new ArrayList<String>(rows.subList(0, cap));
                int last = rows.size() - 1;
                rows.set(last, rows.get(last) + "…");
                bh = bp + titleFs * 1.6f + rows.size() * lh + bp * 0.7f;
            }
            float bx = pad, by = H * 0.455f - bh / 2f;
            if (by < topLimit) by = topLimit;
            if (by + bh > H * 0.735f) by = H * 0.735f - bh;

            pOpenBg.setColor((alpha << 24) | 0xFFFFFF);
            card(c, pOpenBg, bx, by, bw, bh, W);

            pOpenTitle.setTextSize(titleFs);
            pOpenTitle.setTextAlign(Paint.Align.LEFT);
            pOpenTitle.setColor((alpha << 24) | 0xA06A00);
            c.drawText("报幕词", bx + bp, by + bp + titleFs, pOpenTitle);
            /* 右上角不再写「直播模式」：模式标识已经在顶栏「播放中」后面，
               这里只留报幕词标题，避免同一屏出现两处模式字样。 */

            pOpenText.setColor((alpha << 24) | 0x152238);
            float ty = by + bp + titleFs * 1.6f;
            for (int i = 0; i < rows.size(); i++)
                c.drawText(rows.get(i), bx + bp, ty + i * lh + fs, pOpenText);
        }

        /** 按像素宽度折行：先认 \n 分段，再逐字累加；做最简避头尾。结果按文本+宽度缓存。 */
        private ArrayList<String> openingRows(Paint p, String text, float maxW) {
            float fs = p.getTextSize();
            if (openRows != null && maxW == openRowsW && fs == openRowsFs && text.equals(openRowsFor))
                return openRows;
            ArrayList<String> out = new ArrayList<String>();
            for (String para : text.split("\n")) {
                int start = 0, n = para.length();
                if (n == 0) { out.add(""); continue; }
                while (start < n) {
                    int end = start + 1;
                    while (end <= n && p.measureText(para.substring(start, end)) <= maxW) end++;
                    end--;
                    if (end <= start) end = start + 1;
                    while (end < n && NO_LINE_START.indexOf(para.charAt(end)) >= 0
                           && end - start < n) end++;
                    if (end - start > 1 && NO_LINE_END.indexOf(para.charAt(end - 1)) >= 0)
                        end--;
                    out.add(para.substring(start, end));
                    start = end;
                }
            }
            openRowsFor = text;
            openRowsW = maxW;
            openRowsFs = fs;
            openRows = out;
            return out;
        }
        /* LIVE-END */
        /* ---------- 启动过渡页 ---------- */

        /** 冷启动第一屏：深底 + 金色台名 + 呼吸省略号；一首歌都没扫到就换成插盘提示。 */
        private void drawSplash(Canvas c, int W, int H) {
            c.drawRect(0, 0, W, H, pBg);
            float cx = W / 2f, cy = H * 0.46f;

            pCur.setTextAlign(Paint.Align.CENTER);
            pCur.setTextSize(W * 0.058f);
            pCur.setColor(GOLD);
            fit(pCur, appName, W * 0.86f, W * 0.058f);   // 台名过长自动缩字号，不截断
            c.drawText(appName, cx, cy, pCur);

            /* 分隔线：长度取台名实测字宽（fit 之后量，长名缩字号它也同步变短），
               两端由金到全透明渐变收进底图，不做生硬的平头切断。 */
            float lw = Math.min(pCur.measureText(appName), W * 0.86f);
            float lx0 = cx - lw / 2f, lx1 = cx + lw / 2f;
            float ly = cy + H * 0.050f, lh = Math.max(3f, H * 0.0035f);
            pGold.setColor(GOLD);
            pGold.setShader(new LinearGradient(lx0, ly, lx1, ly, new int[]{
                    GOLD & 0x00FFFFFF, GOLD, GOLD, GOLD & 0x00FFFFFF},
                    new float[]{0f, 0.22f, 0.78f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(lx0, ly, lx1, ly + lh, pGold);
            pGold.setShader(null);          // 别让渐变留下，后面还要复用这支笔

            if (waitingUsb) {
                centerAt(c, pSub, "请插入 U 盘", cx, cy + H * 0.165f, W * 0.024f, GOLD);
                centerAt(c, pSub, "插好后自动开始，不用按键",
                         cx, cy + H * 0.225f, W * 0.019f, INK_DIM);
            } else {
                /* 省略号 400ms 一格循环：扫描期间画面不呆板（主线程已让给绘制）。 */
                StringBuilder dots = new StringBuilder("启动中");
                int n = (int) ((System.currentTimeMillis() / 400L) % 4);
                for (int i = 0; i < n; i++) dots.append('·');
                centerAt(c, pSub, dots.toString(), cx, cy + H * 0.165f, W * 0.024f, INK_SOFT);
            }

            pCur.setColor(INK);
            pSub.setTextAlign(Paint.Align.LEFT);   // 还原对齐，别让顶栏继承居中
        }
    }
}
