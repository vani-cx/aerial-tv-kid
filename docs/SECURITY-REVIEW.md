# 安全性审查

2026-10-22，针对 1.27 + 儿童模式这一次改动。是通读代码得出的结论，没有做逆向、没有对真机做渗透测试、也没有跑第三方依赖的 CVE 扫描——下面写清楚了哪些是查过的、哪些是推断。

**先说结论**：Aerial 原本的安全面是"一个正常的桌面 App"，没有遥测、没有后台下载安装、没有把用户数据往外送；新加的儿童模式在**"家里的小孩拿着遥控器"**这个威胁模型下够用，但它不是保险箱——它挡不住能拿到 ADB 的人，也挡不住会看父母按键的人。详见第五节的 K-1 到 K-11。

## 一、发现项一览

| 编号 | 严重性 | 问题 | 状态 |
|---|---|---|---|
| K-1 | 中 | 方向密码组合空间小（4 位 256 种），且会被"看"到 | 已用递增等待缓解；文档说明 |
| K-2 | 中 | 管理员密码只有 8 位，离线爆破在弱口令下可行 | 接受（PBKDF2 抬高成本）；文档建议 |
| K-3 | 低 | 密码哈希明文存 SharedPreferences | 接受（依赖 Android 沙箱） |
| K-4 | 中 | 拦截页是 Activity，不是系统悬浮窗 | 接受（HOME 键离开即不受限，等价于放弃看电视） |
| K-5 | 中 | 改电视日期可清零每日额度 | 接受（设置 App 已被密码挡住）；见 7.2 |
| K-6 | 高* | 停用无障碍服务后计时与拦截失效 | `GuardSetup.ensureWatch` 会在 Aerial 启动/开机时补回；系统设置优先，仍非绝对 |
| K-7 | 低 | 数学题可无限重试，答案会被记住 | 设计如此（练习而非惩罚） |
| K-8 | 低 | 家长放行是挂钟时间，改系统时间可提前结束 | 接受 |
| K-9 | 低 | `clear_kid` 是 ADB 后门 | 有意为之（唯一的救命通道） |
| K-10 | 中 | 无障碍服务没开时，从别处打开的 App 拦不到 | 已在设置面板明示「未开启」 |
| K-11 | 高* | 换一个桌面 App、或卸载 Aerial，全部失效 | 户主的权利，无法也不该阻止 |
| A-1 | 低 | 手机配置页是局域网明文 HTTP，token 在 URL 里 | 已有 token；只在面板打开期间监听 |
| A-2 | 低 | 手机配置页可被同一网络重复上传填满存储 | #57 已限单次 200 MB |
| A-3 | 信息 | 天气/城市走了白名单 cleartext | 可被中间人篡改，仅影响显示 |
| A-4 | 信息 | NanoHTTPD 2.3.1 已多年不维护 | 靠 token + 短时暴露降低风险 |

\* 标"高"的两条不是代码缺陷，是这类"非系统级家长控制"的固有上限，写在这里是为了不让人误以为它固若金汤。

## 二、审查范围

查过的：

- `AndroidManifest.xml`：全部导出组件、权限、task 关系。
- 全部 `uses-permission` 的实际使用点（逐个反查代码）。
- 网络：把 `app/src/main/java` 里所有 URL 抠出来核对（见 3.4）。
- 存储：所有 SharedPreferences / 文件写入点，以及 `allowBackup`。
- 无障碍服务的三份配置（`home_guard.xml`、`parental_watch.xml`）与相关 Service 代码。
- 手机配置页（`wallpaper/PhoneServer.kt`）的鉴权、输入校验、上传限制、路径处理。
- 本次新增的全部代码：`parental/`、`ui/parental/`、`MainActivity.launch`、`HomeViewModel.whenUnlocked`、`ConfigReceiver`、资源文件。

没查的（如果有人要继续做，从这些下手）：

- 真机上的实际行为（没有电视，只有代码）。
- 依赖库的已知漏洞（没有 SCA 工具，也没有联网库）。
- 厂商固件本身的漏洞（小米 / TCL 的系统组件）。
- 侧信道、功耗、屏幕残余这类物理层面的东西。

## 三、Aerial 原本的攻击面

### 3.1 权限

