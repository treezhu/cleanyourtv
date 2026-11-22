---
name: cleanyourtv
description: 给 Android TV / Google TV 的电视与电视盒子去广告、去预装、把原厂桌面换成仿 Apple TV 的简洁启动器（CleanYourTV 项目）。当用户要给电视清理预装应用、关闭厂商遥测与推荐行、更换电视启动器或电视桌面、恢复被禁用的应用、或用 ADB 连接电视（TV_HOST、pm disable-user、USB/网络调试、电视去广告、debloat Android TV、小米电视、海信电视、TCL 电视、电视盒子）时使用。
license: Complete terms in LICENSE.txt
---

# CleanYourTV：Android TV / Google TV 去臃肿 + 仿 Apple TV 启动器

## 概述

把 Android TV / Google TV 上的厂商遥测、广告服务、内容推荐行和预装垃圾**禁用**（`pm disable-user --user 0`，从不卸载），再装一个约 572 KB 的原生启动器接管 Home，得到类似 Apple TV 的黑底 Dock 桌面。全程无需 root、无线操作、可一键还原。

三条不可协商的原则：



1. **只禁用，不卸载** —— 所有改动都能用 `restore` 精确回滚到操作前状态。

2. **先备份再动手** —— 先存包列表与设置，并留下可执行的恢复记录。

3. **判断性操作先问用户** —— 哪些流媒体、语音助手、Chromecast、固件更新要保留，由用户勾选。

## 适用与不适用



* 适用：Android TV / Google TV（小米、TCL、海信、索尼、OnePlus、Realme、NVIDIA Shield 等，仓库收录 450 个机型）

