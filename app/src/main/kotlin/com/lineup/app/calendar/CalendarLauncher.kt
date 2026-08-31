package com.lineup.app.calendar

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.lineup.app.core.EventDraft
import com.lineup.app.core.EventTimingCalculator
import java.time.ZoneId

/**
 * Hands the draft to whatever calendar app the user already has. No provider write access,
 * no accounts, no OAuth - the user confirms and saves in their own calendar UI.
 */
object CalendarLauncher {

    fun buildIntent(draft: EventDraft, zone: ZoneId): Intent {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
        }

        draft.title?.takeIf { it.isNotBlank() }?.let {
            intent.putExtra(CalendarContract.Events.TITLE, it)
        }
        draft.location?.takeIf { it.isNotBlank() }?.let {
            intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it)
        }
        draft.description?.takeIf { it.isNotBlank() }?.let {
            intent.putExtra(CalendarContract.Events.DESCRIPTION, it)
        }

        EventTimingCalculator.compute(draft, zone)?.let { timing ->
            if (timing.allDay) intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, timing.beginMillis)
            intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, timing.endMillis)
        }

        return intent
    }

    /** Returns false when the device has no app that can create calendar events. */
    fun launch(context: Context, draft: EventDraft, zone: ZoneId): Boolean {
        val intent = buildIntent(draft, zone).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
