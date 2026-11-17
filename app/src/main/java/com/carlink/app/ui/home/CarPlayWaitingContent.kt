package com.carlink.app.ui.home

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.carlink.app.R
import com.carlink.app.ui.HomeActivity
import com.carlink.core.connection.Connection
import com.carlink.core.connection.toLinkState
import com.carlink.core.core.LinkStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Lightweight native waiting view: no Compose/font/GPU-probe startup beside USB authentication. */
object CarPlayWaitingContent {
    fun create(context: Context): View {
        val session = Connection.get(context)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 30f
            gravity = Gravity.CENTER
        }
        val body = TextView(context).apply {
            setTextColor(0xFFB8C4D2.toInt())
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(28))
        }
        return object : LinearLayout(context) {
            private var scope: CoroutineScope? = null
            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { owner ->
                    owner.launch {
                        session.state.collect { state ->
                            val failed = state.toLinkState().stage == LinkStage.FAILED
                            title.setText(if (failed) R.string.carlink_f150_failed else R.string.carlink_f150_connecting)
                            body.setText(if (failed) R.string.carlink_f150_failed_body else R.string.carlink_f150_connecting_body)
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
            layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF0C1016.toInt())
            setPadding(dp(28), dp(28), dp(28), dp(28))
            addView(title, LinearLayout.LayoutParams(-1, -2))
            addView(body, LinearLayout.LayoutParams(-1, -2))
            addView(Button(context).apply {
                setText(R.string.carlink_f150_back)
                textSize = 22f
                minHeight = dp(76)
                setOnClickListener {
                    context.startActivity(Intent(context, HomeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }, LinearLayout.LayoutParams(-2, -2))
        }
    }
}
