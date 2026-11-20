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
import com.carlink.core.connection.Connection
import com.carlink.app.ui.theme.CarLinkTheme
import com.carlink.app.ui.theme.UiProfileDetector

/** CarLink's launcher and hub. Hosts every CarLink screen (see ui/CarLinkApp.kt). */
class HomeActivity : ComponentActivity() {
    private val vm: CarLinkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) consumeCarSettings(intent)
        setContent {
            val display by vm.display.collectAsStateWithLifecycle()
            val profile = remember(display.uiProfileMode) { UiProfileDetector.detect(this, display.uiProfileMode) }
            CarLinkTheme(profile, display.themeMode, display.interfaceScale, display.liteRendering) {
                androidx.compose.foundation.layout.Box {
                    CarLinkApp(vm, onExitToCarHome = ::exitToCarHome)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Connection.onHomeShown(this)
        vm.refresh()
    }

    /** Back on Home goes to the car's own launcher, without ending a live phone session. */
    private fun exitToCarHome() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(home) }.onFailure { moveTaskToBack(true) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeCarSettings(intent)
    }

    /** The car tile in CarPlay opens Settings; the phone session keeps running. */
    private fun consumeCarSettings(intent: Intent) {
        if (intent.getStringExtra("page") != PAGE_SETTINGS) return
        intent.removeExtra("page")
        vm.openSettingsFromCar()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, HomeActivity::class.java)

        /** DiPlay's page name for its settings, also used by CarPlay's "CarLink settings" menu button. */
        private const val PAGE_SETTINGS = "settings"

        /** Settings over a running projection, for the engines' car tile (CarLinkApplication). */
        fun carSettingsIntent(context: Context): Intent = intent(context).putExtra("page", PAGE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }
}
