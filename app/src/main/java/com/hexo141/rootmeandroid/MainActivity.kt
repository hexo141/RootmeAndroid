package com.hexo141.rootmeandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hexo141.rootmeandroid.ui.theme.RootmeAndroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RootmeAndroidTheme {
                AppRoot()
            }
        }
    }
}

enum class NavDest(val labelRes: Int, val iconRes: Int) {
    Home(R.string.nav_home, R.drawable.ic_nav_home),
    Hardware(R.string.nav_hardware, R.drawable.ic_nav_hardware),
    Exploit(R.string.nav_exploit, R.drawable.ic_nav_exploit),
    Settings(R.string.nav_settings, R.drawable.ic_nav_settings),
    About(R.string.nav_about, R.drawable.ic_nav_about)
}

/// 更新检查状态：检测中 / 已是最新 / 有新版本
enum class UpdateCheckState { CHECKING, LATEST, AVAILABLE }

@Composable
fun AppRoot() {
    val context = LocalContext.current
    // Shizuku 连接状态：未就绪时显示配对界面
    var shizukuReady by remember { mutableStateOf(checkShizukuState(context) == ShizukuState.READY) }

    // 应用更新检查状态
    var updateInfo by remember { mutableStateOf<ReleaseInfo?>(null) }
    var updateCheckState by remember { mutableStateOf(UpdateCheckState.CHECKING) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    // UI 缩放
    val scale = ScaleManager.current
    val baseDensity = LocalDensity.current
    val scaledDensity = Density(
        density = baseDensity.density * scale,
        fontScale = baseDensity.fontScale * scale
    )

    // 启动时后台请求 GitHub latest release
    LaunchedEffect(Unit) {
        val currentVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        }.getOrNull() ?: "1.0"
        val release = checkLatestRelease()
        if (release != null && isVersionNewer(currentVersion, release.tagName)) {
            updateInfo = release
            updateCheckState = UpdateCheckState.AVAILABLE
            showUpdateDialog = true
        } else {
            updateCheckState = UpdateCheckState.LATEST
        }
    }

    // 启动或继续下载（断点续传）
    fun startDownload() {
        val release = updateInfo ?: return
        showUpdateDialog = false
        isDownloading = true
        isPaused = false
        downloadError = null
        val apkFile = File(context.cacheDir, release.apkName)
        downloadJob = scope.launch {
            try {
                downloadApkResumable(release.apkUrl, apkFile) { progress ->
                    downloadProgress = progress
                }
                isDownloading = false
                installApk(context, apkFile)
            } catch (e: CancellationException) {
                throw e // 暂停或取消，不显示错误
            } catch (e: Exception) {
                isDownloading = false
                downloadError = e.message ?: e.javaClass.simpleName
            }
        }
    }

    // 暂停下载
    fun pauseDownload() {
        downloadJob?.cancel()
        isPaused = true
    }

    // 取消下载并清理
    fun cancelDownload() {
        downloadJob?.cancel()
        isDownloading = false
        isPaused = false
        downloadProgress = 0f
        updateInfo?.let { File(context.cacheDir, it.apkName).delete() }
    }

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        if (!shizukuReady) {
            ShizukuConnectionPage(onConnected = { shizukuReady = true })
            UpdateDialogs(
                updateInfo = updateInfo,
                showUpdateDialog = showUpdateDialog,
                isDownloading = isDownloading,
                isPaused = isPaused,
                downloadProgress = downloadProgress,
                downloadError = downloadError,
                onConfirmDownload = { startDownload() },
                onDismissUpdate = { showUpdateDialog = false },
                onDismissError = { downloadError = null },
                onPause = { pauseDownload() },
                onResume = { startDownload() },
                onCancelDownload = { cancelDownload() }
            )
            return@CompositionLocalProvider
        }

        // 导航栏首次进入：从屏幕外上滑入，后续直接显示
        var navBarShown by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) { navBarShown = true }

        var selected by remember { mutableStateOf(NavDest.Home) }
        // 仅首次进入 app 播放打字机动画；之后切回 Home 显示静态完整文本
        var welcomeAnimated by rememberSaveable { mutableStateOf(false) }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            // 外层 Box 不应用 innerPadding，让导航栏独立处理底部 inset，避免
            // 依赖 Scaffold innerPadding 导致的定位不稳问题
            Box(modifier = Modifier.fillMaxSize()) {
                // 页面内容：尊重 Scaffold 的 innerPadding（状态栏 + 导航栏）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    when (selected) {
                        NavDest.Home -> WelcomePage(
                            playAnimation = !welcomeAnimated,
                            onAnimationComplete = { welcomeAnimated = true },
                            updateCheckState = updateCheckState,
                            onVersionClick = { startDownload() }
                        )
                        NavDest.Hardware -> HardwarePage()
                        NavDest.Exploit -> ExploitPage()
                        NavDest.Settings -> SettingsPage()
                        NavDest.About -> AboutPage()
                    }
                }
                // 浮动导航栏：首次进入从屏幕外上滑入
                AnimatedVisibility(
                    visible = navBarShown,
                    enter = slideInVertically(
                        initialOffsetY = { fullHeight -> fullHeight },
                        animationSpec = tween(durationMillis = 400)
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp)
                ) {
                    DynamicIslandNavBar(
                        items = NavDest.entries.toList(),
                        selected = selected,
                        onSelect = { selected = it }
                    )
                }
            }
        }
        UpdateDialogs(
            updateInfo = updateInfo,
            showUpdateDialog = showUpdateDialog,
            isDownloading = isDownloading,
            isPaused = isPaused,
            downloadProgress = downloadProgress,
            downloadError = downloadError,
            onConfirmDownload = { startDownload() },
            onDismissUpdate = { showUpdateDialog = false },
            onDismissError = { downloadError = null },
            onPause = { pauseDownload() },
            onResume = { startDownload() },
            onCancelDownload = { cancelDownload() }
        )
    }
}

