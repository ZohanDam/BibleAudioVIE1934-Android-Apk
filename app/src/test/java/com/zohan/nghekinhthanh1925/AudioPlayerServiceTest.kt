package com.zohan.nghekinhthanh1925

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class AudioPlayerServiceTest {
    private lateinit var controller: ServiceController<AudioPlayerService>
    private lateinit var service: AudioPlayerService
    private lateinit var repository: BibleRepository

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("state", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("audio_downloads_v2", Context.MODE_PRIVATE).edit().clear().commit()
        PlaybackStateStore.currentOrder = 0
        PlaybackStateStore.isPlaying = false
        PlaybackStateStore.playWhenReady = false
        PlaybackStateStore.audioVersion = "VIE1925"
        PlaybackStateStore.positionMs = 0
        PlaybackStateStore.durationMs = 0
        PlaybackStateStore.speed = 1f
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(60_000, 1000) }
        controller = Robolectric.buildService(AudioPlayerService::class.java).create()
        service = controller.get()
        repository = BibleRepository(context)
    }

    @After fun cleanup() {
        controller.destroy()
        repository.close()
    }

    private fun action(action: String) {
        service.onStartCommand(Intent(service, AudioPlayerService::class.java).setAction(action), 0, 1)
    }

    private fun select(order: Int, version: String, play: Boolean) {
        service.onStartCommand(Intent(service, AudioPlayerService::class.java)
            .setAction(if (play) AudioPlayerService.ACTION_PLAY_ORDER else AudioPlayerService.ACTION_SELECT_ORDER)
            .putExtra(AudioPlayerService.EXTRA_ORDER, order)
            .putExtra(AudioPlayerService.EXTRA_AUDIO_VERSION, version)
            .putExtra(AudioPlayerService.EXTRA_AUTO_PLAY, play)
            .putExtra(AudioPlayerService.EXTRA_START_FROM_BEGINNING, true), 0, 1)
    }

    private fun player(): MediaPlayer? {
        return AudioPlayerService::class.java.getDeclaredField("mediaPlayer").apply {
            isAccessible = true
        }.get(service) as MediaPlayer?
    }

    private fun prepare() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

    @Test fun bothEditionsHaveCompleteSeparateDownloadIdentities() {
        val vietnamese = repository.chapters()
        val english = repository.chapters("NIV")
        assertEquals(1189, vietnamese.size)
        assertEquals(1189, english.size)
        assertEquals(2378, (vietnamese + english).map { it.downloadKey }.toSet().size)
        assertEquals(2378, (vietnamese + english).map { it.filename }.toSet().size)
        assertEquals(1, vietnamese.first().downloadKey)
        assertEquals("position_1", vietnamese.first().positionKey)
        assertNotEquals(vietnamese.first().positionKey, english.first().positionKey)
        assertEquals("VIE1925", audioVersionForText("VIE2010"))
        assertEquals("NIV", audioVersionForText("NIV"))
    }

    @Test fun selectingAndSkippingWhilePausedNeverCreatesPlayer() {
        select(1, "NIV", false)
        action(AudioPlayerService.ACTION_NEXT)
        assertEquals(2, PlaybackStateStore.currentOrder)
        assertEquals("NIV", PlaybackStateStore.audioVersion)
        assertFalse(PlaybackStateStore.playWhenReady)
        assertFalse(PlaybackStateStore.isPlaying)
        assertNull(player())
        action(AudioPlayerService.ACTION_PREVIOUS)
        assertEquals(1, PlaybackStateStore.currentOrder)
        assertNull(player())
        select(1, "VIE1925", false)
        assertEquals("VIE1925", PlaybackStateStore.audioVersion)
        assertFalse(PlaybackStateStore.playWhenReady)
        assertNull(player())
    }

    @Test fun pauseDuringPreparationPreventsDelayedAutoplay() {
        select(1, "NIV", true)
        action(AudioPlayerService.ACTION_PAUSE)
        prepare()
        assertFalse(player()!!.isPlaying)
        assertFalse(PlaybackStateStore.playWhenReady)
        service.onStartCommand(Intent(service, AudioPlayerService::class.java)
            .setAction(AudioPlayerService.ACTION_SPEED)
            .putExtra(AudioPlayerService.EXTRA_SPEED, 1.5f), 0, 1)
        assertFalse(player()!!.isPlaying)
        assertEquals(1.5f, PlaybackStateStore.speed)
    }

    @Test fun playingNextContinuesInNivFromZero() {
        select(1, "NIV", true)
        prepare()
        assertTrue(player()!!.isPlaying)
        action(AudioPlayerService.ACTION_NEXT)
        assertEquals(2, PlaybackStateStore.currentOrder)
        assertTrue(PlaybackStateStore.playWhenReady)
        assertEquals(0, PlaybackStateStore.positionMs)
        prepare()
        assertTrue(player()!!.isPlaying)
        assertEquals("NIV", PlaybackStateStore.audioVersion)
    }

    @Test fun pausedNextDoesNotResumeOldOrNewRecording() {
        select(1, "NIV", true)
        prepare()
        action(AudioPlayerService.ACTION_PAUSE)
        action(AudioPlayerService.ACTION_NEXT)
        prepare()
        assertEquals(2, PlaybackStateStore.currentOrder)
        assertFalse(PlaybackStateStore.playWhenReady)
        assertFalse(PlaybackStateStore.isPlaying)
        assertNull(player())
    }

    @Test fun stalePreparedCallbackCannotStartPreviousChapter() {
        select(1, "NIV", true)
        val oldPlayer = player()!!
        val callback = shadowOf(oldPlayer).onPreparedListener
        select(2, "NIV", false)
        callback.onPrepared(oldPlayer)
        assertEquals(2, PlaybackStateStore.currentOrder)
        assertFalse(PlaybackStateStore.playWhenReady)
        assertNull(player())
    }

    @Test fun downloadedNivChapterUsesLocalFile() {
        val chapter = repository.chapters("NIV").first()
        val file = AudioDownloadStore(service).targetFile(chapter)
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(73, 68, 51))
        try {
            select(1, "NIV", true)
            assertEquals(DataSource.toDataSource(file.absolutePath), shadowOf(player()!!).dataSource)
            prepare()
            assertTrue(player()!!.isPlaying)
        } finally {
            file.delete()
        }
    }

    @Test fun switchingEditionWhilePlayingKeepsChapterAndStartsAtZero() {
        select(101, "VIE1925", true)
        prepare()
        assertTrue(player()!!.isPlaying)
        select(101, "NIV", true)
        assertEquals(101, PlaybackStateStore.currentOrder)
        assertEquals(0, PlaybackStateStore.positionMs)
        prepare()
        assertEquals("NIV", PlaybackStateStore.audioVersion)
        assertTrue(player()!!.isPlaying)
    }

    @Test fun nivQueueAndDeleteLeaveVietnameseDownloadsAlone() {
        val store = AudioDownloadStore(service)
        val vietnamese = repository.chapters().first()
        val english = repository.chapters("NIV").first()
        store.enqueue(listOf(vietnamese, english))
        assertEquals(setOf(vietnamese.downloadKey, english.downloadKey), store.queuedOrders())
        store.cancelPending(listOf(english))
        assertEquals(setOf(vietnamese.downloadKey), store.queuedOrders())
        val oldFile = store.targetFile(vietnamese)
        val newFile = store.targetFile(english)
        oldFile.writeBytes(byteArrayOf(73, 68, 51))
        newFile.writeBytes(byteArrayOf(73, 68, 51))
        try {
            assertEquals(1, store.delete(listOf(english)))
            assertTrue(oldFile.exists())
            assertFalse(newFile.exists())
            assertNotNull(store.availableFile(vietnamese))
            assertNull(store.availableFile(english))
        } finally {
            oldFile.delete()
            newFile.delete()
        }
    }
}
