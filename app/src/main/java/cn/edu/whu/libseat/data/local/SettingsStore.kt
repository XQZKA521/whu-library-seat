package cn.edu.whu.libseat.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cn.edu.whu.libseat.domain.model.FavoriteSeat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.seatDataStore: DataStore<Preferences> by preferencesDataStore(name = "library_seat")

data class UserSettings(
    val username: String = "",
    val token: String = "",
    val demoMode: Boolean = true,
    val baseUrl: String = DEFAULT_BASE_URL,
    val defaultBuildingId: Long? = null,
    val defaultFloor: Int? = null,
    val defaultStart: Int = 8 * 60,
    val defaultEnd: Int = 12 * 60,
    val fallbackEnabled: Boolean = true,
    val webLoggedIn: Boolean = false,
    val displayName: String = "",
) {
    val isLoggedIn: Boolean get() = token.isNotBlank()
}

const val DEFAULT_BASE_URL = "https://seat.lib.whu.edu.cn"

/**
 * 本地配置中心。
 *
 * 复用 Ham 的“首选座位 + 默认时间”思路，但把落地方式改成了 DataStore：
 * - 账号会话（token）、演示模式、服务器地址
 * - 默认图书馆 / 默认楼层
 * - 默认预约时间段、失败是否自动换座
 * - 常用座位列表
 */
class SettingsStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: Flow<UserSettings> = context.seatDataStore.data.map { prefs ->
        UserSettings(
            username = prefs[KEY_USERNAME].orEmpty(),
            token = prefs[KEY_TOKEN].orEmpty(),
            demoMode = prefs[KEY_DEMO_MODE] ?: true,
            baseUrl = prefs[KEY_BASE_URL] ?: DEFAULT_BASE_URL,
            defaultBuildingId = prefs[KEY_DEFAULT_BUILDING],
            defaultFloor = prefs[KEY_DEFAULT_FLOOR],
            defaultStart = prefs[KEY_DEFAULT_START] ?: 8 * 60,
            defaultEnd = prefs[KEY_DEFAULT_END] ?: 12 * 60,
            fallbackEnabled = prefs[KEY_FALLBACK] ?: true,
            webLoggedIn = prefs[KEY_WEB_LOGIN] ?: false,
            displayName = prefs[KEY_DISPLAY_NAME].orEmpty(),
        )
    }

    val favorites: Flow<List<FavoriteSeat>> = context.seatDataStore.data.map { prefs ->
        decodeFavorites(prefs[KEY_FAVORITES])
    }

    val catalogCache: Flow<CatalogSnapshot?> = context.seatDataStore.data.map { prefs ->
        prefs[KEY_CATALOG]?.let { raw ->
            runCatching { json.decodeFromString<CatalogSnapshot>(raw) }.getOrNull()
        }
    }

    suspend fun current(): UserSettings = settings.first()

    suspend fun saveCatalog(snapshot: CatalogSnapshot) {
        context.seatDataStore.edit { it[KEY_CATALOG] = json.encodeToString(snapshot) }
    }

    suspend fun clearCatalog() {
        context.seatDataStore.edit { it.remove(KEY_CATALOG) }
    }

    suspend fun saveSession(username: String, token: String) {
        context.seatDataStore.edit {
            it[KEY_USERNAME] = username
            it[KEY_TOKEN] = token
            it[KEY_DEMO_MODE] = false
        }
    }

    suspend fun clearSession() {
        context.seatDataStore.edit {
            it.remove(KEY_USERNAME)
            it.remove(KEY_TOKEN)
            it.remove(KEY_WEB_LOGIN)
            it.remove(KEY_DISPLAY_NAME)
        }
    }

    /**
     * 保存 WebView 登录后从座位系统会话里取到的 token。
     * 不改变演示模式，数据源仍由用户显式控制。
     */
    suspend fun saveWebSession(token: String, username: String, displayName: String) {
        context.seatDataStore.edit {
            it[KEY_TOKEN] = token
            it[KEY_USERNAME] = username
            it[KEY_DISPLAY_NAME] = displayName
            it[KEY_WEB_LOGIN] = true
            // Web 登录成功后切到真实数据源
            it[KEY_DEMO_MODE] = false
        }
    }

    suspend fun setDemoMode(enabled: Boolean) {
        context.seatDataStore.edit { it[KEY_DEMO_MODE] = enabled }
    }

    suspend fun setBaseUrl(url: String) {
        context.seatDataStore.edit { it[KEY_BASE_URL] = url.trim().trimEnd('/') }
    }

    suspend fun setDefaultTarget(buildingId: Long, floor: Int) {
        context.seatDataStore.edit {
            it[KEY_DEFAULT_BUILDING] = buildingId
            it[KEY_DEFAULT_FLOOR] = floor
        }
    }

    suspend fun setDefaultFloor(floor: Int) {
        context.seatDataStore.edit { it[KEY_DEFAULT_FLOOR] = floor }
    }

    suspend fun setDefaultWindow(startMinutes: Int, endMinutes: Int) {
        context.seatDataStore.edit {
            it[KEY_DEFAULT_START] = startMinutes
            it[KEY_DEFAULT_END] = endMinutes
        }
    }

    suspend fun setFallbackEnabled(enabled: Boolean) {
        context.seatDataStore.edit { it[KEY_FALLBACK] = enabled }
    }

    // ---------------- 常用座位 ----------------

    suspend fun addFavorite(seat: FavoriteSeat) {
        context.seatDataStore.edit { prefs ->
            val list = decodeFavorites(prefs[KEY_FAVORITES]).toMutableList()
            val existing = list.indexOfFirst { it.seatId == seat.seatId && it.buildingId == seat.buildingId }
            if (existing >= 0) {
                list[existing] = seat
            } else {
                list.add(0, seat)
            }
            prefs[KEY_FAVORITES] = json.encodeToString(list)
        }
    }

    suspend fun removeFavorite(seatId: Long, buildingId: Long) {
        context.seatDataStore.edit { prefs ->
            val list = decodeFavorites(prefs[KEY_FAVORITES])
                .filterNot { it.seatId == seatId && it.buildingId == buildingId }
            prefs[KEY_FAVORITES] = json.encodeToString(list)
        }
    }

    suspend fun isFavorite(seatId: Long, buildingId: Long): Boolean =
        favorites.first().any { it.seatId == seatId && it.buildingId == buildingId }

    private fun decodeFavorites(raw: String?): List<FavoriteSeat> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<FavoriteSeat>>(raw) }.getOrDefault(emptyList())
    }

    private companion object {
        val KEY_USERNAME = stringPreferencesKey("username")
        val KEY_TOKEN = stringPreferencesKey("token")
        val KEY_DEMO_MODE = booleanPreferencesKey("demo_mode")
        val KEY_BASE_URL = stringPreferencesKey("base_url")
        val KEY_DEFAULT_BUILDING = longPreferencesKey("default_building_id")
        val KEY_DEFAULT_FLOOR = intPreferencesKey("default_floor")
        val KEY_DEFAULT_START = intPreferencesKey("default_start")
        val KEY_DEFAULT_END = intPreferencesKey("default_end")
        val KEY_FALLBACK = booleanPreferencesKey("fallback_enabled")
        val KEY_FAVORITES = stringPreferencesKey("favorite_seats")
        val KEY_CATALOG = stringPreferencesKey("catalog_cache_v2")
        val KEY_WEB_LOGIN = booleanPreferencesKey("web_logged_in")
        val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
    }
}
