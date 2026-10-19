package cn.edu.whu.libseat.ui.login

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.edu.whu.libseat.LibrarySeatApp
import cn.edu.whu.libseat.data.local.DEFAULT_BASE_URL
import cn.edu.whu.libseat.ui.components.InfoBanner
import cn.edu.whu.libseat.ui.components.LoadingState
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * WebView 登录（对齐 Ham 的做法）：
 *
 * 不在原生界面里处理账号密码和验证码，而是打开座位系统自己的登录页，
 * 由用户在网页里输入读者证号/密码、手动完成图片验证码，或走统一身份认证。
 * App 只做两件事：
 *   1. 轮询页面 sessionStorage 里的 token（座位系统登录成功后写入）；
 *   2. 用 token 调一次 getUserInfo 校验会话，并拿到姓名/学号。
 *
 * 好处：不接触用户密码；验证码、SSO、滑块风控都由官方页面负责；
 * 登录页地址跟随设置里的服务器地址变化，不会被接口改版卡死。
 */
@Composable
fun WebLoginScreen(
    app: LibrarySeatApp,
    onBack: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    var baseUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val saved = runCatching { app.container.settingsStore.current().baseUrl }
            .getOrDefault(DEFAULT_BASE_URL)
        baseUrl = saved
            .ifBlank { DEFAULT_BASE_URL }
            .trimEnd('/')
            .removeSuffix("/jsq")
    }

    val url = baseUrl
    if (url == null) {
        Box(Modifier.fillMaxSize()) { LoadingState("正在准备登录页…") }
    } else {
        WebLoginContent(
            app = app,
            baseUrl = url,
            onBack = onBack,
            onLoggedIn = onLoggedIn,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebLoginContent(
    app: LibrarySeatApp,
    baseUrl: String,
    onBack: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnLoggedIn by rememberUpdatedState(onLoggedIn)

    var progress by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var handled by remember { mutableStateOf(false) }

    val loginUrl = "$baseUrl/seat/#/login"

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onLog(message: String) {
                cn.edu.whu.libseat.core.log.AppLog.i("WebLogin", message)
            }

            @JavascriptInterface
            fun onLogin(token: String, userInfo: String) {
                if (token.isBlank() || handled) return
                handled = true
                Handler(Looper.getMainLooper()).post {
                    scope.launch {
                        saving = true
                        error = null
                        try {
                            // 只做本地保存，保证登录成功后立刻返回；
                            // 姓名/学号可以稍后由首页的 getUserInfo 补齐。
                            val (parsedAccount, parsedName) = parseWebUser(userInfo)
                            app.container.repository.saveWebSession(token, parsedAccount, parsedName)
                            Toast.makeText(context, "登录成功", Toast.LENGTH_SHORT).show()
                            currentOnLoggedIn()
                        } catch (e: Exception) {
                            handled = false
                            error = e.message ?: "保存登录状态失败，请重试"
                        } finally {
                            saving = false
                        }
                    }
                }
            }
        }
    }

    val webView = remember {
        WebView(context).apply {
            setBackgroundColor(AndroidColor.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.setSupportZoom(true)
            // 座位系统前端是 PC 版（jsq-pc），桌面 UA 避免被导向不存在的移动页
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(bridge, "SeatBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(POLL_JS, null)
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    view?.evaluateJavascript(POLL_JS, null)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress = newProgress
                }
            }
            loadUrl(loginUrl)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        }
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("登录图书馆", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "座位系统官方登录页",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { webView.reload() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "重新加载")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            InfoBanner(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                icon = Icons.Rounded.Lock,
                text = "请在网页中完成登录：输入读者证号/密码并填写图片验证码，或点“统一身份认证”。" +
                    "推荐统一身份认证：登录一次后，掉线会自动静默续期，无需重复登录。App 不保存密码，只保留会话凭证。",
            )

            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            } else {
                Spacer(Modifier.height(2.dp))
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White),
            ) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize(),
                )

                if (saving) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.86f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(26.dp),
                                strokeWidth = 2.5.dp,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "正在保存登录状态…",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "登录成功后会自动返回",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { webView.loadUrl(loginUrl) }) {
                    Text("重新打开登录页")
                }
            }

            if (error != null) {
                Text(
                    error.orEmpty(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private const val POLL_JS = """
(function () {
  if (window.__seatBridgeInstalled) return;
  window.__seatBridgeInstalled = true;

  // 新版前端用 sessionStorageProxy 包了一层，真实 key 是 "jsq_p-token"；
  // 老版本/其他部署可能是 "token"，两种都要兼容。
  function readFrom(store, key) {
    try {
      var prefixed = store.getItem('jsq_p-' + key);
      if (prefixed) return prefixed;
      var direct = store.getItem(key);
      if (direct) return direct;
      for (var i = 0; i < store.length; i++) {
        var k = store.key(i);
        if (!k) continue;
        if (k === key || k === 'jsq_p-' + key ||
            k.indexOf('-' + key) === k.length - key.length - 1) {
          var v = store.getItem(k);
          if (v) return v;
        }
      }
    } catch (e) {}
    return '';
  }

  function read(key) {
    return readFrom(window.sessionStorage, key) || readFrom(window.localStorage, key);
  }

  function clean(value) {
    if (!value) return '';
    var v = String(value).trim();
    if (v.charAt(0) === '"' && v.charAt(v.length - 1) === '"') {
      try { v = JSON.parse(v); } catch (e) { v = v.slice(1, -1); }
    }
    if (v.indexOf('%7B') === 0) {
      try { v = decodeURIComponent(v); } catch (e) {}
    }
    return String(v).replace(/^Bearer\s+/i, '').trim();
  }

  // 注意：统一认证回跳时 URL 上的 ?token= 是 SSO 票据（待页面换取），
  // 不是 jsq 登录 token；绝不能直接拿来当会话凭证，否则后续接口全部失败。
  // 正确做法：等页面自己用票据换取后写入 sessionStorage 的 token。
  function tokenFromUrl() {
    try {
      var m = location.href.match(/[?&#]token=([^&]+)/);
      if (m) return decodeURIComponent(m[1]);
    } catch (e) {}
    return '';
  }

  function check() {
    var token = clean(read('token'));
    if (!token) {
      if (tokenFromUrl() && !window.__seatCapWaitLogged) {
        window.__seatCapWaitLogged = true;
        try { window.SeatBridge.onLog('统一认证回跳成功，等待页面换取登录态'); } catch (e) {}
      }
      return;
    }
    if (window.__seatBridgeTimer) {
      clearInterval(window.__seatBridgeTimer);
      window.__seatBridgeTimer = null;
    }
    try {
      window.SeatBridge.onLogin(token, clean(read('userInfo')));
    } catch (e) {}
  }

  check();
  window.__seatBridgeTimer = setInterval(check, 600);
})();
"""

private fun parseWebUser(raw: String): Pair<String, String> {
    if (raw.isBlank()) return "" to ""
    val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return "" to ""
    fun pick(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
    val account = pick("username", "userName", "account", "loginName", "readerNo").orEmpty()
    val name = pick("fullName", "name", "realName", "readerName").orEmpty()
    return account to name
}
