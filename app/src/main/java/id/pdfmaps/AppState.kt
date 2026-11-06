package id.pdfmaps

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class Mode { NONE, CALIBRATE, ADD_POINT, MEASURE_LINE, MEASURE_AREA, RULER }

class LoadedMap(
    val bitmap: Bitmap,
    val autoPoints: List<ControlPoint>,
    val pageCount: Int,
    val scale: Float = 1f,   // px bitmap per poin PDF
    val note: String = "",
)

class Detail(val bmp: Bitmap, val x0: Float, val y0: Float, val zoom: Float)

fun GeoTransform.off(p: Pt): Offset {
    val (x, y) = toPixel(p.lat, p.lon)
    return Offset(x.toFloat(), y.toFloat())
}

private const val AVG_N = 20

/** Render satu halaman PDF ke bitmap + baca titik georeferensi OGC (jika ada). */
fun loadPdfPage(file: File, pageIdx: Int): LoadedMap =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            val count = renderer.pageCount
            val idx = pageIdx.coerceIn(0, count - 1)
            renderer.openPage(idx).use { page ->
                val s = min(4096f / max(page.width, page.height), 4f)
                val bmp = Bitmap.createBitmap(
                    (page.width * s).toInt(), (page.height * s).toInt(), Bitmap.Config.ARGB_8888
                )
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val raw = try { GeoPdfParser.readPagePoints(file, idx) } catch (e: Exception) { emptyList() }
                val pts = raw.map { ControlPoint(it.x * s, (page.height - it.y) * s, it.lat, it.lon) }
                LoadedMap(bmp, pts, count, s)
            }
        }
    }

fun loadImageFile(file: File): LoadedMap {
    if (GeoTiff.isTiff(file)) {
        val r = GeoTiff.read(file, 3072)
        val bmp = Bitmap.createBitmap(r.pixels, r.width, r.height, Bitmap.Config.ARGB_8888)
        return LoadedMap(bmp, r.points, 1, 1f, r.note)
    }
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, o)
    var s = 1
    while (max(o.outWidth, o.outHeight) / s > 4096) s *= 2
    val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
        ?: throw IllegalStateException("Gambar tidak bisa dibaca")
    return LoadedMap(bmp, emptyList(), 1)
}

class AppState(val ctx: Context) {
    init {
        GeoTiff.jpegDecoder = { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { b ->
                val px = IntArray(b.width * b.height)
                b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
                val r = Triple(b.width, b.height, px)
                b.recycle()
                r
            }
        }
    }
    // ----- data permanen -----
    var store by mutableStateOf(Storage.load(ctx))
    init { GeoMath.unit = store.unit }
        private set

    // ----- peta aktif -----
    var currentMapId by mutableStateOf<String?>(null)
    var page by mutableStateOf(0)
    var pageCount by mutableStateOf(1)
    var bitmap by mutableStateOf<Bitmap?>(null)
    var autoPoints by mutableStateOf(listOf<ControlPoint>())
    var zoom by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var rotation by mutableFloatStateOf(0f)      // derajat, searah jarum jam
    var rotateLocked by mutableStateOf(false)
    var ruler by mutableStateOf<List<Pt>>(emptyList())
    var viewSize by mutableStateOf(IntSize.Zero)
    private var needsFit = false

