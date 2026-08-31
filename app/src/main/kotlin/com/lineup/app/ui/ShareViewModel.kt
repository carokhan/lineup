package com.lineup.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lineup.app.ai.GeminiNanoTranscriber
import com.lineup.app.calendar.CalendarWriter
import com.lineup.app.ai.PosterTranscriber
import com.lineup.app.core.Confidence
import com.lineup.app.core.EventDraft
import com.lineup.app.core.FieldConfidence
import com.lineup.app.ocr.MlKitOcrService
import com.lineup.app.ocr.OcrException
import com.lineup.app.ocr.OcrService
import com.lineup.app.ocr.OcrDebug
import com.lineup.app.ocr.OcrText
import com.lineup.app.parser.EventParser
import com.lineup.app.parser.LocalEventParser
import com.lineup.app.parser.ParseInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** What the confirmation screen is currently showing. */
sealed interface ShareUiState {
    /** Launched from the home screen with nothing to work on. */
    data object Idle : ShareUiState
    data object Working : ShareUiState
    data class Ready(val ocr: OcrText) : ShareUiState
    data class Failed(val message: String) : ShareUiState
}

/** The editable copy of an [EventDraft]. Survives rotation because it lives in the ViewModel. */
data class EventForm(
    val title: String = "",
    val date: LocalDate? = null,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val location: String = "",
    val description: String? = null,
    val confidence: FieldConfidence = FieldConfidence(),
) {
    fun toDraft() = EventDraft(
        title = title.trim().ifBlank { null },
        date = date,
        startTime = startTime,
        endTime = endTime,
        location = location.trim().ifBlank { null },
        description = description,
        confidence = confidence,
    )

    val canAddToCalendar: Boolean get() = title.isNotBlank() || date != null
}

private fun EventDraft.toForm() = EventForm(
    title = title.orEmpty(),
    date = date,
    startTime = startTime,
    endTime = endTime,
    location = location.orEmpty(),
    description = description,
    confidence = confidence,
)

class ShareViewModel(application: Application) : AndroidViewModel(application) {

