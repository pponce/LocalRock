package com.kodraliu.localrock.ui.vacuum

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kodraliu.localrock.shared.vacuum.camera.CameraCleaningAction
import com.kodraliu.localrock.shared.vacuum.camera.CameraMotionController
import com.kodraliu.localrock.shared.vacuum.camera.cameraCleaningAction
import kotlinx.coroutines.CancellationException

@Composable
internal fun CameraCleaningButton(viewModel: VacuumViewModel, motion: CameraMotionController) {
    val status by viewModel.status.collectAsState()
    val busy by motion.busy.collectAsState()
    val action = cameraCleaningAction(status.state, status.inCleaning, status.errorCode)
    // Refresh after an acknowledgement, including when video is unavailable on the dock.
    LaunchedEffect(busy) {
        if (!busy) try { viewModel.repository.refreshStatus() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* The next action still requires a fresh status read. */ }
    }
    FilledTonalButton(onClick = { action?.let(motion::cleaning) }, enabled = !busy && action != null) {
        Icon(if (action == CameraCleaningAction.PAUSE) Icons.Default.Pause else Icons.Default.PlayArrow, null)
        Spacer(Modifier.width(6.dp))
        Text(action?.label ?: "Cleaning unavailable")
    }
}
