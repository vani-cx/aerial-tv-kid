# 坚果 N1 / JMGO（型号 S700）

- Android 11（SDK 30），`MediaTek/S700/S700:11/RP1A.200720.011/1.2.31.46:userdebug/dev-keys`，固件 1.2.31.46（2024-07-18），32 位 ARM（armeabi-v7a）。
- 1920×1080，320dpi（960×540dp），单显示。投影亮度/对焦由 `com.jmgo.autokeystone`、`com.jmgo.tofautofocus`、`com.jmgo.dewarping` 负责，别动。
- 原桌面：`com.jmgo.tv/.MainActivity`。没有 `com.android.settings`，系统设置是 `com.jmgo.setting.x`（单 Activity + Fragment）。
- 安装的 flavor：`standard`。1.27-guard（debug 签名）、1.36（debug 签名）都能 `adb install -r` 原地升级。
- **2026-10-23 起装的是 1.37**：包名换成 `com.vani.aerialkid`，用自己生成的正式签名（`local/aerial-release.jks`）。这是另一个 App，旧版的锁定列表和密码不继承；旧的 `app.orchard.tv` 已 `pm disable-user` 停用（`pm enable` 可恢复）。

## HOME 接管（可用）

`cmd package set-home-activity com.vani.aerialkid/app.orchard.tv.MainActivity` 返回 `Success`，之后 `resolve-activity` 就返回 Aerial 了。重启后仍然是 Aerial，不需要无障碍守护。

（对比小米 MFTR0：那边这个命令「成功但无效」，只能靠无障碍守护。坚果这边不需要。）

## 无障碍服务开不了（2026-10-23 实测）

这是这台机器最大的坑：**第三方无障碍服务无法启用**，因此 `WatchService`（5 秒 tick 的前台计时）和 `HomeGuardService` 在这台上都跑不起来。

证据链：

1. `settings put secure enabled_accessibility_services "...com.vani.aerialkid/app.orchard.tv.parental.WatchService"` 返回 0，但回读还是只有 `com.jmgo.hippo/...JmgoKeyAccessibilityService`。
2. logcat 里是固件改了 `SettingsProvider`：

   ```
   E/SettingsProvider(  965): mutateSecureSetting disable modify enabled_accessibility_services: app.orchard.tv
   E/SettingsProvider(  965): mutateSecureSetting disable modify enabled_accessibility_services: com.android.shell
   ```

   写**同样的现值**也被拒，说明这个键被整把封了；对照组 `settings put secure test_probe_value 123` 正常，所以不是 shell 没有写权限。
3. `cmd accessibility` 只有 `set-bind-instant-service-allowed` / `call-system-action`，没有启用服务的子命令。
4. 系统里**没有无障碍设置页**：`am start -a android.settings.ACCESSIBILITY_SETTINGS` 报 `Activity not started, unable to resolve Intent`，`cmd package query-activities` 也是 `No activities found`；把 `com.jmgo.setting.x` 的 APK 拉下来搜过，没有 `ACCESSIBILITY_SETTINGS` 这个 action。
5. 固件是 `userdebug/dev-keys`，但 `adb root` 只打印 `restarting adbd as root`、重连后 `id` 仍是 `uid=2000(shell)`，`adb remount` 报 `Not running as root`，`su` 不存在（细节见下面「权限能拿到什么」）。

结论：这条路走不通，至少不刷机/不改固件的话走不通。

### 对家长控制的影响

| 功能 | 没有无障碍时 |
|---|---|
| 应用锁定（打开要方向密码、自动复锁） | 正常。拦截在 `MainActivity.launch()` 里，用不着无障碍 |
| 从 Aerial 打开的 App 的计时 | 正常，走 `FallbackClock`（同一进程里 5 秒 tick，回主屏幕结束一次） |
| 到点自动弹拦截页 | 正常。`SYSTEM_ALERT_WINDOW` 已 allow，所以后台也能 `startActivity(BlockActivity)` |
| 从别处（最近任务、厂商入口）打开的 App | 计不到时间，也拦不住 |
| Aerial 进程被杀之后的时间 | 计不到（`FallbackClock` 跟着进程走） |
| 桌面守护（从原桌面切回 Aerial） | 没有，但也不需要——Aerial 就是默认 HOME |

## 权限能拿到什么（对照 cleanyourtv 复查）

[Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) 的定位是「不 root、不刷机、不用 USB 线」精简电视，仓库里**没有任何提权方案**，它能用的权限就是 ADB shell 这一层。逐条对照这台机器：

| 它的手段 | 在这台上 |
|---|---|
| `pm grant <pkg> WRITE_SECURE_SETTINGS` | 可以（已授） |
| `appops set <pkg> SYSTEM_ALERT_WINDOW allow` / `WRITE_SETTINGS` | 可以（已授） |
| `settings put` 改屏保、布局开关 | 可以（`enabled_accessibility_services` 是唯一例外，见上一节） |
| `pm disable-user --user 0` 冻结预装 | 可以（已用 8 个） |
| `cmd package set-home-activity` | 可以（已用，重启仍生效） |
| `ServiceRepair`：应用被强停后把它自己的无障碍服务重新写回列表 | **不行**，写这个键的动作本身被固件拒，不是「列表里少了谁」的问题 |
| 用 shell 读 `/dev/input/event*` 抢遥控器按键 | 理论上可以（shell 能读），但只有 shell 用户能读，重启后 TV 上没有任何自启动手段，做不成 App 功能 |

**root 复查**：`ro.debuggable=1`、`ro.secure=1`、SELinux 是 `Permissive`；`adb root` 会打印 `restarting adbd as root`，`service.adb.root` 也确实被置成 1，但重连之后 `id` 仍是 `uid=2000(shell)`（adbd 并没有真的以 root 重启），`adb remount` 报 `Not running as root`，系统里没有 `su`、`adb shell su` 不存在。**SELinux 是 Permissive 也帮不上忙**——拦写入的是 `SettingsProvider` 里的 Java 层判断，跟 SELinux 无关。

### 唯一新拿到的权限：用量统计

```bash
adb shell appops set com.vani.aerialkid GET_USAGE_STATS allow   # 回读 GET_USAGE_STATS: allow
```

`dumpsys usagestats` 能看到系统自己记的事件流，正好是家长控制需要的两件事：

```text
type=ACTIVITY_RESUMED package=<某个视频 App> class=<某个视频 App>.MainActivity time="<时间>"
type=ACTIVITY_PAUSED  package=<某个视频 App> class=<某个视频 App>.MainActivity time="<时间>"
type=SCREEN_INTERACTIVE / type=SCREEN_NON_INTERACTIVE
```

**哪个 App 什么时候在前台、屏幕什么时候亮着，系统都记着**，跨进程、跨重启都在，不需要无障碍。这是这台机器上补回计时的可行路径。

顺带：TCL 固件用 `APP_AUTO_START` 这个 app-op 来保证无障碍服务能绑上，**这台机器没有这个 op**（`Error: Unknown operation string`），所以那条路也不适用。

## 亮度：第三方 App 改不了灯（2026-10-23 实测）

Aerial 控制中心的亮度行在这台机器上是**空转的**，写下去的值不会改变投影亮度。查证过程：

| 通道 | 结果 |
|---|---|
| `Settings.System.SCREEN_BRIGHTNESS`（Aerial 走的标准路径，也是遥控器亮度键走的） | 值会变（102→151），但 `LightsHAL` 打印 `[FIXME]Can not really set the brightness now, need to refactor the flow with apollo later!`——**驱动层根本没实现** |
| `Settings.Global.picture_backlight`（小米那套，这台也有这个键，值 55） | 写入成功、回读也是新值，但用 `shark_test 04` 读灯的实际驱动值，始终是 `R255G255B255C0`，没变 |
| `Settings.Global.picture_brightness`（50） | 同上，写 80 和写回 50 都毫无反应 |
| `am broadcast -a android.jmgo.action.brightness --ei value 5` | `Broadcast completed: result=0`，**没有接收者**（这个 action 只出现在 `com.jmgo.setting.x` 的 dex 里） |
| `shark_test 04` | 只能**读**（`getString: E_ID_DISPLAY_BRIGHTNESS(201): R255G255B255C0`），带参数也不会改 |
| 真正的存放处 | 坚果自己的 env 存储：`shark_test 65 1` 里 `"20": "display.brightness=100"`、`"19": "display.brightness_value=255,255,255,0"`、`"14": "dlpBrightness=-1"` |
| 谁在改它 | `com.jmgo.hippo` 里的 `jmgomiddle-01`（`com.jmgo.middleware.service.IJmGOSystemService`），`setBrightness` 在 hippo 的 dex 里；`dumpsys jmgomiddle-01` 有完整的厂商调试命令清单，唯独没有给第三方 App 的入口 |

