package moe.damesck.yins.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.damesck.yins.R
import moe.damesck.yins.YLog
import moe.damesck.yins.data.AppKind
import moe.damesck.yins.data.MediaProviderClient
import moe.damesck.yins.data.Mode
import moe.damesck.yins.data.Policy
import moe.damesck.yins.data.PolicyRepository
import moe.damesck.yins.data.Recommendations
import moe.damesck.yins.hook.DecisionIntents
import moe.damesck.yins.root.NoRootException
import moe.damesck.yins.root.PermissionApplier
import moe.damesck.yins.ui.theme.YinsTheme

/**
 * The four-way dialog. Launched (for result) by the PermissionController hook and by the
 * redirected "all files access" intent.
 *
 * If a policy already exists for the target it is re-applied silently and the activity finishes
 * without UI; otherwise the user chooses and the choice is applied (root) and persisted.
 */
class DecisionActivity : ComponentActivity() {
    private lateinit var target: String
    private lateinit var permissions: List<String>
    private lateinit var source: String
    private val repo by lazy { PolicyRepository(this) }

    /** Drives the enter/exit animation of the sheet; false until the first frame, false again before finish(). */
    private val sheetVisible = mutableStateOf(false)
    private var showingUi = false

    /** Incremented when applying a choice failed, so the sheet re-enables its buttons. */
    private val failureTick = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        target = intent.getStringExtra(DecisionIntents.EXTRA_TARGET_PACKAGE) ?: run { finishCancelled(); return }
        permissions = intent.getStringArrayExtra(DecisionIntents.EXTRA_PERMISSIONS)?.toList()
            ?: AppCatalog.declaredStoragePermissions(this, target)
        source = intent.getStringExtra(DecisionIntents.EXTRA_SOURCE) ?: DecisionIntents.SOURCE_RUNTIME