* 不适用：Samsung Tizen、LG webOS、Roku、VIDAA、SmartCast —— 连不上 ADB，改用 [https://cleanyour.tv/not-android](https://cleanyour.tv/not-android) 让用户用遥控器关追踪

* 部分中国区机型在 `examples/<机型>/README.md` 头部注明：拒绝侧载（`INSTALL_FAILED_VERIFICATION_FAILURE`）或拒绝非厂商签名的 HOME 包 —— 这类只能做去臃肿那一半，要如实告知

## 目录与调用约定

**本 SKILL.md 所在目录就是仓库根目录。** 脚本用 `$PSScriptRoot` 和相对路径推算仓库位置，因此：



* 所有命令都在这个目录下执行、用相对路径；不要移动或拆散 `tools/`、`launcher/`、`examples/`、`themes/`、`addons/`。

* 脚本有 `.ps1` 与 `.sh` 两套等价实现：Windows 用 `.ps1`（`disable.ps1`、`restore.ps1`、`theme.ps1`、`launcher\build-and-install.ps1`），`.sh` 只用于 macOS/Linux。

* 被 ExecutionPolicy 拦住时：`pwsh -NoProfile -ExecutionPolicy Bypass -File tools\disable.ps1 ...`

## 第一步：完整读 `PROMPT.md`

`PROMPT.md`（235 行）是本项目的权威分步提示词：连接、备份、逐类禁用、启动器交接、截图验证、收尾关 ADB。它同时是**安全约束的唯一来源**（哪些包绝对不能禁用、哪些包不能启动、什么时候必须先问用户）。

执行前必须完整读完，不要凭印象简化；它点名引用的文件按需打开。

配套参考：



| 需要什么                  | 读哪个                                                              |
| --------------------- | ---------------------------------------------------------------- |
| 机型现成清单（450 个）         | `examples/<机型>/`：`debloat.txt`（包清单）、`README.md`（该机型坑位）、`tv.json` |
| 排障（黑屏、Home 抢不回来、重启异常） | `docs/TROUBLESHOOTING.md`                                        |
| 电视在另一个网络 / Tailscale  | `docs/REMOTE.md`、`tools/relay.md`                                |
| 没有 adb 二进制 / 无线调试配对   | `tools/native-adb.md`                                            |
| 主题、Dock、开关项           | `themes/README.md`                                               |
| 模块卡片（天气、Frigate 等）    | `addons/README.md`                                               |
| 整体介绍（中文）              | `README.zh-CN.md`                                                |

## 环境准备（一次性）



```
python -m venv tools\venv
tools\venv\Scripts\python.exe -m pip install -r tools\requirements.txt
```



* 依赖只有 `adb-shell`、`rsa`、`cryptography`；**不需要装 adb 二进制**，`tools/tv.py` 自身就是 ADB-over-TCP 客户端。

* `tools\venv` 缺失时 `disable.ps1` / `restore.ps1` / `theme.ps1` 会直接报错并给出上面这两行命令。

* 装启动器另需 JDK 17–22 与 Android SDK platform 34 /build-tools 34（Android Studio 的 `jbr` 会被脚本自动找到），或用 `tools\docker-build.ps1` 在 Docker 里构建（`SKIP_BUILD=1` 可跳过本机构建）。

## 核心流程

### A. 连接



1. 电视侧：设置 → 关于 → 连按「版本号」7 次 → 开发者选项 → 打开 USB 调试（有 Network/ADB debugging 一并打开）。

2. `$env:TV_HOST = '192.168.1.50'`（必须用电视真实 IP，不能是 URL 或路径）。

3. 验证：`tools\venv\Scripts\python.exe -I tools\tv.py --probe`

连不上时按 PROMPT.md 的分支处理（5555 不开放 → `adb mdns services` 查 `_adb-tls-connect._tcp` 走无线调试配对；网络被沙箱挡住 → 让用户跑 `tools\relay.py <tv ip>` 后改用 `TV_HOST=127.0.0.1`、`TV_PORT=15555`）。

### B. 备份（动手前）



```
tools\venv\Scripts\python.exe -I tools\tv.py 'pm list packages -e'
tools\venv\Scripts\python.exe -I tools\tv.py 'pm list packages -d'
tools\venv\Scripts\python.exe -I tools\tv.py 'getprop'
```

`disable.ps1` 会自行写出恢复记录（禁用前的状态），供 `restore.ps1` 精确回放；不要删它。

### C. 去臃肿



```
tools\disable.ps1 -TvHost 192.168.1.50 examples\xiaomi-mitv-axfr0\debloat.txt
```



* 有对应机型就先用现成清单；没有则按 PROMPT.md 的分类方法自行盘包（遥测 / 广告 / 内容服务 / 预装），**写成新清单再交给脚本禁用** —— 不要绕开脚本手敲 `pm disable-user`，脚本里的 setupwraith、Home、input-driver 等安全检查是护栏。

* 小批量禁用、每批之间截图；最后一批后等约 15 秒（或先 `sync`）再重启，否则包状态可能没落盘。

### D. 装启动器



```
launcher\build-and-install.ps1 -TvHost 192.168.1.50
# -StockLauncher <原厂包名>  不传则从电视自动识别
# -InputKeyService  遥控器 Input 键走启动器的输入切换器（Google TV）
# -HomeKeyService   遥控器 Home 键发 KEYCODE_ALL_APPS 的机型（小米棒等）
```

先确认电视接受侧载、且 SDK ≥ `launcher/app/build.gradle` 的 `minSdk`（26 = Android 8.0），否则只做去臃肿那一半。Dock 图标数量 / 顺序等改 `launcher/app/src/main/res/values/config.xml` 或主题 `config.json` 里的 `columns`、`dock_apps`。

### E. 验证



```
tools\venv\Scripts\python.exe -I tools\tv.py --screenshot shots\01.png DPAD_RIGHT
```

截图是 0 字节（Live TV、HDMI、Netflix 常见）时改用 `dumpsys activity activities | grep -i ResumedActivity` 判断前台；不要据此判定去臃肿失败。

### F. 收尾与日常维护



| 场景            | 命令                                                                                                                                       |
| ------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| 固件更新后发现被还原    | `tools\venv\Scripts\python.exe -I tools\check.py examples\<机型>\debloat.txt`（有漂移退出码 1）；加 `--fix` 重新应用                                     |
| 完全还原原样        | `tools\restore.ps1 -TvHost <ip> examples\<机型>\debloat.txt`（`-Stock <包名>` 可指定原厂桌面）                                                        |
| 换主题 / 移除主题    | `tools\theme.ps1 -TvHost <ip> themes\aurora` / `tools\theme.ps1 -TvHost <ip> -Reset`                                                     |
| 查 / 装模块卡片、改开关 | `$env:TV_HOST='<ip>'; tools\venv\Scripts\python.exe -I tools\manage.py list themes`（还有 `show` / `status` / `install` / `set` / `remove`） |
| 确认 ADB 已关闭    | `tools\venv\Scripts\python.exe tools\adb-closed.py <ip> [port]`                                                                          |

**做完必须关掉网络 ADB**：电视上关闭 Wireless debugging / Network ADB debugging / USB 调试并撤销授权，再用 `adb-closed.py` 确认 5555 真的拒绝连接 —— 只认工具结果，不认开关长什么样。

## 安全红线（源自 PROMPT.md 与脚本内建检查）



* **只&#x20;**`pm disable-user --user 0`**，永不卸载系统包。**

* **绝不碰 Android 核心包与 TV middleware**（permission controller、package installer、ext services/shared、`com.android.providers.*`、`systemui`、`shell`、`settings`、`android`、`com.cltv.mal`、`com.mediatek.tis` 等）。`disable.ps1` 会拒绝这类清单，**不要用&#x20;**`FORCE_*`**&#x20;环境变量绕过**：已有整机开不了机的案例（#884、#1578）。

* **setupwraith 前置检查**：禁用 `com.google.android.tungsten.setupwraith` 会删掉首次设置时保存的 Wi‑Fi，可能直接断网断 ADB —— 先 `dumpsys wifi | grep -o 'cname=[^ ]*'` 确认网络归属，必要时让用户先在设置里忘记并重连。

* **不要启动&#x20;**`factory`**&#x20;/&#x20;**`fac`**&#x20;/&#x20;**`AT`**&#x20;菜单类包**（`com.cvte.tv.hkcfac`、`com.mediatek.factorymenu.ui` 等），哪怕只读也可能翻标志位毁掉 Input/CEC 并让下次开机禁用 Play 服务；用 `dumpsys package <name>` 判断它是什么。

* **USB 供电警告**：USB 调试开启时部分机型会切断 USB 口供电（音箱、U 盘、USB 网卡会掉线）；整场一次做完，收尾提醒用户关调试。

* **偏好必须先问**：流媒体、语音助手、Chromecast/Miracast、固件更新服务、Dock 图标与顺序 —— 给勾选清单，只禁用用户没勾的。

* **小步 + 截图**：一批里可能带走关键组件，出问题无法定位。

## 交付物

按 PROMPT.md 收尾要求，完成后交给用户：



1. 备份目录（包列表、`getprop`、settings）

2. 恢复脚本（`restore.ps1` 的调用方式）

3. 构建 / 安装脚本

4. 一份说明：如何单独重新启用某个 App、怎么改 Dock 顺序和壁纸

## 许可与来源

上游仓库：[https://github.com/Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv)（许可见 `LICENSE`）。本 skill 是原仓库的本地副本，脚本逻辑未作改动；仅额外增加本 `SKILL.md` 入口文件，并略去上游的 `.github/`（CI 配置）与 `tests/`（开发者测试）。