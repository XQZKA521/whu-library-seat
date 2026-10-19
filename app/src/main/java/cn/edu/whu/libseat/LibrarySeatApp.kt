package cn.edu.whu.libseat

import android.app.Application
import android.content.Context
import android.webkit.WebView
import cn.edu.whu.libseat.core.log.AppLog
import cn.edu.whu.libseat.data.local.DEFAULT_BASE_URL
import cn.edu.whu.libseat.data.local.SettingsStore
import cn.edu.whu.libseat.data.remote.JsqLibraryApi
import cn.edu.whu.libseat.data.remote.JsqSessionClient
import cn.edu.whu.libseat.data.remote.LibraryApi
import cn.edu.whu.libseat.data.remote.WhuLibraryApi
import cn.edu.whu.libseat.data.remote.defaultOkHttpClient
import cn.edu.whu.libseat.data.repository.LibraryRepository
import cn.edu.whu.libseat.data.session.CasSessionManager
import cn.edu.whu.libseat.domain.usecase.QuickBookUseCase
import cn.edu.whu.libseat.ui.captcha.CaptchaManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 轻量手工依赖容器（不引入 Hilt，保持构建简单）。
 */
class AppContainer(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsStore = SettingsStore(context.applicationContext)

    @Volatile
    private var baseUrl: String = DEFAULT_BASE_URL

    private val httpClient = defaultOkHttpClient()

    private val realApi: LibraryApi = WhuLibraryApi(
        client = httpClient,
        baseUrlProvider = { baseUrl },
    )

    val jsqSession = JsqSessionClient(httpClient)

    /** 预约前的人机校验（原生滑块弹窗）。 */
    val captchaManager = CaptchaManager(settingsStore)

    private val jsqApi: LibraryApi = JsqLibraryApi(
        client = httpClient,
        baseUrlProvider = { baseUrl },
        capTokenProvider = { hint -> captchaManager.solve(hint) },
    )

    /** token 过期时通过统一认证 SSO 静默续期。 */
    private val casSession = CasSessionManager(context.applicationContext, jsqApi as JsqLibraryApi)

    val repository = LibraryRepository(
        store = settingsStore,
        realApi = realApi,
        jsqApi = jsqApi,
        sessionRefresher = { casSession.reAuth() },
    )

    val quickBook = QuickBookUseCase(repository)

    init {
        scope.launch {
            settingsStore.settings.collect { baseUrl = it.baseUrl.ifBlank { DEFAULT_BASE_URL } }
        }
    }
}

class LibrarySeatApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        AppLog.install(this)
        container = AppContainer(this)
    }
}
