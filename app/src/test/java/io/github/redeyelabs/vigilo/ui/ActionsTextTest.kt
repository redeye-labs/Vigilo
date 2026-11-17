package io.github.redeyelabs.vigilo.ui

import io.github.redeyelabs.vigilo.api.Backend
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionsTextTest {
    @Test
    fun subtitle() {
        assertEquals(ACTIONS_SUBTITLE, actionsSubtitle(MainViewModel.ActionsState.Unsupported))
        assertEquals(ACTIONS_SUBTITLE, actionsSubtitle(MainViewModel.ActionsState.Ready(Backend.Actions(enabled = false))))
        assertEquals(
            "Attive: fari e clacson su allerta e allarme",
            actionsSubtitle(MainViewModel.ActionsState.Ready(Backend.Actions(enabled = true, onPanic = true, honk = true))),
        )
        assertEquals("Attive: fari su allerta", actionsSubtitle(MainViewModel.ActionsState.Ready(Backend.Actions(enabled = true))))
    }

    @Test
    fun days() {
        assertEquals("Feriali", scheduleDaysText(0b0011111))
        assertEquals("Weekend", scheduleDaysText(0b1100000))
        assertEquals("Tutti i giorni", scheduleDaysText(0b1111111))
        assertEquals("Lun Mer", scheduleDaysText(0b0000101))
    }
}
