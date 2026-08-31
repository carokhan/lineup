package com.lineup.app.calendar

import android.content.Context
import com.lineup.app.core.SavedEvent
import com.lineup.app.core.SavedEventCodec

/** The last few events this app created, newest first. */
class SavedEventStore(context: Context) {

    private val prefs = context.getSharedPreferences("lineup", Context.MODE_PRIVATE)

    fun recent(): List<SavedEvent> = SavedEventCodec.decode(prefs.getString(KEY, null))

    fun add(event: SavedEvent) {
        val updated = (listOf(event) + recent().filterNot { it.eventId == event.eventId })
            .take(MAX_REMEMBERED)
        prefs.edit().putString(KEY, SavedEventCodec.encode(updated)).apply()
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private companion object {
        const val KEY = "saved_events"
        const val MAX_REMEMBERED = 20
    }
}
