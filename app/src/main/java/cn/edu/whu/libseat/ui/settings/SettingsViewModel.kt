package cn.edu.whu.libseat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.whu.libseat.data.local.CatalogSnapshot
import cn.edu.whu.libseat.data.local.UserSettings
import cn.edu.whu.libseat.data.repository.LibraryRepository
import cn.edu.whu.libseat.domain.model.HistoryItem
import cn.edu.whu.libseat.domain.model.UserProfile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val loading: Boolean = true,
    val settings: UserSettings = UserSettings(),
    val catalog: CatalogSnapshot? = null,
    val profile: UserProfile? = null,
    val history: List<HistoryItem> = emptyList(),
    val error: String? = null,
    val refreshing: Boolean = false,
) {
    val defaultBuildingName: String
        get() = catalog?.buildings?.firstOrNull { it.id == settings.defaultBuildingId }?.name ?: "未设置"

    val floors: List<Int>
        get() = catalog?.floorsOf(settings.defaultBuildingId ?: -1).orEmpty()
}

class SettingsViewModel(
    private val repo: LibraryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            repo.userSettings.collect { s -> _state.update { it.copy(settings = s) } }
        }
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val settings = repo.currentSettings()
                val catalog = repo.catalog()
                val profile = if (!settings.demoMode && settings.isLoggedIn) repo.profile() else null
                val history = runCatching { repo.history() }.getOrDefault(emptyList())
                _state.update {
                    it.copy(
                        loading = false,
                        settings = settings,
                        catalog = catalog,
                        profile = profile,
                        history = history,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "加载失败") }
            }
        }
    }

    fun refreshCatalog() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                val catalog = repo.catalog(forceRefresh = true)
                _state.update { it.copy(catalog = catalog, refreshing = false) }
                _messages.emit("基础数据已更新")
            } catch (e: Exception) {
                _state.update { it.copy(refreshing = false) }
                _messages.emit(e.message ?: "更新失败")
            }
        }
    }

    fun setDefaultBuilding(buildingId: Long) {
        val floors = _state.value.catalog?.floorsOf(buildingId).orEmpty()
        val floor = floors.firstOrNull() ?: return
        viewModelScope.launch {
            repo.updateDefaultTarget(buildingId, floor)
            _messages.emit("默认图书馆已设为 ${_state.value.catalog?.buildings?.firstOrNull { it.id == buildingId }?.name}")
        }
    }

    fun setDefaultFloor(floor: Int) {
        val buildingId = _state.value.settings.defaultBuildingId ?: return
        viewModelScope.launch {
            repo.updateDefaultTarget(buildingId, floor)
        }
    }

    fun setDefaultWindow(start: Int, end: Int) {
        if (end <= start) {
            viewModelScope.launch { _messages.emit("结束时间需要晚于开始时间") }
            return
        }
        viewModelScope.launch {
            repo.updateDefaultWindow(start, end)
            _messages.emit("默认时间段已更新")
        }
    }

    fun setFallback(enabled: Boolean) {
        viewModelScope.launch { repo.setFallback(enabled) }
    }

    fun setDemoMode(enabled: Boolean) {
        viewModelScope.launch {
            repo.setDemoMode(enabled)
            val catalog = runCatching { repo.catalog(forceRefresh = true) }.getOrNull()
            _state.update { it.copy(catalog = catalog ?: it.catalog) }
            _messages.emit(if (enabled) "已切换到演示模式" else "已切换到真实账号模式")
        }
    }

    fun setBaseUrl(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            repo.setBaseUrl(url)
            repo.clearCatalogCache()
            _messages.emit("服务器地址已保存")
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            repo.clearCatalogCache()
            _messages.emit("本地基础数据已清除")
        }
    }

    fun logout() {
        viewModelScope.launch {
            repo.logout()
            // 退出后回到演示数据，避免首页因缺少 token 直接报错
            repo.setDemoMode(true)
            _state.update { it.copy(profile = null) }
            _messages.emit("已退出登录")
        }
    }
}
