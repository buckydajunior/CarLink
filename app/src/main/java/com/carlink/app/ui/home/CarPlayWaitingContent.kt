package com.carlink.app.ui.home

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.carlink.app.ui.HomeActivity
import com.carlink.core.connection.Connection
import com.carlink.core.connection.toLinkState
import com.shilapi.xcertplay.CarPlayHostActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** No Compose/font/GPU-probe warmup beside authentication; the clock ticks only while attached. */
object CarPlayWaitingContent {
    fun create(context: Context): View {
        val session = Connection.get(context)
        fun home(settings: Boolean = false) {
            context.startActivity(if (settings) HomeActivity.carSettingsIntent(context)
                else HomeActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        return object : IdleClockView(context) {
            private var scope: CoroutineScope? = null
            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                ViewCompat.requestApplyInsets(this)
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { owner ->
                    owner.launch {
                        session.state.collect { state ->
                            bind(IdleConnectionUi.from(state.toLinkState()), onSettings = { home(true) }) { action ->
                                when (action) {
                                    IdleAction.RETRY -> session.retry()
                                    IdleAction.DISCONNECT -> session.disconnect()
                                    IdleAction.CONNECT -> session.connectUsb()
                                    IdleAction.ALLOW -> home()
                                    IdleAction.OPEN -> context.startActivity(Intent(context, CarPlayHostActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                                }
                            }
                        }
                    }
                }
            }
            override fun onDetachedFromWindow() {
                scope?.cancel()
                scope = null
                super.onDetachedFromWindow()
            }
        }.apply {
            // The engine leaves host waiting content full-bleed; Home handles its own safeDrawing.
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        }
    }
}
