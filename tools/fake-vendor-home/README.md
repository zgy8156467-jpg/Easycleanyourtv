# fake-vendor-home（仅测试用，不进发布包）

模拟电视厂商原厂桌面（PatchWall 那种）：一个 `CATEGORY_HOME` 的 `MainActivityUserMode`，
外加一个类名含 `screensaver` 的 `ScreenSaverActivity`。用来在 AOSP 模拟器上测
`HomeKeyService` 的接管逻辑——模拟器里没有厂商桌面，真机才有的问题测不了，但服务自身的
探测、去重、节流、重试都能在这里跑。

```bash
# 构建（复用 launcher 的 gradle wrapper 和已缓存依赖）
cd ~/Downloads/cleanyourtv/tvhome-zh/launcher
JAVA_HOME=/Applications/HBuilderX.app/Contents/HBuilderX/plugins/amazon-corretto \
  ./gradlew -p ../tools/fake-vendor-home assembleDebug --offline

# 装到模拟器
adb -s emulator-5554 install -r ../tools/fake-vendor-home/app/build/outputs/apk/debug/app-debug.apk

# 让桌面重新解析品牌档案（DeviceProfile 是进程内缓存）
adb -s emulator-5554 shell am force-stop com.gathin.tvhome
adb -s emulator-5554 shell settings put secure enabled_accessibility_services \
  com.gathin.tvhome/com.gathin.tvhome.HomeKeyService
adb -s emulator-5554 shell settings put secure accessibility_enabled 1
adb -s emulator-5554 shell am start -n com.gathin.tvhome/.MainActivity

# 测原厂桌面接管（日志里应有 profile=generic stockHome=[com.fake.vendorhome, ...]）
adb -s emulator-5554 logcat -c
adb -s emulator-5554 shell am start -n com.fake.vendorhome/.MainActivityUserMode

# 测待机画报接管（会被 1 秒节流挡一次，约 1.2 秒后由 deskRetry 补上）
adb -s emulator-5554 shell am start -n com.fake.vendorhome/.ScreenSaverActivity
```
