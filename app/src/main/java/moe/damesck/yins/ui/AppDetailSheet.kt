package moe.damesck.yins.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.damesck.yins.R
import moe.damesck.yins.data.MediaProviderClient
import moe.damesck.yins.data.Mode
import moe.damesck.yins.data.PolicyContract
import moe.damesck.yins.data.Recommendations

/** Per-app editor shown in the manager's bottom sheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppDetailSheet(
    app: AppInfo,
    current: Mode?,
    onApply: (Mode) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kind = remember(app.packageName) { Recommendations.kind(context, app.packageName) }
    val recommended = Recommendations.mode(kind)
    var choice by remember(app.packageName, current) { mutableStateOf(current ?: recommended) }
    val icon = remember(app.packageName) { appIcon(context, app.packageName, sizePx = 168) }
    val tags = remember(app.storagePermissions) { permissionTags(context, app.storagePermissions) }
    val allFilesTag = stringResource(R.string.perm_all_files)
    val wantsAllFiles = app.storagePermissions.any { it == PolicyContract.PERM_MANAGE_EXTERNAL_STORAGE }

    fun grant(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val added = withContext(Dispatchers.IO) { MediaProviderClient.grant(context, app.packageName, currentUserId(), uris) }
            val text = when {
                added < 0 -> context.getString(R.string.apply_failed, "grant")
                added < uris.size -> context.getString(R.string.grants_added, added) + "，" + context.getString(R.string.grants_unresolved, uris.size - added)
                else -> context.getString(R.string.grants_added, added)
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { grant(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { grant(it) }

    // Cap the height so the sheet never grows past the status bar (which would push the drag
    // handle off-screen); anything beyond scrolls inside the sheet instead.
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp - 160.dp
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
    ) {
        // Header: icon, name, package.
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconImage(icon, 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // What the app declares, as short pills instead of raw permission names.
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (tag in tags) PermissionTag(tag, emphasized = tag == allFilesTag)
        }

        if (wantsAllFiles && (choice == Mode.BLANK || choice == Mode.PARTIAL)) {
            Spacer(Modifier.height(12.dp))
            InfoCard(stringResource(R.string.hint_all_files_scope))
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel(stringResource(R.string.section_policy))
        if (current == null) {
            Text(
                stringResource(kind.reasonRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        for (mode in Mode.entries) {
            ModeCard(
                mode = mode,
                highlighted = mode == choice,
                tag = when {
                    mode == current -> ModeTag.CURRENT
                    current == null && mode == recommended -> ModeTag.RECOMMENDED
                    else -> null
                },
                onClick = { choice = mode },
            )
            Spacer(Modifier.height(6.dp))
        }

        if (current == Mode.PARTIAL && choice == Mode.PARTIAL) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.section_grants), modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { MediaProviderClient.clearGrants(context, app.packageName, currentUserId()) }
                            Toast.makeText(context, R.string.grants_cleared, Toast.LENGTH_SHORT).show()
                        }
                    },
                ) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_clear_grants))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconTonalButton(
                    icon = Icons.Outlined.AddPhotoAlternate,
                    text = stringResource(R.string.action_pick_media),
                    modifier = Modifier.weight(1f),
                ) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
                IconTonalButton(
                    icon = Icons.Outlined.NoteAdd,
                    text = stringResource(R.string.action_pick_files),
                    modifier = Modifier.weight(1f),
                ) { filePicker.launch(arrayOf("*/*")) }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { onApply(choice) },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(stringResource(R.string.mode_apply), style = MaterialTheme.typography.titleMedium)
            }
            if (current != null) {
                TextButton(onClick = onRemove, modifier = Modifier.height(48.dp)) {
                    Text(stringResource(R.string.action_remove_policy), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

@Composable
private fun InfoCard(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun IconTonalButton(icon: ImageVector, text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(44.dp), shape = RoundedCornerShape(14.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, maxLines = 1)
    }
}

fun currentUserId(): Int = android.os.Process.myUid() / 100_000
