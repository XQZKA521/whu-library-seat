package cn.edu.whu.libseat.widget

import android.content.Context
import cn.edu.whu.libseat.core.time.TimeText
import cn.edu.whu.libseat.domain.model.FavoriteSeat
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 小部件本地状态。
 *
 * 小部件运行在广播里，不能像界面那样挂 ViewModel，所以这里用 SharedPreferences
 * 存一份“渲染快照”：
 * - 全局：常用座位、默认时间段、登录 / 演示模式、最近一次预约结果
 * - 每个小部件实例：选中的座位、日期（今天 / 明天）、起止时间
 */
class WidgetStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---------------- 全局快照 ----------------

    fun favorites(): List<FavoriteSeat> {
        val raw = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<FavoriteSeat>>(raw) }.getOrDefault(emptyList())
    }

    fun saveFavorites(list: List<FavoriteSeat>) {
        prefs.edit().putString(KEY_FAVORITES, json.encodeToString(list)).apply()
    }

    fun defaultWindow(): Pair<Int, Int> =
        prefs.getInt(KEY_DEFAULT_START, DEFAULT_START) to prefs.getInt(KEY_DEFAULT_END, DEFAULT_END)

    fun saveDefaultWindow(start: Int, end: Int) {
        prefs.edit().putInt(KEY_DEFAULT_START, start).putInt(KEY_DEFAULT_END, end).apply()
    }

    fun saveFlags(demoMode: Boolean, loggedIn: Boolean) {
        prefs.edit()
            .putBoolean(KEY_DEMO_MODE, demoMode)
            .putBoolean(KEY_LOGGED_IN, loggedIn)
            .apply()
    }

    fun demoMode(): Boolean = prefs.getBoolean(KEY_DEMO_MODE, true)

    fun loggedIn(): Boolean = prefs.getBoolean(KEY_LOGGED_IN, false)

    fun status(): Pair<String, Int> =
        prefs.getString(KEY_STATUS_TEXT, "").orEmpty() to prefs.getInt(KEY_STATUS_KIND, KIND_NEUTRAL)

    fun saveStatus(text: String, kind: Int) {
        prefs.edit().putString(KEY_STATUS_TEXT, text).putInt(KEY_STATUS_KIND, kind).apply()
    }

    // ---------------- 默认图书馆与全馆余量 ----------------

    fun saveBuilding(id: Long?, name: String) {
        prefs.edit()
            .putLong(KEY_BUILDING_ID, id ?: -1L)
            .putString(KEY_BUILDING_NAME, name)
            .apply()
    }

    fun buildingId(): Long? = prefs.getLong(KEY_BUILDING_ID, -1L).takeIf { it > 0 }

    fun buildingName(): String = prefs.getString(KEY_BUILDING_NAME, "").orEmpty()

    fun saveStats(stats: BuildingStats) {
        prefs.edit()
            .putLong(KEY_STATS_ID, stats.buildingId)
            .putString(KEY_STATS_NAME, stats.name)
            .putInt(KEY_STATS_TOTAL, stats.total)
            .putInt(KEY_STATS_FREE, stats.free)
            .putLong(KEY_STATS_AT, stats.at)
            .apply()
    }

    /** 最近一次拉到的全馆余量；没有数据时返回 null。 */
    fun stats(): BuildingStats? {
        if (!prefs.contains(KEY_STATS_AT)) return null
        val total = prefs.getInt(KEY_STATS_TOTAL, 0)
        if (total <= 0) return null
        return BuildingStats(
            buildingId = prefs.getLong(KEY_STATS_ID, -1L),
            name = prefs.getString(KEY_STATS_NAME, "").orEmpty(),
            total = total,
            free = prefs.getInt(KEY_STATS_FREE, 0),
            at = prefs.getLong(KEY_STATS_AT, 0L),
        )
    }

    // ---------------- 每个小部件实例的选择 ----------------

    /**
     * 首次添加小部件时的初始值：
     * 今天从「现在」开始（不取整到半小时），结束时间按默认时长往后推并向上取整。
     */
    fun ensureSeeded(widgetId: Int) {
        if (prefs.contains(key(widgetId, "start"))) return
        val (defaultStart, defaultEnd) = defaultWindow()
        val now = TimeText.nowMinutes().coerceIn(MIN_START, MAX_START)
        val span = (defaultEnd - defaultStart).coerceIn(STEP, MAX_SPAN)
        val end = ceilToStep(now + span).coerceIn(now + STEP, MAX_END)
        prefs.edit()
            .putInt(key(widgetId, "start"), now)
            .putInt(key(widgetId, "end"), end)
            .putInt(key(widgetId, "date"), 0)
            .putBoolean(key(widgetId, "now"), true)
            .putLong(key(widgetId, "seat"), favorites().firstOrNull()?.seatId ?: NO_SEAT)
            .apply()
    }

    fun snapshot(widgetId: Int): WidgetState {
        val (defaultStart, defaultEnd) = defaultWindow()
        return WidgetState(
            favorites = favorites(),
            selectedSeatId = prefs.getLong(key(widgetId, "seat"), NO_SEAT).takeIf { it > 0 },
            dateOffset = prefs.getInt(key(widgetId, "date"), 0),
            start = prefs.getInt(key(widgetId, "start"), defaultStart),
            end = prefs.getInt(key(widgetId, "end"), defaultEnd),
            startIsNow = prefs.getBoolean(key(widgetId, "now"), true),
            defaultStart = defaultStart,
            defaultEnd = defaultEnd,
            demoMode = demoMode(),
            loggedIn = loggedIn(),
            statusText = status().first,
            statusKind = status().second,
            buildingId = buildingId(),
            buildingName = buildingName(),
            stats = stats(),
        )
    }

    fun saveStartNow(widgetId: Int, isNow: Boolean) {
        prefs.edit().putBoolean(key(widgetId, "now"), isNow).apply()
    }

    fun saveSelection(widgetId: Int, seatId: Long?) {
        prefs.edit().putLong(key(widgetId, "seat"), seatId ?: NO_SEAT).apply()
    }

    fun saveDateOffset(widgetId: Int, offset: Int) {
        prefs.edit().putInt(key(widgetId, "date"), offset).apply()
    }

    fun saveWindow(widgetId: Int, start: Int, end: Int) {
        prefs.edit()
            .putInt(key(widgetId, "start"), start)
            .putInt(key(widgetId, "end"), end)
            .apply()
    }

    fun clear(widgetId: Int) {
        prefs.edit()
            .remove(key(widgetId, "seat"))
            .remove(key(widgetId, "date"))
            .remove(key(widgetId, "start"))
            .remove(key(widgetId, "end"))
            .remove(key(widgetId, "now"))
            .apply()
    }

    private fun key(widgetId: Int, name: String) = "w$widgetId.$name"

    companion object {
        private const val PREF_NAME = "quick_book_widget"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_DEFAULT_START = "default_start"
        private const val KEY_DEFAULT_END = "default_end"
        private const val KEY_DEMO_MODE = "demo_mode"
        private const val KEY_LOGGED_IN = "logged_in"
        private const val KEY_STATUS_TEXT = "status_text"
        private const val KEY_STATUS_KIND = "status_kind"
        private const val KEY_BUILDING_ID = "building_id"
        private const val KEY_BUILDING_NAME = "building_name"
        private const val KEY_STATS_ID = "stats_building_id"
        private const val KEY_STATS_NAME = "stats_building_name"
        private const val KEY_STATS_TOTAL = "stats_total"
        private const val KEY_STATS_FREE = "stats_free"
        private const val KEY_STATS_AT = "stats_at"

        private const val DEFAULT_START = 8 * 60
        private const val DEFAULT_END = 12 * 60
        private const val NO_SEAT = -1L

        private const val STEP = 30
        private const val MIN_START = 7 * 60
        private const val MAX_START = 21 * 60
        private const val MAX_END = 22 * 60
        private const val MAX_SPAN = 8 * 60

        fun ceilToStep(minutes: Int): Int = ((minutes + STEP - 1) / STEP) * STEP

        fun floorToStep(minutes: Int): Int = (minutes / STEP) * STEP

        const val KIND_NEUTRAL = 0
        const val KIND_SUCCESS = 1
        const val KIND_ERROR = 2
    }
}

data class WidgetState(
    val favorites: List<FavoriteSeat>,
    val selectedSeatId: Long?,
    val dateOffset: Int,
    val start: Int,
    val end: Int,
    val startIsNow: Boolean = true,
    val defaultStart: Int,
    val defaultEnd: Int,
    val demoMode: Boolean,
    val loggedIn: Boolean,
    val statusText: String,
    val statusKind: Int,
    val buildingId: Long? = null,
    val buildingName: String = "",
    val stats: BuildingStats? = null,
) {
    val selectedFavorite: FavoriteSeat?
        get() = favorites.firstOrNull { it.seatId == selectedSeatId } ?: favorites.firstOrNull()

    /** 当前默认馆的全馆余量；馆不一致（刚切换图书馆）时视为无数据。 */
    val buildingStats: BuildingStats?
        get() = stats?.takeIf { it.total > 0 && (buildingId == null || it.buildingId == buildingId) }
}

/** 整个图书馆的座位占用快照。 */
data class BuildingStats(
    val buildingId: Long,
    val name: String,
    val total: Int,
    val free: Int,
    val at: Long,
) {
    val occupied: Int get() = (total - free).coerceAtLeast(0)
    val freeRatio: Float get() = if (total <= 0) 0f else free.toFloat() / total
}
