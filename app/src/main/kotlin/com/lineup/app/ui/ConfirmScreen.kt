package com.lineup.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lineup.app.ai.PosterTranscriber
import com.lineup.app.calendar.CalendarLauncher
import com.lineup.app.calendar.CalendarWriter
import com.lineup.app.core.Confidence
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val DATE_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")
private val TIME_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

@Composable
fun ConfirmScreen(
    viewModel: ShareViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val state = viewModel.state
    val form = viewModel.form

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(onClose = onClose)

            when (state) {
                is ShareUiState.Idle -> IdleBody()
                is ShareUiState.Working -> WorkingBody(viewModel)
                is ShareUiState.Failed -> FailedBody(
                    message = state.message,
                    onManual = viewModel::startBlankDraft,
                )
                is ShareUiState.Ready -> {
                    PreviewThumbnail(viewModel)
                    if (form.date == null || form.startTime == null) {
                        MissingFieldsHint(form)
                    }
                    EventFields(viewModel)
                    SaveSection(
                        viewModel = viewModel,
                        snackbarHost = snackbarHost,
                        onSaved = onClose,
                    )
                    FallbackSection(viewModel)
                    RawTextSection(state.ocr.raw)
                    Spacer(Modifier.size(24.dp))
                }
            }
        }
    }
}

/**
 * The escalation path when OCR could not read the poster. Only ever shown when something
 * important is still missing, and a model download is always an explicit choice.
 */
