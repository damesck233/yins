# yins 设计（2026-09-09）

## 需求

ColorOS 16（Android 16）、已 Root、LSPosed。App 索取存储 / 媒体权限时弹出：授予 / 空白通行证 / 授权部分（单张选图）/ 不授予。
空白 = App 以为拿到了权限，但只能看到自己写入的文件；部分 = 自己写的 + 用户选中的。
第一版只覆盖存储 / 媒体权限：`READ_EXTERNAL_STORAGE`、`READ_MEDIA_IMAGES/VIDEO/AUDIO`、`READ_MEDIA_VISUAL_USER_SELECTED`、`MANAGE_EXTERNAL_STORAGE`。

## 决策

- **真授权 + MediaProvider 遮蔽**。不伪造任何 App 侧的权限检查（那条路会让 App 拿到 EACCES 崩溃，FakePers 是反例）。
- 弹窗在 PermissionController 里拦；「全部文件访问」的设置页 Intent 在 system_server 改道。
- Kotlin + Compose；传统 Xposed API（`de.robv.android.xposed:api:82`）；包名 `moe.damesck.yins`。
- 先例：GrapheneOS Storage Scopes（设计蓝本）、FuseHide（native 级隐藏，后续版本参考）、Media-Provider-Manager（Java hook 参考）。

## 事实依据（android16-release 源码）

- Android 11+ 普通 App 访问 `/storage/emulated/0` 只有 FUSE 一条路；FUSE daemon 在 MediaProvider 进程里，每个决策都进 Java：`getFilesInDirectoryForFuse`、`onFileOpenForFuse`、`isDirAccessAllowedForFuse`、`insertFileIfNecessaryForFuse`、`deleteFileForFuse`、`renameForFuse`。MANAGE_EXTERNAL_STORAGE / legacy App 的「旁路」也是在 Java 里由 `shouldBypassFuseRestrictions` 判定后返回 `{"/"}` 哨兵。
- 判定依据全部汇聚在 `LocalCallingIdentity.hasPermission(int permission, boolean forDataDelivery)`：`PERMISSION_IS_MANAGER=1<<2`、`IS_LEGACY_GRANTED/READ/WRITE=1<<9/10/11`、`READ_AUDIO/VIDEO/IMAGES=1<<16/17/18`、`WRITE_*=1<<19/20/21`、`IS_SYSTEM_GALLERY=1<<22`、`WRITE_EXTERNAL_STORAGE=1<<24`、`READ_MEDIA_VISUAL_USER_SELECTED=1<<27`。
- 查询可见性：`appendAccessCheckQuery` → `AccessChecker.hasAccessToCollection` / `hasUserSelectedAccess` → `media_grants` 子查询或 `owner_package_name` 匹配。FUSE 列目录内部也走 `query()`。
- `MediaGrants.addMediaGrantsForPackage(String, List<Uri>, int)` 只接受本地相册选择器返回的 picker URI，`ContentUris.parseId` 取 id。
- `insertFileIfNecessaryForFuse` 在旁路分支里仍会插入带 owner 的数据库行，因此创建时放宽旁路不会丢失自有文件的归属。

## 架构

| 组件 | 进程 | 职责 |
|---|---|---|
| 管理 App | `moe.damesck.yins` | 策略编辑；`DecisionActivity` 弹窗；libsu 执行 `pm grant` / `appops set` / `am force-stop`；`PolicyProvider` 只读 ContentProvider（directBootAware） |
| `GrantPermissionsHook` | PermissionController | 请求全为存储权限时 `startActivityForResult(DecisionActivity)`；`Activity.dispatchActivityResult` 里接住结果，`setResult(EXTRA_REQUEST_PERMISSIONS_NAMES/RESULTS)` 并 finish；取消则放行系统弹窗 |
| `AllFilesAccessRedirectHook` | system_server | `ActivityTaskManagerService.startActivityAsUser` 里把 `MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` Intent 改为 `DecisionActivity`；只对第三方 App 自己发起的请求生效 |
| `MediaProviderHooks` | MediaProvider | 见下 |

### MediaProvider 遮蔽

- after-hook `LocalCallingIdentity.hasPermission(int, boolean)`：uid 有 BLANK 策略 → 上述位全部 false；PARTIAL → 同上但 `READ_MEDIA_VISUAL_USER_SELECTED` = true。常量值在安装时反射读取，缺失时用源码 fallback。
- `RelaxScope`（ThreadLocal）：在 `insertFileIfNecessaryForFuse` 和 `isDirAccessAllowedForFuse`（accessType WRITE/CREATE）期间不遮蔽，让持有 MES / legacy 的 App 仍能在任意位置创建文件和目录（GrapheneOS `shouldRelaxWriteRestrictions` 语义）。打开、列目录、删除、重命名不放宽。
- `MediaProvider.call()` 私有通道（校验调用 uid == 管理 App）：`yins.ping` / `yins.reload` / `yins.grant`（picker URI → `mMediaGrants.addMediaGrantsForPackage`）/ `yins.clearGrants`。
- `PolicyCache`：`onCreate` 后从 `PolicyProvider` 拉取（指数退避重试），uid → mode 结果缓存，reload 时清空。

### 数据流

App 请求 → 弹窗 → 选择 → root 真授权（PARTIAL 额外 `pm grant READ_MEDIA_VISUAL_USER_SELECTED`，并把选择器 URI 交给 `yins.grant`）→ 写策略库 → `yins.reload` → 返回结果给 App。
已有策略的 App 再次请求时 `DecisionActivity` 不显示 UI，直接按预设重新授权并返回。
管理界面里改策略额外 `am force-stop`。

## 已知限制

- 空白 App 仍能看到子目录名，`stat` 猜路径可探测存在性；堵死需 native FUSE hook。
- 只拦截全部为存储 / 媒体权限的请求，混合请求放行给系统弹窗。
- 只处理主用户。
