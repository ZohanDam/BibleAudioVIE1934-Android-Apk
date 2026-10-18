package com.zohan.nghekinhthanh1925

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

object SleepTimer {
    private fun state(context: Context) = context.getSharedPreferences("state", Context.MODE_PRIVATE)
    private fun boot(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)

    fun restore(context: Context) {
        val state = state(context)
        val deadline = state.getLong("sleepDeadlineElapsed", 0)
        if (deadline > 0 && state.getInt("sleepBoot", -1) != boot(context)) {
            cancel(context)
            state.edit().putBoolean("wasPlaying", false).apply()
            return
        }
        PlaybackStateStore.sleepDeadlineElapsed = deadline
        PlaybackStateStore.sleepMinutes = state.getInt("sleepMinutes", 0)
    }

    fun start(context: Context, minutes: Int) {
        val duration = minutes.coerceIn(0, 480)
        if (duration == 0) {
            cancel(context)
            return
        }
        val deadline = SystemClock.elapsedRealtime() + duration * 60_000L
        PlaybackStateStore.sleepDeadlineElapsed = deadline
        PlaybackStateStore.sleepMinutes = duration
        state(context).edit().putLong("sleepDeadlineElapsed", deadline)
            .putInt("sleepMinutes", duration).putInt("sleepBoot", boot(context)).apply()
    }

    fun cancel(context: Context) {
        PlaybackStateStore.sleepDeadlineElapsed = 0
        PlaybackStateStore.sleepMinutes = 0
        state(context).edit().remove("sleepDeadlineElapsed").remove("sleepMinutes").remove("sleepBoot").apply()
    }

    fun remainingMs(): Long = if (PlaybackStateStore.sleepDeadlineElapsed > 0) {
        (PlaybackStateStore.sleepDeadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    } else 0

    fun remainingMinutes(): Long = (remainingMs() + 59_999) / 60_000

    fun countdown(context: Context): String {
        val minutes = remainingMinutes()
        return UiLanguage.text(context, "$minutes ph", "$minutes min")
    }

    fun nextDisplayDelayMs(): Long {
        val remaining = remainingMs()
        if (remaining == 0L) return 60_000
        return ((remaining - 1) % 60_000) + 1
    }
}
