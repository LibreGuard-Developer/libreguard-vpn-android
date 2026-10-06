package net.libreguard.vpn.ui.components

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import net.libreguard.vpn.R
import net.libreguard.vpn.core.AppUpdatePhase
import net.libreguard.vpn.util.openGooglePlayListing
import net.libreguard.vpn.viewmodel.*

/** Hosts consent and restart UI once, outside every navigation destination. */
@Composable
internal fun AppUpdateHost(
    viewModel: AppUpdateViewModel,
    isVpnActive: () -> Boolean,
    hasBottomNavigation: Boolean,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showRestartConfirmation by rememberSaveable { mutableStateOf(false) }
    val requestRestart = {
        if (viewModel.state.value.snapshot.phase == AppUpdatePhase.READY_TO_INSTALL) {
            if (isVpnActive()) showRestartConfirmation = true else viewModel.completeUpdate()
        }
    }
    val currentRestart by rememberUpdatedState(requestRestart)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onConsentResult(when (result.resultCode) {
            Activity.RESULT_OK -> AppUpdateConsentResult.ACCEPTED
            Activity.RESULT_CANCELED -> AppUpdateConsentResult.CANCELED
            else -> AppUpdateConsentResult.FAILED
        })
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is AppUpdateEffect.Launch -> {
                        if (viewModel.state.value.snapshot.phase == AppUpdatePhase.STARTING) {
                            try {
                                if (!effect.starter.launch(launcher)) viewModel.onLaunchFailed()
                            } catch (error: Exception) {
                                viewModel.onLaunchFailed(error)
                            }
                        }
                    }
                    AppUpdateEffect.RequestRestart -> currentRestart()
                    AppUpdateEffect.OpenStore -> if (!openGooglePlayListing(context)) {
                        Toast.makeText(context, R.string.app_update_store_error, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    val readyMessage = stringResource(R.string.app_update_snackbar)
    val restartLabel = stringResource(R.string.app_update_restart)
    LaunchedEffect(state.snapshot.phase, state.snapshot.versionCode, state.foregroundSession) {
        if (state.snapshot.phase == AppUpdatePhase.READY_TO_INSTALL) {
            val result = snackbarHostState.showSnackbar(
                readyMessage, restartLabel, withDismissAction = true, duration = SnackbarDuration.Indefinite
            )
            if (result == SnackbarResult.ActionPerformed) currentRestart()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp)
                .padding(bottom = if (hasBottomNavigation) 88.dp else 16.dp)
        )
    }
    if (showRestartConfirmation && state.snapshot.phase == AppUpdatePhase.READY_TO_INSTALL) {
        AppUpdateRestartDialog(
            onConfirm = {
                showRestartConfirmation = false
                viewModel.completeUpdate()
            },
            onLater = { showRestartConfirmation = false }
        )
    }
}