package moe.damesck.yins.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.damesck.yins.R
import moe.damesck.yins.data.MediaProviderClient
import moe.damesck.yins.data.Mode
import moe.damesck.yins.data.PolicyRepository
import moe.damesck.yins.notify.AccessNotifications

/**
 * Opened from the "app is reading your photos" notification: straight into the system photo
 * picker, then the selection is added to the app's media_grants. No UI of its own.
 */
class AddGrantsActivity : ComponentActivity() {
    private lateinit var target: String

    private val picker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) {
            returnToApp()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                MediaProviderClient.grant(this@AddGrantsActivity, target, currentUserId(), uris)
            }
            if (added > 0) moe.damesck.yins.data.GrantTimers.onGranted(this@AddGrantsActivity, target, currentUserId())
            // The app that's reading photos has already loaded its list, so the newly granted items
            // only show up after it re-queries: tell the user to back out one level and re-enter.
            val message = if (added >= 0) {
                getString(R.string.grants_added_reenter, added)
            } else {
                getString(R.string.apply_failed, "grant")
            }
            // Post the toast while we are still the foreground app (ColorOS suppresses toasts from
            // background apps), then give it a moment before we drop back to the original app.
            Toast.makeText(this@AddGrantsActivity, message, Toast.LENGTH_LONG).show()
            delay(500)
            returnToApp()
        }
    }

    /**
     * We run in our own isolated task (distinct taskAffinity), launched from the notification on
     * top of the app that was reading photos. Removing our task returns the user to whatever was in
     * front before — the original app — without us starting it ourselves (starting it would trip
     * ColorOS's "X wants to open Y" cross-app-launch confirmation).
     */
    private fun returnToApp() {
        finishAndRemoveTask()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: run { finish(); return }
        AccessNotifications.cancel(this, target)
        lifecycleScope.launch {
            val policy = PolicyRepository(this@AddGrantsActivity).get(target, currentUserId())
            if (policy?.mode != Mode.PARTIAL) {
                finish()
                return@launch
            }
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
    }

    companion object {
        const val EXTRA_TARGET_PACKAGE = "yins.targetPackage"
    }
}
