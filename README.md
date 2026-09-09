# yins

存储 / 相册「空白通行证」LSPosed 模块。App 索取存储或媒体权限时弹出四选一：

| 选项 | 系统状态 | App 实际能看到什么 |
|---|---|---|
| 授予 | 真授权 | 全部 |
| 空白通行证 | 真授权 | 只有它自己写入的文件 |
| 授权部分 | 真授权 | 自己写入的文件 + 你在相册选择器里选中的 |
| 不授予 | 拒绝 | 无，且不再弹窗 |

运行时相册/媒体权限（READ_MEDIA_*）真的授予：MediaProvider 把这些访问都汇聚到
`LocalCallingIdentity.hasPermission()`，我们在那里按策略把权限位遮掉，让系统自带的
「分区存储只看自有文件」和「用户选择的部分媒体」逻辑生效。文件路径访问（FUSE）和
MediaStore 查询走的是同一套过滤。

**「全部文件访问」（MANAGE_EXTERNAL_STORAGE）例外**：这个权限不能真授予，因为持有它的 App
会从一条全局放行路径绕过 MediaProvider 的过滤（实测能看到全部文件）。所以 BLANK/PARTIAL 下
**不给真的 MES**（appop 置为 ignore），改为在 system_server 的 AppOpsService 里做欺骗：当 App
**自己**查询自己的 MANAGE_EXTERNAL_STORAGE op（`Environment.isExternalStorageManager()` 的底层）
时返回 allowed；MediaProvider、Settings、shell 等其他调用方查到的仍是真实值，过滤照常生效。
因此这类 App 不需要加进 LSPosed 作用域。若额外勾选了，App 进程内还有一层同样效果的兜底 hook。

目标环境：ColorOS 16 / Android 16，已 Root，LSPosed。

## 构建

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
./gradlew :app:assembleDebug :testapp:assembleDebug
./gradlew :app:testDebugUnitTest
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（模块 + 管理界面），
`testapp/build/outputs/apk/debug/testapp-debug.apk`（验证用探针 App）。

## 安装

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. LSPosed 管理器 → 模块 → 启用 yins，作用域勾选：
   - `com.google.android.providers.media.module`（或 `com.android.providers.media.module`）
   - `com.google.android.permissioncontroller`（或 `com.android.permissioncontroller`）
   - 系统框架（`android`）
   - 被管理的第三方 App 不需要勾选
3. 重启。打开 yins，顶部显示「模块已激活」。
4. 首次打开时会申请 Root（libsu）。

## 工作原理

```
App 请求权限
  └─ PermissionController: GrantPermissionsActivity.onCreate  ── hook ──▶ DecisionActivity（yins）
                                                                              │ 用户四选一
                                                                              │ root: pm grant / appops set
                                                                              │ 写入策略库 → MediaProvider reload
       ◀── setResult(GRANTED / DENIED) ─────────────────────────────────────┘
App 打开「全部文件访问」设置页
  └─ system_server: ActivityTaskManagerService.startActivityAsUser ── 拦下，以系统身份拉起 ──▶ DecisionActivity
     （以系统身份启动是为了绕开 ColorOS 的「X 想要打开 yins」关联启动确认）
App 自查是否有「全部文件访问」
  └─ system_server: AppOpsService.checkOperation / noteOperation ── 调用方 uid == 被查 uid 且策略为空白/部分 ──▶ allowed

App 读文件 / 查 MediaStore
  └─ MediaProvider: LocalCallingIdentity.hasPermission(int, boolean) ── after-hook 按策略遮蔽 ──▶
       BLANK  : IS_MANAGER / LEGACY_* / READ_* / WRITE_* / USER_SELECTED 全 false → 只见自有文件
       PARTIAL: 同上，但 READ_MEDIA_VISUAL_USER_SELECTED = true → 自有 + media_grants
     创建文件 / 创建目录时（insertFileIfNecessaryForFuse、isDirAccessAllowedForFuse CREATE/WRITE）
     临时不遮蔽，保证要「全部文件访问」的 App 仍能在任意位置建目录写文件。
```

策略存在 yins 的 Room 数据库（device-protected 存储，解锁前可用），通过只读 ContentProvider
`content://moe.damesck.yins.policy/policies` 提供给 hook 进程；MediaProvider 里的缓存通过
`ContentResolver.call(MediaStore.AUTHORITY, "yins.reload")` 刷新。

## 追加授权与文件选择

- 「授权部分」的 App 在读取相册时，MediaProvider 侧会发一条 Android 16 Live Update（ColorOS 上进流体云胶囊，通知归「媒体存储」名下），点击直接进系统相册选择器追加可见的照片。同一 App 30 秒内只提示一次，60 秒后自动消失。
- 「全部文件访问」请求下的「授权部分」走系统文件选择器，可多选任意类型文件；管理界面里也有「选择文件」按钮。选中的文件会转成 MediaStore id 写入 `media_grants`。
- ColorOS 把本地相册选择器的 authority 换成了 `com.coloros.gallery3d.photopicker`，MediaProvider 只接受当前 authority 的 picker URI；hook 在写入前按运行时的实际值重拼，不写死。

## 更新模块后什么时候要重启

MediaProvider 从开机起常驻，只在启动时加载模块代码。改了 `hook/mediaprovider/` 下的代码要把 `MediaProviderHooks.HOOK_REVISION` 加一并重启；管理界面里顶部会在版本不一致时提示。只改管理 App / PermissionController / system_server 侧的不用重启（后两者按需重启进程即可）。

## 环境相关

- Root 方案为 SukiSU Ultra（KernelSU 系）时，必须在管理器里把 yins 加入允许列表；libsu 配置为只用 `su`，拿不到会明确提示而不是退回普通 shell。
- ColorOS 会延迟投递发往后台 App 的广播，并可能阻止其他进程拉起被杀掉的 yins。因此 MediaProvider → yins 的通信走同步的 ContentProvider `call()`，通知由 MediaProvider 自己发，策略在 MediaProvider 目录里落盘，三者都不依赖 yins 进程存活。

## 已知限制

- 空白 App 仍能看到子目录**名**（如 DCIM/Camera），只是列不出里面的文件、也打不开。
  `stat` 一个猜到的完整路径可以探测文件是否存在。堵这两个洞需要 native FUSE hook（参考 FuseHide），未做。
- 只拦截**全部都是**存储 / 媒体权限的请求。一次同时请求相机 + 相册的混合请求会放行给系统弹窗（日志里有记录）。
- 策略变更后 App 侧可能有 FUSE 缓存，管理界面里改策略会 `am force-stop` 该 App。
- 只处理主用户（userId 0）。

## 真机核对清单（Phase 0）

见 `docs/phase0-checklist.md`。

## 目录

```
app/      模块 + 管理 App（Kotlin + Compose）
testapp/  探针 App：请求权限、列目录、查 MediaStore、写文件
docs/     设计与核对记录
```
