package com.carlink.app.ui.wired

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.carlink.app.ui.CarLinkViewModel
import com.carlink.core.core.LinkProblem

/** Only microphone and the wired bridge's VPN consent can be requested by this product. */
@Composable
internal fun rememberProblemFix(vm: CarLinkViewModel): (LinkProblem) -> Unit {
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.recheckAfterFix()
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.recheckAfterFix()
    }
    return remember(vm) {
        { problem ->
            when (problem) {
                LinkProblem.PERMISSIONS -> permissions.launch(vm.missingPermissions().toTypedArray())
                LinkProblem.VPN_CONSENT -> android.net.VpnService.prepare(context)?.let(consent::launch) ?: vm.retry()
                else -> Unit
            }
        }
    }
}
