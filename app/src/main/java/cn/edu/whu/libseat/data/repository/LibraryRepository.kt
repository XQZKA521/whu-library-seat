package cn.edu.whu.libseat.data.repository

import cn.edu.whu.libseat.core.log.AppLog
import cn.edu.whu.libseat.core.time.TimeText
import cn.edu.whu.libseat.data.local.CatalogSnapshot
import cn.edu.whu.libseat.data.local.SettingsStore
import cn.edu.whu.libseat.data.local.UserSettings
import cn.edu.whu.libseat.data.remote.CatalogData
import cn.edu.whu.libseat.data.remote.DemoLibraryApi
import cn.edu.whu.libseat.data.remote.LibraryApi
import cn.edu.whu.libseat.data.remote.SessionExpiredException
import cn.edu.whu.libseat.domain.model.FavoriteSeat
import cn.edu.whu.libseat.domain.model.HistoryItem
import cn.edu.whu.libseat.domain.model.Reservation
import cn.edu.whu.libseat.domain.model.RoomStats
import cn.edu.whu.libseat.domain.model.SeatMap
import cn.edu.whu.libseat.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * 图书馆数据仓库。
 *
 * 复用点：会话 + 基础数据缓存 + 实时座位数据 + 预约闭环，
 * 与 Ham 的图书馆模块一一对应；差异在于原生端用 suspend/Flow 重写。
 */
