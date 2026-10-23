package com.example.pdftoolkit

import android.content.*
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.*
import java.io.File

enum class Filt(val label: String) { NONE("Original"), GRAY("Gray"), BW("B&W"), SEPIA("Sepia"), BRIGHT("Bright") }

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) { super.onCreate(s); setContent { MaterialTheme { App() } } }
}

// ---------- helpers ----------
fun decode(ctx: Context, uri: Uri, max: Int = 2000): Bitmap {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1; while (o.outWidth / s > max || o.outHeight / s > max) s *= 2
    return ctx.contentResolver.openInputStream(uri)!!.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })!!
    }
}

fun applyFilter(src: Bitmap, f: Filt): Bitmap {
    if (f == Filt.NONE) return src
    val m = ColorMatrix()
    when (f) {
        Filt.GRAY -> m.setSaturation(0f)
        Filt.BW -> { m.setSaturation(0f); m.postConcat(ColorMatrix(floatArrayOf(
            3f,0f,0f,0f,-400f, 0f,3f,0f,0f,-400f, 0f,0f,3f,0f,-400f, 0f,0f,0f,1f,0f))) }
        Filt.SEPIA -> m.set(floatArrayOf(.393f,.769f,.189f,0f,0f, .349f,.686f,.168f,0f,0f, .272f,.534f,.131f,0f,0f, 0f,0f,0f,1f,0f))
        Filt.BRIGHT -> m.set(floatArrayOf(1.2f,0f,0f,0f,25f, 0f,1.2f,0f,0f,25f, 0f,0f,1.2f,0f,25f, 0f,0f,0f,1f,0f))
        else -> {}
    }
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
    return out
}

fun PdfDocument.addImagePage(bmp: Bitmap, n: Int) {
    val w = 595; val h = (bmp.height * w / bmp.width.toFloat()).toInt()
    val p = startPage(PdfDocument.PageInfo.Builder(w, h, n).create())
    p.canvas.drawBitmap(Bitmap.createScaledBitmap(bmp, w, h, true), 0f, 0f, null)
    finishPage(p)
}