    // Manual construction: the app is far too small to earn a DI framework.
    private val ocrService: OcrService = MlKitOcrService(application)
    private val parser: EventParser = LocalEventParser()
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() }
    private val transcriber: PosterTranscriber = GeminiNanoTranscriber()

    var state by mutableStateOf<ShareUiState>(ShareUiState.Idle)
        private set

    var form by mutableStateOf(EventForm())
        private set

    var preview by mutableStateOf<Bitmap?>(null)
        private set

    /** Whether the on-device model can be offered as a fallback, and what it is doing. */
    var aiStatus by mutableStateOf(PosterTranscriber.Status.UNSUPPORTED)
        private set

    var aiMessage by mutableStateOf<String?>(null)
        private set

    /** Writable calendars, empty until permission is granted. */
    var calendars by mutableStateOf<List<CalendarWriter.Target>>(emptyList())
        private set

    var selectedCalendarId by mutableStateOf<Long?>(null)
        private set

    val zone: ZoneId get() = clock().zone

    private var processedUri: Uri? = null

    /**
     * Entry point from the activity. Safe to call repeatedly: OCR runs once per distinct URI,
     * so recomposition and configuration changes never re-trigger it.
     */
    fun onImageReceived(uri: Uri?) {
        if (uri == null) {
            if (processedUri == null) state = ShareUiState.Idle
            return
        }
        if (uri == processedUri) return
        processedUri = uri
        state = ShareUiState.Working
        preview = null

        viewModelScope.launch {
            val previewBitmap = loadPreview(uri)
            preview = previewBitmap
            try {
                val (ocr, draft) = recognizeAndParse(uri)
                if (ocr.raw.isBlank()) {
                    state = ShareUiState.Failed("No text found in that image.")
                    form = EventForm()
                    return@launch
                }
                form = draft.toForm()
                state = ShareUiState.Ready(ocr)
                considerFallback(uri)
            } catch (e: OcrException) {
                state = ShareUiState.Failed(e.message ?: "Could not read that image.")
            } catch (e: Throwable) {
                state = ShareUiState.Failed("Something went wrong reading that image.")
            }
        }
    }

    /**
     * Debug-only: OCR a file already copied into the app's own files dir and dump the layout
     * to logcat. Lets `tools/ocr.sh` capture real ML Kit output for a poster in one command,
     * with no share sheet and no storage permission.
     */
    fun debugOcrFile(name: String) {
        if (!com.lineup.app.BuildConfig.DEBUG) return
        val file = java.io.File(getApplication<Application>().filesDir, name)
        if (!file.exists()) {
            android.util.Log.w("LineupOcr", "MISSING ${file.absolutePath}")
            return
        }
        processedUri = Uri.fromFile(file)
        state = ShareUiState.Working
        viewModelScope.launch {
            try {
                val (ocr, draft) = recognizeAndParse(Uri.fromFile(file))
                android.util.Log.d("LineupOcr", "PARSE\ttitle=${draft.title}\tdate=${draft.date}\tstart=${draft.startTime}\tend=${draft.endTime}\tlocation=${draft.location}")
                form = draft.toForm()
                state = ShareUiState.Ready(ocr)

                // Exercise the fallback from tools/ocr.sh as well, so it can be tested headlessly.
                aiStatus = transcriber.status()
                android.util.Log.d("LineupNano", "STATUS $aiStatus incomplete=${form.isIncomplete}")
                if (form.isIncomplete && aiStatus == PosterTranscriber.Status.READY) {
                    runFallback(Uri.fromFile(file))
                    android.util.Log.d(
                        "LineupNano",
                        "AFTER\ttitle=${form.title}\tdate=${form.date}\tstart=${form.startTime}\tend=${form.endTime}\tlocation=${form.location}",
                    )
                }
                android.util.Log.d("LineupOcr", "DRAFT done")
            } catch (e: Throwable) {
                android.util.Log.e("LineupOcr", "FAILED ${e.message}")
                state = ShareUiState.Failed(e.message ?: "failed")
            }
        }
    }

    private suspend fun recognizeAndParse(uri: Uri): Pair<OcrText, EventDraft> {
        val ocr = ocrService.recognize(uri)
        OcrDebug.dump(ocr)
        val draft = parser.parse(ParseInput(ocr, clock()))
        OcrDebug.dumpDraft(draft)
        return ocr to draft
    }

    // ------------------------------------------------------------ on-device fallback

    /**
     * The fields that make an event worth having; anything less is worth escalating.
     *
     * A doubtful location counts. OCR sees a screenshot's whole interface, so its weakest
     * location guesses come from app chrome; the model is asked for the poster artwork
     * only, which is chrome-free by construction.
     */
    private val EventForm.isIncomplete: Boolean
        get() = date == null ||
            startTime == null ||
            title.isBlank() ||
            location.isBlank() ||
            confidence.location == Confidence.LOW

    private suspend fun considerFallback(uri: Uri) {
        if (!form.isIncomplete) return
        aiStatus = transcriber.status()
        // Ready means the model is already on the device: free, offline, no reason to ask.
        if (aiStatus == PosterTranscriber.Status.READY) runFallback(uri)
    }

    /**
     * Offered as a button when the model still has to be fetched, so a multi-gigabyte
     * download is never started behind the user's back.
     */
    fun requestFallback() {
        val uri = processedUri ?: return
        viewModelScope.launch {
            if (aiStatus == PosterTranscriber.Status.DOWNLOADABLE) {
                aiMessage = "Downloading the on-device model…"
                val ok = transcriber.download { progress ->
                    aiMessage = "Downloading the on-device model… ${(progress * 100).toInt()}%"
                }
                if (!ok) {
                    aiMessage = "The on-device model could not be downloaded."
                    return@launch
                }
                aiStatus = PosterTranscriber.Status.READY
            }
            runFallback(uri)
        }
    }

    private suspend fun runFallback(uri: Uri) {
        aiMessage = "Re-reading the poster on-device… (a few seconds)"
        transcriber.warmup()
        val bitmap = decode(uri, AI_INPUT_PX)
        if (bitmap == null) {
            aiMessage = null
            return
        }
        val transcript = transcriber.transcribe(bitmap)
        aiMessage = when {
            transcript == null -> "The on-device model could not read it either."
            else -> {
                // The transcription goes through the same parser as OCR text, so every rule
                // that is already tested still applies.
                val rescued = parser.parse(ParseInput(transcript, clock()))
                val before = form
                form = form.filledFrom(rescued)
                if (form == before) "The on-device model found nothing new." else null
            }
        }
    }

    /**
     * Fills gaps only - a field the local parser found is never overwritten. Where the two
     * independently agree, the field stops being a guess.
     */
    private fun EventForm.filledFrom(ai: EventDraft): EventForm = copy(
        title = title.ifBlank { ai.title.orEmpty() },
        date = date ?: ai.date,
        startTime = startTime ?: ai.startTime,
        endTime = endTime ?: ai.endTime,
        location = if (locationYields(ai)) ai.location.orEmpty().ifBlank { location } else location,
        confidence = confidence.copy(
            title = merge(confidence.title, ai.confidence.title, title.isBlank(), title == ai.title),
            date = merge(confidence.date, ai.confidence.date, date == null, date == ai.date),
            startTime = merge(confidence.startTime, ai.confidence.startTime, startTime == null, startTime == ai.startTime),
            endTime = merge(confidence.endTime, ai.confidence.endTime, endTime == null, endTime == ai.endTime),
            location = merge(confidence.location, ai.confidence.location, locationYields(ai), location == ai.location),
        ),
    )

    /**
     * A guessed location is worth replacing, because the guess most likely came from the
     * host app's interface. Anything the parser was actually sure of is left alone.
     */
    private fun EventForm.locationYields(ai: EventDraft): Boolean =
        location.isBlank() ||
            (confidence.location == Confidence.LOW && !ai.location.isNullOrBlank())

    /**
     * A field taken wholesale from the model keeps the model's confidence; a field both
     * agreed on is corroborated and worth trusting more than either alone.
     */
    private fun merge(
        local: Confidence,
        ai: Confidence,
        wasMissing: Boolean,
        agrees: Boolean,
    ): Confidence = when {
        wasMissing -> ai
        agrees && ai != Confidence.NONE -> maxOf(local, ai)
        else -> local
    }

    // ---------------------------------------------------------------- calendars

    /**
     * Loads the writable calendars and restores the last one used, so choosing an account
     * is a one-time decision rather than a question on every poster.
     */
    fun refreshCalendars() {
        val context = getApplication<Application>()
        if (!CalendarWriter.hasPermission(context)) return
        val available = CalendarWriter.availableCalendars(context)
        calendars = available
        val remembered = prefs.getLong(KEY_CALENDAR_ID, -1L).takeIf { it > 0 }
        selectedCalendarId = remembered?.takeIf { id -> available.any { it.id == id } }
            ?: available.firstOrNull { it.isPrimary }?.id
            ?: available.firstOrNull()?.id
    }

    fun selectCalendar(id: Long) {
        selectedCalendarId = id
        prefs.edit().putLong(KEY_CALENDAR_ID, id).apply()
    }

    fun saveToCalendar(): CalendarWriter.Outcome =
        CalendarWriter.insert(getApplication(), form.toDraft(), zone, selectedCalendarId)
            .also { if (it.result == CalendarWriter.Result.SAVED) saved = it }

    /** Set once the event is really in the calendar, so the screen can say so. */
    var saved by mutableStateOf<CalendarWriter.Outcome?>(null)
        private set

    private val prefs by lazy {
        getApplication<Application>().getSharedPreferences("lineup", android.content.Context.MODE_PRIVATE)
    }

    /** Lets the user keep going by hand after OCR finds nothing useful. */
    fun startBlankDraft() {
        form = EventForm(confidence = FieldConfidence(title = Confidence.NONE))
        state = ShareUiState.Ready(OcrText.EMPTY)
    }

    fun updateTitle(value: String) { form = form.copy(title = value) }
    fun updateLocation(value: String) { form = form.copy(location = value) }
    fun updateDate(value: LocalDate?) { form = form.copy(date = value) }
    fun updateStartTime(value: LocalTime?) { form = form.copy(startTime = value) }
    fun updateEndTime(value: LocalTime?) { form = form.copy(endTime = value) }

    private suspend fun loadPreview(uri: Uri): Bitmap? = decode(uri, TARGET_PREVIEW_PX)

    private suspend fun decode(uri: Uri, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = getApplication<Application>().contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val largest = maxOf(bounds.outWidth, bounds.outHeight)
            if (largest <= 0) return@runCatching null
            var sample = 1
            while (largest / sample > maxPx) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }

    override fun onCleared() {
        super.onCleared()
        (transcriber as? GeminiNanoTranscriber)?.close()
    }

    private companion object {
        const val TARGET_PREVIEW_PX = 720
        const val KEY_CALENDAR_ID = "calendar_id"

        /** Small enough for the on-device model, large enough to keep hand-lettering legible. */
        const val AI_INPUT_PX = 1536
    }
}
