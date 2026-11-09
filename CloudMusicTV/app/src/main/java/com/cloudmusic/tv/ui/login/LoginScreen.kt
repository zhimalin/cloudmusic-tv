package com.cloudmusic.tv.ui.login

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.cloudmusic.tv.BuildConfig
import com.cloudmusic.tv.ui.components.TvButton
import com.cloudmusic.tv.ui.theme.CloudMusicColors
import kotlinx.coroutines.delay

private const val TAG = "LoginScreen"

private const val LOGIN_URL = "https://music.163.com/login"
private const val COOKIE_URL = "https://music.163.com"
private const val MUSIC_U = "MUSIC_U="

/** Spec: the QR code is considered expired after 5 minutes. */
private const val LOGIN_TIMEOUT_MS = 5L * 60L * 1000L

/** Spec: poll the cookie jar every 500ms. */
private const val POLL_INTERVAL_MS = 500L

/** 首次进入登录页的加载遮罩兜底时长。 */
private const val LOADING_OVERLAY_FALLBACK_MS = 20_000L

/**
 * 登录卡片注入重试时间点（从 onPageFinished 起算的绝对毫秒）。
 * 只跑 4 次，不做长轮询。
 */
private val INJECT_RETRY_DELAYS_MS = longArrayOf(300L, 800L, 1500L, 2500L)

/**
 * 桌面站只保留网易云登录卡片。
 *
 * 这段脚本已在 Chromium 119 的 Chrome probe 中验证；不要改选择器/尺寸，
 * 除非重新在真机 WebView 上截图确认。
 */
private const val LOGIN_INJECT_JS = """(() => {
  document.getElementById('g-topbar')?.remove();
  document.querySelector('.g-btmbar')?.remove();
  document.documentElement.style.background='#0E0D11';
  document.body.style.background='#0E0D11';
  const f=document.getElementById('g_iframe');
  if(!f) return 'NO_IFRAME';
  f.style.cssText='position:fixed;left:0;top:0;width:100%;height:100%;border:0;background:#0E0D11';
  let d; try{d=f.contentDocument;}catch(e){return 'XORIGIN';}
  if(!d) return 'NO_DOC';
  ['g_top','g_nav'].forEach(id=>{const el=d.getElementById(id); if(el) el.style.display='none';});
  const foot=d.querySelector('.g-ft'); if(foot) foot.style.display='none';
  const back=d.getElementById('g_backtop'); if(back) back.style.display='none';
  d.documentElement.style.background='#0E0D11';
  d.body.style.background='#0E0D11';
  const w=d.getElementById('login-wrapper');
  if(!w) return 'NO_WRAPPER';
  const vw = window.innerWidth, vh = window.innerHeight;
  const cardW = Math.min(520, Math.round(vw * 0.66));
  w.style.cssText += ';position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);margin:0;width:' + cardW + 'px;height:auto;max-height:' + Math.round(vh * 0.92) + 'px;background:#fff;border-radius:20px;padding:16px 20px;box-shadow:0 20px 60px rgba(0,0,0,.6);';
  const cv = d.querySelector('canvas');
  let qrState = 'no-canvas';
  if (cv) {
    try {
      const ctx = cv.getContext('2d');
      const px = ctx.getImageData(0, 0, 1, 1).data;
      qrState = 'canvas:' + cv.width + 'x' + cv.height + ':' + px.join(',');
    } catch (e) { qrState = 'canvas-err:' + e.message; }
  }
  return 'OK|' + qrState;
})()"""

/**
 * music.163.com/login serves a mobile layout (app-download / SMS login) when it
 * sees the default WebView UA, which carries `; wv` and a mobile form factor.
 * A plain desktop Chrome UA is what actually gets the QR-code login panel.
 *
 * Hard-coded fixed string on purpose — never derive it from
 * `WebSettings.getDefaultUserAgent()`, that reintroduces the mobile token.
 */
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Safari/537.36"

/**
 * Everything that can go wrong before the user is logged in.
 *
 * [code] is surfaced on screen so a bug report from a TV can be acted on
 * without a logcat capture.
 */
data class WebViewFailure(val code: String, val detail: String)

