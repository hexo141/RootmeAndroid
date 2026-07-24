package com.hexo141.rootmeandroid

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/// 全局 UI 缩放管理器
object ScaleManager {
    private const val MIN = 0.5f
    private const val MAX = 2.0f
    private const val DEFAULT = 1.0f

    var current by mutableFloatStateOf(DEFAULT)

    val range: ClosedFloatingPointRange<Float> get() = MIN..MAX

    val default: Float get() = DEFAULT

    /** 重置为默认缩放 */
    fun reset() {
        current = DEFAULT
    }
}