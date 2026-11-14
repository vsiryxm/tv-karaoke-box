# spec —— 技术规格

> 面向 AI agent 的实现事实书。需求口径见 `PRD.md`，工作纪律见 `Agents.md`。改动前先读本文对应章节，避免破坏既有约束。

## 1. 技术栈与运行环境

| 项 | 值 |
|---|---|
| 目标平台 | Android 4.0.4 / API 15（minSdk = targetSdk = 15），横屏，真机 1920×1080 |
| 应用包名 | `com.xinmin.karaoke`，唯一入口 Activity `KaraokeActivity`（`launchMode=singleTop`） |
| 第三方依赖 | **零**。不链接任何 jar，只用 API 15 标准 SDK |
| 权限 | `WAKE_LOCK`（保持亮屏，`SCREEN_DIM_WAKE_LOCK` 常驻）；`RECORD_AUDIO`（仅启动时探测麦克风能力并打日志，不录音） |
| 构建链 | `javac --release 8` → `dx`（`dx.jar`）→ `aapt2`（compile + link）→ `apksigner` v1 签名。非 Gradle 项目，Android Studio 打不出此包 |
| 签名 | 仓库自带 `debug.keystore`（alias `tvcast`），仅用于两形态互相 `adb install -r` 覆盖不换签名；禁止用于正式发布 |
| UI | 单 `KView` 自绘 Canvas，约每 100ms 重绘一帧；无任何 Android 布局控件 |

## 2. 代码地图

```text
tv-karaoke-box/
├── AndroidManifest.xml            # 入口、权限、SDK 版本（版本号恒 1/1.0，不递增）
├── src/com/xinmin/karaoke/
│   └── KaraokeActivity.java       # 唯一真源，约 3400 行，全部业务逻辑
├── build.sh                       # 构建入口（两形态，见 §6）
├── flavor.py                      # 编译前 LIVE 标记块剥离器（见 §6.1）
├── karaoke.sh                     # build / install / start / push 简易入口
├── install_live.sh                # 扫网段找电视（按机型 ro.product.model 验身）→ 备份旧包 → 覆盖安装 → 验收日志
├── prepare_song.sh                # Demucs 分离生成「歌名.伴奏.mp3」，--tv 直推电视
├── make_inst.sh                   # ffmpeg 相位抵消快速生成伴奏（质量低于 Demucs）
├── make_icon.swift                # 生成 res/drawable-*/ic_launcher.png（ICON_GLYPH 可换字）
├── karaoke.conf.example           # karaoke.conf 全键注释模板（定制版专用键在其中）
├── karaoke_openings.example.txt / karaoke_program.example.txt / sample.lrc
├── res/                           # 图标 + values/strings.xml（构建期生成，不入库）
├── docs/backup/                   # 有意入库的私人配置备份（.gitignore 例外放行）
├── karaoke.apk                    # 开源版成品，**故意入库**
└── karaoke-live.apk               # 定制版成品，**不入库**
```

`KaraokeActivity.java` 内部按注释分区，自上而下：

| # | 分区 | 要点 |
|---|---|---|
| 1 | 常量与可配置项 | 配色（深蓝底 `BG=0xFF0B1120`、唯一强调色金 `GOLD=0xFFFFC94A` 等）、字号比例、LIVE 块字段 |
| 2 | 数据模型 | `Line{ms,text,endMs}`、`Song{name,path,instPath,plays,hasLrc,lines}`、`ProgramItem{type,key,songIdx,miss,skip}` |
| 3 | 界面状态字段 | `cur`/`listCursor`/`showList`/`usbGone`/`booting` 等 |
| 4 | 生命周期 | `onCreate` 注册 U 盘挂载/移除广播、获取 WakeLock、首扫 |
| 5 | 偏好与配置读取 | `loadConf/parseConf/readConfAsset`、`bumpPlay/settleCount/restoreLast`、麦克风探测 |
| 6 | 曲库扫描与 LRC 解析 | `rescan/scanSongs/applySongs/loadLrc` |
| 7 | 播放控制 | `play/stopPlayer/createPlayer/toggle/switchTrack/seek` |
| 8 | 按键与鼠标 | `onKeyDown/onListKey`（直播模式分派 `onProgramKey`） |
| 9 | 绘制辅助 | 文字自适应 `fit/fitExact`、省略号、卡片、图标 |
| 10 | KView | `onDraw` 总入口 + 各画面绘制函数、触摸/滚轮 |

## 3. 数据契约（文件格式）

### 3.1 U 盘曲库：三件套

