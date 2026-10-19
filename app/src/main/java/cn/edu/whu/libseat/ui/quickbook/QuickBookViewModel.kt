package cn.edu.whu.libseat.ui.quickbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.whu.libseat.core.time.TimeText
import cn.edu.whu.libseat.data.repository.LibraryRepository
import cn.edu.whu.libseat.domain.model.FavoriteSeat
import cn.edu.whu.libseat.domain.model.QuickBookResult
import cn.edu.whu.libseat.domain.model.Reservation
import cn.edu.whu.libseat.domain.usecase.QuickBookUseCase
import cn.edu.whu.libseat.widget.WidgetStore
import cn.edu.whu.libseat.widget.WidgetSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 小部件点“预约”时带过来的请求。 */
data class QuickBookRequest(
    val seatId: Long,
    val date: String,
    val start: Int,
    val end: Int,
)

enum class QuickBookPhase {
    LOADING,
    BOOKING,
    SUCCESS,
    FAILURE,
    NOT_LOGGED_IN,
    MISSING,
}

data class QuickBookUiState(
    val favorite: FavoriteSeat? = null,
    val phase: QuickBookPhase = QuickBookPhase.LOADING,
    val message: String = "",
    val bookedSeatName: String = "",
    val requestedSeatName: String = "",
    val usedFallback: Boolean = false,
    val reservation: Reservation? = null,
)

/**
 * 小部件发起的快速预约。
 *
 * 复用 [QuickBookUseCase]：同一套“目标座位 → 附近空座回退”的策略，
 * 预约前的人机校验由全局 [cn.edu.whu.libseat.ui.captcha.CaptchaManager] 弹原生卡片完成。
 */
class QuickBookViewModel(
    private val repo: LibraryRepository,
    private val quickBook: QuickBookUseCase,
    private val request: QuickBookRequest,
) : ViewModel() {

    private val _state = MutableStateFlow(QuickBookUiState())
    val state: StateFlow<QuickBookUiState> = _state.asStateFlow()

    private val date: LocalDate = TimeText.parseIso(request.date) ?: LocalDate.now()

    init {
        submit()
    }

    /** 重新走一遍：加载收藏 → 预约。 */
    fun submit() {
        viewModelScope.launch {
            if (_state.value.phase == QuickBookPhase.BOOKING) return@launch
            _state.update { it.copy(phase = QuickBookPhase.LOADING, message = "") }
            val favorite = runCatching { repo.favorites.first() }.getOrDefault(emptyList())
                .firstOrNull { it.seatId == request.seatId }
            if (favorite == null) {
                _state.update {
                    it.copy(
                        phase = QuickBookPhase.MISSING,
                        message = "该常用座位已不在收藏中，请回首页重新收藏后再试",
                    )
                }
                return@launch
            }
            _state.update { it.copy(favorite = favorite, phase = QuickBookPhase.BOOKING) }
            book(favorite)
        }
    }

    private suspend fun book(favorite: FavoriteSeat) {
        val fallback = runCatching { repo.currentSettings() }
            .getOrNull()?.fallbackEnabled ?: true
        try {
            when (val result = quickBook.book(favorite, date, request.start, request.end, fallback)) {
                is QuickBookResult.Success -> {
                    WidgetSync.publishReservation(result.reservation)
                    _state.update {
                        it.copy(
                            phase = QuickBookPhase.SUCCESS,
                            bookedSeatName = result.seat.name,
                            requestedSeatName = result.requestedSeatName,
                            usedFallback = result.usedFallback,
                            reservation = result.reservation,
                        )
                    }
                }

                is QuickBookResult.Failure -> {
                    WidgetSync.publishStatus(result.message.take(30), WidgetStore.KIND_ERROR)
                    _state.update {
                        it.copy(phase = QuickBookPhase.FAILURE, message = result.message)
                    }
                }

                is QuickBookResult.NotLoggedIn -> _state.update {
                    it.copy(
                        phase = QuickBookPhase.NOT_LOGGED_IN,
                        message = "登录已失效，请重新登录后再预约",
                    )
                }
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(phase = QuickBookPhase.FAILURE, message = e.message ?: "网络异常，请稍后重试")
            }
        }
    }
}