/**
 * Full-screen QR login.
 *
 * Flow: load https://music.163.com/login in a WebView, let 网易云 render its own
 * QR code, poll the cookie jar until `MUSIC_U` shows up, hand the cookie to the
 * caller. No login protocol is reimplemented here.
 *
 * Failure handling (this is the "WebView 不可用兜底" requirement):
 *  1. `WebView.getCurrentWebViewPackage()` returns null  -> WEBVIEW_INIT_FAILED
 *  2. the provider package is disabled in system settings -> WEBVIEW_INIT_FAILED
 *  3. `WebView(context)` throws (broken provider, missing lib) -> WEBVIEW_INIT_FAILED
 *  4. main-frame load error (offline / DNS / TLS)          -> PAGE_LOAD_FAILED(n)
 *  5. renderer process died                                -> RENDER_PROCESS_GONE
 * All five render a full-screen error with the code + a focusable retry button.
 * The screen never goes blank and the process never crashes on a missing WebView.
 *
 * Two additional requirements this screen owns:
 *  - the Activity window keeps `FLAG_KEEP_SCREEN_ON` while this screen is
 *    composed (a blank panel in the middle of a 5-minute QR wait forces the user
 *    to start over), restored to its previous state on dispose;
 *  - the WebView loads with a fixed desktop Chrome User-Agent, because the site
 *    serves a mobile layout without a QR code to the default `; wv` UA.
 */
@Composable
fun LoginScreen(
    onLoggedIn: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var initAttempt by remember { mutableIntStateOf(0) }
    var pollKey by remember { mutableIntStateOf(0) }
    var expired by remember { mutableStateOf(false) }
    // 只允许第一次进入时显示加载遮罩；onPageFinished 第一次触发后永久关闭。
    var overlayVisible by remember { mutableStateOf(true) }
    var overlayDismissed by remember { mutableStateOf(false) }
    var loadFailure by remember { mutableStateOf<WebViewFailure?>(null) }

    // Creating the WebView during composition is fine here: `initAttempt` only
    // changes on retry, so it happens once per attempt and never on recomposition.
    val creation = remember(initAttempt) {
        runCatching {
            createLoginWebView(
                context = context,
                onPageStarted = {
                    // SPA 路由切换会再次触发 onPageStarted；首帧结束后不再重新盖遮罩。
                    if (!overlayDismissed) overlayVisible = true
                },
                onPageFinished = {
                    if (!overlayDismissed) {
                        overlayDismissed = true
                        overlayVisible = false
                    }
                },
                onMainFrameError = { errorCode, description ->
                    loadFailure = WebViewFailure(
                        code = "PAGE_LOAD_FAILED($errorCode)",
                        detail = description,
                    )
                },
                onRenderProcessGone = {
                    loadFailure = WebViewFailure(
                        code = "RENDER_PROCESS_GONE",
                        detail = "WebView 渲染进程已退出",
                    )
                },
            )
        }
    }

    val handle = creation.getOrNull()
    val webView = handle?.webView
    val initFailure = creation.exceptionOrNull()?.let { throwable ->
        WebViewFailure(
            code = "WEBVIEW_INIT_FAILED",
            detail = throwable.message ?: throwable.javaClass.name,
        )
    }
    val failure = loadFailure ?: initFailure

    // --- 登录等待期间保持屏幕常亮 -------------------------------------------
    // 电视的休眠策略比手机激进得多：5 分钟等扫码期间一旦黑屏，用户必须重新
    // 扫码。这是登录方案的成败项，不是体验优化。
    //
    // 成对 add/clear 放在同一个 DisposableEffect 里（不用 onResume/onPause），
    // 离开登录页就一定清掉，不会漏到别的页面。
    // 清之前先记住「进来之前这个 flag 是否已置位」，只有原本没置位才清；
    // 以后播放页也要用这个 flag 时不会被这里踩掉。
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(activity) {
        val window = activity?.window
        if (window == null) {
            Log.w(TAG, "找不到 Activity window，KEEP_SCREEN_ON 未生效")
            onDispose { }
        } else {
            val keepOn = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            val alreadySet = (window.attributes.flags and keepOn) != 0
            window.addFlags(keepOn)
            Log.i(TAG, "KEEP_SCREEN_ON added (alreadySetBeforeEnter=$alreadySet)")
            onDispose {
                if (alreadySet) {
                    Log.i(TAG, "KEEP_SCREEN_ON left set (was already set before login)")
                } else {
                    window.clearFlags(keepOn)
                    Log.i(TAG, "KEEP_SCREEN_ON cleared")
                }
            }
        }
    }

    DisposableEffect(handle) {
        onDispose {
            handle?.injector?.cancel()
            handle?.webView?.let(::destroyLoginWebView)
        }
    }

    // 兜底：进入登录页 20 秒后，无论页面/WebView 处于什么状态都撤掉遮罩。
    LaunchedEffect(Unit) {
        delay(LOADING_OVERLAY_FALLBACK_MS)
        if (!overlayDismissed) {
            overlayDismissed = true
            overlayVisible = false
            Log.w(TAG, "loading overlay auto-hidden after ${LOADING_OVERLAY_FALLBACK_MS}ms")
        }
    }

    // 500ms cookie polling. Restarts on retry (pollKey) and when the WebView is recreated.
    LaunchedEffect(pollKey, webView) {
        if (webView == null) return@LaunchedEffect
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            val cookie = readSessionCookie()
            if (cookie != null) {
                onLoggedIn(cookie)
                return@LaunchedEffect
            }
            if (SystemClock.elapsedRealtime() - startedAt >= LOGIN_TIMEOUT_MS) {
                Log.i(TAG, "QR login timed out after ${LOGIN_TIMEOUT_MS}ms")
                expired = true
                return@LaunchedEffect
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    val retry: () -> Unit = {
        loadFailure = null
        expired = false
        val current = webView
        if (current == null) {
            // WebView could not be constructed at all -> build a brand new one.
            initAttempt++
        } else {
            current.loadUrl(LOGIN_URL)
        }
        pollKey++
    }

    // Login page: back exits the app. Deliberately no BackHandler here, so the
    // Activity's default back behaviour (finish) applies.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CloudMusicColors.Background),
    ) {
        when {
            failure != null -> LoginFailureScreen(failure = failure, onRetry = retry)

            expired -> LoginExpiredScreen(onRetry = retry)

            webView != null -> {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize(),
                )
                if (overlayVisible) {
                    LoadingOverlay()
                }
            }
        }
    }
}

