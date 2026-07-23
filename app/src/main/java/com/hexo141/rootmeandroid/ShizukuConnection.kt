package com.hexo141.rootmeandroid

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rikka.shizuku.Shizuku

/// Shizuku 连接状态
enum class ShizukuState {
    NOT_INSTALLED,   // Shizuku 未安装
    NOT_RUNNING,     // 已安装但服务未启动
    NEED_PERMISSION, // 服务运行中但未授权
    READY            // 已连接可用
}

/// 检查 Shizuku 当前连接状态
fun checkShizukuState(context: android.content.Context): ShizukuState {
    return try {
        if (!Shizuku.pingBinder()) {
            ShizukuState.NOT_RUNNING
        } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            ShizukuState.READY
        } else {
            ShizukuState.NEED_PERMISSION
        }
    } catch (e: Exception) {
        // binder 不可用（通常意味着 Shizuku 未安装）
        ShizukuState.NOT_INSTALLED
    }
}

/// Shizuku 配对/连接界面
@Composable
fun ShizukuConnectionPage(
    onConnected: () -> Unit
) {
    val context = LocalContext.current
    var shizukuState by remember { mutableStateOf(checkShizukuState(context)) }
    var permissionRequesting by remember { mutableStateOf(false) }

    // 监听 Shizuku binder 状态变化
    DisposableEffect(Unit) {
        val binderReceivedListener = Shizuku.OnBinderReceivedListener {
            shizukuState = checkShizukuState(context)
        }
        val binderDeadListener = Shizuku.OnBinderDeadListener {
            shizukuState = ShizukuState.NOT_RUNNING
        }
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        onDispose {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        }
    }

    // 监听权限请求结果
    DisposableEffect(Unit) {
        val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            permissionRequesting = false
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                shizukuState = ShizukuState.READY
                onConnected()
            }
        }
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        onDispose {
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        }
    }

    // 状态变化后若已就绪，回调进入主程序
    LaunchedEffect(shizukuState) {
        if (shizukuState == ShizukuState.READY) {
            onConnected()
        }
    }

    val statusText = when (shizukuState) {
        ShizukuState.NOT_INSTALLED -> stringRes(R.string.shizuku_state_not_installed)
        ShizukuState.NOT_RUNNING -> stringRes(R.string.shizuku_state_not_running)
        ShizukuState.NEED_PERMISSION -> stringRes(R.string.shizuku_state_need_permission)
        ShizukuState.READY -> stringRes(R.string.shizuku_state_ready)
    }
    val statusColor = if (shizukuState == ShizukuState.READY) Color(0xFF4CAF50) else Color(0xFFFF9800)

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // Shizuku 图标
            Icon(
                painter = painterResource(id = R.drawable.ic_shizuku),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringRes(R.string.shizuku_need_connect),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringRes(R.string.shizuku_guide),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            // 状态指示
            Text(
                text = stringRes(R.string.shizuku_status_label) + statusText,
                color = statusColor,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(32.dp))

            // 按钮区：主按钮 + 重新检测按钮放在同一行
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val btnAuthorize = stringRes(R.string.shizuku_btn_authorize)
                val btnAuthorizing = stringRes(R.string.shizuku_btn_authorizing)
                val btnGet = stringRes(R.string.shizuku_btn_get)
                val btnOpen = stringRes(R.string.shizuku_btn_open)
                val btnRecheck = stringRes(R.string.shizuku_btn_recheck)
                when (shizukuState) {
                    ShizukuState.NEED_PERMISSION -> {
                        Button(
                            onClick = {
                                if (Shizuku.shouldShowRequestPermissionRationale()) {
                                    // 用户之前拒绝过，打开 Shizuku 应用详情页
                                    openShizukuAppSettings(context)
                                } else {
                                    permissionRequesting = true
                                    Shizuku.requestPermission(0)
                                }
                            },
                            enabled = !permissionRequesting,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(if (permissionRequesting) btnAuthorizing else btnAuthorize)
                        }
                    }
                    ShizukuState.NOT_INSTALLED -> {
                        Button(
                            onClick = { openShizukuWebsite(context) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(btnGet)
                        }
                    }
                    ShizukuState.NOT_RUNNING -> {
                        Button(
                            onClick = { openShizukuAppSettings(context) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(btnOpen)
                        }
                    }
                    ShizukuState.READY -> {
                        // 已就绪会自动进入主程序，这里不显示按钮
                    }
                }
                // 手动刷新按钮
                Button(
                    onClick = { shizukuState = checkShizukuState(context) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(btnRecheck)
                }
            }
        }
    }
}

/// 打开 Shizuku 应用（若已安装）
private fun openShizukuAppSettings(context: android.content.Context) {
    val launchIntent = context.packageManager
        .getLaunchIntentForPackage("moe.shizuku.privileged.api")
    if (launchIntent != null) {
        launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
    } else {
        openShizukuWebsite(context)
    }
}

/// 打开 Shizuku 官网
private fun openShizukuWebsite(context: android.content.Context) {
    val intent = android.content.Intent(
        android.content.Intent.ACTION_VIEW,
        android.net.Uri.parse("https://shizuku.rikka.app/")
    ).apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // 无浏览器
    }
}
