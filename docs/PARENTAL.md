# 儿童模式与时间管理

给户主用的说明，也给接手代码的人看。实现上的取舍和已知漏洞见 [SECURITY-REVIEW.md](SECURITY-REVIEW.md)。

一句话：**锁定的 App 要用方向密码才能开；分组里的 App 有每日总时长、每次使用时长、间隔休息和允许时段，到点弹出一张全屏拦截页，页上有三个出口——数学题（5 分钟，每次只能答一次）、方向密码、管理员密码（10–50 分钟）。**

## 两个密码

| | 管理员密码 | 方向密码（使用密码） |
|---|---|---|
| 形式 | 8 位，字母和数字都必须有 | 遥控器上下左右，4–8 个方向，OK 确认 |
| 用来做什么 | 进「儿童与时间管理」、改任何规则、改或删两个密码、到点后加时长 | 只用来打开锁定的 App、答不出数学题时放行一次 |
| 怎么改 | 设置里输入旧密码后改 | **设置里输入管理员密码后直接改/删**（需求 4） |
| 存法 | PBKDF2-HMAC-SHA1，60000 轮，16 字节随机盐 | 同上（整串方向一起哈希） |
| 错几次 | 连错 5 次等 30 秒，再连错 5 次等 1 分钟，然后 2、4、8 分钟 | 同左 |

管理员密码一旦设置，Aerial 原来的 4 位家长密码（`AerialSettings`）就让位了：挡设置、挡电视自带设置 App 的都改成管理员密码（见 `HomeViewModel.whenUnlocked`、`needsParent`）。没设管理员密码时旧的行为照旧。

方向密码是**变长**的：按 4 到 8 个方向，按 OK 就算这一遍输完（不是等某个固定位数）。设置时要连输两遍，一致才保存；设置页的圆点会随着按下的个数从 4 个往下长出第 5、6 个。

两个密码都**不可能找回**：只存哈希。忘了就按下面的「恢复」清掉整个功能。

## 从哪里进

首页 → 右上角控制中心 → 个性化（或长按空白处）→ 底部「儿童与时间管理」。

第一次进去要先设管理员密码（两次输入一样、8 位、字母数字都有）。之后每次进这个面板都要管理员密码；输一次 10 分钟内不再问（`Parental.ADMIN_WINDOW_MS`），回主屏幕就清掉（`onHomePressed` → `Parental.closeAdmin()`）。

面板里从上到下：

1. **儿童模式** 开/关。关掉等于整个功能不存在：不锁、不限时、不计数。
2. **计时服务** 状态和「去开启」——跳到系统的无障碍设置页。见下面「计时」。
3. **管理员密码**：设置 / 修改 / 删除。
4. **使用密码（方向键）**：设置 / 修改 / 删除，都要求先过管理员密码。
5. **锁定的 App**：勾选。这些 App 打开时要方向密码。
6. **时间限制**：分组列表，每行是「N 个 App · 今天 X 分钟 / 上限」，有放行时会多一句「已放行，剩 N 分钟」，后面是「编辑 / 清零 / 删除」。

## 需求 1：锁定 App

入口有两个：面板里的「锁定的 App」（多选），以及**在首页长按某个磁贴 → 菜单里的「锁定应用 / 解除锁定」**——看到哪个 App 不顺眼就在那儿锁，不用绕进设置。菜单里这一项要过管理员密码（`vm.whenUnlocked`），并且只有儿童模式开着时才出现（关着的时候锁了也不生效）。

勾上的包进 `Parental.lockedPackages`。行为：

- 从 Aerial 点开 → 直接进方向密码盘（`Overlay.KidGate(lockMode = true)`），输对才启动。
- 已经在别处打开了（比如从系统的「最近任务」或厂商桌面进来）→ 无障碍服务看见前台变了，弹 `BlockActivity` 盖住。
- 输对一次这个 App 就算「这次会话已解锁」（`Parental.markAppUnlocked`），离开它（换 App、回主屏幕）就重新上锁（`onForegroundChanged` → `lockApp`）。
- 锁定和管理员密码无关：方向密码只能开 App，不能改任何规则。

## 需求 2：时间限制分组

### 数据

一个分组（`Group`）= 名字 + 一组包 + 7 条日规则（周一在前）。一条日规则（`DayRule`）有四项：

