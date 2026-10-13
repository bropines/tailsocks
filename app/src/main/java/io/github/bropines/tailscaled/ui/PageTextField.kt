package io.github.bropines.tailscaled.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.github.bropines.tailscaled.R

/*
 * A text field that stands in a page — a step of the onboarding, a card of the main screen,
 * a form of several fields — and not alone in a dialog opened to type one thing.
 *
 * On a television such a field is a stop the remote does not walk past: it raises the
 * on-screen keyboard the moment the remote lands on it, and the keyboard then takes the
 * arrows (seen on the Android TV emulator, by everyone who met one). So there the field is a
 * row with its label and what it holds, and OK opens a dialog around the field itself — what
 * ProxySettingRow (ui/TaildriveActivity.kt) and TvQueryField (ui/TvReading.kt) do for their
 * screens. A dialog with one field in it needs none of this: opening it is asking to type.
 */

/**
 * [OutlinedTextField] for a page, one line of text: on a handheld exactly that, with these
 * parameters and no others; on a television a [TvFieldRow].
 */
@Composable
fun PageTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    labelFontSize: TextUnit = TextUnit.Unspecified,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    shape: Shape = OutlinedTextFieldDefaults.shape,
) {
    if (isTv) {
        TvFieldRow(
            value = value,
            onValueChange = onValueChange,
            label = label,
            // What a labelled field keeps above its outline for the label: fields stacked
            // without a gap of their own would stand rim to rim as rows.
            modifier = modifier.padding(top = 8.dp),
            placeholder = placeholder,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
        )
        return
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = labelFontSize) },
        modifier = modifier,
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        shape = shape,
    )
}

/**
 * A field as a television shows it in a page: its [label] over what it holds — the label
 * alone, or the [placeholder], while it is empty — in an outlined row that OK opens as a
 * dialog around the field. What is typed goes to [onValueChange] as it is typed, exactly as
 * from the field this row stands in for, so a caller that refuses a character refuses it
 * here too. Done, on the keyboard or under the field, closes the dialog; so does Back, after
 * it has closed the keyboard.
 *
 * [trailingIcon] moves into the dialog's field: in the row it would be a second stop. A field
 * that stands under a heading of its own and has no [label] passes that heading as [title],
 * for the dialog to be called something.
 */
@Composable
fun TvFieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    title: String = label,
    placeholder: String? = null,
    prefix: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    // Read here: the dialog is a window of its own and would answer in the system language.
    val done = stringResource(R.string.tv_field_done)
    val colors = MaterialTheme.colorScheme
    val shown = remember(value, visualTransformation) { visualTransformation.filter(AnnotatedString(value)).text.text }
    Surface(
        onClick = { editing = true },
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        border = BorderStroke(1.dp, if (isError) colors.error else colors.outline),
        modifier = modifier.heightIn(min = 56.dp)
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        if (label.isEmpty()) placeholder.orEmpty() else label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    if (label.isNotEmpty()) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        prefix.orEmpty() + shown,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = if (singleLine) 1 else 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (supportingText != null) {
                    Text(supportingText, style = MaterialTheme.typography.bodySmall, color = if (isError) colors.error else colors.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Edit, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
    if (editing) {
        val field = remember { FocusRequester() }
        // The field's own state, as the String overload of a text field keeps it — but with
        // the cursor after what is already there, where a remote's typing goes on from.
        var typed by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
        val current = typed.copy(text = value)
        SideEffect { if (current.text != typed.text) typed = current }
        var lastText by remember(value) { mutableStateOf(value) }
        val close = { editing = false }
        // It is what was asked for by pressing the row: the keyboard may come up.
        LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
        AppAlertDialog(
            onDismissRequest = close,
            title = { Text(title.ifEmpty { placeholder.orEmpty() }) },
            text = {
                OutlinedTextField(
                    value = current,
                    onValueChange = { next ->
                        typed = next
                        val changed = lastText != next.text
                        lastText = next.text
                        if (changed) onValueChange(next.text)
                    },
                    placeholder = placeholder?.let { { Text(it) } },
                    prefix = prefix?.let { { Text(it) } },
                    supportingText = supportingText?.let { { Text(it) } },
                    isError = isError,
                    singleLine = singleLine,
                    keyboardOptions = if (singleLine) keyboardOptions.copy(imeAction = ImeAction.Done) else keyboardOptions,
                    keyboardActions = KeyboardActions(onDone = { close() }),
                    visualTransformation = visualTransformation,
                    trailingIcon = trailingIcon,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().focusRequester(field)
                )
            },
            confirmButton = { TextButton(onClick = close) { Text(done) } }
        )
    }
}
