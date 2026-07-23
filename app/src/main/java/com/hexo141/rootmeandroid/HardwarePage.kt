package com.hexo141.rootmeandroid

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.BufferedReader
import java.io.InputStreamReader

/// 硬件信息项（无图标）
data class HardwareItem(
    val label: String,
    val value: String
)

/// 硬件信息页：通过 shell 读取设备信息
@Composable
fun HardwarePage() {
    var items by remember { mutableStateOf<List<HardwareItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // 标签字符串：语言切换时自动重组
    val labelBrand = stringRes(R.string.hardware_label_brand)
    val labelCpu = stringRes(R.string.hardware_label_cpu)
    val labelGpu = stringRes(R.string.hardware_label_gpu)
    val labelKernel = stringRes(R.string.hardware_label_kernel)
    val labelSelinux = stringRes(R.string.hardware_label_selinux)
    val labelFingerprint = stringRes(R.string.hardware_label_fingerprint)
    val coresFormat = stringRes(R.string.hardware_cores_format)
    val unknownFreq = stringRes(R.string.hardware_unknown_freq)
    val unknownStr = stringRes(R.string.hardware_unknown)

    LaunchedEffect(labelBrand, coresFormat, unknownFreq, unknownStr) {
        loading = true
        items = collectHardwareInfo(
            labelBrand = labelBrand,
            labelCpu = labelCpu,
            labelGpu = labelGpu,
            labelKernel = labelKernel,
            labelSelinux = labelSelinux,
            labelFingerprint = labelFingerprint,
            coresFormat = coresFormat,
            unknownFreq = unknownFreq,
            unknownStr = unknownStr
        )
        loading = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (loading) {
            Text(
                text = stringRes(R.string.hardware_loading),
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onBackground
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // 顶部 16dp，底部 120dp 给悬浮导航栏预留空间
                contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp, start = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items) { item -> HardwareInfoCard(item) }
            }
        }
    }
}

/// 单条硬件信息卡片（无图标）
@Composable
private fun HardwareInfoCard(item: HardwareItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
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
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
            }
            Spacer(Modifier.size(6.dp))
            Text(
                text = item.value,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace
            )
        }
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

    // 1. 品牌及型号
    result.add(
        HardwareItem(
            label = labelBrand,
            value = "${Build.BRAND} ${Build.MODEL}"
        )
    )

    // 2. CPU 信息
    result.add(HardwareItem(label = labelCpu, value = readCpuInfo(coresFormat, unknownFreq, unknownStr)))

    // 3. GPU 信息（读 /sys/class/misc/mali0/device/gpu_model 或 dumpsys gfxinfo）
    result.add(HardwareItem(label = labelGpu, value = readGpuInfo(unknownStr)))

    // 4. 内核信息
    result.add(HardwareItem(label = labelKernel, value = readKernelInfo(unknownStr)))

    // 5. SELinux 状态
    result.add(HardwareItem(label = labelSelinux, value = readSelinuxStatus(unknownStr)))

    // 6. 设备指纹
    result.add(HardwareItem(label = labelFingerprint, value = Build.FINGERPRINT))

    return result
}

/// 以 shell 身份执行命令，返回 stdout
/// /proc、/sys 下的文件全局可读；getenforce 等命令也可由普通进程执行
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

/// 读内核版本：优先 /proc/version，回退到 System.getProperty
private fun readKernelInfo(unknownStr: String): String {
    val procVersion = shizukuShell("cat /proc/version").trim()
    if (procVersion.isNotEmpty()) return procVersion
    val osVer = System.getProperty("os.version") ?: ""
    return if (osVer.isNotEmpty()) osVer else unknownStr
}

/// 读 CPU 信息：厂商 + 型号 + 核数 + 频率
private fun readCpuInfo(coresFormat: String, unknownFreq: String, unknownStr: String): String {
    // 1. 厂商与型号：优先 getprop 的 SoC 属性（现代设备最可靠）
    val socManufacturer = shizukuShell("getprop ro.soc.manufacturer").trim()
    val socModel = shizukuShell("getprop ro.soc.model").trim()
    // 回退：/proc/cpuinfo 的 Hardware 行（老设备）或 model name（部分新设备）
    val hardware = shizukuShell("cat /proc/cpuinfo | grep -m1 'Hardware' | cut -d: -f2").trim()
    val modelName = shizukuShell("cat /proc/cpuinfo | grep -m1 'model name' | cut -d: -f2").trim()
    val platform = shizukuShell("getprop ro.board.platform").trim()

    // 组装厂商+型号
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

    // 2. 核数
    val cores = Runtime.getRuntime().availableProcessors()

    // 3. 最大频率
    val maxFreq = shizukuShell("cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq 2>/dev/null").trim()
    val freqStr = if (maxFreq.isNotEmpty() && maxFreq.matches(Regex("\\d+"))) {
        "${maxFreq.toInt() / 1000} MHz"
    } else {
        unknownFreq
    }

    return "$brandStr\n${coresFormat.format(cores, freqStr)}"
}

/// 读 GPU 信息：优先 dumpsys gfxinfo 的 GPU 字段，回退到 Build.SOFTWARE_CODENAME 等属性
/// 不再使用 EGL/GL 上下文（容易在非 GL 线程失败）
private fun readGpuInfo(unknownStr: String): String {
    // dumpsys SurfaceFlinger 中的 GLES 字段行（部分设备）
    val sfGpu = shizukuShell("dumpsys SurfaceFlinger 2>/dev/null | grep -i 'GLES:'").trim()
    if (sfGpu.isNotEmpty()) {
        // 取 "GLES: ..." 里的 renderer 描述
        val cleaned = sfGpu.substringAfter("GLES:", sfGpu).trim()
        if (cleaned.isNotEmpty()) return cleaned
    }
    // 部分设备可从 /sys/class/misc/mali0/device/gpu_model 读到
    val mali = shizukuShell("cat /sys/class/misc/mali0/device/gpu_model 2>/dev/null").trim()
    if (mali.isNotEmpty()) return mali
    // 回退：使用 ro.hardware.egl / ro.opengles.version 属性
    val egl = shizukuShell("getprop ro.hardware.egl").trim()
    val glVer = shizukuShell("getprop ro.opengles.version").trim()
    val verStr = if (glVer.isNotEmpty() && glVer.matches(Regex("\\d+"))) {
        // ro.opengles.version 是整数编码：0x00010000 = 1.0, 0x00030001 = 3.1
        val v = glVer.toInt(16)
        "${v shr 16}.${v and 0xFFFF}"
    } else ""
    val parts = mutableListOf<String>()
    if (egl.isNotEmpty()) parts.add(egl)
    if (verStr.isNotEmpty()) parts.add("GLES $verStr")
    return if (parts.isNotEmpty()) parts.joinToString(" ") else unknownStr
}

/// 读 SELinux 状态：getenforce 返回 Enforcing / Permissive / Disabled
private fun readSelinuxStatus(unknownStr: String): String {
    val status = shizukuShell("getenforce").trim()
    return if (status.isNotEmpty()) status else unknownStr
}