@Composable
private fun LoadingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CloudMusicColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "正在打开登录页…",
                style = MaterialTheme.typography.titleMedium,
                color = CloudMusicColors.NavUnselectedText,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "首次可能需要几秒",
                style = MaterialTheme.typography.bodySmall,
                color = CloudMusicColors.SectionHeader,
            )
        }
    }
}

@Composable
private fun LoginFailureScreen(failure: WebViewFailure, onRetry: () -> Unit) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
            .onFailure { Log.w(TAG, "登录失败页重试按钮焦点请求失败", it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(64.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "登录页加载失败",
            style = MaterialTheme.typography.headlineMedium,
            color = CloudMusicColors.OnDark,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "错误码：${failure.code}",
            style = MaterialTheme.typography.titleMedium,
            color = CloudMusicColors.NavAccent,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "详情：${failure.detail}",
            style = MaterialTheme.typography.bodyMedium,
            color = CloudMusicColors.SectionHeader,
        )
        Spacer(modifier = Modifier.height(36.dp))
        TvButton(
            label = "重试",
            onClick = onRetry,
            focusRequester = requester,
        )
    }
}

@Composable
private fun LoginExpiredScreen(onRetry: () -> Unit) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
            .onFailure { Log.w(TAG, "二维码过期页重试按钮焦点请求失败", it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(64.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "二维码已过期",
            style = MaterialTheme.typography.headlineMedium,
            color = CloudMusicColors.OnDark,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "扫码登录超时（5 分钟），请重新加载二维码。",
            style = MaterialTheme.typography.bodyLarge,
            color = CloudMusicColors.NavUnselectedText,
        )
        Spacer(modifier = Modifier.height(36.dp))
        TvButton(
            label = "重试",
            onClick = onRetry,
            focusRequester = requester,
        )
    }
}

/**
 * Builds the login WebView. Throws on any "WebView is not usable" condition so
 * the caller can turn it into a [WebViewFailure] screen.
 */
