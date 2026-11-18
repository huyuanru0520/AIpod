package com.musicbox.nas

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.musicbox.nas.player.PlayerController
import com.musicbox.nas.ui.components.DockItem
import com.musicbox.nas.ui.components.GlassMiniPlayer
import com.musicbox.nas.ui.components.LiquidGlassDock
import com.musicbox.nas.ui.components.MiniPlayer
import com.musicbox.nas.ui.screens.LibraryScreen
import com.musicbox.nas.ui.screens.PlaylistsScreen
import com.musicbox.nas.ui.screens.PlayerScreen
import com.musicbox.nas.ui.screens.SettingsScreen
import com.musicbox.nas.ui.theme.MusicBoxTheme
import com.musicbox.nas.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /** Compose 侧最近一次请求的沉浸状态。 */
    @Volatile
    private var immersiveWanted = false

    /** 上一次 hide() 的时间：给 insets 自愈节流，避免个别机型「藏了但仍报可见」造成死循环。 */
    @Volatile
    private var lastHideAt = 0L

    /**
     * 竖屏播放页要保留状态栏（参考 iOS 音乐：时间 / 电量正常显示，歌曲信息在其下方），
     * 只隐藏导航栏。Cover Flow 与横屏播放页仍整屏隐藏。
     */
    @Volatile
    var keepStatusBarVisible = false
        private set

    private var hidePosted = false
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideRetry = Runnable { if (immersiveWanted) hideSystemBars() }

    fun setImmersiveWanted(on: Boolean, keepStatusBar: Boolean = false) {
        val statusOnly = on && keepStatusBar
        keepStatusBarVisible = statusOnly
        // 「只显状态栏」模式**不再进入沉浸流程**：既不 hide、也不补 hide、也不做 insets 自愈。
        // 之前是「先 hide 了再 show 回来」，任何一个补 hide / 自愈回调都会把它重新藏掉，
        // 这就是 1.2.3 里 1.2 秒后 statusVisible 仍为 false 的根因。
        // 现在这个模式下根本没有任何代码去藏系统栏，状态栏自然常驻。
        immersiveWanted = on && !statusOnly
        uiHandler.removeCallbacks(hideRetry)
        hidePosted = false
        if (!on) return
        if (statusOnly) {
            applyStatusBarOnly()
            return
        }
        applyImmersive()
        // 系统栏隐藏是一段动画（约 200~300ms），个别 ROM 会在转场结束、窗口焦点变化的
        // 那一瞬间把它放回来。这里在动画收尾阶段补三次，确保「进入即全屏」，
        // 不用再靠用户转一次屏来触发。
        listOf(150L, 500L, 1000L).forEach { uiHandler.postDelayed(hideRetry, it) }
    }

    /**
     * 只显示系统栏的模式：状态栏正常占位、内容在其下方，导航栏也保留。
     *
     * 与前一条路径的关键差别是**清掉 FLAG_LAYOUT_NO_LIMITS**。该标志让窗口布局完全无视
     * 系统栏，部分 ROM（以及原生 Android 15 的强制 edge-to-edge）会把它当作「状态栏被覆盖」，
     * 于是状态栏时有时无。播放页要的恰恰是「状态栏真实占位」，所以这里回到普通 edge-to-edge：
     * 内容仍铺满整屏（decorFits=false），避让交给 Compose 的 statusBarsPadding。
     */
    private fun applyStatusBarOnly() {
        runCatching {
            val w = window
            val decor = w.decorView
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                w.isNavigationBarContrastEnforced = false
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val lp = w.attributes
                lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                w.attributes = lp
            }
            w.statusBarColor = android.graphics.Color.TRANSPARENT
            w.navigationBarColor = android.graphics.Color.TRANSPARENT
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF000000.toInt()))
            decor.setBackgroundColor(0xFF000000.toInt())
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            val c = androidx.core.view.WindowCompat.getInsetsController(w, decor)
            // 深色封面背景：状态栏图标用浅色
            c.isAppearanceLightStatusBars = false
            c.isAppearanceLightNavigationBars = false
            c.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * 进入沉浸：**先改完所有窗口属性，最后才 hide**。
     *
     * 这里是「进入播放页顶部残留一条状态栏高的黑带、转一次屏才消失」的根本原因：
     * `addFlags` / `setAttributes`（改 layoutInDisplayCutoutMode）/ `statusBarColor=` /
     * `setBackgroundDrawable` 每一个都会触发窗口重新应用 LayoutParams，进而让系统重新
     * 评估系统栏可见性。旧代码把 `layoutInDisplayCutoutMode` 和 `requestLayout()` 放在
     * `hide()` 之后，等于刚藏起来又把系统栏顶了回来 —— 首帧量到的 `statusBars` inset 仍是 134，
     * 内容从 y=134 开始绘制，顶部露出被刷黑的窗口底色，也就是用户看到的黑区。
     * 转屏时窗口重建、属性稳定，hide 才「碰巧」生效，于是看起来好了。
     */
    private fun applyImmersive() {
        runCatching {
            val w = window
            val decor = w.decorView
            // ① 窗口布局类属性全部前置
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val lp = w.attributes
                lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                w.attributes = lp
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                w.isNavigationBarContrastEnforced = false
            }
            // ② 系统栏透明：沉浸页内容本就铺满整屏，透明后即便系统栏一时没藏掉，
            //    也只是图标浮在封面上，绝不会出现一条与内容反差的黑色/白色色带。
            w.statusBarColor = android.graphics.Color.TRANSPARENT
            w.navigationBarColor = android.graphics.Color.TRANSPARENT
            // 背景是暗色封面：图标必须用浅色，否则深色图标压在暗背景上等于消失。
            runCatching {
                val c = androidx.core.view.WindowCompat.getInsetsController(w, decor)
                c.isAppearanceLightStatusBars = false
                c.isAppearanceLightNavigationBars = false
            }
            // ③ 窗口与 decor 底色刷黑，覆盖挖孔 / 圆角等没被内容覆盖的缝隙
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF000000.toInt()))
            decor.setBackgroundColor(0xFF000000.toInt())
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            // ④ 最后隐藏，此后不再改动任何窗口属性
            hideSystemBars()
        }
    }

    /** 只隐藏系统栏，不碰任何窗口属性——属性改动会重新布局并把栏「顶」回来。 */
    private fun hideSystemBars() {
        runCatching {
            val w = window
            val decor = w.decorView
            val c = androidx.core.view.WindowCompat.getInsetsController(w, decor)
            c.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                w.insetsController?.apply {
                    systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(
                        android.view.WindowInsets.Type.navigationBars()
                            or android.view.WindowInsets.Type.statusBars()
                    )
                }
            }
            lastHideAt = System.currentTimeMillis()
        }
    }

    /**
     * 自愈：只要处于沉浸态而 insets 仍报「系统栏可见」，就再藏一次。
     * 这样无论是转场、窗口焦点变化、弹窗关闭，还是系统自己把栏放回来，都会自动纠正，
     * 不再依赖「转一次屏」这种巧合。
     */
    private fun installInsetsWatcher() {
        runCatching {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
                if (immersiveWanted) {
                    val barsVisible =
                        insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()) ||
                            insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
                    val now = android.os.SystemClock.uptimeMillis()
                    if (barsVisible && !hidePosted && now - lastHideAt > 400L) {
                        hidePosted = true
                        v.postDelayed({
                            hidePosted = false
                            if (immersiveWanted) hideSystemBars()
                        }, 60L)
                    }
                }
                insets
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && immersiveWanted) hideSystemBars()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLogger.mark("MainActivity.onCreate enter")
        super.onCreate(savedInstanceState)
        // 关键架构约定：窗口「永远」edge-to-edge，之后不再在 true/false 之间来回切。
        // 动态切换 setDecorFitsSystemWindows 会让首帧按旧布局测量，顶部留下一条与状态栏
        // 等高的黑带；普通页面改用 statusBarsPadding() 自行避让（见 AppRoot）。
        runCatching {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        // 挂上 insets 自愈监听：沉浸态下系统栏一旦被系统放回来就自动再藏一次
        installInsetsWatcher()
        // 播放器在 AppRoot 首帧之后创建，冷启动阶段不做任何重初始化
        // Android 13+ 需动态申请通知权限，否则后台播放/媒体通知不会显示
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
        CrashLogger.mark("setContent enter")
        setContent { AppRoot() }
        CrashLogger.mark("setContent returned")
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        CrashLogger.mark("AppRoot first composition")
        runCatching { PlayerController.attach(context.applicationContext) }
            .onFailure { CrashLogger.error("AppRoot.attach", it) }
        CrashLogger.info("AppRoot", "启动完成，刷新「下载」目录日志")
        // Activity 起来后再强制写一次，确保 MediaProvider 就绪时下载目录里一定有文件
        withContext(Dispatchers.IO) { CrashLogger.flushNow() }
    }

    // 上次崩溃过 → 先进入极简诊断界面，不加载任何业务 UI / 导航 / 播放器
    val diag = remember { CrashLogger.read() }
    val hadCrash = remember { CrashLogger.hasCrash() }
    var normal by remember { mutableStateOf(!hadCrash) }

    if (!normal && diag != null) {
        val logText = diag
        val diagDark = isSystemInDarkTheme()
        // 诊断页也响应手势返回：回到正常界面，而不是退出应用
        BackHandler {
            CrashLogger.dismissCrash()
            normal = true
        }
        MusicBoxTheme(darkTheme = diagDark, accent = androidx.compose.ui.graphics.Color(0xFF000000.toInt() or MusicBoxApp.instance.accentColor.value)) {
            SystemBarColors(diagDark)
            DiagnosticsScreen(
                log = logText,
                path = CrashLogger.logPath(),
                onCopy = {
                    runCatching {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("NASMusic log", logText))
                    }
                },
                onContinue = {
                    CrashLogger.clear()
                    normal = true
                }
            )
        }
        return
    }

    val themeMode by MusicBoxApp.instance.themeMode.collectAsState()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val onPlayer = route == "player"

    // 横屏 Cover Flow / 由其打开的专辑列表 / 播放页，均进入「全屏沉浸」：
    // 1) 开启 edge-to-edge（decorFitsSystemWindows=false + FLAG_LAYOUT_NO_LIMITS），内容绘制到系统栏之下；
    // 2) 把导航栏背景设为「透明」并关闭对比强制——这是根治右侧黑边最关键的一步：
    //    部分机型/导航模式下 hide() 无法彻底移除导航栏，其纯黑背景会露在右侧形成黑条；
    //    透明化后，即便导航栏未移除，播放页的黑色背景也会直接透出，不再有可见黑边；
    // 3) 同时用 androidx 控制器 + 传统标志双管齐下尝试真正隐藏系统栏。
    val fullScreen by MusicBoxApp.instance.fullScreenMode.collectAsState()
    // 把「屏幕方向」也纳入触发键：旋转设备时强制重新评估沉浸状态，
    // 避免横屏 Cover Flow 全屏「有时触发不了」（方向变化但 fullScreen/onPlayer 未变时也能重跑）。
    val orientation = LocalConfiguration.current.orientation
    val themeBg = MaterialTheme.colorScheme.background
    LaunchedEffect(fullScreen, onPlayer, orientation) {
        val activity = context.findActivity() as? MainActivity ?: return@LaunchedEffect
        val window = activity.window
        val decor = window.decorView
        val immersive = fullScreen || onPlayer
        if (immersive) {
            // 全部交给 Activity：窗口属性与 hide() 的先后顺序在这里是有讲究的
            //（见 applyImmersive 的注释）。旧代码在这之后又改了一次
            // layoutInDisplayCutoutMode + requestLayout，正好把刚藏起来的系统栏顶了回来，
            // 于是首帧顶部留下一条状态栏高的黑带，要转一次屏才好。
            // 竖屏播放页保留状态栏；Cover Flow 与横屏播放页仍整屏沉浸
            val keepStatus = onPlayer && !fullScreen && orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            activity.setImmersiveWanted(true, keepStatusBar = keepStatus)
        } else {
            activity.setImmersiveWanted(false)
            // 先恢复窗口属性，最后再 show 系统栏（同样是顺序问题）
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            // 注意：不再 setDecorFitsSystemWindows(true)。窗口常驻 edge-to-edge，
            // 普通页面的避让交给 AppRoot 的 statusBarsPadding / navigationBarsPadding。
            val bgArgb = colorToArgb(themeBg)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bgArgb))
            decor.setBackgroundColor(bgArgb)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val lp = window.attributes
                lp.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                window.attributes = lp
            }
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, decor)
            controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            runCatching { decor.requestApplyInsets() }
        }
        // 诊断：同时记录状态栏 inset 与内容视图的实际高度，便于定位「顶部黑带」。
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(decor)
        val navRight =
            insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.right ?: -1
        val statusTop =
            insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())?.top ?: -1
        CrashLogger.info(
            "Immersive",
            "immersive=$immersive fullScreen=$fullScreen onPlayer=$onPlayer " +
                "keepStatus=${activity.keepStatusBarVisible} v=${BuildConfig.VERSION_NAME} orientation=$orientation " +
                "navRightInset=$navRight statusTopInset=$statusTop " +
                "decorH=${decor.height} contentH=${decor.findViewById<android.view.View>(android.R.id.content)?.height} " +
                "flags=${decor.systemUiVisibility} " +
                "statusBarColor=${window.statusBarColor} navBarColor=${window.navigationBarColor}"
        )
        // 系统栏隐藏是异步动画，上面这条记录的是「刚请求时」的快照。
        // 等 1.2 秒后再记一条 settled，直接看出最终是否真的铺满（statusTop 应为 0）。
        kotlinx.coroutines.delay(1200)
        val s = androidx.core.view.ViewCompat.getRootWindowInsets(decor)
        CrashLogger.info(
            "Immersive",
            "settled(1.2s) immersive=$immersive keepStatus=${activity.keepStatusBarVisible} v=${BuildConfig.VERSION_NAME} statusTop=" +
                (s?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())?.top ?: -1) +
                " navBottom=" + (s?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom ?: -1) +
                " statusVisible=" + (s?.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars())) +
                " navVisible=" + (s?.isVisible(androidx.core.view.WindowInsetsCompat.Type.navigationBars())) +
                " decorH=${decor.height} contentH=" +
                (decor.findViewById<android.view.View>(android.R.id.content)?.height)
        )
    }

    // 安卓手势返回 / 返回键：按导航栈逐层回退；首页时退到后台而不是 finish 退出应用。
    // 配合 Manifest 的 enableOnBackInvokedCallback，Android 13+ 可获得预测式返回动画。
    BackHandler {
        CrashLogger.info("Back", "手势返回，当前路由=$route")
        when {
            route != null && route != "library" -> {
                val popped = runCatching { navController.popBackStack() }.getOrDefault(false)
                if (!popped) {
                    runCatching {
                        navController.navigate("library") {
                            popUpTo("library") { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
            }
            else -> {
                val activity = context.findActivity()
                if (activity != null) {
                    activity.moveTaskToBack(true)
                } else {
                    runCatching { navController.popBackStack() }
                }
            }
        }
    }

    val accentArgb by MusicBoxApp.instance.accentColor.collectAsState()
    MusicBoxTheme(darkTheme = dark, accent = androidx.compose.ui.graphics.Color(0xFF000000.toInt() or accentArgb)) {
        val immersiveNow = fullScreen || onPlayer
        // 沉浸页由 Activity 统一接管系统栏（透明 + 隐藏）。这里必须跳过，
        // 否则 SystemBarColors 会把状态栏重新设成不透明色，并因此触发一次窗口属性重排，
        // 把刚藏起来的系统栏又放回来——顶部那条黑带就是这么来的。
        SystemBarColors(dark, immersive = immersiveNow)
        // 沉浸页（播放页 / Cover Flow）背景是暗色的：把 AppRoot 背景与 NavHost 容器在沉浸时
        // 统一改为纯黑，并去掉内容与底栏之间的 4dp 间隙——否则该间隙露出主题背景
        //（浅色=白、深色=近黑），在暗色内容下方形成可见的「底部白块 / 黑边」。
        val rootBg = if (immersiveNow) androidx.compose.ui.graphics.Color.Black
                     else MaterialTheme.colorScheme.background
        // 显式绘制背景：否则 Activity 窗口的浅色底会透出来，深色模式不是纯黑。
        // 窗口常驻 edge-to-edge，所以普通页面要自己避让系统栏（状态栏 + 导航栏）；
        // 沉浸页不加 padding，内容直接铺满整屏——这样「进入播放页」不再有窗口布局切换，
        // 顶部也就不会残留一条与状态栏等高的黑带。
        // 悬浮外壳（Dock + 迷你播放器）浮在内容之上，内容区按实测高度留出空位，
        // 这样列表最后一项不会被 Dock 盖住，Dock 也始终悬在底部而不是挤占布局。
        // library 首页是纯粹的 iPod 模拟器：滚轮上方那一块就是屏幕，没有 Dock、没有
        // 「正在播放」横条，唯一交互是转轮，因此首页不显示外壳。设置 / 歌单等二级页保留
        // Dock 作为返回手段（它们是普通 Material 页面，需要触摸操作）。
        val shellVisible = !onPlayer && !fullScreen && route != "library"
        var shellHeight by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current

        Box(
            Modifier
                .fillMaxSize()
                .background(rootBg)
                .then(
                    if (immersiveNow) Modifier
                    else Modifier
                        .statusBarsPadding()
                        .navigationBarsPadding()
                )
        ) {
            Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(rootBg)
                    .then(if (immersiveNow) Modifier else Modifier.padding(bottom = 4.dp))
            ) {
                NavHost(
                    navController,
                    startDestination = "library",
                    // 页面切换动画：普通页轻推 + 淡入，播放页从底部升起（贴合"打开正在播放"的心智）。
                    // 时长控制在 140~260ms：再长会让两页同时绘制的时间变长，反而显得卡。
                    enterTransition = {
                        fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 16 }
                    },
                    exitTransition = {
                        fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { -it / 24 }
                    },
                    popEnterTransition = {
                        fadeIn(tween(180)) + slideInHorizontally(tween(220)) { -it / 16 }
                    },
                    popExitTransition = {
                        fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { it / 24 }
                    }
                ) {
                    composable("library") { LibraryScreen(navController) }
                    composable("playlists") { PlaylistsScreen(navController) }
                    composable("settings") { SettingsScreen(navController) }
                    composable(
                        "player",
                        enterTransition = {
                            fadeIn(tween(200)) + slideInVertically(tween(260)) { it / 10 }
                        },
                        popExitTransition = {
                            fadeOut(tween(180)) + slideOutVertically(tween(220)) { it / 10 }
                        }
                    ) { PlayerScreen(navController) }
                }
            }

            // 播放错误在任意页面都可见（曲库/歌单点播放失败时不再「毫无反应」）。
            // 收进独立组件：错误、队列、当前曲目的订阅都只让这一小块重组，
            // 不再牵动整个 AppRoot（含 NavHost）跟着重组。
            if (!onPlayer) PlayErrorBanner()

            // 底部留白 = 悬浮外壳的实测高度，最后一项才不会被遮住
            if (shellVisible) Spacer(Modifier.height(shellHeight))
        }

        if (shellVisible) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { shellHeight = with(density) { it.height.toDp() } }
            ) {
                ShellBottom(navController, route)
            }
        }
        }
    }
}

