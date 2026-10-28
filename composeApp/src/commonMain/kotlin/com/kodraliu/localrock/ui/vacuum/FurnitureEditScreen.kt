package com.kodraliu.localrock.ui.vacuum

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.kodraliu.localrock.shared.vacuum.*
import com.kodraliu.localrock.shared.vacuum.map.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun FurnitureEditScreen(viewModel: VacuumViewModel, onBack: () -> Unit) {
    val repository = viewModel.repository
    val liveMap by repository.parsedMap.collectAsState()
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<FurnitureSnapshot?>(null) }
    var draft by remember { mutableStateOf<List<FurniturePlacement>>(emptyList()) }
    var selectedKey by remember { mutableStateOf<Int?>(null) }
    var nextKey by remember { mutableStateOf(-1) }
    var working by remember { mutableStateOf(true) }
    var requiresReload by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showCatalog by remember { mutableStateOf(false) }
    var showItems by remember { mutableStateOf(false) }
    var discardAction by remember { mutableStateOf<String?>(null) }
    val baseline = snapshot
    val map = baseline?.map
    val viewport = remember(map?.width, map?.height) { MapViewport() }
    val dirty = baseline != null && draft != baseline.placements
    val changedRemotely = baseline != null && liveMap?.let { !sameFurnitureBase(baseline.map, it) } == true
    val canEdit = baseline != null && !working && !requiresReload && !changedRemotely
    val selected = draft.firstOrNull { it.key == selectedKey }

    fun accept(fresh: FurnitureSnapshot) {
        snapshot = fresh; draft = fresh.placements; selectedKey = null; nextKey = -1; requiresReload = false
    }
    suspend fun load() {
        working = true; notice = null
        try { accept(repository.loadFurnitureEditor()) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { requiresReload = true; notice = e.message ?: "Couldn't load furniture. Try again." }
        finally { working = false }
    }
    LaunchedEffect(Unit) { load() }
    fun reload() { scope.launch { load() } }
    fun requestBack() {
        if (working) return
        if (dirty) discardAction = "back" else onBack()
    }
    BackHandler(enabled = true) { requestBack() }
    fun replace(item: FurniturePlacement) {
        if (canEdit && validFurniturePlacement(item)) {
            draft = draft.map { if (it.key == item.key) item else it }; notice = null
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Furniture") },
            navigationIcon = { TextButton(onClick = ::requestBack, enabled = !working) { Text("Cancel") } },
            actions = {
                if (working) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                else {
                    IconButton(onClick = { if (dirty) discardAction = "reload" else reload() }) {
                        Icon(Icons.Default.Refresh, "Reload furniture")
                    }
                    Button(enabled = canEdit && dirty, modifier = Modifier.padding(end = 8.dp), onClick = {
                        val base = snapshot ?: return@Button
                        val edits = draft.toList()
                        working = true; requiresReload = true; notice = "Saving and checking the robot's map…"
                        scope.launch {
                            try {
                                accept(repository.saveFurnitureEdits(base, edits))
                                notice = "Saved. The robot's map shows your furniture."
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { notice = e.message ?: "Couldn't confirm the save. Reload the map to check." }
                            finally { working = false }
                        }
                    }) { Text("Save") }
                }
            },
        )
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
          val dockLimit = maxHeight * .48f
          Column(Modifier.fillMaxSize()) {
            if (map == null) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (working) "Loading the robot's furniture…" else notice ?: "Furniture is unavailable.", textAlign = TextAlign.Center)
                    if (!working) TextButton(onClick = ::reload) { Text("Try again") }
                }
            } else {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    FurnitureCanvas(map, draft, selectedKey, viewport, canEdit, { selectedKey = it }, ::replace, Modifier.fillMaxSize())
                    if (changedRemotely || requiresReload || notice != null) {
                        Surface(Modifier.align(Alignment.TopCenter).padding(12.dp), shape = RoundedCornerShape(16.dp), tonalElevation = 4.dp) {
                            Column(Modifier.padding(12.dp)) {
                                Text(if (changedRemotely && !working) "The map changed. Reload before editing again."
                                    else notice ?: "Reload the map to check its current furniture.", style = MaterialTheme.typography.bodyMedium)
                                if (!working && (changedRemotely || requiresReload)) TextButton(onClick = {
                                    if (dirty) discardAction = "reload" else reload()
                                }) { Text("Reload") }
                            }
                        }
                    }
                }
                Surface(Modifier.heightIn(max = dockLimit), tonalElevation = 3.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        if (selected == null) {
                            Text("Tap furniture to select it. Drag the map to pan; pinch to zoom.", style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { showCatalog = true }, enabled = canEdit, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.Add, null); Text("Add furniture")
                                }
                                OutlinedButton(onClick = { showItems = true }, enabled = !working) { Text("Items (${draft.size})") }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(selected.kind?.label ?: "Furniture", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                IconButton(enabled = canEdit, onClick = {
                                    draft = draft.filterNot { it.key == selected.key }; selectedKey = null
                                }) { Icon(Icons.Default.Delete, "Delete selected furniture") }
                                TextButton(onClick = { selectedKey = null }, enabled = !working) { Text("Done") }
                            }
                            Text("Drag to move, or use the controls below.", style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                                IconButton(enabled = canEdit, onClick = { replace(selected.rotated(15.0)) }) { Icon(Icons.Default.RotateLeft, "Rotate counterclockwise 15 degrees") }
                                Text("${((selected.angleDegrees.roundToInt() % 360) + 360) % 360}°")
                                IconButton(enabled = canEdit, onClick = { replace(selected.rotated(-15.0)) }) { Icon(Icons.Default.RotateRight, "Rotate clockwise 15 degrees") }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                IconButton(enabled = canEdit, onClick = { replace(selected.moved(-50, 0)) }) { Icon(Icons.Default.ArrowBack, "Move left 5 cm") }
                                IconButton(enabled = canEdit, onClick = { replace(selected.moved(0, 50)) }) { Icon(Icons.Default.ArrowUpward, "Move up 5 cm") }
                                IconButton(enabled = canEdit, onClick = { replace(selected.moved(0, -50)) }) { Icon(Icons.Default.ArrowDownward, "Move down 5 cm") }
                                IconButton(enabled = canEdit, onClick = { replace(selected.moved(50, 0)) }) { Icon(Icons.Default.ArrowForward, "Move right 5 cm") }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FurnitureDimension("Width", selected.widthMm, canEdit, Modifier.weight(1f)) {
                                    replace(selected.resized(it, selected.depthMm))
                                }
                                FurnitureDimension("Depth", selected.depthMm, canEdit, Modifier.weight(1f)) {
                                    replace(selected.resized(selected.widthMm, it))
                                }
                            }
                        }
                    }
                }
            }
          }
        }
    }
    if (discardAction != null) AlertDialog(
        onDismissRequest = { discardAction = null }, title = { Text("Discard local changes?") },
        text = { Text(if (requiresReload) "Reload the robot's current furniture to check what was saved. Your local draft will be discarded."
            else "These furniture changes have not been saved to the robot.") },
        confirmButton = { TextButton(onClick = {
            val action = discardAction; discardAction = null
            if (action == "reload") reload() else onBack()
        }) { Text(if (discardAction == "reload") "Reload" else "Discard") } },
        dismissButton = { TextButton(onClick = { discardAction = null }) { Text("Keep editing") } },
    )
    if (showCatalog) ModalBottomSheet(onDismissRequest = { showCatalog = false }) {
        Text("Add furniture", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineSmall)
        LazyVerticalGrid(columns = GridCells.Adaptive(104.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 12.dp),
            contentPadding = PaddingValues(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(FURNITURE_CATALOG, key = { "${it.type}/${it.subtype}" }) { kind ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.clickable(enabled = canEdit) {
                        val (x, y) = map?.let { viewport.centerMm(it) } ?: return@clickable
                        val item = newFurniture(nextKey, kind,
                            x.coerceIn(kind.widthMm / 2 + 1, MAX_MAP_COORD_MM - kind.widthMm / 2 - 1),
                            y.coerceIn(kind.depthMm / 2 + 1, MAX_MAP_COORD_MM - kind.depthMm / 2 - 1))
                        if (validFurniturePlacement(item)) {
                            nextKey--; draft = draft + item; selectedKey = item.key; notice = null; showCatalog = false
                        }
                    }) {
                    Column(Modifier.padding(8.dp).heightIn(min = 116.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        FurniturePreview(kind, Modifier.size(64.dp))
                        Text(kind.label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    if (showItems) ModalBottomSheet(onDismissRequest = { showItems = false }) {
        Text("Furniture on this map", Modifier.padding(20.dp), style = MaterialTheme.typography.headlineSmall)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(16.dp)) {
            if (draft.isEmpty()) item { Text("Use Add furniture to place your first item.") }
            items(draft, key = { it.key }) { item ->
                ListItem(headlineContent = { Text(item.kind?.label ?: "Furniture") },
                    supportingContent = { Text("${(item.widthMm / 10).roundToInt()} × ${(item.depthMm / 10).roundToInt()} cm") },
                    leadingContent = { item.kind?.let { FurniturePreview(it, Modifier.size(48.dp)) } },
                    modifier = Modifier.clickable {
                        selectedKey = item.key; showItems = false
                        map?.let { currentMap ->
                            val fitted = currentMap.fittedSize(viewport.viewSize)
                            val center = currentMap.mmToCanvas(item.corners.sumOf { it.x } / 4,
                                item.corners.sumOf { it.y } / 4, fitted.width, fitted.height)
                            viewport.offset = clampMapOffset(
                                Offset(viewport.viewSize.width / 2, viewport.viewSize.height / 2) - center * viewport.scale,
                                viewport.scale, fitted, viewport.viewSize)
                        }
                    })
            }
            if (map?.furniture?.any { it.isVisible && it.editablePlacement() == null } == true)
                item { Text("Some existing furniture is view-only.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun FurnitureDimension(label: String, mm: Double, enabled: Boolean, modifier: Modifier, onChange: (Double) -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            IconButton(enabled = enabled && mm > 100, onClick = { onChange(maxOf(100.0, mm - 50)) }) { Icon(Icons.Default.Remove, "Reduce $label by 5 cm") }
            Text("${(mm / 10).roundToInt()} cm", style = MaterialTheme.typography.bodyMedium)
            IconButton(enabled = enabled, onClick = { onChange(mm + 50) }) { Icon(Icons.Default.Add, "Increase $label by 5 cm") }
        }
    }
}

@Composable
private fun FurniturePreview(kind: FurnitureKind, modifier: Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    Canvas(modifier) {
        val ratio = minOf(size.width / kind.widthMm, size.height / kind.depthMm) * .85f
        val w = kind.widthMm * ratio; val h = kind.depthMm * ratio
        val x = (size.width - w) / 2; val y = (size.height - h) / 2
        drawIntoCanvas { drawFurnitureSymbol(it, listOf(Offset(x, y), Offset(x + w, y), Offset(x + w, y + h), Offset(x, y + h)),
            kind.type, kind.subtype, dark, 1.dp.toPx()) }
    }
}

@Composable
private fun FurnitureCanvas(
    map: ParsedMap, draft: List<FurniturePlacement>, selectedKey: Int?, viewport: MapViewport,
    enabled: Boolean, onSelect: (Int?) -> Unit, onChange: (FurniturePlacement) -> Unit, modifier: Modifier,
) {
    val bitmap = rememberMapBitmap(map, drawFurniture = false)
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val primary = MaterialTheme.colorScheme.primary
    var viewSize by remember { mutableStateOf(Size.Zero) }
    val bounds = remember(map) { map.contentBoundsNorm() }
    var scale by viewport::scale
    var offset by viewport::offset
    LaunchedEffect(viewport, viewSize) {
        viewport.viewSize = viewSize
        if (viewSize.width > 0 && viewSize.height > 0 && viewport.framedFor != viewSize) {
            val frame = frameMap(bounds, map.fittedSize(viewSize), viewSize)
            scale = frame.first; offset = frame.second; viewport.framedFor = viewSize
        }
    }
    val currentDraft by rememberUpdatedState(draft)
    val currentSelected by rememberUpdatedState(selectedKey)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentSelect by rememberUpdatedState(onSelect)
    val currentChange by rememberUpdatedState(onChange)
    Canvas(modifier.clipToBounds().onSizeChanged { viewSize = it.toSize() }
        .semantics { contentDescription = "Furniture map. Use the Items list to select furniture and the placement controls to edit it." }
        .pointerInput(map, viewSize) {
            val fitted = map.fittedSize(size.toSize())
            if (fitted.width <= 0f || fitted.height <= 0f) return@pointerInput
            fun mm(point: Offset) = map.canvasToMm((point - offset) / scale, fitted.width, fitted.height)
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val startMm = mm(down.position)
                val startItem = currentDraft.firstOrNull { it.key == currentSelected && it.contains(startMm.first, startMm.second) }
                var pastSlop = false; var multiTouch = false; var last = down.position
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) {
                        if (!pastSlop && !multiTouch && currentEnabled) {
                            val point = mm(down.position)
                            val slop = 12.dp.toPx() / scale * map.width / fitted.width * MM_PER_CELL
                            val hit = currentDraft.lastOrNull { item ->
                                item.contains(point.first, point.second) || item.corners.indices.any { i ->
                                    val a = item.corners[i]; val b = item.corners[(i + 1) % 4]
                                    distanceToSegmentMm(point.first, point.second, a.x, a.y, b.x, b.y) <= slop
                                }
                            }
                            currentSelect(hit?.key)
                        }
                        break
                    }
                    if (pressed.size >= 2 || multiTouch) {
                        multiTouch = true
                        val transformed = zoomMap(scale, offset, event.calculateCentroid(), event.calculatePan(),
                            event.calculateZoom(), fitted, size.toSize())
                        scale = transformed.first; offset = transformed.second
                        event.changes.forEach { it.consume() }
                        continue
                    }
                    val change = pressed.first()
                    if (!pastSlop && (change.position - down.position).getDistance() < viewConfiguration.touchSlop) continue
                    pastSlop = true; change.consume()
                    if (startItem != null && currentEnabled) {
                        val point = mm(change.position)
                        val moved = startItem.moved(point.first - startMm.first, point.second - startMm.second)
                        if (validFurniturePlacement(moved)) currentChange(moved)
                    } else offset = clampMapOffset(offset + change.position - last, scale, fitted, size.toSize())
                    last = change.position
                }
            }
        }) {
        val fitted = map.fittedSize(size)
        withTransform({ translate(offset.x, offset.y); scale(scale, scale, Offset.Zero) }) {
            drawImage(bitmap, dstSize = IntSize(fitted.width.roundToInt(), fitted.height.roundToInt()), filterQuality = FilterQuality.None)
            drawIntoCanvas { canvas ->
                map.furniture.filter { it.isVisible && it.editablePlacement() == null }.forEach { item ->
                    drawFurnitureSymbol(canvas, item.cornersMm.map { map.mmToCanvas(it.x, it.y, fitted.width, fitted.height) },
                        item.type, item.subtype, dark, 1.dp.toPx() / scale)
                }
                draft.forEach { item ->
                    drawFurnitureSymbol(canvas, item.corners.map { map.mmToCanvas(it.x, it.y, fitted.width, fitted.height) },
                        item.type, item.subtype, dark, 1.dp.toPx() / scale)
                }
            }
            draft.firstOrNull { it.key == selectedKey }?.let { item ->
                val points = item.corners.map { map.mmToCanvas(it.x, it.y, fitted.width, fitted.height) }
                val path = Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                drawPath(path, primary, style = Stroke(2.dp.toPx() / scale))
            }
        }
    }
}
