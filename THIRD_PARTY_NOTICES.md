# 第三方组件声明

## JLayer（MP3 解码器）

- 文件：`lib/jlayer-1.0.1.4.jar`；配套系数表
  `lib/res/javazoom/jl/decoder/*.ser`（`sfd.ser` / `l3reorder.ser` / `lin2au.ser` / `au2lin.ser`）
- 版本：1.0.1.4，来源 Maven Central
  `https://repo1.maven.org/maven2/com/googlecode/soundlibs/jlayer/1.0.1.4/jlayer-1.0.1.4.jar`
- 上游项目：http://www.javazoom.net/javalayer/javalayer.html
- 许可证：**GNU Lesser General Public License v2.1 (LGPL-2.1)**，Copyright (c) 1999-2005 JavaZoom
- 本仓库**未修改源码**，仅把原始 jar 合入 `classes.dex`，并把 4 个 `.ser` 资源
  按原路径打进 APK（JLayer 运行时通过 `getResourceAsStream` 读它们）。
- 用途：部分老电视的 `MediaPlayer` 是私有独占会话，无法同时播两路；
  这里用 JLayer 纯 Java 解两路 MP3 成 PCM，混音后写入一个标准 `AudioTrack`。
- 许可全文：https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
- 需要替换或审查时，用同版本 jar 覆盖 `lib/jlayer-1.0.1.4.jar` 后跑 `./build.sh`。
  单轨兼容模式不依赖 JLayer，但当前构建脚本仍会把它打进 APK。

---

本仓库自身代码（`src/`、`build.sh`、`make_icon.swift`、`prepare_song.sh`、
`make_inst.sh`、`karaoke.sh` 等）适用仓库根目录 `LICENSE`（MIT）。