@Composable
private fun FallbackSection(viewModel: ShareViewModel) {
    val message = viewModel.aiMessage
    val status = viewModel.aiStatus
    val form = viewModel.form
    val incomplete = form.date == null || form.startTime == null || form.title.isBlank()

    if (message != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!message.endsWith(".")) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    if (!incomplete) return
    if (status != PosterTranscriber.Status.DOWNLOADABLE) return

    OutlinedButton(
        onClick = viewModel::requestFallback,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Read it with on-device AI")
    }
    Text(
        "Runs entirely on your phone. Downloads the model once.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The last step. "Add to Calendar" writes the event and shows what happened, so the user
 * never has to go and check whether it worked; handing off to a calendar app is kept as a
 * way out for people who want its full editor.
 */
@Composable
private fun SaveSection(
    viewModel: ShareViewModel,
    snackbarHost: SnackbarHostState,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val form = viewModel.form

    viewModel.saved?.let { outcome ->
        SavedPanel(outcome, form, onDone = onSaved)
        return
    }

    fun handOff() {
        if (!CalendarLauncher.launch(context, form.toDraft(), viewModel.zone)) {
            scope.launch { snackbarHost.showSnackbar("No calendar app found on this device.") }
        }
    }

    fun save() {
        val outcome = viewModel.saveToCalendar()
        if (outcome.result == CalendarWriter.Result.SAVED) return // the panel takes over
        scope.launch {
            snackbarHost.showSnackbar(
                when (outcome.result) {
                    CalendarWriter.Result.NO_CALENDAR -> "No writable calendar; opening your calendar app."
                    CalendarWriter.Result.NO_DATE -> "Pick a date first, or set it in your calendar app."
                    else -> "Couldn't save directly; opening your calendar app."
                }
            )
        }
        if (outcome.result != CalendarWriter.Result.NO_DATE) handOff()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) {
            viewModel.refreshCalendars()
            // With one calendar there is nothing to choose, so finish the job in one tap.
            if (viewModel.calendars.size <= 1) save()
        } else {
            handOff()
        }
    }

    val hasPermission = CalendarWriter.hasPermission(context)
    LaunchedEffect(hasPermission) { if (hasPermission) viewModel.refreshCalendars() }

    if (viewModel.calendars.size > 1) {
        CalendarPicker(viewModel)
    }

    Button(
        onClick = { if (hasPermission) save() else permissionLauncher.launch(CalendarWriter.PERMISSIONS) },
        enabled = form.canAddToCalendar,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Add to Calendar")
    }

    TextButton(onClick = ::handOff, modifier = Modifier.fillMaxWidth()) {
        Text("Open in calendar app instead", style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Saying so on screen, rather than in a toast on a closing activity, is the difference
 * between "saved" and "it didn't seem to do anything".
 */
@Composable
private fun SavedPanel(
    outcome: CalendarWriter.Outcome,
    form: EventForm,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Added to ${outcome.calendarName ?: "your calendar"}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Text(
            buildString {
                form.date?.let { append(it.format(DATE_LABEL)) }
                form.startTime?.let { append(" · ").append(it.format(TIME_LABEL)) }
            }.trim().ifEmpty { form.title },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }

    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }

    outcome.eventId?.let { id ->
        TextButton(
            onClick = {
                val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setData(uri)) }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("View in calendar", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * A dialog rather than a dropdown on purpose: a menu anchored here opens straight over the
 * "Add to Calendar" button, so a tap meant for the button silently changes the calendar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarPicker(viewModel: ShareViewModel) {
    var open by rememberSaveable { mutableStateOf(false) }
    val selected = viewModel.calendars.firstOrNull { it.id == viewModel.selectedCalendarId }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = true }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CalendarDot(selected?.color)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Save to",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                selected?.displayName ?: "Choose a calendar",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            selected?.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Change calendar")
    }

    if (!open) return
    BasicAlertDialog(onDismissRequest = { open = false }) {
        Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(
                    "Save to",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    viewModel.calendars.forEach { target ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectCalendar(target.id)
                                    open = false
                                }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CalendarDot(target.color)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(target.displayName, style = MaterialTheme.typography.bodyMedium)
                                target.subtitle?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (target.id == viewModel.selectedCalendarId) {
                                Icon(Icons.Default.Check, contentDescription = "Selected")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDot(color: Int?) {
    Box(
        Modifier
            .size(12.dp)
            .background(
                if (color != null && color != 0) Color(color) else MaterialTheme.colorScheme.primary,
                CircleShape,
            )
    )
}

@Composable
private fun MissingFieldsHint(form: EventForm) {
    val missing = buildList {
        if (form.date == null) add("date")
        if (form.startTime == null) add("time")
    }
    if (missing.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Couldn't read the ${missing.joinToString(" or ")} — tap the image to enlarge it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Header(onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "New event",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Close")
        }
    }
}

@Composable
private fun IdleBody() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Share a poster or screenshot to Lineup", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Open any image, tap Share, and pick Lineup. The event details are read on your " +
                "device and handed to your calendar app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WorkingBody(viewModel: ShareViewModel) {
    PreviewThumbnail(viewModel)
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text("Reading the image…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FailedBody(message: String, onManual: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = onManual) { Text("Enter details manually") }
    }
}

/**
 * When extraction fails the poster itself is the fallback, so the whole image has to be
 * readable - a cropped strip hides exactly the corner the date is usually written in.
 * Tap to swap between a compact strip and the full image.
 */
@Composable
private fun PreviewThumbnail(viewModel: ShareViewModel, expandable: Boolean = true) {
    val bitmap = viewModel.preview ?: return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = if (expanded) "Shared image, tap to shrink" else "Shared image, tap to enlarge",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = if (expanded) 520.dp else 200.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .then(if (expandable) Modifier.clickable { expanded = !expanded } else Modifier),
    )
}

@Composable
private fun EventFields(viewModel: ShareViewModel) {
    val form = viewModel.form
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var editingTime by rememberSaveable { mutableStateOf<String?>(null) }

    OutlinedTextField(
        value = form.title,
        onValueChange = viewModel::updateTitle,
        label = { Text("Title") },
        placeholder = { Text("Not found — add a title") },
        singleLine = true,
        isError = form.title.isBlank(),
        supportingText = uncertainHint(form.confidence.title, form.title.isNotBlank()),
        modifier = Modifier.fillMaxWidth(),
    )

    PickerField(
        label = "Date",
        value = form.date?.format(DATE_LABEL),
        confidence = form.confidence.date,
        onClick = { showDatePicker = true },
    )

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) {
            PickerField(
                label = "Start",
                value = form.startTime?.format(TIME_LABEL),
                confidence = form.confidence.startTime,
                onClick = { editingTime = "start" },
            )
        }
        Box(Modifier.weight(1f)) {
            PickerField(
                label = "End (optional)",
                value = form.endTime?.format(TIME_LABEL),
                confidence = form.confidence.endTime,
                optional = true,
                onClick = { editingTime = "end" },
            )
        }
    }

    OutlinedTextField(
        value = form.location,
        onValueChange = viewModel::updateLocation,
        label = { Text("Location") },
        placeholder = { Text("Not found — optional") },
        singleLine = true,
        supportingText = uncertainHint(form.confidence.location, form.location.isNotBlank()),
        modifier = Modifier.fillMaxWidth(),
    )

    if (showDatePicker) {
        DatePickerSheet(
            initial = form.date,
            onDismiss = { showDatePicker = false },
            onPicked = {
                viewModel.updateDate(it)
                showDatePicker = false
            },
        )
    }

    editingTime?.let { which ->
        TimePickerSheet(
            initial = if (which == "start") form.startTime else form.endTime,
            onDismiss = { editingTime = null },
            onCleared = {
                if (which == "start") viewModel.updateStartTime(null) else viewModel.updateEndTime(null)
                editingTime = null
            },
            onPicked = {
                if (which == "start") viewModel.updateStartTime(it) else viewModel.updateEndTime(it)
                editingTime = null
            },
        )
    }
}

private fun uncertainHint(confidence: Confidence, hasValue: Boolean): (@Composable () -> Unit)? = when {
    !hasValue -> null
    confidence.needsReview && confidence != Confidence.NONE -> {
        { Text("Guessed — please check", style = MaterialTheme.typography.labelSmall) }
    }
    else -> null
}

@Composable
private fun PickerField(
    label: String,
    value: String?,
    confidence: Confidence,
    optional: Boolean = false,
    onClick: () -> Unit,
) {
    OutlinedTextField(
        value = value.orEmpty(),
        onValueChange = {},
        label = { Text(label) },
        placeholder = { Text(if (optional) "None" else "Not found — tap to set") },
        readOnly = true,
        enabled = false,
        singleLine = true,
        trailingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
        supportingText = uncertainHint(confidence, value != null),
        colors = disabledFieldColors(),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}

@Composable
private fun disabledFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    disabledTextColor = MaterialTheme.colorScheme.onSurface,
    disabledBorderColor = MaterialTheme.colorScheme.outline,
    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    disabledSupportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(
    initial: LocalDate?,
    onDismiss: () -> Unit,
    onPicked: (LocalDate) -> Unit,
) {
    val state = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = initial?.let { DateConversions.toUtcMillis(it) },
    )
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = { state.selectedDateMillis?.let { onPicked(DateConversions.toLocalDate(it)) } },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        androidx.compose.material3.DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerSheet(
    initial: LocalTime?,
    onDismiss: () -> Unit,
    onCleared: () -> Unit,
    onPicked: (LocalTime) -> Unit,
) {
    val state = androidx.compose.material3.rememberTimePickerState(
        initialHour = initial?.hour ?: 19,
        initialMinute = initial?.minute ?: 0,
        is24Hour = false,
    )
    androidx.compose.material3.BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.material3.TimePicker(state = state)
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onCleared) { Text("Clear") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onPicked(LocalTime.of(state.hour, state.minute)) }) {
                        Text("OK")
                    }
                }
            }
        }
    }
}

@Composable
private fun RawTextSection(raw: String) {
    if (raw.isBlank()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide recognized text" else "Show recognized text")
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                text = raw,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            )
        }
    }
}

private object DateConversions {
    fun toUtcMillis(date: LocalDate): Long =
        date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    fun toLocalDate(millis: Long): LocalDate =
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
}
