package com.zohan.nghekinhthanh1925

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaybackLoopStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val store get() = PlaybackLoopStore(context)

    @Before fun reset() {
        context.getSharedPreferences(PlaybackLoopStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun orderSurvivesRecreationWithoutSorting() {
        store.save("VIE1925", PlaybackLoopSelection(true, listOf(20, 3, 10)))
        assertEquals(PlaybackLoopSelection(true, listOf(20, 3, 10)), store.load("VIE1925"))
    }

    @Test fun duplicatesAndInvalidChaptersAreRemovedWithoutChangingOrder() {
        store.save("VIE1925", PlaybackLoopSelection(true, listOf(0, 20, 20, 3, -1, 1190, 1189)))
        assertEquals(listOf(20, 3, 1189), store.load("VIE1925").orders)
    }

    @Test fun emptySelectionCannotBeEnabled() {
        store.save("NIV", PlaybackLoopSelection(true, emptyList()))
        assertFalse(store.load("NIV").enabled)
    }

    @Test fun nextAndPreviousWrapInSelectedOrder() {
        val selection = PlaybackLoopSelection(true, listOf(20, 3, 10))
        assertEquals(3, selection.relativeOrder(20, 1))
        assertEquals(20, selection.relativeOrder(10, 1))
        assertEquals(10, selection.relativeOrder(20, -1))
        assertNull(selection.copy(enabled = false).relativeOrder(20, 1))
    }

    @Test fun singleChapterLoopsInBothDirections() {
        val selection = PlaybackLoopSelection(true, listOf(1189))
        assertEquals(1189, selection.relativeOrder(1189, 1))
        assertEquals(1189, selection.relativeOrder(1189, -1))
    }

    @Test fun manualJumpInsideKeepsLoopAndOutsideDisablesWithoutDeletingSelection() {
        val store = store
        store.save("VIE1925", PlaybackLoopSelection(true, listOf(20, 3)))
        store.onManualSelection("VIE1925", "VIE1925", 3)
        assertTrue(store.load("VIE1925").enabled)
        store.onManualSelection("VIE1925", "VIE1925", 4)
        assertEquals(PlaybackLoopSelection(false, listOf(20, 3)), store.load("VIE1925"))
    }

    @Test fun editionsHaveSeparateSelectionsAndSwitchingDisablesBoth() {
        val store = store
        store.save("VIE1925", PlaybackLoopSelection(true, listOf(20, 3)))
        store.save("NIV", PlaybackLoopSelection(true, listOf(1, 10)))
        store.onManualSelection("VIE1925", "NIV", 1)
        assertEquals(PlaybackLoopSelection(false, listOf(20, 3)), store.load("VIE1925"))
        assertEquals(PlaybackLoopSelection(false, listOf(1, 10)), store.load("NIV"))
    }

    @Test fun cachedReaderSeesChangesFromOtherStoreInstances() {
        val reader = store
        assertFalse(reader.load("NIV").enabled)
        store.save("NIV", PlaybackLoopSelection(true, listOf(20, 1)))
        assertEquals(listOf(20, 1), reader.load("NIV").orders)
        assertTrue(reader.load("NIV").enabled)
        store.disable("NIV")
        assertFalse(reader.load("NIV").enabled)
    }

    @Test fun malformedSavedSelectionIsSafelyDisabled() {
        context.getSharedPreferences(PlaybackLoopStore.PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("NIV_orders", "invalid json").putBoolean("NIV_enabled", true).commit()
        assertEquals(PlaybackLoopSelection(false, emptyList<Int>()), store.load("NIV"))
    }

    @Test fun namedPresetsSurviveRecreationAndActivateIndependently() {
        val first = store.savePreset("VIE1925", " Evening ", listOf(20, 3, 20))
        val second = store.savePreset("VIE1925", "Morning", listOf(51, 1))
        assertEquals(listOf("Evening", "Morning"), store.presets("VIE1925").map { it.name })
        assertFalse(store.load("VIE1925").enabled)
        assertTrue(store.activatePreset("VIE1925", first.id))
        assertEquals(PlaybackLoopSelection(true, listOf(20, 3)), store.load("VIE1925"))
        store.activatePreset("VIE1925", second.id)
        assertEquals(second.id, store.selectedPresetId("VIE1925"))
        assertEquals(PlaybackLoopSelection(true, listOf(51, 1)), store.load("VIE1925"))
        assertEquals(2, store.presets("VIE1925").size)
    }

    @Test fun renameKeepsIdentityAndSelectedOrderAndEditingInactiveDoesNotChangePlayback() {
        val active = store.savePreset("NIV", "Active", listOf(3, 1))
        val inactive = store.savePreset("NIV", "Other", listOf(10))
        store.activatePreset("NIV", active.id)
        val renamed = store.savePreset("NIV", "Renamed", active.orders, active.id)
        assertEquals(active.id, renamed.id)
        assertEquals(active.id, store.selectedPresetId("NIV"))
        store.savePreset("NIV", "Other", listOf(20, 10), inactive.id)
        assertEquals(PlaybackLoopSelection(true, listOf(3, 1)), store.load("NIV"))
        store.savePreset("NIV", "Renamed", listOf(1, 3), active.id)
        assertEquals(PlaybackLoopSelection(true, listOf(1, 3)), store.load("NIV"))
    }

    @Test fun disablingAndDeletingPresetsDoesNotDeleteOthersOrEnableInactivePlayback() {
        val first = store.savePreset("NIV", "First", listOf(3, 1))
        val second = store.savePreset("NIV", "Second", listOf(10))
        store.activatePreset("NIV", first.id)
        store.onManualSelection("NIV", "NIV", 2)
        assertFalse(store.load("NIV").enabled)
        assertEquals(first.id, store.selectedPresetId("NIV"))
        store.savePreset("NIV", "First", listOf(3), first.id)
        assertFalse(store.load("NIV").enabled)
        store.activatePreset("NIV", first.id)
        store.deletePreset("NIV", second.id)
        assertTrue(store.load("NIV").enabled)
        store.deletePreset("NIV", first.id)
        assertNull(store.selectedPresetId("NIV"))
        assertEquals(PlaybackLoopSelection(false, emptyList<Int>()), store.load("NIV"))
    }

    @Test fun invalidNamesAndEmptyPresetsAreRejectedWithoutChangingSavedData() {
        store.savePreset("NIV", "Evening", listOf(1))
        listOf(" ", " evening ", "x".repeat(49)).forEach { name ->
            try {
                store.savePreset("NIV", name, listOf(3))
                fail("Invalid name accepted")
            } catch (_: IllegalArgumentException) { }
        }
        try {
            store.savePreset("NIV", "Empty", listOf(0, -1, 1190))
            fail("Empty loop accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, store.presets("NIV").size)
        assertFalse(store.activatePreset("NIV", "missing"))
    }

    @Test fun presetsAreEditionIsolatedAndInvalidJsonCannotCrashStartup() {
        store.savePreset("NIV", "Evening", listOf(1))
        store.savePreset("VIE1925", "Evening", listOf(3))
        assertEquals(listOf(1), store.presets("NIV").single().orders)
        assertEquals(listOf(3), store.presets("VIE1925").single().orders)
        context.getSharedPreferences(PlaybackLoopStore.PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("NIV_presets", "invalid json").commit()
        assertTrue(store.presets("NIV").isEmpty())
        assertEquals(1, store.presets("VIE1925").size)
    }
}
