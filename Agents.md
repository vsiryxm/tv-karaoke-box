# Agents —— AI agent 员工手册

> 任何 AI agent 接手本仓库前必须通读本文；需求见 `PRD.md`，实现细节见 `spec.md`，快速上手见 `Readme.md`。本文只写纪律、流程与判断标准。

## 1. 项目一句话

Android 4.0.4 老电视的离线 K 歌机：零依赖、零网络、单 Activity 自绘 UI，U 盘即曲库；开源版为公开基线，`--flavor live` 定制版追加直播演出流程。私人部署场景，交付物是可覆盖安装的 APK。

## 2. 文档分工

| 文档 | 谁读 | 内容 |
|---|---|---|
| `PRD.md` | 产品/验收 | 需求、交互口径、验收清单 |
| `spec.md` | 改码前 | 技术规格、数据契约、核心机制、构建系统 |
| `Agents.md` | 任何改动前 | 本手册：纪律、SOP、验证方式 |
| `Readme.md` | 快速上手 | 概览、快速开始、文件地图 |

## 3. 硬性纪律（违反即返工）

1. **唯一真源**：全部业务逻辑在 `src/com/xinmin/karaoke/KaraokeActivity.java`。不新建 Java 类拆分（该文件有内部分区注释，按区定位），不引入第三方库，不用 Gradle/Android Studio 改造构建。
2. **LIVE 块契约**：live 专属代码必须包在成对 `/* LIVE-BEGIN */` / `/* LIVE-END */` 中（标记独占一行、不可嵌套）；块内只允许早返回、覆盖赋值、`if-else` 接续三种写法——保证剥块后仍是合法 Java。反向同样成立：**开源版共享代码不得写进 LIVE 块**。
3. **三道关卡，全部通过才算完成**：
   - `flavor.py` 禁词检查（开源版源码不得残留 `liveMode`、`karaoke_program`、`节目单` 等专属词）；
   - `build.sh` 第 7 步自检（开源版 APK dex 与 assets 不得含直播痕迹，构建失败即未完成）；
   - 隐私检查（diff 中不得出现私人台名、真实人名、家庭信息、内网地址）。
4. **API 15 兼容**：只用 API 15 标准 SDK。Java 语法 ≤ Java 8（`javac --release 8`）。禁用 lambda / try-with-resources / 新集合 API 等（现有代码用匿名内部类，保持一致）。
5. **伴奏 CBR 不变式**：所有伴奏产物必须 CBR 128k / 44.1kHz / 立体声且与原唱等长（VBR 会使 API 15 MediaPlayer 静默丢 seek）。工具脚本已保证；人工放文件时须复核。
6. **计数语义不可破坏**：只有自然播完才 +1；开机恢复、直播节目单播放、切歌、重唱、换轨、seek 一律不计（见 `spec.md` §4.2 的 token 机制）。
7. **性能预算**：Canvas 约 100ms 一帧，低端设备。绘制函数只做纯计算 + Paint 绘制，不做 IO / 分配大对象；新动效须先想清楚每帧成本。
8. **UI 文案与交互口径**：以 `PRD.md` 为准（键位、提示文案、停留时长）。改交互先改 PRD 再改码，保持文档与现实一致。
9. **版本纪律**：`AndroidManifest.xml` 的 versionCode/versionName 恒为 1/1.0，不递增（覆盖安装靠签名一致，不靠版本号）。

## 4. 常见任务 SOP

| 任务 | 步骤 |
|---|---|
| 改通用功能（两版都有） | 在 LIVE 块外改 → `./build.sh` → 自检通过 → 装开源版真机验证 → 必要时再跑 `./build.sh --flavor live` 回归 |
| 改 live 专属功能 | 代码进 LIVE 块 → `./build.sh --flavor live` → `./install_live.sh` 装电视 → 按验收日志复核 |
| 加配置键 | 三处同步：`build.sh` 白名单（`CORE`/`LIVE` 元组与 `DEFAULTS`）、`karaoke.conf.example` 注释、`PRD.md`/`README` 相应表格；运行期在 `loadConf()` 解析 |
| 加按键 | `onKeyDown` / `onListKey` /（live）`onProgramKey` 三处口径核对；注意防误触规则（单击/双击、二次确认） |
| 改曲库规则 | `spec.md` §3.1 三件套契约不动；新增识别规则须同时更新 `PRD.md` §3.1 与 README |
| 制作曲库文件 | `./prepare_song.sh 歌名.mp3`（AI 分离）或 `./make_inst.sh`（快速）；配 `歌名.lrc`；三件套同名同目录 |
| 装机验证 | 真机：`TV_NET=<网段> TV_MODEL=<机型> ./install_live.sh`；临时：`TV_HOST=<ip>:5555 ./karaoke.sh install [live]` |
| 改图标 | `swift make_icon.swift res`（`ICON_GLYPH=字` 换字） |

## 5. 验证方式

- **无自动化测试**。质量靠三道构建关卡 + 真机黑盒验收。
- 真机验收基线：`install_live.sh` 输出「崩溃/ANR 计数 0」「装后 md5 与本地一致」「U 盘文件可读」；随后按 `PRD.md` §6 验收清单逐条操作遥控器确认。
- 日志抓取：`adb -s <ip>:5555 logcat -d | grep -i Karaoke`（定制版逐键打点，可远程排障）。
- 无法真机时至少：`./build.sh` 与 `./build.sh --flavor live` 双双零告警通过，并用 `unzip -p karaoke.apk assets/karaoke.conf` 核对键白名单。

## 6. 禁止事项

- 禁止把私人配置（台名、节目单、报幕词）写进源码、assets 或提交信息；仓库只收 `.example`。
- 禁止提交 `karaoke-live.apk`、`build/`、`res/values/strings.xml`。
- 禁止引入联网能力、新权限、新第三方依赖。
- 禁止用高版本 Android API 或为「以后升级」预留抽象——目标机型固定为 API 15 老电视。
- 禁止在开源版产物中保留任何 live 痕迹（`flavor.py`/`build.sh` 会拦，但也不要靠运气）。
- 禁止改动 `debug.keystore`（换签名 = 用户设备上覆盖安装失败）。

## 7. 变更检查清单（提交前逐条过）

1. `./build.sh` 与 `./build.sh --flavor live` 均通过（含第 7 步自检）。
2. `git diff` 无私人数据、无未声明的新依赖 / 新权限。
3. 行为变化已同步进 `PRD.md`（需求口径）与 `spec.md`（实现事实）；`README.md` 仅在影响快速上手时更新。
4. 真机或构建产物复核完成，验收日志贴出关键行。
5. 提交信息只描述「做了什么、为什么」，不掺私人信息。

> AI生成
