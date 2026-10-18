package com.zohan.nghekinhthanh1925

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    private lateinit var repository: BibleRepository
    private lateinit var downloads: AudioDownloadStore
    private lateinit var appUpdates: AppUpdateController
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var books: List<BibleBook>
    private lateinit var chapters: List<BibleChapter>
    private lateinit var currentChapter: BibleChapter
    private lateinit var textVersions: List<BibleTextVersion>
    private var selectedTextVersion = "VIE1925"

    private var fontSize = 20f
    private var playerBookView: TextView? = null
    private var playerChapterView: TextView? = null
    private var playerOfflineView: TextView? = null
    private var playerPositionView: TextView? = null
    private var playerDurationView: TextView? = null
    private var playerSpeedView: TextView? = null
    private var playerPlayButton: ImageButton? = null
    private var playerProgressView: SeekBar? = null
    private var playerBarView: View? = null
    private var compactNavigationView: View? = null
    private var compactPreviousView: ImageButton? = null
    private var compactNextView: ImageButton? = null
    private var playerPanelToggleView: View? = null
    private var playerPanelVisible = true
    private var isSeeking = false
    private var darkMode = false
    private var readerScrollView: ScrollView? = null

    private val colorBackground get() = if (darkMode) Color.rgb(18, 18, 18) else Color.rgb(250, 250, 250)
    private val colorSurface get() = if (darkMode) Color.rgb(30, 30, 30) else Color.WHITE
    private val colorText get() = if (darkMode) Color.rgb(238, 238, 238) else Color.rgb(31, 31, 31)
    private val colorMuted get() = if (darkMode) Color.rgb(183, 183, 183) else Color.rgb(103, 103, 103)
    private val colorBorder get() = if (darkMode) Color.rgb(66, 66, 66) else Color.rgb(226, 226, 226)
    private val colorSelection get() = if (darkMode) Color.rgb(65, 65, 65) else Color.rgb(230, 230, 230)
    private val colorAccent = Color.rgb(190, 40, 45)
    private val colorEmphasis get() = if (darkMode) Color.rgb(255, 156, 159) else colorAccent

    override fun onCreate(savedInstanceState: Bundle?) {
        preferences = getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)
        applyReaderTheme()
        super.onCreate(savedInstanceState)
        appUpdates = AppUpdateController(this)
        repository = BibleRepository(applicationContext)
        downloads = AudioDownloadStore(applicationContext)
        textVersions = repository.textVersions()
        selectedTextVersion = preferences.getString(KEY_TEXT_VERSION, "VIE1925")
            ?.takeIf { code -> textVersions.any { it.code == code } } ?: "VIE1925"
        if ((PlaybackStateStore.isPlaying || PlaybackStateStore.playWhenReady) &&
            audioVersionForText(selectedTextVersion) != PlaybackStateStore.audioVersion) {
            selectedTextVersion = PlaybackStateStore.audioVersion
        }
        books = repository.books(selectedTextVersion)
        chapters = repository.chapters(audioVersionForText(selectedTextVersion))
        fontSize = preferences.getFloat(KEY_FONT_SIZE, 20f)
        playerPanelVisible = preferences.getBoolean(KEY_PLAYER_PANEL_VISIBLE, true)
        val initialOrder = PlaybackStateStore.currentOrder.takeIf { it > 0 } ?: preferences.getInt(
            KEY_LAST_ORDER,
            preferences.getInt(KEY_READER_ORDER, 1)
        )
        currentChapter = chapters.firstOrNull { it.globalOrder == initialOrder } ?: chapters.first()
        PlaybackStateStore.currentOrder = currentChapter.globalOrder
        PlaybackStateStore.audioVersion = currentChapter.audioVersion
        preferences.edit().putString(KEY_AUDIO_VERSION, currentChapter.audioVersion).apply()
        PlaybackStateStore.speed = preferences.getFloat(KEY_SPEED, 1.0f)
        requestNotificationPermission()
        downloads.resumeIfNeeded()
        renderReader()
    }

    override fun onResume() {
        super.onResume()
        appUpdates.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        if (::appUpdates.isInitialized) appUpdates.close()
        handler.removeCallbacksAndMessages(null)
        backgroundExecutor.shutdownNow()
        repository.close()
        super.onDestroy()
    }

    private fun renderReader() {
        preferences.edit().putInt(KEY_READER_ORDER, currentChapter.globalOrder).apply()
        val root = vertical().apply {
            setBackgroundColor(colorBackground)
            setOnApplyWindowInsetsListener { view, insets ->
                val topInset: Int
                val bottomInset: Int
                if (Build.VERSION.SDK_INT >= 30) {
                    val systemInsets = insets.getInsets(
                        WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                    )
                    topInset = systemInsets.top
                    bottomInset = systemInsets.bottom
                } else {
                    @Suppress("DEPRECATION")
                    topInset = insets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    bottomInset = insets.systemWindowInsetBottom
                }
                view.setPadding(0, topInset, 0, bottomInset)
                insets
            }
        }
        val toolbar = readerToolbar()
        root.addView(toolbar, matchWrap())
        root.addView(readerContent(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        playerBarView = playerBar().apply {
            visibility = if (playerPanelVisible) View.VISIBLE else View.GONE
        }
        root.addView(playerBarView, matchWrap())
        compactNavigationView = compactChapterNavigation().apply {
            visibility = if (playerPanelVisible) View.GONE else View.VISIBLE
        }
        root.addView(compactNavigationView, matchWrap())
        setContentView(root)
        // The system-bar controller requires the decor view created by setContentView.
        updateSystemBars()
        root.requestApplyInsets()
        updatePlayerBar()
    }

    private fun readerToolbar(): View {
        return horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(4), dp(6), dp(4))
            setBackgroundColor(colorSurface)
            elevation = dp(2).toFloat()
            addView(selectorChip("Sách") { showBookPicker() }.also {
                it.contentDescription = "Chọn sách"
                playerBookView = it
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })
            addView(selectorChip("1") {
                books.firstOrNull { it.code == currentChapter.bookCode }?.let(::showChapterPicker)
            }.also {
                it.contentDescription = "Chọn chương"
                playerChapterView = it
            }, LinearLayout.LayoutParams(dp(50), dp(40)).apply { marginEnd = dp(2) })
            addView(speakerStatusButton(), LinearLayout.LayoutParams(dp(44), dp(42)).apply {
                marginEnd = dp(2)
            })
            addView(downloadStatusButton(), LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                marginEnd = dp(2)
            })
            addView(plainIconButton(R.drawable.ic_settings, editionLabel("Cài đặt", "Settings")) {
                showToolbarMenu(it)
            }, LinearLayout.LayoutParams(dp(46), dp(46)))
        }
    }

    private fun readerContent(): View {
        val content = vertical().apply {
            setPadding(dp(22), dp(24), dp(22), dp(32))
        }
        content.addView(TextView(this).apply {
            text = readerBookName(currentChapter.bookCode)
            textSize = 25f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorText)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        })
        content.addView(TextView(this).apply {
            text = getString(
                if (selectedTextVersion == "NIV") R.string.chapter_heading_en else R.string.chapter_heading_vi,
                currentChapter.chapter
            )
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorMuted)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        })
        content.addView(horizontal().apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(18))
            addView(TextView(this@MainActivity).apply {
                text = "$selectedTextVersion  ▾"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colorMuted)
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                contentDescription = "Chọn bản dịch"
                setOnClickListener { showTextVersionDialog() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        })
        val verses = repository.verses(
            currentChapter.bookCode, currentChapter.chapter, selectedTextVersion
        )
        content.addView(TextView(this).apply {
            text = buildVerseText(verses)
            textSize = fontSize
            setTextColor(colorText)
            setLineSpacing(dp(6).toFloat(), 1.12f)
            setTextIsSelectable(true)
        })
        return ScrollView(this).apply {
            isFillViewport = true
            addView(content)
            readerScrollView = this
        }
    }

    private fun buildVerseText(verses: List<BibleVerse>): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        verses.forEachIndexed { index, verse ->
            val start = builder.length
            builder.append(verse.number)
            val end = builder.length
            builder.setSpan(
                RelativeSizeSpan(0.7f),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.setSpan(
                ForegroundColorSpan(colorEmphasis),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            builder.append("  ")
            builder.append(verse.text)
            if (index != verses.lastIndex) builder.append("\n\n")
        }
        return builder
    }

    private fun playerBar(): View {
        val container = vertical().apply {
            setBackgroundColor(colorSurface)
            elevation = dp(10).toFloat()
        }
        if (selectedTextVersion == "VIE2010") {
            container.addView(TextView(this).apply {
                text = "Audio: VIE1925"
                textSize = 11f
                setTextColor(colorMuted)
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, 0)
            }, matchWrap())
        }
        val timeline = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), 0)
        }
        playerPositionView = timelineLabel(Gravity.START)
        playerDurationView = timelineLabel(Gravity.END)
        timeline.addView(playerPositionView, LinearLayout.LayoutParams(dp(46), dp(34)))
        playerProgressView = SeekBar(this).apply {
            max = max(PlaybackStateStore.durationMs, 1)
            progress = PlaybackStateStore.positionMs.coerceIn(0, max)
            progressTintList = android.content.res.ColorStateList.valueOf(colorAccent)
            thumbTintList = android.content.res.ColorStateList.valueOf(colorAccent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(colorBorder)
            setPadding(dp(14), 0, dp(14), 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) = Unit

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val position = seekBar?.progress ?: 0
                    sendPlayerAction(AudioPlayerService.ACTION_SEEK) {
                        putExtra(AudioPlayerService.EXTRA_POSITION, position)
                    }
                    isSeeking = false
                }
            })
        }
        timeline.addView(playerProgressView, LinearLayout.LayoutParams(0, dp(34), 1f))
        timeline.addView(playerDurationView, LinearLayout.LayoutParams(dp(46), dp(34)))
        timeline.addView(plainIconButton(R.drawable.ic_collapse, editionLabel(
            "Thu gọn bảng điều khiển", "Collapse audio controls"
        )) { togglePlayerPanel() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        container.addView(timeline)

        val transport = horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        playerSpeedView = transportOptionButton(formatSpeed(PlaybackStateStore.speed), "Tốc độ") {
            showSpeedDialog()
        }
        transport.addView(playerSpeedView, LinearLayout.LayoutParams(dp(58), dp(54)))
        transport.addView(chapterButton(previous = true) {
            navigateChapter(-1)
        }, LinearLayout.LayoutParams(0, dp(54), 1f))
        playerPlayButton = mediaButton(
            if (PlaybackStateStore.isPlaying) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            },
            "Phát hoặc tạm dừng",
            primary = true,
            large = true
        ) { togglePlayback() }
        transport.addView(playerPlayButton)
        transport.addView(chapterButton(previous = false) {
            navigateChapter(1)
        }, LinearLayout.LayoutParams(0, dp(54), 1f))
        transport.addView(transportOptionButton("⏱", "Hẹn giờ") {
            showSleepTimerDialog()
        }, LinearLayout.LayoutParams(dp(58), dp(54)))
        container.addView(transport)
        return container
    }

    private fun compactChapterNavigation(): View = horizontal().apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        setBackgroundColor(colorSurface)
        compactPreviousView = chapterButton(previous = true) { navigateChapter(-1) }
        compactNextView = chapterButton(previous = false) { navigateChapter(1) }
        addView(compactPreviousView, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
        addView(compactNextView, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    private fun togglePlayback() {
        if (PlaybackStateStore.playWhenReady || PlaybackStateStore.isPlaying) {
            PlaybackStateStore.playWhenReady = false
            PlaybackStateStore.isPlaying = false
            sendPlayerAction(AudioPlayerService.ACTION_PAUSE)
        } else {
            PlaybackStateStore.playWhenReady = true
            if (PlaybackStateStore.currentOrder == currentChapter.globalOrder &&
                PlaybackStateStore.audioVersion == currentChapter.audioVersion &&
                PlaybackStateStore.durationMs > 0) {
                sendPlayerAction(AudioPlayerService.ACTION_RESUME)
            } else {
                playChapter(currentChapter)
            }
        }
        handler.postDelayed({ updatePlayerBar() }, 250)
    }

    private fun playChapter(chapter: BibleChapter) {
        sendPlayerAction(AudioPlayerService.ACTION_PLAY_ORDER) {
            putExtra(AudioPlayerService.EXTRA_ORDER, chapter.globalOrder)
            putExtra(AudioPlayerService.EXTRA_AUDIO_VERSION, chapter.audioVersion)
            putExtra(AudioPlayerService.EXTRA_AUTO_PLAY, true)
            putExtra(AudioPlayerService.EXTRA_START_FROM_BEGINNING, true)
        }
        handler.postDelayed({ updatePlayerBar() }, 250)
    }

    private fun navigateChapter(offset: Int) {
        val index = currentChapter.globalOrder - 1 + offset
        val chapter = chapters.getOrNull(index) ?: return
        selectChapter(chapter)
    }

    private fun selectChapter(chapter: BibleChapter) {
        if (chapter.globalOrder == currentChapter.globalOrder && chapter.audioVersion == currentChapter.audioVersion) return
        val continuePlaying = PlaybackStateStore.playWhenReady || PlaybackStateStore.isPlaying
        currentChapter = chapter
        PlaybackStateStore.audioVersion = chapter.audioVersion
        PlaybackStateStore.playWhenReady = continuePlaying
        PlaybackStateStore.currentOrder = chapter.globalOrder
        PlaybackStateStore.positionMs = 0
        PlaybackStateStore.durationMs = 0
        PlaybackStateStore.isPlaying = false
        preferences.edit().putInt(KEY_LAST_ORDER, chapter.globalOrder)
            .putString(KEY_AUDIO_VERSION, chapter.audioVersion).apply()
        renderReader()
        sendPlayerAction(if (continuePlaying) AudioPlayerService.ACTION_PLAY_ORDER else AudioPlayerService.ACTION_SELECT_ORDER) {
            putExtra(AudioPlayerService.EXTRA_ORDER, chapter.globalOrder)
            putExtra(AudioPlayerService.EXTRA_AUDIO_VERSION, chapter.audioVersion)
            putExtra(AudioPlayerService.EXTRA_AUTO_PLAY, continuePlaying)
            putExtra(AudioPlayerService.EXTRA_START_FROM_BEGINNING, true)
        }
    }

    private fun showBookPicker() {
        var dialog: AlertDialog? = null
        val bookList = vertical().apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        fun addSection(title: String, sectionBooks: List<BibleBook>) {
            bookList.addView(TextView(this).apply {
                text = title
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(13), dp(14), dp(13))
                background = rounded(colorAccent, null, 8)
            }, matchWrap().apply {
                setMargins(dp(4), dp(10), dp(4), dp(5))
            })

            sectionBooks.forEach { book ->
                val active = book.code == currentChapter.bookCode
                bookList.addView(pickerChoice(book.name, active) {
                    val chapter = chapters.firstOrNull { it.bookCode == book.code && it.chapter == 1 }
                        ?: return@pickerChoice
                    dialog?.dismiss()
                    selectChapter(chapter)
                }, matchWrap())
                bookList.addView(View(this).apply {
                    setBackgroundColor(colorBorder)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(1)
                ))
            }
        }

        addSection("Cựu Ước", books.filter { it.testament == "old" })
        addSection("Tân Ước", books.filter { it.testament != "old" })

        dialog = AlertDialog.Builder(this)
            .setCustomTitle(TextView(this).apply {
                text = "Chọn sách"
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colorText)
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(20), dp(20), dp(12))
            })
            .setView(ScrollView(this).apply { addView(bookList) })
            .setNegativeButton("Đóng", null)
            .create()
        dialog.show()
    }

    private fun showChapterPicker(book: BibleBook) {
        var dialog: AlertDialog? = null
        val grid = GridLayout(this).apply {
            columnCount = 5
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }

        repeat(book.chapterCount) { index ->
            val chapterNumber = index + 1
            val active = book.code == currentChapter.bookCode &&
                chapterNumber == currentChapter.chapter
            val button = Button(this).apply {
                text = chapterNumber.toString()
                textSize = 14f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (active) Color.WHITE else colorText)
                background = if (active) {
                    rounded(colorAccent, null, 7)
                } else {
                    rounded(colorBackground, colorBorder, 7)
                }
                setOnClickListener {
                    val chapter = chapters.firstOrNull { it.bookCode == book.code && it.chapter == chapterNumber }
                        ?: return@setOnClickListener
                    dialog?.dismiss()
                    selectChapter(chapter)
                }
            }
            grid.addView(button, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(48)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }

        val content = vertical().apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(grid, matchWrap())
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(book.name)
            .setView(ScrollView(this).apply { addView(content) })
            .setNeutralButton("Tải sách") { _, _ ->
                enqueueDownloads(
                    chapters.filter { it.bookCode == book.code },
                    book.name
                )
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showDownloadsDialog() {
        val summary = downloads.summary(chapters)
        val currentBookChapters = chapters.filter { it.bookCode == currentChapter.bookCode }
        val currentBookSummary = downloads.summary(currentBookChapters)
        val content = vertical().apply {
            setPadding(dp(20), dp(10), dp(20), dp(8))
        }
        content.addView(dialogTitle("Tải audio ${currentChapter.audioVersion}"))
        content.addView(statusCard(
            "${summary.downloaded} / ${chapters.size} chương",
            "${formatBytes(summary.downloadedBytes)} đã lưu" +
                if (summary.running + summary.queued > 0) {
                    "  •  ${summary.running + summary.queued} đang chờ/tải"
                } else {
                    ""
                }
        ))
        content.addView(primaryButton(
            if (downloads.availableFile(currentChapter) != null) {
                "Chương này đã tải"
            } else {
                "Tải ${currentChapter.bookName} ${currentChapter.chapter}"
            }
        ) {
            enqueueDownloads(listOf(currentChapter), "${currentChapter.bookName} ${currentChapter.chapter}")
        })
        content.addView(outlinedButton(
            "Tải sách ${currentChapter.bookName} " +
                "(${currentBookSummary.downloaded}/${currentBookChapters.size})"
        ) {
            enqueueDownloads(currentBookChapters, currentChapter.bookName)
        })
        content.addView(primaryButton(
            "Tải toàn bộ Kinh Thánh (${summary.downloaded}/${chapters.size})"
        ) {
            confirmFullDownload()
        })
        if (summary.running + summary.queued > 0) {
            content.addView(outlinedButton("Dừng các mục đang tải") {
                runBackgroundAction(
                    action = { downloads.cancelPending(chapters) },
                    message = { "Đã dừng $it mục tải." }
                )
            })
        }
        content.addView(sectionLabel("Quản lý bộ nhớ"))
        content.addView(textButton("Xóa audio của sách hiện tại") {
            confirmDelete(currentBookChapters, currentChapter.bookName)
        })
        content.addView(textButton("Xóa toàn bộ audio ${currentChapter.audioVersion} đã tải") {
            confirmDelete(chapters, "toàn bộ ${currentChapter.audioVersion}")
        })
        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun confirmFullDownload() {
        val missing = chapters.count { downloads.availableFile(it) == null }
        if (missing == 0) {
            toast("Toàn bộ audio đã được tải.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Tải toàn bộ ${currentChapter.audioVersion}?")
            .setMessage(
                "Còn $missing chương. " + audioStorageNote() + " " +
                    "Quá trình tải tiếp tục khi đóng ứng dụng."
            )
            .setPositiveButton("Bắt đầu tải") { _, _ ->
                enqueueDownloads(chapters, "toàn bộ Kinh Thánh")
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun confirmDelete(items: List<BibleChapter>, label: String) {
        AlertDialog.Builder(this)
            .setTitle("Xóa audio?")
            .setMessage("Các tệp của $label sẽ bị xóa khỏi thiết bị.")
            .setPositiveButton("Xóa") { _, _ ->
                runBackgroundAction(
                    action = { downloads.delete(items) },
                    message = { "Đã xóa $it chương." }
                )
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun enqueueDownloads(items: List<BibleChapter>, label: String) {
        runBackgroundAction(
            action = { downloads.enqueue(items) },
            message = {
                if (it == 0) "$label đã có sẵn hoặc đang tải."
                else "Đã thêm $it chương của $label vào hàng đợi."
            }
        )
    }

    private fun runBackgroundAction(action: () -> Int, message: (Int) -> String) {
        toast("Đang xử lý...")
        backgroundExecutor.execute {
            val result = action()
            handler.post {
                toast(message(result))
            }
        }
    }

    private fun showSpeedDialog() {
        val labels = speeds.map(::formatSpeed).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Tốc độ phát")
            .setSingleChoiceItems(labels, speeds.indexOf(PlaybackStateStore.speed)) { dialog, index ->
                val speed = speeds[index]
                PlaybackStateStore.speed = speed
                preferences.edit().putFloat(KEY_SPEED, speed).apply()
                sendPlayerAction(AudioPlayerService.ACTION_SPEED) {
                    putExtra(AudioPlayerService.EXTRA_SPEED, speed)
                }
                dialog.dismiss()
                updatePlayerBar()
            }
            .show()
    }

    private fun showSleepTimerDialog() {
        val labels = arrayOf(
            "15 phút",
            "30 phút",
            "60 phút",
            "90 phút",
            "120 phút",
            "240 phút",
            "480 phút",
            "Tắt hẹn giờ"
        )
        val values = intArrayOf(15, 30, 60, 90, 120, 240, 480, 0)
        AlertDialog.Builder(this)
            .setTitle("Hẹn giờ tắt")
            .setItems(labels) { _, index ->
                sendPlayerAction(AudioPlayerService.ACTION_SLEEP) {
                    putExtra(AudioPlayerService.EXTRA_MINUTES, values[index])
                }
                toast(if (values[index] == 0) "Đã tắt hẹn giờ." else "Sẽ tắt sau ${values[index]} phút.")
            }
            .show()
    }

    private fun showFontSizeDialog() {
        val sizes = floatArrayOf(16f, 18f, 20f, 22f, 24f, 27f)
        val labels = sizes.map { "${it.toInt()} pt" }.toTypedArray()
        val selected = sizes.indexOfFirst { it == fontSize }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(editionLabel("Cỡ chữ", "Font"))
            .setSingleChoiceItems(labels, selected) { dialog, index ->
                fontSize = sizes[index]
                preferences.edit().putFloat(KEY_FONT_SIZE, fontSize).apply()
                dialog.dismiss()
                renderReader()
            }
            .show()
    }

    private fun readerBookName(bookCode: String): String {
        return books.firstOrNull { it.code == bookCode }?.name ?: bookCode
    }

    private fun editionLabel(vietnamese: String, english: String): String =
        if (selectedTextVersion == "NIV") english else vietnamese

    private fun showTextVersionDialog() {
        var dialog: AlertDialog? = null
        val choices = vertical().apply {
            setPadding(dp(16), dp(8), dp(16), dp(4))
            textVersions.forEach { version ->
                addView(pickerChoice(version.code, version.code == selectedTextVersion) {
                    dialog?.dismiss()
                    selectTextVersion(version.code)
                }, matchWrap().apply { bottomMargin = dp(4) })
            }
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Chọn bản dịch")
            .setView(choices)
            .setNegativeButton("Đóng", null)
            .create()
        dialog.show()
    }

    private fun selectTextVersion(code: String) {
        if (code == selectedTextVersion) return
        selectedTextVersion = code
        books = repository.books(code)
        preferences.edit().putString(KEY_TEXT_VERSION, code).apply()
        val audioVersion = audioVersionForText(code)
        chapters = repository.chapters(audioVersion)
        if (audioVersion != currentChapter.audioVersion) {
            selectChapter(chapters.first { it.globalOrder == currentChapter.globalOrder })
        } else {
            renderReader()
        }
    }

    private fun audioStorageNote(): String = if (currentChapter.audioVersion == "VIE1925") {
        "Tổng bộ audio cần khoảng 1,16 GB."
    } else {
        repository.nivAudioSizeBytes()?.let {
            "Tổng bộ audio cần khoảng " + String.format(Locale.getDefault(), "%.2f GB.", it / 1_000_000_000.0)
        } ?: "Dung lượng đã tải được hiển thị trong mục tải audio."
    }

    private fun showAboutDialog() {
        val summary = downloads.summary(chapters)
        val textVersion = textVersions.first { it.code == selectedTextVersion }
        val content = vertical().apply {
            setPadding(dp(22), dp(4), dp(22), dp(16))
        }
        fun section(title: String, body: String, small: Boolean = false) {
            content.addView(sectionLabel(title).apply {
                textSize = 15f
                setTextColor(colorText)
            }, matchWrap())
            content.addView(TextView(this).apply {
                text = body
                textSize = if (small) 12f else 14f
                setTextColor(if (small) colorMuted else colorText)
                setLineSpacing(dp(3).toFloat(), 1.1f)
                setTextIsSelectable(true)
            }, matchWrap())
        }
        section(editionLabel("Ứng dụng", "The app"), editionLabel(
            "Ứng dụng bởi Zohan, với mong muốn nghe Kinh Thánh offline.",
            "By Zohan, for listening to the Bible offline."
        ))
        section(editionLabel("Nội dung", "Bible content"), editionLabel(
            "66 sách • 1.189 chương\nBản văn: ${textVersion.code}\nAudio: ${currentChapter.audioVersion}",
            "66 books • 1,189 chapters\nText: ${textVersion.code}\nAudio: ${currentChapter.audioVersion}"
        ))
        val storage = if (selectedTextVersion == "NIV") {
            repository.nivAudioSizeBytes()?.let {
                "Full audio download: about " + String.format(Locale.US, "%.2f GB.", it / 1_000_000_000.0)
            } ?: "Downloaded size is shown in audio downloads."
        } else audioStorageNote()
        section(editionLabel("Audio offline", "Offline audio"), editionLabel(
            "${summary.downloaded}/${chapters.size} chương đã tải.\n$storage\n" +
                "Audio có thể nghe được online hoặc tải về máy để nghe offline.",
            "${summary.downloaded}/${chapters.size} chapters downloaded.\n$storage\n" +
                "Stream audio online or download it in the app to listen offline."
        ))
        val sources = buildString {
            append("${textVersion.title}\n${textVersion.copyrightNotice}\n${textVersion.sourceUrl}")
            if (currentChapter.audioVersion == "NIV") {
                append("\n\nThe Listener’s Bible®: NIV Edition. Audio © 2011 Max McLean. All rights reserved worldwide.")
                append("\nhttps://www.bible.com/audio-bible/111/GEN.1.NIV")
            }
        }
        section(editionLabel("Nguồn và bản quyền", "Sources and copyright"), sources, small = true)
        AlertDialog.Builder(this)
            .setTitle(editionLabel("Giới thiệu ứng dụng", "About this app"))
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(editionLabel("Đóng", "Close"), null)
            .show()
    }

    private fun updatePlayerBar() {
        val displayedChapter = currentChapter
        playerBookView?.text = "${readerBookName(displayedChapter.bookCode)}  ▾"
        playerChapterView?.text = "${displayedChapter.chapter}"
        compactPreviousView?.apply {
            isEnabled = displayedChapter.globalOrder > 1
            alpha = if (isEnabled) 1f else 0.3f
        }
        compactNextView?.apply {
            isEnabled = displayedChapter.globalOrder < chapters.size
            alpha = if (isEnabled) 1f else 0.3f
        }
        val offline = downloads.availableFile(displayedChapter) != null
        playerOfflineView?.text = if (offline) "Offline" else "Online"
        playerPositionView?.text = formatTime(PlaybackStateStore.positionMs)
        playerDurationView?.text = formatTime(PlaybackStateStore.durationMs)
        playerSpeedView?.text = formatSpeed(PlaybackStateStore.speed)
        playerPlayButton?.setImageResource(
            if (PlaybackStateStore.isPlaying || PlaybackStateStore.playWhenReady) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            }
        )
        playerProgressView?.takeUnless { isSeeking }?.let {
            it.max = max(PlaybackStateStore.durationMs, 1)
            it.progress = PlaybackStateStore.positionMs.coerceIn(0, it.max)
        }
    }

    private fun togglePlayerPanel() {
        playerPanelVisible = !playerPanelVisible
        playerBarView?.visibility = if (playerPanelVisible) View.VISIBLE else View.GONE
        compactNavigationView?.visibility = if (playerPanelVisible) View.GONE else View.VISIBLE
        playerPanelToggleView?.contentDescription =
            if (playerPanelVisible) "Ẩn bảng điều khiển" else "Hiện bảng điều khiển"
        preferences.edit()
            .putBoolean(KEY_PLAYER_PANEL_VISIBLE, playerPanelVisible)
            .apply()
    }

    private val ticker = object : Runnable {
        override fun run() {
            val playingOrder = PlaybackStateStore.currentOrder
            val audioVersion = PlaybackStateStore.audioVersion
            if (playingOrder > 0 && (playingOrder != currentChapter.globalOrder || audioVersion != currentChapter.audioVersion)) {
                if (audioVersionForText(selectedTextVersion) != audioVersion) {
                    selectedTextVersion = audioVersion
                    books = repository.books(selectedTextVersion)
                    preferences.edit().putString(KEY_TEXT_VERSION, selectedTextVersion).apply()
                }
                if (chapters.firstOrNull()?.audioVersion != audioVersion) chapters = repository.chapters(audioVersion)
                chapters.firstOrNull { it.globalOrder == playingOrder }?.let {
                    currentChapter = it
                    renderReader()
                }
            }
            updatePlayerBar()
            handler.postDelayed(this, 1_000)
        }
    }

    private fun sendPlayerAction(action: String, configure: Intent.() -> Unit = {}) {
        val intent = Intent(this, AudioPlayerService::class.java)
            .setAction(action)
            .apply(configure)
        val needsForeground = action == AudioPlayerService.ACTION_PLAY_ORDER ||
            action == AudioPlayerService.ACTION_TOGGLE || action == AudioPlayerService.ACTION_RESUME
        if (needsForeground) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1925)
        }
    }

    private fun showToolbarMenu(anchor: View) {
        var popup: PopupWindow? = null
        val content = vertical().apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(colorSurface, colorBorder, 8)
            addView(settingsMenuItem("Aa", editionLabel("Cỡ chữ", "Font")) {
                popup?.dismiss()
                showFontSizeDialog()
            })
            addView(settingsMenuItem("◐", editionLabel("Giao diện", "Appearance")) {
                popup?.dismiss()
                showAppearanceDialog()
            })
            addView(settingsMenuItem("", editionLabel("Kiểm tra cập nhật", "Check for updates"),
                R.drawable.ic_system_update) {
                popup?.dismiss()
                appUpdates.check()
            })
            addView(settingsMenuItem("ⓘ", editionLabel("Giới thiệu", "About")) {
                popup?.dismiss()
                showAboutDialog()
            })
        }
        popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
            })
            showAsDropDown(anchor, -dp(6), -dp(4))
        }
    }

    private fun applyReaderTheme() {
        darkMode = when (preferences.getString(KEY_THEME_MODE, "system")) {
            "dark" -> true
            "light" -> false
            else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        }
        setTheme(if (darkMode) R.style.AppTheme_Dark else R.style.AppTheme)
    }

    @Suppress("DEPRECATION")
    private fun updateSystemBars() {
        window.statusBarColor = colorSurface
        window.navigationBarColor = colorSurface
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (darkMode) 0 else mask, mask)
        } else {
            val mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility =
                (window.decorView.systemUiVisibility and mask.inv()) or
                    (if (darkMode) 0 else mask)
        }
    }

    private fun showAppearanceDialog() {
        val modes = listOf("system", "light", "dark")
        val selected = modes.indexOf(preferences.getString(KEY_THEME_MODE, "system"))
            .coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(editionLabel("Giao diện", "Appearance"))
            .setSingleChoiceItems(arrayOf(
                editionLabel("Theo hệ thống", "System"),
                editionLabel("Sáng", "Light"),
                editionLabel("Tối", "Dark")
            ), selected) { dialog, index ->
                val scrollPosition = readerScrollView?.scrollY ?: 0
                preferences.edit().putString(KEY_THEME_MODE, modes[index]).apply()
                dialog.dismiss()
                applyReaderTheme()
                renderReader()
                readerScrollView?.post { readerScrollView?.scrollTo(0, scrollPosition) }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun speakerStatusButton(): View {
        return vertical().apply {
            gravity = Gravity.CENTER
            contentDescription = if (playerPanelVisible) "Ẩn bảng điều khiển" else "Hiện bảng điều khiển"
            setOnClickListener { togglePlayerPanel() }
            playerPanelToggleView = this
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_speaker_simple)
                imageTintList = android.content.res.ColorStateList.valueOf(colorText)
                scaleType = ImageView.ScaleType.CENTER
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(25)
            ))
            addView(TextView(this@MainActivity).apply {
                textSize = 10f
                setTextColor(colorMuted)
                maxLines = 1
                includeFontPadding = false
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                playerOfflineView = this
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(13)
            ))
        }
    }

    private fun downloadStatusButton(): View {
        return ImageView(this).apply {
            setImageResource(R.drawable.ic_download_bold)
            imageTintList = android.content.res.ColorStateList.valueOf(colorText)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Tải xuống"
            setOnClickListener { showDownloadsDialog() }
        }
    }

    private fun settingsMenuItem(
        symbol: String,
        label: String,
        icon: Int? = null,
        action: () -> Unit
    ): View {
        return horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            contentDescription = label
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { action() }
            val symbolView = if (icon != null) ImageView(this@MainActivity).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(colorText)
                scaleType = ImageView.ScaleType.CENTER
            } else TextView(this@MainActivity).apply {
                text = symbol
                textSize = if (symbol == "Aa") 17f else 21f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colorText)
                gravity = Gravity.CENTER
            }
            symbolView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(symbolView, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(8) })
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 15f
                setTextColor(colorText)
                maxWidth = dp(200)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            layoutParams = matchWrap()
        }
    }

    private fun plainIconButton(icon: Int, description: String, action: (View) -> Unit): ImageButton {
        return ImageButton(this).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(colorText)
            scaleType = ImageView.ScaleType.CENTER
            background = null
            setPadding(0, 0, 0, 0)
            contentDescription = description
            setOnClickListener { action(it) }
        }
    }

    private fun selectorChip(text: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorText)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            background = rounded(colorBackground, colorBorder, 8)
            setOnClickListener { action() }
        }
    }

    private fun pickerChoice(label: String, selected: Boolean, action: () -> Unit): View {
        return horizontal().apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isSelected = selected
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = if (selected) "$label, đang chọn" else label
            if (selected) background = rounded(colorSelection)
            setOnClickListener { action() }
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = if (selected) 18f else 16f
                setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(colorText)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = if (selected) "✓" else ""
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colorText)
                gravity = Gravity.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun mediaButton(
        icon: Int,
        description: String,
        primary: Boolean = false,
        large: Boolean = false,
        action: () -> Unit
    ): ImageButton {
        val size = if (large) 62 else 48
        return ImageButton(this).apply {
            setImageResource(icon)
            setColorFilter(if (primary) Color.WHITE else colorText)
            background = if (primary) {
                rounded(colorAccent, null, 100)
            } else {
                rounded(Color.TRANSPARENT, null, 100)
            }
            contentDescription = description
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
    }

    private fun primaryButton(text: String, action: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = rounded(colorAccent, null, 8)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
            ).apply { setMargins(0, dp(5), 0, dp(5)) }
        }
    }

    private fun outlinedButton(text: String, action: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 14f
            isAllCaps = false
            setTextColor(colorText)
            gravity = Gravity.CENTER
            background = rounded(colorSurface, colorBorder, 8)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply { setMargins(dp(3), dp(5), dp(3), dp(5)) }
        }
    }

    private fun textButton(text: String, action: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 14f
            isAllCaps = false
            setTextColor(colorEmphasis)
            background = rounded(Color.TRANSPARENT)
            setOnClickListener { action() }
        }
    }

    private fun timelineLabel(alignment: Int): TextView {
        return TextView(this).apply {
            textSize = 11f
            setTextColor(colorMuted)
            gravity = alignment or Gravity.CENTER_VERTICAL
        }
    }

    private fun chapterButton(
        previous: Boolean,
        action: () -> Unit
    ): ImageButton = plainIconButton(
        if (previous) R.drawable.ic_chapter_previous else R.drawable.ic_chapter_next,
        if (previous) "Chương trước" else "Chương sau"
    ) { action() }

    private fun transportOptionButton(
        text: String,
        description: String,
        action: () -> Unit
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = if (description == "Hẹn giờ") 23f else 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorText)
            gravity = Gravity.CENTER
            background = rounded(colorBackground, colorBorder, 100)
            contentDescription = description
            setOnClickListener { action() }
        }
    }

    private fun dialogTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 23f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorText)
            setPadding(0, 0, 0, dp(12))
        }
    }

    private fun sectionLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colorMuted)
            setPadding(0, dp(18), 0, dp(5))
        }
    }

    private fun statusCard(title: String, detail: String): View {
        return vertical().apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(colorBackground, colorBorder, 8)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colorText)
            })
            addView(TextView(this@MainActivity).apply {
                text = detail
                textSize = 13f
                setTextColor(colorMuted)
            })
        }
    }

    private fun rounded(
        fill: Int,
        stroke: Int? = null,
        radius: Int = 8
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }
    }

    private fun vertical() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun horizontal() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun formatTime(milliseconds: Int): String {
        val seconds = (milliseconds / 1_000).coerceAtLeast(0)
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val megabytes = bytes / 1024.0 / 1024.0
        return if (megabytes >= 1024.0) {
            String.format(Locale.getDefault(), "%.2f GB", megabytes / 1024.0)
        } else {
            String.format(Locale.getDefault(), "%.0f MB", megabytes)
        }
    }

    private fun formatSpeed(speed: Float): String {
        return if (speed == speed.toInt().toFloat()) {
            "${speed.toInt()}x"
        } else {
            "${speed}x"
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val STATE_PREFERENCES = "state"
        private const val KEY_LAST_ORDER = "lastOrder"
        private const val KEY_READER_ORDER = "readerOrder"
        private const val KEY_FONT_SIZE = "fontSize"
        private const val KEY_SPEED = "speed"
        private const val KEY_PLAYER_PANEL_VISIBLE = "playerPanelVisible"
        private const val KEY_THEME_MODE = "themeMode"
        private const val KEY_TEXT_VERSION = "textVersion"
        private const val KEY_AUDIO_VERSION = "audioVersion"
    }
}