- 扫描根：常量 `ROOTS`（`/mnt/sdcard`、`/mnt/usbdisk`、`/mnt/usb`、`/sdcard`、`/storage/sdcard0`、`/data/local/tmp/karaoke`）+ `/proc/mounts` 里文件系统含 `fat/ntfs/fuse` 且挂载点以 `/mnt/` 或 `/storage/` 开头的目录（去重）。
- 递归深度上限 5 层；跳过隐藏文件；只收 `.mp3`。
- 主歌 = 去掉扩展名的 stem；伴奏 = 同目录 `歌名.伴奏.mp3`（大小写不敏感，`INST_SUFFIX=".伴奏"`）；`LEGACY_SUFFIXES` 列出的旧分轨命名不进歌单。
- LRC 匹配同目录 `歌名.lrc`（大小写不敏感）。

### 3.2 LRC 解析（`loadLrc`）

- 编码：字节读入后 UTF-8 优先（剥 BOM），含替换符则回退 GBK。
- `[offset:]` 标签取**负值**叠加到每行时间。
- 时间标签支持 1–3 位小数（按位数解释为 1/10、1/100、1/1000 秒）；一行多标签会复制出多条 Line。
- 纯空行（带时间戳）= 上一句的「唱完」锚点，写入 `prev.endMs`（连续空行只认第一个）。
- 头部元信息清洗：最多前 8 行，命中 `LRC_META`（作词/作曲/演唱…）或 1 秒内含连字符的标题行，整段移除。
- 解析后按时间排序。

### 3.3 配置 `karaoke.conf`

- 格式：UTF-8，一行 `key=value`，`#` 注释，键名大小写不敏感（`appname` 归一为 `app_name`），值可含 `\n`。
- 优先级（运行期）：U 盘根目录 `karaoke.conf` > APK `assets/karaoke.conf` > 代码默认。
- 键表与白名单（构建期只收白名单键写进 assets）：

| 键 | 适用 | 默认值 |
|---|---|---|
| `app_name` | 两版 | `K歌台`（同时决定安装名，构建期写入 `res/values/strings.xml`） |
| `welcome` | 两版 | `欢迎来到 <台名>，` |
| `opening_secs` | live | `30` |
| `opening_hold` | live | `0`（1 = 开唱不提前收） |
| `live_start` / `live_end` | live | `18:30` / `20:00` |
| `live_start_text` / `live_break_text` / `live_warn_text` / `live_end_text` | live | 「直播开始啦 / 中场休息一下 / 还有 %d 个节目 / 直播结束啦」 |

- `live_start`/`live_end` 校验：正则严格 `HH:mm`（两位），且 end > start，不合法则 `liveEnabled=false`（全部提醒静默）。
- 开源版构建**不读本地 `karaoke.conf`**（防私人信息混入）；定制版构建读仓库根目录同名文件。

### 3.4 节目单 `karaoke_program.txt`（U 盘根目录，live 版）

- `演唱|磁盘歌文件名（不含 .mp3）` 或 `弹奏|节目名`；`#` 注释；严格行序。
- 解析时逐项对账：按 key 在已扫描曲库中回填 `songIdx`，记录 `miss`（缺歌曲/缺歌词/缺伴奏）与 `skip`（mp3 与 lrc 同时缺）。

### 3.5 报幕词 `karaoke_openings.txt`（U 盘根目录，live 版）

- `## 名字` 分节 + 多行正文拼一段；按名字取词（演唱类 key=磁盘 stem，弹奏类 key=节目名）；另支持松散匹配（去空格等归一）。

### 3.6 偏好存储（SharedPreferences）

| 文件 | 键 | 含义 |
|---|---|---|
| `karaoke_v4` | `last` | 上次唱的歌名（开机续播依据） |
| `karaoke_v4` | `program_idx` | 直播节目进度 |
| `karaoke_v4` | `program_done` | 1 = 上一场已完整走完 |
| `karaoke_v4` | `live_started_at` | 本轮开演时间戳（>0 = 已开演过） |
| `karaoke_counts` | `<歌名stem>` | 累计点歌次数 |

## 4. 核心机制

### 4.1 曲库扫描（异步，主线程应用结果）

