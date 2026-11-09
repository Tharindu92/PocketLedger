package com.billbook.app.ui.statement

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.billbook.app.export.CsvReader
import com.billbook.app.export.ExportFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen preview of a generated statement file. Download and Share act on this exact file,
 * so what is shared is what was previewed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatementPreview(
    file: File,
    format: ExportFormat,
    busy: Boolean,
    onClose: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Preview ${format.name}")
                            Text(
                                file.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close preview") }
                    },
                    actions = {
                        TextButton(onClick = onDownload, enabled = !busy) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Text(" Save")
                        }
                        TextButton(onClick = onShare, enabled = !busy) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Text(" Share")
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (format) {
                    ExportFormat.PDF -> PdfPagesView(file)
                    ExportFormat.CSV -> CsvTableView(file)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ PDF

/** Renders pages of a PDF on demand. PdfRenderer allows one open page at a time, hence the lock. */
private class PdfPages(file: File) {
    private val lock = Any()
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private var closed = false
    val count: Int = renderer.pageCount

    /** Null once closed (the preview was dismissed while a page was still rendering). */
    fun render(index: Int, widthPx: Int): Bitmap? = synchronized(lock) {
        if (closed) return null
        val page = renderer.openPage(index)
        try {
            val heightPx = (widthPx.toFloat() * page.height / page.width).toInt()
            Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).apply {
                eraseColor(AndroidColor.WHITE)   // PDF pages are transparent where nothing is drawn
                page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } finally {
            page.close()
        }
    }

    fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            renderer.close()
            fd.close()
        }
    }
}

@Composable
private fun PdfPagesView(file: File) {
    val pages = remember(file) { PdfPages(file) }
    DisposableEffect(pages) { onDispose { pages.close() } }
    var zoomed by remember { mutableStateOf<ImageBitmap?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
        // Rendered at twice the screen width so text stays sharp when zoomed in.
        val widthPx = with(LocalDensity.current) { (maxWidth - 24.dp).roundToPx() * 2 }.coerceIn(600, 2400)
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(pages.count) { index ->
                val bitmap by produceState<ImageBitmap?>(null, index, widthPx) {
                    value = withContext(Dispatchers.IO) { pages.render(index, widthPx)?.asImageBitmap() }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(595f / 842f)   // A4, until the page is rendered
                            .background(Color.White)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            .clickable(enabled = bitmap != null) { zoomed = bitmap },
                        contentAlignment = Alignment.Center,
                    ) {
                        val b = bitmap
                        if (b != null) {
                            Image(b, contentDescription = "Page ${index + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                    Text(
                        "Page ${index + 1} of ${pages.count} · tap to zoom",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    zoomed?.let { ZoomablePage(it) { zoomed = null } }
}

@Composable
private fun ZoomablePage(bitmap: ImageBitmap, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "Page (pinch to zoom, tap to close)",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
        }
    }
}

// ------------------------------------------------------------------ CSV

@Composable
private fun CsvTableView(file: File) {
    val rows by produceState<List<List<String>>?>(null, file) {
        value = withContext(Dispatchers.IO) { CsvReader.parse(file.readText(Charsets.UTF_8)) }
    }
    val table = rows
    if (table == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val header = table.firstOrNull().orEmpty()
    val body = table.drop(1)
    val columns = table.maxOfOrNull { it.size } ?: 0
    // Column widths from the longest value in each column, within sensible limits.
    val widths: List<Dp> = (0 until columns).map { c ->
        val chars = table.maxOf { it.getOrNull(c)?.length ?: 0 }
        (chars * 8 + 24).coerceIn(64, 240).dp
    }
    val totalWidth = widths.fold(0.dp) { acc, w -> acc + w }

    Column(Modifier.fillMaxSize()) {
        Text(
            "${body.size} ${if (body.size == 1) "entry" else "entries"} · ${columns} columns · scroll sideways for more",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(12.dp),
        )
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            LazyColumn(Modifier.width(totalWidth)) {
                item { CsvRow(header, widths, isHeader = true) }
                itemsIndexed(body) { i, row -> CsvRow(row, widths, isHeader = false, shaded = i % 2 == 1) }
                if (body.isEmpty()) {
                    item {
                        Text("No entries in this period.", modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CsvRow(cells: List<String>, widths: List<Dp>, isHeader: Boolean, shaded: Boolean = false) {
    val background = when {
        isHeader -> MaterialTheme.colorScheme.secondaryContainer
        shaded -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        else -> Color.Transparent
    }
    Column {
        Row(Modifier.background(background)) {
            widths.forEachIndexed { c, w ->
                Text(
                    cells.getOrNull(c).orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(w).padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        HorizontalDivider()
    }
}
