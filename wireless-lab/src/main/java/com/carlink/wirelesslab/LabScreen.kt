package com.carlink.wirelesslab

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Native views keep this small diagnostic APK independent of the projection app. */
internal class LabScreen(context: Context) : ScrollView(context) {
    private val car = resources.configuration.screenWidthDp >= 600
    private val bodySize = if (car) 24f else 18f
    private val ink = Color.rgb(235, 242, 247)
    private val muted = Color.rgb(171, 189, 201)
    private val accent = Color.rgb(104, 223, 213)
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(if (car) 32 else 20), dp(24), dp(if (car) 32 else 20), dp(32))
    }
    private val status: TextView
    private val check: Button
    private val share: Button
    private val note: TextView
    private val report: TextView
    var onCheck: () -> Unit = {}
    var onShare: () -> Unit = {}
    var onAbout: () -> Unit = {}

    init {
        isFillViewport = true
        setBackgroundColor(Color.rgb(11, 16, 22))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        text(context.getString(R.string.carlink_lab_app_name), bodySize, accent, 0)
        text(context.getString(R.string.carlink_lab_heading), if (car) 38f else 30f, ink, 10).apply {
            setTypeface(typeface, Typeface.BOLD)
        }
        text(context.getString(R.string.carlink_lab_intro), bodySize, muted, 14)
        status = text(context.getString(R.string.carlink_lab_ready), if (car) 28f else 22f, ink, 28).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTypeface(typeface, Typeface.BOLD)
        }
        check = action(R.string.carlink_lab_check, primary = true) { onCheck() }
        share = action(R.string.carlink_lab_share) { onShare() }
        note = text(context.getString(R.string.carlink_lab_initial_note), bodySize, muted, 20)
        report = text("", if (car) 22f else 17f, ink, 24).apply {
            setTextIsSelectable(true)
            setLineSpacing(dp(5).toFloat(), 1f)
            visibility = View.GONE
        }
        action(R.string.carlink_lab_about) { onAbout() }
        showState(null, busy = false)
    }

    fun showState(reportText: String?, busy: Boolean, message: Int? = null) {
        check.isEnabled = !busy
        check.alpha = if (busy) 0.45f else 1f
        check.setText(if (reportText == null) R.string.carlink_lab_check else R.string.carlink_lab_check_again)
        share.isEnabled = !busy && reportText != null
        share.alpha = if (share.isEnabled) 1f else 0.4f
        status.setText(message ?: when {
            busy -> R.string.carlink_lab_checking
            reportText != null -> R.string.carlink_lab_complete
            else -> R.string.carlink_lab_ready
        })
        note.setText(if (reportText == null) R.string.carlink_lab_initial_note else R.string.carlink_lab_next)
        report.text = reportText.orEmpty()
        report.visibility = if (reportText == null) View.GONE else View.VISIBLE
    }

    private fun text(value: String, size: Float, color: Int, top: Int): TextView = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        content.addView(this, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
        })
    }

    private fun action(label: Int, primary: Boolean = false, click: () -> Unit): Button = Button(context).apply {
        setText(label)
        isAllCaps = false
        textSize = bodySize
        setTextColor(if (primary) Color.rgb(11, 30, 33) else ink)
        minHeight = dp(if (car) 76 else 64)
        minimumHeight = minHeight
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (primary) accent else Color.rgb(25, 39, 51))
        }
        setOnClickListener { click() }
        content.addView(this, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
