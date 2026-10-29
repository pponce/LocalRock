package com.kodraliu.localrock.ui.vacuum

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kodraliu.localrock.shared.vacuum.camera.CameraMotionController
import com.kodraliu.localrock.shared.webrtc.RtcConnectionState
import com.kodraliu.localrock.shared.vacuum.camera.CameraLiveSession
import com.kodraliu.localrock.shared.vacuum.camera.CameraVideoTest
import com.kodraliu.localrock.shared.vacuum.camera.experimentalCameraSupported
import com.kodraliu.localrock.shared.webrtc.liveViewSupported

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun CameraLiveViewScreen(viewModel: VacuumViewModel, onBack: () -> Unit) {
    val live = remember(viewModel) { CameraLiveSession(viewModel.repository.session) }
    val state by live.state.collectAsState()
    val peer by live.peer.collectAsState()
    val connection = peer?.connectionState?.collectAsState()?.value
    val ready = connection == RtcConnectionState.CONNECTED &&
        (state is CameraLiveSession.State.Connecting || state == CameraLiveSession.State.Streaming)
    val readyNow by rememberUpdatedState(ready)
    val motion = remember(viewModel) { CameraMotionController(viewModel.repository.session) { readyNow } }
    var expanded by remember { mutableStateOf(false) }
    val viewing = peer != null && (state is CameraLiveSession.State.Connecting || state == CameraLiveSession.State.Streaming)
    BackHandler(enabled = expanded) { motion.release(); expanded = false }
    LaunchedEffect(ready) { if (!ready) motion.stop(includeDock = false) }
    LaunchedEffect(viewing) { if (!viewing) expanded = false }
    val diagnostics by live.diagnostics.collectAsState()
    val motionError by motion.error.collectAsState()
    val cleanupWarning by live.cleanupWarning.collectAsState()
    // Existing credentials are kept only for this screen's foreground lifetime.
    var pin by remember(viewModel) { mutableStateOf("") }
    var typed by remember { mutableStateOf(true) }
    var selectedTest by remember { mutableStateOf(CameraVideoTest.OEM_MATCH) }
    val supported = liveViewSupported && experimentalCameraSupported(viewModel.modelName)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissKeyboard() { focus.clearFocus(); keyboard?.hide() }
    fun start(test: CameraVideoTest = selectedTest) {
        if (pin.isNotBlank() && (state == CameraLiveSession.State.Idle || state is CameraLiveSession.State.Failed)) {
            selectedTest = test
            dismissKeyboard()
            live.start(pin, test)
        }
    }

    DisposableEffect(live, motion) { onDispose { motion.dispose(); live.dispose() } }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        pin = ""
        motion.stop(includeDock = false)
        live.stop()
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = Color.Black,
        contentColor = Color.White,
        topBar = {
            if (!expanded) TopAppBar(
                title = { Text(if (state == CameraLiveSession.State.Idle) "Video tests"
                    else "Video ${selectedTest.code} · ${selectedTest.label}", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { dismissKeyboard(); motion.stop(includeDock = false); live.stop(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Go back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
            )
        },
        bottomBar = {
            if (supported && state == CameraLiveSession.State.Idle) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (typed) TextButton(onClick = { dismissKeyboard() }) { Text("Hide keyboard", color = Color.White) }
                    Text("Choose a video tile to start its test.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
            // Attach the renderer while connecting so a first video frame can be decoded.
            if (viewing) peer?.let { currentPeer ->
                CameraVideoControls(viewModel, currentPeer, motion, state, ready, expanded,
                    onExpand = { expanded = it }, onStopVideo = { motion.stop(includeDock = false); live.stop() })
            }
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
                    Text("Video is unavailable while docked. Play starts cleaning and moves the robot off the dock.", textAlign = TextAlign.Center)
                    CameraCleaningButton(viewModel, motion)
                    motionError?.let { Text(it, color = Color.Yellow, textAlign = TextAlign.Center) }
                    Text("Enter your existing camera PIN or pattern. Activate remote viewing in the official Roborock app before switching to the local server.",
                        textAlign = TextAlign.Center)
                    if (typed) {
                        OutlinedTextField(
                            value = pin, onValueChange = { pin = it.filter { c -> c in '0'..'9' } }, label = { Text("Existing camera PIN") },
                            singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { dismissKeyboard() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedLabelColor = Color.White, unfocusedLabelColor = Color.LightGray,
                            ),
                        )
                    } else {
                        PatternPinInput(onPattern = { pin = it })
                        Text(if (pin.isBlank()) "Draw your existing pattern" else "Pattern entered")
                    }
                    TextButton(onClick = { dismissKeyboard(); typed = !typed; pin = "" }) {
                        Text(if (typed) "Use a pattern" else "Use a numeric PIN", color = Color.White)
                    }
                    Text("Start with A, then try B–D. Stop before choosing another tile and follow the capture prompts.",
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    CameraTestTiles(enabled = pin.isNotBlank(), onStart = { start(it) })
                    Text("All four use the robot's camera. Your phone's microphone and camera are not recorded.",
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    cleanupWarning?.let { Text(it, color = Color.Yellow, textAlign = TextAlign.Center) }
                }
                is CameraLiveSession.State.Connecting -> if (!viewing) Column(
                    Modifier.background(Color.Black.copy(alpha = .8f)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text(s.stage)
                    Text(diagnostics.summary, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { live.stop() }) { Text("Cancel", color = Color.White) }
                }
                CameraLiveSession.State.Streaming -> Unit
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
                    CameraCleaningButton(viewModel, motion)
                    motionError?.let { Text(it, color = Color.Yellow, textAlign = TextAlign.Center) }
                    cleanupWarning?.let { Text(it, color = Color.Yellow, textAlign = TextAlign.Center) }
                    Button(onClick = { start() }, enabled = pin.isNotBlank()) { Text("Retry Video ${selectedTest.code}") }
                    TextButton(onClick = { live.stop() }) { Text("Choose another video test", color = Color.White) }
                    TextButton(onClick = { pin = ""; live.stop() }) { Text("Enter PIN again", color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun CameraTestTiles(enabled: Boolean, onStart: (CameraVideoTest) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CameraVideoTest.entries.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { test ->
                    OutlinedCard(onClick = { onStart(test) }, enabled = enabled,
                        modifier = Modifier.weight(1f).heightIn(min = 132.dp),
                        colors = CardDefaults.outlinedCardColors(containerColor = Color(0xff18232d), contentColor = Color.White,
                            disabledContainerColor = Color(0xff18232d), disabledContentColor = Color.LightGray)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Video ${test.code}", style = MaterialTheme.typography.labelLarge)
                            Text(test.label, style = MaterialTheme.typography.titleSmall)
                            Text(test.description, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

