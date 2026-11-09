package com.cloudmusic.tv

import android.app.Activity
import android.content.Context
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.cloudmusic.core.player.PlaybackRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowWebView
import java.time.Duration

/**
 * JVM 上的「冷启动不会崩」验证：真正把 [MainActivity] 启动到 RESUMED，并推进到首帧。
 *
 * 这个工程从未在真机/模拟器上跑过（目标电视锁了 adb 安装），所以本测试只回答一个问题：
 * **`onCreate` -> `setContent` -> 首次组合 -> 首次 measure/layout/draw` 这条路径会不会抛异常。**
 *
 * 三个场景：
 *  1. 首装（SharedPreferences 里没有 cookie，且没有 WebView provider）-> LOGIN 分支的
 *     `WEBVIEW_INIT_FAILED` 兜底屏；
 *  2. 已登录（预写一个假 `MUSIC_U` cookie）-> MAIN 分支（顶栏 + 首页）；
 *  3. 首装 + 假 WebView provider -> LOGIN 分支的 `AndroidView` + 登录 WebView 配置路径。
 *
 * 注意这里**不是**真机渲染：Robolectric 不跑 GPU，WebView 是 shadow。
 * 详见测试报告。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityLaunchTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetSession() {
        // 与 SessionStore 使用同一个 prefs 名；每个测试都从「首装」状态开始。
        context.getSharedPreferences("cloudmusic_session", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        // 场景 3 会装一个假 WebView provider；显式清掉，保证场景 1 的测试顺序无关。
        ShadowWebView.setCurrentWebViewPackage(null)
    }

    @After
    fun releasePlayer() {
        // PlaybackRuntime 是进程内单例，Robolectric 同一个 sandbox 里跨测试复用静态状态；
        // 每个测试结束时释放，避免第二个测试拿到上一个测试的 ExoPlayer/looper。
        PlaybackRuntime.release()
    }

    // ------------------------------------------------------------------ 场景 1：首装

    @Test
    fun `cold start without cookie reaches RESUMED and renders the login screen`() {
        val instancesBefore = PlaybackRuntime.playerInstanceCount()
        val activity = launchActivity()

        assertLaidOut(activity, label = "LOGIN")

        // 登录页绝不能创建 ExoPlayer：真机上这发生在首帧前，会白白拉起 media3 渲染管线。
        assertEquals(
            "LOGIN 首帧不应创建 ExoPlayer 实例",
            instancesBefore,
            PlaybackRuntime.playerInstanceCount(),
        )
        assertFalse("LOGIN 首帧不应存在 ExoPlayer 实例", PlaybackRuntime.isCreated())

        val composeView = requireComposeView(activity)
        val texts = collectTexts(composeView)
        println("[launch-test][LOGIN] semantics text = $texts")

        assertTrue("login screen rendered no text at all: $texts", texts.isNotEmpty())
        assertTrue(
            "expected a login-screen marker (登录页加载失败 / 正在打开登录页), got $texts",
            texts.any { it.contains("登录") },
        )

        // LoginScreen 的 DisposableEffect 会给窗口加 FLAG_KEEP_SCREEN_ON；
        // 这个 flag 只可能在登录页被组合出来时出现。
        val keepScreenOn =
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        assertTrue("LoginScreen's KEEP_SCREEN_ON effect never ran", keepScreenOn != 0)
    }

    // ------------------------------------------------------------------ 场景 2：已登录

    @Test
    fun `cold start with a stored cookie reaches RESUMED and renders the main screen`() {
        context.getSharedPreferences("cloudmusic_session", Context.MODE_PRIVATE)
            .edit()
            .putString("login_cookie", "MUSIC_U=FAKE_TEST_COOKIE_0001; __csrf=fake")
            .putString("account_hint", "MUSIC_U ****0001")
            .commit()

        val instancesBefore = PlaybackRuntime.playerInstanceCount()
        val activity = launchActivity()

        assertLaidOut(activity, label = "MAIN")

        // 已登录但没有播放动作（没有点歌/没有恢复播放）时同样不应该创建 ExoPlayer。
        assertEquals(
            "MAIN 无播放动作时不应创建 ExoPlayer 实例",
            instancesBefore,
            PlaybackRuntime.playerInstanceCount(),
        )
        assertFalse("MAIN 无播放动作时不应存在 ExoPlayer 实例", PlaybackRuntime.isCreated())

        val composeView = requireComposeView(activity)
        val texts = collectTexts(composeView)
        println("[launch-test][MAIN] semantics text = $texts")

        assertTrue("main screen rendered no text at all: $texts", texts.isNotEmpty())
        assertTrue("expected the top-nav '首页' tab, got $texts", texts.contains("首页"))
        assertTrue(
            "expected all four top-nav tabs, got $texts",
            listOf("首页", "每日推荐", "私人 FM", "我的音乐").all { texts.contains(it) },
        )
        // 已登录不应该再出现登录页的错误/加载文案。
        assertTrue(
            "main screen must not show login text, got $texts",
            texts.none { it.contains("登录页加载失败") || it.contains("正在加载登录页") },
        )
    }

    // ------------------------------------------------------------------ 场景 3：登录页 WebView 分支

    /**
     * 场景 1 里 Robolectric 的 `WebView.getCurrentWebViewPackage()` 返回 null，
     * 登录页走的是 `WEBVIEW_INIT_FAILED` 兜底屏。这里给 [ShadowWebView] 装一个假的
     * WebView provider，强行走「真的创建 WebView -> 配置 settings -> loadUrl ->
     * AndroidView interop」这条路径。
     *
     * 注意：WebView 是 shadow，不加载网页、不渲染，也不执行 JS。
     * 它验证的是登录页的 AndroidView 互操作 + 配置代码不会崩，而不是网页真的能打开。
     */
    @Test
    fun `login screen composes its AndroidView WebView when a provider exists`() {
        ShadowWebView.setCurrentWebViewPackage(
            PackageInfo().apply {
                packageName = "com.google.android.webview"
                versionName = "131.0.0.0"
            },
        )

        val activity = launchActivity()
        assertLaidOut(activity, label = "LOGIN-WEBVIEW")

        val webView = findWebView(activity.window.decorView)
        assertNotNull("LoginScreen never added a WebView to the hierarchy", webView)
        val webViewShadow = shadowOf(webView!!)

        assertEquals(
            "login WebView did not load the QR login page",
            "https://music.163.com/login",
            webViewShadow.lastLoadedUrl,
        )
        assertNotNull("login WebView has no WebViewClient", webViewShadow.webViewClient)

        // createLoginWebView() 里设置的产品行为：桌面 UA，否则站点返回无二维码的手机版。
        val settings = webView.settings
        val userAgent = settings.userAgentString
        println("[launch-test][LOGIN-WEBVIEW] userAgent = $userAgent")
        assertTrue(
            "expected the fixed desktop Chrome UA, got $userAgent",
            userAgent.startsWith("Mozilla/5.0 (Windows NT 10.0; Win64; x64)") &&
                userAgent.contains("Chrome/131"),
        )
        assertTrue("javaScriptEnabled was not applied", settings.javaScriptEnabled)
        assertTrue("domStorageEnabled was not applied", settings.domStorageEnabled)
        assertTrue("databaseEnabled was not applied", settings.databaseEnabled)
        assertTrue("loadWithOverviewMode was not applied", settings.loadWithOverviewMode)
        assertTrue("useWideViewPort was not applied", settings.useWideViewPort)
        assertTrue(
            "mediaPlaybackRequiresUserGesture was not applied",
            settings.mediaPlaybackRequiresUserGesture,
        )

        val composeView = requireComposeView(activity)
        val texts = collectTexts(composeView)
        println("[launch-test][LOGIN-WEBVIEW] semantics text = $texts")
        assertTrue(
            "expected the '正在打开登录页…' overlay, got $texts",
            texts.any { it.contains("正在打开登录页") },
        )

        // 页面还没加载完，二维码过期/加载失败都不应该出现。
        assertTrue(
            "unexpected failure screen while WebView is loading: $texts",
            texts.none { it.contains("登录页加载失败") || it.contains("二维码已过期") },
        )
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 真启动 Activity：create -> start -> resume -> visible。
     *
     * `visible()` 会把 decorView attach 到窗口，Compose 的 `ComposeView` 在
     * `onAttachedToWindow` 里同步创建 composition。之后的 measure/layout 与
     * recomposition 由 Choreographer 驱动 —— Robolectric 的 paused looper 不会
     * 自己跑，所以这里手动 idle + 手动 measure/layout，把「首帧」推完。
     */
    private fun launchActivity(): MainActivity {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        drainMainLooper(frames = 6)
        layoutWindow(activity)
        drainMainLooper(frames = 2)
        return activity
    }

    private fun drainMainLooper(frames: Int) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat(frames) { looper.idleFor(FRAME_MS) }
        looper.idle()
    }

    /**
     * Robolectric 下 ViewRootImpl 不一定会走真实 traversal；如果 decorView 还是
     * 0x0，就手动 measure/layout 一次，让 Compose 的 measure 策略真正执行。
     */
    private fun layoutWindow(activity: Activity) {
        val decor = activity.window.decorView
        if (decor.width > 0 && decor.height > 0) return

        val metrics = activity.resources.displayMetrics
        val width = metrics.widthPixels.takeIf { it > 0 } ?: 1920
        val height = metrics.heightPixels.takeIf { it > 0 } ?: 1080
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, width, height)
    }

    private fun assertLaidOut(activity: MainActivity, label: String) {
        assertEquals(
            "$label: activity must be RESUMED",
            Lifecycle.State.RESUMED,
            activity.lifecycle.currentState,
        )

        val decor = activity.window.decorView
        assertNotNull("$label: window.decorView is null", decor)
        assertTrue(
            "$label: decorView has no children (Compose content never attached)",
            decor is ViewGroup && decor.childCount > 0,
        )
        assertTrue(
            "$label: decorView has zero size: ${decor.width}x${decor.height}",
            decor.width > 0 && decor.height > 0,
        )
        println("[launch-test][$label] decorView ok: ${decor.width}x${decor.height}, " +
            "children=${(decor as ViewGroup).childCount}")

        // 组合 + measure/layout 之后，手动跑一次 draw，把「首帧」的最后一段也走掉。
        // Robolectric 默认 LEGACY graphics：Canvas/Bitmap 是 shadow，像素没有意义，
        // 但 Compose 的 draw 代码（Layer/Brush/文字绘制）会真的执行。
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        println("[launch-test][$label] draw pass completed (${bitmap.width}x${bitmap.height})")
    }

    private fun requireComposeView(activity: Activity): View {
        val composeView = findComposeView(activity.window.decorView)
        assertNotNull(
            "Compose never attached an AndroidComposeView to the window",
            composeView,
        )
        assertTrue(
            "AndroidComposeView was never laid out (${composeView!!.width}x${composeView.height})",
            composeView.width > 0 && composeView.height > 0,
        )
        return composeView
    }

    /**
     * `AndroidComposeView` 在 Kotlin 里是 internal（只有字节码层面是 public），
     * 所以按类名查找，不直接引用类型。它是 ComposeView 的子 View，一定在窗口树里。
     */
    private fun findComposeView(root: View): View? {
        if (root.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findComposeView(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun findWebView(root: View): WebView? {
        if (root is WebView) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findWebView(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    /**
     * 直接从 Compose 的语义树里取文案，用来断言「到底渲染了哪个页面」。
     *
     * 这比只看 decorView 有子 View 强得多：两者都只有一个 AndroidComposeView，
     * 真正的区别在语义树里（登录页 vs 顶栏四个 tab）。
     *
     * `AndroidComposeView#getSemanticsOwner()` 是 public 字节码方法，但类本身是
     * internal，因此这里只对这一个入口用反射；拿到的 [SemanticsOwner] 是公开 API，
     * 之后按正常方式遍历。
     */
    private fun collectTexts(composeView: View): List<String> {
        val owner = composeView.javaClass
            .getMethod("getSemanticsOwner")
            .invoke(composeView) as SemanticsOwner
        val texts = mutableListOf<String>()
        val visited = mutableSetOf<Int>()

        fun walk(node: SemanticsNode) {
            if (!visited.add(node.id)) return
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { texts += it.text }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { texts += it }
            node.children.forEach(::walk)
        }

        walk(owner.rootSemanticsNode)
        return texts
    }

    private companion object {
        val FRAME_MS: Duration = Duration.ofMillis(16)
    }
}
