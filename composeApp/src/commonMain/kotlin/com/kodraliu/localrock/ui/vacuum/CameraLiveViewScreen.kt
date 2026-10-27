package com.kodraliu.localrock.ui.vacuum

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kodraliu.localrock.shared.vacuum.camera.CameraLiveSession
import com.kodraliu.localrock.shared.vacuum.camera.experimentalCameraSupported
import com.kodraliu.localrock.shared.webrtc.RtcVideoView
import com.kodraliu.localrock.shared.webrtc.liveViewSupported

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraLiveViewScreen(viewModel: VacuumViewModel, onBack: () -> Unit) {
    val live = remember(viewModel) { CameraLiveSession(viewModel.repository.session) }
    val state by live.state.collectAsState()
    val peer by live.peer.collectAsState()
    val diagnostics by live.diagnostics.collectAsState()
    val cleanupWarning by live.cleanupWarning.collectAsState()
    // Existing credentials are kept only for this screen's foreground lifetime.
    var pin by remember(viewModel) { mutableStateOf("") }
    var typed by remember { mutableStateOf(true) }
    val supported = liveViewSupported && experimentalCameraSupported(viewModel.modelName)

    DisposableEffect(live) { onDispose { live.dispose() } }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        pin = ""
        live.stop()
    }

    Scaffold(
        containerColor = Color.Black,
        contentColor = Color.White,
        topBar = {
            TopAppBar(
                title = { Text("Video · experimental", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { live.stop(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Go back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
            )
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
            // Attach the renderer while connecting so a first video frame can be decoded.
            peer?.let { key(it) { RtcVideoView(it, Modifier.fillMaxSize()) } }
            if (!supported) {
                Text("Experimental video is currently available for S7 MaxV only, in builds with WebRTC enabled.",
                    Modifier.padding(24.dp), textAlign = TextAlign.Center)
            } else when (val s = state) {
                CameraLiveSession.State.Idle -> Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(viewModel.deviceName, style = MaterialTheme.typography.titleMedium)
                    Text("Enter your existing camera PIN or pattern. Live view uses your robot's current camera activation and local server.",
                        textAlign = TextAlign.Center)
                    if (typed) {
                        OutlinedTextField(
                            value = pin, onValueChange = { pin = it }, label = { Text("Existing camera PIN") },
                            singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedLabelColor = Color.White, unfocusedLabelColor = Color.LightGray,
                            ),
                        )
                    } else {
                        PatternPinInput(onPattern = { pin = it })
                        Text(if (pin.isBlank()) "Draw your existing pattern" else "Pattern entered")
                    }
                    Button(onClick = { live.start(pin) }, enabled = pin.isNotBlank()) { Text("Start live view") }
                    TextButton(onClick = { typed = !typed; pin = "" }) {
                        Text(if (typed) "Use a pattern" else "Use a numeric PIN", color = Color.White)
                    }
                    cleanupWarning?.let { Text(it, color = Color.Yellow, textAlign = TextAlign.Center) }
                }
                is CameraLiveSession.State.Connecting -> Column(
                    Modifier.background(Color.Black.copy(alpha = .8f)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text(s.stage)
                    Text(diagnostics.summary, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { live.stop() }) { Text("Cancel", color = Color.White) }
                }
                CameraLiveSession.State.Streaming -> Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color.Black.copy(alpha = .8f)).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("LIVE · ${diagnostics.summary}", style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { live.stop() }) { Text("Stop live view", color = Color.White) }
                }
                CameraLiveSession.State.Stopping -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Text("Releasing camera…", Modifier.padding(16.dp))
                }
                is CameraLiveSession.State.Failed -> Column(
                    Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(s.message, textAlign = TextAlign.Center)
                    cleanupWarning?.let { Text(it, color = Color.Yellow, textAlign = TextAlign.Center) }
                    Button(onClick = { live.start(pin) }, enabled = pin.isNotBlank()) { Text("Retry") }
                    TextButton(onClick = { pin = ""; live.stop() }) { Text("Enter PIN again", color = Color.White) }
                }
            }
        }
    }
}
