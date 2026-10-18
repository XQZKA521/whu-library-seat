package cn.edu.whu.libseat.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.whu.libseat.data.local.UserSettings
import cn.edu.whu.libseat.data.remote.SessionExpiredException
import cn.edu.whu.libseat.data.repository.LibraryRepository
import cn.edu.whu.libseat.domain.model.FavoriteSeat
import cn.edu.whu.libseat.domain.model.QuickBookResult
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

data class FavoritesUiState(
    val favorites: List<FavoriteSeat> = emptyList(),
    val settings: UserSettings = UserSettings(),
    val bookingSeatId: Long? = null,
)

class FavoritesViewModel(
    private val repo: LibraryRepository,
    private val quickBook: QuickBookUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(FavoritesUiState())
    val state: StateFlow<FavoritesUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            repo.favorites.collect { list -> _state.update { it.copy(favorites = list) } }
        }
        viewModelScope.launch {
            repo.userSettings.collect { s -> _state.update { it.copy(settings = s) } }
        }
    }

    fun book(favorite: FavoriteSeat, date: LocalDate = LocalDate.now()) {
        if (_state.value.bookingSeatId != null) return
        viewModelScope.launch {
            _state.update { it.copy(bookingSeatId = favorite.seatId) }
            try {
                val settings = repo.currentSettings()
                when (val result = quickBook.book(favorite, date, settings.fallbackEnabled)) {
                    is QuickBookResult.Success -> {
                        val suffix = if (result.usedFallback) {
                            "，已从 ${result.requestedSeatName} 换到附近 ${result.seat.name} 号"
                        } else {
                            ""
                        }
                        _messages.emit("预约成功：${result.seat.name} 号$suffix")
                    }

                    is QuickBookResult.Failure -> _messages.emit(result.message)
                    is QuickBookResult.NotLoggedIn -> _messages.emit("请先登录图书馆账号")
                }
            } catch (e: SessionExpiredException) {
                _messages.emit("登录已失效，请重新登录")
            } catch (e: Exception) {
                _messages.emit(e.message ?: "预约失败")
            } finally {
                _state.update { it.copy(bookingSeatId = null) }
            }
        }
    }

    fun remove(favorite: FavoriteSeat) {
        viewModelScope.launch {
            repo.removeFavorite(favorite.seatId, favorite.buildingId)
            _messages.emit("已移除 ${favorite.seatName} 号")
        }
    }
}
