# Aerial · 儿童模式分支

**给国产电视的一块干净桌面，加上一套家长控制。**

这个仓库是 [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv) 的个人分支，在 1.35 的基础上做了「儿童模式与时间管理」，并把原项目里两处不适合家用的东西（4 位家长密码、应用内更新检查）换掉了。

- 上游：`pengchujin/aerial-tv` 1.35（versionCode 37）
- 本分支：**1.36（versionCode 38）**
- 分支：`kid-control`（工作分支）、`main`（上游同步用）
- 状态：[x] 编译通过（`assembleStandardRelease` / `assembleGuardRelease`，Gradle 8.14.3 + JDK 17 + SDK 36） · [ ] 真机验证

> 按项目自己的规矩，**没在电视上跑过就不算完成**。真机清单在 [docs/PARENTAL.md](docs/PARENTAL.md) 末尾，共 21 条，装机时照着走一遍。



---

## 一、为什么有这个分支

上游 Aerial 解决的是「国产电视桌面全是广告和推荐流」这件事，做得很好：一块玻璃底板、一层 App 网格、航拍屏保，没有一处是运营位。

但它是按「成年人的电视」设计的。家里有小孩之后会立刻撞上三个问题：

1. **任天堂、爱奇艺、哔哩哔哩点开就能看，没有闸门**。上游只有一个 4 位家长密码，而且它只挡 Aerial 自己的设置项（电视设置 App、控制中心 Wi-Fi、控制中心设置、隐藏 App 面板、关掉它自己），**锁不住任何 App**。
2. **没有时间概念**。看多久、几点能看、看完要不要休息，全靠家长在旁边喊。
3. **自带的应用更新检查对这个分支没意义**——这个分支是自己打包装电视的，官网的版本号不是它的版本号；而且「能把 APK 交给系统安装器」本身是一条不该留在家长控制分支里的通道。

所以这个分支做了两件事：**把闸门从「挡设置」改成「挡 App + 挡时间」**，以及**把不属于家用场景的能力删掉**。

---

## 二、新增功能

### 1. 应用锁定（与儿童模式解耦）

长按任意 App 的磁贴，菜单里有「锁定应用 / 解除锁定」。

|          | 上游 1.35             | 本分支 1.36                          |
| -------- | ------------------- | --------------------------------- |
| 怎么锁      | 只能进设置面板勾选，且必须先开儿童模式 | 首页长按直接锁；面板里也能多选                   |
| 要不要开儿童模式 | 要                   | **不要**，锁定是独立的一件事                  |
| 要不要管理员密码 | 要                   | 第一次锁不需要，直接弹出设密码界面                 |
| 解锁       | 管理员密码放行 10 分钟       | **方向密码**，且只覆盖这一次                  |
| 复锁       | 按 HOME / 10 分钟到期    | 回主屏幕、切 App、**息屏待机**、进程重启，四条都会重新上锁 |
| 解除锁定     | 面板里取消勾选             | 长按 → 解除锁定 → **先验方向密码**            |

第一次锁定（还没设过应用锁定密码）会直接弹出设置界面：上面写规则（遥控器上下左右，4–8 个方向，OK 结束，连输两遍），下面**另起一行**提示「以后要修改或删除它，需要在设置中打开儿童模式」。密码要输两遍，一致才保存。

「解锁只覆盖一次」是有意设计的：不设「解锁后打开多少分钟」，因为孩子会算着时间反复进出。改成事件驱动之后，只要人离开过一次，下一次就要重新问。

### 2. 三个密码，各管一段

|       | 管理员密码                             | 应用锁定密码               | 儿童时间锁密码         |
| ----- | --------------------------------- | -------------------- | --------------- |
| 形式    | 8 位，字母和数字都必须有                     | 方向密码，4–8 个方向         | 同左              |
| 管什么   | 进设置面板、改规则、改/删两个方向密码、开电视自带设置 App   | 打开被锁定的 App、解除锁定      | 拦截页「叫爸爸妈妈来」时的验证 |
| 什么时候要 | 进面板时                              | 每次打开锁定的 App          | 每次要放行时间         |
| 何时可改  | 输入旧密码即可                           | **要开着儿童模式**          | 要开着儿童模式         |
| 存储    | PBKDF2-HMAC-SHA1，60000 轮，16 字节随机盐 | 同上（整串方向一起哈希）         | 同上              |
| 试错    | 连错 5 次等 30 秒 → 1 → 2 → 4 → 8 分钟   | 同左，**三个密码共用同一个等待计数** | 同左              |

