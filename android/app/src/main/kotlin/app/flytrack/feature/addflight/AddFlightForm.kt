package app.flytrack.feature.addflight

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.flytrack.R
import app.flytrack.core.model.FlightStatusTone
import app.flytrack.core.time.FlightTimeFormat
import app.flytrack.core.time.RelativeDay
import app.flytrack.ui.components.IconAction
import app.flytrack.ui.components.PrimaryButton
import app.flytrack.ui.components.relativeDayText
import app.flytrack.ui.theme.FlyTrackTheme
import kotlinx.datetime.LocalDate

/** [text] with its [key] word in terracotta italics: the brief's emphasis for the word that matters. */
@Composable
internal fun emphasised(text: String, key: String): AnnotatedString {
    val accent = FlyTrackTheme.accentText
    return remember(text, key, accent) {
        buildAnnotatedString {
            append(text)
            val start = text.indexOf(key)
            if (key.isNotEmpty() && start >= 0) {
                addStyle(SpanStyle(fontStyle = FontStyle.Italic, color = accent), start, start + key.length)
            }
        }
    }
}

/** "Wed, 25 Nov", with the year when it is not this year (the picker reaches twelve months ahead). */
internal fun formDateText(date: LocalDate, today: LocalDate): String =
    if (date.year == today.year) FlightTimeFormat.weekdayDayMonth(date) else FlightTimeFormat.fullDate(date)

@Composable
internal fun AddFlightForm(
    state: AddFlightUiState,
    onFlightInputChange: (String) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onPickDate: () -> Unit,
    onPnrInputChange: (String) -> Unit,
    onSeatInputChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editable = !state.isTracking
    Column(modifier = modifier.fillMaxWidth()) {
        state.prefillNotice?.let { notice ->
            PrefillNoticeRow(notice, state)
            Spacer(Modifier.height(16.dp))
        }

        FlightNumberField(state, editable, onFlightInputChange, onSearch)
        Spacer(Modifier.height(20.dp))

        DateSection(state, editable, onDateChange, onPickDate)
        Spacer(Modifier.height(20.dp))

        FieldLabel(stringResource(R.string.addflight_details_label))
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PnrField(state, onPnrInputChange, Modifier.weight(1.5f))
            SeatField(state, onSeatInputChange, Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))

        PrimaryButton(
            text = stringResource(R.string.addflight_search),
            onClick = onSearch,
            modifier = Modifier.fillMaxWidth(),
            // Stays enabled with an empty field: pressing it then explains what is missing.
            enabled = state.isBackendConfigured && editable,
            loading = state.isSearching,
            icon = painterResource(R.drawable.ic_search),
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PrefillNoticeRow(notice: PrefillNotice, state: AddFlightUiState) {
    val text = when (notice) {
        PrefillNotice.CheckDetails -> stringResource(R.string.addflight_prefill_ok)
        PrefillNotice.FlightUnreadable -> stringResource(R.string.addflight_prefill_flight_unreadable)
        PrefillNotice.DateUnreadable -> stringResource(R.string.addflight_prefill_date_unreadable)
        is PrefillNotice.DateOutOfRange -> stringResource(
            R.string.addflight_prefill_date_out_of_range,
            FlightTimeFormat.fullDate(notice.ticketDate),
            FlightTimeFormat.fullDate(state.firstSelectableDate),
            FlightTimeFormat.fullDate(state.lastSelectableDate),
        )
    }
    NoticeSurface(
        text = text,
        icon = painterResource(if (notice == PrefillNotice.CheckDetails) R.drawable.ic_ticket else R.drawable.ic_info),
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
    )
}

/** A quiet, hairlined note: icon plus text, with optional title and action below. */
@Composable
internal fun NoticeSurface(
    text: String,
    icon: Painter,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = if (actionLabel != null) 4.dp else 12.dp)) {
            Icon(painter = icon, contentDescription = null, modifier = Modifier.padding(top = 1.dp).size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (title != null) {
                    Text(text = title, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(2.dp))
                }
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) {
                        Text(text = actionLabel, style = MaterialTheme.typography.labelLarge, color = content)
                    }
                }
            }
        }
    }
}

@Composable
private fun FlightNumberField(
    state: AddFlightUiState,
    enabled: Boolean,
    onChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    val errorText = when (state.flightError) {
        FlightInputError.REQUIRED -> stringResource(R.string.addflight_flight_error_required)
        FlightInputError.NOT_A_FLIGHT_NUMBER -> stringResource(R.string.addflight_flight_error_invalid)
        null -> null
    }
    OutlinedTextField(
        value = state.flightInput,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().semantics { if (errorText != null) error(errorText) },
        enabled = enabled,
        textStyle = FlyTrackTheme.type.numeralSmall,
        label = { Text(stringResource(R.string.addflight_flight_label)) },
        placeholder = {
            Text(
                text = stringResource(R.string.addflight_flight_placeholder),
                style = FlyTrackTheme.type.numeralSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        },
        trailingIcon = if (state.flightInput.isNotEmpty() && enabled) {
            {
                IconAction(
                    icon = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.addflight_flight_clear),
                    onClick = { onChange("") },
                )
            }
        } else {
            null
        },
        supportingText = {
            when {
                errorText != null -> Text(errorText)
                state.normalisedFlight != null -> FlightAccepted(state.normalisedFlight)
                else -> Text(stringResource(R.string.addflight_flight_hint))
            }
        },
        isError = errorText != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        shape = MaterialTheme.shapes.small,
    )
}