1. `rescan()`：防重入（`scanning` 标记）；`loadConf()` 在主线程跑；后台线程只做 IO 与解析（不碰 UI 状态），结果 `handler.post` 回主线程 `applySongs()`。
2. `applySongs()`：按 `SONG_ORDER`（plays 降序 → 文件名忽略大小写升序）排序、替换列表；live 版调 `rebindProgram()` 重对账；首扫成功才关启动页（`booting=false`）并 `restoreLast()` 直接续播（不计点歌）。
3. 一首都没扫到：停在启动页（提示插盘），每 `USB_WAIT_RETRY_MS=2000`ms 自动重扫，直到 U 盘挂载。U 盘拔出：置 `usbGone` 全屏提示，播放停止；插回：重扫并续播当前歌。

### 4.2 点歌计数（结算制）

- `play(idx, startAt, autoStart, countPlay)`：仅用户主动点唱时 `countPlay=true`，挂起 `pendingCount` 并写 `lastSong`；开机恢复与直播节目单播放传 `false`。
- `onCompletion` → `settleCount()`：核对是当前歌且未被 veto（`countVetoed`，任何中途切走/重唱/换轨/seek 都置位）才 `bumpPlay`（写 `karaoke_counts`）。**会话内列表不重排**，排序下次扫描生效。
- 生成代际 token（`advanceToken`/`playerGen`）：停止播放即作废在途回调（2 秒过场、补 seek 等），防止旧实例回调串台。

### 4.3 音轨切换（`switchTrack`）

- 原唱↔伴奏是**两个独立文件**：重开 MediaPlayer，`seekTo(上次位置)`，350ms 后补一次 seek 兜底；`seekFloor` 在 800ms 内压制进度条抖动。
- **硬约束**：伴奏必须 CBR 128k / 44.1kHz / 立体声。API 15 的 MediaPlayer 遇 VBR 会静默忽略 `seekTo`，切音轨即跳位。`prepare_song.sh` / `make_inst.sh` 已按此约束产出；人工放文件同样必须遵守。
- 直播模式弹奏类状态下 `trackDisabled()` 拦截 `1`/`M`/静音键。

### 4.4 播放推进

- 自然播完 → `settleCount` → `handler.post(nextSong)` → 2 秒过场（`interludeUntil`）→ 下一首；期间任何按键作废过场 token。
- `seek`：双击 `←/→`（阈值 `DOUBLE_TAP_MS=350ms`）±`SEEK_STEP_MS=10000`；`onPause` 暂停保状态，`onResume` 恢复，`onDestroy` 释放。

### 4.5 直播模式状态机（live 版字段）

| 字段 / 偏好 | 含义 |
|---|---|
| `liveMode` | 当前处于直播模式 |
| `liveArmed` | 就绪态：已定位节目、停播、等 OK 开演 |
| `programWaiting` | 当前节目为弹奏类：停住等 OK |
| `programEnded` | 整份节目单已走完 |
| `programIdx` (+prefs `program_idx`) | 当前节目下标 |
| prefs `program_done` / `live_started_at` | 跨开机记忆（见 §3.6） |

- `#` 双击（`POUND_DOUBLE_MS=600`）→ `toggleLiveMode`。进入直播：定位 `program_idx`（已演完则归 0）→ `liveArmed=true` 就绪态。
- 就绪态按 OK → `enterProgram(idx)`：写 `live_started_at`（本场首次开演清 `program_done`）、按类型分派（弹奏→`programWaiting`，演唱→`play(songIdx, 0, true, false)` 不计点歌）、`skip` 项递归跳到 `nextPlayable`。
- 演唱完成 → `advanceProgram(withInterlude)`：下一个节目 2 秒过场进入；到末尾置 `programEnded`、写 `program_done=1`、停住。
- 报幕词：进入节目即挂词卡，`openingUntilMs()` 控制（就绪态按 `opening_secs`；开演后提前收=开唱 2 秒、至少留 6 秒；`opening_hold=1` 不提前收）。

### 4.6 四种提醒触发（`checkLiveNotice` / `checkBreakNotice` / `checkWarnNotice`）

- `liveEnabled` 为前提（时段校验通过）。按分钟粒度查 Calendar（电视本机时区），每种每天各一次（`lastLiveMinute` 去重）。
- 开播：跨过 `live_start` 且在时段内 → 未开演（`live_started_at<=0` 且未演完）则 `armFirstProgram()`（定位第 1 个节目进就绪态）；已开演只弹气泡（15s）。
- 中场：进入第 `floor(N/2)+1` 个节目时弹（5s）；下播预告：进入倒数第 2 个节目时弹（15s）。
- 下播：`>= live_end` 且 `program_done=1` → 全屏庆祝页（礼花粒子 `FW_BURSTS=16×FW_PARTS=22`，约 20 秒），任意键收起。`warn_before` 已废弃不实现。
- 全部临时提示统一走 `showToast`（右上角滑入 320ms / 停留 5s / 淡出，错误红字；直播时间提醒用 15s 档）。

