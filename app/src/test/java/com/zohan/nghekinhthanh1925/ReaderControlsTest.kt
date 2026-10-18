package com.zohan.nghekinhthanh1925

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Button
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderControlsTest {
    private val preferences get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("state", Context.MODE_PRIVATE)

    @Before fun reset() {
        preferences.edit().clear().commit()
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("audio_downloads_v2", Context.MODE_PRIVATE).edit().clear().commit()
        PlaybackStateStore.currentOrder = 0
        PlaybackStateStore.audioVersion = "VIE1925"
        PlaybackStateStore.playWhenReady = false
        PlaybackStateStore.isPlaying = false
        PlaybackStateStore.positionMs = 0
        PlaybackStateStore.positionUpdatedElapsed = 0
        PlaybackStateStore.durationMs = 0
        PlaybackStateStore.speed = 1f
        PlaybackStateStore.sleepDeadlineElapsed = 0
        PlaybackStateStore.sleepMinutes = 0
    }

    private fun withReader(test: (MainActivity) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        try {
            test(controller.setup().get())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun views(root: View): List<View> = buildList {
        if (root.visibility != View.VISIBLE) return@buildList
        add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) addAll(views(root.getChildAt(index)))
        }
    }

    private fun root(activity: MainActivity): View = activity.findViewById(android.R.id.content)

    private fun control(activity: MainActivity, description: String): View =
        views(root(activity)).single { it.contentDescription == description }

    private fun selectedBook(theme: String) {
        preferences.edit().putString("themeMode", theme).commit()
        withReader { activity ->
            control(activity, "Chọn sách").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val rows = views(dialog.window!!.decorView)
            val selected = rows.single { it.isSelected }
            val labels = views(selected).filterIsInstance<TextView>()
            assertNotNull("Selected book must have a background highlight", selected.background)
            assertEquals("✓", labels.last().text.toString())
            val label = labels.first()
            assertEquals(Typeface.BOLD, label.typeface.style and Typeface.BOLD)
            val otherBook = rows.filterIsInstance<LinearLayout>()
                .first { it.hasOnClickListeners() && !it.isSelected }
            assertTrue(labels.first().textSize > (otherBook.getChildAt(0) as TextView).textSize)
            assertNotEquals(Color.rgb(190, 40, 45), labels.first().currentTextColor)
            assertTrue(selected.contentDescription.toString().endsWith("đang chọn"))
            dialog.dismiss()
        }
    }

    @Test fun selectedBookHasNeutralHighlightBoldTextAndCheckmark() = selectedBook("light")

    @Test fun selectedBookIsDistinctInDarkMode() = selectedBook("dark")

    @Test fun hiddenPlayerKeepsSmallArrowsAndPausedNavigation() = withReader { activity ->
        control(activity, "Ẩn bảng điều khiển").performClick()
        assertFalse(views(root(activity)).any { it.contentDescription == "Phát hoặc tạm dừng" })
        assertChapterIcons(activity)
        assertFalse(control(activity, "Chương trước").isEnabled)
        control(activity, "Chương sau").performClick()
        assertEquals(2, PlaybackStateStore.currentOrder)
        assertEquals(0, PlaybackStateStore.positionMs)
        assertFalse(PlaybackStateStore.playWhenReady)
        assertFalse(PlaybackStateStore.isPlaying)
        assertEquals("2", (control(activity, "Chọn chương") as TextView).text.toString())
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(AudioPlayerService.ACTION_SELECT_ORDER, intent.action)
        assertFalse(intent.getBooleanExtra(AudioPlayerService.EXTRA_AUTO_PLAY, true))
        control(activity, "Chương trước").performClick()
        assertEquals(1, PlaybackStateStore.currentOrder)
        assertFalse(PlaybackStateStore.playWhenReady)
        control(activity, "Hiện bảng điều khiển").performClick()
        assertEquals(1, views(root(activity)).count { it.contentDescription == "Chương sau" })
        assertNotNull(control(activity, "Phát hoặc tạm dừng"))
    }

    @Test fun hiddenPlayerNavigationContinuesOnlyExistingPlayback() {
        PlaybackStateStore.currentOrder = 2
        PlaybackStateStore.isPlaying = true
        PlaybackStateStore.playWhenReady = true
        PlaybackStateStore.positionMs = 25_000
        withReader { activity ->
            control(activity, "Ẩn bảng điều khiển").performClick()
            control(activity, "Chương sau").performClick()
            assertEquals(3, PlaybackStateStore.currentOrder)
            assertEquals(0, PlaybackStateStore.positionMs)
            assertTrue(PlaybackStateStore.playWhenReady)
            val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
            assertEquals(AudioPlayerService.ACTION_PLAY_ORDER, intent.action)
            assertTrue(intent.getBooleanExtra(AudioPlayerService.EXTRA_START_FROM_BEGINNING, false))
            assertNotNull(control(activity, "Hiện bảng điều khiển"))
        }
    }

    @Test fun savedHiddenPlayerRestoresNavigationAndRespectsLastChapter() {
        preferences.edit().putBoolean("playerPanelVisible", false).putInt("lastOrder", 1189).commit()
        withReader { activity ->
            assertNotNull(control(activity, "Hiện bảng điều khiển"))
            assertFalse(control(activity, "Chương sau").isEnabled)
            assertTrue(control(activity, "Chương trước").isEnabled)
            control(activity, "Chương sau").performClick()
            assertEquals(1189, PlaybackStateStore.currentOrder)
            control(activity, "Chương trước").performClick()
            assertEquals(1188, PlaybackStateStore.currentOrder)
            assertFalse(preferences.getBoolean("playerPanelVisible", true))
        }
    }

    @Test fun compactVersionPickerSelectsNivWithoutStartingPlayback() {
        preferences.edit().putInt("lastOrder", 929).putBoolean("playerPanelVisible", false).commit()
        withReader { activity ->
            assertNull(control(activity, "Chọn bản dịch").background)
            control(activity, "Chọn bản dịch").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val choices = views(dialog.window!!.decorView)
                .filter { it.contentDescription?.toString()?.substringBefore(',') in
                    listOf("VIE1925", "VIE2010", "NIV") }
            assertEquals(3, choices.size)
            assertEquals("VIE1925, đang chọn", choices.single { it.isSelected }.contentDescription)
            choices.single { it.contentDescription == "NIV" }.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertEquals("NIV", preferences.getString("textVersion", null))
            assertEquals("NIV", PlaybackStateStore.audioVersion)
            assertEquals(929, PlaybackStateStore.currentOrder)
            assertFalse(PlaybackStateStore.playWhenReady)
            val chapter = (control(activity, "Chọn chương") as TextView).text
            assertTrue(views(root(activity)).filterIsInstance<TextView>().any { it.text.toString() == "Chapter $chapter" })
            assertNotNull(control(activity, "Hiện bảng điều khiển"))
            control(activity, "Chọn bản dịch").performClick()
            val reopened = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals("NIV, đang chọn", views(reopened.window!!.decorView)
                .single { it.isSelected }.contentDescription)
            reopened.dismiss()
        }
    }

    @Test fun vie2010SelectionKeepsChapterAndExistingVietnamesePlayback() {
        PlaybackStateStore.currentOrder = 20
        PlaybackStateStore.isPlaying = true
        PlaybackStateStore.playWhenReady = true
        PlaybackStateStore.positionMs = 5_000
        withReader { activity ->
            control(activity, "Chọn bản dịch").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            views(dialog.window!!.decorView).single { it.contentDescription == "VIE2010" }.performClick()
            assertEquals("VIE2010", preferences.getString("textVersion", null))
            assertEquals("VIE1925", PlaybackStateStore.audioVersion)
            assertEquals(20, PlaybackStateStore.currentOrder)
            assertEquals(5_000, PlaybackStateStore.positionMs)
            assertTrue(PlaybackStateStore.playWhenReady)
            assertTrue(PlaybackStateStore.isPlaying)
        }
    }

    private fun assertChapterIcons(activity: MainActivity) {
        for (description in listOf("Chương trước", "Chương sau")) {
            val button = control(activity, description) as ImageButton
            assertNull("Chapter arrows must remain borderless", button.background)
            assertEquals(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32f,
                activity.resources.displayMetrics).toInt(), button.drawable.intrinsicWidth)
        }
    }

    @Test fun bothPlayerStatesHaveLargerChapterIcons() = withReader { activity ->
        assertChapterIcons(activity)
        control(activity, "Ẩn bảng điều khiển").performClick()
        assertChapterIcons(activity)
    }

    @Test fun collapseButtonHidesPlayerWithoutStoppingAudio() {
        PlaybackStateStore.currentOrder = 2
        PlaybackStateStore.isPlaying = true
        PlaybackStateStore.playWhenReady = true
        PlaybackStateStore.positionMs = 5_000
        withReader { activity ->
            control(activity, "Thu gọn bảng điều khiển").performClick()
            assertFalse(views(root(activity)).any { it.contentDescription == "Phát hoặc tạm dừng" })
            assertNotNull(control(activity, "Hiện bảng điều khiển"))
            assertChapterIcons(activity)
            assertTrue(PlaybackStateStore.isPlaying)
            assertTrue(PlaybackStateStore.playWhenReady)
            assertEquals(5_000, PlaybackStateStore.positionMs)
            assertFalse(preferences.getBoolean("playerPanelVisible", true))
            control(activity, "Hiện bảng điều khiển").performClick()
            assertNotNull(control(activity, "Thu gọn bảng điều khiển"))
        }
    }

    private fun menuRows(activity: MainActivity, description: String): Pair<android.widget.PopupWindow, List<LinearLayout>> {
        assertTrue(control(activity, description) is ImageButton)
        control(activity, description).performClick()
        val popup = shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow
        val container = popup.contentView as LinearLayout
        val rows = (0 until container.childCount).map { container.getChildAt(it) }.filterIsInstance<LinearLayout>()
        return popup to rows
    }

    @Test fun gearMenuHasLabeledOptionsAndStructuredAboutLast() = withReader { activity ->
        val (popup, rows) = menuRows(activity, "Cài đặt")
        val labels = listOf("Cỡ chữ", "Ngôn ngữ và giao diện", "Kiểm tra cập nhật", "Giới thiệu")
        assertEquals(labels, rows.map { it.contentDescription.toString() })
        listOf(0, 2, 3).forEach { index ->
            val row = rows[index]
            val label = labels[index]
            assertTrue(views(row).filterIsInstance<TextView>().any { it.text.toString() == label })
        }
        assertEquals(listOf("VN", "EN"), views(rows[1]).filterIsInstance<TextView>().map { it.text.toString() })
        assertEquals(listOf("Ngày", "Đêm"), views(rows[1]).filterIsInstance<ImageButton>().map { it.contentDescription.toString() })
        assertTrue(views(rows[1]).single { it.contentDescription == "VN" }.isSelected)
        assertTrue("Update must use a distinct vector icon", rows[2].getChildAt(0) is ImageView)
        rows.last().performClick()
        assertFalse(popup.isShowing)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val texts = views(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        assertTrue(texts.contains("Giới thiệu ứng dụng"))
        val sections = listOf("Ứng dụng", "Nội dung", "Audio offline", "Nguồn và bản quyền")
        assertTrue(sections.all { texts.contains(it) })
        assertEquals(sections.map { texts.indexOf(it) }.sorted(), sections.map { texts.indexOf(it) })
        assertTrue(texts.any { it.contains("bible.com") })
        dialog.dismiss()
    }

    @Test fun nivUsesEnglishChapterHeadingAndSettingsLabels() {
        preferences.edit().putString("textVersion", "NIV").putInt("lastOrder", 2).commit()
        withReader { activity ->
            val texts = views(root(activity)).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(texts.contains("Chapter 2"))
            assertFalse(texts.contains("Chương 2"))
            val (_, rows) = menuRows(activity, "Settings")
            assertEquals(listOf("Font", "Language and appearance", "Check for updates", "About"),
                rows.map { it.contentDescription.toString() })
            rows.last().performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val about = views(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(about.contains("About this app"))
            assertTrue(about.contains("Sources and copyright"))
            assertTrue(about.any { it.contains("Max McLean") })
            assertTrue(about.any { it.contains("Biblica") })
            dialog.dismiss()
        }
    }

    @Test fun vietnameseEditionKeepsVietnameseChapterHeading() {
        preferences.edit().putString("textVersion", "VIE2010").putInt("lastOrder", 2).commit()
        withReader { activity ->
            val texts = views(root(activity)).filterIsInstance<TextView>().map { it.text.toString() }
            assertTrue(texts.contains("Chương 2"))
            assertFalse(texts.contains("Chapter 2"))
        }
    }

    @Test fun languageButtonsChangeOnlyInterfaceAndRememberTheChoice() = withReader { activity ->
        val (_, rows) = menuRows(activity, "Cài đặt")
        views(rows[1]).single { it.contentDescription == "EN" }.performClick()
        assertTrue(UiLanguage.isEnglish(activity))
        assertEquals("en", preferences.getString("uiLanguage", null))
        assertNotNull(control(activity, "Choose book"))
        assertEquals("VIE1925", preferences.getString("textVersion", "VIE1925"))
        assertFalse(PlaybackStateStore.playWhenReady)
        val (_, englishRows) = menuRows(activity, "Settings")
        assertTrue(views(englishRows[1]).single { it.contentDescription == "EN" }.isSelected)
        views(englishRows[1]).single { it.contentDescription == "VN" }.performClick()
        assertNotNull(control(activity, "Chọn sách"))
        assertEquals("vi", preferences.getString("uiLanguage", null))
    }

    @Test fun dayNightButtonsApplyImmediatelyWithoutAnotherDialogOrAudioRestart() {
        PlaybackStateStore.isPlaying = true
        PlaybackStateStore.playWhenReady = true
        withReader { activity ->
            val (_, rows) = menuRows(activity, "Cài đặt")
            views(rows[1]).single { it.contentDescription == "Đêm" }.performClick()
            assertEquals("dark", preferences.getString("themeMode", null))
            assertTrue(PlaybackStateStore.isPlaying)
            val (_, darkRows) = menuRows(activity, "Cài đặt")
            assertTrue(views(darkRows[1]).single { it.contentDescription == "Đêm" }.isSelected)
            assertFalse(views(darkRows[1]).filterIsInstance<TextView>().any { it.text.toString().contains("hệ thống") })
            views(darkRows[1]).single { it.contentDescription == "Ngày" }.performClick()
            assertEquals("light", preferences.getString("themeMode", null))
            assertTrue(PlaybackStateStore.playWhenReady)
        }
    }

    @Test fun compactPlayerHasAnExpandButton() = withReader { activity ->
        control(activity, "Thu gọn bảng điều khiển").performClick()
        control(activity, "Mở rộng bảng điều khiển").performClick()
        assertNotNull(control(activity, "Thu gọn bảng điều khiển"))
        assertNotNull(control(activity, "Phát hoặc tạm dừng"))
        assertFalse(PlaybackStateStore.playWhenReady)
    }

    @Test fun timerCountdownUpdatesInWholeMinutesInPlayerAndDialog() {
        SleepTimer.start(RuntimeEnvironment.getApplication(), 15)
        withReader { activity ->
            assertTrue(views(root(activity)).filterIsInstance<TextView>().any { it.text.toString() == "15 ph" })
            control(activity, "Hẹn giờ tắt").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val countdown = views(dialog.window!!.decorView).single { it.tag == "sleepCountdown" } as TextView
            assertEquals("15 ph", countdown.text.toString())
            val choices = views(dialog.window!!.decorView).filterIsInstance<Button>()
            assertTrue(choices.any { it.text.toString() == "4 giờ" })
            assertTrue(choices.any { it.text.toString() == "8 giờ" })
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals("15 ph", countdown.text.toString())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
            shadowOf(Looper.getMainLooper()).idle()
            val remaining = SleepTimer.countdown(activity)
            assertEquals("14 ph", remaining)
            assertEquals(remaining, countdown.text.toString())
            assertTrue(views(root(activity)).filterIsInstance<TextView>().any { it.text.toString() == remaining })
            SleepTimer.start(activity, 30)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals("30 ph", countdown.text.toString())
            assertTrue(choices.single { it.text.toString() == "30 phút" }.isSelected)
            dialog.dismiss()
            val stoppedText = countdown.text.toString()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
            assertEquals("Dismissed dialog must stop ticking", stoppedText, countdown.text.toString())
        }
    }

    @Test fun timerDialogStopsRefreshingOffScreenAndCatchesUpOnResume() {
        SleepTimer.start(RuntimeEnvironment.getApplication(), 15)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            control(activity, "Hẹn giờ tắt").performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val countdown = views(dialog.window!!.decorView).single { it.tag == "sleepCountdown" } as TextView
            controller.pause()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2))
            assertEquals("15 ph", countdown.text.toString())
            controller.resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(SleepTimer.countdown(activity), countdown.text.toString())
            assertTrue(SleepTimer.remainingMs() < 900_000)
            dialog.dismiss()
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
