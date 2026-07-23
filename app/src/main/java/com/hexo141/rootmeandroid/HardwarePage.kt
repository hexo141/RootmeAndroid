package com.hexo141.rootmeandroid

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/// 硬件信息项（无图标）
data class HardwareItem(
    val label: String,
    val value: String
)

/// 硬件信息页：进入页面直接开始获取，获取过程中显示弹窗，完成后直接展示在 UI 上
@Composable
fun HardwarePage() {
    var items by remember { mutableStateOf<List<HardwareItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // 标签字符串
    val labelBrand = stringRes(R.string.hardware_label_brand)
    val labelCpu = stringRes(R.string.hardware_label_cpu)
    val labelGpu = stringRes(R.string.hardware_label_gpu)
    val labelKernel = stringRes(R.string.hardware_label_kernel)
    val labelSelinux = stringRes(R.string.hardware_label_selinux)
    val labelFingerprint = stringRes(R.string.hardware_label_fingerprint)
    val coresFormat = stringRes(R.string.hardware_cores_format)
    val unknownFreq = stringRes(R.string.hardware_unknown_freq)
    val unknownStr = stringRes(R.string.hardware_unknown)

    // 进入页面直接开始获取
    LaunchedEffect(Unit) {
        scope.launch {
            items = withContext(Dispatchers.IO) {
                collectHardwareInfo(
                    labelBrand, labelCpu, labelGpu, labelKernel,
                    labelSelinux, labelFingerprint, coresFormat, unknownFreq, unknownStr
                )
            }
            loading = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!loading) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 16.dp, bottom = 80.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items) { item -> HardwareInfoCard(item) }
            }
        }
    }

    if (loading) {
        LoadingDialog(onDismiss = { /* 不可关闭 */ })
    }
}

/// 加载中弹窗（不可关闭）
@Composable
private fun LoadingDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringRes(R.string.hardware_dialog_title)) },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

/// 单条硬件信息卡片（无图标）
@Composable
private fun HardwareInfoCard(item: HardwareItem) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // 标签：带区别背景色的圆角小标签
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = item.label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = item.value,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace
        )
    }
}

/// 收集所有硬件信息（shell + Build）
private fun collectHardwareInfo(
    labelBrand: String,
    labelCpu: String,
    labelGpu: String,
    labelKernel: String,
    labelSelinux: String,
    labelFingerprint: String,
    coresFormat: String,
    unknownFreq: String,
    unknownStr: String
): List<HardwareItem> {
    val result = mutableListOf<HardwareItem>()

    result.add(HardwareItem(label = labelBrand, value = "${Build.BRAND} ${Build.MODEL}"))
    result.add(HardwareItem(label = labelCpu, value = readCpuInfo(coresFormat, unknownFreq, unknownStr)))
    result.add(HardwareItem(label = labelGpu, value = readGpuInfo(unknownStr)))
    result.add(HardwareItem(label = labelKernel, value = readKernelInfo(unknownStr)))
    result.add(HardwareItem(label = labelSelinux, value = readSelinuxStatus(unknownStr)))
    result.add(HardwareItem(label = labelFingerprint, value = Build.FINGERPRINT))

    return result
}

/// 以 shell 身份执行命令，返回 stdout
private fun shizukuShell(cmd: String): String {
    return try {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = BufferedReader(InputStreamReader(process.inputStream)).readText()
        process.waitFor()
        out
    } catch (e: Exception) {
        ""
    }
}

/// 读内核版本
private fun readKernelInfo(unknownStr: String): String {
    val procVersion = shizukuShell("cat /proc/version").trim()
    if (procVersion.isNotEmpty()) return procVersion
    val osVer = System.getProperty("os.version") ?: ""
    return if (osVer.isNotEmpty()) osVer else unknownStr
}

/// 读 CPU 信息
private fun readCpuInfo(coresFormat: String, unknownFreq: String, unknownStr: String): String {
    val socManufacturer = shizukuShell("getprop ro.soc.manufacturer").trim()
    val socModel = shizukuShell("getprop ro.soc.model").trim()
    val hardware = shizukuShell("cat /proc/cpuinfo | grep -m1 'Hardware' | cut -d: -f2").trim()
    val modelName = shizukuShell("cat /proc/cpuinfo | grep -m1 'model name' | cut -d: -f2").trim()
    val platform = shizukuShell("getprop ro.board.platform").trim()

    val parts = mutableListOf<String>()
    if (socManufacturer.isNotEmpty()) parts.add(socManufacturer)
    if (socModel.isNotEmpty()) {
        parts.add(socModel)
    } else if (hardware.isNotEmpty()) {
        parts.add(hardware)
    } else if (modelName.isNotEmpty()) {
        parts.add(modelName)
    } else if (platform.isNotEmpty()) {
        parts.add(platform)
    }
    val brandStr = if (parts.isNotEmpty()) parts.joinToString(" ") else unknownStr

    val cores = Runtime.getRuntime().availableProcessors()
    val maxFreq = shizukuShell("cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq 2>/dev/null").trim()
    val freqStr = if (maxFreq.isNotEmpty() && maxFreq.matches(Regex("\\d+"))) {
        "${maxFreq.toInt() / 1000} MHz"
    } else {
        unknownFreq
    }

    return "$brandStr\n${coresFormat.format(cores, freqStr)}"
}

/// 读 GPU 信息
private fun readGpuInfo(unknownStr: String): String {
    val sfGpu = shizukuShell("dumpsys SurfaceFlinger 2>/dev/null | grep -i 'GLES:'").trim()
    if (sfGpu.isNotEmpty()) {
        val cleaned = sfGpu.substringAfter("GLES:", sfGpu).trim()
        if (cleaned.isNotEmpty()) return cleaned
    }
    val mali = shizukuShell("cat /sys/class/misc/mali0/device/gpu_model 2>/dev/null").trim()
    if (mali.isNotEmpty()) return mali
    val egl = shizukuShell("getprop ro.hardware.egl").trim()
    val glVer = shizukuShell("getprop ro.opengles.version").trim()
    val verStr = if (glVer.isNotEmpty() && glVer.matches(Regex("\\d+"))) {
        val v = glVer.toInt(16)
        "${v shr 16}.${v and 0xFFFF}"
    } else ""
    val parts = mutableListOf<String>()
    if (egl.isNotEmpty()) parts.add(egl)
    if (verStr.isNotEmpty()) parts.add("GLES $verStr")
    return if (parts.isNotEmpty()) parts.joinToString(" ") else unknownStr
}

/// 读 SELinux 状态
private fun readSelinuxStatus(unknownStr: String): String {
    val status = shizukuShell("getenforce").trim()
    return if (status.isNotEmpty()) status else unknownStr
}