/** Confirms what will be searched, e.g. "AI 101" for the typed "ai101": a tick plus words, not colour alone. */
@Composable
private fun FlightAccepted(normalised: String) {
    val color = FlyTrackTheme.statusColors.forTone(FlightStatusTone.ON_TIME).content
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.ic_check),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = color,
        )
        Spacer(Modifier.width(4.dp))
        Text(text = stringResource(R.string.addflight_flight_valid, normalised), color = color)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateSection(
    state: AddFlightUiState,
    enabled: Boolean,
    onDateChange: (LocalDate) -> Unit,
    onPickDate: () -> Unit,
) {
    val shortcuts = listOf(
        state.today.plusDays(-1) to RelativeDay.Yesterday,
        state.today to RelativeDay.Today,
        state.today.plusDays(1) to RelativeDay.Tomorrow,
    )
    val isOtherDate = shortcuts.none { (day, _) -> day == state.date }

    Column {
        FieldLabel(stringResource(R.string.addflight_date_label))
        Spacer(Modifier.height(4.dp))
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            shortcuts.forEach { (day, relative) ->
                DayChip(
                    text = relativeDayText(relative),
                    selected = day == state.date,
                    enabled = enabled && state.isSelectable(day),
                    onClick = { onDateChange(day) },
                )
            }
            val otherText = if (isOtherDate) formDateText(state.date, state.today) else stringResource(R.string.addflight_date_other)
            val otherDescription = stringResource(R.string.addflight_date_pick_description, otherText)
            DayChip(
                text = otherText,
                selected = isOtherDate,
                enabled = enabled,
                onClick = onPickDate,
                icon = painterResource(R.drawable.ic_calendar),
                modifier = Modifier.semantics { contentDescription = otherDescription },
            )
        }
        Spacer(Modifier.height(4.dp))
        if (state.dateRejected) {
            Text(
                text = stringResource(
                    R.string.addflight_date_error,
                    FlightTimeFormat.fullDate(state.firstSelectableDate),
                    FlightTimeFormat.fullDate(state.lastSelectableDate),
                ),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            // The shortcuts hide the actual date, so it is spelled out next to the rule it follows.
            Text(
                text = FlightTimeFormat.fullDate(state.date),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.addflight_date_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One choice of the date row. The filled chip is also announced as selected, so colour is not the only cue. */
@Composable
private fun DayChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = CircleShape,
        color = if (selected) colors.primary else Color.Transparent,
        contentColor = when {
            selected -> colors.onPrimary
            enabled -> colors.onSurface
            else -> colors.onSurface.copy(alpha = 0.38f)
        },
        border = if (selected) null else BorderStroke(1.dp, if (enabled) colors.outline else colors.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(painter = icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text = text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun PnrField(state: AddFlightUiState, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val errorText = if (state.pnrInvalid) stringResource(R.string.addflight_pnr_error, MIN_PNR_LENGTH, MAX_PNR_LENGTH) else null
    OutlinedTextField(
        value = state.pnrInput,
        onValueChange = onChange,
        modifier = modifier.semantics { if (errorText != null) error(errorText) },
        label = { Text(stringResource(R.string.addflight_pnr_label)) },
        supportingText = { Text(errorText ?: stringResource(R.string.addflight_pnr_hint)) },
        isError = errorText != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Next,
        ),
        shape = MaterialTheme.shapes.small,
    )
}

@Composable
private fun SeatField(state: AddFlightUiState, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val errorText = if (state.seatInvalid) stringResource(R.string.addflight_seat_error) else null
    OutlinedTextField(
        value = state.seatInput,
        onValueChange = onChange,
        modifier = modifier.semantics { if (errorText != null) error(errorText) },
        label = { Text(stringResource(R.string.addflight_seat_label)) },
        supportingText = { Text(errorText ?: stringResource(R.string.addflight_seat_hint)) },
        isError = errorText != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        shape = MaterialTheme.shapes.small,
    )
}

/** Material's calendar, limited to the dates the search accepts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DepartureDatePickerDialog(
    selected: LocalDate,
    first: LocalDate,
    last: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectableDates = remember(first, last) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis in first.toUtcMillis()..last.toUtcMillis()

            override fun isSelectableYear(year: Int): Boolean = year in first.year..last.year
        }
    }
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = selected.toUtcMillis(),
        yearRange = first.year..last.year,
        selectableDates = selectableDates,
    )
    val picked = pickerState.selectedDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { picked?.let { onConfirm(utcMillisToLocalDate(it)) } }, enabled = picked != null) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    ) {
        DatePicker(
            state = pickerState,
            title = {
                Text(
                    text = stringResource(R.string.addflight_date_picker_title),
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp),
                )
            },
        )
    }
}
