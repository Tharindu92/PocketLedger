package com.billbook.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.fragment.app.FragmentActivity
import com.billbook.app.security.AppLock
import com.billbook.app.ui.AppNav
import com.billbook.app.ui.LockScreen
import com.billbook.app.ui.theme.BillBookTheme

// FragmentActivity (a ComponentActivity) because the fingerprint prompt needs it.
class MainActivity : FragmentActivity() {

    /** Screen requested by a notification tap, e.g. [OPEN_BANK]. */
    private val openRequest = mutableStateOf<String?>(null)

    private val settings get() = (application as BillBookApp).settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ always draws apps edge to edge; do it on older versions too so screens look the same.
        enableEdgeToEdge()
        if (savedInstanceState == null) openRequest.value = intent?.getStringExtra(EXTRA_OPEN)
        if (!lockInitialised) {
            // Fresh start of the app (process): locked if the user turned the lock on.
            locked.value = settings.appLock
            lockInitialised = true
        }
        setContent {
            BillBookTheme {
                LaunchedEffect(settings.appLock) { hideInRecents(settings.appLock) }
                LaunchedEffect(locked.value) { if (locked.value) unlock() }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        // Kept composed underneath so the open screen survives locking, but hidden
                        // from screen readers while locked.
                        Box(if (locked.value) Modifier.clearAndSetSemantics {} else Modifier) {
                            AppNav(openRequest = openRequest.value, onOpenHandled = { openRequest.value = null })
                        }
                        if (locked.value) LockScreen(onUnlock = ::unlock)
                    }
                }
            }
        }
    }

    private fun unlock() {
        if (!AppLock.isAvailable(this)) {
            // The phone's screen lock was removed: there is nothing to check against any more.
            settings.updateAppLock(false)
            locked.value = false
            Toast.makeText(this, "App lock turned off: this phone has no screen lock", Toast.LENGTH_LONG).show()
            return
        }
        AppLock.authenticate(this, "Unlock Pocket Ledger", onSuccess = { locked.value = false })
    }

    /** No preview of entries in the recent-apps view while the lock is on. */
    private fun hideInRecents(hide: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) {
            // Screenshots keep working; only the recents thumbnail is blanked.
            setRecentsScreenshotEnabled(!hide)
        } else if (hide) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onStart() {
        super.onStart()
        if (settings.appLock && leftAt > 0 && SystemClock.elapsedRealtime() - leftAt > AppLock.GRACE_MILLIS) {
            locked.value = true
        }
        leftAt = 0
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) leftAt = SystemClock.elapsedRealtime()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequest.value = intent.getStringExtra(EXTRA_OPEN)
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_BANK = "bank"

        // Per app process, so rotating the screen doesn't lock again.
        private val locked = mutableStateOf(false)
        private var lockInitialised = false
        private var leftAt = 0L
    }
}
