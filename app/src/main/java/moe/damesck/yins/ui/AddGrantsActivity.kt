package moe.damesck.yins.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
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
            finish()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                MediaProviderClient.grant(this@AddGrantsActivity, target, currentUserId(), uris)
            }
            Toast.makeText(
                this@AddGrantsActivity,
                if (added >= 0) getString(R.string.grants_added, added) else getString(R.string.apply_failed, "grant"),
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
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
