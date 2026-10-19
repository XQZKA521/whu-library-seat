package cn.edu.whu.libseat.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import cn.edu.whu.libseat.LibrarySeatApp
import cn.edu.whu.libseat.core.log.AppLog
import cn.edu.whu.libseat.core.time.TimeText
import cn.edu.whu.libseat.domain.model.Reservation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App 与小部件之间的同步通道。
 *
 * - [syncFromStore]：小部件刷新时从 DataStore 读常用座位 / 默认时段 / 默认馆
 * - [publishStatus] / [publishReservation]：App 内预约结束后把结果回写到小部件
 * - [refreshBuildingStats]：拉默认馆“全馆已约 / 总量”，缓存 10 分钟，避免高频请求
 *
 * 小部件本身只在用户点刷新时联网，其余时候都读这里的本地快照。
 */
object WidgetSync {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /** 从 DataStore 同步常用座位、默认时段与默认图书馆。 */
    suspend fun syncFromStore(context: Context) {
        val app = context.applicationContext as? LibrarySeatApp ?: return
        val repo = app.container.repository
        val settings = runCatching { repo.currentSettings() }.getOrNull() ?: return
        val favorites = runCatching { repo.favorites.first() }.getOrDefault(emptyList())
        val catalog = runCatching { repo.cachedCatalog.first() }.getOrNull()
        val buildingName = catalog?.buildings
            ?.firstOrNull { it.id == settings.defaultBuildingId }?.name
            .orEmpty()
        WidgetStore(context).apply {
            saveFavorites(favorites)
            saveDefaultWindow(settings.defaultStart, settings.defaultEnd)
            saveFlags(settings.demoMode, settings.isLoggedIn)
            saveBuilding(settings.defaultBuildingId, buildingName)
        }
    }

    /** 首页切换图书馆时调用，让小部件的“全馆余量”跟着默认馆走。 */
    fun publishBuilding(buildingId: Long?, name: String?) {
        val ctx = appContext ?: return
        WidgetStore(ctx).saveBuilding(buildingId, name.orEmpty())
        refresh(ctx)
    }

    fun publishStatus(text: String, kind: Int) {
        val ctx = appContext ?: return
        WidgetStore(ctx).saveStatus(text, kind)
        refresh(ctx)
    }

    /** 把当前预约（或“无预约”）写到小部件状态行。 */
    fun publishReservation(reservation: Reservation?) {
        if (reservation == null) {
            publishStatus("", WidgetStore.KIND_NEUTRAL)
            return
        }
        val label = when (reservation.status.uppercase()) {
            "IN_USE", "USING", "CHECKED_IN", "ACTIVE" -> "使用中"
            "AWAY", "LEAVE" -> "暂离中"
            "COMPLETE" -> "已完成"
            else -> "已预约"
        }
        val place = reservation.location
            .replace("|", " ")
            .replace(",", " ")
            .replace("座位号", "")
            .trim()
            .take(16)
        publishStatus(
            "$label ${reservation.begin}-${reservation.end}" + if (place.isNotBlank()) " · $place" else "",
            WidgetStore.KIND_SUCCESS,
        )
    }

    /**
     * 拉取默认馆的全馆座位占用。
     *
     * 座位系统没有“整馆一次返回”的接口，这里按楼层聚合（默认馆通常 5–7 层），
     * 因此加 10 分钟缓存 + 超时保护，只有用户点刷新时才强制重取。
     */
    suspend fun refreshBuildingStats(
        context: Context,
        force: Boolean = false,
        timeoutMs: Long = 7_000,
    ): BuildingStats? {
        val app = context.applicationContext as? LibrarySeatApp ?: return null
        val repo = app.container.repository
        val settings = runCatching { repo.currentSettings() }.getOrNull() ?: return null
        val buildingId = settings.defaultBuildingId ?: return null
        val store = WidgetStore(context)
        val cached = store.stats()

        if (!force && cached != null && cached.buildingId == buildingId &&
            System.currentTimeMillis() - cached.at < STATS_TTL_MS
        ) {
            return cached
        }

        return try {
            val date = TimeText.iso(TimeText.today())
            val rooms = withTimeoutOrNull(timeoutMs) {
                repo.roomStats(buildingId, null, date)
            } ?: return cached

            val total = rooms.sumOf { it.total }
            val free = rooms.sumOf { it.free }
            if (total <= 0) return cached

            val catalog = runCatching { repo.cachedCatalog.first() }.getOrNull()
            val name = catalog?.buildings?.firstOrNull { it.id == buildingId }?.name
                ?: cached?.name?.takeIf { it.isNotBlank() }
                ?: store.buildingName().ifBlank { "图书馆" }
            val stats = BuildingStats(
                buildingId = buildingId,
                name = name,
                total = total,
                free = free,
                at = System.currentTimeMillis(),
            )
            store.saveStats(stats)
            stats
        } catch (e: Exception) {
            AppLog.w("Widget", "全馆余量刷新失败：${e.message}")
            cached
        }
    }

    /** App 侧后台刷新（只在桌面上有小部件时才发请求）。 */
    fun refreshStatsInBackground(force: Boolean = false) {
        val ctx = appContext ?: return
        if (!hasWidgets(ctx)) return
        scope.launch {
            runCatching {
                refreshBuildingStats(ctx, force)
                refresh(ctx)
            }
        }
    }

    fun refresh(context: Context) {
        scope.launch { runCatching { QuickBookWidgetProvider.updateAll(context) } }
    }

    private fun hasWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return manager.getAppWidgetIds(
            ComponentName(context, QuickBookWidgetProvider::class.java),
        ).isNotEmpty()
    }

    /** 全馆余量缓存有效期。 */
    private const val STATS_TTL_MS = 10 * 60 * 1000L
}
