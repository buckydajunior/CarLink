package com.carlink.app.ui.home

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextClock
import android.widget.TextView
import com.carlink.app.R
import com.carlink.core.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class IdleClockViewTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun state(stage: LinkStage, problem: LinkProblem? = null) =
        LinkState(stage, Engine.CARPLAY, Transport.USB, problem = problem)

    @Test fun connectionStagesNeverShowConnectedBeforeTheSessionIsReady() {
        val statuses = listOf(LinkStage.IDLE, LinkStage.WAITING, LinkStage.CONNECTING, LinkStage.FAILED).map {
            IdleConnectionUi.from(state(it))
        }
        assertTrue(statuses.none { it.status == R.string.carlink_f150_idle_connected || it.action == IdleAction.OPEN })
        assertEquals(IdleAction.OPEN, IdleConnectionUi.from(state(LinkStage.CONNECTED)).action)
    }

    @Test fun permissionAndAuthenticationFailuresOfferTheAppropriateRecovery() {
        assertEquals(IdleAction.ALLOW, IdleConnectionUi.from(state(LinkStage.FAILED, LinkProblem.VPN_CONSENT)).action)
        assertEquals(IdleAction.ALLOW, IdleConnectionUi.from(LinkState.Idle, needsPermissions = true).action)
        assertEquals(IdleAction.RETRY, IdleConnectionUi.from(state(LinkStage.FAILED, LinkProblem.USB)).action)
        assertNull(IdleConnectionUi.from(state(LinkStage.FAILED, LinkProblem.CARPLAY_AUTH_MISSING)).action)
    }

    @Test fun clockAndRecoveryControlsSurviveWaitingToConnectedToUnplugged() {
        val view = IdleClockView(app)
        val actions = mutableListOf<IdleAction>()
        var settingsOpened = false
        fun bind(link: LinkState) = view.bind(IdleConnectionUi.from(link), { settingsOpened = true }, actions::add)
        bind(state(LinkStage.CONNECTING))
        val texts = descendants(view).filterIsInstance<TextView>()
        assertTrue(texts.any { it.text == app.getString(R.string.carlink_f150_idle_connecting) })
        assertEquals(3, descendants(view).filterIsInstance<TextClock>().size)
        bind(state(LinkStage.CONNECTED))
        descendants(view).filterIsInstance<Button>().single { it.text == app.getString(R.string.carlink_f150_open) }.performClick()
        assertEquals(listOf(IdleAction.OPEN), actions)
        bind(LinkState.Idle)
        assertFalse(descendants(view).filterIsInstance<Button>().any {
            it.visibility == View.VISIBLE && it.text == app.getString(R.string.carlink_f150_open)
        })
        descendants(view).filterIsInstance<Button>().single { it.text == app.getString(R.string.carlink_f150_settings) }.performClick()
        assertTrue(settingsOpened)
    }

    @Test fun largeClockMetricsAreMeasuredBeforeLayoutAtHeadUnitAndPhoneWidths() {
        for ((width, height) in listOf(1024 to 600, 768 to 1024, 400 to 860)) {
            val view = IdleClockView(app)
            view.bind(IdleConnectionUi.from(LinkState.Idle), {}, {})
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val time = descendants(view).filterIsInstance<TextClock>().first()
            assertTrue("clock text is measured at its display size", time.measuredHeight >=
                time.paint.fontMetrics.descent - time.paint.fontMetrics.ascent - 2)
            assertTrue("clock digits fit the measured width", time.measuredWidth >= time.paint.measureText(time.text.toString()) - 2)
            val button = descendants(view).filterIsInstance<Button>().first()
            assertTrue("Settings text fits its button", button.width >=
                button.paint.measureText(button.text.toString()) + button.paddingLeft + button.paddingRight - 2)
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
