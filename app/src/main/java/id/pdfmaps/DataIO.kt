package id.pdfmaps

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream

/** Hasil baca file KML/GPX sebelum dimasukkan ke layer. */
class RawFeature(val name: String, val notes: String, val type: FType, val pts: List<Pt>)

object Exporter {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun iso(t: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(t))
    }

    fun toGpx(layers: List<Layer>, feats: List<Feature>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"PdfMaps\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (f in feats) {
            val desc = if (f.notes.isBlank()) "" else "<desc>${esc(f.notes)}</desc>"
            when (f.type) {
                FType.POINT -> {
                    val p = f.pts[0]
                    append("  <wpt lat=\"${p.lat}\" lon=\"${p.lon}\">")
                    if (!p.ele.isNaN()) append("<ele>${p.ele}</ele>")
                    append("<name>${esc(f.name)}</name>$desc</wpt>\n")
                }
                FType.LINE -> {
                    append("  <rte><name>${esc(f.name)}</name>$desc\n")
                    f.pts.forEach { append("    <rtept lat=\"${it.lat}\" lon=\"${it.lon}\"/>\n") }
                    append("  </rte>\n")
                }
                FType.TRACK, FType.AREA -> {
                    val pts = if (f.type == FType.AREA) f.pts + f.pts.first() else f.pts
                    append("  <trk><name>${esc(f.name)}</name>$desc<trkseg>\n")
                    for (p in pts) {
                        append("    <trkpt lat=\"${p.lat}\" lon=\"${p.lon}\">")
                        if (!p.ele.isNaN()) append("<ele>${p.ele}</ele>")
                        if (p.t > 0) append("<time>${iso(p.t)}</time>")
                        append("</trkpt>\n")
                    }
                    append("  </trkseg></trk>\n")
                }
            }
        }
        append("</gpx>\n")
    }

    fun toKml(layers: List<Layer>, feats: List<Feature>): String = buildString {
        fun coords(pts: List<Pt>) =
            pts.joinToString(" ") { "${it.lon},${it.lat},${if (it.ele.isNaN()) 0 else it.ele}" }
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")
        for (l in layers) {
            val fs = feats.filter { it.layerId == l.id }
            if (fs.isEmpty()) continue
            append("<Folder><name>${esc(l.name)}</name>\n")
            for (f in fs) {
                append("<Placemark><name>${esc(f.name)}</name>")
                if (f.notes.isNotBlank()) append("<description>${esc(f.notes)}</description>")
                if (f.attrs.isNotEmpty()) {
                    append("<ExtendedData>")
                    f.attrs.forEach { (k, v) -> append("<Data name=\"${esc(k)}\"><value>${esc(v)}</value></Data>") }
                    append("</ExtendedData>")
                }
                when (f.type) {
                    FType.POINT -> append("<Point><coordinates>${coords(f.pts)}</coordinates></Point>")
                    FType.LINE, FType.TRACK ->
                        append("<LineString><coordinates>${coords(f.pts)}</coordinates></LineString>")
                    FType.AREA -> append(
                        "<Polygon><outerBoundaryIs><LinearRing><coordinates>${coords(f.pts + f.pts.first())}" +
                            "</coordinates></LinearRing></outerBoundaryIs></Polygon>"
                    )
                }
                append("</Placemark>\n")
            }
            append("</Folder>\n")
        }
        append("</Document></kml>\n")
    }

    fun toKmz(layers: List<Layer>, feats: List<Feature>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("doc.kml"))
            z.write(toKml(layers, feats).toByteArray())
            z.closeEntry()
        }
        return bos.toByteArray()
    }

    fun toCsv(layers: List<Layer>, feats: List<Feature>): String = buildString {
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        val attrCols = layers.flatMap { it.fields }.distinct()
        append("name,type,layer,lat,lon,elevation_m,notes,length_m,area_m2")
        attrCols.forEach { append(",").append(q(it)) }
        append("\n")
        for (f in feats) {
            val layer = layers.firstOrNull { it.id == f.layerId }?.name ?: ""
            val c = if (f.type == FType.POINT) f.pts[0] else GeoMath.centroid(f.pts)
            val len = when (f.type) {
                FType.LINE, FType.TRACK -> GeoMath.length(f.pts)
                FType.AREA -> GeoMath.perimeter(f.pts)
                else -> 0.0
            }
            val area = if (f.type == FType.AREA) GeoMath.areaM2(f.pts) else 0.0
            append("${q(f.name)},${typeLabel(f.type)},${q(layer)},${c.lat},${c.lon},")
            append("${if (f.pts[0].ele.isNaN()) "" else f.pts[0].ele.toString()},${q(f.notes)},$len,$area")
            attrCols.forEach { append(",").append(q(f.attrs[it] ?: "")) }
            append("\n")
        }
    }
}