| 权限 | 用途 | 拿到方式 | 风险 |
|---|---|---|---|
| `INTERNET` | 天气、每日壁纸、航拍、检查更新 | 安装即授 | 正常 |
| `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | 状态胶囊、局域网地址 | 安装即授 | 无 |
| `ACCESS_FINE_LOCATION` | 只为了读 Wi-Fi 名（Android 8.1+ 的要求） | **只有 ADB**（`tools/install.sh`），App 内从不申请 | 中：能读到 Wi-Fi 名。不申请定位本身不用 |
| `QUERY_ALL_PACKAGES` | 桌面必须列出所有 App | 安装即授 | 信息：能看到装了哪些 App。桌面 App 无法避免 |
| `RECEIVE_BOOT_COMPLETED` | 开机拉回自己 | 安装即授 | 无 |
| `SYSTEM_ALERT_WINDOW` | 开机后立刻启动自己的豁免 | 用户/ADB | 低 |
| `WRITE_SECURE_SETTINGS` | 被小米强停后把守护重新打开 | **只有 ADB** | 高权限，但只有户主能授；也是 `ConfigReceiver` 的保护伞 |
| `WRITE_SETTINGS` | 某些电视的亮度写在系统设置里 | **只有 ADB** | 低 |
| `REQUEST_DELETE_PACKAGES` | 控制中心卸载 App | 安装即授 | 低（仍要系统确认框） |
| `READ/WRITE_SYNC_SETTINGS` | 重生机制（同步适配器） | 安装即授 | 低 |

结论：真正的高权限只有两条，而且都只能从电脑用 ADB 授，App 自己从不弹窗要。这一点比大多数国产桌面 App 干净。

### 3.2 导出组件

| 组件 | exported | 保护 | 说明 |
|---|---|---|---|
| `MainActivity` | 是 | 无 | 桌面本身，必须导出（`guard` 版靠无障碍接管，连 HOME 都不声明） |
| `AerialDream` | 是 | `BIND_DREAM_SERVICE` | 屏保，系统才能绑 |
| `BootReceiver` | 是 | 无 | 只响应 `BOOT_COMPLETED`，做的是"拉起自己"，没有外部输入可用 |
| `ConfigReceiver` | 是 | **`WRITE_SECURE_SETTINGS`** | 只接受 ADB 广播。见 K-9 |
| `RevivalListener` | 是 | `BIND_NOTIFICATION_LISTENER_SERVICE` | 读通知只为了判断"我是不是被强停了" |
| `HomeGuardService` | 是 | `BIND_ACCESSIBILITY_SERVICE` | 见 3.6 |
| `WatchService` | 是 | `BIND_ACCESSIBILITY_SERVICE` | 本次新增，见 3.6 |
| `RevivalSyncService` / `RevivalAccountService` | 是 | 无（系统靠 action 绑） | 异常之处见下 |
| `BlockActivity` | **否** | — | 本次新增，只能由 App 自己启动。`excludeFromRecents`，独立任务 |

`RevivalSyncService` 注册的同步适配器把 `contentAuthority` 指到系统的 `settings`，`accountType` 是自己的。这是"被强停后约 30–60 秒复活"的实现方式，本身不读任何数据（`RevivalSync` 里只做启动），但它确实往系统同步框架里插了一个以 `settings` 为 authority 的适配器。**这是全项目里看起来最可疑、最容易被安全软件报警的一处**，属于功能与安全的取舍，不是漏洞。

### 3.3 手机配置页（`wallpaper/PhoneServer.kt`）

同一局域网内的手机扫码后可以：换壁纸、换屏保、传照片、给 App 换图标、设天气城市。

- **鉴权**：每个地址都带 8 位 token（31 个字符的字母表，约 8.5×10¹¹ 种），只出现在电视屏幕上的二维码里，重开面板会换新的。
- **暴露时间**：只在个性化面板打开时监听，关闭后过几分钟停（`PhoneServer.stop`）。不是常驻端口。
- **输入校验**：`delete_photo` 只接受"已经列出来的照片 id"（`Wallpapers.deletePhoto` 里显式防 `photo:../daily/...` 这种路径穿越，见 #57）；`icon` 只接受 `AppCatalog` 里存在的包名；上传前按 `Content-Length` 卡 200 MB。
- **明文**：HTTP，没有 TLS。同一个 Wi-Fi 下能抓包的人可以拿到 token（它就在 URL 里），进而操控这个页面。**影响仅限"换壁纸/传照片/改城市"，拿不到任何密码、看不到 App 列表之外的隐私**——但反过来说，它能上传图片、能把存储塞满（A-2），所以**不要在咖啡厅的 Wi-Fi 下打开个性化面板**。
- 页面本身是 `assets/web/index.html`，用字符串替换塞设备型号，没有服务端模板注入面。

### 3.4 网络

代码里出现的全部外部地址：

```
https://aerial.qust.me/download/version.txt      检查更新（只读版本号）
https://aerial.qust.me/install.sh                官网上的安装脚本（不在 App 里执行）
https://api.open-meteo.com/v1/forecast           天气
https://geocoding-api.open-meteo.com/v1/search   城市搜索
https://ipinfo.io/json                           按 IP 猜城市
https://cn.bing.com/HPImageArchive.aspx          每日精选壁纸
sylvan.apple.com                                 航拍影片
```

- **没有统计、没有崩溃上报、没有广告 SDK**。依赖清单里除 androidx / Media3 外只有 `nanohttpd` 和 `zxing`，都是本地功能。
- **更新是"只提示、不下载"**（`update/Updates.kt` 只读一个版本号字符串，正则校验 `\d+(\.\d+)*`）。不会被投毒成自动装包。
- **证书**：`network_security_config.xml` 全局禁止明文，只对 `sylvan.apple.com`（并单独信任 Apple Root CA，因为 Android 不自带）和三个天气/城市域名开了 cleartext。后者意味着**天气和城市可被同网段中间人改**（A-3），影响限于显示错误天气。
- 航拍影片来自 Apple 的 CDN，是公开资源，不涉及账号。

### 3.5 存储与备份

- `android:allowBackup="false"`：SharedPreferences 和照片不会进云备份、不会被 `adb backup` 整包带走。
- 数据都在 `/data/data/app.orchard.tv/`（`shared_prefs/`、`files/`），靠 Android 的沙箱隔离；**设备 root 之后全部可读**。
- 照片存 `files/photos/`，用户自选图标存 `files/icons/<包名>.png`，上传的临时文件在 `cacheDir` 里由 NanoHTTPD 自己清。

### 3.6 两个无障碍服务

| 服务 | 事件 | 读屏幕内容 | 过滤按键 | 用途 |
|---|---|---|---|---|
| `HomeGuardService` | 窗口变化 | **否** | 是（只在自己前台时） | 接管 HOME、按 HOME 把人拉回来 |
| `WatchService`（新） | 窗口变化 | **否** | 否 | 数时间、到点弹拦截页 |

两个都只订阅 `typeWindowStateChanged`、`canRetrieveWindowContent="false"`——能知道"前台是哪个包"，读不到任何文字、界面结构或输入内容。这是能做到的最小权限（要"知道前台是谁"就绕不开无障碍服务或 `UsageStatsManager`，后者在电视上更不可靠且同样敏感）。

需要如实说明的：**无障碍服务本身是 Android 上最强的用户级权限之一**，用户把一个来路不明的 App 开成无障碍服务基本等于把机器交出去。Aerial 这一个是自己写的、只做这两件事，但户主授权时应当知道这一层含义。

## 四、新功能的威胁模型

**防线内**：拿着遥控器的孩子；孩子的哥哥姐姐；误触。
**不在防线内**：能拿到 ADB 的人（= 户主本人）；能 root 的人；能物理换掉桌面 App 的人；能站在旁边看父母输密码的人。

这个划分决定了下面所有取舍：规则可以"能被绕过一点"，但不能"容易被误触绕过"；密码不需要抗国家级爆破，但要抗"小孩连按 5 分钟方向键"。

## 五、发现项细节

### K-1 方向密码的空间小（中）

4 个方向，`Parental.MIN_COMBO=4`、默认 6、最多 8。组合数分别是 256 / 4096 / 65536。

原来的做法是"连错 5 次等 30 秒"，那么 256 种组合平均 128 次就能撞开，约 13 分钟——一个执着的孩子够了。**已改**为递增等待（`Parental.count`）：连错 5 次等 30 秒，再 5 次等 1 分钟，然后 2 / 4 / 8 分钟，最高 8 分钟一轮；安静一小时后重新从 30 秒开始。这样撞完 256 种要按小时算，而且每一轮都要在电视前干等，失去"偷偷试"的意义。

剩余风险：**肩窥**。孩子看见父母按了哪几个方向就完了，这是所有短密码的共同问题。建议：把孩子在场时的输入当"已被看见"，必要时改一次。

### K-2 管理员密码的强度取决于人选什么（中）

8 位字母数字混合，空间 62⁸ ≈ 2.2×10¹⁴。PBKDF2-HMAC-SHA1 60000 轮。假设拿到哈希（需要 root 或物理接触）在 GPU 上离线爆破，每次尝试 0.1 ms 量级，那么：

- 随机 8 位：不可行。
- 8 位小写字母的常见词 + 数字（`password1`、`abc12345`、孩子生日）：**几分钟到几小时**。

所以真正的防线是"别选有意义的东西"，而代码挡不住这个。已经做的：强制字母+数字都要有、长度正好 8 位、只存 PBKDF2 哈希。没有做的（也不打算做）：弱口令字典检查——电视上跑这个既慢又容易误伤。

### K-3 哈希明文存 prefs（低）

`/data/data/app.orchard.tv/shared_prefs/parental.xml` 里是 `pbkdf2$60000$<salt>$<hash>`。设备 root 后可读，但读到的是哈希不是密码。**没有**用 Android Keystore 加密，原因：Keystore 在 Android 5.0 上行为不一致，而且它保护的是"密钥"而不是"已经哈希过的东西"，收益很小；真正的风险是 root，root 之后 Keystore 也挡不住。

顺带一处已加固的点：`Secrets.verify` 会从存储的字符串里读回轮数，如果之后被改成 1，爆破成本就归零。现在限制在 10000–200000 之间（`MIN_ROUNDS`/`MAX_ROUNDS`）。

### K-4 拦截页是 Activity，不是悬浮窗（中）

`BlockActivity` 用普通 Activity 而不是 `TYPE_APPLICATION_OVERLAY`，后果：

- 按 HOME 能退出——但按 HOME 就离开了那个 App，时间不再累计，等价于"不看了"。
- 有的电视在多任务里能看到它（已 `excludeFromRecents`，多数机器不会显示）。
- 背后的视频**可能继续出声**（全屏 Activity 抢焦点后，多数播放器会暂停，但不是全部）。

tvwall 走的是同样的路线（拉起一个占满屏的 Activity），因为它更可靠：悬浮窗要额外授权，还在某些固件上被禁。不改成悬浮窗是有意的。

真正的绕过路径不是"点过去"，而是"从别的地方重新进来"：厂商桌面、系统"最近任务"、App 自己的通知。这几条由 `WatchService` 盯着前台窗口变化来补——所以 K-6 才是要害。

### K-5 改日期能清零每日额度（中）

`Usage.rollDay` 以本地日期为准，日期变了就整盘清零。把电视日期往前调一天，今天的额度就回来了。

- 缓解 1：系统设置 App 在 Aerial 里是被管理员密码挡住的（`vm.needsParent()` + `SystemProbe.isSettingsApp`）。
- 缓解 2：很多电视遥控器有独立的"设置"键，可能直接进厂商设置，Aerial 拦不到。这是 K-5 仍然是"中"的原因。
- 没有用单调时钟（`SystemClock.elapsedRealtime`）的原因：它跨重启归零，待机时还会停，用它做"每天"的账根本对不上。
- 能做的（未做）：把"最后见到的时刻"记下来，发现时间倒退就报警给家长。收益不大，复杂度不小，先记在这里。

### K-6 停用无障碍服务就失效（高，但是上限）

Android 上，一个普通 App 没有办法保证自己的无障碍服务不被关掉。孩子（或任何拿到设置的人）在系统设置里关掉"Aerial 儿童计时"之后：

- 不再计时（除了从 Aerial 启动的那条降级路径）。
- 不再弹拦截页。
- 锁定的 App 仍然拦——因为那是 Aerial 启动前判断的（`MainActivity.launch`）。

设置面板里明确显示"计时服务：未开启"，就是为了让户主自己发现这件事。

代码能做的都做了：儿童模式开着时，`GuardSetup.ensureEnabled()` 在开机（`BootReceiver`）、`MainActivity` 启动、通知监听复活时都会把 `WatchService` 写回无障碍列表（`ensureWatch`），`rebindIfStalled()` 还会处理"在列表里但没绑上"的情况（TCL 升级 APK 后出现过）。所以**孩子按两下关掉它，下次 Aerial 起来就恢复了**——这是这个功能能成立的前提，不然时间限制等于摆设。

代价要写明：这等于 App 会把自己的无障碍权限加回来。系统设置里的"关闭"不是最终裁决，只有面板里的关（要管理员密码）或 `clear_kid` 才真的移除。对自建自用的家长控制是合理的；如果这个 App 要被分发，Google Play 的无障碍政策不会允许这种自我恢复，必须在发布版里去掉。

**如果电视的家长控制是刚需**，仍然建议把系统设置 App 也管起来（已在 `vm.needsParent()` 里），或用厂商自带的儿童模式做第二道。

同理，`HomeGuardService` 被关掉，HOME 键就不再回到 Aerial——这是 Aerial 一直以来的既有约束，不是本次引入的。

### K-7 数学题可以一直重试（低）

答错换一道题，没有次数上限。这是刻意的：题目是"练习"，不是惩罚；真正的闸门是"一次会话只能换 5 分钟"（`Session.quizUsed`）。副作用是同一批题会被记住答案——所以 `MathPad` 每次从随机生成（`Random`），不是固定题库。

### K-8 放行是挂钟时间（低）

`grantPass` 记的是"到某个时刻为止放行"。把系统时间往前调，pass 提前结束（对孩子不利）；往后调，pass 变长，但同时"今天"也可能被跳过（`rollDay`），互相抵消一部分。没有做成"累计分钟数"是因为那样反复进出 App 会不断送出新的额度。

### K-9 `clear_kid` 后门（低）

```bash
adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez clear_kid true
```

它能一键清掉两个密码、锁定列表和所有分组。这是设计上的"救命令"：PBKDF2 只存哈希，忘了密码没有第二条路。保护是清单里的 `android:permission="android.permission.WRITE_SECURE_SETTINGS"`——普通 App 拿不到这个权限，只有 ADB shell（和系统）能发。

要注意的是：**如果电视开了网络 ADB 且没有密码，同一个局域网里的人也能发这条广播**。不过那种状态下整台电视已经是别人的了，家长控制失守只是其中一项。

### K-10 无障碍没开时，从别处进来的 App 拦不到（中）

`FallbackClock` 只覆盖"Aerial 启动 → 回 Aerial 停"这一条路。从厂商桌面、从"最近任务"、从 App 自己的通知进入的，既不计时也不拦截。这不是疏忽，是没有无障碍服务就看不到前台是谁的必然结果。设置面板里的"计时服务"一行就是让户主知道当前处在哪一档。

### K-11 换桌面或卸载（高，是上限）

Aerial 是桌面，换一个桌面 App 或卸载它，全部规则消失。任何非系统的家长控制都是这样。guard 版能做到"把 HOME 键抢回来"，但抢不过"在系统设置里改默认桌面"。

## 六、明确的非目标

- **不是防取证工具**：不隐藏自己的存在，prefs 名字就叫 `parental`。
- **不是多用户系统**：没有"孩子的账户"，只有"这台电视的一套规则"。
- **不做时间银行、不做奖励、不做远程控制**：需求里没有，也不打算加。
- **不阻止孩子关电视**：本功能只管 App 的时间。

## 七、给户主的建议

1. **设 8 位随机一点的管理员密码**，别用生日、门牌号、`abc12345`。写在一个孩子拿不到的地方。
2. **方向密码至少设 6 个方向**，并且别在孩子面前反复输同一个。
3. **每次看电视前瞄一眼"计时服务：已开启"**；不确定就进去看一眼。
4. **别在公共 Wi-Fi 下打开个性化面板**（手机配置页会开一个局域网 HTTP 端口，见 A-1）。
5. **想彻底停下这套规则**：设置 → 儿童与时间管理 → 儿童模式关。想连数据一起清掉：用上面的 ADB 命令。
6. **改完规则先在电视上自己走一遍**（`docs/PARENTAL.md` 末尾有 14 条测试清单）。这套东西没有自动化测试。

## 八、要继续加固的话

按性价比排序：

1. **K-6**：在 `WatchService` 断掉时给 Aerial 打一个明显的标记（首页角标"计时已停"），比藏在设置里更醒目。成本低，效果直接。
2. **K-5**：记录"最后见到的日期"，发现倒退就（a）不加额度、（b）在设置面板提示。中等成本。
3. **A-1/A-4**：手机配置页加一层"TV 上确认"的配对码，或者把 NanoHTTPD 换掉。收益有限，因为攻击面本来就很窄。
4. **K-2**：如果以后真要防"家里有个懂技术的青少年"，管理员密码应是长口令而不是固定 8 位——但这与需求 4 冲突，需要户主自己决定。