/** Re-renders every page of the PDFs as JPEG (scale = resolution, q = JPEG quality). Used by compress + merge. */
fun rebuild(ctx: Context, uris: List<Uri>, scale: Float, q: Int): PdfDocument {
    val doc = PdfDocument(); var n = 1
    for (u in uris) {
        val pfd = ctx.contentResolver.openFileDescriptor(u, "r")!!
        PdfRenderer(pfd).use { r ->
            for (i in 0 until r.pageCount) r.openPage(i).use { pg ->
                val bmp = Bitmap.createBitmap((pg.width * scale).toInt(), (pg.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val bos = java.io.ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
                val jpg = BitmapFactory.decodeByteArray(bos.toByteArray(), 0, bos.size())
                val page = doc.startPage(PdfDocument.PageInfo.Builder(pg.width, pg.height, n++).create())
                page.canvas.drawBitmap(Bitmap.createScaledBitmap(jpg, pg.width, pg.height, true), 0f, 0f, null)
                doc.finishPage(page)
            }
        }
        pfd.close()
    }
    return doc
}

fun savePdf(ctx: Context, doc: PdfDocument, name: String): Uri? {
    val cv = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, "$name-${System.currentTimeMillis() / 1000}.pdf")
        put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
    ctx.contentResolver.openOutputStream(uri)!!.use { doc.writeTo(it) }
    doc.close(); return uri
}

fun openFile(ctx: Context, uri: Uri, mime: String?) = try {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with"))
} catch (e: Exception) { android.widget.Toast.makeText(ctx, "No app found to open this file", android.widget.Toast.LENGTH_LONG).show() }

fun sharePdf(ctx: Context, uri: Uri) = ctx.startActivity(Intent.createChooser(
    Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share PDF"))

// ---------- UI ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Uri?>(null) }
    val images = remember { mutableStateListOf<Uri>() }
    val filters = remember { mutableStateListOf<Filt>() }
    var cropIdx by remember { mutableIntStateOf(-1) }
    var quality by remember { mutableFloatStateOf(60f) }
    var pdfs by remember { mutableStateOf<List<Uri>>(emptyList()) }

    fun run(name: String, block: () -> PdfDocument) = scope.launch {
        busy = true
        result = try { withContext(Dispatchers.IO) { savePdf(ctx, block(), name) } } catch (e: Exception) {
            android.widget.Toast.makeText(ctx, "Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show(); null }
        busy = false
        if (result != null) android.widget.Toast.makeText(ctx, "Saved to Downloads", android.widget.Toast.LENGTH_SHORT).show()
    }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) {
        images.addAll(it); repeat(it.size) { filters.add(Filt.NONE) } }
    val pickPdfs = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { pdfs = it }
    val pickOffice = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) openFile(ctx, u, ctx.contentResolver.getType(u)) }
    val cropper = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val out = r.data?.let { UCrop.getOutput(it) }
        if (out != null && cropIdx >= 0) images[cropIdx] = out
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("PDF Toolkit") }) },
        bottomBar = { NavigationBar {
            listOf("Images→PDF" to Icons.Default.Image, "Compress" to Icons.Default.Compress,
                "Merge" to Icons.Default.CallMerge, "Open" to Icons.Default.FolderOpen).forEachIndexed { i, (l, ic) ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(ic, l) }, label = { Text(l, maxLines = 1) }) } } }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (tab) {
                0 -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pickImages.launch("image/*") }) { Text("Add images") }
                        Button(enabled = images.isNotEmpty() && !busy, onClick = {
                            val list = images.toList(); val fl = filters.toList()
                            run("images") { PdfDocument().also { d -> list.forEachIndexed { i, u -> d.addImagePage(applyFilter(decode(ctx, u), fl[i]), i + 1) } } }
                        }) { Text("Create PDF") }
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(images) { i, u ->
                            Card { Column(Modifier.padding(12.dp)) {
                                Text("Page ${i + 1}", style = MaterialTheme.typography.titleSmall)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    var exp by remember { mutableStateOf(false) }
                                    Box { OutlinedButton(onClick = { exp = true }) { Text(filters[i].label) }
                                        DropdownMenu(exp, { exp = false }) { Filt.values().forEach { f ->
                                            DropdownMenuItem(text = { Text(f.label) }, onClick = { filters[i] = f; exp = false }) } } }
                                    OutlinedButton(onClick = {
                                        cropIdx = i
                                        val dest = Uri.fromFile(File(ctx.cacheDir, "crop_${System.nanoTime()}.jpg"))
                                        cropper.launch(UCrop.of(u, dest).getIntent(ctx))
                                    }) { Icon(Icons.Default.Crop, null); Text(" Crop") }
                                    IconButton(onClick = { images.removeAt(i); filters.removeAt(i) }) { Icon(Icons.Default.Delete, "Remove") }
                                }
                            } }
                        }
                    }
                }
                1 -> {
                    Button(onClick = { pickPdfs.launch("application/pdf") }) { Text(if (pdfs.isEmpty()) "Choose PDF" else "PDF selected") }
                    Text("Quality: ${quality.toInt()}%  (lower = smaller file)")
                    Slider(quality, { quality = it }, valueRange = 20f..90f)
                    Button(enabled = pdfs.isNotEmpty() && !busy, onClick = {
                        val l = pdfs.take(1); val q = quality.toInt()
                        run("compressed") { rebuild(ctx, l, 0.5f + q / 100f, q) }
                    }) { Text("Compress") }
                }
                2 -> {
                    Button(onClick = { pickPdfs.launch("application/pdf") }) { Text("Choose PDFs (select several)") }
                    Text("${pdfs.size} file(s) selected, merged in selection order")
                    Button(enabled = pdfs.size > 1 && !busy, onClick = {
                        val l = pdfs; run("merged") { rebuild(ctx, l, 1.5f, 90) }
                    }) { Text("Merge") }
                }
                3 -> {
                    Text("Open Word, Excel, PowerPoint, PDF or text files in an installed app (Office, WPS, Google Docs…).")
                    Button(onClick = { pickOffice.launch(arrayOf("application/pdf", "application/msword",
                        "application/vnd.openxmlformats-officedocument.*", "application/vnd.ms-excel", "application/vnd.ms-powerpoint", "text/*")) }) { Text("Pick a file") }
                }
            }
            result?.let { r ->
                HorizontalDivider()
                Text("Done! Saved in Downloads.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openFile(ctx, r, "application/pdf") }) { Text("Open") }
                    OutlinedButton(onClick = { sharePdf(ctx, r) }) { Text("Share") }
                }
            }
        }
    }
}
