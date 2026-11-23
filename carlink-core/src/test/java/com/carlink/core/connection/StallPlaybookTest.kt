package com.carlink.core.connection
import org.junit.Assert.*
import org.junit.Test
class StallPlaybookTest {
    @Test fun originalWiredDeadlineDefaultsAndFloorsRemain() {
        assertEquals(15_000L, AdaptiveDeadlines.bounds(ProgressStage.WAITING_FOR_PHONE).default)
        assertEquals(12_000L, AdaptiveDeadlines.bounds(ProgressStage.HANDSHAKE).default)
        assertEquals(20_000L, AdaptiveDeadlines.bounds(ProgressStage.STARTING_PROJECTION).default)
        assertEquals(75_000L, AdaptiveDeadlines.bounds(null).default)
        assertEquals(8000L, AdaptiveDeadlines.deadline(listOf(100, 200, 300), AdaptiveDeadlines.bounds(ProgressStage.WAITING_FOR_PHONE)))
    }
    @Test fun learnedDeadlineNeedsThreeSamplesAndUsesBoundedMedian() {
        val bounds = AdaptiveDeadlines.Bounds(12_000, 6000, 25_000)
        assertEquals(12_000L, AdaptiveDeadlines.deadline(listOf(10, 20), bounds))
        assertEquals(10_000L, AdaptiveDeadlines.deadline(listOf(1000, 4000, 100_000), bounds))
        assertEquals(25_000L, AdaptiveDeadlines.deadline(listOf(100_000, 200_000, 300_000), bounds))
    }
    @Test fun everyPlaybookTerminatesWithAReplugAndHasNoNetworkFallback() {
        for (stage in ProgressStage.entries + listOf<ProgressStage?>(null)) {
            val actions = StallPlaybook.actions(stage)
            assertEquals(RecoveryAction.ASK_REPLUG, actions.last())
            assertEquals(RecoveryAction.GIVE_UP, StallPlaybook.next(actions, actions.size).first)
        }
    }
}