儿童时间锁密码默认**就是**应用锁定密码那一个，一户人家只记一个方向密码；想分开可以在设置里单独设。

三把都只存哈希，**不可能找回**。忘了走「恢复」：`am broadcast --ez clear_kid true`。

### 3. 四类拦截，四种说法

拦截页是全屏黑底，遥控器只能操作页上的东西。判定顺序见 [docs/PARENTAL.md](docs/PARENTAL.md#判定顺序parentalverdict)，落到界面上是四种：

**(a) 时段外** —— 按当前时刻说话：

> 现在还太早啦  
> 电视还在休息呢，你先去玩一会儿，等一会儿再来找它。

午休（12–13 点）和太晚（下一个时段是明天）另有文案。页面上有一个按钮「答对一道数学题，就能再看一会儿」：答对给 **15 分钟，每天只有一次**，并提示「你可以再看 15 分钟，好好看吧，时间到了就让眼睛歇一歇」。

**(b) 这一次看太久了** ——

> 你已经看很久啦  
> 眼睛辛苦啦，看看窗外远处休息一下，08:42 后就能接着看。

下面是一个**按墙钟走的倒计时**：到点那一刻记下来，孩子留在页面、回主屏幕、出去转一圈再回来，倒计时都在走，不会重置。休息到点自动开新的一次，孩子不用退出 App。页面上同样有数学题，答对给 **5 分钟**，这 5 分钟**计入每日总时长**，每次休息可以答一次。

**(c) 今天的额度用完了** ——

> 今天看够啦  
> 今天已经看了很久很久了，眼睛也累了吧？我们明天再看。

**没有数学题按钮**，只剩「叫爸爸妈妈来」和「回到首页」。这是刻意的：每天的总量是底线，不能被做题绕过去。

**(d) App 被锁定** —— 直接进方向密码盘。

答错一律「你答错了，再想想。」，清空输入框，换一道新题，可以一直试——这是练习，不是惩罚。

「叫爸爸妈妈来」不再直接问管理员密码：**先问儿童时间锁密码**，验过之后才出 10/20/30/40/50 分钟五个选项。放行按墙钟算到点自动收回，期间不受时段/总量/单次/休息限制，但时间照常计入今天的用量。

### 4. 数学题按年龄出

设置里有「儿童年龄」，3–12 岁，决定题库难度：

| 年龄    | 题目                         |
| ----- | -------------------------- |
| 3–5   | 10 以内加减                    |
| 6–7   | 20 以内加减                    |
| 8–9   | 100 以内加减 + 乘法口诀            |
| 10–12 | 两位数加法、除法、两步运算（`3 × 4 + 5`） |

每次随机生成，不是固定题库（否则答案会被记住）。用遥控器数字键或屏幕键盘输入，最多 3 位。

### 5. 时间规则本身（沿用并细化）

一个分组 = 一组 App + 7 条日规则（周一在前），每条有四项：每日总时长、每次使用时长、间隔休息、允许时段。可以「每天相同」一键复制到 7 天。

判定顺序里有一条值得单独说：**每日总时长排在最前面**，所以「今天用完了」永远压过数学题和休息；而**家长放行**排在它前面，家长永远能覆盖一切。

---

## 三、相对上游删掉了什么

**1. 4 位家长密码（`ui/home/PinPad.kt`、`AerialSettings` 里的 `pinSet` / `unlock` / `relock` 等）**

它挡的四个入口现在由管理员密码挡：Aerial 设置、控制中心的 Wi-Fi 与设置、隐藏 App 面板、以及「关掉儿童模式」。旧的 `pin_hash` 在 `AerialSettings.init` 里被删掉，不会留在电视上。

**2. 应用内更新检查（`update/Updates.kt`、控制中心的升级页、FileProvider、`REQUEST_INSTALL_PACKAGES`）**

一起删掉的还有：`AndroidManifest` 里的 FileProvider 与安装权限、`res/xml/update_files.xml`、`ConfigReceiver` 的 `check_update` / `update_test` 两个 ADB 入口、`tools/install.sh` 里给 `REQUEST_INSTALL_PACKAGES` 授权的那行。

控制中心底部的版本号改成**从本机包信息读**，不再联网。少掉的不只是一次网络请求，还有一条「能把新 APK 装进电视」的通道。

升级方式变成：重新构建 → `adb install -r` 覆盖安装（签名必须一致）。

---

## 四、技术实现

### 模块地图（本分支新增/改动的部分）

```
app/src/main/java/app/orchard/tv/
├── parental/
│   ├── Parental.kt      状态 + 规则引擎：三个密码、锁定、分组、verdict()、分组 JSON
│   ├── Secrets.kt       PBKDF2-HMAC-SHA1 60000 轮 + 随机盐，constant-time 比较
│   ├── Usage.kt         当天用量、家长放行、数学题买的时间、进行中的一次、休息的墙钟
│   ├── WatchService.kt  无障碍服务：5 秒 tick、前台跟踪、拉起拦截页；FallbackClock 降级
│   └── BlockActivity.kt 独立任务的全屏拦截页宿主
├── ui/parental/
│   ├── ParentalWall.kt  拦截页：Ask / AppCombo / TimeCombo / Admin / Pass / Math / Done
│   └── KidSettings.kt   设置面板：根页、App 选择、分组编辑、三个密码的门、锁定/解锁弹窗
├── HomeViewModel.kt     Overlay.Kid / KidGate / KidAdmin / LockSetup / LockConfirm
└── MainActivity.kt      launch() 里的拦截、息屏复锁、Parental/Usage/FallbackClock 的 init
```

### 计时为什么必须用无障碍服务

Aerial 自己就是桌面：孩子打开爱奇艺之后，Aerial 的进程已经不在前台，靠它自己数时间是数不到的。

所以照 [iBala/tvwall](https://github.com/iBala/tvwall) 的做法用一个**无障碍服务**：只订阅 `typeWindowStateChanged`（`canRetrieveWindowContent="false"`，不读屏幕内容），每 5 秒把时间加到当前前台的组上，**只在亮屏时加**（待机不算），两次 tick 之间超过 15 秒就整段丢弃。到点由这个服务拉起 `BlockActivity`——无障碍服务是少数能在后台启动 Activity 的组件，这也是必须用它的原因。

**无障碍没开**时有一层降级计时（`FallbackClock`），只覆盖「从 Aerial 打开、再回 Aerial」这一条路。要完整功能必须让「Aerial 儿童计时」出现在无障碍列表里；授过 `WRITE_SECURE_SETTINGS` 的话，打开儿童模式时 Aerial 会自己写进去。

小米在待机时会强停第三方 App，强停会把无障碍服务从列表里删掉。`GuardSetup.ensureEnabled()` 在开机、`MainActivity` 启动、通知监听复活时检查并写回，所以家用场景下不用重跑 ADB 命令。

### 墙钟语义（这一版最关键的设计）

休息、家长放行、数学题买的时长，全部记「到哪个时刻为止」，而不是「还剩多少秒」。两个好处：

- 退出/进入不打断计时（需求原文要求）；
- 反复进出不会多送时间。

单次时长打到头的那一刻（`Usage.hitSession`）就把 `hitAt` 记下来当休息起点，所以「留在拦截页 / 回首页 / 换 App / 再回来」四种情况算出来的是同一个倒计时。

### 与上游同步

上游改的是桌面本身（1.35 加了内置城市表、诊断日志、动态壁纸、信号源识别等），本分支改的是家长控制。两边重叠很少，但 `AndroidManifest.xml`、`ConfigReceiver.kt`、两份 `strings.xml`、`Weather.kt` 会冲突。

1.35 那次合并是把 36 个提交并进来、7 处冲突逐个手工解决的，`Weather.kt` 直接取上游版（上游的内置城市表比本分支的 geocoder 方案更准、也能离线）。

合并之后务必搜一遍冲突标记再提交——上一次合并把一行 `>>>>>>> origin/main` 带进了 `AndroidManifest.xml`，而构建居然是成功的。

---

## 五、构建与安装

```bash
# 需要 JDK 17、Gradle 8.14.3、Android SDK 36
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME='/path/to/android-sdk'
gradle --no-daemon assembleStandardRelease assembleGuardRelease
```

- `standard`：正常的 HOME 应用，带 HOME intent-filter。
- `guard`：不带 HOME intent-filter，靠无障碍服务接管 Home，给那些拒绝安装声明 `CATEGORY_HOME` 的应用的国产固件（部分 TCL / 海信）用。

产物：`app/build/outputs/apk/standard/release/` 与 `app/build/outputs/apk/guard/release/`。

**签名**：正式的更新签名放在 `local/signing.properties`（不在版本控制里）。没有它就会退回本机的 debug 签名——能装新机，**覆盖不了电视上已有的官方签名包**，要装得先卸载（密码和数据会一起没）。

安装后要授一次权限，见 [README.md](README.md#安装)。儿童模式额外需要的是无障碍服务，不是普通权限，不能用 `pm grant` 授权：

```bash
adb shell settings get secure enabled_accessibility_services   # 先看现在开着哪些
adb shell settings put secure enabled_accessibility_services \
  app.orchard.tv/app.orchard.tv.parental.WatchService
adb shell settings put secure accessibility_enabled 1
```

这一步只做一次：写进的是系统的 `secure` 设置，重启、待机都不会丢。

---

## 六、已知限制

- **没在真机上验证过**。编译通过 ≠ 能用，21 条清单在 [docs/PARENTAL.md](docs/PARENTAL.md)。
- **挡不住有 ADB 的人**。`clear_kid` 广播能一键清掉全部家长控制；改系统时间能让当天用量归零。威胁模型是「拿遥控器的孩子」，不是「懂技术的大人」。完整分析见 [docs/SECURITY-REVIEW.md](docs/SECURITY-REVIEW.md)。
- **方向密码的组合空间小**。4 个方向只有 256 种，而且会被孩子「看」到。已用递增等待缓解，设置面板里建议别只用最短的那一个。
- **无障碍服务会被强停**。小米待机强停第三方 App 会把服务从列表里删掉，靠 `GuardSetup` 自愈，但不是 100%。
- **不是多用户系统**。只有「这台电视的一套规则」，没有孩子的独立账户。
- **不挡应用安装**。Aerial 自己不下载安装任何东西了，但它也不去拦别的 App 装应用。
- **跨零点清零**。改电视的日期也能清零用量——这是刻意的取舍。

---

## 七、参考项目

本分支大量读过、并对齐了实现思路的项目（参考实现都已 fork 到本账号下，方便对照）：

| 项目 | 用途 | 本账号 |
|---|---|---|
| [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv) | 上游本体：桌面、信号源、屏保、无障碍接管 Home 的底座 | 用 `upstream` 远程跟踪（同名仓库已是本项目，不再另开 fork） |
| [iBala/tvwall](https://github.com/iBala/tvwall) | 儿童时间限制的参考实现，`WatchService` 的 tick 与亮屏门控思路来自这里 | [vani-cx/tvwall](https://github.com/vani-cx/tvwall) |
| [spocky/miproja1](https://github.com/spocky/miproja1) | 小米电视 Projectivy 配置，机型适配与系统设置写法参考 | [vani-cx/miproja1](https://github.com/vani-cx/miproja1) |
| [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv)（原 tv-vibehack） | 各机型去广告清单与小米 MFTR0 的信号源笔记，`docs/devices` 里的机型差异参考过它 | [vani-cx/cleanyourtv](https://github.com/vani-cx/cleanyourtv) |

---

## 八、文档

| 文件                                                    | 内容                                          |
| ----------------------------------------------------- | ------------------------------------------- |
| [README.md](README.md) / [README.en.md](README.en.md) | 上游的安装与功能说明（本分支已同步改动）                        |
| [docs/PARENTAL.md](docs/PARENTAL.md)                  | **家长控制的使用说明**：三把密码、四类拦截、判定顺序、计时、恢复、21 条真机清单 |
| [docs/SECURITY-REVIEW.md](docs/SECURITY-REVIEW.md)    | 安全性审查：威胁模型、已知漏洞、撤销方案                        |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)          | 整体架构与模块地图                                   |
| [docs/HANDOFF.md](docs/HANDOFF.md)                    | 交接说明、版本历史、构建环境                              |
| [docs/devices](docs/devices)                          | 各机型的行为差异                                    |

---

## 九、许可

继承上游，MIT（见 [LICENSE](LICENSE)）。字体许可见 [FONT-LICENSE-OFL.txt](FONT-LICENSE-OFL.txt)。