| 项 | 默认 | 0 的含义 | 可选值 |
|---|---|---|---|
| `dailyMinutes` 每日总时长 | 60 | 不限 | 30/60/90/120/180/240/不限 |
| `sessionMinutes` 每次使用时长 | 30 | 不限 | 15/20/30/45/60/90/不限 |
| `breakMinutes` 间隔休息 | 10 | 不休息 | 0/5/10/15/20/30/60 |
| `windows` 允许时段 | 空（全天） | 全天 | 每段起止，±30 分钟调，可加多段 |

`enabled` 是「这一天限制开不开」。编辑时选定一天改，改的是那一天；「每天相同」把当前这一天的规则复制到 7 天，先设好一天再微调别的天。

### 判定顺序（`Parental.verdict`）

每次判断一个包现在能不能用，按这个顺序，**第一个不通过的说了算**：

1. 功能总开关关着 → 放行。
2. 生日切了（`Usage.rollDay`）→ 今天的数字清零。
3. 这个组有家长放的时长（`passUntil`）→ 放行（只受「锁定」影响）。
4. 这一天的限制关着 → 放行。
5. 不在允许时段里 → 拦，提示还有几分钟到下一个时段。
6. 今天用够了每日总时长 → 拦（只有家长能加）。
7. 还在间隔休息里 → 拦，提示还剩几分钟。
8. 这一次连续用够了每次使用时长（加上数学题给的 5 分钟）→ 拦（可以答数学题）。
9. 否则放行。

顺序是有意的：时段最硬（到点就是不能看），每日总量其次，然后是休息，最后是单次。

### 什么算「一次使用」

- 打开组里任意一个 App = 开始一次（`Usage.beginSession`）。
- 转到组里**另一个** App 不算中断（还在同一个组）。
- 回到主屏幕、打开别的 App、App 被杀掉 = 这一次结束（`Usage.endSession`）。**撑过 60 秒的一次才算数**，才开始算休息——App 崩了、快速回主屏幕看一眼不该罚孩子。
- 一次使用里答对数学题 = 这一次多 5 分钟（`Session.extraSec`），且这一次不能再答（`quizUsed`）。这一次结束后，下一次又有一题。

### 需求 2 的拦截页

全屏、黑底、不透明，遥控器只能操作页上的东西，没有「悄悄点过去」的路径（`BlockActivity` 在无障碍服务里被显式忽略，防止它自己被当成「前台 App」）。

标题按原因不同：`App 已锁定` / `今天的分钟用完了` / `这一次的时间到了` / `休息一下` / `现在不是允许的时间`。

四个按钮，按情况和配置出现：

- **答数学题**（只在「这一次的时间到了」时出现，且这一次还没答过）：三个选项，答对 +5 分钟继续；答错换一道新题，可以一直试——这是「练习」，不是惩罚。
- **方向密码**（锁定的 App 才有）：输对了开 App。
- **请家长**（设了管理员密码才有）：输管理员密码 → 出 5 个选项 **10 / 20 / 30 / 40 / 50 分钟**（`PassPicker`，横向滚动的固定 5 项）→ 选哪个就放行多少分钟。这是「挂钟时间」，到点自动收回，期间不受每日总量、单次、休息、时段限制，但**时间照常计入今天的用量**。
- **回主屏幕**：离开（这个按钮一直在，返回键也是同样的事）。

页面每秒重新判断一次（`LaunchedEffect` 里的 1 秒循环），条件自己变好（休息结束、时段到了、家长在别处放了行）就自己关掉，不需要人按。

### 家长加时长的语义

`Usage.grantPass(groupId, minutes)` 记的是「到这个时刻为止放行」，不是「加多少分钟额度」。好处是加完之后哪怕孩子立刻回主屏幕、过 20 分钟再来，那 20 分钟也算在放行里——和家长的直觉一致，也不会因为反复进出而多送时间。多个 pass 会累加（在剩余时间上继续加）。

## 计时

这是整个功能里唯一有真实工程约束的地方。

