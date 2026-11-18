# 第三方声明

APK 本身**零第三方运行时依赖**：只用 Android 4.0.4 / API 15 的系统 API，
由 `javac` + `dx` + `aapt2` + `apksigner` 直接产出，没有链接任何 jar。
项目源码与构建脚本采用 MIT 许可，见 `LICENSE` / `NOTICE`。

下面这些只是**在电脑上制作伴奏 / 整理曲库时可能用到的外部工具**，
不会打包进 APK，也不需要在电视上安装：

| 工具 | 用途 | 许可 |
| --- | --- | --- |
| ffmpeg | 转码、探测时长、生成 CBR 128k / 44.1kHz / stereo 伴奏 | LGPL / GPL（按你安装的构建而定） |
| Demucs | `prepare_song.sh` 里做人声 / 伴奏分离 | MIT |
| yt-dlp | 从公开视频页面采集音频（可选） | Unlicense |

`debug.keystore` 是仓库自带的公开调试签名，只为了方便 `adb install -r`
不换签名；正式分发请换成自己的密钥。

早期版本曾用 JLayer（LGPL 2.1）做双 PCM 混音。v5 起改为单 `MediaPlayer`
在原唱 / 伴奏两个文件间切换，JLayer 的 jar、系数表与 `MixEngine.java`
已全部移除，本仓库不再分发任何 LGPL 组件。
