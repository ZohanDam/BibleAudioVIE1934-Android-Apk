package com.zohan.nghekinhthanh1925

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PlaybackLoopPreset(val id: String, val name: String, val orders: List<Int>)

data class PlaybackLoopSelection(val enabled: Boolean, val orders: List<Int>) {
    fun relativeOrder(current: Int, offset: Int): Int? {
        if (!enabled || orders.isEmpty()) return null
        val index = orders.indexOf(current)
        if (index < 0) return if (offset < 0) orders.last() else orders.first()
        return orders[Math.floorMod(index + offset, orders.size)]
    }
}

class PlaybackLoopStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private var cachedVersion: String? = null
    private var cachedOrders: String? = null
    private var cachedEnabled = false
    private var cachedSelection = PlaybackLoopSelection(false, emptyList())

    fun load(audioVersion: String): PlaybackLoopSelection {
        val encoded = preferences.getString("${audioVersion}_orders", "[]") ?: "[]"
        val enabled = preferences.getBoolean("${audioVersion}_enabled", false)
        if (cachedVersion == audioVersion && cachedOrders == encoded && cachedEnabled == enabled) {
            return cachedSelection
        }
        val orders = try {
            val array = JSONArray(encoded)
            normalize((0 until array.length()).map { array.optInt(it, 0) })
        } catch (_: org.json.JSONException) {
            emptyList()
        }
        cachedVersion = audioVersion
        cachedOrders = encoded
        cachedEnabled = enabled
        cachedSelection = PlaybackLoopSelection(enabled && orders.isNotEmpty(), orders)
        return cachedSelection
    }

    fun save(audioVersion: String, selection: PlaybackLoopSelection) {
        putSelection(preferences.edit(), audioVersion, selection).apply()
    }

    private fun putSelection(editor: SharedPreferences.Editor, audioVersion: String, selection: PlaybackLoopSelection): SharedPreferences.Editor {
        val orders = normalize(selection.orders)
        return editor
            .putString("${audioVersion}_orders", JSONArray(orders).toString())
            .putBoolean("${audioVersion}_enabled", selection.enabled && orders.isNotEmpty())
    }

    fun presets(audioVersion: String): List<PlaybackLoopPreset> = try {
        val array = JSONArray(preferences.getString("${audioVersion}_presets", "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val name = item.optString("name").trim()
            val saved = item.optJSONArray("orders") ?: return@mapNotNull null
            val orders = normalize((0 until saved.length()).map { saved.optInt(it, 0) })
            if (id.isBlank() || name.isBlank() || orders.isEmpty()) null else PlaybackLoopPreset(id, name, orders)
        }.distinctBy { it.id }
    } catch (_: org.json.JSONException) {
        emptyList()
    }

    fun selectedPresetId(audioVersion: String): String? = preferences.getString("${audioVersion}_presetId", null)

    fun savePreset(audioVersion: String, name: String, orders: List<Int>, id: String? = null): PlaybackLoopPreset {
        val existing = presets(audioVersion)
        val cleanName = name.trim()
        val cleanOrders = normalize(orders)
        require(cleanName.isNotEmpty() && cleanName.length <= MAX_NAME_LENGTH) { "Invalid loop name" }
        require(cleanOrders.isNotEmpty()) { "Select at least one chapter" }
        require(existing.none { it.id != id && it.name.equals(cleanName, ignoreCase = true) }) { "Loop name already exists" }
        require(id == null || existing.any { it.id == id }) { "Loop no longer exists" }
        val preset = PlaybackLoopPreset(id ?: UUID.randomUUID().toString(), cleanName, cleanOrders)
        val updated = if (id == null) existing + preset else existing.map { if (it.id == id) preset else it }
        val editor = putPresets(preferences.edit(), audioVersion, updated)
        if (selectedPresetId(audioVersion) == preset.id) {
            putSelection(editor, audioVersion, PlaybackLoopSelection(load(audioVersion).enabled, cleanOrders))
        }
        editor.apply()
        return preset
    }

    fun activatePreset(audioVersion: String, id: String): Boolean {
        val preset = presets(audioVersion).firstOrNull { it.id == id } ?: return false
        putSelection(preferences.edit().putString("${audioVersion}_presetId", id), audioVersion,
            PlaybackLoopSelection(true, preset.orders)).apply()
        return true
    }

    fun deletePreset(audioVersion: String, id: String) {
        val editor = putPresets(preferences.edit(), audioVersion, presets(audioVersion).filterNot { it.id == id })
        if (selectedPresetId(audioVersion) == id) {
            editor.remove("${audioVersion}_presetId")
            putSelection(editor, audioVersion, PlaybackLoopSelection(false, emptyList()))
        }
        editor.apply()
    }

    private fun putPresets(editor: SharedPreferences.Editor, audioVersion: String, presets: List<PlaybackLoopPreset>): SharedPreferences.Editor {
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(JSONObject().put("id", preset.id).put("name", preset.name).put("orders", JSONArray(preset.orders)))
        }
        return editor.putString("${audioVersion}_presets", array.toString())
    }

    fun disable(audioVersion: String) {
        if (preferences.getBoolean("${audioVersion}_enabled", false)) {
            preferences.edit().putBoolean("${audioVersion}_enabled", false).apply()
        }
    }

    fun onManualSelection(previousVersion: String, selectedVersion: String, order: Int) {
        if (previousVersion != selectedVersion) {
            disable(previousVersion)
            disable(selectedVersion)
        } else {
            val selection = load(selectedVersion)
            if (selection.enabled && order !in selection.orders) disable(selectedVersion)
        }
    }

    companion object {
        const val PREFERENCES = "playback_loops"
        const val MAX_NAME_LENGTH = 48
        private fun normalize(orders: List<Int>) = orders.filter { it in 1..1189 }.distinct()
    }
}