private fun createLoginWebView(
    context: Context,
    onPageStarted: () -> Unit,
    onPageFinished: () -> Unit,
    onMainFrameError: (Int, String) -> Unit,
    onRenderProcessGone: () -> Unit,
): LoginWebHandle {
    // Trigger 1: no WebView provider at all (AOSP build without WebView, broken ROM).
    val provider = WebView.getCurrentWebViewPackage()
        ?: throw IllegalStateException(
            "系统没有可用的 WebView 实现（WebView.getCurrentWebViewPackage() 返回 null）",
        )

    // Trigger 2: WebView installed but disabled in system settings.
    if (provider.applicationInfo?.enabled == false) {
        throw IllegalStateException("WebView 实现 ${provider.packageName} 已被系统禁用")
    }
    Log.i(TAG, "WebView provider = ${provider.packageName} ${provider.versionName}")

    // Debug-only CDP channel: allows `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`
    // and chrome://inspect. In release this branch is dead because BuildConfig.DEBUG is false.
    if (BuildConfig.DEBUG) {
        WebView.setWebContentsDebuggingEnabled(true)
        Log.i(TAG, "WebView remote debugging enabled (debug build)")
    }

    // Trigger 3: provider present but construction fails (loader error, missing lib).
    val webView = WebView(context)
    // 和页面根底色一致，避免加载期间/首帧前白屏闪一下。
    webView.setBackgroundColor(0xFF0E0D11.toInt())
    webView.overScrollMode = View.OVER_SCROLL_NEVER

    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    // 网易云's login flow touches third-party domains; without this the QR
    // session cookie is never persisted and polling can never succeed.
    cookieManager.setAcceptThirdPartyCookies(webView, true)

    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        loadWithOverviewMode = true
        useWideViewPort = true
        mediaPlaybackRequiresUserGesture = true
        cacheMode = WebSettings.LOAD_DEFAULT
        // Must be set before loadUrl(), otherwise the very first request still
        // goes out with the default mobile (and `; wv`) UA.
        userAgentString = DESKTOP_USER_AGENT
    }

    val injector = LoginPageInjector(webView)
    webView.webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            onPageStarted()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            logPageState(view, url)
            onPageFinished()
            // 页面完成后开始 4 次注入尝试；SPA 再次 onPageFinished 会重启这一轮。
            injector.start()
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean = false // keep every navigation inside this WebView

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            // Trigger 4: only main-frame failures break login; sub-resource
            // failures are ignored on purpose.
            if (request.isForMainFrame) {
                onMainFrameError(error.errorCode, error.description?.toString() ?: "unknown")
            }
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            // Trigger 5: renderer crashed / was killed. Returning true tells the
            // framework we handled it, so the app process is not killed with it.
            Log.w(TAG, "WebView renderer gone, didCrash=${detail.didCrash()}")
            onRenderProcessGone()
            return true
        }
    }

    webView.loadUrl(LOGIN_URL)
    return LoginWebHandle(webView = webView, injector = injector)
}

/** WebView 和它的注入调度器必须一起销毁，避免 destroy 后 Handler 还在回调。 */
private class LoginWebHandle(
    val webView: WebView,
    val injector: LoginPageInjector,
)

/**
 * 登录卡片注入调度器。
 *
 * 从 onPageFinished 开始，在 300 / 800 / 1500 / 2500ms 四个时间点各注入一次；
 * 只有 JS 返回 `OK|canvas:WxH:r,g,b,a` 且采样像素不是空白（alpha>0 且非近白）
 * 才停止。二维码 canvas 还没画好时会继续重试。每轮只有 4 次，不做长轮询。
 */
private class LoginPageInjector(private val webView: WebView) {

    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var released = false

    fun start() {
        if (released) return
        generation += 1
        handler.removeCallbacksAndMessages(null)
        scheduleAttempt(generation, attempt = 0)
    }

    fun cancel() {
        released = true
        generation += 1
        handler.removeCallbacksAndMessages(null)
    }

    private fun scheduleAttempt(gen: Int, attempt: Int) {
        if (released || gen != generation || attempt !in INJECT_RETRY_DELAYS_MS.indices) return

        val previousDelay = if (attempt == 0) 0L else INJECT_RETRY_DELAYS_MS[attempt - 1]
        val delayMs = (INJECT_RETRY_DELAYS_MS[attempt] - previousDelay).coerceAtLeast(0L)
        handler.postDelayed({ evaluate(gen, attempt) }, delayMs)
    }

