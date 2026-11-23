package com.carlink.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.carlink.core.connection.Connection
import com.carlink.core.entry.AfterUserUnlock
import com.carlink.app.entry.HomeLauncher
import com.carlink.app.ui.theme.CarLinkTheme
import com.carlink.app.ui.theme.UiProfileDetector
import kotlinx.coroutines.launch

/** CarLink's launcher and hub. Hosts every CarLink screen (see ui/CarLinkApp.kt). */
class HomeActivity : ComponentActivity() {
    private val vm: CarLinkViewModel by viewModels()
    private var ready = false
    private var newIntentBeforeSetup = false
    private var pendingSetup: AfterUserUnlock.Pending? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingSetup = AfterUserUnlock.run(this, this) {
            if (!isFinishing && !isDestroyed) initializeUi(savedInstanceState)
        }
    }

    private fun initializeUi(savedInstanceState: Bundle?) {
        if (ready) return
        ready = true
        enableEdgeToEdge()
        if (savedInstanceState?.getBoolean(STATE_USB_STARTUP) == true) vm.requestUsbStartupProjection()
        // Android can retain the original launch extras after recreation. The saved pending
        // flag is authoritative then; only a genuinely new intent may arm another handoff.
        if (savedInstanceState == null || newIntentBeforeSetup) consumeLaunchIntent(intent)
        setContent {
            val display by vm.display.collectAsStateWithLifecycle()
            val profile = remember(display.uiProfileMode) { UiProfileDetector.detect(this, display.uiProfileMode) }
            CarLinkTheme(profile, display.themeMode, display.interfaceScale, display.liteRendering) {
                androidx.compose.foundation.layout.Box {
                    CarLinkApp(vm, onExitToCarHome = ::exitToCarHome)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                vm.automaticProjectionReady.collect { open -> if (open) vm.openStartupProjectionIfReady() }
            }
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) refreshHome()
    }

    override fun onStart() {
        super.onStart()
        HomeLauncher.homeStarted(this)
    }

    override fun onResume() {
        super.onResume()
        if (ready) refreshHome()
    }

    private fun refreshHome() {
        Connection.onHomeShown(this)
        vm.refresh()
    }

    internal fun armUsbStartupProjection() {
        if (ready && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && vm.isHomeRouteVisible) {
            vm.requestUsbStartupProjection()
        }
    }

    override fun onStop() {
        HomeLauncher.homeStopped(this)
        super.onStop()
    }

    /** Back on Home goes to the car's own launcher, without ending a live phone session. */
    private fun exitToCarHome() {
        vm.cancelAutomaticProjection()
        HomeLauncher.homeStopped(this)
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(home) }.onFailure { moveTaskToBack(true) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (ready) consumeLaunchIntent(intent)
        else newIntentBeforeSetup = true
    }

    /** The car tile overrides a pending startup handoff; USB markers are consumed once. */
    private fun consumeLaunchIntent(intent: Intent) {
        val settings = intent.getStringExtra("page") == PAGE_SETTINGS
        val usbStartup = intent.getBooleanExtra(EXTRA_USB_STARTUP, false)
        intent.removeExtra("page")
        intent.removeExtra(EXTRA_USB_STARTUP)
        if (settings) vm.openSettingsFromCar()
        else if (usbStartup) vm.requestUsbStartupProjection()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (ready) outState.putBoolean(STATE_USB_STARTUP, vm.usbStartupPending)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        HomeLauncher.homeStopped(this)
        pendingSetup?.cancel()
        pendingSetup = null
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, HomeActivity::class.java)
        fun usbStartupIntent(context: Context): Intent = intent(context).putExtra(EXTRA_USB_STARTUP, true)
        private const val EXTRA_USB_STARTUP = "com.carlink.app.USB_STARTUP"
        private const val STATE_USB_STARTUP = "pending_usb_projection"

        /** DiPlay's page name for its settings, also used by CarPlay's "CarLink settings" menu button. */
        private const val PAGE_SETTINGS = "settings"

        /** Settings over a running projection, for the engines' car tile (CarLinkApplication). */
        fun carSettingsIntent(context: Context): Intent = intent(context).putExtra("page", PAGE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }
}
