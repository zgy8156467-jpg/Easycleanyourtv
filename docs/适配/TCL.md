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
- extras：`Type=Settings`，`Catagory=Main` / `Picture` / `network` / `about`（拼写就是 `Catagory`）
- 其它值一律落到 `Main`。应用列表没有独立页，也只能开 `Main`。

`MainActivity.openPage()` 统一处理：TCL 先走 overlay，不是 TCL 或服务起不来就回退原来的 Intent。

## 输入源

- 没有 `tv_input` 服务，`TvInputManager` 拿不到端口表。
- provider 返回 `SourceId` / `SourceName`。实测 id：**2 = TV，3 = AV1，8 = HDMI1，9 = HDMI2**（TCL 自己的表，不是 Android 的）。
- 切换：`com.tcl.tv.TVActivity` + int extra `targetSourceId`。
- Live TV 应用没有 launcher category，要按上面的组件名直接起。

## 代码改动

- `DeviceProfile`：新增 `tcl` 品牌（`com.tcl.cyberui` 桌面、`com.tcl.settings` 设置包）。
- 新增 `TclSource.java`：设置 overlay、输入源列表/切换、Live TV、是否 TCL。
- `MainActivity`：`openPage()` 统一设置入口；输入源面板对 TCL 走 provider；投屏列表加 `com.tcl.MultiScreenInteraction_TV`；`vendorSettingsIntent` 跳过 TCL 的设置包。
- `QuickSettingsActivity`：每行多一个 TCL 页面参数，统一走 `MainActivity.openPage()`。
- `AndroidManifest.xml`：`queries` 加 `com.tcl.tv` / `com.tcl.settings` / `com.tcl.MultiScreenInteraction_TV`。
- `strings.xml`（中英）：无障碍引导文案去掉创维专属说法，改成各家通用的“设置 → 通用 / 关于本机”。

## 验证情况

- 编译通过；这台 50V6EA 上 `adb install -r` 后截图确认首页、控制中心显示正常。
- **没有逐项点测**输入源切换、设置各页、投屏，只在代码层面按上面的实测常量实现。
- 未验证：其它 TCL / 雷鸟（FFalcon）机型、海外 Android TV 版（C06 系列）、Home 键接管在这台机上的长期稳定性。
