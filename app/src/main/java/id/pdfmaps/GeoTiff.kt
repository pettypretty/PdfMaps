package id.pdfmaps

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.Inflater

class TiffException(msg: String) : Exception(msg)

/** pixels = ARGB, sudah di-subsample (stride) agar muat di memori. points = titik georeferensi (bisa kosong). */
class GeoTiffResult(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val stride: Int,
    val points: List<ControlPoint>,
    val note: String,
)

/**
 * Pembaca TIFF/GeoTIFF mandiri (tanpa library): strip & tile, 8/16-bit, gray/RGB/palette,
 * kompresi none/LZW/Deflate/PackBits/JPEG, predictor 2, BigTIFF. Georeferensi: tiepoint+scale / transformation,
 * CRS geografis, UTM (WGS84/NAD83/ETRS89), dan Web Mercator.
 */
object GeoTiff {
    /**
     * Dekoder JPEG untuk TIFF berkompresi JPEG. Di Android diisi dengan BitmapFactory (lihat AppState);
     * hasilnya (lebar, tinggi, piksel ARGB) atau null jika gagal.
     */
    var jpegDecoder: ((ByteArray) -> Triple<Int, Int, IntArray>?)? = null

    fun isTiff(file: File): Boolean = try {
        file.inputStream().use { i ->
            val h = ByteArray(4)
            i.read(h) == 4 &&
                ((h[0] == 'I'.code.toByte() && h[1] == 'I'.code.toByte() && (h[2].toInt() == 42 || h[2].toInt() == 43) && h[3].toInt() == 0) ||
                    (h[0] == 'M'.code.toByte() && h[1] == 'M'.code.toByte() && h[2].toInt() == 0 && (h[3].toInt() == 42 || h[3].toInt() == 43)))
        }
    } catch (e: Exception) {
        false
    }

    private class Entry(val type: Int, val count: Int, val pos: Int)

    private class Tiff(val buf: ByteBuffer) {
        val tags = HashMap<Int, Entry>()

        fun longs(tag: Int): LongArray? {
            val e = tags[tag] ?: return null
            return LongArray(e.count) { i ->
                when (e.type) {
                    1, 7 -> buf.get(e.pos + i).toLong() and 0xFF
                    3 -> buf.getShort(e.pos + 2 * i).toLong() and 0xFFFF
                    4 -> buf.getInt(e.pos + 4 * i).toLong() and 0xFFFFFFFFL
                    8 -> buf.getShort(e.pos + 2 * i).toLong()
                    9 -> buf.getInt(e.pos + 4 * i).toLong()
                    16, 17, 18 -> buf.getLong(e.pos + 8 * i)
                    else -> throw TiffException("Tipe tag tidak didukung: $tag")
                }
            }
        }

        fun long1(tag: Int, def: Long): Long = longs(tag)?.firstOrNull() ?: def

        fun doubles(tag: Int): DoubleArray? {
            val e = tags[tag] ?: return null
            return DoubleArray(e.count) { i ->
                when (e.type) {
                    12 -> buf.getDouble(e.pos + 8 * i)
                    11 -> buf.getFloat(e.pos + 4 * i).toDouble()
                    3 -> (buf.getShort(e.pos + 2 * i).toLong() and 0xFFFF).toDouble()
                    4 -> (buf.getInt(e.pos + 4 * i).toLong() and 0xFFFFFFFFL).toDouble()
                    else -> throw TiffException("Tipe tag tidak didukung: $tag")
                }
            }
        }
    }

