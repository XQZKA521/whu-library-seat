package cn.edu.whu.libseat.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import cn.edu.whu.libseat.MainActivity
import cn.edu.whu.libseat.R
import cn.edu.whu.libseat.core.log.AppLog
import cn.edu.whu.libseat.core.time.TimeText
import cn.edu.whu.libseat.domain.model.FavoriteSeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * 快速预约桌面小部件。
 *
 * 三套排版按宿主给的尺寸自动切换：
 * - 2x2（[WidgetSize.SMALL]）：全馆余量 + 当前座位/时段 + 一键预约
 * - 3x3（[WidgetSize.MEDIUM]）：完整选座、调时间、预约
 * - 4x3 及以上（[WidgetSize.LARGE]）：再加标题旁的状态胶囊
 *
 * 卡片外层是透明 FrameLayout、内容卡片按高度居中，宿主把格子拉高时不会出现大片白边。
 */
class QuickBookWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()
        scope.launch {
            try {
                WidgetSync.syncFromStore(context)
                // 首次添加时顺便拉一次全馆余量（有 10 分钟缓存，不会每次都请求）
                WidgetSync.refreshBuildingStats(context, force = false, timeoutMs = 6_000)
                appWidgetIds.forEach { WidgetStore(context).ensureSeeded(it) }
                updateAll(context)
            } catch (t: Throwable) {
                AppLog.w(TAG, "刷新失败：${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        // 拉伸 / 缩小后换用对应尺寸的排版
        updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> {
                val pending = goAsync()
                scope.launch {
                    try {
                        WidgetSync.syncFromStore(context)
                        // 用户主动刷新：强制重取全馆余量
                        WidgetSync.refreshBuildingStats(context, force = true, timeoutMs = 7_000)
                        updateAll(context)
                    } catch (t: Throwable) {
                        AppLog.w(TAG, "手动刷新失败：${t.message}")
                    } finally {
                        pending.finish()
                    }
                }
                return
            }

            ACTION_TOGGLE_DATE, ACTION_SELECT_SEAT, ACTION_CYCLE_SEAT,
            ACTION_START_MINUS, ACTION_START_PLUS,
            ACTION_END_MINUS, ACTION_END_PLUS,
            -> {
                runCatching { handleAction(context, intent) }
                    .onFailure { AppLog.w(TAG, "操作失败：${it.message}") }
                updateAll(context)
                return
            }
        }
        super.onReceive(context, intent)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val store = WidgetStore(context)
        appWidgetIds.forEach { store.clear(it) }
    }

    // ---------------- 交互 ----------------

    private fun handleAction(context: Context, intent: Intent) {
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

        val store = WidgetStore(context)
        store.ensureSeeded(widgetId)
        val state = normalized(store, widgetId)

        when (intent.action) {
            ACTION_TOGGLE_DATE -> toggleDate(store, widgetId, state)

            ACTION_SELECT_SEAT -> {
                val index = intent.getIntExtra(EXTRA_SEAT_INDEX, -1)
                store.favorites().getOrNull(index)?.let { store.saveSelection(widgetId, it.seatId) }
            }

            ACTION_CYCLE_SEAT -> cycleSeat(store, widgetId, state)

            ACTION_START_MINUS -> stepStart(store, widgetId, state, -STEP)
            ACTION_START_PLUS -> stepStart(store, widgetId, state, STEP)
            ACTION_END_MINUS -> stepEnd(store, widgetId, state, -STEP)
            ACTION_END_PLUS -> stepEnd(store, widgetId, state, STEP)
        }
    }

    /**
     * 开始时间：默认就是「现在」，第一次按 ± 才落到最近的半小时整数倍，
     * 之后每次按 30 分钟步进。
     */
    private fun stepStart(store: WidgetStore, widgetId: Int, state: WidgetState, delta: Int) {
        val base = when {
            state.start % STEP == 0 -> state.start + delta
            delta > 0 -> WidgetStore.ceilToStep(state.start)
            else -> WidgetStore.floorToStep(state.start)
        }
        val start = base.coerceIn(MIN_START, MAX_START)
        var end = state.end
        if (end < start + STEP) end = (start + STEP).coerceAtMost(MAX_END)
        store.saveWindow(widgetId, start, end)
        store.saveStartNow(widgetId, false)
    }

    private fun stepEnd(store: WidgetStore, widgetId: Int, state: WidgetState, delta: Int) {
        val base = when {
            state.end % STEP == 0 -> state.end + delta
            delta > 0 -> WidgetStore.ceilToStep(state.end)
            else -> WidgetStore.floorToStep(state.end)
        }
        val end = base.coerceIn(state.start + STEP, MAX_END)
        store.saveWindow(widgetId, state.start, end)
    }

    private fun toggleDate(store: WidgetStore, widgetId: Int, state: WidgetState) {
        if (state.dateOffset == 0) {
            // 今天 → 明天：用设置的默认时间段
            store.saveDateOffset(widgetId, 1)
            store.saveStartNow(widgetId, false)
            store.saveWindow(widgetId, state.defaultStart, state.defaultEnd)
        } else {
            // 明天 → 今天：从「现在」开始，保留原来的时长
            store.saveDateOffset(widgetId, 0)
            store.saveStartNow(widgetId, true)
            val now = TimeText.nowMinutes().coerceIn(MIN_START, MAX_START)
            val span = (state.end - state.start).coerceIn(STEP, MAX_SPAN)
            val end = WidgetStore.ceilToStep(now + span).coerceIn(now + STEP, MAX_END)
            store.saveWindow(widgetId, now, end)
        }
    }

    private fun cycleSeat(store: WidgetStore, widgetId: Int, state: WidgetState) {
        val favorites = store.favorites()
        if (favorites.isEmpty()) return
        val current = favorites.indexOfFirst { it.seatId == state.selectedFavorite?.seatId }
        val next = favorites[(current + 1).mod(favorites.size)]
        store.saveSelection(widgetId, next.seatId)
    }

    /** 「现在」模式每次渲染都跟着当前时间走；其余情况只做边界修正。 */
    private fun normalized(store: WidgetStore, widgetId: Int): WidgetState {
        val state = store.snapshot(widgetId)
        var start = state.start
        var end = state.end

        if (state.dateOffset == 0 && state.startIsNow) {
            start = TimeText.nowMinutes()
        }
        start = start.coerceIn(MIN_START, MAX_START)
        if (end <= start) end = WidgetStore.ceilToStep(start + STEP * 4)
        end = end.coerceIn(start + STEP, MAX_END)

        if (start != state.start || end != state.end) store.saveWindow(widgetId, start, end)
        return state.copy(start = start, end = end)
    }

    // ---------------- 渲染 ----------------

    private fun buildViews(context: Context, widgetId: Int): RemoteViews {
        val store = WidgetStore(context)
        store.ensureSeeded(widgetId)
        val state = normalized(store, widgetId)
        val size = sizeOf(context, widgetId)

        return when (size) {
            WidgetSize.SMALL -> {
                val views = RemoteViews(context.packageName, R.layout.widget_quick_book_small)
                bindSmall(context, widgetId, views, state)
                views
            }

            else -> {
                val layout = when (size) {
                    WidgetSize.MEDIUM -> R.layout.widget_quick_book_medium
                    WidgetSize.TALL -> R.layout.widget_quick_book_tall
                    else -> R.layout.widget_quick_book
                }
                val views = RemoteViews(context.packageName, layout)
                bindFull(
                    context = context,
                    widgetId = widgetId,
                    views = views,
                    state = state,
                    showPill = size == WidgetSize.LARGE || size == WidgetSize.TALL,
                    compactSeat = size == WidgetSize.MEDIUM,
                )
                views
            }
        }
    }

    /** 3x3 / 4x3 共用：选座 + 调时间 + 预约。 */
    private fun bindFull(
        context: Context,
        widgetId: Int,
        views: RemoteViews,
        state: WidgetState,
        showPill: Boolean,
        compactSeat: Boolean,
    ) {
        val today = state.dateOffset == 0
        val favorite = state.selectedFavorite

        views.setOnClickPendingIntent(R.id.widget_title, openApp(context, widgetId))
        views.setTextViewText(R.id.widget_date, if (today) "今天" else "明天")
        views.setOnClickPendingIntent(
            R.id.widget_date,
            broadcast(context, widgetId, ACTION_TOGGLE_DATE, CODE_DATE),
        )
        views.setOnClickPendingIntent(
            R.id.widget_refresh,
            broadcast(context, widgetId, ACTION_REFRESH, CODE_REFRESH),
        )

        // 状态胶囊只在标准尺寸显示，紧凑版宽度不够
        val pill = statusText(state, today)
        if (showPill && pill.isNotBlank()) {
            views.setViewVisibility(R.id.widget_status_pill, View.VISIBLE)
            views.setTextViewText(R.id.widget_status_pill, pill)
            views.setTextColor(
                R.id.widget_status_pill,
                ContextCompat.getColor(context, statusColorRes(state.statusKind)),
            )
        } else {
            views.setViewVisibility(R.id.widget_status_pill, View.GONE)
        }

        bindStats(context, widgetId, views, state)

        // 常用座位（最多 3 个，不足的用等宽占位补齐）
        val chips = intArrayOf(R.id.widget_seat_0, R.id.widget_seat_1, R.id.widget_seat_2)
        val names = intArrayOf(
            R.id.widget_seat_name_0,
            R.id.widget_seat_name_1,
            R.id.widget_seat_name_2,
        )
        val labels = intArrayOf(
            R.id.widget_seat_label_0,
            R.id.widget_seat_label_1,
            R.id.widget_seat_label_2,
        )
        val spacers = intArrayOf(
            R.id.widget_seat_spacer_0,
            R.id.widget_seat_spacer_1,
            R.id.widget_seat_spacer_2,
        )
        val empty = state.favorites.isEmpty()
        // 只有 1 个常用座位时用占位保持 1/3 宽度；2 个则平分整行，避免右侧空一大块
        val keepSlotWidth = state.favorites.size == 1
        views.setViewVisibility(R.id.widget_seat_empty, if (empty) View.VISIBLE else View.GONE)
        views.setViewVisibility(
            R.id.widget_seat_more,
            if (!empty && state.favorites.size > chips.size) View.VISIBLE else View.GONE,
        )
        chips.forEachIndexed { index, chipId ->
            val fav = state.favorites.getOrNull(index)
            if (fav == null) {
                views.setViewVisibility(chipId, View.GONE)
                views.setViewVisibility(
                    spacers[index],
                    if (keepSlotWidth) View.VISIBLE else View.GONE,
                )
                return@forEachIndexed
            }
            val selected = fav.seatId == favorite?.seatId
            views.setViewVisibility(chipId, View.VISIBLE)
            views.setViewVisibility(spacers[index], View.GONE)
            views.setInt(
                chipId,
                "setBackgroundResource",
                if (selected) R.drawable.widget_seat_chip_selected else R.drawable.widget_seat_chip,
            )
            views.setTextViewText(
                names[index],
                if (compactSeat) fav.seatName else "${fav.seatName} 号",
            )
            views.setTextColor(
                names[index],
                ContextCompat.getColor(
                    context,
                    if (selected) R.color.widget_seat_text_selected else R.color.widget_seat_text,
                ),
            )
            views.setTextViewText(labels[index], seatLabel(fav))
            views.setOnClickPendingIntent(
                chipId,
                broadcast(context, widgetId, ACTION_SELECT_SEAT, CODE_SEAT + index, index),
            )
        }
        if (empty) {
            views.setOnClickPendingIntent(R.id.widget_seat_empty, openApp(context, widgetId))
        } else {
            views.setOnClickPendingIntent(R.id.widget_seat_more, openApp(context, widgetId))
        }

        // 时间段
        views.setTextViewText(R.id.widget_start_time, TimeText.minutesToHhmm(state.start))
        views.setTextViewText(R.id.widget_end_time, TimeText.minutesToHhmm(state.end))
        views.setOnClickPendingIntent(
            R.id.widget_start_minus,
            broadcast(context, widgetId, ACTION_START_MINUS, CODE_START_MINUS),
        )
        views.setOnClickPendingIntent(
            R.id.widget_start_plus,
            broadcast(context, widgetId, ACTION_START_PLUS, CODE_START_PLUS),
        )
        views.setOnClickPendingIntent(
            R.id.widget_end_minus,
            broadcast(context, widgetId, ACTION_END_MINUS, CODE_END_MINUS),
        )
        views.setOnClickPendingIntent(
            R.id.widget_end_plus,
            broadcast(context, widgetId, ACTION_END_PLUS, CODE_END_PLUS),
        )

        bindBookButton(context, widgetId, views, state)
    }

    /** 2x2：只保留余量、当前选择与一键预约。 */
    private fun bindSmall(
        context: Context,
        widgetId: Int,
        views: RemoteViews,
        state: WidgetState,
    ) {
        val today = state.dateOffset == 0
        val favorite = state.selectedFavorite

        views.setOnClickPendingIntent(R.id.widget_title, openApp(context, widgetId))
        views.setTextViewText(R.id.widget_date, if (today) "今天" else "明天")
        views.setOnClickPendingIntent(
            R.id.widget_date,
            broadcast(context, widgetId, ACTION_TOGGLE_DATE, CODE_DATE),
        )

        val stats = state.buildingStats
        if (stats != null) {
            // 2x2 宽度只有 ~78dp，数字不带千分位以留出余量文字的位置
            views.setTextViewText(
                R.id.widget_stats_text,
                "已约${stats.occupied}/${stats.total}",
            )
            views.setTextViewText(R.id.widget_stats_free, "余${stats.free}")
            views.setTextColor(
                R.id.widget_stats_free,
                ContextCompat.getColor(context, freeColorRes(stats.freeRatio)),
            )
            views.setProgressBar(R.id.widget_stats_bar, stats.total, stats.occupied, false)
        } else {
            views.setTextViewText(R.id.widget_stats_text, "全馆余量待更新")
            views.setTextViewText(R.id.widget_stats_free, "")
            views.setProgressBar(R.id.widget_stats_bar, 100, 0, false)
        }

        if (favorite == null) {
            views.setTextViewText(R.id.widget_seat_name_small, "去收藏")
            views.setTextViewText(R.id.widget_window, "还没有常用座位")
            views.setOnClickPendingIntent(R.id.widget_seat_small, openApp(context, widgetId))
        } else {
            views.setTextViewText(R.id.widget_seat_name_small, favorite.seatName)
            views.setTextViewText(
                R.id.widget_window,
                "${TimeText.minutesToHhmm(state.start)}-${TimeText.minutesToHhmm(state.end)}",
            )
            // 点座位循环切换常用座位，点时间进 App 精调
            views.setOnClickPendingIntent(
                R.id.widget_seat_small,
                broadcast(context, widgetId, ACTION_CYCLE_SEAT, CODE_CYCLE_SEAT),
            )
        }
        views.setOnClickPendingIntent(R.id.widget_window, openApp(context, widgetId))

        bindBookButton(context, widgetId, views, state, compact = true)
    }

    private fun bindStats(
        context: Context,
        widgetId: Int,
        views: RemoteViews,
        state: WidgetState,
    ) {
        val stats = state.buildingStats
        val building = stats?.name?.takeIf { it.isNotBlank() }
            ?: state.buildingName.ifBlank { "图书馆" }
        views.setTextViewText(R.id.widget_building, building)
        views.setOnClickPendingIntent(R.id.widget_building, openApp(context, widgetId))
        views.setOnClickPendingIntent(R.id.widget_stats_text, openApp(context, widgetId))

        if (stats != null) {
            views.setTextViewText(
                R.id.widget_stats_text,
                "已约 ${formatCount(stats.occupied)}/${formatCount(stats.total)}",
            )
            views.setTextViewText(R.id.widget_stats_free, "余 ${formatCount(stats.free)}")
            views.setTextColor(
                R.id.widget_stats_free,
                ContextCompat.getColor(context, freeColorRes(stats.freeRatio)),
            )
            views.setProgressBar(R.id.widget_stats_bar, stats.total, stats.occupied, false)
        } else {
            views.setTextViewText(R.id.widget_stats_text, "全馆余量待更新")
            views.setTextViewText(R.id.widget_stats_free, "")
            views.setProgressBar(R.id.widget_stats_bar, 100, 0, false)
        }
    }

    private fun bindBookButton(
        context: Context,
        widgetId: Int,
        views: RemoteViews,
        state: WidgetState,
        compact: Boolean = false,
    ) {
        val favorite = state.selectedFavorite
        val today = state.dateOffset == 0
        if (favorite == null) {
            views.setTextViewText(R.id.widget_book, "去收藏常用座位")
            views.setOnClickPendingIntent(R.id.widget_book, openApp(context, widgetId))
            return
        }
        val window = "${TimeText.minutesToHhmm(state.start)}-${TimeText.minutesToHhmm(state.end)}"
        val label = when {
            compact -> "立即预约"
            state.startIsNow && today -> "现在预约 ${favorite.seatName} 号"
            else -> "预约 ${favorite.seatName} 号 · $window"
        }
        views.setTextViewText(R.id.widget_book, label)
        views.setOnClickPendingIntent(
            R.id.widget_book,
            bookIntent(
                context = context,
                widgetId = widgetId,
                seatId = favorite.seatId,
                date = TimeText.iso(if (today) TimeText.today() else TimeText.tomorrow()),
                start = state.start,
                end = state.end,
            ),
        )
    }

    /** 标题旁的短状态：有预约结果优先显示结果，否则只在需要提醒时显示。 */
    private fun statusText(state: WidgetState, today: Boolean): String {
        val raw = state.statusText.trim()
        if (raw.isNotBlank()) return if (raw.length > 10) raw.take(9) + "…" else raw
        if (!today && !state.demoMode && LocalTime.now().isBefore(LocalTime.of(22, 45))) {
            return "22:45 开放"
        }
        return when {
            state.demoMode -> "演示"
            !state.loggedIn -> "未登录"
            else -> ""
        }
    }

    private fun statusColorRes(kind: Int): Int = when (kind) {
        WidgetStore.KIND_SUCCESS -> R.color.widget_success
        WidgetStore.KIND_ERROR -> R.color.widget_error
        else -> R.color.widget_accent
    }

    private fun formatCount(value: Int): String =
        if (value < 1000) value.toString() else "%,d".format(value)

    /** 余量配色沿用首页容量条的语义：充足=绿，偏紧=琥珀，紧张=红。 */
    private fun freeColorRes(ratio: Float): Int = when {
        ratio >= 0.4f -> R.color.widget_success
        ratio >= 0.15f -> R.color.widget_warn
        else -> R.color.widget_error
    }

    private fun seatLabel(fav: FavoriteSeat): String {
        val room = fav.roomName.removePrefix("${fav.floor}F").trim()
        return if (room.isBlank()) "${fav.floor}F" else "${fav.floor}F $room"
    }

    // ---------------- 尺寸 ----------------

    private fun sizeOf(context: Context, widgetId: Int): WidgetSize {
        val manager = AppWidgetManager.getInstance(context)
        val options = manager.getAppWidgetOptions(widgetId)
        val width = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) ?: 250
        val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180) ?: 180
        return when {
            width < 160 || height < 150 -> WidgetSize.SMALL
            width < 235 -> WidgetSize.MEDIUM
            height >= 230 -> WidgetSize.TALL
            else -> WidgetSize.LARGE
        }
    }

    // ---------------- PendingIntent ----------------

    private fun broadcast(
        context: Context,
        widgetId: Int,
        action: String,
        code: Int,
        seatIndex: Int = -1,
    ): PendingIntent {
        val intent = Intent(context, QuickBookWidgetProvider::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            if (seatIndex >= 0) putExtra(EXTRA_SEAT_INDEX, seatIndex)
        }
        return PendingIntent.getBroadcast(
            context,
            widgetId * 32 + code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openApp(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            widgetId * 32 + CODE_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun bookIntent(
        context: Context,
        widgetId: Int,
        seatId: Long,
        date: String,
        start: Int,
        end: Int,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_QUICK_BOOK
            putExtra(MainActivity.EXTRA_SEAT_ID, seatId)
            putExtra(MainActivity.EXTRA_DATE, date)
            putExtra(MainActivity.EXTRA_START, start)
            putExtra(MainActivity.EXTRA_END, end)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        return PendingIntent.getActivity(
            context,
            widgetId * 32 + CODE_BOOK,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private enum class WidgetSize { SMALL, MEDIUM, LARGE, TALL }

    companion object {
        private const val TAG = "Widget"

        const val ACTION_REFRESH = "cn.edu.whu.libseat.widget.REFRESH"
        const val ACTION_TOGGLE_DATE = "cn.edu.whu.libseat.widget.TOGGLE_DATE"
        const val ACTION_SELECT_SEAT = "cn.edu.whu.libseat.widget.SELECT_SEAT"
        const val ACTION_CYCLE_SEAT = "cn.edu.whu.libseat.widget.CYCLE_SEAT"
        const val ACTION_START_MINUS = "cn.edu.whu.libseat.widget.START_MINUS"
        const val ACTION_START_PLUS = "cn.edu.whu.libseat.widget.START_PLUS"
        const val ACTION_END_MINUS = "cn.edu.whu.libseat.widget.END_MINUS"
        const val ACTION_END_PLUS = "cn.edu.whu.libseat.widget.END_PLUS"

        const val EXTRA_SEAT_INDEX = "seat_index"

        private const val STEP = 30
        private const val MIN_START = 7 * 60
        private const val MAX_START = 21 * 60
        private const val MAX_END = 22 * 60
        private const val MAX_SPAN = 8 * 60

        private const val CODE_DATE = 1
        private const val CODE_REFRESH = 2
        private const val CODE_SEAT = 4
        private const val CODE_START_MINUS = 8
        private const val CODE_START_PLUS = 9
        private const val CODE_END_MINUS = 10
        private const val CODE_END_PLUS = 11
        private const val CODE_OPEN_APP = 12
        private const val CODE_BOOK = 13
        private const val CODE_CYCLE_SEAT = 14

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 刷新所有小部件实例。 */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, QuickBookWidgetProvider::class.java),
            )
            ids.forEach { id ->
                runCatching { manager.updateAppWidget(id, buildViewsFor(context, id)) }
                    .onFailure { AppLog.w(TAG, "渲染失败：${it.message}") }
            }
        }

        private fun buildViewsFor(context: Context, widgetId: Int): RemoteViews =
            QuickBookWidgetProvider().buildViews(context, widgetId)
    }
}
