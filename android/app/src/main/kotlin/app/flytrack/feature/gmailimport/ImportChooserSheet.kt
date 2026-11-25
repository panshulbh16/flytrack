package app.flytrack.feature.gmailimport

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.flytrack.BuildConfig
import app.flytrack.R

/** "From Gmail" is offered only with the release switch on and Google sign-in set up in this build. */
val isGmailImportAvailable: Boolean
    get() = BuildConfig.GMAIL_IMPORT_ENABLED && BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

/** "Import a ticket": from a file or screenshot (the existing importer) or from Gmail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportChooserSheet(onFromFile: () -> Unit, onFromGmail: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Text(
            text = stringResource(R.string.gmail_chooser_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
        )
        ChooserRow(R.drawable.ic_document, R.string.gmail_chooser_file, R.string.gmail_chooser_file_detail) {
            onDismiss()
            onFromFile()
        }
        ChooserRow(R.drawable.ic_mail, R.string.gmail_chooser_gmail, R.string.gmail_chooser_gmail_detail) {
            onDismiss()
            onFromGmail()
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ChooserRow(@DrawableRes icon: Int, @StringRes title: Int, @StringRes detail: Int, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        supportingContent = { Text(stringResource(detail)) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) { Text(stringResource(title)) }
}