/// 集中渲染更新相关对话框：发现新版本、下载中/暂停、下载失败
@Composable
fun UpdateDialogs(
    updateInfo: ReleaseInfo?,
    showUpdateDialog: Boolean,
    isDownloading: Boolean,
    isPaused: Boolean,
    downloadProgress: Float,
    downloadError: String?,
    onConfirmDownload: () -> Unit,
    onDismissUpdate: () -> Unit,
    onDismissError: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancelDownload: () -> Unit
) {
    if (showUpdateDialog && updateInfo != null) {
        AlertDialog(
            onDismissRequest = onDismissUpdate,
            title = { Text(stringRes(R.string.update_available_title)) },
            text = {
                Text(stringRes(R.string.update_available_message_format, updateInfo.tagName))
            },
            confirmButton = {
                TextButton(onClick = onConfirmDownload) {
                    Text(stringRes(R.string.update_btn_download))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissUpdate) {
                    Text(stringRes(R.string.update_btn_cancel))
                }
            }
        )
    }
    if (isDownloading) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(if (isPaused) stringRes(R.string.update_paused_title) else stringRes(R.string.update_downloading_title))
            },
            text = {
                Column {
                    // downloadProgress 为 -1f 表示服务端未返回 Content-Length，转为不确定模式
                    val determined = downloadProgress >= 0f
                    if (determined) {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("${(downloadProgress * 100).toInt()}%")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                if (isPaused) {
                    TextButton(onClick = onResume) {
                        Text(stringRes(R.string.update_btn_resume))
                    }
                } else {
                    TextButton(onClick = onPause) {
                        Text(stringRes(R.string.update_btn_pause))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDownload) {
                    Text(stringRes(R.string.update_btn_cancel))
                }
            }
        )
    }
    if (downloadError != null) {
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringRes(R.string.update_available_title)) },
            text = {
                Text(stringRes(R.string.update_download_error_format, downloadError))
            },
            confirmButton = {
                TextButton(onClick = onDismissError) {
                    Text(stringRes(R.string.update_btn_close))
                }
            }
        )
    }
}

@Composable
fun PlaceholderPage(dest: NavDest) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringRes(dest.labelRes),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

