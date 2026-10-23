# Aerial Kid · 自用儿童模式分支

> ## ⚠️ 先说清楚这是什么
>
> - 这是**个人自用分支**，代码在与 AI 协作下修改，**不是官方版本**，也不打算跟上游保持功能对等。
> - 只针对**本人的设备**调过：**坚果 N1 投影仪**。
> - **不保证在其他电视、投影仪上可用**。分辨率、遥控器按键、厂商固件（尤其是无障碍与 HOME 接管）的差异都可能让功能失效或表现异常，装之前请先看 [六、已知限制](#六已知限制)。
> - 出问题请自己承担后果，也不要去找上游作者 —— 需要稳定就装 [上游正式版](https://github.com/pengchujin/aerial-tv)。

给 Aerial 加上一套家长控制：**应用锁定 + 儿童时间锁 + 分龄数学题**，并把原版里两处不适合家用自建的东西（4 位家长密码、应用内更新检查）换掉。

| 项 | 值 |
|---|---|
| 仓库 | `aerial-tv-kid`（`kid-control` 分支是默认分支，`main` 只作上游镜像） |
| 上游 | [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv) 1.35（versionCode 37） |
| 本分支 | **1.39（versionCode 41）** |
| 目标设备 | 坚果 N1 投影仪（个人使用环境） |
| 编译状态 | [x] `assembleStandardRelease` / `assembleGuardRelease` 通过（Gradle 8.14.3 + JDK 17 + SDK 36） |
| 真机状态 | [ ] 未在真机上完整验证，[docs/PARENTAL.md](docs/PARENTAL.md) 末尾有 21 条清单 |

---

## 一、为什么有这个分支

上游 Aerial 解决的是「国产电视桌面全是广告和推荐流」这件事，做得很好：一块玻璃底板、一层 App 网格、航拍屏保，没有一处是运营位。

但它是按「成年人的电视」设计的。家里有小孩之后会立刻撞上三个问题：

1. **爱奇艺、哔哩哔哩点开就能看，没有闸门**。上游只有一个 4 位家长密码，而且它只挡 Aerial 自己的设置项（电视设置 App、控制中心 Wi-Fi、控制中心设置、隐藏 App 面板、关掉它自己），**锁不住任何 App**。
2. **没有时间概念**。看多久、几点能看、看完要不要休息，全靠家长在旁边喊。
3. **自带的应用更新检查对这个分支没意义**——自己打包装机，官网的版本号不是它的版本号；而且「能把 APK 交给系统安装器」本身是一条不该留在家长控制分支里的通道。

所以这个分支做了两件事：**把闸门从「挡设置」改成「挡 App + 挡时间」**，以及**把不属于家用场景的能力删掉**。

---

## 二、功能描述

### 2.1 桌面部分（继承上游，基本没动）

- **首页**：常用的一排 App 放在玻璃底板上，往下是全部 App；系统里藏起来的应用（小米「应用中心」「米家」这类）也会列出来。
- **整理**：长按 OK 或菜单键，可以移动、放进文件夹、隐藏、卸载；固定到主屏幕的 HDMI 可以选「接的是 Apple TV / PlayStation / Switch / Xbox / 电脑」并换成对应图标。
- **控制中心**：第一排按上键打开，有信号源、网络、设置、音量、亮度、个性化。
- **屏保**：Apple TV 的 114 段航拍影片，支持时播 4K / 杜比视界，也可以换照片或时钟。
- **壁纸**：必应每日精选、14 张内置壁纸、手机扫码传图，或「航拍在主屏幕后面播」。
- **个性化**：单排/双排、时钟大小与位置、是否显示 App 名称、焦点样式、按键声音。

> 这些都由上游实现，本分支**没有改动视觉与交互**。控制中心的「版本号 / 更新」是唯一的例外，见 [三、修改描述](#三修改描述)。

### 2.2 家长控制部分（本分支新增）

一句话：**锁定的 App 要用方向密码才开；分组里的 App 有每日总时长、每次时长、间隔休息和允许时段，到点弹一张全屏拦截页，页上能答数学题换时长，也能叫家长来放行。**

#### (1) 应用锁定

长按任意 App 的磁贴，菜单里有「锁定应用 / 解除锁定」。

| 项 | 上游 1.35 | 本分支 1.36 |
|---|---|---|
| 怎么锁 | 只能进设置面板勾选，且必须先开儿童模式 | 首页长按直接锁；面板里也能多选 |
| 要不要开儿童模式 | 要 | **不要**，锁定是独立的一件事 |
| 要不要管理员密码 | 要 | 第一次锁不需要，直接弹出设密码界面 |
| 解锁 | 管理员密码放行 10 分钟 | **方向密码**，且只覆盖这一次 |
| 复锁 | 按 HOME / 10 分钟到期 | 回主屏幕、切 App、**息屏待机**、进程重启，四条都会重新上锁 |
| 解除锁定 | 面板里取消勾选 | 长按 → 解除锁定 → **先验方向密码** |

第一次锁定时（还没设过应用锁定密码）会直接弹设置界面：上面写规则（遥控器上下左右，4–8 个方向，OK 结束，连输两遍），下面**另起一行**提示「以后要修改或删除它，需要在设置中打开儿童模式」。密码两遍一致才保存。

「解锁只覆盖一次」是刻意的：不设「解锁后多少分钟」，因为孩子会算着时间反复进出；改成事件驱动之后，人离开过一次，下一次就要重新问。

#### (2) 三个密码，各管一段

|  | 管理员密码 | 应用锁定密码 | 儿童时间锁密码 |
|---|---|---|---|
| 形式 | 8 位，字母和数字都必须有 | 方向密码，4–8 个方向 | 同左 |
| 管什么 | 进设置面板、改规则、改/删两个方向密码、开电视自带设置 App | 打开被锁定的 App、解除锁定 | 拦截页「叫爸爸妈妈来」时的验证 |
| 什么时候要 | 进面板时（验证一次 10 分钟内不再问） | 每次打开锁定的 App | 每次要放行时间 |
| 何时可改 | 输入旧密码即可 | **要开着儿童模式** | 要开着儿童模式 |
| 存储 | PBKDF2-HMAC-SHA1，60000 轮，16 字节随机盐 | 同上（整串方向一起哈希） | 同上 |
| 试错 | 连错 5 次等 30 秒 → 1 → 2 → 4 → 8 分钟 | 同左，**三个密码共用同一个等待计数** | 同左 |

儿童时间锁密码默认**就是**应用锁定密码那一个，只记一个方向密码就够；想分开可以在设置里单独设。

三把都只存哈希，**不可能找回**。忘了走「恢复」：`am broadcast --ez clear_kid true`。

#### (3) 四类拦截，四种说法

拦截页全屏黑底，遥控器只能操作页上的东西。判定顺序见 [docs/PARENTAL.md](docs/PARENTAL.md#判定顺序parentalverdict)，落到界面上是四种：

**(a) 时段外** —— 按当前时刻说话：

> 现在还太早啦
> 电视还在休息呢，你先去玩一会儿，等一会儿再来找它。

午休（12–13 点）和太晚（下一个时段是明天）另有文案。页面上有按钮「答对一道数学题，就能再看一会儿」：答对给 **15 分钟，每天只有一次**，并提示「你可以再看 15 分钟，好好看吧，时间到了就让眼睛歇一歇」。

**(b) 这一次看太久了** ——

> 你已经看很久啦
> 眼睛辛苦啦，看看窗外远处休息一下，08:42 后就能接着看。

下面是一个**按墙钟走的倒计时**：起算那一刻被记下来，孩子留在页面、回主屏幕、出去转一圈再回来，倒计时都在走，不会重置；休息到点自动开新的一次，不用退出 App。页面上同样有数学题，答对给 **5 分钟**，这 5 分钟**计入每日总时长**，每次休息可以答一次。

**(c) 今天的额度用完了** ——

> 今天看够啦
> 今天已经看了很久很久了，眼睛也累了吧？我们明天再看。

**没有数学题按钮**，只剩「叫爸爸妈妈来」和「回到首页」。每天的总量是底线，不能被做题绕过去。

**(d) App 被锁定** —— 直接进方向密码盘。

答错一律「你答错了，再想想。」，清空输入框、换一道新题，可以一直试——这是练习，不是惩罚。

「叫爸爸妈妈来」不再直接问管理员密码：**先问儿童时间锁密码**，验过之后才出 10/20/30/40/50 分钟五个选项。放行按墙钟算、到点自动收回，期间不受时段/总量/单次/休息限制，但时间照常计入今天的用量。

#### (4) 数学题按年龄出

设置里有「儿童年龄」（3–12 岁），决定题库难度：

| 年龄 | 题目 |
|---|---|
| 3–5 | 10 以内加减 |
| 6–7 | 20 以内加减 |
| 8–9 | 100 以内加减 + 乘法口诀 |
| 10–12 | 两位数加法、除法、两步运算（`3 × 4 + 5`） |

每次随机生成，不是固定题库（否则答案会被记住）。遥控器数字键或屏幕键盘输入，最多 3 位。

#### (5) 时间规则

一个分组 = 一组 App + 7 条日规则（周一在前），每条四项：每日总时长、每次使用时长、间隔休息、允许时段，可以「每天相同」一键复制到 7 天。

判定顺序里有一条值得单独说：**每日总时长排在最前面**，所以「今天用完了」永远压过数学题和休息；而**家长放行**排在它前面，家长永远能覆盖一切。

---

## 三、修改描述

相对上游 1.35（合并基 `c9d26f8`）的整体差异：**32 个文件，+4694 / −774 行**。

### 3.1 新增的模块

| 文件 | 行数 | 作用 |
|---|---|---|
| `parental/Parental.kt` | 新增 | 状态 + 规则引擎：三把密码、锁定集合、分组、`verdict()`、分组 JSON |
| `parental/Secrets.kt` | 新增 | PBKDF2-HMAC-SHA1 60000 轮 + 随机盐、constant-time 比较 |
| `parental/Usage.kt` | 新增 | 当天用量、家长放行、数学题买的时间、进行中的一次、休息的墙钟 |
| `parental/WatchService.kt` | 新增 | 无障碍服务：5 秒 tick、前台跟踪、拉起拦截页；另有 `FallbackClock` 降级 |
| `parental/BlockActivity.kt` | 新增 | 独立任务的全屏拦截页宿主 |
| `ui/parental/ParentalWall.kt` | 新增 | 拦截页与题库（Ask / AppCombo / TimeCombo / Admin / Pass / Math / Done） |
| `ui/parental/KidSettings.kt` | 新增 | 设置面板：根页、App 选择、分组编辑、三道密码门、锁定/解锁弹窗 |
| `res/xml/parental_watch.xml` | 新增 | 无障碍服务声明（只看窗口标题，不读内容） |
| `docs/PARENTAL.md`、`docs/SECURITY-REVIEW.md` | 新增 | 使用说明与安全审查 |

### 3.2 删掉的东西

| 删了什么 | 文件 | 原因 |
|---|---|---|
| 4 位家长密码 | `ui/home/PinPad.kt` 整文件（186 行）、`AerialSettings` 的 `pinSet`/`unlock`/`relock`/`hash`、`HomeViewModel.Pin`/`PinMode`、`WallpaperSheet` 的密码行 | 它只挡设置项、锁不住 App，且与新的三把密码职责重叠。原来的四个入口改由**管理员密码**挡；旧键 `pin_hash` 在 `AerialSettings.init` 里删掉 |
| 应用内更新检查 | `update/Updates.kt` 整文件（312 行）、`res/xml/update_files.xml`、`ControlCenter` 的升级页（98 行改动）、`AndroidManifest` 的 FileProvider 与 `REQUEST_INSTALL_PACKAGES`、`ConfigReceiver` 的 `check_update`/`update_test`、`tools/install.sh` 里给安装权限授权的那行 | 自己打包装机，官网版本号无意义；也顺手拿掉一条「能把 APK 装进电视」的通道 |

控制中心底部的版本号改成**从本机包信息读**（`Context.packageManager`），不再联网。

### 3.3 行为改动

| 位置 | 改了什么 |
|---|---|
| `Parental.verdict()` | 重排判定顺序：应用锁定提到 `if (!enabled) return` 之前（锁定不再依赖儿童模式）；`Reason` 从 `Session/Window/Daily` 改成 `Daily/Window/Break`；每日总量压过数学题 |
| `MainActivity.launch()` | 打开 App 前先问 `Parental.verdict()`，非 `Allow` 就进拦截页；只有属于分组时才启动计时 |
| `MainActivity` 的广播 | 同一个 receiver 监听 `ACTION_SCREEN_ON` 与 `ACTION_SCREEN_OFF`，息屏即 `Parental.lockAll()` |
| `HomeScreen` 长按菜单 | 加「锁定应用 / 解除锁定」，锁定不需要儿童模式；解除锁定先验方向密码 |
| `HomeViewModel` | 覆盖层新增 `LockSetup`（首次锁 → 设密码）、`LockConfirm`（解除锁 → 验密码）；`whenUnlocked()` 收敛成只认管理员密码 |
| `Usage` | 休息改成墙钟语义：`hitSession()` 在打满那一刻记 `hitAt` 作为休息起点，`restLeftSec()` 到期自动开新 session；新增 `quizPassUntil` / `restQuizAt` / `restFromCap` |
| `ParentalWall` | 七步流程；四类拦截文案；数学题按 `newQuestion(age)` 四档；答案框 3 位；答对先给 2.6 秒提醒再放行 |
| `KidSettings` | 根页三行新内容：应用锁定密码、儿童时间锁密码（可选与前者共用）、儿童年龄；未开儿童模式时「修改/删除」按钮置灰并说明原因 |

### 3.4 资源与工程

| 项 | 改动 |
|---|---|
| `values/strings.xml`、`values-zh/strings.xml` | 各 +33 条、−12 条旧键（`cc_update*`、`pin_*`、`kid_wall_session/window*` 等），中英一一对应 |
| `app/build.gradle.kts` | `versionCode 37 → 38`、`versionName 1.35 → 1.36`；1.37 再改 `applicationId = com.vani.aerialkid`、`versionCode 39`、`versionName 1.37`；1.38 是 `versionCode 40`，1.39 是 `versionCode 41` |
| `AndroidManifest.xml` | 去掉 FileProvider、`REQUEST_INSTALL_PACKAGES`；保留 `BlockActivity`（`exported=false`、`singleInstance`、`excludeFromRecents`、独立 `taskAffinity`） |
| `tools/install.sh` | 去掉 `appops set ... REQUEST_INSTALL_PACKAGES allow` |
| `docs/ARCHITECTURE.md`、`docs/HANDOFF.md`、`README.md`、`README.en.md` | 同步删掉 `update/`、`PinPad.kt` 条目，改写「升级」章节；README 顶部加了这个分支的说明 |

### 3.5 版本历史

| 提交 | 内容 |
|---|---|
| `1610ac3` | 第一版家长控制（应用锁定 + 时间限制，两个方向密码） |
| `84dfe49` | 合并上游 1.35（36 个提交，7 处冲突手工解决） |
| `70d438c` | 1.36：删 4 位密码与更新检查，锁定解耦，四类拦截，分龄数学题 |
| `ec26474` | 加 `PROJECT.md`、两份 README 的分支说明 |
| 1.37 | 改包名 `com.vani.aerialkid` + 正式签名；密码输入改成圆点；家长放行期间退出 App 延迟 1 分钟再上锁 |
| 1.39 | 修数学题的两个问题：遥控器 OK 键被拦截页的根节点抢走，按数字直接判错、数字进不了答案框；小键盘改成 3 列（1–9 加「确认 / 0 / 删除」）等宽居中。同时把提示小字从 11sp 提到 15sp（面板）/ 17sp（拦截页），坐在沙发上能看清 |
| 1.38 | 修 4 个面板缺陷：两个方向密码的行在儿童模式关着时选不中；分组日期不能多选（「每天」现在会把周一到周日全选）；「新建分组」没点完成就存了；退出密码框会跳页。另按户主要求改密码规则：**进面板验证一次，离开 1 分钟以上再进要重输，面板内部不再逐项要密码** |

---

## 四、技术实现要点

### 模块地图

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
│   └── KidSettings.kt   设置面板：根页、App 选择、分组编辑、三道密码门、锁定/解锁弹窗
├── HomeViewModel.kt     Overlay.Kid / KidGate / KidAdmin / LockSetup / LockConfirm
└── MainActivity.kt      launch() 里的拦截、息屏复锁、Parental/Usage/FallbackClock 的 init
```

### 计时为什么必须用无障碍服务

Aerial 自己就是桌面：孩子打开爱奇艺之后，Aerial 的进程已经不在前台，靠它自己数时间是数不到的。

所以照 [iBala/tvwall](https://github.com/iBala/tvwall) 的做法用一个**无障碍服务**：只订阅 `typeWindowStateChanged`（`canRetrieveWindowContent="false"`，不读屏幕内容），每 5 秒把时间加到当前前台的组上，**只在亮屏时加**（待机不算），两次 tick 之间超过 15 秒就整段丢弃。到点由这个服务拉起 `BlockActivity`——无障碍服务是少数能在后台启动 Activity 的组件，这也是必须用它的原因。

**无障碍没开**时有一层降级计时（`FallbackClock`），只覆盖「从 Aerial 打开、再回 Aerial」这一条路。要完整功能必须让「Aerial 儿童计时」出现在无障碍列表里；授过 `WRITE_SECURE_SETTINGS` 的话，打开儿童模式时 Aerial 会自己写进去。

部分固件在待机时会强停第三方 App，强停会把无障碍服务从列表里删掉。`GuardSetup.ensureEnabled()` 在开机、`MainActivity` 启动、通知监听复活时检查并写回，所以家用场景下不用重跑 ADB 命令。

### 墙钟语义

休息、家长放行、数学题买的时长，全部记「到哪个时刻为止」，而不是「还剩多少秒」。两个好处：退出/进入不打断计时；反复进出不会多送时间。

单次时长打到头的那一刻（`Usage.hitSession`）就把 `hitAt` 记下来当休息起点，所以「留在拦截页 / 回首页 / 换 App / 再回来」四种情况算出来的是同一个倒计时。

### 与上游同步

上游改的是桌面本身（1.35 加了内置城市表、诊断日志、动态壁纸、信号源识别等），本分支改的是家长控制。两边重叠很少，但 `AndroidManifest.xml`、`ConfigReceiver.kt`、两份 `strings.xml`、`Weather.kt` 会冲突。

```bash
git fetch upstream && git merge upstream/main
```

1.35 那次合并是把 36 个提交并进来、7 处冲突逐个手工解决的，`Weather.kt` 直接取上游版（上游的内置城市表比本分支的 geocoder 方案更准、也能离线）。

合并之后务必搜一遍冲突标记再提交——上一次合并把一行 `>>>>>>> origin/main` 带进了 `AndroidManifest.xml`，而构建居然是成功的。

---

## 五、构建与安装

```bash
# 需要 JDK 17、Gradle 8.14.3、Android SDK 36
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
gradle --no-daemon assembleStandardRelease assembleGuardRelease
```

- `standard`：正常的 HOME 应用，带 HOME intent-filter（坚果 N1 用这个）。
- `guard`：不带 HOME intent-filter，靠无障碍服务接管 Home，给那些拒绝安装声明 `CATEGORY_HOME` 的应用的固件用。

产物：`app/build/outputs/apk/standard/release/` 与 `app/build/outputs/apk/guard/release/`。

### 5.1 包名：从这一版起叫 `com.vani.aerialkid`

1.37 起装出来的包名是 **`com.vani.aerialkid`**，不再是上游的 `app.orchard.tv`。这样它和上游正式版、和之前自用的 1.36 都能同时装在电视上，用的是自己的正式签名，不会被上游的包覆盖。

改动只有一层：`build.gradle.kts` 里的 `applicationId`，**Java 包名（`namespace`）仍然是 `app.orchard.tv`**。所以：

| 场景 | 写法 |
|---|---|
| 启动 | `adb shell am start -n com.vani.aerialkid/app.orchard.tv.MainActivity` |
| 配置广播 | `adb shell am broadcast -a app.orchard.tv.CONFIG -n com.vani.aerialkid/app.orchard.tv.guard.ConfigReceiver ...` |
| 数据目录 | `/data/data/com.vani.aerialkid/` |
| 无障碍 / 屏保组件 | `com.vani.aerialkid/app.orchard.tv.parental.WatchService`、`com.vani.aerialkid/app.orchard.tv.dream.AerialDream` |

`tools/install.sh`、`restore.sh`、`report.sh` 里已经用 `PKG`（applicationId）+ `NS`（Java 包）两个变量拼好，不用手改。

**从 1.36 或上游版迁过来的代价**（改包名的必然结果，不是 bug）：

- 电视把它当成**新 App**：旧版的锁定列表、分组、三个密码都不会继承，要在新版里重新设一遍。
- 桌面、屏保、无障碍、通知监听、`WRITE_SECURE_SETTINGS` 这些**都要重新指定/授权**（`tools/install.sh` 一条命令做完）。
- 旧版可以留着当退路：`pm disable-user --user 0 app.orchard.tv` 只是停用，`pm enable` 随时回来。

### 5.2 签名：这一版有正式签名了

正式签名在 `local/`（不进版本控制）：

```
local/aerial-release.jks      # RSA 4096，有效期 30 年
local/signing.properties      # storeFile / storePassword / keyAlias / keyPassword
```

`app/build.gradle.kts` 发现 `local/signing.properties` 就用它签 release，没有才退回本机 debug 签名。**这个 keystore 丢了就再也没法给已装的电视发更新**（只能卸载重装、数据全丢），务必单独备份。

两件事要注意：

1. **同一台电视上，包名 + 签名是绑定的**。以后所有给 `com.vani.aerialkid` 的更新都必须用这个 keystore 签，直接 `adb install -r` 就能覆盖，数据保留。
2. 想换 keystore 或包名，等于换一个 App：先卸载旧的。

### 5.3 装到电视上

```bash
adb install -r app/build/outputs/apk/standard/release/app-standard-release.apk
adb shell cmd package set-home-activity com.vani.aerialkid/app.orchard.tv.MainActivity
adb shell pm grant com.vani.aerialkid android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.vani.aerialkid SYSTEM_ALERT_WINDOW allow
adb shell appops set com.vani.aerialkid WRITE_SETTINGS allow
adb shell appops set com.vani.aerialkid GET_USAGE_STATS allow          # 只有坚果 N1 这类开不了无障碍的机器需要
adb shell cmd notification allow_listener com.vani.aerialkid/app.orchard.tv.guard.RevivalListener
adb shell settings put secure screensaver_components com.vani.aerialkid/app.orchard.tv.dream.AerialDream
```

`tools/install.sh TV=<ip>` 把上面这些一次做完（还带构建、验收和还原）。

儿童模式的计时靠**无障碍服务**，它不是普通权限，`pm grant` 授不了：

```bash
adb shell settings get secure enabled_accessibility_services   # 先看现在开着哪些
adb shell settings put secure enabled_accessibility_services \
  com.vani.aerialkid/app.orchard.tv.parental.WatchService
adb shell settings put secure accessibility_enabled 1
```

这一步只做一次：写进的是系统的 `secure` 设置，重启、待机都不会丢。**坚果 N1 的固件拒绝写这个键**（见 [docs/devices/jmgo-n1.md](docs/devices/jmgo-n1.md)），那台机器上只有从 Aerial 启动的 App 会计时。

---

## 六、已知限制

- **只在坚果 N1 上打算这么用，别的设备不保证**。桌面接管（HOME intent-filter / 无障碍接管）、无障碍服务保活、遥控器按键映射这几处跟固件关系最大，换一台电视或投影就可能失效。
- **没在真机上完整验证过**。编译通过 ≠ 能用，21 条清单在 [docs/PARENTAL.md](docs/PARENTAL.md)。
- **挡不住有 ADB 的人**。`clear_kid` 广播能一键清掉全部家长控制；改系统时间能让当天用量归零。威胁模型是「拿遥控器的孩子」，不是「懂技术的大人」，详见 [docs/SECURITY-REVIEW.md](docs/SECURITY-REVIEW.md)。
- **方向密码的组合空间小**。4 个方向只有 256 种，而且会被孩子「看」到。已用递增等待缓解，设置面板里建议别只用最短的那一个。
- **无障碍服务可能被强停**，靠 `GuardSetup` 自愈，但不是 100%。
- **不是多用户系统**。只有「这台机器的一套规则」，没有孩子的独立账户。
- **不挡应用安装**。Aerial 自己不下载安装任何东西了，但它也不去拦别的 App 装应用。
- **跨零点清零**。改设备日期也能清零用量——刻意的取舍。

---

## 七、参考项目

本分支大量读过、并对齐了实现思路的项目（参考实现都已 fork 到本账号下，方便对照）：

| 项目 | 用途 | 本账号 |
|---|---|---|
| [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv) | 上游本体：桌面、信号源、屏保、无障碍接管 Home 的底座 | 用 `upstream` 远程跟踪（不再另开 fork，避免同名变 `aerial-tv-1`） |
| [iBala/tvwall](https://github.com/iBala/tvwall) | 儿童时间限制的参考实现，`WatchService` 的 tick 与亮屏门控思路来自这里 | [vani-cx/tvwall](https://github.com/vani-cx/tvwall) |
| [spocky/miproja1](https://github.com/spocky/miproja1) | 小米电视 Projectivy 配置，机型适配与系统设置写法参考 | [vani-cx/miproja1](https://github.com/vani-cx/miproja1) |
| [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv)（原 tv-vibehack） | 各机型去广告清单与设备笔记，`docs/devices` 里的机型差异参考过它 | [vani-cx/cleanyourtv](https://github.com/vani-cx/cleanyourtv) |

---

## 八、文档

| 文件 | 内容 |
|---|---|
| [README.md](README.md) / [README.en.md](README.en.md) | 上游的安装与功能说明（本分支已同步改动） |
| [docs/PARENTAL.md](docs/PARENTAL.md) | **家长控制的使用说明**：三把密码、四类拦截、判定顺序、计时、恢复、21 条真机清单 |
| [docs/SECURITY-REVIEW.md](docs/SECURITY-REVIEW.md) | 安全性审查：威胁模型、已知漏洞、撤销方案 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 整体架构与模块地图 |
| [docs/HANDOFF.md](docs/HANDOFF.md) | 交接说明、版本历史、构建环境 |
| [docs/devices](docs/devices) | 各机型的行为差异 |

---

## 九、许可

继承上游，MIT（见 [LICENSE](LICENSE)）。字体许可见 [FONT-LICENSE-OFL.txt](FONT-LICENSE-OFL.txt)。

上游版权归 [pengchujin](https://github.com/pengchujin) 与各位贡献者；本分支的改动同样是 MIT，但**不提供任何担保**（见上面的自用声明）。