class LibraryRepository(
    private val store: SettingsStore,
    private val realApi: LibraryApi,
    private val jsqApi: LibraryApi,
    private val demoApi: LibraryApi = DemoLibraryApi(),
    private val sessionRefresher: (suspend () -> String?)? = null,
) {

    val userSettings: Flow<UserSettings> = store.settings
    val favorites: Flow<List<FavoriteSeat>> = store.favorites
    val cachedCatalog: Flow<CatalogSnapshot?> = store.catalogCache

    suspend fun currentSettings(): UserSettings = store.current()

    /**
     * 选择实际使用的数据源。
     * 演示模式下不需要 token；真实模式没有 token 时抛出 [SessionExpiredException]。
     */
    private suspend fun session(): Pair<LibraryApi, String> {
        val s = store.current()
        if (s.demoMode) return demoApi to DEMO_TOKEN
        if (s.token.isBlank()) throw SessionExpiredException("尚未登录图书馆账号")
        return (if (useJsq(s)) jsqApi else realApi) to s.token
    }

    /**
     * 会话调用包装：token 过期（20003）时尝试统一认证静默续期，成功后重试一次。
     */
    private suspend fun <T> withSessionRetry(block: suspend (LibraryApi, String) -> T): T {
        val (api, token) = session()
        return try {
            block(api, token)
        } catch (e: SessionExpiredException) {
            val refresher = sessionRefresher ?: throw e
            AppLog.i("Session", "token 过期，尝试统一认证静默续期")
            val newToken = refresher() ?: run {
                AppLog.w("Session", "静默续期失败，需要重新登录")
                throw e
            }
            val s = store.current()
            store.saveWebSession(newToken, s.username, s.displayName)
            AppLog.i("Session", "静默续期成功，重试原请求")
            block(api, newToken)
        }
    }

    /** 武大本部 / 已完成 Web 登录的会话走新版 /jsq 接口。 */
    private fun useJsq(s: UserSettings): Boolean =
        s.webLoggedIn || s.baseUrl.contains("seat.lib.whu.edu.cn", ignoreCase = true)

    private suspend fun sourceName(): String {
        val s = store.current()
        return when {
            s.demoMode -> CatalogSnapshot.SOURCE_DEMO
            useJsq(s) -> CatalogSnapshot.SOURCE_JSQ
            else -> CatalogSnapshot.SOURCE_REAL
        }
    }

    suspend fun login(username: String, password: String) {
        // 登录始终走真实接口；成功后自动退出演示模式
        val token = realApi.login(username, password)
        store.saveSession(username, token)
    }

    suspend fun logout() {
        store.clearSession()
    }

    /** WebView 登录成功后保存会话 token。 */
    suspend fun saveWebSession(token: String, username: String, displayName: String) {
        store.saveWebSession(token, username, displayName)
    }

    suspend fun profile(): UserProfile? = try {
        withSessionRetry { api, token -> api.user(token) }
    } catch (e: Exception) {
        AppLog.w("Session", "profile 获取失败：${e.message}")
        null
    }

    // ---------------- 基础数据 ----------------

    suspend fun catalog(forceRefresh: Boolean = false): CatalogSnapshot {
        val source = sourceName()
        val cached = store.catalogCache.first()
        if (!forceRefresh && cached != null && cached.source == source && cached.isFresh()) {
            return cached
        }
        return try {
            val remote = withSessionRetry { api, token -> api.catalog(token) }
            val snapshot = remote.toSnapshot(source)
            store.saveCatalog(snapshot)
            snapshot
        } catch (e: Exception) {
            // 网络失败时退回旧缓存（即使已过期），保证首页可用
            if (cached != null && cached.source == source) cached else throw e
        }
    }

    private fun CatalogData.toSnapshot(source: String) = CatalogSnapshot(
        source = source,
        buildings = buildings,
        rooms = rooms,
        dates = dates,
        updatedAt = System.currentTimeMillis(),
        floors = floors,
    )

    suspend fun roomStats(
        buildingId: Long,
        floorNumber: Int? = null,
        date: String = TimeText.iso(LocalDate.now()),
    ): List<RoomStats> {
        return withSessionRetry { api, token ->
            if (api is cn.edu.whu.libseat.data.remote.JsqLibraryApi) {
                api.roomStatsForFloor(token, buildingId, floorNumber, date)
            } else {
                api.roomStats(token, buildingId)
            }
        }
    }

    suspend fun seatMap(roomId: Long, date: String): SeatMap =
        withSessionRetry { api, token -> api.seatMap(token, roomId, date) }

    // ---------------- 预约 ----------------

    suspend fun book(
        seatId: Long,
        date: String,
        start: Int,
        end: Int,
        capToken: String? = null,
    ): Reservation = withSessionRetry { api, token ->
        api.book(token, seatId, date, start, end, capToken)
    }

    /** 获取一次滑块验证 capToken（快速预约在候选座位间复用，避免反复弹验证码）。 */
    suspend fun acquireCapToken(hint: String? = null): String? =
        (jsqApi as? cn.edu.whu.libseat.data.remote.JsqLibraryApi)?.acquireCapToken(hint)

    suspend fun reservations(): List<Reservation> {
        val s = store.current()
        if (!s.demoMode && s.token.isBlank()) return emptyList()
        return withSessionRetry { api, token -> api.reservations(token) }
    }

    suspend fun cancel(reservationId: Long) {
        withSessionRetry { api, token -> api.cancel(token, reservationId) }
    }

    /** 提前结束履约（官方 make/stop / rest/v2/stop），释放座位。 */
    suspend fun stopUsage(reservationId: Long) {
        withSessionRetry { api, token -> api.stop(token, reservationId) }
    }

    suspend fun history(): List<HistoryItem> {
        val s = store.current()
        if (!s.demoMode && s.token.isBlank()) return emptyList()
        return withSessionRetry { api, token -> api.history(token) }
    }

    @Volatile
    private var historyAllCache: List<HistoryItem>? = null

    @Volatile
    private var historyAllAt = 0L

    /** 全量历史预约（官方数据），10 分钟内存缓存；用于统计与常用座位使用情况。 */
    suspend fun historyAll(forceRefresh: Boolean = false): List<HistoryItem> {
        val s = store.current()
        if (s.demoMode) return emptyList()
        if (!forceRefresh && historyAllCache != null &&
            System.currentTimeMillis() - historyAllAt < 10 * 60_000L
        ) {
            return historyAllCache!!
        }
        val list = withSessionRetry { api, token -> api.historyAll(token) }
        historyAllCache = list
        historyAllAt = System.currentTimeMillis()
        return list
    }

    suspend fun startTimes(seatId: Long, date: String) =
        withSessionRetry { api, token -> api.startTimes(token, seatId, date) }

    suspend fun endTimes(seatId: Long, date: String, start: Int) =
        withSessionRetry { api, token -> api.endTimes(token, seatId, date, start) }

    // ---------------- 常用座位 ----------------

    suspend fun addFavorite(seat: FavoriteSeat) = store.addFavorite(seat)

    suspend fun removeFavorite(seatId: Long, buildingId: Long) = store.removeFavorite(seatId, buildingId)

    suspend fun updateDefaultTarget(buildingId: Long, floor: Int) = store.setDefaultTarget(buildingId, floor)

    suspend fun updateDefaultWindow(start: Int, end: Int) = store.setDefaultWindow(start, end)

    suspend fun setFallback(enabled: Boolean) = store.setFallbackEnabled(enabled)

    suspend fun setDemoMode(enabled: Boolean) = store.setDemoMode(enabled)

    suspend fun setBaseUrl(url: String) = store.setBaseUrl(url)

    suspend fun clearCatalogCache() = store.clearCatalog()

    private companion object {
        const val DEMO_TOKEN = "DEMO-TOKEN"
    }
}