### 4.7 按键总表（实现对照）

| 键 | 主屏 | 列表打开时 |
|---|---|---|
| `1` / `M`/静音 | 切原唱/伴奏（live 弹奏态禁用） | 同左（直接生效） |
| `2` | 重唱 | 同左 |
| `3` / 菜单键 | 开/收歌单（live=节目单，走 `onProgramKey`） | 收起 |
| `0` | 切歌（live=切节目，只挪位置不开演） | 直接切歌 |
| OK/ENTER/播放暂停键/空格 | 播放暂停（live 就绪态=开演，弹奏/演完=推进） | 立即唱选中项 |
| `↑↓` | 不响应（防抢键） | 选歌，到头循环 |
| `←→` | 双击 350ms 内 ±10 秒；单击无动作 | — |
| `#`（live） | 双击 600ms 切模式；单击无动作 | 同左 |
| 返回 | 2 秒内连按两次退出；`usbGone` 时单次退出 | 收起 |

## 5. 构建系统

### 5.1 flavor 隔离（隐私红线）

- 源码内所有 live 专属代码包在成对标记 `/* LIVE-BEGIN */` / `/* LIVE-END */`（独占一行，不可嵌套）中。
- `flavor.py strip` 在**编译前**整块剥离生成开源版源码（`build/src/...`），并断言剥离结果不含 BANNED 禁词（`liveMode`、`karaoke_program`、`节目单` 等）；`flavor.py check` 只验配对。
- 剥离后必须是合法 Java：**块内只允许三种写法**——早返回、覆盖赋值、`if-else` 接续后续语句。
- `build.sh` 末尾自检（第 7 步）：解包 APK 检查 `classes.dex` 字符串与 `assets/karaoke.conf` 键——开源版命中任何专属字符串/键即**构建失败**，并断言 `karaoke_counts` 等核心特征存在；定制版断言必备特征齐全。

### 5.2 build.sh 七步

1. flavor 源码（strip/check）+ 生成 `res/values/strings.xml`（台名）+ `build/assets/karaoke.conf`（白名单键）；
2. `javac --release 8`（classpath 仅 `android.jar`）；
3. `dx` 出 `classes.dex`；
4. `aapt2 compile/link`（Manifest + 图标 + assets + NOTICE）；
5. zip 合入 dex；
6. `apksigner` v1 签名 + verify（min-sdk 15）；
7. 隔离自检（见上）。

- 工具路径可用环境变量覆盖：`KARAOKE_JAVA_HOME`、`ANDROID_JAR`、`AAPT2_BIN`、`ANDROID_BUILD_TOOLS`。
- 产物：`karaoke.apk`（core）/ `karaoke-live.apk`（live）。

## 6. 辅助工具（电脑端，不入 APK）

| 脚本 | 用途 |
|---|---|
| `prepare_song.sh` | Demucs `--two-stems=vocals --other-method minus` 分离 → ffmpeg CBR 128k；校验原唱/人声/伴奏时长差 ≤0.5s；`--tv` 经临时目录传中文名并推到电视同目录 |
| `make_inst.sh` | ffmpeg 相位抵消快速伴奏（质量一般），同样强制 CBR 且校验等长 |
| `make_icon.swift` | 生成四密度启动图标（深蓝圆角 + 金字） |
| `install_live.sh` | 并发扫 `TV_NET` 网段 5555 → `ro.product.model` 验身（`TV_MODEL`）→ pull 旧包备份 → `install -r` → md5 比对 → 拉起 App 并输出崩溃计数等验收日志；适配该电视固件的 `kmem_init` 噪音 |
| `karaoke.sh` | `build / install / start / push`（`TV_HOST=<ip>:5555`，第二参数 `live` 走定制版） |

外部依赖工具（ffmpeg、Demucs、yt-dlp）见 `THIRD_PARTY_NOTICES.md`，永不打包。

## 7. 入库纪律（.gitignore 契约）

- **不入库**：`karaoke.conf`、`karaoke_program.txt`、`karaoke_openings.txt`、`karaoke-live.apk`、`build/`、`res/values/strings.xml`、`config.env`。
- **例外**：`docs/backup/` 是有意入库的配置备份（`!docs/backup/...` 放行）。
- **故意入库**：`karaoke.apk`（开源成品可直接安装）、`debug.keystore`（保覆盖安装签名一致）。

> AI生成
