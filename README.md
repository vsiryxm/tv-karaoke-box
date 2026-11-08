# tv-karaoke-box

**一台老电视 + 一个 U 盘 = 离线点歌台。** 给 Android 4.0.4（API 15）电视写的原生 APK：
不联网、不用电脑、不用手机，U 盘插上就能唱，电脑可以关机。

界面是给家里长辈做的单屏适老设计：大字、图标 + 文字、数字键一键直达、没有二级菜单。

## 它能做什么

- U 盘目录本身就是歌单：按**文件名升序**加载，开机自动定位到上次那首，从头接着唱。
- 原唱 / 伴奏**分别调音量**：两路 MP3 在电视里解成 PCM 混音，不是简单切文件。
- 全屏大字歌词逐字染色，唱完一句整组向上翻滚；前奏用 5 个音符 + 两位数字倒计时提示接唱。
- 顶部跑马灯显示自定义欢迎语和「下一首」；切歌有短暂过场。
- U 盘被拔出时全屏提示，插回自动重扫。

## 快速开始

```bash
# 1. 装 APK（电视需打开 ADB / 网络调试）
adb connect <电视IP>:5555
adb install -r karaoke.apk

# 2. U 盘里建个文件夹放歌，插到电视 USB 口
# 3. 在应用列表打开刚装的 App，自动开始唱
```

`karaoke.apk` 是签好名的现成包，也可以拷进 U 盘用电视自带的文件管理器安装。
应用名、图标、欢迎语都可以改成自己的，见下面「自定义」。

不想敲命令的话，用仓库自带的入口脚本：

```bash
TV_HOST=<电视IP>:5555 ./karaoke.sh install    # 编译 + 安装 + 启动
TV_HOST=<电视IP>:5555 ./karaoke.sh start      # 只启动
TV_HOST=<电视IP>:5555 ./karaoke.sh push 朋友.mp3   # 推一首歌到 U 盘
```

## U 盘怎么放歌

曲库目录名随意，App 会递归扫描 U 盘里的 `.mp3 / .wav / .m4a / .ogg` 和同名 `.lrc`。
推荐按「`01_歌名`」加数字前缀固定顺序（中文按 Unicode 排，不是拼音）。

**双轨模式（可分别调人声与伴奏音量，推荐）**，四个文件放同一目录：

```
01_朋友.mp3            原唱母版（歌单显示、单轨兜底）
01_朋友.lrc            歌词
01_朋友.人声.mp3       导唱人声分轨
01_朋友.伴奏.mp3       无人声伴奏分轨
```

**单轨兜底**：只有 `歌名.mp3` + 同名 `.lrc` 也能唱，只是不能分别调音量；
再多放一个 `歌名.伴奏.mp3` 就能用 `1` 在原唱 / 伴奏之间切换。

LRC 用标准整句时间轴，一行可以有多个时间标签，**文件请存成 UTF-8**。
LRC 头部的 `[ti:]` `[ar:]` 等元信息会被自动过滤，不会当歌词滚出来。示例见 `sample.lrc`。

伴奏 / 分轨怎么来，见下面「制作分轨」。

## 遥控器

| 键 | 作用 |
|---|---|
| `1` | 原唱 / 伴奏切换 |
| `2` | 重唱（整首从头，不是重唱一句） |
| `3` | 切歌（下一首） |
| `4` | 打开 / 收起歌单 |
| `5` | 打开调音卡（←→ 原唱音量，↑↓ 伴奏音量，再按 5 收起） |
| `OK` | 主屏播放 / 暂停；歌单里 = 马上唱选中的歌 |
| 歌单里 `↑ ↓` | 上下选歌，到头循环；`OK` 立即开唱 |

主屏方向键**不绑功能**，避免和歌单选歌、调音卡调音量抢键。音量设置会记住，是低频操作。

## 自定义（不用求人）

| 想改什么 | 改哪里 | 要不要重编译 |
|---|---|---|
| 跑马灯欢迎语 | U 盘根目录放 `karaoke.conf`，写一行 `welcome=你的欢迎语`（见 `karaoke.conf.example`） | **不用**，重插 U 盘或重启 App 生效 |
| 应用名 | `AndroidManifest.xml` 里两处 `android:label` | 要 |
| 图标中间的字 | `make_icon.swift` 里 `NSAttributedString(string: "谢", ...)`，改完跑 `swift make_icon.swift res` | 要 |
| 图标配色 | `make_icon.swift` 顶部的 `bg` / `gold` | 要 |

`karaoke.conf` 是 UTF-8 纯文本，井号开头是注释；文件不存在或写错就回落到通用默认值
「欢迎来到 K 歌台，」，绝不影响唱歌。改文案**不需要动代码、不需要重新编译**。

## 制作分轨

`prepare_song.sh` 用 Demucs 把「歌名.mp3 + 歌名.lrc」拆成四个文件：

```bash
./prepare_song.sh 朋友.mp3                       # 单曲
./prepare_song.sh --dir /Volumes/USB/karaoke    # 整库批量
TV_HOST=<电视IP>:5555 ./prepare_song.sh --tv 朋友.mp3   # 生成后直接推到电视
```

需要 `ffmpeg` / `ffprobe` 和 Demucs（脚本会提示一次性准备命令，可用 `DEMUCS_BIN` 指定）。
只想快速凑个伴奏、不需要分别调音量，用 `make_inst.sh`（ffmpeg 相位抵消）：
`./make_inst.sh 朋友.mp3` 或 `./make_inst.sh --ai 朋友.mp3`。

两轨都固定 CBR 128k / 44.1kHz / 立体声：老电视的解码器遇到 VBR 会静默忽略 seek，
只有编码延迟一致，两路才能共用同一个播放时钟。

## 自己编译

`./build.sh` 走的是 API 15 的老构建链（Java 8 编译 → dx → aapt2 → v1 签名），
普通 Android Studio 打不出这种包。需要：JDK、`android.jar`（API 15）、`aapt2`、
以及 `dx.jar` / `apksigner.jar`。路径不在默认位置时，用环境变量覆盖：

```bash
KARAOKE_JAVA_HOME=/path/to/jdk \
ANDROID_JAR=/path/to/android.jar \
AAPT2_BIN=/path/to/aapt2 \
ANDROID_BUILD_TOOLS=/path/to/build-tools \
./build.sh
```

仓库里的 `debug.keystore` 是**公开的调试密钥**，只为让 `adb install -r` 不换签名；
别拿它做正式发布，要发布请换成自己的密钥。

## 为什么用 JLayer 混音

这台老电视的 `MediaPlayer` 走 MTK 私有独占会话：开两个实例会互相顶掉，
表现为每隔 2 秒左右 `PLAY_DONE` 重启播放。所以改成 JLayer 纯 Java 解两路 MP3，
逐样本按增益相加后写进**单个** `AudioTrack`。JLayer 是 LGPL-2.1，声明见
`THIRD_PARTY_NOTICES.md`。

## 音频接线

伴奏从电视自身扬声器或 3.5mm 耳机口输出；唱歌用的麦克风请走你自己的无线麦 /
功放链路，App 不采集、不回放人声（`RECORD_AUDIO` 权限只用于探测设备能力）。

## 兼容性与许可

在 Android 4.0.4 / API 15 / 1920×1080 的电视上真机验证；别的设备不保证，
但代码只用了 API 15 的标准接口，移植到较新 Android 通常不难。

本仓库自身代码为 MIT（见 `LICENSE`）；第三方组件见 `THIRD_PARTY_NOTICES.md`。
仓库内不含任何特定家庭、地点或真实网络地址信息。
