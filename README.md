<div align="center">

<img src="docs/brand/icon.png" width="96" alt="Aerial 图标">

# Aerial

**给国产电视的一块干净桌面。**

参照 tvOS 设计的 Android TV 桌面：没有广告，没有推荐流，只有你的 App、你的壁纸和 Apple TV 航拍屏保。<br>
为小米、TCL 等国产电视而做，开机自动接管，待机也不会被清理。

**中文** · [English](README.en.md)

<a href="https://aerial.qust.me"><img alt="官网" src="https://img.shields.io/badge/%E5%AE%98%E7%BD%91-1f6feb?style=for-the-badge&logo=safari&logoColor=white"></a>
<a href="https://github.com/pengchujin/aerial-tv/releases/latest"><img alt="下载 APK" src="https://img.shields.io/badge/%E4%B8%8B%E8%BD%BD%20APK-34a853?style=for-the-badge&logo=android&logoColor=white"></a>
<a href="#用-agent-安装推荐"><img alt="让 Agent 帮你装" src="https://img.shields.io/badge/%E8%AE%A9%20Agent%20%E5%B8%AE%E4%BD%A0%E8%A3%85-d97757?style=for-the-badge&logo=claude&logoColor=white"></a>
<a href="#第一步打开电视的-adb-调试"><img alt="开启 ADB" src="https://img.shields.io/badge/%E5%BC%80%E5%90%AF%20ADB-555555?style=for-the-badge&logo=gnubash&logoColor=white"></a>

<a href="https://github.com/pengchujin/aerial-tv/releases/latest"><img alt="版本" src="https://img.shields.io/github/v/release/pengchujin/aerial-tv?style=flat-square&label=%E7%89%88%E6%9C%AC&color=2997ff"></a>
<a href="LICENSE"><img alt="许可证 MIT" src="https://img.shields.io/badge/%E8%AE%B8%E5%8F%AF%E8%AF%81-MIT-555555?style=flat-square"></a>
<img alt="Android TV 5.0+" src="https://img.shields.io/badge/Android%20TV-5.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white">

![Aerial 首页](site/img/home.jpg)

| 电视自带的桌面 | 换上 Aerial 之后 |
|:---:|:---:|
| <img src="site/img/stock-xiaomi.jpg" alt="电视自带的桌面：满屏剧集推荐、会员和限免角标"> | <img src="site/img/home.jpg" alt="Aerial 首页"> |

</div>

## 功能

### 首页

<p align="center">
  <img src="site/img/focus.jpg" width="49%" alt="聚焦在 Netflix 上">
  <img src="site/img/grid.jpg" width="49%" alt="往下滚动后的应用网格">
</p>

- **时钟**：大号细体时钟压在壁纸上，壁纸底部和左侧自动压暗。往下滚动时，时钟收进状态栏。
- **应用网格**：每行 5 个，聚焦时浮起并显示名字。默认加一圈白色描边，离得远也看得清；在「控制中心 → 个性化 → 焦点样式」可以改回 tvOS 的原样。
- **图标**：有电视横幅就用横幅；方形图标用自己的底色铺满磁贴；自适应图标拆成两层做视差。
- **全部 App**：小米「应用中心」「高清播放器」「米家」这类没有标准入口的系统 App 也会显示。
- **信号源磁贴**：把 HDMI、AV、电视天线固定到首页，按一下直接切换（控制中心 → 信号源 → 显示在主屏幕）。
- **返回键**：和 tvOS 一样，在网格里按返回回到第一行，在第一行按返回开始屏保。

### 长按菜单

![长按菜单](site/img/menu.jpg)

长按 OK 键：**移动**（其他磁贴自动让位）、**从主屏幕隐藏**、**应用信息**、**卸载**。

### 控制中心

![控制中心](site/img/control-center.jpg)

在第一行按上键到右上角，再按确定打开（焦点在右上角时，也可以按菜单键 ≡）。包括信号源（显示 CEC 设备）、网络、设置，音量和亮度滑块，以及个性化（壁纸、屏保、焦点样式、天气）和隐藏的 App。网络和设置会打开电视自己的面板。在某个 App 上按菜单键，会打开这个 App 的菜单（移动、隐藏等），和长按确定键一样。

### 屏保

<p align="center">
  <img src="site/img/screensaver-aerial.jpg" width="49%" alt="航拍屏保：俯瞰优胜美地">
  <img src="site/img/screensaver-clock.jpg" width="49%" alt="时钟屏保">
</p>

- **航拍**：114 段 Apple TV 航拍影片，从 Apple 的服务器直接播放，镜头移动时显示地名。左右键切换上一段 / 下一段。
- **照片**：每日精选和你的照片缓慢推移。
- **时钟**：每分钟换一个位置，防止烧屏。

