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
    val bookError: String? = null,
    val reservation: Reservation? = null,
    val cancelling: Boolean = false,
    val stopping: Boolean = false,
    /** 用户选择了官方“现在”选项：预约按协议传 startMinute=-1。 */
    val startIsNow: Boolean = false,
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
                val reservation = runCatching { repo.reservations().firstOrNull() }.getOrNull()
                _state.update {
                    it.copy(
                        loading = false,
                        map = map.copy(roomName = room.name),
                        room = room,
                        building = building,
                        settings = settings,
                        favorites = favorites.map { seat -> seat.seatId }.toSet(),
                        reservation = reservation,
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
                // 官方允许从当前精确时间开始，不取整到半小时
                desiredStart = maxOf(desiredStart, TimeText.nowMinutes())
            }
            val startSlots = runCatching { repo.startTimes(seat.id, dateText) }.getOrDefault(emptyList())
            val startSlot = startSlots.firstOrNull { it.minutes >= desiredStart }
                ?: startSlots.firstOrNull()
            val start = startSlot?.minutes ?: desiredStart
            val startIsNow = startSlot?.isNow == true

            val endSlots = runCatching { repo.endTimes(seat.id, dateText, start) }.getOrDefault(emptyList())
            val end = endSlots.firstOrNull { it.minutes >= maxOf(settings.defaultEnd, start + 30) }?.minutes
                ?: endSlots.firstOrNull { it.minutes > start }?.minutes
                ?: (start + 60)

            _state.update {
                it.copy(
                    selectedSeat = seat,
                    start = start,
                    end = end,
                    startIsNow = startIsNow,
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
        val slot = _state.value.startSlots.firstOrNull { it.minutes == minutes }
        val isNow = slot?.isNow == true
        _state.update { it.copy(start = minutes, startIsNow = isNow) }
        viewModelScope.launch {
            // 官方行为：选“现在”时用精确当前分钟拉结束时间
            val endSlots = runCatching { repo.endTimes(seat.id, dateText, minutes) }.getOrDefault(emptyList())
            val end = endSlots.firstOrNull { it.minutes > minutes }?.minutes ?: (minutes + 30)
            _state.update { it.copy(end = end, endSlots = endSlots) }
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
                // 官方协议：选“现在”时 startMinute 传 -1，服务端取当前时间
                val startMinute = if (s.startIsNow) -1 else s.start
                val reservation = repo.book(seat.id, dateText, startMinute, s.end)
                _state.update {
                    it.copy(booking = false, booked = reservation, reservation = reservation)
                }
            } catch (e: SessionExpiredException) {
                _state.update { it.copy(booking = false, bookError = "登录已失效，请重新登录") }
            } catch (e: Exception) {
                // 预约错误用对话框展示，避免被底部卡片挡住
                _state.update { it.copy(booking = false, bookError = e.message ?: "预约失败") }
            }
        }
    }

    fun dismissBooked() {
        _state.update { it.copy(booked = null) }
    }

    fun dismissBookError() {
        _state.update { it.copy(bookError = null) }
    }

    fun cancelReservation() {
        val r = _state.value.reservation ?: return
        if (_state.value.cancelling) return
        viewModelScope.launch {
            _state.update { it.copy(cancelling = true) }
            try {
                repo.cancel(r.id)
                _state.update { it.copy(reservation = null, cancelling = false) }
                _messages.emit("预约已取消")
            } catch (e: Exception) {
                _state.update { it.copy(cancelling = false) }
                _messages.emit(e.message ?: "取消失败")
            }
        }
    }

    /** 提前结束履约（官方 make/stop），成功后刷新当前预约状态。 */
    fun stopUsage() {
        val r = _state.value.reservation ?: return
        if (_state.value.stopping) return
        viewModelScope.launch {
            _state.update { it.copy(stopping = true) }
            try {
                repo.stopUsage(r.id)
                val latest = runCatching { repo.reservations().firstOrNull() }.getOrNull()
                _state.update { it.copy(reservation = latest, stopping = false) }
                _messages.emit("已提前结束使用")
            } catch (e: SessionExpiredException) {
                _state.update { it.copy(stopping = false) }
                _messages.emit("登录已失效，请重新登录")
            } catch (e: Exception) {
                _state.update { it.copy(stopping = false) }
                _messages.emit(e.message ?: "结束使用失败")
            }
        }
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