/** 播放错误条：独立订阅错误流，只有出错 / 清除时才重组这一小块。 */
@Composable
private fun PlayErrorBanner() {
    val playError by PlayerController.error.collectAsState()
    // 退出动画期间错误已被清空：保留最后一条文案，否则收缩时内容会先变空。
    var lastError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playError) { if (playError != null) lastError = playError }
    AnimatedVisibility(
        visible = playError != null,
        enter = expandVertically(tween(180)) + fadeIn(tween(180)),
        exit = shrinkVertically(tween(160)) + fadeOut(tween(120))
    ) {
        val msg = lastError
        if (msg != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "播放失败：$msg",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { PlayerController.clearError() }) {
                    Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

/** 从 Compose 的 LocalContext 逐层解包拿到 Activity。 */
private fun Context.findActivity(): android.app.Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * 系统栏（状态栏 + 导航栏）底色显式设为「主题背景色」（不透明），窗口底色同样跟随主题背景。
 * 这样系统栏与内容背景完全一致、无缝过渡，彻底消除深色模式下顶部/侧边露白或黑块。
 *
 * 注意：这里刻意用「不透明主题背景色」而不是 TRANSPARENT。
 * 本工程的夜间主题父级仍是 Light.NoActionBar，在 setDecorFitsSystemWindows(true) 下
 * 把 statusBarColor 设为 TRANSPARENT 时，部分机型/系统版本并不会透出窗口底色，而是保留
 * 主题的默认浅色状态栏（白色），于是深色模式出现「顶部白色状态栏」。直接显式指定颜色可根治。
 */
@Composable
private fun SystemBarColors(dark: Boolean, immersive: Boolean = false) {
    val context = LocalContext.current
    val bg = MaterialTheme.colorScheme.background
    LaunchedEffect(dark, bg, immersive) {
        // 沉浸页（播放页 / Cover Flow）：系统栏由 Activity.applyImmersive() 统一接管
        //（透明 + 隐藏）。这里如果继续把状态栏刷成不透明色，会触发一次窗口属性重排，
        //把刚藏起来的系统栏放回来，表现为「首次进入顶部有黑带、转屏后才好」。
        if (immersive) return@LaunchedEffect
        runCatching {
            val window = (context.findActivity() ?: return@LaunchedEffect).window
            // 窗口底色 = 主题背景，保证系统栏透出区域与内容完全一致、无缝过渡。
            val argb = android.graphics.Color.argb(
                (bg.alpha * 255f).toInt().coerceIn(0, 255),
                (bg.red * 255f).toInt().coerceIn(0, 255),
                (bg.green * 255f).toInt().coerceIn(0, 255),
                (bg.blue * 255f).toInt().coerceIn(0, 255)
            )
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(argb))
            // decorView 背景刷成同一底色：覆盖挖孔/系统栏等 Compose 未铺满的缝隙，
            // 避免强制深色 + 系统浅色时资源主题的白色 windowBackground 从缝隙漏出。
            window.decorView.setBackgroundColor(argb)
            // 关键：系统栏底色直接设为不透明的主题背景色（非透明），
            // 保证深色模式也是黑、浅色模式也是白，与内容融为一体，不存在反差白条。
            window.statusBarColor = argb
            window.navigationBarColor = argb
            // 关闭导航栏对比强制，避免透明导航栏被系统叠加浅色蒙版。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
}

/** Compose 颜色 → 打包成 0xAARRGGBB 的 Int（避免依赖 toArgb 扩展的兼容性问题）。 */
private fun colorToArgb(c: androidx.compose.ui.graphics.Color): Int = android.graphics.Color.argb(
    (c.alpha * 255f).toInt().coerceIn(0, 255),
    (c.red * 255f).toInt().coerceIn(0, 255),
    (c.green * 255f).toInt().coerceIn(0, 255),
    (c.blue * 255f).toInt().coerceIn(0, 255)
)

/**
 * 极简诊断界面：只依赖基础 Compose + Material3，
 * 不触碰 NavHost / LibraryScreen / DataStore / Media3，确保能显示出来。
 */
@Composable
private fun DiagnosticsScreen(
    log: String,
    path: String,
    onCopy: () -> Unit,
    onContinue: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 窗口常驻 edge-to-edge，诊断页自己避让系统栏
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("诊断模式", style = MaterialTheme.typography.headlineSmall)
        Text(
            "上次启动检测到崩溃，已切换到简化界面。请把下面的日志复制粘贴给开发者，或按路径导出文件后上传。",
            style = MaterialTheme.typography.bodyMedium
        )
        Text("日志文件路径：\n$path", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCopy) { Text("复制日志") }
            Button(onClick = onContinue) { Text("清除并正常启动") }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            log,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/**
 * 底部悬浮外壳：玻璃迷你播放器 + 液态玻璃 Dock。
 *
 * 只在这里订阅播放状态（队列 / 播放中 / 进度）：外面的 NavHost 不会因为进度变化重组。
 */
@Composable
private fun ShellBottom(navController: NavController, route: String?) {
    val queue by PlayerController.queue.collectAsState()
    val idx by PlayerController.currentIndex.collectAsState()
    val isPlaying by PlayerController.isPlaying.collectAsState()
    val position by PlayerController.position.collectAsState()
    val duration by PlayerController.duration.collectAsState()
    val current = if (idx in queue.indices) queue[idx] else null
    val progress = if (duration > 0L) position.toFloat() / duration.toFloat() else 0f

    val items = listOf(
        DockItem("library", "曲库", Icons.Default.LibraryMusic),
        DockItem("playlists", "歌单", Icons.Default.QueueMusic),
        DockItem("settings", "设置", Icons.Default.Settings)
    )

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 10.dp)
    ) {
        AnimatedVisibility(
            visible = current != null,
            enter = slideInVertically(tween(240)) { it / 2 } + fadeIn(tween(180)),
            exit = slideOutVertically(tween(200)) { it / 2 } + fadeOut(tween(140))
        ) {
            val c = current
            if (c != null) {
                Column {
                    GlassMiniPlayer(
                        title = c.title,
                        artist = c.artist,
                        coverUrl = c.coverUrl,
                        embeddedAudioUrl = c.embeddedAudioUrl,
                        isPlaying = isPlaying,
                        progress = progress,
                        onClick = { navController.navigate("player") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        LiquidGlassDock(
            selected = items.indexOfFirst { it.route == route }.coerceAtLeast(0),
            items = items,
            onSelect = { index ->
                val r = items[index].route
                if (r != route) {
                    navController.navigate(r) {
                        popUpTo("library") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
