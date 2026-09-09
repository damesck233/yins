# Phase 0：真机核对

手机连上 adb 后逐条执行，把输出记录到本文件下方的「结果」小节。
任何一条与代码假设不符，都要先改 hook 再进入 Phase 2 验证。

## 1. 包名

```bash
adb shell pm list packages | grep -iE 'permissioncontroller|providers.media|securitypermission|permission'
adb shell getprop ro.build.version.sdk ro.build.version.release ro.fuse.bpf.is_running
```

期望：存在 `com.google.android.providers.media.module`（或 AOSP 变体）和
`com.google.android.permissioncontroller`（或 AOSP 变体）。两者都已列入 `res/values/arrays.xml` 的默认作用域。

## 2. 运行时权限弹窗是谁画的

让任意 App 弹一次相册权限框（testapp 的「请求 READ_MEDIA_IMAGES/VIDEO」按钮即可），弹窗停留时：

```bash
adb shell dumpsys activity activities | grep -iE 'mResumedActivity|topResumedActivity'
```

期望：`com.google.android.permissioncontroller/com.android.permissioncontroller.permission.ui.GrantPermissionsActivity`。
如果是 OPPO 自己的 Activity，需要改 `GrantPermissionsHook.GRANT_ACTIVITY` 和作用域。

## 3. 「全部文件访问」页面归属

```bash
adb shell cmd package resolve-activity -a android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION -d package:moe.damesck.yins.testapp
```

只做记录。system_server 里的改道在解析之前发生，理论上与归属无关。

## 4. MediaProvider 方法签名

```bash
mkdir -p docs/apk docs/jadx
adb pull "$(adb shell pm path com.google.android.providers.media.module | head -1 | cut -d: -f2)" docs/apk/mediaprovider.apk
adb pull "$(adb shell pm path com.google.android.permissioncontroller | head -1 | cut -d: -f2)" docs/apk/permissioncontroller.apk
brew install jadx
jadx -d docs/jadx/mediaprovider docs/apk/mediaprovider.apk
jadx -d docs/jadx/permissioncontroller docs/apk/permissioncontroller.apk
```

核对（`grep -rn` docs/jadx/mediaprovider）：

| 代码里的假设 | 核对项 |
|---|---|
| `LocalCallingIdentity.hasPermission(int, boolean)` | 方法存在且为公开；`uid` 字段名 |
| `PERMISSION_*` 常量 | 名称与 `MediaProviderHooks.MASKED_PERMISSION_FIELDS` 一致（值由反射读取，不一致只影响 fallback） |
| `MediaProvider.call(String, String, Bundle)` | 签名 |
| `MediaProvider.mMediaGrants` | 字段名 |
| `MediaGrants.addMediaGrantsForPackage(String, List<Uri>, int)` / `removeAllMediaGrantsForPackages(String[], String, Integer)` | 签名 |
| `insertFileIfNecessaryForFuse(String, int)` / `isDirAccessAllowedForFuse(String, int, int)` | 第二个参数是 uid，第三个是 accessType（1 READ / 2 WRITE / 3 CREATE / 4 DELETE） |
| `GrantPermissionsActivity.onCreate(Bundle)` | 类名、包名 |

## 5. MediaProvider 能否读到 yins 的 ContentProvider

模块安装并启用、重启后：

```bash
adb logcat -s Yins | grep -E "policies loaded|MediaProvider hooked"
```

期望在开机后一分钟内看到 `policies loaded: N`。看不到 → SELinux 或包可见性拦住了，
需要改成由管理 App 主动 `yins.reload` 推送（已有 BootReceiver 做这件事）并检查
`adb logcat | grep avc` 里的 `mediaprovider_app` 拒绝记录。

## 6. appops 名称

```bash
adb shell cmd appops get moe.damesck.yins.testapp
adb shell appops set --uid moe.damesck.yins.testapp MANAGE_EXTERNAL_STORAGE allow && adb shell cmd appops get moe.damesck.yins.testapp | grep MANAGE
```

## 结果

（待填）
