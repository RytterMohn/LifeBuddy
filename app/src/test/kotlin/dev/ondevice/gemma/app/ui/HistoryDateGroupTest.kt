package dev.ondevice.gemma.app.ui

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryDateGroupTest {
    @Test fun `groups by local calendar days instead of elapsed hours`() {
        val zone = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 10, 2)
        fun group(day: LocalDate) = historyDateGroup(day.atTime(23, 59).atZone(zone).toInstant().toEpochMilli(), today, zone)
        assertEquals("今天", group(today))
        assertEquals("昨天", group(today.minusDays(1)))
        assertEquals("过去 7 天", group(today.minusDays(7)))
        assertEquals("过去 30 天", group(today.minusDays(8)))
        assertEquals("过去 30 天", group(today.minusDays(30)))
        assertEquals("2026 年 8 月", group(today.minusDays(40)))
    }
}