在使用 Android 标准屏保的电视（比如 TCL）上，Aerial 就是系统屏保。小米用自家的屏保，Aerial 会在桌面空闲时抢先启动，挡在小米的广告屏保前面。

### 壁纸与手机设置

<p align="center">
  <img src="site/img/wallpaper.jpg" width="74%" alt="个性化面板">
  <img src="site/img/phone.jpg" width="20%" alt="手机上的设置页">
</p>

- **壁纸**：必应每日精选（国内直连）、8 组内置渐变，或者你自己的照片。
- **用手机设置**：扫描壁纸面板上的二维码，在手机上传照片、选壁纸、换屏保。页面只在面板打开时开放，地址带随机令牌。

## 安装

不用 root，不动任何系统应用，随时能还原。电脑和电视连同一个网络，两步装好。

### 1. 打开电视的 ADB 调试

| 电视 | 怎么打开 |
|---|---|
| **小米 / Redmi** | 设置 → 关于 → 连续按 **产品型号**，进入开发者模式；再到 设置 → 账号与安全，打开 **ADB 调试** 和 **安装未知来源的应用** |
| **TCL / 雷鸟** | 设置 → 关于 → **本机信息**，快速按遥控器 **上下左右**，在底部出现的「更多」里打开 **ADB** |
| **OPPO** | 设置 → 系统 → 关于本机 → **设备型号**，按 **上上右右** 再按确认（用户反馈） |
| **其他 Android TV** | 设置 → 设备偏好设置 → 关于 → 连按 **版本号** 7 次，再在开发者选项里打开 **USB 调试** |

电视的 IP 在「设置 → 网络」里。电脑第一次连接时，电视会问是否允许调试，选 **始终允许**。

### 2. 安装（三选一）

**交给 Agent（推荐）**：把下面这段发给能在电脑上运行命令的 Agent，比如：

<img src="site/img/agents/workbuddy.svg" width="18" height="18" alt=""> WorkBuddy &nbsp; <img src="site/img/agents/doubao.svg" width="18" height="18" alt=""> 豆包电脑版 &nbsp; <img src="site/img/agents/qwen.svg" width="18" height="18" alt=""> 千问电脑版 &nbsp; <img src="site/img/agents/trae.svg" width="18" height="18" alt=""> Trae &nbsp; <img src="site/img/agents/claude-code.svg" width="18" height="18" alt=""> Claude Code &nbsp; <img src="site/img/agents/codex.svg" width="18" height="18" alt=""> Codex

```text
请帮我把开源电视桌面 Aerial 装到我的电视上，并设为开机进入的桌面。
电视 IP：<电视IP>
保持 ADB 常开：要

安装说明在这里：https://aerial.qust.me/agent.md
（打不开时用 https://raw.githubusercontent.com/pengchujin/aerial-tv/main/site/agent.md ）

请先完整读完安装说明再动手，然后在我的电脑上亲自运行命令，不要只把步骤告诉我。需要我在电视上操作时提醒我。
最后按说明里的「验收」，把那几条命令的原始输出贴给我。
```

手机 App 和网页版聊天只会把步骤告诉你，装不了。Agent 说装好了，看它贴出的最后一条输出里有没有 `app.orchard.tv`，没有就是没装上。

**一行命令（macOS / Linux）**：

```bash
curl -fsSL https://aerial.qust.me/install.sh | KEEP_ADB=1 TV=192.168.1.20 bash
```

