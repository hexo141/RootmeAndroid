package com.hexo141.rootmeandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.provider.Settings as SystemSettings
import com.hexo141.rootmeandroid.ui.theme.RootmeAndroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
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

enum class NavDest(val label: String, val iconRes: Int) {
    Home("Home", R.drawable.ic_nav_home),
    Hardware("Hardware", R.drawable.ic_nav_hardware),
    Exploit("Exploit", R.drawable.ic_nav_exploit),
    Settings("Settings", R.drawable.ic_nav_settings),
    About("About", R.drawable.ic_nav_about)
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    // Shizuku 连接状态：未就绪时显示配对界面
    var shizukuReady by remember { mutableStateOf(checkShizukuState(context) == ShizukuState.READY) }

    if (!shizukuReady) {
        ShizukuConnectionPage(onConnected = { shizukuReady = true })
        return
    }

    var selected by remember { mutableStateOf(NavDest.Home) }
    // 仅首次进入 app 播放打字机动画；之后切回 Home 显示静态完整文本
    var welcomeAnimated by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selected) {
                NavDest.Home -> WelcomePage(
                    playAnimation = !welcomeAnimated,
                    onAnimationComplete = { welcomeAnimated = true }
                )
                NavDest.Hardware -> HardwarePage()
                NavDest.Exploit -> PlaceholderPage(NavDest.Exploit)
                NavDest.Settings -> PlaceholderPage(NavDest.Settings)
                NavDest.About -> PlaceholderPage(NavDest.About)
            }
            DynamicIslandNavBar(
                items = NavDest.entries.toList(),
                selected = selected,
                onSelect = { selected = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            )
        }
    }
}

@Composable
fun PlaceholderPage(dest: NavDest) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = dest.label,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

/** 读取设备机主名：读 device_name，读不到回退到 "User" */
@Composable
fun rememberDeviceOwnerName(): String {
    val context = LocalContext.current
    return remember {
        val resolver = context.contentResolver
        val deviceName = SystemSettings.Global.getString(resolver, "device_name")
        if (!deviceName.isNullOrBlank()) deviceName else "User"
    }
}

/** 读取 Shizuku 版本号：通过 Shizuku API 获取，读不到回退到 "?" */
@Composable
fun rememberShizukuVersion(): String {
    return remember {
        try {
            rikka.shizuku.Shizuku.getVersion()
        } catch (e: Exception) {
            "?"
        }.toString()
    }
}

@Composable
fun WelcomePage(
    playAnimation: Boolean,
    onAnimationComplete: () -> Unit
) {
    val ownerName = rememberDeviceOwnerName()
    val fullText = "Welcome, $ownerName"
    // playAnimation=true 时从 0 逐字打；false 时直接满
    var typedCount by remember { mutableIntStateOf(if (playAnimation) 0 else fullText.length) }

    // 打字机：每 90ms 增加一个字符，到末尾后回调一次
    LaunchedEffect(playAnimation, fullText) {
        if (playAnimation) {
            while (typedCount < fullText.length) {
                delay(90)
                typedCount++
            }
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
        // 背景区：着色器 + 欢迎文字垂直居中
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.3f),
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
                    text = fullText.substring(0, typedCount),
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
        // 背景区下方：一言名句
        HitokotoQuote(
            textColor = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp)
        )
    }
}

/// 一言名句：从 https://v1.hitokoto.cn/ 拉取并展示
@Composable
fun HitokotoQuote(
    textColor: Color,
    modifier: Modifier = Modifier
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

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (quote.isNotEmpty()) {
            Text(
                text = "\"$quote\"",
                color = textColor,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "—— $from",
                color = textColor.copy(alpha = 0.6f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
        // 版本信息行：git 图标 + Version: x.x.x    shizuku 图标 + Shizuku:none（并列一行）
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
            Text(
                text = "Version: $versionName",
                color = textColor.copy(alpha = 0.6f),
                fontSize = 11.sp
            )
            Spacer(Modifier.width(24.dp))
            Icon(
                painter = painterResource(id = R.drawable.ic_shizuku),
                contentDescription = null,
                tint = textColor.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "Shizuku: v${rememberShizukuVersion()}",
                color = textColor.copy(alpha = 0.6f),
                fontSize = 11.sp
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
    // tween for colors is smoother & cheaper than spring (no overshoot, single interp)
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) Color.White.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = tween(durationMillis = 220),
        label = "nav_bg"
    )
    val iconTint by animateColorAsState(
        targetValue = if (isSelected) Color.White else Color.White.copy(alpha = 0.55f),
        animationSpec = tween(durationMillis = 220),
        label = "nav_tint"
    )
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(bgColor)
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                painter = painterResource(id = item.iconRes),
                contentDescription = item.label,
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
                    text = item.label,
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
