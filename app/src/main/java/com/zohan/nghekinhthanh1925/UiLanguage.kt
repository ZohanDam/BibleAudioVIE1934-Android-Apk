package com.zohan.nghekinhthanh1925

import android.content.Context

object UiLanguage {
    fun isEnglish(context: Context): Boolean {
        val state = context.getSharedPreferences("state", Context.MODE_PRIVATE)
        return state.getString("uiLanguage", null)?.let { it == "en" }
            ?: (state.getString("textVersion", "VIE1925") == "NIV")
    }

    fun set(context: Context, english: Boolean) {
        context.getSharedPreferences("state", Context.MODE_PRIVATE).edit()
            .putString("uiLanguage", if (english) "en" else "vi").apply()
    }

    fun text(context: Context, vietnamese: String, english: String): String =
        if (isEnglish(context)) english else vietnamese
}