/** 读取 Shizuku 版本号：从 PackageManager 读取 Shizuku 应用的 versionName，仅保留 主.次.修订 */
@Composable
fun rememberShizukuVersion(): String {
    val context = LocalContext.current
    return remember {
        try {
            val pkgInfo = context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            // 形如 "13.6.0.r1086.2650830c" -> "13.6.0"
            val raw = pkgInfo.versionName ?: return@remember "?"
            val parts = raw.split('.')
            if (parts.size >= 3) "${parts[0]}.${parts[1]}.${parts[2]}" else raw
        } catch (e: Exception) {
            "?"
        }
    }
}

@Composable
fun WelcomePage(
    playAnimation: Boolean,
    onAnimationComplete: () -> Unit,
    updateCheckState: UpdateCheckState = UpdateCheckState.CHECKING,
    onVersionClick: () -> Unit = {}
) {
    val initialText = stringRes(R.string.welcome_text)
    val subtitleText = stringRes(R.string.welcome_subtitle)

    // 阶段：0=打字 initialText, 1=删除 initialText, 2=打字 subtitleText, 3=完成
    var phase by remember { mutableIntStateOf(if (playAnimation) 0 else 3) }
    var typedCount by remember { mutableIntStateOf(if (playAnimation) 0 else subtitleText.length) }
    val currentText = if (phase <= 1) initialText else subtitleText

    // 三阶段打字机：先打出 initialText → 逐字删除 → 再打出 subtitleText
    LaunchedEffect(playAnimation) {
        if (playAnimation) {
            // 阶段 0：逐字打出 initialText
            phase = 0
            typedCount = 0
            while (typedCount < initialText.length) {
                delay(90)
                typedCount++
            }
            delay(600)

            // 阶段 1：逐字删除 initialText
            phase = 1
            while (typedCount > 0) {
                delay(50)
                typedCount--
            }
            delay(300)

            // 阶段 2：逐字打出 subtitleText
            phase = 2
            typedCount = 0
            while (typedCount < subtitleText.length) {
                delay(90)
                typedCount++
            }

            phase = 3
            onAnimationComplete()
        }
    }

    // 光标 "_" 用无限动画在 1f / 0f 之间循环
    val cursorTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by cursorTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_alpha"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val pageBgColor = MaterialTheme.colorScheme.background
        // 着色器背景在上：占据上半部分，底部云层向下延伸并过渡到白色背景
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.42f),
            contentAlignment = Alignment.Center
        ) {
            WelcomeShaderBackground(
                modifier = Modifier.fillMaxSize(),
                pageBgColor = pageBgColor
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                val textShadow = Shadow(
                    color = Color.Black.copy(alpha = 0.55f),
                    offset = Offset(2f, 3f),
                    blurRadius = 6f
                )
                Text(
                    text = currentText.substring(0, typedCount.coerceAtMost(currentText.length)),
                    style = MaterialTheme.typography.headlineMedium.copy(shadow = textShadow),
                    color = Color(0xFFFFF5E6)
                )
                Text(
                    text = "_",
                    style = MaterialTheme.typography.headlineMedium.copy(shadow = textShadow),
                    color = Color(0xFFFFF5E6),
                    modifier = Modifier.alpha(cursorAlpha)
                )
            }
        }
        // 背景区下方：一言名句（着色器底部云层已过渡到白色，留少量间距）
        HitokotoQuote(
            textColor = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
            updateCheckState = updateCheckState,
            onVersionClick = onVersionClick
        )
    }
}

