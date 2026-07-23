package com.hexo141.rootmeandroid

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/// 支持的语言模式
enum class LanguageMode(val tag: String, val display: String) {
    ZH("zh", "中文"),
    EN("en", "English")
}

/// 全局语言状态：通过 mutableStateOf 触发 Compose 重组
object LanguageManager {
    var current by mutableStateOf(loadInitial())

    private fun loadInitial(): LanguageMode {
        // 默认中文（可扩展为持久化读取）
        return LanguageMode.ZH
    }

    /** 切换语言 */
    fun setLanguage(mode: LanguageMode) {
        if (mode != current) current = mode
    }
}

/// 根据 LanguageManager.current 构造对应 locale 的 Context
@Composable
fun localizedContext(): Context {
    val appCtx = LocalContext.current
    val mode = LanguageManager.current
    val locale = Locale(mode.tag)
    Locale.setDefault(locale)
    val config = Configuration(appCtx.resources.configuration).apply {
        setLocale(locale)
    }
    return appCtx.createConfigurationContext(config)
}

/// 取当前语言下的字符串
@Composable
fun stringRes(id: Int): String {
    return localizedContext().getString(id)
}

/// 取当前语言下的格式化字符串
@Composable
fun stringRes(id: Int, vararg formatArgs: Any): String {
    return localizedContext().getString(id, *formatArgs)
}
