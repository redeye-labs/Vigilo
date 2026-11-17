package io.github.redeyelabs.vigilo.work

import io.github.redeyelabs.vigilo.api.Backend
import io.github.redeyelabs.vigilo.data.ScheduleEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleSyncTest {
    @Test
    fun mergeAddsOnlyNewLocalRules() {
        val server = listOf(Backend.Rule(id = 4, hour = 22, minute = 0, daysMask = 31, enable = true))
        val local = listOf(
            ScheduleEntity(id = 1, daysMask = 31, hour = 22, minute = 0, enableSentry = true),
            ScheduleEntity(id = 2, daysMask = 31, hour = 7, minute = 0, enableSentry = false, active = false),
        )
        val out = ScheduleSync.merge(server, local)
        assertEquals(2, out.size)
        assertEquals(Backend.Rule(id = 0, hour = 7, minute = 0, daysMask = 31, enable = false, active = false), out[1])
    }
}
