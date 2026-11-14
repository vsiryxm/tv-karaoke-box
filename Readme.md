# tv-karaoke-box

**一台老电视 + 一个 U 盘 = 离线点歌台。** Android 4.0.4（API 15）原生 APK：不联网、不用电脑，U 盘插上就能唱。单屏适老设计：大字、图标 + 文字、数字键一键直达、无二级菜单。

## 两种形态

| | 开源版（默认） | 定制版（`--flavor live`） |
|---|---|---|
| 构建 / 产物 | `./build.sh` → `karaoke.apk` | `./build.sh --flavor live` → `karaoke-live.apk` |
| 范围 | 曲库三件套 / 双音轨 / 歌词 / 点歌热度排序 / 歌单 / 适老遥控 | 上述全部 + 直播模式：节目单、报幕词、就绪态、四种提醒、下播礼花 |

隔离靠编译前剥离（`flavor.py` 处理源码 `LIVE-BEGIN/END` 标记块）+ 构建末尾自检，开源版产物不含任何直播痕迹。两版同一把调试密钥，可互相覆盖安装不丢数据。

## 快速开始

```bash
./build.sh                            # 编译开源版 karaoke.apk
./build.sh --flavor live              # 编译定制版（读本地 karaoke.conf，不入库）
TV_NET=192.168.2 TV_MODEL=<机型> ./install_live.sh   # 自动找电视、备份旧包、覆盖安装
TV_HOST=<电视IP>:5555 ./karaoke.sh install live      # 编译 + 安装 + 启动（指定 IP）
```

U 盘（建议 FAT32）放三件套，同名同目录：

```text
歌名.mp3        原唱
歌名.lrc        歌词（UTF-8 整句时间轴）
歌名.伴奏.mp3   伴奏（可选；遥控器 1 键切换，保持进度）
```

伴奏生成：`./prepare_song.sh 歌名.mp3`（Demucs AI 分离，质量好）或 `./make_inst.sh 歌名.mp3`（相位抵消，快）。完整功能清单与遥控键位见 `PRD.md`。

## 文件地图

```text
src/com/xinmin/karaoke/KaraokeActivity.java   唯一真源：全部业务逻辑（约 3400 行）
build.sh / flavor.py                          构建入口 / LIVE 标记块剥离器
karaoke.sh / install_live.sh                  快捷入口 / 自动发现电视并安装定制版
prepare_song.sh / make_inst.sh                伴奏制作（电脑端）
karaoke.conf.example                          配置模板（台名、欢迎语、直播时段等）
karaoke_openings.example.txt                  报幕词模板（定制版）
karaoke_program.example.txt                   节目单模板（定制版）
karaoke.apk                                   开源版成品（可直接安装）
docs/backup/                                  直播配置备份（有意入库）
```

私人数据（真实台名、节目单、报幕词）只在 U 盘上，不入仓库。

## 文档导航

| 文档 | 内容 |
|---|---|
| [`PRD.md`](PRD.md) | 产品需求：功能清单、交互口径、验收标准 |
| [`spec.md`](spec.md) | 技术规格：架构、数据契约、核心机制、构建系统 |
| [`Agents.md`](Agents.md) | AI agent 员工手册：硬性纪律、任务 SOP、验证方式 |

## 许可

MIT（见 `LICENSE` / `NOTICE`）。APK 零第三方运行时依赖；电脑端可选辅助工具声明见 `THIRD_PARTY_NOTICES.md`。

> AI生成
