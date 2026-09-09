package moe.damesck.yins.ui

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import moe.damesck.yins.R
import moe.damesck.yins.data.AppKind
import moe.damesck.yins.data.Mode
import moe.damesck.yins.data.PolicyContract

@StringRes
fun Mode?.labelRes(): Int = when (this) {
    Mode.FULL -> R.string.mode_full
    Mode.BLANK -> R.string.mode_blank
    Mode.PARTIAL -> R.string.mode_partial
    Mode.DENY -> R.string.mode_deny
    null -> R.string.mode_none
}

@StringRes
fun Mode.descriptionRes(): Int = when (this) {
    Mode.FULL -> R.string.mode_full_desc
    Mode.BLANK -> R.string.mode_blank_desc
    Mode.PARTIAL -> R.string.mode_partial_desc
    Mode.DENY -> R.string.mode_deny_desc
}

@StringRes
fun AppKind.reasonRes(): Int = when (this) {
    AppKind.TRUSTED -> R.string.reason_trusted
    AppKind.TEMPORARY -> R.string.reason_temporary
    AppKind.UNKNOWN -> R.string.reason_unknown
}

fun Mode.icon(): ImageVector = when (this) {
    Mode.FULL -> Icons.Outlined.CheckCircle
    Mode.BLANK -> Icons.Outlined.VisibilityOff
    Mode.PARTIAL -> Icons.Outlined.PhotoLibrary
    Mode.DENY -> Icons.Outlined.Block
}

/** Short human names for the permissions shown in dialogs. */
fun permissionShortName(permission: String): String = permission.removePrefix("android.permission.")

/** Human-readable, de-duplicated names such as ["照片", "视频"] or ["全部文件"]. */
fun permissionTags(context: Context, permissions: Collection<String>): List<String> {
    val names = LinkedHashSet<String>()
    for (p in permissions) {
        val res = when (p) {
            PolicyContract.PERM_MANAGE_EXTERNAL_STORAGE -> R.string.perm_all_files
            PolicyContract.PERM_READ_MEDIA_IMAGES -> R.string.perm_images
            PolicyContract.PERM_READ_MEDIA_VIDEO -> R.string.perm_video
            PolicyContract.PERM_READ_MEDIA_AUDIO -> R.string.perm_audio
            PolicyContract.PERM_READ_MEDIA_VISUAL_USER_SELECTED -> R.string.perm_images
            PolicyContract.PERM_READ_EXTERNAL_STORAGE,
            PolicyContract.PERM_WRITE_EXTERNAL_STORAGE -> R.string.perm_storage
            PolicyContract.PERM_ACCESS_MEDIA_LOCATION -> R.string.perm_media_location
            else -> null
        }
        names += res?.let { context.getString(it) } ?: permissionShortName(p)
    }
    return names.toList()
}

/** Human-readable, de-duplicated summary such as "照片、视频" or "全部文件访问". */
fun describePermissions(context: Context, permissions: Collection<String>): String =
    permissionTags(context, permissions).joinToString("、")

/** Small badge shown next to a mode's name. */
enum class ModeTag { CURRENT, RECOMMENDED }

/**
 * One selectable mode, drawn as a rounded card. Shared by the permission dialog and the
 * manager's per-app sheet so both look the same.
 */
@Composable
fun ModeCard(
    mode: Mode,
    highlighted: Boolean,
    tag: ModeTag? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val container = when {
        highlighted -> MaterialTheme.colorScheme.primaryContainer
        mode == Mode.DENY -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when {
        highlighted -> MaterialTheme.colorScheme.onPrimaryContainer
        mode == Mode.DENY -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val shape = RoundedCornerShape(18.dp)
    Surface(
        color = container,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.6f),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(mode.icon(), contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(mode.labelRes()),
                        style = MaterialTheme.typography.titleMedium,
                        color = content,
                    )
                    if (tag != null) {
                        Spacer(Modifier.width(8.dp))
                        val (text, bg, fg) = when (tag) {
                            ModeTag.CURRENT -> Triple(
                                stringResource(R.string.decision_current),
                                MaterialTheme.colorScheme.tertiary,
                                MaterialTheme.colorScheme.onTertiary,
                            )
                            ModeTag.RECOMMENDED -> Triple(
                                stringResource(R.string.decision_recommended),
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                        Text(
                            text,
                            style = MaterialTheme.typography.labelSmall,
                            color = fg,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(bg)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    stringResource(mode.descriptionRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/** Compact tinted pill naming a mode, used in list rows. */
@Composable
fun ModePill(mode: Mode) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (mode) {
        Mode.FULL -> scheme.primaryContainer to scheme.onPrimaryContainer
        Mode.BLANK -> scheme.secondaryContainer to scheme.onSecondaryContainer
        Mode.PARTIAL -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        Mode.DENY -> scheme.errorContainer to scheme.onErrorContainer
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(mode.icon(), contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(mode.labelRes()), style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/** Pill with a short permission name, e.g. "照片". */
@Composable
fun PermissionTag(text: String, emphasized: Boolean = false) {
    val bg = if (emphasized) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (emphasized) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** App icon or a neutral placeholder of the same size. */
@Composable
fun AppIconImage(icon: ImageBitmap?, size: Dp) {
    val shape = RoundedCornerShape(size / 4)
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = Modifier.size(size).clip(shape))
    } else {
        Box(
            Modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
    }
}

fun appIcon(context: Context, packageName: String, sizePx: Int = 144): ImageBitmap? = try {
    val drawable: Drawable = context.packageManager.getApplicationIcon(packageName)
    drawable.toBitmap(sizePx, sizePx).asImageBitmap()
} catch (t: Throwable) {
    null
}
