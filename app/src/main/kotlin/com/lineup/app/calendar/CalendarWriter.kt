package com.lineup.app.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.lineup.app.core.EventDraft
import com.lineup.app.core.EventTimingCalculator
import java.time.ZoneId

/**
 * Writes the event straight into the user's calendar, so confirming in this app is the
 * last step rather than the second-to-last.
 *
 * This is still the Android calendar provider, not the Google Calendar API: no account,
 * no OAuth, no network. It just needs permission to write to whichever calendar the user
 * already syncs.
 */
object CalendarWriter {

    val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    data class Outcome(val result: Result, val eventId: Long? = null, val calendarName: String? = null)

    enum class Result {
        SAVED,

        /** The user has not granted calendar access. */
        NO_PERMISSION,

        /** No calendar on this device can be written to. */
        NO_CALENDAR,

        /** Without a date there is nothing meaningful to write. */
        NO_DATE,

        FAILED,
    }

    /** A calendar the user could save into. */
    data class Target(
        val id: Long,
        val displayName: String,
        val accountName: String,
        val color: Int,
        val isPrimary: Boolean,
    ) {
        /** "Work" is ambiguous on its own; the account it belongs to is what disambiguates. */
        val subtitle: String? get() = accountName.takeIf { it.isNotBlank() && it != displayName }
    }

    fun hasPermission(context: Context): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun insert(context: Context, draft: EventDraft, zone: ZoneId, targetId: Long?): Outcome {
        if (!hasPermission(context)) return Outcome(Result.NO_PERMISSION)
        if (draft.date == null) return Outcome(Result.NO_DATE)
        val timing = EventTimingCalculator.computeForProvider(draft, zone)
            ?: return Outcome(Result.NO_DATE)
        val calendars = availableCalendars(context)
        val target = calendars.firstOrNull { it.id == targetId }
            ?: calendars.firstOrNull { it.isPrimary }
            ?: calendars.firstOrNull()
            ?: return Outcome(Result.NO_CALENDAR)
        val calendarId = target.id

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.DTSTART, timing.beginMillis)
            put(CalendarContract.Events.DTEND, timing.endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, timing.timeZoneId)
            put(CalendarContract.Events.ALL_DAY, if (timing.allDay) 1 else 0)
            draft.title?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.TITLE, it) }
            draft.location?.takeIf { it.isNotBlank() }
                ?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            draft.description?.takeIf { it.isNotBlank() }
                ?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }

        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                Outcome(Result.SAVED, uri.lastPathSegment?.toLongOrNull(), target.displayName)
            } else {
                Outcome(Result.FAILED)
            }
        } catch (_: SecurityException) {
            Outcome(Result.NO_PERMISSION)
        } catch (_: Exception) {
            Outcome(Result.FAILED)
        }
    }

    /**
     * Whether an event this app created is still really in the provider. Null means we
     * cannot tell (no permission), which is not the same as "it is gone".
     */
    fun stillExists(context: Context, eventId: Long): Boolean? {
        if (!hasPermission(context)) return null
        return try {
            val cursor = context.contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                arrayOf(CalendarContract.Events._ID, CalendarContract.Events.DELETED),
                null,
                null,
                null,
            ) ?: return null
            cursor.use { it.moveToFirst() && it.getInt(1) == 0 }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Every calendar the user may write to, primary first. Requires READ_CALENDAR, so this
     * returns empty until permission is granted.
     */
    fun availableCalendars(context: Context): List<Target> {
        if (!hasPermission(context)) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.VISIBLE,
        )
        return try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                null,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        if (cursor.getInt(5) != 1) continue // hidden calendars are not offers
                        add(
                            Target(
                                id = cursor.getLong(0),
                                displayName = cursor.getString(1).orEmpty().ifBlank { "Calendar" },
                                accountName = cursor.getString(2).orEmpty(),
                                color = cursor.getInt(3),
                                isPrimary = cursor.getInt(4) == 1,
                            )
                        )
                    }
                }.sortedByDescending { it.isPrimary }
            }.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
