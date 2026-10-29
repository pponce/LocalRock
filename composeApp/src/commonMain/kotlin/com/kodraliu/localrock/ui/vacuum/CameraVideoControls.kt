package com.kodraliu.localrock.ui.vacuum

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.kodraliu.localrock.shared.vacuum.VacuumStateCodes
import com.kodraliu.localrock.shared.vacuum.camera.CameraLiveSession
import com.kodraliu.localrock.shared.vacuum.camera.CameraMotionController
import com.kodraliu.localrock.shared.vacuum.map.MapFormat
import com.kodraliu.localrock.shared.vacuum.map.ParsedMap
import com.kodraliu.localrock.shared.webrtc.RtcPeer
import com.kodraliu.localrock.shared.webrtc.RtcVideoView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun CameraVideoControls(
    viewModel: VacuumViewModel, peer: RtcPeer, motion: CameraMotionController,
    state: CameraLiveSession.State, ready: Boolean, expanded: Boolean,
    onExpand: (Boolean) -> Unit, onStopVideo: () -> Unit,
) {
    val status by viewModel.status.collectAsState()
    val map by viewModel.repository.parsedMap.collectAsState()
    val floor by viewModel.repository.currentFloorFlag.collectAsState()
    val busy by motion.busy.collectAsState()
    val error by motion.error.collectAsState()
    var steering by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var target by remember(floor, map?.viewportIdentity(floor)) { mutableStateOf<Pair<Int,Int>?>(null) }
    val enabled = ready && !busy
    val headline = status.state?.let { VacuumStateCodes.describe(it) } ?: "Robot status unavailable"
    // The repository already polls navigation/cleaning rapidly; refresh here only for RC state 4.
    LaunchedEffect(viewModel, ready, status.state) {
        if (ready && status.state == VacuumStateCodes.REMOTE_CONTROL_ACTIVE) {
            while (isActive) {
                try { viewModel.repository.fetchMap() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Keep the last map while video remains usable. */ }
                delay(3_000)
            }
        }
    }
    fun switchMode() { motion.release(); steering = !steering }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val landscape = maxWidth > maxHeight
        val wide = expanded && landscape
        val video: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier.background(Color.Black)) {
                key(peer) { RtcVideoView(peer, Modifier.fillMaxSize()) }
                if (status.state in setOf(VacuumStateCodes.CHARGING, VacuumStateCodes.CHARGING_COMPLETE)) {
                    Text("Robot docked. Move it to view the area.", Modifier.align(Alignment.Center).padding(20.dp), color = Color.White)
                } else if (state is CameraLiveSession.State.Connecting) {
                    Text(state.stage, Modifier.align(Alignment.Center).background(Color.Black.copy(alpha=.65f)).padding(12.dp), color=Color.White)
                }
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp), verticalAlignment=Alignment.CenterVertically) {
                    Text(headline, Modifier.weight(1f), color=Color.White)
                    IconButton(onClick={ motion.release(); settings=true }) { Icon(Icons.Default.MoreVert,"Video settings",tint=Color.White) }
                }
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp), verticalAlignment=Alignment.CenterVertically) {
                    Text("SD",color=Color.White,modifier=Modifier.background(Color.Black.copy(alpha=.65f)).padding(8.dp))
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick={ motion.release();onExpand(!expanded) }) {
                        Icon(if(expanded) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            if(expanded) "Exit full screen" else "Expand video",tint=Color.White)
                    }
                }
            }
        }
        if (wide) {
            video(Modifier.fillMaxSize())
            map?.let { pm -> CameraTargetMap(pm,floor,null,false,{},Modifier.align(Alignment.TopStart).padding(top=56.dp,start=12.dp).size(130.dp)) }
            CameraDrivePad(motion,ready,Modifier.align(Alignment.BottomStart).padding(16.dp).size(220.dp))
            Column(Modifier.align(Alignment.BottomEnd).padding(end=16.dp,bottom=64.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                FilledTonalIconButton(onClick={motion.stop()},modifier=Modifier.size(56.dp)) { Icon(Icons.Default.Stop,"Stop movement") }
                FilledTonalIconButton(onClick={motion.dock()},enabled=enabled,modifier=Modifier.size(56.dp)) { Icon(Icons.Default.Home,"Return to dock") }
            }
            TextButton(onClick=onStopVideo,modifier=Modifier.align(Alignment.BottomCenter)) { Text("Stop video",color=Color.White) }
        } else {
            Column(Modifier.fillMaxSize()) {
                video(Modifier.fillMaxWidth().weight(if(expanded) 1f else .43f))
                if (expanded) {
                    Text("Turn your phone sideways for full-screen controls.",Modifier.padding(16.dp),color=Color.White)
                } else {
                    Row(Modifier.fillMaxWidth().background(Color(0xff242424)).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                        TextButton(onClick={switchMode()}) { Text(if(steering) "Map" else "Controls",color=Color.White) }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick={motion.stop()}) { Icon(Icons.Default.Stop,"Stop movement",tint=Color.White) }
                        IconButton(onClick={motion.dock()},enabled=enabled) { Icon(Icons.Default.Home,"Return to dock",tint=if(enabled) Color.White else Color.Gray) }
                        TextButton(onClick=onStopVideo) { Text("Stop video",color=Color.White) }
                    }
                    Box(Modifier.fillMaxWidth().weight(.57f).background(Color(0xff181818)),contentAlignment=Alignment.Center) {
                        if(steering) {
                            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                                CameraDrivePad(motion,ready,Modifier.fillMaxWidth(.85f).aspectRatio(1f))
                                Text("Hold to drive · release to stop",color=Color.LightGray,modifier=Modifier.padding(8.dp))
                            }
                        } else {
                            val pm=map
                            if(pm==null) Text("Waiting for the map…",color=Color.White)
                            else CameraTargetMap(pm,floor,target,enabled,{target=it},Modifier.fillMaxSize().padding(bottom=60.dp))
                            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                                Text(if(target==null) "Tap the map to set a target" else "Target selected",Modifier.weight(1f),color=Color.LightGray)
                                Button(onClick={
                                    val point=target
                                    val current=map
                                    if(point!=null && current!=null) {
                                        val cell=current.mmToCell(point.first,point.second)
                                        if(cameraTargetAt(current,Offset(cell.x/current.width,cell.y/current.height))!=null)
                                            motion.goTo(point.first,point.second,floor)
                                        else target=null
                                    }
                                },enabled=enabled && target!=null) { Text("Go") }
                            }
                        }
                    }
                }
            }
        }
        error?.let { Text(it,Modifier.align(Alignment.TopCenter).padding(top=44.dp).background(Color(0xff5b2424)).padding(8.dp),color=Color.White) }
    }
    if(settings) AlertDialog(
        onDismissRequest={settings=false}, title={Text("Remote viewing")},
        text={Text("Video quality: SD\n\nCall volume, microphone calls and remote-viewing activation are not available in this build. Use the OEM app to change those settings.")},
        confirmButton={TextButton(onClick={settings=false}){Text("Done")}},
    )
}

