package moe.damesck.yins.ui

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.damesck.yins.ModuleStatus
import moe.damesck.yins.R
import moe.damesck.yins.data.MediaProviderClient
import moe.damesck.yins.data.Mode
import moe.damesck.yins.data.Policy
import moe.damesck.yins.data.PolicyContract
import moe.damesck.yins.data.PolicyRepository
import moe.damesck.yins.hook.mediaprovider.MediaProviderHooks
import moe.damesck.yins.root.PermissionApplier
import moe.damesck.yins.ui.theme.YinsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YinsTheme { MainScreen() }
        }
    }
}

private enum class Filter { ALL, MANAGED, UNMANAGED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val repo = remember { PolicyRepository(context) }
    val scope = rememberCoroutineScope()

    var includeSystem by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(Filter.ALL) }
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var selected by remember { mutableStateOf<AppInfo?>(null) }
    val policies by repo.observeAll().collectAsState(initial = emptyList())
    val policyByPackage = remember(policies) { policies.associateBy { it.packageName } }

    LaunchedEffect(includeSystem) { apps = AppCatalog.load(context, includeSystem) }
    // Opening the manager is a cheap moment to make sure MediaProvider has the current policies.
    LaunchedEffect(Unit) { repo.push() }

    // Needed for the "app is reading your photos" Live Update.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // MediaProvider keeps running the hook code it was started with; detect a stale one.
    var hookStale by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        hookStale = withContext(Dispatchers.IO) {
            val reply = MediaProviderClient.ping(context)
            reply != null && reply.getInt(PolicyContract.EXTRA_HOOK_VERSION, 0) < MediaProviderHooks.HOOK_REVISION
        }
    }

    val visible = remember(apps, query, filter, policyByPackage) {
        apps.asSequence()
            .filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
            .filter {
                when (filter) {
                    Filter.ALL -> true
                    Filter.MANAGED -> it.packageName in policyByPackage
                    Filter.UNMANAGED -> it.packageName !in policyByPackage
                }
            }
            .toList()
    }
    val (managed, unmanaged) = remember(visible, policyByPackage) { visible.partition { it.packageName in policyByPackage } }
    val managedCount = remember(apps, policyByPackage) { apps.count { it.packageName in policyByPackage } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = navBottom + 24.dp),
        ) {
            item(key = "status") {
                StatusCard(
                    active = ModuleStatus.isActive(),
                    stale = hookStale,
                    managedCount = managedCount,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item(key = "search") {
                SearchField(query, onChange = { query = it }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            item(key = "filters") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (f in Filter.entries) {
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(stringResource(f.labelRes())) },
                        )
                    }
                    FilterChip(
                        selected = includeSystem,
                        onClick = { includeSystem = !includeSystem },
                        label = { Text(stringResource(R.string.filter_system)) },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            if (visible.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.empty_list),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            if (managed.isNotEmpty() && filter == Filter.ALL) {
                item(key = "h-managed") { SectionHeader(stringResource(R.string.section_managed), managed.size) }
            }
            items(managed, key = { "m:" + it.packageName }) { app ->
                AppRow(app, policyByPackage[app.packageName]?.mode) { selected = app }
            }
            if (unmanaged.isNotEmpty() && filter == Filter.ALL && managed.isNotEmpty()) {
                item(key = "h-unmanaged") { SectionHeader(stringResource(R.string.section_unmanaged), unmanaged.size) }
            }
            items(unmanaged, key = { "u:" + it.packageName }) { app ->
                AppRow(app, null) { selected = app }
            }
        }
    }

    selected?.let { app ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { selected = null }, sheetState = sheetState) {
            AppDetailSheet(
                app = app,
                current = policyByPackage[app.packageName]?.mode,
                onApply = { mode ->
                    scope.launch {
                        val result = PermissionApplier.apply(app.packageName, app.storagePermissions, mode, forceStop = true)
                        result.onFailure {
                            Toast.makeText(context, context.getString(R.string.apply_failed, it.message), Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        repo.set(Policy(app.packageName, mode = mode))
                        Toast.makeText(context, R.string.applied, Toast.LENGTH_SHORT).show()
                    }
                },
                onRemove = {
                    scope.launch {
                        repo.remove(app.packageName)
                        selected = null
                    }
                },
            )
        }
    }
}

private fun Filter.labelRes(): Int = when (this) {
    Filter.ALL -> R.string.filter_all
    Filter.MANAGED -> R.string.filter_managed
    Filter.UNMANAGED -> R.string.filter_unmanaged
}

@Composable
private fun StatusCard(active: Boolean, stale: Boolean, managedCount: Int, modifier: Modifier = Modifier) {
    data class Look(val icon: ImageVector, val title: String, val body: String, val bg: androidx.compose.ui.graphics.Color, val fg: androidx.compose.ui.graphics.Color)
    val scheme = MaterialTheme.colorScheme
    val look = when {
        !active -> Look(
            Icons.Outlined.ErrorOutline,
            stringResource(R.string.module_inactive),
            stringResource(R.string.module_inactive_desc),
            scheme.errorContainer,
            scheme.onErrorContainer,
        )
        stale -> Look(
            Icons.Outlined.RestartAlt,
            stringResource(R.string.module_stale),
            stringResource(R.string.module_stale_desc),
            scheme.tertiaryContainer,
            scheme.onTertiaryContainer,
        )
        else -> Look(
            Icons.Outlined.Shield,
            stringResource(R.string.module_active),
            stringResource(R.string.status_managed_count, managedCount),
            scheme.primaryContainer,
            scheme.onPrimaryContainer,
        )
    }
    Surface(color = look.bg, shape = RoundedCornerShape(20.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(look.fg.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(look.icon, contentDescription = null, tint = look.fg, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(look.title, style = MaterialTheme.typography.titleMedium, color = look.fg)
                Text(look.body, style = MaterialTheme.typography.bodySmall, color = look.fg.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clear_search))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionHeader(text: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppRow(app: AppInfo, mode: Mode?, onClick: () -> Unit) {
    val context = LocalContext.current
    val icon = remember(app.packageName) { appIcon(context, app.packageName, sizePx = 120) }
    val wants = remember(app.storagePermissions) { describePermissions(context, app.storagePermissions) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconImage(icon, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                wants,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (mode != null) {
            Spacer(Modifier.width(8.dp))
            ModePill(mode)
        }
    }
}
