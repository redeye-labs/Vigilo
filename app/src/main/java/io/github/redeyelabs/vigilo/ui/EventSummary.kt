package io.github.redeyelabs.vigilo.ui

import io.github.redeyelabs.vigilo.data.EventEntity
import io.github.redeyelabs.vigilo.data.EventType

const val DAY_MS = 24L * 60 * 60 * 1000

fun eventsInLast24h(events: List<EventEntity>, nowMs: Long = System.currentTimeMillis()): List<EventEntity> {
    val from = nowMs - DAY_MS
    return events.filter { it.timestamp >= from }.sortedByDescending { it.timestamp }
}

/** 24 buckets, index 0 = 23 hours ago, index 23 = the current hour. */
fun hourlyEventBuckets(events: List<EventEntity>, nowMs: Long = System.currentTimeMillis()): IntArray {
    val buckets = IntArray(24)
    val start = nowMs - DAY_MS
    for (e in events) {
        if (e.timestamp < start) continue
        val i = ((e.timestamp - start) / (60 * 60 * 1000L)).toInt().coerceIn(0, 23)
        buckets[i]++
    }
    return buckets
}

fun sentryAlertCount(events: List<EventEntity>): Int =
    events.count { EventType.isAlert(it.type) }
