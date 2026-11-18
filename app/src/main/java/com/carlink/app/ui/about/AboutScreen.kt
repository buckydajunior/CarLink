package com.carlink.app.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.carlink.app.BuildConfig
import com.carlink.app.R
import com.carlink.app.ui.theme.CarLinkTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Credits and complete license texts, with no updater, network client or QR dependency. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var license by remember { mutableStateOf<String?>(null) }
    val sourceUrl = stringResource(R.string.carlink_f150_source_url)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            OutlinedButton(onBack, Modifier.heightIn(min = CarLinkTokens.dimens.minTouch)) {
                Text(stringResource(R.string.carlink_f150_back))
            }
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.carlink_f150_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.carlink_f150_credits), style = MaterialTheme.typography.bodyLarge)
            OutlinedButton({ license = "AGPL-3.0" }, Modifier.fillMaxWidth().heightIn(min = CarLinkTokens.dimens.minTouch)) {
                Text(stringResource(R.string.carlink_f150_agpl))
            }
            OutlinedButton({ license = "GPL-3.0" }, Modifier.fillMaxWidth().heightIn(min = CarLinkTokens.dimens.minTouch)) {
                Text(stringResource(R.string.carlink_f150_gpl))
            }
            OutlinedButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl))) },
                Modifier.fillMaxWidth().heightIn(min = CarLinkTokens.dimens.minTouch)) {
                Text(stringResource(R.string.carlink_f150_source))
            }
            OutlinedButton({ license = "Inter-OFL" }, Modifier.fillMaxWidth().heightIn(min = CarLinkTokens.dimens.minTouch)) {
                Text(stringResource(R.string.carlink_f150_ofl))
            }
            OutlinedButton({ license = "NOTICE" }, Modifier.fillMaxWidth().heightIn(min = CarLinkTokens.dimens.minTouch)) {
                Text(stringResource(R.string.carlink_f150_notices))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    license?.let { name ->
        val text by produceState("", name) {
            value = withContext(Dispatchers.IO) { context.assets.open(if (name == "Inter-OFL") "licenses/Inter-OFL.txt" else "carlink-licenses/$name.txt").bufferedReader().use { it.readText() } }
        }
        AlertDialog(onDismissRequest = { license = null },
            title = { Text(name) },
            text = { SelectionContainer { Text(text, Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) } },
            confirmButton = { OutlinedButton({ license = null }) { Text(stringResource(R.string.carlink_f150_close)) } })
    }
}