        lifecycleScope.launch {
            val existing = repo.get(target, currentUserId())
            when {
                // A runtime permission request repeated by an app that already has a policy is
                // answered silently, otherwise the app would be able to nag forever.
                existing != null && source == DecisionIntents.SOURCE_RUNTIME -> {
                    YLog.i("re-applying ${existing.mode} to $target")
                    if (!applyAndFinish(existing.mode, persist = false)) showDialog(existing.mode)
                }
                // Opening the "all files access" page is the user's own action: let them revisit.
                else -> showDialog(existing?.mode)
            }
        }
    }

    private fun showDialog(current: Mode?) {
        if (showingUi) return
        showingUi = true
        val label = AppCatalog.label(this, target)
        val icon = appIcon(this, target)
        val wants = describePermissions(this, permissions)
        val kind = Recommendations.kind(this, target)
        val filesSource = source == DecisionIntents.SOURCE_ALL_FILES
        setContent {
            YinsTheme {
                DecisionSheet(
                    visible = sheetVisible.value,
                    failureTick = failureTick.value,
                    label = label,
                    icon = icon,
                    wants = wants,
                    kind = kind,
                    current = current,
                    pickFiles = filesSource,
                    onChoose = { mode -> lifecycleScope.launch { applyAndFinish(mode, persist = true) } },
                    onPartialWithPicker = { uris ->
                        lifecycleScope.launch {
                            applyAndFinish(Mode.PARTIAL, persist = true) {
                                if (uris.isNotEmpty()) {
                                    val added = MediaProviderClient.grant(this@DecisionActivity, target, currentUserId(), uris)
                                    if (added in 0 until uris.size) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(
                                                this@DecisionActivity,
                                                getString(R.string.grants_unresolved, uris.size - added),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onCancel = { finishCancelled() },
                )
            }
        }
        // The sheet's transition state starts hidden and animates towards this on first composition.
        sheetVisible.value = true
    }

    /**
     * Applies [mode] and, on success, returns the result to the caller and finishes.
     * On failure the activity stays up (the sheet re-enables itself) so the user can fix root
     * access and retry, instead of silently falling through to the system dialog.
     */
    private suspend fun applyAndFinish(mode: Mode, persist: Boolean, afterPersist: suspend () -> Unit = {}): Boolean {
        val result = PermissionApplier.apply(target, permissions, mode, forceStop = false)
        val error = result.exceptionOrNull()
        if (error != null) {
            YLog.w("apply $mode to $target failed", error)
            val message = if (error is NoRootException) getString(R.string.root_missing) else getString(R.string.apply_failed, error.message ?: "?")
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            failureTick.value++
            return false
        }
        if (persist) repo.set(Policy(target, currentUserId(), mode))
        withContext(Dispatchers.IO) { afterPersist() }
        setResult(RESULT_OK, Intent().putExtra(DecisionIntents.EXTRA_RESULT_MODE, mode.name))
        finishAnimated()
        return true
    }

    private fun finishCancelled() {
        setResult(RESULT_CANCELED)
        finishAnimated()
    }

    /** Slide the sheet out before the activity goes away; instant when no UI was ever shown. */
    private fun finishAnimated() {
        if (!showingUi || !sheetVisible.value) {
            finish()
            return
        }
        sheetVisible.value = false
        lifecycleScope.launch {
            delay(EXIT_MS.toLong() + 30)
            finish()
        }
    }

    private companion object {
        const val EXIT_MS = SHEET_EXIT_MS
    }
}

private val OPTIONS = listOf(Mode.FULL, Mode.BLANK, Mode.PARTIAL, Mode.DENY)
private const val ENTER_MS = 360
private const val SHEET_EXIT_MS = 240
private const val EXIT_MS = SHEET_EXIT_MS
private val EASE_OUT = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EASE_IN = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

@Composable
private fun DecisionSheet(
    visible: Boolean,
    failureTick: Int,
    label: String,
    icon: ImageBitmap?,
    wants: String,
    kind: AppKind,
    current: Mode?,
    pickFiles: Boolean,
    onChoose: (Mode) -> Unit,
    onPartialWithPicker: (List<Uri>) -> Unit,
    onCancel: () -> Unit,
) {
    val recommended = Recommendations.mode(kind)
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) busy = false else onPartialWithPicker(uris)
    }
    // For "all files access" requests the partial choice goes through the system file picker,
    // so documents, archives and the like can be granted as well as photos.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) busy = false else onPartialWithPicker(uris)
    }
    BackHandler(enabled = !busy) { onCancel() }
    // A failed apply (e.g. no root) hands control back to the user.
    LaunchedEffect(failureTick) { if (failureTick > 0) busy = false }

    // Start hidden on the very first composition, then animate towards [visible]; passing
    // `visible` straight in would compose the sheet already on screen and skip the slide-in.
    val transition = remember { MutableTransitionState(false) }
    LaunchedEffect(visible) { transition.targetState = visible }

    Box(Modifier.fillMaxSize()) {
        // Scrim
        AnimatedVisibility(
            visibleState = transition,
            enter = fadeIn(tween(ENTER_MS)),
            exit = fadeOut(tween(EXIT_MS)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(enabled = !busy, onClick = onCancel),
            )
        }

        // Sheet
        AnimatedVisibility(
            visibleState = transition,
            enter = slideInVertically(tween(ENTER_MS, easing = EASE_OUT)) { it },
            exit = slideOutVertically(tween(EXIT_MS, easing = EASE_IN)) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    // Consume taps on the sheet itself so only the scrim cancels.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(
                    Modifier
                        .navigationBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
                ) {
                    DragHandle()
                    Spacer(Modifier.height(16.dp))
                    Header(label, icon, wants)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(kind.reasonRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    Spacer(Modifier.height(14.dp))

                    for (mode in OPTIONS) {
                        OptionRow(
                            mode = mode,
                            recommended = mode == recommended,
                            current = mode == current,
                            enabled = !busy,
                            onClick = {
                                busy = true
                                if (mode == Mode.PARTIAL) {
                                    if (pickFiles) {
                                        filePicker.launch(arrayOf("*/*"))
                                    } else {
                                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                                    }
                                } else {
                                    onChoose(mode)
                                }
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (busy) stringResource(R.string.decision_applying) else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onCancel, enabled = !busy) { Text(stringResource(android.R.string.cancel)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DragHandle() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun Header(label: String, icon: ImageBitmap?, wants: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIconImage(icon, 48.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.decision_wants, wants),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OptionRow(mode: Mode, recommended: Boolean, current: Boolean, enabled: Boolean, onClick: () -> Unit) {
    ModeCard(
        mode = mode,
        highlighted = recommended,
        tag = when {
            current -> ModeTag.CURRENT
            recommended -> ModeTag.RECOMMENDED
            else -> null
        },
        enabled = enabled,
        onClick = onClick,
    )
}
