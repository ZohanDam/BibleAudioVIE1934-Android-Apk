package com.zohan.nghekinhthanh1925

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class PlaybackLoopPresetsDialog(
    private val activity: Activity,
    private val books: List<BibleBook>,
    private val chapters: List<BibleChapter>,
    private val store: PlaybackLoopStore,
    private val canActivate: () -> Boolean,
    private val onPlaybackChanged: (PlaybackLoopSelection, PlaybackLoopSelection) -> Unit
) {
    private val version = chapters.first().audioVersion
    private val palette = activity.obtainStyledAttributes(R.styleable.LoopPalette).let {
        try { intArrayOf(
            it.getColor(R.styleable.LoopPalette_android_textColorPrimary, Color.BLACK),
            it.getColor(R.styleable.LoopPalette_android_textColorSecondary, Color.GRAY)
        ) }
        finally { it.recycle() }
    }
    private val list = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(4), dp(16), dp(8))
    }

    fun show() {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(ui("Phát lặp", "Loop playback"))
            .setView(ScrollView(activity).apply { addView(list) })
            .setNegativeButton(ui("Đóng", "Close"), null)
            .setPositiveButton(ui("+ Tạo mới", "+ New loop"), null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { edit(null) }
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        list.addView(label(ui("Audio $version · Chọn một vòng lặp để dùng.",
            "Audio $version · Select one loop to use."), 13f, muted = true).apply {
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        val enabled = store.load(version).enabled
        val selectedId = store.selectedPresetId(version)
        list.addView(RadioButton(activity).apply {
            text = ui("Tắt phát lặp", "Loop off")
            textSize = 15f
            tag = "loopOff"
            isChecked = !enabled
            minimumHeight = dp(48)
            setTextColor(palette[0])
            buttonTintList = ColorStateList.valueOf(palette[0])
            setOnClickListener { changePlayback { store.disable(version) } }
        })
        val presets = store.presets(version)
        if (presets.isEmpty()) list.addView(label(ui(
            "Chưa có vòng lặp. Tạo mới để chọn sách hoặc chương.",
            "No saved loops. Create one to choose books or chapters."
        ), 14f, muted = true).apply { setPadding(dp(4), dp(12), dp(4), dp(16)) })
        presets.forEach { preset ->
            val active = enabled && selectedId == preset.id
            val row = LinearLayout(activity).apply {
                tag = "loopPreset:${preset.id}"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                contentDescription = preset.name
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (checkEdition()) changePlayback { store.activatePreset(version, preset.id) }
                    else refresh()
                }
            }
            row.addView(RadioButton(activity).apply {
                isChecked = active
                isClickable = false
                isFocusable = false
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                buttonTintList = ColorStateList.valueOf(palette[0])
            }, LinearLayout.LayoutParams(dp(40), dp(48)))
            row.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), dp(6), dp(8))
                addView(label(preset.name, 16f).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTypeface(typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
                })
                addView(label(ui("${preset.orders.size} chương", "${preset.orders.size} chapters"), 12f, muted = true))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(activity).apply {
                text = "⋮"
                textSize = 24f
                tag = "loopOptions:${preset.id}"
                contentDescription = ui("Tùy chọn: ${preset.name}", "Options: ${preset.name}")
                setTextColor(palette[0])
                background = null
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener { options(preset) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            list.addView(row)
        }
    }

    private fun options(preset: PlaybackLoopPreset) {
        AlertDialog.Builder(activity).setTitle(preset.name)
            .setItems(arrayOf(ui("Sửa lựa chọn", "Edit selection"), ui("Đổi tên", "Rename"), ui("Xóa", "Delete"))) { _, which ->
                when (which) {
                    0 -> edit(preset)
                    1 -> rename(preset)
                    2 -> AlertDialog.Builder(activity).setTitle(ui("Xóa vòng lặp?", "Delete loop?"))
                        .setMessage(preset.name)
                        .setNegativeButton(ui("Hủy", "Cancel"), null)
                        .setPositiveButton(ui("Xóa", "Delete")) { _, _ -> changePlayback { store.deletePreset(version, preset.id) } }
                        .show()
                }
            }.show()
    }

    private fun edit(preset: PlaybackLoopPreset?) {
        val enabled = preset == null || (store.load(version).enabled && store.selectedPresetId(version) == preset.id)
        PlaybackLoopDialog(activity, books, chapters,
            PlaybackLoopSelection(enabled, preset?.orders.orEmpty()), preset?.name.orEmpty(),
            nameAvailable = { name -> nameAvailable(name, preset?.id) },
            onApply = { name, selection ->
                val before = store.load(version)
                val saved = store.savePreset(version, name, selection.orders, preset?.id)
                if (selection.enabled && checkEdition()) store.activatePreset(version, saved.id)
                else if (store.selectedPresetId(version) == saved.id) store.disable(version)
                onPlaybackChanged(before, store.load(version))
                refresh()
            }).show()
    }

    private fun rename(preset: PlaybackLoopPreset) {
        val name = EditText(activity).apply {
            tag = "loopRename"
            hint = ui("Tên vòng lặp", "Loop name")
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(PlaybackLoopStore.MAX_NAME_LENGTH))
            setText(preset.name)
            selectAll()
        }
        val wrapper = LinearLayout(activity).apply {
            setPadding(dp(20), dp(4), dp(20), dp(4))
            addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(activity).setTitle(ui("Đổi tên", "Rename"))
            .setView(wrapper).setNegativeButton(ui("Hủy", "Cancel"), null)
            .setPositiveButton(ui("Lưu", "Save"), null).create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val cleanName = name.text.toString().trim()
            if (cleanName.isEmpty() || !nameAvailable(cleanName, preset.id)) {
                name.error = if (cleanName.isEmpty()) ui("Nhập tên vòng lặp", "Enter a loop name")
                    else ui("Tên này đã được dùng", "This name is already used")
            } else {
                store.savePreset(version, cleanName, preset.orders, preset.id)
                refresh()
                dialog.dismiss()
            }
        }
    }

    private fun nameAvailable(name: String, id: String?) =
        store.presets(version).none { it.id != id && it.name.equals(name, ignoreCase = true) }

    private fun checkEdition(): Boolean {
        if (canActivate()) return true
        Toast.makeText(activity, ui("Bản audio đã đổi. Mở lại mục phát lặp.",
            "Audio edition changed. Reopen loop playback."), Toast.LENGTH_SHORT).show()
        return false
    }

    private fun changePlayback(action: () -> Unit) {
        val before = store.load(version)
        action()
        onPlaybackChanged(before, store.load(version))
        refresh()
    }

    private fun label(value: String, size: Float, muted: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(palette[if (muted) 1 else 0])
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun ui(vi: String, en: String) = UiLanguage.text(activity, vi, en)
}