@Composable
private fun CameraDrivePad(motion: CameraMotionController, enabled: Boolean, modifier: Modifier) {
    val scope=rememberCoroutineScope()
    Box(modifier.clip(CircleShape).background(Color(0xff242424).copy(alpha=.9f))) {
        val keys=listOf(
            Triple(CameraMotionController.Direction.FORWARD,Alignment.TopCenter,Icons.Default.KeyboardArrowUp),
            Triple(CameraMotionController.Direction.BACKWARD,Alignment.BottomCenter,Icons.Default.KeyboardArrowDown),
            Triple(CameraMotionController.Direction.LEFT,Alignment.CenterStart,Icons.Default.RotateLeft),
            Triple(CameraMotionController.Direction.RIGHT,Alignment.CenterEnd,Icons.Default.RotateRight),
        )
        keys.forEach { (direction,align,icon) ->
            val label=when(direction){
                CameraMotionController.Direction.FORWARD -> "Drive forward"
                CameraMotionController.Direction.BACKWARD -> "Drive backward"
                CameraMotionController.Direction.LEFT -> "Turn left"
                CameraMotionController.Direction.RIGHT -> "Turn right"
            }
            Box(Modifier.align(align).fillMaxSize(.32f)
                .semantics { contentDescription=label; onClick {
                    if(enabled) scope.launch { motion.hold(direction);delay(250);motion.release() }
                    enabled
                } }
                .pointerInput(enabled,direction) {
                    if(enabled) detectTapGestures(onPress={
                        motion.hold(direction)
                        try { tryAwaitRelease() } finally { motion.release() }
                    })
                },contentAlignment=Alignment.Center) {
                Icon(icon,label,Modifier.size(34.dp),tint=if(enabled) Color.White else Color.Gray)
            }
        }
        FilledTonalIconButton(onClick={motion.stop()},modifier=Modifier.align(Alignment.Center).fillMaxSize(.27f)) {
            Icon(Icons.Default.Stop,"Stop movement")
        }
    }
}

