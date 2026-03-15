package com.bohanli.ruzhtranslator.core

sealed class AppStatus {
    data class Loading(val message: String) : AppStatus()
    object Ready : AppStatus()
    object Listening : AppStatus()
    object Translating : AppStatus()
    data class Error(val message: String) : AppStatus()

    fun toDisplayString(): String = when (this) {
        is Loading -> "[加载] $message"
        Ready -> "[就绪] 点击按钮开始监听"
        Listening -> "[监听] 正在识别俄语..."
        Translating -> "[翻译] 正在翻译..."
        is Error -> "[错误] $message"
    }
}