    private fun evaluate(gen: Int, attempt: Int) {
        if (released || gen != generation) return

        try {
            webView.evaluateJavascript(LOGIN_INJECT_JS) { rawResult ->
                if (released || gen != generation) return@evaluateJavascript

                Log.i(TAG, "inject -> $rawResult")
                if (isInjectionOk(rawResult)) {
                    Log.i(TAG, "inject verified QR canvas after ${attempt + 1} attempts")
                    return@evaluateJavascript
                }

                if (attempt == INJECT_RETRY_DELAYS_MS.lastIndex) {
                    Log.w(TAG, "inject exhausted after ${INJECT_RETRY_DELAYS_MS.size} attempts")
                } else {
                    scheduleAttempt(gen, attempt + 1)
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "inject evaluate failed at attempt ${attempt + 1}", error)
            if (attempt < INJECT_RETRY_DELAYS_MS.lastIndex) {
                scheduleAttempt(gen, attempt + 1)
            }
        }
    }

    /**
     * 成功必须同时满足：
     * 1. JS 返回 `OK|canvas:WxH:r,g,b,a`；
     * 2. canvas 尺寸 > 0；
     * 3. 首个采样像素 alpha > 0 且不是近白（白/透明 = 二维码还没画出来）。
     *
     * evaluateJavascript 返回的是 JSON 字符串，所以成功值外面可能带引号。
     */
    private fun isInjectionOk(rawResult: String?): Boolean {
        val value = rawResult?.trim()?.removeSurrounding("\"") ?: return false
        if (!value.startsWith("OK|canvas:")) return false

        val payload = value.removePrefix("OK|canvas:")
        val sizeAndPixel = payload.split(':', limit = 2)
        if (sizeAndPixel.size != 2) return false

        val dimensions = sizeAndPixel[0].split('x')
        if (dimensions.size != 2) return false
        val width = dimensions[0].toIntOrNull() ?: return false
        val height = dimensions[1].toIntOrNull() ?: return false
        if (width <= 0 || height <= 0) return false

        val rgba = sizeAndPixel[1].split(',').map { it.trim().toIntOrNull() ?: return false }
        if (rgba.size != 4) return false
        val red = rgba[0]
        val green = rgba[1]
        val blue = rgba[2]
        val alpha = rgba[3]
        if (alpha <= 0) return false

        return !(red >= 250 && green >= 250 && blue >= 250)
    }
}

/**
 * Reads the cookie jar. Returns the raw cookie header only when `MUSIC_U` is
 * present, which is the login signal per spec.
 */
private fun readSessionCookie(): String? = try {
    val cookies = CookieManager.getInstance().getCookie(COOKIE_URL)
    if (cookies != null && cookies.contains(MUSIC_U)) cookies else null
} catch (throwable: Throwable) {
    Log.w(TAG, "reading cookie jar failed", throwable)
    null
}

private fun destroyLoginWebView(webView: WebView) {
    try {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.removeAllViews()
        webView.destroy()
        Log.i(TAG, "WebView destroyed")
    } catch (throwable: Throwable) {
        Log.w(TAG, "destroying WebView failed", throwable)
    }
}

/**
 * Logs what actually rendered.
 *
 * Deliberately kept in the shipped build: on a TV the only way to tell
 * "this is the QR login page" from "redirected to the app-download page" or
 * "desktop layout squeezed off-screen" without a debugger is logcat.
 *
 * `viewport` is not part of the original requirement — it is logged because the
 * desktop UA + TV density combination is the next thing to check if the QR code
 * still does not appear.
 */
private fun logPageState(webView: WebView?, url: String?) {
    if (webView == null) return
    Log.i(TAG, "onPageFinished url=$url")
    runCatching {
        webView.evaluateJavascript("location.href") { href ->
            Log.i(TAG, "  location.href=$href")
        }
        webView.evaluateJavascript("document.title") { title ->
            Log.i(TAG, "  document.title=$title")
        }
        webView.evaluateJavascript(
            "window.innerWidth + 'x' + window.innerHeight + ' dpr=' + window.devicePixelRatio",
        ) { viewport ->
            Log.i(TAG, "  viewport=$viewport")
        }
    }.onFailure { Log.w(TAG, "page state probe failed", it) }
}

/** Unwraps the Activity out of a (possibly wrapped) Compose [Context]. */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
