package app.flytrack.feature.importticket

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.flytrack.R
import app.flytrack.core.model.TrackSource
import app.flytrack.ui.components.SecondaryButton
import app.flytrack.ui.nav.LocalAppNavigator
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.datetime.toKotlinLocalDate

/**
 * "Scan boarding pass": Google's code scanner (Play services, no camera permission) reads the
 * barcode, [parseBoardingPass] reads the IATA BCBP text, and add-flight opens filled in.
 */
@Composable
fun ScanBoardingPassButton() {
    val context = LocalContext.current
    val navigator = LocalAppNavigator.current
    var legs by remember { mutableStateOf(emptyList<BoardingPassLeg>()) }

    fun open(leg: BoardingPassLeg) {
        legs = emptyList()
        // ponytail: there is no "scan" track source on the server (check constraint); a camera read is closest to a screenshot.
        navigator.openAddFlightFromImport(leg.flight.display, leg.date?.toString(), leg.pnr, TrackSource.SCREENSHOT.wire, leg.seat)
    }

    SecondaryButton(
        text = stringResource(R.string.scan_action),
        modifier = Modifier.padding(top = 8.dp),
        onClick = {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_PDF417, Barcode.FORMAT_AZTEC, Barcode.FORMAT_QR_CODE)
                .build()
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { barcode ->
                    val found = parseBoardingPass(barcode.rawValue.orEmpty(), java.time.LocalDate.now().toKotlinLocalDate())
                    when (found.size) {
                        0 -> Toast.makeText(context, R.string.scan_not_a_boarding_pass, Toast.LENGTH_LONG).show()
                        1 -> open(found.single())
                        else -> legs = found
                    }
                }
                .addOnFailureListener { Toast.makeText(context, R.string.scan_unavailable, Toast.LENGTH_LONG).show() }
        },
    )

    if (legs.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { legs = emptyList() },
            title = { Text(stringResource(R.string.scan_choose_flight)) },
            text = {
                Column {
                    legs.forEach { leg ->
                        TextButton(onClick = { open(leg) }) { Text("${leg.flight.display}  ${leg.fromIata} → ${leg.toIata}") }
                    }
                }
            },
            confirmButton = {},
        )
    }
}
