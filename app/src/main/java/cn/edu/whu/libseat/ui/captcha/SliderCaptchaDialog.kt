package cn.edu.whu.libseat.ui.captcha

import android.annotation.SuppressLint
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.edu.whu.libseat.core.log.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 原生卡片 + 官方滑块验证组件（最小验证页，不加载整站 UI）。
 *
 * 通信方式：原生侧轮询 WebView 内部的全局状态（__seatCapReady/Token/Err/Log），
 * 不依赖 addJavascriptInterface 回调，避免 JS↔Native 通道在部分机型上失效。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SliderCaptchaDialog(
    state: CaptchaState,
    onToken: (String) -> Unit,
    onCancel: () -> Unit,
) {
    if (!state.visible) return
    val base = state.baseUrl.trim().ifBlank { "https://seat.lib.whu.edu.cn" }
        .let { if (it.startsWith("http")) it else "https://$it" }
        .trimEnd('/')
        .removeSuffix("/jsq")
        .removeSuffix("/seat")
    var ready by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    val cancelled = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            cancelled.set(true)
            webViewHolder[0]?.destroy()
        }
    }

    LaunchedEffect(state.visible) {
        if (!state.visible) return@LaunchedEffect
        AppLog.i("Captcha", "开始轮询验证码状态 base=$base")
        while (true) {
            delay(600)
            if (cancelled.get()) return@LaunchedEffect
            val view = webViewHolder[0] ?: continue
            val snap = readState(view) ?: continue
            snap.logs.forEach { AppLog.i("CaptchaJS", it) }
            if (snap.ready && !ready) {
                ready = true
                AppLog.i("Captcha", "滑块组件已就绪")
            }
            if (!ready && snap.btn) {
                ready = true
                AppLog.i("Captcha", "滑块按钮已出现（视为就绪）")
            }
            snap.err?.let { msg ->
                if (failure == null) {
                    AppLog.e("Captcha", "JS 报告：$msg")
                    failure = msg
                }
            }
            snap.token?.let { token ->
                AppLog.i("Captcha", "拿到 capToken，长度=${token.length}")
                onToken(token)
                return@LaunchedEffect
            }
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "安全验证",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "拖动滑块完成拼图后继续预约",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.hint?.let { hint ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Spacer(Modifier.height(12.dp))

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(330.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            WebView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.builtInZoomControls = false
                                settings.displayZoomControls = false
                                settings.useWideViewPort = false
                                settings.loadWithOverviewMode = false
                                settings.setSupportZoom(false)
                                // 站点按 UA 区分桌面/移动构建：桌面版的 CSS 才包含 TAC 样式且真实存在
                                settings.userAgentString =
                                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                                        "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                                setBackgroundColor(android.graphics.Color.WHITE)
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(
                                        view: WebView,
                                        url: String,
                                        favicon: android.graphics.Bitmap?,
                                    ) {
                                        AppLog.i("Captcha", "页面开始加载 $url")
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        AppLog.i("Captcha", "页面加载完成 $url")
                                    }

                                    override fun onReceivedError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        error: WebResourceError,
                                    ) {
                                        AppLog.e("Captcha", "资源错误 ${request.url} ${error.description}")
                                        if (request.isForMainFrame) {
                                            failure = "页面加载失败：${error.description}"
                                        }
                                    }

                                    override fun onReceivedHttpError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        response: WebResourceResponse,
                                    ) {
                                        AppLog.w("Captcha", "HTTP ${response.statusCode} ${request.url}")
                                        if (request.isForMainFrame) {
                                            failure = "页面加载失败：HTTP ${response.statusCode}"
                                        }
                                    }

                                    override fun onReceivedSslError(
                                        view: WebView,
                                        handler: SslErrorHandler,
                                        error: SslError,
                                    ) {
                                        AppLog.e("Captcha", "SSL 错误 ${error.primaryError}")
                                        handler.cancel()
                                        failure = "证书校验失败，请检查网络环境"
                                    }
                                }
                                AppLog.i("Captcha", "WebView 创建，开始加载最小验证页")
                                loadDataWithBaseURL(
                                    "$base/seat/",
                                    buildCaptchaHtml(state.username, base),
                                    "text/html",
                                    "UTF-8",
                                    null,
                                )
                                webViewHolder[0] = this
                            }
                        },
                    )
                    if (!ready && failure == null) {
                        CircularProgressIndicator(Modifier.size(26.dp))
                    }
                    failure?.let { msg ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(16.dp),
                        ) {
                            Text(
                                msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(
                                onClick = {
                                    failure = null
                                    ready = false
                                    webViewHolder[0]?.reload()
                                },
                            ) {
                                Icon(
                                    Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("重试")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "验证服务由图书馆座位系统提供",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            webViewHolder[0]?.evaluateJavascript(
                                "(function(){try{window.__seatTac&&window.__seatTac.reloadCaptcha&&window.__seatTac.reloadCaptcha()}catch(e){}})()",
                                null,
                            )
                        },
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text("换一张", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private data class CaptchaSnapshot(
    val ready: Boolean,
    val btn: Boolean,
    val token: String?,
    val err: String?,
    val logs: List<String>,
)

private suspend fun readState(view: WebView): CaptchaSnapshot? = try {
    val raw = view.evalJs(STATE_SCRIPT)
    val inner = JSONTokener(raw).nextValue() as? String
        ?: throw IllegalStateException("状态返回格式异常")
    val obj = JSONObject(inner)
    val logs = mutableListOf<String>()
    obj.optJSONArray("log")?.let { arr ->
        for (i in 0 until arr.length()) logs.add(arr.optString(i))
    }
    // 注意：JSON null 会被 optString 读成字符串 "null"，必须用 isNull 判断
    fun str(key: String): String? =
        if (obj.isNull(key)) null else obj.optString(key, "").takeIf { it.isNotEmpty() }
    CaptchaSnapshot(
        ready = obj.optBoolean("ready", false),
        btn = obj.optBoolean("btn", false),
        token = str("token"),
        err = str("err"),
        logs = logs,
    )
} catch (e: Exception) {
    AppLog.w("Captcha", "状态读取失败：${e.message}")
    null
}

private suspend fun WebView.evalJs(script: String): String =
    suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { result -> cont.resume(result) }
    }

private val STATE_SCRIPT = """
    (function(){
      var l = window.__seatCapLog || [];
      window.__seatCapLog = [];
      var err = window.__seatCapErr || null;
      window.__seatCapErr = null;
      var token = window.__seatCapToken || null;
      window.__seatCapToken = null;
      return JSON.stringify({
        ready: !!window.__seatCapReady,
        token: token,
        err: err,
        log: l,
        hasTac: !!window.__seatTac,
        btn: !!document.querySelector('#tianai-captcha-slider-move-btn')
      });
    })()
""".trimIndent()

private fun buildCaptchaHtml(username: String, apiBase: String): String {
    val user = JSONObject.quote(username)
    val api = JSONObject.quote("$apiBase/jsq")
    return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=340, initial-scale=1, maximum-scale=1, user-scalable=no">
          <style>${TAC_WIDGET_CSS}
          
            html, body {
              margin: 0; padding: 0; width: 100%; height: 100%;
              background: #ffffff; overflow: hidden;
              display: flex; align-items: center; justify-content: center;
            }
            #seat-cap { width: 318px; min-height: 270px; position: relative; }
          </style>
        </head>
        <body>
          <div id="seat-cap"></div>
          <script>
            (function(){
              window.__seatCapLog = [];
              window.__seatCapLog0 = function(m){ try { window.__seatCapLog.push('' + m); } catch(e) {} };
              window.onerror = function(message){
                try { window.__seatCapErr = '验证组件异常：' + message; } catch(e) {}
                try { window.__seatCapLog0('JS 异常：' + message); } catch(e) {}
              };
              var of = window.fetch;
              if (of) {
                window.fetch = function(u, o){
                  return of.apply(this, arguments).then(function(r){
                    try { if ((''+u).indexOf('/cap/') >= 0) window.__seatCapLog0('fetch ' + u + ' -> ' + r.status); } catch(e) {}
                    return r;
                  }).catch(function(err){
                    try { if ((''+u).indexOf('/cap/') >= 0) window.__seatCapLog0('fetch ' + u + ' 网络失败'); } catch(e) {}
                    throw err;
                  });
                };
              }
              var ox = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(m, u){ this.__u = u; return ox.apply(this, arguments); };
              var os = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.send = function(){
                var x = this;
                x.addEventListener('load', function(){ try { if ((''+x.__u).indexOf('/cap/') >= 0) window.__seatCapLog0('xhr ' + x.__u + ' -> ' + x.status); } catch(e) {} });
                x.addEventListener('error', function(){ try { window.__seatCapLog0('xhr ' + x.__u + ' 网络失败'); } catch(e) {} });
                return os.apply(this, arguments);
              };
            })();
          </script>
          <script src="static/js/crypto-js.min.js"
                  onload="try{window.__seatCapLog0('crypto-js ok')}catch(e){}"
                  onerror="try{window.__seatCapErr='crypto-js.min.js 加载失败'}catch(e){}"></script>
          <script src="static/js/jsencrypt.js"
                  onload="try{window.__seatCapLog0('jsencrypt ok')}catch(e){}"
                  onerror="try{window.__seatCapErr='jsencrypt.js 加载失败'}catch(e){}"></script>
          <script src="static/js/tac.min.js"
                  onload="try{window.__seatCapLog0('tac ok')}catch(e){}"
                  onerror="try{window.__seatCapErr='tac.min.js 加载失败'}catch(e){}"></script>
          <script>
            (function(){
              window.__bootCaptcha = function(){
                try {
                  if (typeof TAC === 'undefined') {
                    window.__seatCapErr = 'TAC 脚本未加载';
                    window.__seatCapLog0('TAC 脚本未加载');
                    return;
                  }
                  TAC.enc.rsaPublicKey = 'MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDXRMEk7baUetStHq6IPIxwKB9ga9UyCepDEEFIZUS5cc1/FyS90Tbd1VA4j+AfqurclfBHUWgvuzAj4oW5b/sdS1SC14259tLexFbT5EfPsyY0BPfMXkzUerSbzgL8ZIUtHfHV1z6/WA6iHVmB1SpWT2BwaE9Alednp9EO8dLQvQIDAQAB';
                  var cfg = {
                    bindEl: '#seat-cap',
                    requestCaptchaDataUrl: $api + '/static/cap/cg/gen/SLIDER',
                    validCaptchaUrl: $api + '/static/cap/cg/check',
                    customData: { username: $user },
                    validSuccess: function(res){
                      try { window.__seatCapToken = res.data.token; } catch(e) {}
                    },
                    validFail: function(t, e, tac){
                      try { tac.reloadCaptcha(); } catch(err) {}
                      try { window.__seatCapErr = '验证失败，已自动刷新'; } catch(e) {}
                    },
                    btnRefreshFun: function(){},
                    btnCloseFun: function(t, tac){
                      try { tac.destroyWindow(); } catch(e) {}
                      try { window.__seatCapErr = '用户关闭了验证组件'; } catch(e) {}
                    }
                  };
                  window.__seatTac = new TAC(cfg, {});
                  window.__seatTac.init();
                  window.__seatCapLog0('TAC init ok');
                  setTimeout(function(){
                    window.__seatCapReady = true;
                    window.__seatCapLog0('ready');
                  }, 60);
                } catch (e) {
                  try { window.__seatCapErr = '验证组件初始化失败：' + e.message; } catch(err) {}
                }
              };
              setTimeout(function(){
                window.__seatCapLog0('typeof TAC=' + (typeof TAC) + ' CryptoJS=' + (typeof CryptoJS) + ' JSEncrypt=' + (typeof JSEncrypt));
              }, 500);
              setTimeout(function(){
                if (!window.__seatTac) {
                  var msg = 'TAC 初始化超时 tac=' + (typeof TAC) + ' crypto=' + (typeof CryptoJS) + ' jse=' + (typeof JSEncrypt);
                  try { window.__seatCapErr = msg; } catch(e) {}
                  try { window.__seatCapLog0(msg); } catch(e) {}
                }
              }, 10000);
              try { window.__bootCaptcha(); } catch(e) { window.__seatCapErr = '验证组件启动失败：' + e.message; }
            })();
          </script>
        </body>
        </html>
    """.trimIndent()
}
