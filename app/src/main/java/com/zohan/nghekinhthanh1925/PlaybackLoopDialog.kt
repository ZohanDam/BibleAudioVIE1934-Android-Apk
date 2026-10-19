package com.zohan.nghekinhthanh1925

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

class PlaybackLoopDialog(
    private val activity: Activity,
    private val books: List<BibleBook>,
    private val chapters: List<BibleChapter>,
    selection: PlaybackLoopSelection,
    private val initialName: String = "",
    private val nameAvailable: (String) -> Boolean,
    private val onApply: (String, PlaybackLoopSelection) -> Unit
) {
    private val orders = selection.orders.toMutableList()
    private val chaptersByOrder = chapters.associateBy { it.globalOrder }
    private val chaptersByBook = chapters.groupBy { it.bookCode }
    private val bookNames = books.associate { it.code to it.name }
    private val rows = mutableMapOf<String, Pair<CheckBox, Button>>()
    private val palette = activity.obtainStyledAttributes(R.styleable.LoopPalette).let {
        try { intArrayOf(
            it.getColor(R.styleable.LoopPalette_android_textColorPrimary, Color.BLACK),
            it.getColor(R.styleable.LoopPalette_android_textColorSecondary, Color.GRAY),
            it.getColor(R.styleable.LoopPalette_android_colorControlHighlight, Color.LTGRAY)
        ) }
        finally { it.recycle() }
    }
    private val toggle = Switch(activity).apply {
        text = ui("Dùng vòng lặp này", "Use this loop")
        contentDescription = ui("Bật phát lặp", "Enable loop playback")
        isChecked = selection.enabled
        textSize = 16f
        minimumHeight = dp(48)
        setTypeface(typeface, Typeface.BOLD)
    }
    private val preview = TextView(activity).apply {
        textSize = 14f
        setTextColor(palette[0])
        setPadding(0, dp(10), 0, dp(12))
        contentDescription = ui("Thứ tự phát", "Playback order")
        tag = "loopOrderPreview"
        setOnClickListener { showOrder() }
    }
    private var applyButton: Button? = null
    private val name = EditText(activity).apply {
        tag = "loopName"
        hint = ui("Tên vòng lặp", "Loop name")
        contentDescription = hint
        setSingleLine(true)
        filters = arrayOf(InputFilter.LengthFilter(PlaybackLoopStore.MAX_NAME_LENGTH))
        setText(initialName)
        textSize = 16f
    }

    fun show() {
        val content = vertical().apply { setPadding(dp(18), dp(4), dp(18), dp(8)) }
        content.addView(name, matchWrap())
        content.addView(toggle, matchWrap())
        content.addView(TextView(activity).apply {
            text = ui(
                "Tích sách để chọn tất cả chương; bấm số bên phải để chọn từng chương. Phát theo thứ tự đã chọn rồi lặp lại.",
                "Check a book for all its chapters, or tap its count to choose chapters. Repeat in the order selected."
            )
            textSize = 13f
            setTextColor(palette[1])
        }, matchWrap())
        content.addView(preview, matchWrap())
        content.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(smallButton(ui("Chọn tất cả", "Select all")) { addOrders(chapters.map { it.globalOrder }) },
                LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(smallButton(ui("Bỏ chọn tất cả", "Clear selection")) {
                orders.clear()
                refresh()
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
        }, matchWrap())
        books.forEachIndexed { index, book ->
            if (index == 0 || books[index - 1].testament != book.testament) {
                content.addView(TextView(activity).apply {
                    text = if (book.testament == "old") ui("Cựu Ước", "Old Testament") else ui("Tân Ước", "New Testament")
                    textSize = 17f
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(14), 0, dp(6))
                }, matchWrap())
            }
            val check = CheckBox(activity).apply {
                text = book.name
                textSize = 15f
                setTextColor(palette[0])
                buttonTintList = ColorStateList.valueOf(palette[0])
                tag = "loopBook:${book.code}"
                contentDescription = ui("Chọn cả sách ${book.name}", "Select entire book ${book.name}")
                setOnClickListener {
                    val bookOrders = chaptersByBook[book.code].orEmpty().map { it.globalOrder }
                    if (isChecked) addOrders(bookOrders) else {
                        orders.removeAll(bookOrders.toSet())
                        refresh()
                    }
                }
            }
            val count = smallButton("") { showChapters(book) }.apply {
                tag = "loopChapters:${book.code}"
                contentDescription = ui("Chọn chương lặp: ${book.name}", "Choose loop chapters: ${book.name}")
            }
            rows[book.code] = check to count
            content.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(check, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(count, LinearLayout.LayoutParams(dp(82), dp(48)))
            }, matchWrap())
        }
        toggle.setOnCheckedChangeListener { _, _ -> refresh() }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (initialName.isEmpty()) ui("Tạo vòng lặp", "New loop") else ui("Sửa vòng lặp", "Edit loop"))
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton(ui("Hủy", "Cancel"), null)
            .setPositiveButton(ui("Lưu", "Save"), null)
            .create()
        dialog.show()
        applyButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            setOnClickListener {
                val cleanName = name.text.toString().trim()
                if (cleanName.isEmpty()) {
                    name.error = ui("Nhập tên vòng lặp", "Enter a loop name")
                    name.requestFocus()
                    return@setOnClickListener
                }
                if (!nameAvailable(cleanName)) {
                    name.error = ui("Tên này đã được dùng", "This name is already used")
                    name.requestFocus()
                    return@setOnClickListener
                }
                onApply(cleanName, PlaybackLoopSelection(toggle.isChecked, orders.toList()))
                dialog.dismiss()
            }
        }
        refresh()
    }

    private fun addOrders(additions: List<Int>) {
        val selected = orders.toHashSet()
        orders.addAll(additions.filter { selected.add(it) })
        refresh()
    }

    private fun refresh() {
        val selected = orders.toHashSet()
        rows.forEach { (code, row) ->
            val all = chaptersByBook[code].orEmpty()
            val count = all.count { it.globalOrder in selected }
            row.first.isChecked = all.isNotEmpty() && count == all.size
            row.first.setTypeface(row.first.typeface, if (count > 0) Typeface.BOLD else Typeface.NORMAL)
            row.second.text = String.format(Locale.ROOT, "%d/%d ▾", count, all.size)
        }
        val labels = orderLabels()
        preview.text = if (orders.isEmpty()) ui("Chưa chọn chương nào.", "No chapters selected.") else {
            ui("${orders.size} chương · Audio ${chapters.first().audioVersion}\n", "${orders.size} chapters · Audio ${chapters.first().audioVersion}\n") +
                labels.take(3).joinToString("\n") + if (labels.size > 3) "\n…" else ""
        }
        preview.isEnabled = orders.isNotEmpty()
        applyButton?.isEnabled = orders.isNotEmpty()
    }

    private fun showChapters(book: BibleBook) {
        val grid = GridLayout(activity).apply { columnCount = 5 }
        val chapterButtons = mutableMapOf<Int, Button>()
        fun refreshButtons() {
            val selected = orders.toHashSet()
            chapterButtons.forEach { (order, button) ->
                button.isSelected = order in selected
                button.background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(if (button.isSelected) palette[2] else Color.TRANSPARENT)
                    setStroke(dp(1), if (button.isSelected) palette[0] else palette[2])
                }
            }
        }
        chaptersByBook[book.code].orEmpty().forEach { chapter ->
            val button = smallButton(String.format(Locale.ROOT, "%d", chapter.chapter)) {
                if (chapter.globalOrder in orders) {
                    orders.remove(chapter.globalOrder)
                    refresh()
                } else addOrders(listOf(chapter.globalOrder))
                refreshButtons()
            }.apply {
                tag = "loopChapter:${chapter.globalOrder}"
                contentDescription = "${book.name} ${chapter.chapter}"
                setTypeface(typeface, Typeface.BOLD)
            }
            chapterButtons[chapter.globalOrder] = button
            grid.addView(button, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(48)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        refreshButtons()
        AlertDialog.Builder(activity)
            .setTitle(book.name)
            .setView(ScrollView(activity).apply {
                setPadding(dp(10), dp(4), dp(10), dp(4))
                addView(grid)
            })
            .setPositiveButton(ui("Xong", "Done"), null)
            .show()
    }

    private fun orderLabels(): List<String> {
        val groups = mutableListOf<String>()
        var first: BibleChapter? = null
        var last: BibleChapter? = null
        fun finish() {
            val start = first ?: return
            val end = last ?: return
            val number = if (start.chapter == end.chapter) "${start.chapter}" else "${start.chapter}–${end.chapter}"
            groups.add("${groups.size + 1}. ${bookNames[start.bookCode]} $number")
        }
        orders.mapNotNull { chaptersByOrder[it] }.forEach { chapter ->
            if (last?.bookCode != chapter.bookCode || last!!.chapter + 1 != chapter.chapter) {
                finish()
                first = chapter
            }
            last = chapter
        }
        finish()
        return groups
    }

    private fun showOrder() {
        AlertDialog.Builder(activity)
            .setTitle(ui("Thứ tự phát", "Playback order"))
            .setView(ScrollView(activity).apply {
                addView(TextView(activity).apply {
                    text = orderLabels().joinToString("\n\n")
                    textSize = 16f
                    setPadding(dp(22), dp(8), dp(22), dp(12))
                })
            })
            .setPositiveButton(ui("Đóng", "Close"), null)
            .show()
    }

    private fun smallButton(label: String, click: () -> Unit) = Button(activity).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setTextColor(palette[0])
        background = null
        setPadding(dp(3), 0, dp(3), 0)
        setOnClickListener { click() }
    }

    private fun vertical() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun ui(vi: String, en: String) = UiLanguage.text(activity, vi, en)
}
