package cn.edu.whu.libseat.data.repository

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

    suspend fun profile(): UserProfile? {
        val (api, token) = session()
        return runCatching { api.user(token) }.getOrNull()
    }

    // ---------------- 基础数据 ----------------

    suspend fun catalog(forceRefresh: Boolean = false): CatalogSnapshot {
        val source = sourceName()
        val cached = store.catalogCache.first()
        if (!forceRefresh && cached != null && cached.source == source && cached.isFresh()) {
            return cached
        }
        return try {
            val (api, token) = session()
            val remote = api.catalog(token)
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
        val (api, token) = session()
        return if (api is cn.edu.whu.libseat.data.remote.JsqLibraryApi) {
            api.roomStatsForFloor(token, buildingId, floorNumber, date)
        } else {
            api.roomStats(token, buildingId)
        }
    }

    suspend fun seatMap(roomId: Long, date: String): SeatMap {
        val (api, token) = session()
        return api.seatMap(token, roomId, date)
    }

    // ---------------- 预约 ----------------

    suspend fun book(seatId: Long, date: String, start: Int, end: Int): Reservation {
        val (api, token) = session()
        return api.book(token, seatId, date, start, end)
    }

    suspend fun reservations(): List<Reservation> {
        val s = store.current()
        if (!s.demoMode && s.token.isBlank()) return emptyList()
        val (api, token) = session()
        return api.reservations(token)
    }

    suspend fun cancel(reservationId: Long) {
        val (api, token) = session()
        api.cancel(token, reservationId)
    }

    suspend fun history(): List<HistoryItem> {
        val s = store.current()
        if (!s.demoMode && s.token.isBlank()) return emptyList()
        val (api, token) = session()
        return api.history(token)
    }

    suspend fun startTimes(seatId: Long, date: String) =
        session().let { (api, token) -> api.startTimes(token, seatId, date) }

    suspend fun endTimes(seatId: Long, date: String, start: Int) =
        session().let { (api, token) -> api.endTimes(token, seatId, date, start) }

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
