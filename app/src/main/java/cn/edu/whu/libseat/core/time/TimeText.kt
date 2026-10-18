package cn.edu.whu.libseat.core.time

import cn.edu.whu.libseat.domain.model.TimeSlot
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 座位系统的“时间”是一条以分钟为单位的整数（如 600 => 10:00）。
 * 这里统一做转换，避免接口层与 UI 层各写一套。
 */
object TimeText {
    private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun minutesToHhmm(minutes: Int): String {
        val m = minutes.coerceIn(0, 24 * 60)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    fun hhmmToMinutes(text: String): Int? {
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    fun today(): LocalDate = LocalDate.now()

    fun tomorrow(): LocalDate = LocalDate.now().plusDays(1)

    fun iso(date: LocalDate): String = date.format(DateTimeFormatter.ISO_LOCAL_DATE)

    fun parseIso(text: String?): LocalDate? = runCatching {
        LocalDate.parse(text ?: return null)
    }.getOrNull()

    /** “10月18日 周六” */
    fun pretty(date: LocalDate): String {
        val weekday = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)
        return "${date.monthValue}月${date.dayOfMonth}日 $weekday"
    }

    /** “今天” / “明天” / 日期 */
    fun relative(date: LocalDate): String = when (date) {
        today() -> "今天"
        tomorrow() -> "明天"
        else -> pretty(date)
    }

    /** 当前时间向上取整到半小时，作为今天的可选开始时间。 */
    fun nowRoundedToHalfHour(): Int {
        val now = LocalDateTime.now()
        val minutes = now.hour * 60 + now.minute
        val rounded = ((minutes + 29) / 30) * 30
        return rounded.coerceAtMost(23 * 60)
    }

    fun buildDaySlots(open: Int = 7 * 60, close: Int = 22 * 60): List<TimeSlot> {
        val slots = mutableListOf<TimeSlot>()
        var m = open
        while (m <= close) {
            slots += TimeSlot(m, minutesToHhmm(m))
            m += 30
        }
        return slots
    }
}
