package cn.edu.whu.libseat.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.whu.libseat.core.time.TimeText
import cn.edu.whu.libseat.data.local.CatalogSnapshot
import cn.edu.whu.libseat.data.local.UserSettings
import cn.edu.whu.libseat.data.remote.SessionExpiredException
import cn.edu.whu.libseat.data.repository.LibraryRepository
import cn.edu.whu.libseat.domain.model.FavoriteSeat
import cn.edu.whu.libseat.domain.model.QuickBookResult
import cn.edu.whu.libseat.domain.model.Reservation
import cn.edu.whu.libseat.domain.model.RoomStats
import cn.edu.whu.libseat.domain.model.UserProfile
import cn.edu.whu.libseat.domain.usecase.QuickBookUseCase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class HomeUiState(
    val loading: Boolean = true,
    val settings: UserSettings = UserSettings(),
    val catalog: CatalogSnapshot? = null,
    val buildingId: Long? = null,
    val floor: Int? = null,
    val date: LocalDate = LocalDate.now(),
    val rooms: List<RoomStats> = emptyList(),
    val favorites: List<FavoriteSeat> = emptyList(),
    val reservation: Reservation? = null,
    val profile: UserProfile? = null,
    val error: String? = null,
    val bookingFavoriteSeatId: Long? = null,
) {
    val buildingName: String
        get() = catalog?.buildings?.firstOrNull { it.id == buildingId }?.name ?: "图书馆"

    val floors: List<Int>
        get() = catalog?.floorsOf(buildingId ?: -1L).orEmpty()

    val floorRooms: List<RoomStats>
        get() = rooms.filter { floor == null || it.room.floor == floor }

    val knownRooms: List<RoomStats>
        get() = floorRooms.filter { it.countsKnown }

    val countsKnown: Boolean get() = knownRooms.isNotEmpty()
    val freeSeats: Int get() = knownRooms.sumOf { it.free }
    val totalSeats: Int get() = knownRooms.sumOf { it.total }
}

