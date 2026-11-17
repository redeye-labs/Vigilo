package io.github.redeyelabs.vigilo.work

import io.github.redeyelabs.vigilo.api.Backend
import io.github.redeyelabs.vigilo.data.ScheduleEntity

/** Passaggio delle regole salvate sul telefono (app fino a 1.3.5) al server Vigilo. */
object ScheduleSync {
    fun toRule(e: ScheduleEntity) = Backend.Rule(
        id = 0, hour = e.hour, minute = e.minute, daysMask = e.daysMask and 0x7f, enable = e.enableSentry, active = e.active,
    )

    /** Regole del server più quelle locali non già presenti (stesso orario, giorni e azione). */
    fun merge(server: List<Backend.Rule>, local: List<ScheduleEntity>): List<Backend.Rule> {
        val out = server.toMutableList()
        for (e in local) {
            val r = toRule(e)
            if (out.none { it.hour == r.hour && it.minute == r.minute && it.daysMask == r.daysMask && it.enable == r.enable }) out += r
        }
        return out
    }
}