**手动**：下载 [aerial-standard.apk](https://aerial.qust.me/download/aerial-standard.apk)（[GitHub](https://github.com/pengchujin/aerial-tv/releases/latest) 上也有），然后：

```bash
adb connect 192.168.1.20:5555
adb install -r aerial-standard.apk
adb shell pm grant app.orchard.tv android.permission.WRITE_SECURE_SETTINGS
adb shell cmd notification allow_listener app.orchard.tv/app.orchard.tv.guard.RevivalListener
adb shell appops set app.orchard.tv SYSTEM_ALERT_WINDOW allow
adb shell am start -n app.orchard.tv/.MainActivity
```

Windows 用 Agent 安装，或照[安装说明](site/agent.md)里的 Windows 步骤逐条运行。

### 还原

```bash
curl -fsSL https://aerial.qust.me/install.sh | TV=192.168.1.20 bash -s restore
```

用 Agent 装的，就对它说：

```text
请按 https://aerial.qust.me/agent.md 里「还原」部分的步骤，把我电视（IP：<电视IP>）的桌面还原成原来的，Aerial 先不卸载。请在我的电脑上亲自运行命令，最后把 adb shell "dumpsys window | grep mCurrentFocus" 的原始输出贴给我。
```

### 遇到问题

- **TCL 弹出「多屏互动：正在发起应用推送」**：TCL 不允许 ADB 直接安装，脚本改走多屏互动。按遥控器「右」再按确认，选「同意」。
- **提示固件不允许安装桌面类 App**：一行命令最后加 `-s guard`，手动安装改用 [aerial-guard.apk](https://aerial.qust.me/download/aerial-guard.apk)。
- **`KEEP_ADB=1` 是什么**：小米待机唤醒后会关掉 ADB，加上它 Aerial 会立刻重新打开。不需要就去掉。
- **会员、App 和原来的桌面还在吗**：都在。原来的桌面留在 App 列表里。开机广告由固件播放，去不掉。
- **怎么升级**：再运行一次安装命令，或让 Agent 再装一次。
- **只有手机**：有用户用甲壳虫 ADB 助手装成功过，我们没测试，命令同安装说明里的 Windows 步骤。
- **不支持**：LG webOS、三星 Tizen、华为智慧屏（鸿蒙）、Android 4.4 及更老的系统。

## 它怎么接管桌面，又怎么不被杀掉

国产电视大多只认自家桌面，待机时还会清理第三方 App。Aerial 先温和、后强硬：

1. **正常申请**：申请 HOME 角色、设为默认桌面。原生 Android TV 和 Google TV 到这一步就够了。
2. **桌面守护**：固件坚持用自家桌面时（小米、TCL 都是），由一个无障碍服务接管：官方桌面一到前台，就把 Aerial 拉回来。它只看哪个窗口到了前台，不读屏幕内容。
3. **被杀后复活**：小米待机会强制停止所有第三方 App。Aerial 注册了一个通知监听（不读任何通知），系统会在约 0.5 秒内重新绑定它，Aerial 借此复活，并用 `WRITE_SECURE_SETTINGS` 重新打开守护。
4. **开机自启**：开机时系统绑定通知监听，Aerial 随之启动。TCL 的「自启动管理」默认拦截第三方 App，安装脚本会用 `appops` 放行。
5. **ADB 常开**（可选）：小米唤醒后会关掉 ADB 调试，开了 `KEEP_ADB` 后 Aerial 会马上打开它，方便以后升级。

### ADB 授予的权限

| 权限 | 用途 |
|---|---|
| `WRITE_SECURE_SETTINGS` | 重新打开被系统关掉的守护；保持 Aerial 为系统屏保；`KEEP_ADB`；写入小米的背光值 |
| 通知监听 | 被强制停止或开机后由系统拉起，不读取任何通知 |
| 悬浮窗（`SYSTEM_ALERT_WINDOW`） | 开机后从后台打开 Aerial |
| 修改系统设置 | 用标准接口调节亮度 |
| 定位 | 只用来读取 Wi-Fi 名称 |
| TCL `APP_AUTO_START` / `APP_ASSOC_START` | 允许开机自启（只有 TCL 有） |

## 已验证的电视

| 机型 | 系统 | 状态 | 笔记 |
|---|---|---|---|
| 小米 MiTV-MFTR0 | Android 14 / HyperOS TV | 日常使用中 | [笔记](docs/devices/xiaomi-mitv-mftr0.md) |
| 小米 MiTV-MFFU0 | Android 14 / HyperOS TV | 安装、开机自启已验证 | [笔记](docs/devices/xiaomi-mitv-mffu0.md) |
| Redmi X65 2026（MiTV-MFTR0） | Android 14 / HyperOS TV | 用户验证：安装、重启后自动进入（[#3](https://github.com/pengchujin/aerial-tv/issues/3)） | 同 MiTV-MFTR0 |
| TCL MT5879 国行 | Android 11 | 日常使用中 | [笔记](docs/devices/tcl-mt5879-cn.md) |
| 索尼 XRJ-77A80J 国行（BRAVIA VH2） | Android 10 | 用户验证：安装、开机自启、待机唤醒、屏保（[#16](https://github.com/pengchujin/aerial-tv/issues/16)） | — |
| 索尼 BRAVIA VH1 国行 | Android 12 | 用户验证；亮度改为打开索尼的图像设置（[#2](https://github.com/pengchujin/aerial-tv/issues/2)） | — |
| 创维酷开 K3 Pro 90P3D Max（8R145） | Android 11 | 用户验证：开机自启（[#43](https://github.com/pengchujin/aerial-tv/issues/43)）。1.15 起不用再避免设为默认桌面 | — |
| 康佳 2861 | Android 5.1.1 | 用户验证：安装成功（[#37](https://github.com/pengchujin/aerial-tv/issues/37)） | — |
| PPTV K55U27 | Android 6 | 用户验证：能用（[#39](https://github.com/pengchujin/aerial-tv/issues/39)） | — |
| 腾讯极光盒子 7S | — | 用户验证：安装成功；有两个「设置」，可以长按隐藏一个；待机后回到原桌面，待查（[#51](https://github.com/pengchujin/aerial-tv/issues/51)） | — |
| 小米 MiTV-ASTP0 | Android 9 | 用户验证：安装、开机自启、待机唤醒；按 HOME 会先闪一下原桌面（[#48](https://github.com/pengchujin/aerial-tv/issues/48)） | — |
| 极米投影（m7642） | Android 9 | 用户验证：开机自启；看完视频按 HOME 偶尔卡住，待查（[#44](https://github.com/pengchujin/aerial-tv/issues/44)） | — |
| 雷鸟 55S595C Ultra（MT9655） | Android 11 | 能装，但 ADB 只有无线调试且拿不到授权，接管不了 HOME（[#52](https://github.com/pengchujin/aerial-tv/issues/52)） | [笔记](docs/devices/tcl-mt9655-cn.md) |
| OPPO K9 65 寸（ColorOS TV 2.4） | Android 9 / 32 位 | 用户验证：要先改包名、补桌面标记才能装，装好后开机自启、待机唤醒、屏保、天气都正常 | [笔记](docs/devices/oppo-coloros-tv.md) |

### 用户反馈的机型（没有日志，未验证）

来自微博评论（[#33](https://github.com/pengchujin/aerial-tv/issues/33)），只说装好了，没有型号细节或日志，仅供参考：小米电视 S Pro Mini LED 2026、小米电视 4A（Android 6）、小米盒子 5、小米显示器 / G Pro、红米电视、红米投影 5 Pro、索尼 A8G / X9000H / X90J、TCL 75Q10G Pro、雷鸟鹏 6SE、OPPO S1、夏普老款、当贝投影 F5、亚马逊 Fire TV。

能装但开机进不了 Aerial 的：Vidda、海信（要删掉原桌面才行，Aerial 不会这么做）、OPPO K9、雷鸟鹤 6 2025 Ultra。

欢迎[提交 Issue](https://github.com/pengchujin/aerial-tv/issues) 或 PR 补充你的机型，附上 `getprop ro.product.model` 和安装输出最好。

## 已知限制

- **需要联网**：航拍按 1080p H.264 实时播放，码率约 15–20 Mbps；每日精选也要联网。
- **HDMI 设备名**：真实名称（如「Apple TV」）需要系统权限才读得到，所以只显示「已连接」。
- **TCL 音量条**：调音量时会同时弹出 TCL 自己的音量条，这是让音量真正生效的必要条件。
- **小米屏保**：停在别的 App 里空闲时，仍然会出现小米自己的屏保。
- **网页安装**：浏览器连不上电视的网络 ADB，电视的 USB 口也不能被电脑当成设备连接，所以没法「打开网页就装」。

## 开发

需要 JDK 17、Android SDK 36。

```bash
./gradlew assembleStandardRelease assembleGuardRelease
BUILD=1 KEEP_ADB=1 TV=<电视IP> tools/install.sh      # 用本地编译的 APK 安装
```

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)：代码结构与数据流
- [docs/HANDOFF.md](docs/HANDOFF.md)：现状、调试技巧、必须遵守的规则
- [docs/devices/](docs/devices/)：各机型的实测笔记，包括 TCL 多屏互动的推送协议
- [AGENTS.md](AGENTS.md)：给 AI 代理的开发说明
- [site/](site/)：官网（纯静态，中文 `/`、英文 `/en/`）

设计上的取舍：按 960×540dp 的画布等比缩放，不同密度的电视看起来一样；电视 GPU 很弱，每帧最多一次全屏绘制，空闲时不跑动画；焦点动画用弹簧，可以中途打断；字体用 Inter（OFL 许可）。

航拍目录 `app/src/main/assets/aerials.json` 由 Apple 公开的 `resources-16.tar` 生成，只有影片地址和地名，不含影片本身。

## 许可

[MIT](LICENSE)。Inter 字体使用 [SIL Open Font License](FONT-LICENSE-OFL.txt)。

Aerial 是独立的开源项目，与 Apple、小米、TCL、索尼均无关联。航拍影片版权归 Apple 所有，由 Apple 的服务器直接播放；每日精选图片来自必应，版权归原作者所有。
