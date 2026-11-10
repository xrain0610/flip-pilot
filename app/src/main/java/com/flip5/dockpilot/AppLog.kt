package com.flip5.dockpilot

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer of what the app did. The UI shows it so a failed command can be
 * diagnosed on the device without a PC attached.
 */
object AppLog {

    private const val TAG = "DockPilot"
    private const val MAX_ENTRIES = 300

    enum class Level { INFO, WARN, ERROR }

    data class Entry(val time: String, val level: Level, val message: String)

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Newest entry first. */
    val entries: StateFlow<List<Entry>> = _entries

    fun info(message: String) = add(Level.INFO, message)

    fun warn(message: String) = add(Level.WARN, message)

    fun error(message: String) = add(Level.ERROR, message)

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
    }

    @Synchronized
    private fun add(level: Level, message: String) {
        when (level) {
            Level.INFO -> Log.i(TAG, message)
            Level.WARN -> Log.w(TAG, message)
            Level.ERROR -> Log.e(TAG, message)
        }
        val entry = Entry(timeFormat.format(Date()), level, message)
        _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
    }
}
