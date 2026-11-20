package com.carlink.app.ui.home

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import com.carlink.app.R
import java.util.Locale
import kotlin.math.min

/** A native minute clock shared by Home and USB waiting. No custom tick loop or projection delay. */
open class IdleClockView(context: Context) : ScrollView(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val ink = Color.rgb(242, 246, 252)
    private val muted = Color.rgb(171, 187, 205)
    private val accent = Color.rgb(118, 216, 241)
    private val canvas = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val vehicle = text(16f, muted).apply {
        setText(R.string.carlink_f150_idle_vehicle)
        letterSpacing = .10f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val settings = button(R.string.carlink_f150_settings)
    private val clock = TextClock(context).apply {
        format12Hour = "h:mm"
        format24Hour = "HH:mm"
        setTextColor(ink)
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
        letterSpacing = -.045f
        includeFontPadding = false
        setSingleLine()
    }
    private val period = TextClock(context).apply {
        format12Hour = "a"
        format24Hour = ""
        setTextColor(accent)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        setPadding(dp(12), 0, 0, dp(10))
    }
    private val date = TextClock(context).apply {
        val locale = resources.configuration.locales[0] ?: Locale.getDefault()
        val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd")
        format12Hour = pattern
        format24Hour = pattern
        setTextColor(muted)
        gravity = Gravity.CENTER
        includeFontPadding = false
        setPadding(0, dp(18), 0, 0)
    }
    private val status = text(26f, ink).apply {
        gravity = Gravity.CENTER
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val statusDot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
    private val message = text(20f, muted).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(10), 0, 0)
    }
    private val usb = text(12f, muted).apply {
        setText(R.string.carlink_f150_idle_usb)
        letterSpacing = .16f
        gravity = Gravity.START
        setPadding(0, dp(6), 0, 0)
    }
    private val primary = button(R.string.carlink_f150_open)
    private val disconnect = button(R.string.carlink_f150_disconnect)
    private val actionRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(14), 0, 0)
    }

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        clipToPadding = false
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF152A3D.toInt(), 0xFF0D1723.toInt(), 0xFF090E16.toInt()))
        addView(canvas, LayoutParams(-1, -2))
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(vehicle)
                addView(usb)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(settings, LinearLayout.LayoutParams(-2, -2))
        }
        canvas.addView(header, LinearLayout.LayoutParams(-1, -2))
        val clockRegion = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(28))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER or Gravity.BOTTOM
                orientation = LinearLayout.HORIZONTAL
                addView(clock, LinearLayout.LayoutParams(-2, -2))
                addView(period, LinearLayout.LayoutParams(-2, -2))
            }, LinearLayout.LayoutParams(-1, -2))
            addView(date, LinearLayout.LayoutParams(-1, -2))
        }
        canvas.addView(clockRegion, LinearLayout.LayoutParams(-1, -2, 1f))
        canvas.addView(View(context).apply { setBackgroundColor(0xFF2C4053.toInt()) },
            LinearLayout.LayoutParams(dp(48), dp(2)).apply { gravity = Gravity.CENTER; bottomMargin = dp(22) })
        canvas.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(View(context).apply { background = statusDot }, LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(12)
            })
            addView(status, LinearLayout.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-1, -2))
        canvas.addView(message, LinearLayout.LayoutParams(-1, -2))
        actionRow.addView(primary, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        actionRow.addView(disconnect, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        canvas.addView(actionRow, LinearLayout.LayoutParams(-1, -2))
    }

    private var fittedWidth = -1
    private var fittedHeight = -1
    private var fittedFontScale = -1f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val fontScale = resources.configuration.fontScale
        // Text metrics and padding must be ready before children are measured (also in AndroidView).
        if (w != fittedWidth || h != fittedHeight || fontScale != fittedFontScale) {
            fittedWidth = w
            fittedHeight = h
            fittedFontScale = fontScale
            fitTypography(w, h)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun fitTypography(w: Int, h: Int) {
        val wide = w / density >= 600
        val pad = if (wide) 32 else 24
        canvas.setPadding(dp(pad), dp(12), dp(pad), dp(16))
        // Bound by both axes so short landscape and tall portrait retain room for connection help.
        clock.textSize = min(min(w / density * .25f, h / density * .30f), 190f).coerceAtLeast(58f) /
            resources.configuration.fontScale.coerceAtLeast(.5f)
        period.textSize = if (wide) 26f else 20f
        date.textSize = if (wide) 26f else 20f
        status.textSize = if (wide) 26f else 22f
        message.textSize = if (wide) 24f else 17f
        vehicle.textSize = if (wide) 18f else 13f
        usb.textSize = if (wide) 14f else 12f
        listOf(settings, primary, disconnect).forEach {
            it.textSize = if (wide) 24f else 17f
            it.minHeight = dp(if (wide) 76 else 56)
        }
    }

    fun bind(ui: IdleConnectionUi, onSettings: () -> Unit, onAction: (IdleAction) -> Unit) {
        status.setText(ui.status)
        val tone = when (ui.tone) {
            IdleTone.WAITING -> ink
            IdleTone.CONNECTING -> accent
            IdleTone.CONNECTED -> 0xFF8AE0B2.toInt()
            IdleTone.ATTENTION -> 0xFFFFC178.toInt()
        }
        status.setTextColor(tone)
        statusDot.setColor(tone)
        message.text = ui.notice ?: context.getString(ui.message)
        settings.setOnClickListener { onSettings() }
        primary.visibility = if (ui.action == null) View.GONE else View.VISIBLE
        ui.action?.let { action -> primary.setText(action.label); primary.setOnClickListener { onAction(action) } }
        disconnect.visibility = if (ui.canDisconnect) View.VISIBLE else View.GONE
        disconnect.setOnClickListener { onAction(IdleAction.DISCONNECT) }
        actionRow.visibility = if (ui.action != null || ui.canDisconnect) View.VISIBLE else View.GONE
        // A lone action uses the full row; do not leave an invisible weighted sibling's gap.
        (primary.layoutParams as LinearLayout.LayoutParams).marginEnd = if (ui.canDisconnect) dp(8) else 0
        (disconnect.layoutParams as LinearLayout.LayoutParams).marginStart = if (ui.action != null) dp(8) else 0
    }

    private fun text(size: Float, color: Int) = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        includeFontPadding = false
    }

    private fun button(label: Int) = Button(context).apply {
        setText(label)
        isAllCaps = false
        setTextColor(ink)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val fill = GradientDrawable().apply {
            setColor(0x142F5473)
            setStroke(dp(1), 0xFF3B5268.toInt())
            cornerRadius = dp(40).toFloat()
        }
        background = RippleDrawable(ColorStateList.valueOf(0x305BD6F0), fill, null)
        setPadding(dp(22), dp(8), dp(22), dp(8))
        minWidth = 0
        minHeight = dp(76)
    }
}