/// 一言名句：从 https://v1.hitokoto.cn/ 拉取并展示
@Composable
fun HitokotoQuote(
    textColor: Color,
    modifier: Modifier = Modifier,
    updateCheckState: UpdateCheckState = UpdateCheckState.CHECKING,
    onVersionClick: () -> Unit = {}
) {
    var quote by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL("https://v1.hitokoto.cn/").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    requestMethod = "GET"
                }
                conn.inputStream.use { stream ->
                    val text = BufferedReader(InputStreamReader(stream)).readText()
                    val json = JSONObject(text)
                    json.optString("hitokoto") to json.optString("from")
                }
            }.getOrNull()
        }
        if (result != null) {
            quote = result.first
            from = result.second
        }
    }

    // 获取 app 版本号
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }

    // 打字机：fullText 改变（每次重新拉到一言）时重置并逐字打出
    var typedCount by remember { mutableIntStateOf(0) }
    val fullText = remember(quote, from) {
        if (quote.isEmpty()) ""
        else if (from.isNotEmpty()) "\"$quote\" —— $from"
        else "\"$quote\""
    }
    LaunchedEffect(fullText) {
        typedCount = 0
        while (typedCount < fullText.length) {
            delay(45)
            typedCount++
        }
    }

    // 光标 "_" 无限闪烁
    val cursorTransition = rememberInfiniteTransition(label = "hitokoto_cursor")
    val cursorAlpha by cursorTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hitokoto_cursor_alpha"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (fullText.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = fullText.substring(0, typedCount.coerceAtMost(fullText.length)),
                    color = textColor,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "_",
                    color = textColor,
                    fontSize = 15.sp,
                    modifier = Modifier.alpha(cursorAlpha)
                )
            }
        }
        // 版本信息行：git 图标 + Version: x.x.x [更新状态图标]    shizuku 图标 + v版本号
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_git),
                contentDescription = null,
                tint = textColor.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp)
            )
            // 有新版本时版本名+状态图标整体可点击触发下载
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = if (updateCheckState == UpdateCheckState.AVAILABLE) {
                    Modifier.clickable { onVersionClick() }
                } else {
                    Modifier
                }
            ) {
                Text(
                    text = "Ver: $versionName",
                    color = textColor.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
                UpdateStatusIcon(
                    state = updateCheckState,
                    tint = textColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.width(24.dp))
            Icon(
                painter = painterResource(id = R.drawable.ic_shizuku),
                contentDescription = null,
                tint = textColor.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "v${rememberShizukuVersion()}",
                color = textColor.copy(alpha = 0.6f),
                fontSize = 11.sp
            )
        }
    }
}

/// 更新状态图标：CHECKING 旋转动画 / LATEST 对勾 / AVAILABLE 下载箭头
@Composable
fun UpdateStatusIcon(
    state: UpdateCheckState,
    tint: Color,
    modifier: Modifier = Modifier
) {
    when (state) {
        UpdateCheckState.CHECKING -> {
            // 旋转动画表示正在后台检测
            val transition = rememberInfiniteTransition(label = "update_checking")
            val rotation by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1000),
                    repeatMode = RepeatMode.Restart
                ),
                label = "update_checking_rotation"
            )
            Icon(
                painter = painterResource(id = R.drawable.ic_update_checking),
                contentDescription = null,
                tint = tint,
                modifier = modifier.rotate(rotation)
            )
        }
        UpdateCheckState.LATEST -> {
            Icon(
                painter = painterResource(id = R.drawable.ic_update_latest),
                contentDescription = null,
                tint = tint,
                modifier = modifier
            )
        }
        UpdateCheckState.AVAILABLE -> {
            Icon(
                painter = painterResource(id = R.drawable.ic_update_available),
                contentDescription = null,
                tint = tint,
                modifier = modifier
            )
        }
    }
}

@Composable
fun DynamicIslandNavBar(
    items: List<NavDest>,
    selected: NavDest,
    onSelect: (NavDest) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF0E0E12))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                NavItemView(
                    item = item,
                    isSelected = item == selected,
                    onClick = { onSelect(item) }
                )
            }
        }
    }
}

@Composable
private fun NavItemView(
    item: NavDest,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // LinearEasing = uniform speed, no spring-like overshoot
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) Color.White.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = tween(durationMillis = 220, easing = LinearEasing),
        label = "nav_bg"
    )
    val iconTint by animateColorAsState(
        targetValue = if (isSelected) Color.White else Color.White.copy(alpha = 0.55f),
        animationSpec = tween(durationMillis = 220, easing = LinearEasing),
        label = "nav_tint"
    )
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(bgColor)
            .animateContentSize(
                animationSpec = tween(durationMillis = 220, easing = LinearEasing)
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        val labelText = stringRes(item.labelRes)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                painter = painterResource(id = item.iconRes),
                contentDescription = labelText,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
            // fade-only AnimatedVisibility; size change is driven by animateContentSize above,
            // avoiding the per-frame layout reflow that expandHorizontally causes.
            AnimatedVisibility(
                visible = isSelected,
                enter = fadeIn(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(120))
            ) {
                Text(
                    text = labelText,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AppRootPreview() {
    RootmeAndroidTheme {
        AppRoot()
    }
}