结论：**亮度是坚果私有 binder，普通 App 拿不到**。想从 Aerial 调只有三条路，都不干净：

1. 反射拿 `ServiceManager.getService("jmgomiddle-01")` 直接 transact——事务码只能从 dex 里猜，猜错可能调到 DLP 固件升级之类的方法，**不建议**。
2. `Runtime.exec("shark_test ...")`——`/vendor/bin/shark_test` 是 755，SELinux 又是 Permissive，理论上能跑，但 `shark_test 14`（环境变量写入）的正确语法没试出来（写入被当成了 `W:display.brightness` 这样的键名）。
3. 全屏黑色遮罩（`SYSTEM_ALERT_WINDOW` 已经允许）——能把**画面**压暗，灯还是全功率，且遮罩会盖住所有 App。

> 试 `shark_test 14` 时往坚果的 env 里留了两个无用键（重启后编号是 `26`、`27`，内容是 `W:display.brightness=/vendor/bin/shark_test` 和 `D:W:display.brightness=...`）。它们只是多出来的字符串条目，不参与任何读取；`display.brightness` 本身没被改动（仍是 100）。env 存在 `/data` 里、只有 root 能改，所以留着不管。`shark_test 14` 的正确语法也没试出来——`W:` 前缀会被当成键名的一部分。

## 换掉自带屏保

自带屏保是 `com.jmgo.os.dream/com.jmgo.os.dream.core.service.JGDreamService`。`settings put secure` 对这个键**没有**被封，直接换成 Aerial 的 dream 服务即可，重启后仍然生效：

```bash
adb shell settings put secure screensaver_components com.vani.aerialkid/app.orchard.tv.dream.AerialDream
```

想换回去就写回 `com.jmgo.os.dream/com.jmgo.os.dream.core.service.JGDreamService`。

## 已冻结的自带应用（2026-10-23）

```bash
for p in com.jmgo.os.dream com.android.dreams.basic com.jmgo.appstore com.jmgo.browser \
         com.jmgo.music com.jmgo.helpcenter com.jmgo.imagebrowser4k com.jmgo.os.guide; do
  adb shell pm disable-user --user 0 $p
done
```

还原：把 `disable-user` 换成 `pm enable`。

**没有动**的：`com.jmgo.tv`（原桌面，当崩了之后的退路）、`com.jmgo.fallbackhome`、`com.jmgo.setting.x`（设置）、`com.jmgo.systemapi`、`com.jmgo.hippo`（坚果自己的按键无障碍，动它可能连带影响遥控器）、`com.jmgo.autokeystone` / `tofautofocus` / `dewarping`（对焦梯形校正）、`com.jmgo.player`（本地播放）、`com.jmgo.holacast.x`（投屏）、`com.jmgo.ai.voice`（语音）、`com.jmgo.update` / `remotecontrolota`（OTA）。

## 已验证（2026-10-23）

- `adb install -r app-standard-release.apk` → `Success`，版本从 `1.27-guard`（versionCode 29）升到 `1.36`（38）。
- 1.37（包名 `com.vani.aerialkid`，versionCode 39）→ `Success`；`cmd package set-home-activity com.vani.aerialkid/app.orchard.tv.MainActivity` 返回 `Success`，`resolve-activity` 返回新包；`pm disable-user app.orchard.tv` 停用旧版；屏保改指 `com.vani.aerialkid/app.orchard.tv.dream.AerialDream`；`WRITE_SECURE_SETTINGS`、`SYSTEM_ALERT_WINDOW`、`WRITE_SETTINGS`、`GET_USAGE_STATS`、通知监听 `RevivalListener` 全部重新授过。
- 设为默认桌面，`input keyevent KEYCODE_HOME` 回到 Aerial（`mResumedActivity: com.vani.aerialkid/app.orchard.tv.MainActivity`）。
- `adb reboot` 之后自动进 Aerial，屏保设置与冻结列表都还在。
- 屏幕截图（1920×1080）：天气、壁纸、玻璃底板、App 磁贴都正常渲染。