Aerial 自己就是桌面：孩子打开爱奇艺之后，Aerial 的进程就不在前台了，靠 Aerial 自己数时间是数不到的。所以照 [tvwall](https://github.com/iBala/tvwall) 的做法，用一个**无障碍服务**（`WatchService`）：

- 只订阅 `typeWindowStateChanged`，`canRetrieveWindowContent="false"`——只要知道「前台是哪个包」，不读屏幕内容（见 `res/xml/parental_watch.xml`）。
- 每 5 秒（`TICK_MS`）把这段时间加到当前前台的组上，**只在 `PowerManager.isInteractive` 为真时加**：待机、黑屏不算。
- 两次 tick 之间超过 15 秒（`MAX_GAP_MS`）= 电视睡过去了，这一整段不算。
- 拦截页显示着的时候不计数（`BlockActivity.isShowing`）。
- 只把真正的 Activity 当成前台（`isActivity` 用 `PackageManager.getActivityInfo` 判断），弹窗、Toast 不算；Aerial 自己的窗口算「主屏幕」，拦截页除外。
- 到点由这个服务直接拉起 `BlockActivity`——无障碍服务是少数能在后台启动 Activity 的组件，这也是必须用它的原因。

**没开启无障碍服务时**还有一层降级（`FallbackClock`）：Aerial 打开组里的 App 时自己起一个同样的 5 秒计时，回主屏幕时停。它只覆盖「从 Aerial 打开、再回 Aerial」这一条路；从别处打开的 App 拦不到。设置面板里那个「计时服务：未开启」就是这个意思——**要用完整功能必须让「Aerial 儿童计时」出现在无障碍列表里**。用 `install.sh` 或照 README 授过 `WRITE_SECURE_SETTINGS` 的话，在面板里把儿童模式打开时 Aerial 会自己写进去（`GuardSetup.ensureWatch`），不用手动去系统设置；没有这个权限时才需要手动开，见下一节。服务开着的时候降级计时自动让位。（这一行的状态同时看服务的实际绑定和系统的 `enabled_accessibility_services`，所以「已开启」不会因为服务暂时没绑上而误报成「未开启」。）

### 用 ADB 开启计时服务

无障碍**不是可以用 `pm grant` 授权的权限**，只有两条路：电视上手动开，或者直接写系统设置。遥控器不方便时用后者：

```bash
# 先看现在开着哪些，多个服务用冒号分隔，别把别人的覆盖掉
adb shell settings get secure enabled_accessibility_services

# 只有这一个服务时
adb shell settings put secure enabled_accessibility_services \
  app.orchard.tv/app.orchard.tv.parental.WatchService
adb shell settings put secure accessibility_enabled 1

# 已经有别的服务时，把新的接在后面
adb shell settings put secure enabled_accessibility_services \
  "com.example.other/com.example.other.Service:app.orchard.tv/app.orchard.tv.parental.WatchService"
```

改完回设置面板看「计时服务」是不是「已开启」。**这一步只做一次**：写进的是系统的 `secure` 设置，重启、待机都不会自己丢。丢的情况只有三种——卸载重装（设置跟着包走）、恢复出厂，以及被固件「强行停止」（见下）。

### 被固件关掉之后

Android 有一条规则：应用被**强行停止**时，它的无障碍服务会从列表里删掉。小米在待机时强停所有第三方 App，所以这个列表是会掉的——这也是 tvwall 那类方案共同的软肋。

Aerial 的补法是 `GuardSetup`：`ensureEnabled()` 在开机（`BootReceiver`）、`MainActivity` 启动、通知监听复活时都会跑，发现儿童模式开着而 `WatchService` 不在列表里就写回去（`ensureWatch`），同时把 `accessibility_enabled` 置 1。所以家用场景下不用重跑 ADB 命令。`rebindIfStalled()` 另外照顾「在列表里但没绑上」的情况（TCL 升级 APK 后见过），会把服务摘掉再放回去，逼系统重新绑定。

代价要说清楚：**儿童模式开着时，Aerial 会把自己加回无障碍列表**。在系统设置里手动关掉，下次 Aerial 启动还会被恢复——这是刻意的，否则孩子按两下就能废掉时间限制。真要关，走面板里的「关」（需要管理员密码），它会同时把服务从列表里摘掉（`releaseWatch`）；或者用 `clear_kid`。

Android 13 起，侧载应用的开关在「设置 → 无障碍」里可能是灰的，要先在「应用信息」里「允许受限设置」。ADB 写设置一般不受这个限制，但个别国产固件会拦。

## 数据放在哪

| SharedPreferences | 键 | 内容 |
|---|---|---|
| `parental` | `admin_hash` `combo_hash` `combo_len` `enabled` `locked` `groups` `fails` `lockouts` `blocked_until` | 密码哈希、开关、锁定集合、分组 JSON |
| `parental_usage` | `day` `used` `pass` `sessions` `lastEnd` | 当天的秒数、放行截止时刻、进行中的一次、上次结束时刻 |

`parental_usage` 里所有东西都按**本地日期**滚动：日期变了就整体清空（`Usage.rollDay`）。所以跨零点会清零，改电视的日期也会清零——这是刻意的（见 SECURITY-REVIEW 的 K-6）。

## 恢复：忘了密码

密码只有哈希，救不回来。用电脑：

```bash
adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez clear_kid true
```

这条会：关掉儿童模式、删掉两个密码、清空锁定列表、删掉所有分组（用量一起清）。这个广播要求发送方持有 `WRITE_SECURE_SETTINGS`，普通 App 拿不到，只有 ADB shell 能发（见 `guard/ConfigReceiver.kt`）。

只想重来一天（比如电视时间被调乱了），在分组那一行按「清零」即可。

## 代码地图

```
app/src/main/java/app/orchard/tv/
├── parental/
│   ├── Parental.kt      状态 + 规则引擎：两个密码、锁定、分组、verdict()、JSON
│   ├── Secrets.kt       PBKDF2-HMAC-SHA1 60000 轮 + 随机盐，constant-time 比较
│   ├── Usage.kt         当天的用量、放行、进行中的一次、上次结束；跨天滚动
│   ├── WatchService.kt  无障碍服务：5 秒 tick、前台跟踪、拉起拦截页；FallbackClock 降级
│   └── BlockActivity.kt 独立任务的全屏拦截页宿主（FLAG_KEEP_SCREEN_ON）
├── ui/parental/
│   ├── ParentalWall.kt  拦截页：Ask / Combo / Admin / Pass / Math 五步 + 三块输入板
│   └── KidSettings.kt   设置面板：根页、App 选择、分组编辑、管理员密码与方向密码的门
├── HomeViewModel.kt     Overlay.Kid / KidGate / KidAdmin、whenUnlocked、needsParent
├── MainActivity.kt      launch() 里的拦截、Parental/Usage/FallbackClock 的 init
└── ui/wallpaper/WallpaperSheet.kt  个性化面板里的入口
```

## 真机测试清单

改动没在真机上跑过之前，下面这些至少过一遍（小米 MiTV-MFTR0 或 TCL MT5879 都行）：

1. 设管理员密码（8 位、字母数字都要有）：格式不对要给提示；两次不一样要重来。
2. 设方向密码（4 个方向 + OK），进设置面板改一次、删一次。
3. 锁定一个 App：从首页点它 → 出方向密码盘 → 输对进 App → 回主屏幕再点它 → 又要密码。
4. 建一个分组，只放一个 App，每日总时长选 30、每次 15、休息 5，今天这一天开。
5. 看满 15 分钟 → 出「这一次的时间到了」→ 答对 → 又能看 5 分钟 → 再满 → 没有数学题按钮了 → 请家长 → 选 10 分钟 → 放行。
6. 按 HOME → 等 5 分钟 → 再进那个 App → 不应该再要 15 分钟（这一次已经重新开始）。
7. 把每日总时长临时设成 5 分钟 → 看满 → 只出「请家长」和「回主屏幕」。
8. 允许时段设成 5 分钟后开始 → 现在就打不开，提示还剩几分钟；等到点再试能开。
9. 中途关掉无障碍服务 → 设置面板应该显示「未开启」，从首页打开仍然拦截（降级计时）。
10. 打开电视自带设置 App（或控制中心里那个）→ 应该先要管理员密码。
11. 待机 10 分钟再唤醒 → 用量不应该增加 10 分钟。
12. 跨零点：把日期改到明天 → 今天用的分钟数清零。
13. `clear_kid` 广播 → 全部回到初始状态。
14. 旧版升级：原来设过 4 位家长密码的电视，升级后不设管理员密码时行为不变；设了之后改成问管理员密码。