class HomeViewModel(
    private val repo: LibraryRepository,
    private val quickBook: QuickBookUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            repo.favorites.collect { list ->
                _state.update { it.copy(favorites = list) }
            }
        }
        load()
    }

    fun load(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val settings = repo.currentSettings()
                val catalog = repo.catalog(forceRefresh)
                var buildingId = settings.defaultBuildingId
                    ?.takeIf { id -> catalog.buildings.any { it.id == id } }
                    ?: catalog.buildings.firstOrNull()?.id

                val floors = buildingId?.let { catalog.floorsOf(it) }.orEmpty()
                var floor = settings.defaultFloor?.takeIf { floors.contains(it) }
                    ?: floors.firstOrNull { it == 3 }
                    ?: floors.firstOrNull()

                val defaultBuildingValid = settings.defaultBuildingId != null &&
                    catalog.buildings.any { it.id == settings.defaultBuildingId }
                if (buildingId != null && floor != null &&
                    (!defaultBuildingValid || settings.defaultFloor == null)
                ) {
                    // 首次进入时落一个可用的默认值，之后由用户显式修改
                    repo.updateDefaultTarget(buildingId, floor)
                }

                // 先把目录和默认目标提交到界面：即使房间余量接口失败，
                // 也能看到真实的图书馆 / 楼层，而不是停留在旧数据上
                _state.update {
                    it.copy(
                        settings = settings,
                        catalog = catalog,
                        buildingId = buildingId,
                        floor = floor,
                        error = null,
                    )
                }

                val rooms = if (buildingId != null) {
                    repo.roomStats(buildingId, floor, TimeText.iso(_state.value.date))
                } else {
                    emptyList()
                }

                val reservation = runCatching { repo.reservations() }
                    .getOrDefault(emptyList())
                    .firstOrNull()
                val profile = if (!settings.demoMode && settings.isLoggedIn) repo.profile() else null

                _state.update {
                    it.copy(
                        loading = false,
                        settings = settings,
                        catalog = catalog,
                        buildingId = buildingId,
                        floor = floor,
                        rooms = rooms,
                        reservation = reservation,
                        profile = profile,
                        error = null,
                    )
                }
            } catch (e: SessionExpiredException) {
                val latest = runCatching { repo.currentSettings() }.getOrNull()
                _state.update {
                    it.copy(
                        loading = false,
                        settings = latest ?: it.settings,
                        error = "登录已失效，请重新登录",
                    )
                }
            } catch (e: Exception) {
                val latest = runCatching { repo.currentSettings() }.getOrNull()
                _state.update {
                    it.copy(
                        loading = false,
                        settings = latest ?: it.settings,
                        error = e.message ?: "加载失败，请检查网络",
                    )
                }
            }
        }
    }

    fun selectDate(date: LocalDate) {
        _state.update { it.copy(date = date) }
        val buildingId = _state.value.buildingId ?: return
        viewModelScope.launch {
            refreshRooms(buildingId, _state.value.floor)
        }
    }

    /** 从预约 / 设置页返回首页时刷新状态，不显示整页 loading。 */
    fun refreshOnResume() {
        viewModelScope.launch {
            val settings = runCatching { repo.currentSettings() }.getOrNull() ?: return@launch
            val targetChanged = settings.defaultBuildingId != _state.value.buildingId ||
                settings.defaultFloor != _state.value.floor
            val modeChanged = settings.demoMode != _state.value.settings.demoMode
            if (targetChanged || modeChanged) {
                // 默认图书馆 / 楼层或数据源发生变化，按新设置重新加载。
                // 切换数据源时先清空旧数据，避免把演示数据当成真实数据展示。
                if (modeChanged) {
                    _state.update {
                        it.copy(catalog = null, rooms = emptyList(), settings = settings, error = null)
                    }
                }
                load(forceRefresh = modeChanged)
                return@launch
            }
            _state.update { it.copy(settings = settings) }
            val reservation = runCatching { repo.reservations() }
                .getOrDefault(emptyList())
                .firstOrNull()
            _state.update { it.copy(reservation = reservation) }
            val buildingId = _state.value.buildingId ?: return@launch
            runCatching { repo.roomStats(buildingId, _state.value.floor, TimeText.iso(_state.value.date)) }.onSuccess { rooms ->
                _state.update { it.copy(rooms = rooms) }
            }
        }
    }

    fun selectBuilding(buildingId: Long) {
        val catalog = _state.value.catalog ?: return
        val floors = catalog.floorsOf(buildingId)
        if (floors.isEmpty()) return
        val floor = _state.value.floor?.takeIf { floors.contains(it) } ?: floors.first()
        _state.update { it.copy(buildingId = buildingId, floor = floor, rooms = emptyList()) }
        viewModelScope.launch {
            repo.updateDefaultTarget(buildingId, floor)
            refreshRooms(buildingId, floor)
        }
    }

    fun selectFloor(floor: Int) {
        _state.update { it.copy(floor = floor) }
        val buildingId = _state.value.buildingId ?: return
        viewModelScope.launch {
            repo.updateDefaultTarget(buildingId, floor)
            refreshRooms(buildingId, floor)
        }
    }

    fun quickBookFavorite(favorite: FavoriteSeat) {
        if (_state.value.bookingFavoriteSeatId != null) return
        viewModelScope.launch {
            _state.update { it.copy(bookingFavoriteSeatId = favorite.seatId) }
            try {
                val settings = repo.currentSettings()
                when (val result = quickBook.book(favorite, _state.value.date, settings.fallbackEnabled)) {
                    is QuickBookResult.Success -> {
                        val suffix = if (result.usedFallback) {
                            "（${result.requestedSeatName} 已被占用，已换到附近 ${result.seat.name} 号）"
                        } else {
                            ""
                        }
                        _messages.emit("预约成功：${result.seat.name} 号 $suffix")
                        refreshReservation()
                    }

                    is QuickBookResult.Failure -> _messages.emit(result.message)
                    is QuickBookResult.NotLoggedIn -> _messages.emit("请先登录图书馆账号")
                }
            } catch (e: SessionExpiredException) {
                _messages.emit("登录已失效，请重新登录")
            } catch (e: Exception) {
                _messages.emit(e.message ?: "预约失败")
            } finally {
                _state.update { it.copy(bookingFavoriteSeatId = null) }
            }
        }
    }

    fun cancelReservation(id: Long) {
        viewModelScope.launch {
            try {
                repo.cancel(id)
                _messages.emit("已取消预约")
                refreshReservation()
            } catch (e: Exception) {
                _messages.emit(e.message ?: "取消失败")
            }
        }
    }

    private fun refreshReservation() {
        viewModelScope.launch {
            val reservation = runCatching { repo.reservations() }.getOrDefault(emptyList()).firstOrNull()
            _state.update { it.copy(reservation = reservation) }
            val buildingId = _state.value.buildingId ?: return@launch
            refreshRooms(buildingId, _state.value.floor)
        }
    }

    private suspend fun refreshRooms(buildingId: Long, floor: Int?) {
        runCatching { repo.roomStats(buildingId, floor, TimeText.iso(_state.value.date)) }.onSuccess { rooms ->
            _state.update { it.copy(rooms = rooms) }
        }
    }
}