object Importer {
    private fun parseCoords(s: String): List<Pt> =
        s.trim().split(Regex("\\s+")).mapNotNull {
            val c = it.split(",")
            if (c.size < 2) null else {
                val lon = c[0].toDoubleOrNull()
                val lat = c[1].toDoubleOrNull()
                if (lat == null || lon == null) null
                else Pt(lat, lon, c.getOrNull(2)?.toDoubleOrNull() ?: Double.NaN)
            }
        }

    fun parseKml(input: InputStream): List<RawFeature> {
        val p = Xml.newPullParser()
        p.setInput(input, null)
        val out = mutableListOf<RawFeature>()
        var inPm = false
        var name = ""
        var desc = ""
        var type: FType? = null
        var pts = emptyList<Pt>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "Placemark" -> { inPm = true; name = ""; desc = ""; type = null; pts = emptyList() }
                    "name" -> if (inPm) name = p.nextText()
                    "description" -> if (inPm) desc = p.nextText()
                    "Point" -> if (inPm && type == null) type = FType.POINT
                    "LineString" -> if (inPm && type == null) type = FType.LINE
                    "Polygon" -> if (inPm && type == null) type = FType.AREA
                    "coordinates" -> {
                        val txt = p.nextText()
                        if (inPm && pts.isEmpty()) pts = parseCoords(txt)
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "Placemark") {
                inPm = false
                val t = type
                if (t != null && pts.isNotEmpty()) {
                    var use = pts
                    if (t == FType.AREA && use.size > 1 && use.first().lat == use.last().lat &&
                        use.first().lon == use.last().lon
                    ) use = use.dropLast(1)
                    val ok = when (t) {
                        FType.POINT -> use.isNotEmpty()
                        FType.LINE -> use.size >= 2
                        else -> use.size >= 3
                    }
                    if (ok) out.add(RawFeature(name.ifBlank { "Tanpa nama" }, desc, t, if (t == FType.POINT) listOf(use[0]) else use))
                }
            }
            ev = p.next()
        }
        return out
    }

    fun parseGpx(input: InputStream): List<RawFeature> {
        val p = Xml.newPullParser()
        p.setInput(input, null)
        val out = mutableListOf<RawFeature>()
        var ctx: String? = null
        var name = ""
        var notes = ""
        var pts = mutableListOf<Pt>()
        var inPt = false
        var lat = 0.0
        var lon = 0.0
        var ele = Double.NaN
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "wpt", "trk", "rte" -> {
                        ctx = p.name; name = ""; notes = ""; pts = mutableListOf()
                        if (p.name == "wpt") {
                            lat = p.getAttributeValue(null, "lat").toDouble()
                            lon = p.getAttributeValue(null, "lon").toDouble()
                            ele = Double.NaN
                            inPt = true
                        }
                    }
                    "trkpt", "rtept" -> {
                        lat = p.getAttributeValue(null, "lat").toDouble()
                        lon = p.getAttributeValue(null, "lon").toDouble()
                        ele = Double.NaN
                        inPt = true
                    }
                    "name" -> if (ctx != null && (!inPt || ctx == "wpt")) name = p.nextText()
                    "desc" -> if (ctx != null && (!inPt || ctx == "wpt")) notes = p.nextText()
                    "ele" -> if (inPt) ele = p.nextText().trim().toDoubleOrNull() ?: Double.NaN
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "trkpt", "rtept" -> { pts.add(Pt(lat, lon, ele)); inPt = false }
                    "wpt" -> {
                        out.add(RawFeature(name.ifBlank { "Titik" }, notes, FType.POINT, listOf(Pt(lat, lon, ele))))
                        ctx = null; inPt = false
                    }
                    "trk" -> {
                        if (pts.size >= 2) out.add(RawFeature(name.ifBlank { "Track" }, notes, FType.TRACK, pts.toList()))
                        ctx = null
                    }
                    "rte" -> {
                        if (pts.size >= 2) out.add(RawFeature(name.ifBlank { "Rute" }, notes, FType.LINE, pts.toList()))
                        ctx = null
                    }
                }
            }
            ev = p.next()
        }
        return out
    }
}
