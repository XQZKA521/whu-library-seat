package cn.edu.whu.libseat.ui.captcha

import cn.edu.whu.libseat.core.log.AppLog
import cn.edu.whu.libseat.data.local.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CaptchaException(message: String) : Exception(message)

data class CaptchaState(
    val visible: Boolean = false,
    val username: String = "",
    val baseUrl: String = "",
    val hint: String? = null,
)

/**
 * 预约前的人机校验协调器。
 *
 * 预约协程调用 [solve] 挂起，UI 弹出原生卡片（内嵌官方滑块组件）；
 * 用户完成后 [onToken] 返回 capToken，预约继续。
 */
class CaptchaManager(
    private val settingsStore: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(CaptchaState())
    val state: StateFlow<CaptchaState> = _state.asStateFlow()

    private var continuation: kotlinx.coroutines.CancellableContinuation<String?>? = null

    suspend fun solve(hint: String? = null): String? {
        AppLog.i("Captcha", "预约触发人机校验，等待用户完成滑块${if (hint.isNullOrBlank()) "" else "（$hint）"}")
        val settings = settingsStore.current()
        return suspendCancellableCoroutine { cont ->
            continuation = cont
            _state.value = CaptchaState(
                visible = true,
                username = settings.username,
                baseUrl = settings.baseUrl,
                hint = hint,
            )
            cont.invokeOnCancellation {
                if (continuation === cont) {
                    continuation = null
                    _state.value = CaptchaState()
                }
            }
        }
    }

    fun onToken(token: String) {
        AppLog.i("Captcha", "拿到 capToken，长度=${token.length}")
        val cont = continuation ?: return
        continuation = null
        _state.value = CaptchaState()
        if (cont.isActive) cont.resume(token)
    }

    fun cancel() {
        AppLog.w("Captcha", "用户取消验证")
        val cont = continuation ?: return
        continuation = null
        _state.value = CaptchaState()
        if (cont.isActive) {
            cont.resumeWithException(CaptchaException("已取消安全验证，预约未提交"))
        }
    }

    fun refresh() {
        // 官方组件自带刷新按钮，这里保留空实现以兼容调用方
        if (_state.value.visible) {
            scope.launch { /* no-op */ }
        }
    }
}
