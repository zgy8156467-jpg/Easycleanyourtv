# TCL 电视适配

实测机型：**TCL 50V6EA**（型号代码 `ak30a5`，中国区固件，Android 9），2026-10-26 本机实测。

品牌识别：`Build.MANUFACTURER` 含 `tcl` → `DeviceProfile` 的 `tcl` 条目。

## 原厂组件

| 作用 | 组件 |
|---|---|
| 原厂桌面 | `com.tcl.cyberui` / `cyberui.MainActivity`（声明 `CATEGORY_HOME`；同包还有 `WebActivity`，不能整包当成桌面） |
| 系统设置 | `com.tcl.settings`（overlay 服务，见下） |
| 输入源 | `content://com.tcl.tv.sourceprovider` + `com.tcl.tv.TVActivity` |
| 投屏 | `com.tcl.MultiScreenInteraction_TV` |

## 设置页怎么打开

`com.tcl.settings` 的启动 Activity 只是个壳：它拉起 overlay 服务后几秒自己结束，从桌面上看就是“点了没反应、又弹回桌面”。正确做法是直接起服务：

- component：`com.tcl.settings/.ShowWindowService`
- action：`com.tcl.settings.SHOW_WINDOW`
- extras：`Type=Settings`，`Catagory=Main` / `picture` / `network` / `about`（拼写就是 `Catagory`）
- **大小写有讲究**：`picture` 必须小写，`Picture` 会落到 `Main`（2026-10-26 实机验证）。
- 其它值一律落到 `Main`。应用列表没有独立页，也只能开 `Main`。

`MainActivity.openPage()` 统一处理：TCL 先走 overlay，不是 TCL 或服务起不来就回退原来的 Intent。

## 输入源

- 没有 `tv_input` 服务，`TvInputManager` 拿不到端口表。
- provider 返回 `SourceId` / `SourceName`。实测 id：**2 = TV，3 = AV1，8 = HDMI1，9 = HDMI2**（TCL 自己的表，不是 Android 的）。
- 切换：`com.tcl.tv.TVActivity` + int extra `targetSourceId`。
- Live TV 应用没有 launcher category，要按上面的组件名直接起。

面板入口是 控制中心 → 输入源，列表取自 provider，去掉 TV 后是 AV1 / HDMI 1 / HDMI 2，最后一行「直播电视」回 tuner。

2026-10-26 实机验证两种起法都能切：

- **热启动**：TVActivity 还在后台，intent 走 `onNewIntent`；
- **冷启动**：先 `am force-stop com.tcl.tv` 再点，TVActivity 起来后同样切过去。

两种都看得到 `LiveTv: setInputSource() desSourceType = EN_TCL_HDMI1/2`。

识别**不看包名**，而是看谁声明了 `com.tcl.tv.sourceprovider`（`resolveContentProvider`），TVActivity 用同一个包名拼出来。这套契约按 TCL 家族算，同一固件体系的其它 TCL / 雷鸟型号理论上通用；换型号时先确认 provider 返回的表还是这四个 id。

## 代码改动

- `DeviceProfile`：新增 `tcl` 品牌（`com.tcl.cyberui` 桌面、`com.tcl.settings` 设置包）。
- 新增 `TclSource.java`：设置 overlay、输入源列表/切换、Live TV、是否 TCL。
- `MainActivity`：`openPage()` 统一设置入口；输入源面板对 TCL 走 provider；投屏列表加 `com.tcl.MultiScreenInteraction_TV`；`vendorSettingsIntent` 跳过 TCL 的设置包。
- `QuickSettingsActivity`：每行多一个 TCL 页面参数，统一走 `MainActivity.openPage()`。
- `AndroidManifest.xml`：`queries` 加 `com.tcl.tv` / `com.tcl.settings` / `com.tcl.MultiScreenInteraction_TV`。
- `strings.xml`（中英）：无障碍引导文案去掉创维专属说法，改成各家通用的“设置 → 通用 / 关于本机”。

## 验证情况

2026-10-26 在 50V6EA 上装 2.1.5 逐项点测：

- 首页、控制中心、Dock 显示正常。
- 设置：电视设置（Main）、显示与画面（`picture`）、网络（`network`）、关于本机（`about`）四个入口都能打开对应 overlay 页。
- 输入源：AV1 / HDMI 1 / HDMI 2 / 直播电视，行都在；HDMI 1、HDMI 2 冷启动和热启动都切换成功，直播电视回到 tuner。
- 投屏：打开了 `com.tcl.MultiScreenInteraction_TV`。
- 无障碍：这台机器 `ACTION_ACCESSIBILITY_SETTINGS` 没有任何 Activity 接，确认没有无障碍页；ADB 引导弹窗正常。
- 覆盖安装后无障碍服务会被系统关掉，需要重跑一次 `settings put secure` 两条命令（引导文案里已写）。

未验证：其它 TCL / 雷鸟（FFalcon）机型、海外 Android TV 版（C06 系列）、Home 键接管在这台机上的长期稳定性、AV1 口（没接线，未切）。
