package cn.edu.whu.libseat.ui.seatmap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.whu.libseat.core.time.TimeText
import cn.edu.whu.libseat.data.local.UserSettings
import cn.edu.whu.libseat.data.remote.SessionExpiredException
import cn.edu.whu.libseat.data.repository.LibraryRepository
import cn.edu.whu.libseat.domain.model.Building
import cn.edu.whu.libseat.domain.model.FavoriteSeat
import cn.edu.whu.libseat.domain.model.Reservation
import cn.edu.whu.libseat.domain.model.RoomInfo
import cn.edu.whu.libseat.domain.model.Seat
import cn.edu.whu.libseat.domain.model.SeatMap
import cn.edu.whu.libseat.domain.model.SeatStatus
import cn.edu.whu.libseat.domain.model.TimeSlot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class SeatMapUiState(
    val loading: Boolean = true,
    val map: SeatMap? = null,
    val room: RoomInfo? = null,
    val building: Building? = null,
    val date: LocalDate = LocalDate.now(),
    val error: String? = null,
    val selectedSeat: Seat? = null,
    val favorites: Set<Long> = emptySet(),
    val selectedFavorite: Boolean = false,
    val start: Int = 8 * 60,
    val end: Int = 12 * 60,
    val startSlots: List<TimeSlot> = emptyList(),
    val endSlots: List<TimeSlot> = emptyList(),
    val booking: Boolean = false,
    val booked: Reservation? = null,
    val settings: UserSettings = UserSettings(),
)

class SeatMapViewModel(
    private val repo: LibraryRepository,
    private val roomId: Long,
    private val buildingId: Long,
    private val floor: Int,
    private val dateText: String,
) : ViewModel() {

    private val _state = MutableStateFlow(SeatMapUiState(date = TimeText.parseIso(dateText) ?: LocalDate.now()))
    val state: StateFlow<SeatMapUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val settings = repo.currentSettings()
                val catalog = repo.catalog()
                val map = repo.seatMap(roomId, dateText)
                val room = catalog.rooms.firstOrNull { it.id == roomId }
                    ?: RoomInfo(roomId, map.roomName.ifBlank { "座位区" }, buildingId, floor)
                val building = catalog.buildings.firstOrNull { it.id == room.buildingId }
                val favorites = runCatching { repo.favorites.first() }.getOrDefault(emptyList())
                _state.update {
                    it.copy(
                        loading = false,
                        map = map.copy(roomName = room.name),
                        room = room,
                        building = building,
                        settings = settings,
                        favorites = favorites.map { seat -> seat.seatId }.toSet(),
                        error = null,
                    )
                }
            } catch (e: SessionExpiredException) {
                _state.update { it.copy(loading = false, error = "登录已失效，请重新登录") }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "座位图加载失败") }
            }
        }
    }

    fun selectSeat(seat: Seat) {
        if (seat.status != SeatStatus.FREE) return
        viewModelScope.launch {
            val settings = _state.value.settings
            var desiredStart = settings.defaultStart
            if (_state.value.date == TimeText.today()) {
                desiredStart = maxOf(desiredStart, TimeText.nowRoundedToHalfHour())
            }
            val startSlots = runCatching { repo.startTimes(seat.id, dateText) }.getOrDefault(emptyList())
            val start = startSlots.firstOrNull { it.minutes >= desiredStart }?.minutes
                ?: startSlots.firstOrNull()?.minutes
                ?: desiredStart

            val endSlots = runCatching { repo.endTimes(seat.id, dateText, start) }.getOrDefault(emptyList())
            val end = endSlots.firstOrNull { it.minutes >= settings.defaultEnd }?.minutes
                ?: endSlots.firstOrNull()?.minutes
                ?: (start + 60)

            _state.update {
                it.copy(
                    selectedSeat = seat,
                    start = start,
                    end = end,
                    startSlots = startSlots,
                    endSlots = endSlots,
                    selectedFavorite = it.favorites.contains(seat.id),
                )
            }
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedSeat = null) }
    }

    fun selectStart(minutes: Int) {
        val seat = _state.value.selectedSeat ?: return
        viewModelScope.launch {
            val endSlots = runCatching { repo.endTimes(seat.id, dateText, minutes) }.getOrDefault(emptyList())
            val end = endSlots.firstOrNull { it.minutes > minutes }?.minutes ?: (minutes + 30)
            _state.update { it.copy(start = minutes, end = end, endSlots = endSlots) }
        }
    }

    fun selectEnd(minutes: Int) {
        _state.update { it.copy(end = minutes) }
    }

    fun book() {
        val s = _state.value
        val seat = s.selectedSeat ?: return
        if (s.booking) return
        viewModelScope.launch {
            _state.update { it.copy(booking = true) }
            try {
                val reservation = repo.book(seat.id, dateText, s.start, s.end)
                _state.update { it.copy(booking = false, booked = reservation) }
            } catch (e: SessionExpiredException) {
                _state.update { it.copy(booking = false) }
                _messages.emit("登录已失效，请重新登录")
            } catch (e: Exception) {
                _state.update { it.copy(booking = false) }
                _messages.emit(e.message ?: "预约失败")
            }
        }
    }

    fun dismissBooked() {
        _state.update { it.copy(booked = null) }
    }

    fun toggleFavorite() {
        val s = _state.value
        val seat = s.selectedSeat ?: return
        val room = s.room ?: return
        val building = s.building ?: return
        viewModelScope.launch {
            if (s.selectedFavorite) {
                repo.removeFavorite(seat.id, building.id)
                _state.update {
                    it.copy(selectedFavorite = false, favorites = it.favorites - seat.id)
                }
                _messages.emit("已取消收藏")
            } else {
                repo.addFavorite(
                    FavoriteSeat(
                        seatId = seat.id,
                        seatName = seat.name,
                        roomId = room.id,
                        roomName = room.name,
                        buildingId = building.id,
                        buildingName = building.name,
                        floor = room.floor,
                        nearWindow = seat.nearWindow,
                        hasPower = seat.hasPower,
                        createdAt = System.currentTimeMillis(),
                    )
                )
                _state.update {
                    it.copy(selectedFavorite = true, favorites = it.favorites + seat.id)
                }
                _messages.emit("已加入常用座位")
            }
        }
    }
}
