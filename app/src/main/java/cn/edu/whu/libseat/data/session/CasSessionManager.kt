package cn.edu.whu.libseat.data.session

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import cn.edu.whu.libseat.core.log.AppLog
import cn.edu.whu.libseat.data.remote.JsqLibraryApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 统一认证（CAS/智慧珞珈 rem SSO）静默续期：
 *
 * 1. 隐藏 WebView 打开 `/rem/static/sso/login?redirectUrl=/seat`；
 * 2. 若统一认证的记住登录 Cookie 仍有效，会自动 302 回跳并带上 `?token=票据`；
 * 3. 原生截取票据，调 `POST /jsq/static/public/auth/cas/{ticket}` 换取新的 jsq token。
 *
 * 用户只需在 WebView 里用「统一身份认证登录」成功过一次，之后 token 过期即可静默续期。
 */
class CasSessionManager(
    private val context: Context,
    private val jsqApi: JsqLibraryApi,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun reAuth(): String? {
        val ticket = acquireTicket() ?: return null
        AppLog.i("CasAuth", "票据换取登录态")
        return jsqApi.casAuth(ticket)
    }

    private suspend fun acquireTicket(timeoutMs: Long = 20000): String? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val holder = arrayOfNulls<WebView>(1)
                val done = AtomicBoolean(false)
                val finishRef = java.util.concurrent.atomic.AtomicReference<(String?) -> Unit>()
                val timeout = Runnable { finishRef.get()?.invoke(null) }

                val wv = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = DESKTOP_UA
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView,
                            url: String,
                            favicon: Bitmap?,
                        ) {
                            AppLog.d("CasAuth", "SSO 跳转 $url")
                            val token = Regex("[?&]token=([^&#]+)").find(url)
                                ?.groupValues?.getOrNull(1)
                            if (token != null) {
                                finishRef.get()?.invoke(URLDecoder.decode(token, "UTF-8"))
                            }
                        }
                    }
                }
                holder[0] = wv
                finishRef.set { ticket ->
                    if (done.compareAndSet(false, true)) {
                        mainHandler.removeCallbacks(timeout)
                        mainHandler.post { wv.destroy() }
                        if (cont.isActive) cont.resume(ticket)
                    }
                }
                mainHandler.post { wv.loadUrl(SSO_LOGIN_URL) }
                mainHandler.postDelayed(timeout, timeoutMs)
                cont.invokeOnCancellation {
                    mainHandler.post { holder[0]?.destroy() }
                }
            }
        }

    private companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        const val SSO_LOGIN_URL =
            "https://seat.lib.whu.edu.cn/rem/static/sso/login" +
                "?redirectUrl=https%3A%2F%2Fseat.lib.whu.edu.cn%2Fseat"
    }
}