    // ----- sesi -----
    var status by mutableStateOf("Tap \"Peta\" untuk mengimpor PDF")
    var loc by mutableStateOf<Location?>(null)
    var heading by mutableStateOf<Float?>(null)
    var mode by mutableStateOf(Mode.NONE)
    var draft by mutableStateOf(listOf<Pt>())
    var recording by mutableStateOf(false)
    var trackPts by mutableStateOf(listOf<Pt>())
    var averaging by mutableStateOf(false)
    var avgFixes by mutableStateOf(listOf<Pt>())
    var navTarget by mutableStateOf<String?>(null)
    var alert by mutableStateOf<String?>(null)
    var pendingCalib by mutableStateOf<Offset?>(null)
    var pendingPoint by mutableStateOf<Pt?>(null)
    var selectedId by mutableStateOf<String?>(null)
    var detail by mutableStateOf<Detail?>(null)
    var btName by mutableStateOf<String?>(null)
    private var baseScale = 1f
    private val inside = mutableSetOf<String>()
    private var gpsRunning = false
    private val lm by lazy { ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private val gpsListener = LocationListener { onLocation(it) }
    private val bt = BtGps(
        onFix = { f -> onExternalFix(f) },
        onState = { name ->
            btName = name
            status = if (name != null) "GPS Bluetooth tersambung: $name" else "GPS Bluetooth terputus, kembali ke GPS bawaan"
        },
    )

    // ----- turunan -----
    val currentMap: MapEntry? get() = store.maps.firstOrNull { it.id == currentMapId }
    val manualPoints: List<ControlPoint> get() = currentMap?.manual?.get(page) ?: emptyList()
    val transform: GeoTransform?
        get() = if (bitmap == null) null else GeoTransform.fit(autoPoints + manualPoints)
    val activeLayer: Layer
        get() = store.layers.firstOrNull { it.id == store.activeLayerId } ?: store.layers.first()

    val needsBackground: Boolean
        get() = recording || (store.bgGeofence && store.features.any { it.type == FType.POINT && it.radius > 0.0 })

    fun update(block: (Store) -> Store) {
        store = block(store).ensured()
        Storage.save(ctx, store)
    }

    // =============== peta ===============

    private fun displayName(uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    suspend fun importMap(uri: Uri, image: Boolean, nameHint: String? = null) {
        status = "Mengimpor…"
        try {
            val name = displayName(uri) ?: nameHint ?: if (image) "gambar" else "peta.pdf"
            val fname = UUID.randomUUID().toString() + if (image) ".img" else ".pdf"
            withContext(Dispatchers.IO) {
                val dir = File(ctx.filesDir, "maps").apply { mkdirs() }
                ctx.contentResolver.openInputStream(uri)!!.use { i ->
                    File(dir, fname).outputStream().use { i.copyTo(it) }
                }
            }
            val e = MapEntry(name = name.substringBeforeLast('.'), file = fname, isImage = image)
            update { it.copy(maps = it.maps + e) }
            openMap(e.id)
        } catch (ex: Exception) {
            status = "Gagal mengimpor: ${ex.message}"
        }
    }

    suspend fun importUrl(url: String) {
        status = "Mengunduh…"
        try {
            val (file, type) = withContext(Dispatchers.IO) {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 60000
                val t = c.contentType ?: ""
                val f = File(ctx.cacheDir, "download.tmp")
                c.inputStream.use { i -> f.outputStream().use { i.copyTo(it) } }
                Pair(f, t)
            }
            val hint = url.substringAfterLast('/').substringBefore('?').ifBlank { "peta" }
            importMap(Uri.fromFile(file), type.startsWith("image/"), hint)
        } catch (ex: Exception) {
            status = "Gagal mengunduh: ${ex.message}"
        }
    }

    suspend fun openMap(id: String, pageIdx: Int = 0) {
        val e = store.maps.firstOrNull { it.id == id } ?: return
        status = "Memuat…"
        try {
            val file = File(ctx.filesDir, "maps/${e.file}")
            val r = withContext(Dispatchers.IO) {
                if (e.isImage) loadImageFile(file) else loadPdfPage(file, pageIdx)
            }
            bitmap = r.bitmap
            autoPoints = r.autoPoints
            pageCount = r.pageCount
            currentMapId = id
            baseScale = r.scale
            detail = null
            page = pageIdx.coerceIn(0, r.pageCount - 1)
            mode = Mode.NONE
            draft = emptyList()
            update { it.copy(lastMapId = id) }
            fitToView()
            status = if (autoPoints.size >= 3) "${e.name}: ter-georeferensi otomatis"
            else if (manualPoints.size >= 3) "${e.name}: memakai kalibrasi manual"
            else if (r.note.isNotEmpty()) "${e.name}: ${r.note}"
            else "${e.name}: belum ada georeferensi. Pakai Alat → Kalibrasi manual (≥3 titik)"
        } catch (ex: Exception) {
            status = "Gagal membuka peta: ${ex.message}"
        }
    }

    fun deleteMap(id: String) {
        val e = store.maps.firstOrNull { it.id == id } ?: return
        File(ctx.filesDir, "maps/${e.file}").delete()
        update { it.copy(maps = it.maps.filter { m -> m.id != id }, lastMapId = if (it.lastMapId == id) null else it.lastMapId) }
        if (currentMapId == id) { currentMapId = null; bitmap = null; autoPoints = emptyList() }
    }

    fun setManual(pts: List<ControlPoint>) {
        val id = currentMapId ?: return
        update { s -> s.copy(maps = s.maps.map { if (it.id == id) it.copy(manual = it.manual + (page to pts)) else it }) }
    }

    // =============== tampilan ===============

    fun onViewSize(s: IntSize) {
        viewSize = s
        if (needsFit) fitToView()
    }

    private fun fitToView() {
        val b = bitmap ?: return
        if (viewSize.width <= 0) { needsFit = true; return }
        needsFit = false
        zoom = viewSize.width.toFloat() / b.width
        rotation = 0f
        offset = Offset.Zero
    }

    private fun rot(v: Offset, deg: Float): Offset {
        val a = Math.toRadians(deg.toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return Offset(v.x * c - v.y * s, v.x * s + v.y * c)
    }

    /** titik layar -> koordinat piksel peta */
    fun toMap(s: Offset): Offset = rot(s - offset, -rotation) / zoom

    fun transformBy(centroid: Offset, pan: Offset, gz: Float, gr: Float = 0f) {
        val p = toMap(centroid)
        val nz = (zoom * gz).coerceIn(0.02f, 30f)
        if (!rotateLocked) rotation = ((rotation + gr) % 360f + 360f) % 360f
        zoom = nz
        offset = centroid + pan - rot(p * nz, rotation)
    }

    private fun centerOn(x: Double, y: Double) {
        if (viewSize.width <= 0) return
        val z = max(zoom, 0.5f)
        zoom = z
        offset = Offset(viewSize.width / 2f, viewSize.height / 2f) - rot(Offset(x.toFloat(), y.toFloat()) * z, rotation)
    }

    /** Sudut utara sejati pada peta, derajat searah jarum jam dari atas halaman peta (0 jika peta menghadap utara). */
    fun northAngle(): Float {
        val t = transform ?: return 0f
        if (viewSize.width <= 0) return 0f
        val c = toMap(Offset(viewSize.width / 2f, viewSize.height / 2f))
        val ll = t.toLatLon(c.x.toDouble(), c.y.toDouble()) ?: return 0f
        val a = t.off(Pt(ll.first, ll.second))
        val b = t.off(Pt(ll.first + 0.001, ll.second))
        return Math.toDegrees(atan2((b.x - a.x).toDouble(), -(b.y - a.y).toDouble())).toFloat()
    }

    private fun rotateAboutCenter(newRot: Float) {
        if (viewSize.width <= 0) return
        val c = Offset(viewSize.width / 2f, viewSize.height / 2f)
        val p = toMap(c)
        rotation = ((newRot % 360f) + 360f) % 360f
        offset = c - rot(p * zoom, rotation)
    }

    /** Utara sejati ke atas layar. */
    fun resetNorth() {
        rotateAboutCenter(-northAngle())
    }

    fun setUnit(u: Int) {
        GeoMath.unit = u
        update { it.copy(unit = u) }
    }

    fun rulerInfo(): String {
        if (ruler.size < 2) return "Penggaris: tap titik awal dan titik akhir"
        val d = GeoMath.dist(ruler[0], ruler[1])
        val b = GeoMath.bearing(ruler[0], ruler[1])
        return "Penggaris: ${GeoMath.fmtDist(d)} · arah %.0f° ${GeoMath.compass8(b)}".format(b)
    }

    fun centerOnMe() {
        val t = transform
        val l = loc
        if (t == null || l == null) { status = "Perlu georeferensi dan sinyal GPS"; return }
        val o = t.off(Pt(l.latitude, l.longitude))
        centerOn(o.x.toDouble(), o.y.toDouble())
    }

    fun centerOnFeature(f: Feature) {
        val t = transform ?: return
        val o = t.off(target(f))
        centerOn(o.x.toDouble(), o.y.toDouble())
    }

    /** Render ulang area yang terlihat dengan resolusi penuh (hanya PDF) agar tajam saat di-zoom. */
    suspend fun renderDetail() {
        val b = bitmap ?: return
        val e = currentMap ?: return
        if (e.isImage || zoom < 1.5f || viewSize.width <= 0) { detail = null; return }
        val z = zoom
        val cs = listOf(
            toMap(Offset(0f, 0f)), toMap(Offset(viewSize.width.toFloat(), 0f)),
            toMap(Offset(0f, viewSize.height.toFloat())), toMap(Offset(viewSize.width.toFloat(), viewSize.height.toFloat())),
        )
        val vx0 = max(0f, cs.minOf { it.x })
        val vy0 = max(0f, cs.minOf { it.y })
        val vx1 = min(b.width.toFloat(), cs.maxOf { it.x })
        val vy1 = min(b.height.toFloat(), cs.maxOf { it.y })
        if (vx1 <= vx0 || vy1 <= vy0) return
        val zr = z * min(1f, min(3000f / ((vx1 - vx0) * z), 3000f / ((vy1 - vy0) * z)))
        val w = max(1, ((vx1 - vx0) * zr).toInt())
        val h = max(1, ((vy1 - vy0) * zr).toInt())
        val id = currentMapId
        val pg = page
        val s = baseScale
        val file = File(ctx.filesDir, "maps/${e.file}")
        try {
            val bmp = withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { r ->
                        r.openPage(pg).use { p ->
                            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            out.eraseColor(android.graphics.Color.WHITE)
                            val m = Matrix()
                            val k = s * zr
                            m.postScale(k, k)
                            m.postTranslate(-vx0 * zr, -vy0 * zr)
                            p.render(out, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            out
                        }
                    }
                }
            }
            if (currentMapId == id && page == pg) detail = Detail(bmp, vx0, vy0, zr)
        } catch (_: Exception) {
        }
    }

    // =============== mode & tap ===============

    fun setMode(m: Mode) {
        if (m != Mode.NONE && m != Mode.CALIBRATE && transform == null) {
            status = "Georeferensi belum siap. Kalibrasi ≥3 titik dulu."
            return
        }
        mode = if (mode == m) Mode.NONE else m
        draft = emptyList()
        ruler = emptyList()
    }

    fun onTap(p: Offset) {
        val t = transform
        val ll = t?.toLatLon(p.x.toDouble(), p.y.toDouble())
        when (mode) {
            Mode.CALIBRATE -> pendingCalib = p
            Mode.ADD_POINT -> if (ll != null) pendingPoint = Pt(ll.first, ll.second)
            Mode.MEASURE_LINE, Mode.MEASURE_AREA -> if (ll != null) draft = draft + Pt(ll.first, ll.second)
            Mode.RULER -> if (ll != null) ruler = if (ruler.size >= 2) listOf(Pt(ll.first, ll.second)) else ruler + Pt(ll.first, ll.second)
            Mode.NONE -> {
                val hit = hitTest(p.x, p.y)
                if (hit != null) selectedId = hit.id
                else if (ll != null) status = "Koordinat: " + GeoMath.fmtCoord(ll.first, ll.second, store.coordFormat)
            }
        }
    }

    private fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val u = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + u * dx), py - (ay + u * dy))
    }

    private fun hitTest(px: Float, py: Float): Feature? {
        val t = transform ?: return null
        val tol = 28.0 / zoom
        var best: Feature? = null
        var bestD = tol
        val vis = store.layers.filter { it.visible }.map { it.id }.toSet()
        for (f in store.features) {
            if (f.layerId !in vis) continue
            val xy = f.pts.map { t.toPixel(it.lat, it.lon) }
            var d = Double.MAX_VALUE
            if (f.type == FType.POINT) {
                d = hypot(px - xy[0].first, py - xy[0].second)
            } else {
                for (i in 0 until xy.size - 1) {
                    d = min(d, segDist(px.toDouble(), py.toDouble(), xy[i].first, xy[i].second, xy[i + 1].first, xy[i + 1].second))
                }
                if (f.type == FType.AREA && xy.size >= 3) {
                    d = min(d, segDist(px.toDouble(), py.toDouble(), xy.last().first, xy.last().second, xy[0].first, xy[0].second))
                }
            }
            if (d < bestD) { bestD = d; best = f }
        }
        return best
    }

    // =============== fitur ===============

    private fun nextColor(): Long = LAYER_COLORS[store.layers.size % LAYER_COLORS.size]

    fun addLayer(name: String) {
        val l = Layer(name = name, color = nextColor())
        update { it.copy(layers = it.layers + l, activeLayerId = l.id) }
    }

    fun deleteLayer(id: String) {
        store.features.filter { it.layerId == id }.forEach { f -> f.photo?.let { File(ctx.filesDir, "photos/$it").delete() } }
        update { s ->
            val rest = s.layers.filter { it.id != id }
            s.copy(
                layers = rest,
                features = s.features.filter { it.layerId != id },
                activeLayerId = if (s.activeLayerId == id) rest.firstOrNull()?.id else s.activeLayerId,
            )
        }
    }

    fun setLayerVisible(id: String, v: Boolean) =
        update { s -> s.copy(layers = s.layers.map { if (it.id == id) it.copy(visible = v) else it }) }

    fun setActiveLayer(id: String) = update { it.copy(activeLayerId = id) }

    fun addField(layerId: String, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        update { st -> st.copy(layers = st.layers.map { if (it.id == layerId && n !in it.fields) it.copy(fields = it.fields + n) else it }) }
    }

    fun removeField(layerId: String, name: String) =
        update { st -> st.copy(layers = st.layers.map { if (it.id == layerId) it.copy(fields = it.fields - name) else it }) }

    fun toggleBgGeofence() = update { it.copy(bgGeofence = !it.bgGeofence) }

    fun cycleCoordFormat() = update { it.copy(coordFormat = (it.coordFormat + 1) % 4) }

    fun addFeature(f: Feature) = update { it.copy(features = it.features + f) }

    fun updateFeature(f: Feature) =
        update { s -> s.copy(features = s.features.map { if (it.id == f.id) f else it }) }

    fun deleteFeature(id: String) {
        store.features.firstOrNull { it.id == id }?.photo?.let { File(ctx.filesDir, "photos/$it").delete() }
        update { s -> s.copy(features = s.features.filter { it.id != id }) }
        if (navTarget == id) navTarget = null
        inside.remove(id)
    }

    fun defaultName(type: FType): String {
        val n = store.features.count { it.layerId == activeLayer.id && it.type == type } + 1
        return "${typeLabel(type).replaceFirstChar { it.uppercase() }} $n"
    }

    fun savePoint(p: Pt, name: String) {
        addFeature(Feature(layerId = activeLayer.id, type = FType.POINT, name = name.ifBlank { defaultName(FType.POINT) }, pts = listOf(p)))
        pendingPoint = null
    }

    fun pointHere() {
        val l = loc
        if (l == null) { status = "GPS belum dapat sinyal"; return }
        pendingPoint = Pt(l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else Double.NaN, l.time)
    }

    // ----- gambar / ukur -----

    fun undoDraft() { if (draft.isNotEmpty()) draft = draft.dropLast(1) }

    fun draftInfo(): String = when (mode) {
        Mode.MEASURE_LINE -> "Jarak: ${GeoMath.fmtDist(GeoMath.length(draft))} · ${draft.size} titik"
        Mode.MEASURE_AREA ->
            "Luas: ${GeoMath.fmtArea(GeoMath.areaM2(draft))} · keliling ${GeoMath.fmtDist(GeoMath.perimeter(draft))} · ${draft.size} titik"
        else -> ""
    }

    fun canSaveDraft() = (mode == Mode.MEASURE_LINE && draft.size >= 2) || (mode == Mode.MEASURE_AREA && draft.size >= 3)

    fun saveDraft(name: String) {
        val type = if (mode == Mode.MEASURE_AREA) FType.AREA else FType.LINE
        addFeature(Feature(layerId = activeLayer.id, type = type, name = name.ifBlank { defaultName(type) }, pts = draft))
        draft = emptyList()
        mode = Mode.NONE
        status = "Tersimpan di layer ${activeLayer.name}"
    }

    // ----- track -----

    fun startRecording() {
        trackPts = emptyList()
        recording = true
        status = "Merekam track… (tetap jalan saat app di latar belakang)"
    }

    fun stopRecording() {
        recording = false
        val pts = trackPts
        if (pts.size >= 2) {
            val name = "Track " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            addFeature(Feature(layerId = activeLayer.id, type = FType.TRACK, name = name, pts = pts))
            status = "Track tersimpan. " + GeoMath.trackStats(pts).replace("\n", " · ")
        } else status = "Track terlalu pendek, tidak disimpan"
        trackPts = emptyList()
    }

    // ----- GPS -----

    fun startAveraging() {
        if (loc == null && !averaging) status = "Menunggu sinyal GPS untuk merata-rata…"
        avgFixes = emptyList()
        averaging = true
    }

    fun cancelAveraging() { averaging = false; avgFixes = emptyList() }

    private fun finishAveraging() {
        val f = avgFixes
        averaging = false
        avgFixes = emptyList()
        val eles = f.map { it.ele }.filter { !it.isNaN() }
        val avg = Pt(f.map { it.lat }.average(), f.map { it.lon }.average(), if (eles.isEmpty()) Double.NaN else eles.average(), System.currentTimeMillis())
        var layer = store.layers.firstOrNull { it.name == "Averaged Placemarks" }
        if (layer == null) {
            layer = Layer(name = "Averaged Placemarks", color = nextColor())
            val l = layer
            update { it.copy(layers = it.layers + l) }
        }
        val name = "Rata-rata " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        addFeature(Feature(layerId = layer.id, type = FType.POINT, name = name, pts = listOf(avg)))
        status = "Titik rata-rata (${f.size} fix) tersimpan: " + GeoMath.fmtCoord(avg.lat, avg.lon, store.coordFormat)
    }

    fun startGps() {
        if (gpsRunning) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper())
            gpsRunning = true
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    fun stopGps() {
        if (!gpsRunning) return
        lm.removeUpdates(gpsListener)
        gpsRunning = false
    }

    // ----- GPS Bluetooth -----

    fun pairedDevices(): List<BluetoothDevice> = try {
        ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList() ?: emptyList()
    } catch (e: SecurityException) {
        emptyList()
    }

    fun deviceLabel(d: BluetoothDevice): String = try { d.name ?: d.address } catch (e: SecurityException) { d.address }

    fun connectBt(d: BluetoothDevice) {
        status = "Menyambung ke ${deviceLabel(d)}…"
        bt.connect(d, deviceLabel(d))
    }

    fun disconnectBt() {
        bt.disconnect()
        btName = null
        status = "GPS Bluetooth diputus"
    }

    private fun onExternalFix(f: NmeaFix) {
        val l = Location("bluetooth")
        l.latitude = f.lat
        l.longitude = f.lon
        if (!f.alt.isNaN()) l.altitude = f.alt
        l.accuracy = f.accuracy
        l.time = System.currentTimeMillis()
        onLocation(l, true)
    }

    fun onLocation(l: Location, external: Boolean = false) {
        if (!external && btName != null) return // GPS Bluetooth diutamakan
        loc = l
        val p = Pt(l.latitude, l.longitude, if (l.hasAltitude()) l.altitude else Double.NaN, l.time)
        if (recording) {
            val last = trackPts.lastOrNull()
            if (last == null || GeoMath.dist(last, p) >= 3.0) trackPts = trackPts + p
        }
        if (averaging) {
            avgFixes = avgFixes + p
            if (avgFixes.size >= AVG_N) finishAveraging() else status = "Rata-rata GPS: ${avgFixes.size}/$AVG_N"
        }
        checkGeofences(p)
    }

    private fun buzz() {
        try {
            val v = ctx.getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    private fun checkGeofences(p: Pt) {
        val vis = store.layers.filter { it.visible }.map { it.id }.toSet()
        for (f in store.features) {
            if (f.type != FType.POINT || f.radius <= 0.0 || f.layerId !in vis) continue
            val isIn = GeoMath.dist(p, f.pts[0]) <= f.radius
            if (isIn && inside.add(f.id)) { alert = "Masuk area: ${f.name}"; buzz() }
            else if (!isIn && inside.remove(f.id)) { alert = "Keluar area: ${f.name}"; buzz() }
        }
    }

    // ----- navigasi -----

    fun target(f: Feature): Pt = if (f.type == FType.POINT) f.pts[0] else GeoMath.centroid(f.pts)

    fun navFeature(): Feature? = store.features.firstOrNull { it.id == navTarget }

    fun navInfo(): String? {
        val f = navFeature() ?: return null
        val l = loc ?: return "Navigasi → ${f.name}: menunggu GPS"
        val me = Pt(l.latitude, l.longitude)
        val tg = target(f)
        val d = GeoMath.dist(me, tg)
        val b = GeoMath.bearing(me, tg)
        val h = heading
        val rel = if (h == null) null else ((b - h + 540.0) % 360.0) - 180.0
        return "→ ${f.name}: ${GeoMath.fmtDist(d)}, arah %.0f°".format(b) +
            (if (rel != null) ", putar %+.0f°".format(rel) else "")
    }

    fun describe(f: Feature): String {
        val layer = store.layers.firstOrNull { it.id == f.layerId }?.name ?: "-"
        val sb = StringBuilder("Jenis: ${typeLabel(f.type)} · Layer: $layer\n")
        when (f.type) {
            FType.POINT -> {
                sb.append(GeoMath.fmtCoord(f.pts[0].lat, f.pts[0].lon, store.coordFormat))
                if (!f.pts[0].ele.isNaN()) sb.append("\nElevasi: %.0f m".format(f.pts[0].ele))
            }
            FType.LINE -> sb.append("Panjang: ${GeoMath.fmtDist(GeoMath.length(f.pts))} · ${f.pts.size} titik")
            FType.TRACK -> sb.append(GeoMath.trackStats(f.pts))
            FType.AREA -> sb.append(
                "Luas: ${GeoMath.fmtArea(GeoMath.areaM2(f.pts))}\nKeliling: ${GeoMath.fmtDist(GeoMath.perimeter(f.pts))}"
            )
        }
        return sb.toString()
    }

    fun loadThumb(name: String): Bitmap? = try {
        BitmapFactory.decodeFile(File(ctx.filesDir, "photos/$name").path, BitmapFactory.Options().apply { inSampleSize = 4 })
    } catch (e: Exception) {
        null
    }

    // =============== foto, impor, ekspor ===============

    suspend fun importPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        status = "Membaca foto…"
        val layerId = activeLayer.id
        val made = withContext(Dispatchers.IO) {
            val out = mutableListOf<Feature>()
            val dir = File(ctx.filesDir, "photos").apply { mkdirs() }
            for (u in uris) {
                try {
                    var ll: DoubleArray? = null
                    var alt = Double.NaN
                    ctx.contentResolver.openInputStream(u)?.use {
                        val ex = ExifInterface(it)
                        ll = ex.latLong
                        alt = ex.getAltitude(Double.NaN)
                    }
                    val c = ll ?: continue
                    val fname = UUID.randomUUID().toString() + ".jpg"
                    ctx.contentResolver.openInputStream(u)?.use { i -> File(dir, fname).outputStream().use { i.copyTo(it) } }
                    val nm = (displayName(u) ?: "Foto").substringBeforeLast('.')
                    out.add(Feature(layerId = layerId, type = FType.POINT, name = nm, pts = listOf(Pt(c[0], c[1], alt)), photo = fname))
                } catch (_: Exception) {
                }
            }
            out
        }
        made.forEach { addFeature(it) }
        status = "${made.size} dari ${uris.size} foto punya lokasi GPS dan ditambahkan sebagai titik"
    }

    suspend fun importData(uri: Uri) {
        try {
            val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
            val data: ByteArray = if (isZip) {
                var kml: ByteArray? = null
                ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                    var en = z.nextEntry
                    while (en != null) {
                        if (en.name.endsWith(".kml", true) && (kml == null || en.name.equals("doc.kml", true))) kml = z.readBytes()
                        en = z.nextEntry
                    }
                }
                kml ?: run { status = "KMZ tidak berisi file .kml"; return }
            } else bytes
            val head = data.decodeToString(0, min(data.size, 2000))
            val raws = if (head.contains("<gpx")) Importer.parseGpx(ByteArrayInputStream(data))
            else Importer.parseKml(ByteArrayInputStream(data))
            if (raws.isEmpty()) { status = "Tidak ada fitur yang bisa dibaca (KML, KMZ, atau GPX)"; return }
            val layer = Layer(name = (displayName(uri) ?: "Impor").substringBeforeLast('.'), color = nextColor())
            update { s ->
                s.copy(
                    layers = s.layers + layer,
                    features = s.features + raws.map {
                        Feature(layerId = layer.id, type = it.type, name = it.name, notes = it.notes, pts = it.pts)
                    },
                )
            }
            status = "Diimpor ${raws.size} fitur ke layer \"${layer.name}\""
        } catch (ex: Exception) {
            status = "Gagal impor: ${ex.message}"
        }
    }

    suspend fun exportTo(uri: Uri?, fmt: String) {
        if (uri == null) return
        try {
            val layers = store.layers.filter { it.visible }
            val feats = store.features.filter { f -> layers.any { it.id == f.layerId } }
            val content: ByteArray = when (fmt) {
                "gpx" -> Exporter.toGpx(layers, feats).toByteArray()
                "kml" -> Exporter.toKml(layers, feats).toByteArray()
                "kmz" -> Exporter.toKmz(layers, feats)
                "shp" -> Shapefile.toZip(layers, feats)
                else -> Exporter.toCsv(layers, feats).toByteArray()
            }
            withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(content) } }
            status = "Diekspor ${feats.size} fitur dari layer yang terlihat ($fmt)"
        } catch (ex: Exception) {
            status = "Gagal ekspor: ${ex.message}"
        }
    }
}