/** Only actual floor cells are eligible, with the same Y inversion as the normal map. */
internal fun cameraTargetAt(map: ParsedMap, normalized: Offset): Pair<Int,Int>? {
    if(map.format!=MapFormat.LEGACY || !normalized.x.isFinite() || !normalized.y.isFinite()) return null
    if(normalized.x !in 0f..<1f || normalized.y !in 0f..<1f) return null
    val x=(normalized.x*map.width).toInt()
    val y=map.height-1-(normalized.y*map.height).toInt()
    val cell=map.grid.getOrNull(y*map.width+x)?.toInt()?.and(255) ?: return null
    if(cell==127 || (cell!=128 && map.roomIdAtCell(x,y)==null)) return null
    val point=map.cellToMm(Offset(normalized.x*map.width,normalized.y*map.height))
    if(point.first !in 0..51_200 || point.second !in 0..51_200) return null
    if(map.noGoZones.any { it.contains(point.first,point.second) }) return null
    return point
}

@Composable
private fun CameraTargetMap(map: ParsedMap,floor:Int,target:Pair<Int,Int>?,enabled:Boolean,onTarget:(Pair<Int,Int>)->Unit,modifier:Modifier) {
    val bitmap=rememberMapBitmap(map)
    val viewport=remember { LiveMapViewport() }
    var measured by remember { mutableStateOf(Size.Zero) }
    val identity=map.viewportIdentity(floor)
    val fit=remember(map,measured) { if(measured.width>0f && measured.height>0f) frameMap(map.contentBoundsNorm(),map.fittedSize(measured),measured) else null }
    LaunchedEffect(identity,measured,fit) { viewport.frameIfNeeded(identity,measured,fit) }
    val currentMap by rememberUpdatedState(map)
    val selected by rememberUpdatedState(onTarget)
    Canvas(modifier.clipToBounds().onSizeChanged {measured=it.toSize()}
        .pointerInput(identity) {detectTransformGestures {center,pan,zoom,_->
            val next=zoomMap(viewport.scale,viewport.offset,center,pan,zoom,currentMap.fittedSize(size.toSize()),size.toSize())
            viewport.scale=next.first;viewport.offset=next.second
        }}
        .pointerInput(identity,enabled) {if(enabled) detectTapGestures(onTap={p->
            val content=currentMap.fittedSize(size.toSize())
            if(content.width>0f && content.height>0f) {
                val local=(p-viewport.offset)/viewport.scale
                cameraTargetAt(currentMap,Offset(local.x/content.width,local.y/content.height))?.let(selected)
            }
        })}) {
        val content=map.fittedSize(size)
        withTransform({translate(viewport.offset.x,viewport.offset.y);scale(viewport.scale,viewport.scale,pivot=Offset.Zero)}) {
            drawImage(bitmap,srcOffset=IntOffset.Zero,srcSize=IntSize(bitmap.width,bitmap.height),dstOffset=IntOffset.Zero,
                dstSize=IntSize(content.width.toInt(),content.height.toInt()),filterQuality=FilterQuality.None)
            target?.let {
                val p=map.mmToCanvas(it.first,it.second,content.width,content.height)
                drawCircle(Color.White,10.dp.toPx()/viewport.scale,p,style=Stroke(3.dp.toPx()/viewport.scale))
                drawCircle(Color(0xff3185ff),5.dp.toPx()/viewport.scale,p)
            }
        }
    }
}