    fun read(file: File, maxDim: Int = 4096): GeoTiffResult {
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.channel.size()
            if (size > Int.MAX_VALUE) throw TiffException("File TIFF terlalu besar")
            val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
            return decode(buf, maxDim)
        }
    }

    fun decode(bytes: ByteArray, maxDim: Int = 4096): GeoTiffResult = decode(ByteBuffer.wrap(bytes), maxDim)

    private fun decode(buf: ByteBuffer, maxDim: Int): GeoTiffResult {
        val b0 = buf.get(0).toInt().toChar()
        buf.order(if (b0 == 'I') ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val little = b0 == 'I'
        val magic = buf.getShort(2).toInt()
        if (magic != 42 && magic != 43) throw TiffException("Bukan file TIFF")
        val big = magic == 43
        val t = Tiff(buf)
        val ifd = (if (big) buf.getLong(8) else buf.getInt(4).toLong() and 0xFFFFFFFFL).toInt()
        val n = if (big) buf.getLong(ifd).toInt() else buf.getShort(ifd).toInt() and 0xFFFF
        val first = if (big) 8 else 2
        val esz = if (big) 20 else 12
        val inl = if (big) 8 else 4
        for (i in 0 until n) {
            val p = ifd + first + esz * i
            val tag = buf.getShort(p).toInt() and 0xFFFF
            val type = buf.getShort(p + 2).toInt() and 0xFFFF
            val count = if (big) buf.getLong(p + 4).toInt() else buf.getInt(p + 4)
            val vp = if (big) p + 12 else p + 8
            val ts = when (type) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11 -> 4; 5, 10, 12, 16, 17, 18 -> 8; else -> 1 }
            val pos = if (ts.toLong() * count <= inl) vp
            else (if (big) buf.getLong(vp) else buf.getInt(vp).toLong() and 0xFFFFFFFFL).toInt()
            t.tags[tag] = Entry(type, count, pos)
        }

        val w = t.long1(256, 0).toInt()
        val h = t.long1(257, 0).toInt()
        if (w <= 0 || h <= 0) throw TiffException("Ukuran citra tidak valid")
        val bits = t.long1(258, 8).toInt()
        val spp = t.long1(277, 1).toInt()
        val comp = t.long1(259, 1).toInt()
        val photo = t.long1(262, 1).toInt()
        val predictor = t.long1(317, 1).toInt()
        if (t.long1(284, 1) != 1L) throw TiffException("Format planar tidak didukung")
        if (t.long1(339, 1) != 1L) throw TiffException("Data bukan citra 8/16-bit (mis. elevasi float) tidak didukung")
        if (bits != 8 && bits != 16) throw TiffException("Citra $bits-bit tidak didukung")
        if (comp !in intArrayOf(1, 5, 7, 8, 32946, 32773)) {
            throw TiffException("Kompresi TIFF ($comp) tidak didukung; simpan ulang sebagai LZW/Deflate/tanpa kompresi")
        }
        val jpeg = comp == 7
        if (jpeg) {
            if (bits != 8) throw TiffException("JPEG-in-TIFF hanya 8-bit")
            if (jpegDecoder == null) throw TiffException("Dekoder JPEG tidak tersedia")
        }
        if (photo !in 0..3 && !(jpeg && photo == 6)) throw TiffException("Mode warna TIFF ($photo) tidak didukung")
        if (photo == 2 && spp < 3) throw TiffException("RGB tapi kanal kurang dari 3")
        if (predictor != 1 && predictor != 2) throw TiffException("Predictor $predictor tidak didukung")
        val jpegTables: ByteArray? = if (jpeg) t.tags[347]?.let { e -> ByteArray(e.count) { buf.get(e.pos + it) } } else null

        var palR = IntArray(0); var palG = IntArray(0); var palB = IntArray(0)
        if (photo == 3) {
            val cm = t.longs(320) ?: throw TiffException("Palet warna tidak ada")
            val m = cm.size / 3
            palR = IntArray(m) { (cm[it] shr 8).toInt() }
            palG = IntArray(m) { (cm[m + it] shr 8).toInt() }
            palB = IntArray(m) { (cm[2 * m + it] shr 8).toInt() }
        }

        val tiled = t.tags.containsKey(322)
        val tw: Int
        val th: Int
        if (tiled) { tw = t.long1(322, 0).toInt(); th = t.long1(323, 0).toInt() }
        else { tw = w; th = minOf(t.long1(278, h.toLong()).toInt(), h) }
        val offsets = t.longs(if (tiled) 324 else 273) ?: throw TiffException("Offset data tidak ada")
        val counts = t.longs(if (tiled) 325 else 279)
        val across = (w + tw - 1) / tw
        val down = (h + th - 1) / th
        val bpp0 = if (jpeg) 3 else spp * bits / 8
        val rowBytes0 = tw * bpp0

        val stride = maxOf(1, (maxOf(w, h) + maxDim - 1) / maxDim)
        val ow = (w + stride - 1) / stride
        val oh = (h + stride - 1) / stride
        val out = IntArray(ow * oh)

        for (cy in 0 until down) {
            for (cx in 0 until across) {
                val idx = cy * across + cx
                if (idx >= offsets.size) continue
                val x0 = cx * tw
                val y0 = cy * th
                val rows = if (tiled) th else minOf(th, h - y0)
                val cols = minOf(tw, w - x0)
                val expected = rowBytes0 * rows
                var bpp = bpp0
                var rowBytes = rowBytes0
                var photoE = photo
                val off = offsets[idx].toInt()
                val len = if (counts != null && idx < counts.size) counts[idx].toInt() else expected
                val src = ByteArray(len)
                val dup = buf.duplicate()
                dup.position(off)
                dup.get(src)
                val raw: ByteArray
                if (jpeg) {
                    // gabungkan JPEGTables (SOI..tabel..EOI) dengan data strip/tile (SOI..data..EOI)
                    val full = if (jpegTables != null && jpegTables.size > 4 && src.size > 2) {
                        val head = jpegTables.copyOf(jpegTables.size - 2)
                        head + src.copyOfRange(2, src.size)
                    } else src
                    val dec = jpegDecoder!!(full) ?: throw TiffException("Gagal mendekode tile JPEG")
                    val (jw, jh, px) = dec
                    bpp = 3; rowBytes = jw * 3; photoE = 2
                    raw = ByteArray(jw * jh * 3)
                    for (i in 0 until jw * jh) {
                        val c = px[i]
                        raw[3 * i] = (c shr 16).toByte(); raw[3 * i + 1] = (c shr 8).toByte(); raw[3 * i + 2] = c.toByte()
                    }
                } else {
                    raw = when (comp) {
                        1 -> src.copyOf(expected)
                        5 -> lzw(src, expected)
                        8, 32946 -> inflate(src, expected)
                        else -> packBits(src, expected)
                    }
                    if (predictor == 2) undoPredictor(raw, rows, tw, spp, bits, little)
                }
                for (ry in 0 until rows) {
                    val gy = y0 + ry
                    if (gy % stride != 0) continue
                    val oy = gy / stride
                    if (oy >= oh) continue
                    if (ry * rowBytes + rowBytes > raw.size) continue
                    for (rx in 0 until cols) {
                        val gx = x0 + rx
                        if (gx % stride != 0) continue
                        val ox = gx / stride
                        if (ox >= ow) continue
                        val o = ry * rowBytes + rx * bpp
                        fun smp(i: Int): Int = if (bits == 8 || jpeg) raw[o + i].toInt() and 0xFF
                        else (raw[o + 2 * i + (if (little) 1 else 0)].toInt() and 0xFF)
                        val r: Int
                        val g: Int
                        val bl: Int
                        when (photoE) {
                            2 -> { r = smp(0); g = smp(1); bl = smp(2) }
                            3 -> {
                                val pi = if (bits == 8) smp(0) else
                                    ((raw[o].toInt() and 0xFF) or ((raw[o + 1].toInt() and 0xFF) shl 8)).let { if (little) it else Integer.reverseBytes(it) ushr 16 }
                                val k = minOf(pi, palR.size - 1)
                                r = palR[k]; g = palG[k]; bl = palB[k]
                            }
                            else -> {
                                var v = smp(0)
                                if (photoE == 0) v = 255 - v
                                r = v; g = v; bl = v
                            }
                        }
                        out[oy * ow + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                    }
                }
            }
        }

        val (pts, note) = georef(t, w, h, stride)
        return GeoTiffResult(ow, oh, out, stride, pts, note)
    }

    // ---------- georeferensi ----------

    private fun georef(t: Tiff, w: Int, h: Int, stride: Int): Pair<List<ControlPoint>, String> {
        val keys = HashMap<Int, Int>()
        t.longs(34735)?.let { k ->
            val nk = if (k.size >= 4) k[3].toInt() else 0
            for (i in 0 until nk) {
                val b = 4 + 4 * i
                if (b + 3 >= k.size) break
                if (k[b + 1] == 0L) keys[k[b].toInt()] = k[b + 3].toInt()
            }
        }
        val modelType = keys[1024]
        val geoCs = keys[2048]
        val projCs = keys[3072]
        val pointRaster = keys[1025] == 2
        val ts = t.doubles(34264)
        val sc = t.doubles(33550)
        val tp = t.doubles(33922)
        val shift = if (pointRaster) 0.5 else 0.0

        val fn: ((Double, Double) -> Pair<Double, Double>)? = when {
            ts != null && ts.size >= 16 -> { i, j -> val u = i - shift; val v = j - shift; Pair(ts[0] * u + ts[1] * v + ts[3], ts[4] * u + ts[5] * v + ts[7]) }
            sc != null && tp != null && sc.size >= 2 && tp.size >= 6 -> { i, j ->
                Pair(tp[3] + (i - shift - tp[0]) * sc[0], tp[4] - (j - shift - tp[1]) * sc[1])
            }
            else -> null
        }
        if (fn == null && !(tp != null && tp.size >= 18)) return emptyList<ControlPoint>() to "Tidak ada tag georeferensi di TIFF ini"

        val toLL: ((Double, Double) -> Pair<Double, Double>)? = when {
            modelType == 2 -> { x, y -> Pair(y, x) }
            projCs != null && projCs in 32601..32660 -> { x, y -> GeoMath.utmToLatLon(projCs - 32600, false, x, y) }
            projCs != null && projCs in 32701..32760 -> { x, y -> GeoMath.utmToLatLon(projCs - 32700, true, x, y) }
            projCs != null && projCs in 26901..26923 -> { x, y -> GeoMath.utmToLatLon(projCs - 26900, false, x, y) }
            projCs != null && projCs in 25828..25838 -> { x, y -> GeoMath.utmToLatLon(projCs - 25800, false, x, y) }
            projCs == 3857 || projCs == 900913 || projCs == 3785 -> { x, y -> GeoMath.webMercatorToLatLon(x, y) }
            modelType == null && geoCs != null -> { x, y -> Pair(y, x) }
            else -> null
        }
        if (toLL == null) {
            val what = projCs?.let { "EPSG:$it" } ?: "CRS tidak dikenal"
            return emptyList<ControlPoint>() to "$what belum didukung. Pakai kalibrasi manual"
        }

        val pts = ArrayList<ControlPoint>()
        if (fn != null) {
            for (gx in 0..2) for (gy in 0..2) {
                val u = w * gx / 2.0
                val v = h * gy / 2.0
                val (x, y) = fn(u, v)
                val (lat, lon) = toLL(x, y)
                pts.add(ControlPoint(u / stride, v / stride, lat, lon))
            }
        } else if (tp != null) {
            var i = 0
            while (i + 5 < tp.size) {
                val (lat, lon) = toLL(tp[i + 3], tp[i + 4])
                pts.add(ControlPoint((tp[i] + shift) / stride, (tp[i + 1] + shift) / stride, lat, lon))
                i += 6
            }
        }
        return pts to ""
    }

    // ---------- dekompresi ----------

    private fun inflate(src: ByteArray, expected: Int): ByteArray {
        val inf = Inflater()
        inf.setInput(src)
        val out = ByteArray(expected)
        var n = 0
        try {
            while (!inf.finished() && n < expected) {
                val r = inf.inflate(out, n, expected - n)
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break
                n += r
            }
        } finally {
            inf.end()
        }
        return out
    }

    private fun packBits(src: ByteArray, expected: Int): ByteArray {
        val out = ByteArray(expected)
        var i = 0
        var o = 0
        while (i < src.size && o < expected) {
            val n = src[i++].toInt()
            if (n >= 0) {
                var c = n + 1
                while (c-- > 0 && i < src.size && o < expected) out[o++] = src[i++]
            } else if (n != -128) {
                val c = 1 - n
                if (i >= src.size) break
                val v = src[i++]
                var k = 0
                while (k < c && o < expected) { out[o++] = v; k++ }
            }
        }
        return out
    }

    /** LZW TIFF (MSB-first, early change). */
    private fun lzw(src: ByteArray, expected: Int): ByteArray {
        val out = ByteArray(expected)
        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val length = IntArray(4096)
        for (i in 0 until 256) { suffix[i] = i.toByte(); length[i] = 1; prefix[i] = -1 }
        var next = 258
        var bitsN = 9
        var bitBuf = 0
        var bitCnt = 0
        var ip = 0
        var o = 0
        var prev = -1

        fun writeStr(code: Int) {
            val len = length[code]
            var c = code
            var p = o + len - 1
            while (c >= 0 && p >= o) {
                if (p < expected) out[p] = suffix[c]
                p--
                c = prefix[c]
            }
            o += len
        }
        fun firstChar(code: Int): Byte {
            var c = code
            while (prefix[c] >= 0) c = prefix[c]
            return suffix[c]
        }

        while (o < expected) {
            while (bitCnt < bitsN) {
                if (ip >= src.size) return out
                bitBuf = (bitBuf shl 8) or (src[ip++].toInt() and 0xFF)
                bitCnt += 8
            }
            val code = (bitBuf shr (bitCnt - bitsN)) and ((1 shl bitsN) - 1)
            bitCnt -= bitsN
            bitBuf = bitBuf and ((1 shl bitCnt) - 1)
            if (code == 257) break
            if (code == 256) { next = 258; bitsN = 9; prev = -1; continue }
            if (prev == -1) {
                if (code < 256) { out[o++] = code.toByte() } else break
                prev = code
                continue
            }
            if (code < next) {
                val fc = firstChar(code)
                prefix[next] = prev; suffix[next] = fc; length[next] = length[prev] + 1
                next++
                writeStr(code)
            } else {
                val fc = firstChar(prev)
                prefix[next] = prev; suffix[next] = fc; length[next] = length[prev] + 1
                writeStr(next)
                next++
            }
            prev = code
            bitsN = when {
                next >= 2047 -> 12
                next >= 1023 -> 11
                next >= 511 -> 10
                else -> 9
            }
            if (next >= 4095) { /* tabel penuh; menunggu clear code */ }
        }
        return out
    }

    private fun undoPredictor(raw: ByteArray, rows: Int, tw: Int, spp: Int, bits: Int, little: Boolean) {
        val rowBytes = tw * spp * bits / 8
        if (bits == 8) {
            for (r in 0 until rows) {
                val base = r * rowBytes
                for (x in spp until rowBytes) raw[base + x] = (raw[base + x] + raw[base + x - spp]).toByte()
            }
        } else {
            val bb = ByteBuffer.wrap(raw).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            for (r in 0 until rows) {
                val base = r * rowBytes
                for (x in spp until tw * spp) {
                    val a = bb.getShort(base + 2 * x)
                    val b = bb.getShort(base + 2 * (x - spp))
                    bb.putShort(base + 2 * x, (a + b).toShort())
                }
            }
        }
    }
}
