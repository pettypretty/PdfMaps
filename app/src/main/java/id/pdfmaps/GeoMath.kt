package id.pdfmaps

import kotlin.math.*

object GeoMath {
    private const val R = 6371008.8

    fun dist(a: Pt, b: Pt): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R * asin(min(1.0, sqrt(h)))
    }

    fun length(pts: List<Pt>): Double {
        var s = 0.0
        for (i in 1 until pts.size) s += dist(pts[i - 1], pts[i])
        return s
    }

    fun perimeter(pts: List<Pt>): Double =
        if (pts.size < 2) 0.0 else length(pts) + dist(pts.last(), pts.first())

    /** Arah awal dari a ke b, derajat 0..360 dari utara. */
    fun bearing(a: Pt, b: Pt): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Luas poligon (m²) lewat proyeksi equirectangular lokal + rumus shoelace. */
    fun areaM2(pts: List<Pt>): Double {
        if (pts.size < 3) return 0.0
        val lat0 = Math.toRadians(pts.map { it.lat }.average())
        val x0 = Math.toRadians(pts[0].lon) * R * cos(lat0)
        val y0 = Math.toRadians(pts[0].lat) * R
        val xs = pts.map { Math.toRadians(it.lon) * R * cos(lat0) - x0 }
        val ys = pts.map { Math.toRadians(it.lat) * R - y0 }
        var s = 0.0
        for (i in pts.indices) {
            val j = (i + 1) % pts.size
            s += xs[i] * ys[j] - xs[j] * ys[i]
        }
        return abs(s) / 2.0
    }

    /** Total tanjakan dengan ambang 3 m supaya noise GPS tidak ikut terhitung. */
    fun elevationGain(pts: List<Pt>): Double {
        var ref = Double.NaN
        var gain = 0.0
        for (p in pts) {
            if (p.ele.isNaN()) continue
            if (ref.isNaN()) { ref = p.ele; continue }
            if (p.ele - ref >= 3.0) { gain += p.ele - ref; ref = p.ele }
            else if (ref - p.ele >= 3.0) ref = p.ele
        }
        return gain
    }

    fun centroid(pts: List<Pt>): Pt =
        Pt(pts.map { it.lat }.average(), pts.map { it.lon }.average())

    /** Satuan ukur: 0 otomatis (m/km), 1 meter, 2 kilometer, 3 kaki, 4 mil, 5 mil laut. */
    val UNIT_NAMES = listOf("Otomatis (m / km)", "Meter (m)", "Kilometer (km)", "Kaki (ft)", "Mil (mi)", "Mil laut (nmi)")
    var unit: Int = 0

    /** meter per satuan */
    fun unitFactor(u: Int = unit): Double = when (u) { 2 -> 1000.0; 3 -> 0.3048; 4 -> 1609.344; 5 -> 1852.0; else -> 1.0 }

    fun unitSym(u: Int = unit): String = when (u) { 2 -> "km"; 3 -> "ft"; 4 -> "mi"; 5 -> "nmi"; else -> "m" }

    /** 1, 2, 5 x 10^n terdekat (untuk skala dan tanda penggaris) */
    fun niceStep(x: Double): Double {
        if (x <= 0.0 || x.isNaN() || x.isInfinite()) return 1.0
        val e = floor(log10(x))
        val f = x / 10.0.pow(e)
        val nf = if (f < 1.5) 1.0 else if (f < 3.5) 2.0 else if (f < 7.5) 5.0 else 10.0
        return nf * 10.0.pow(e)
    }

    private fun num(v: Double): String {
        val d = if (v >= 100) 0 else if (v >= 10) 1 else if (v >= 1) 2 else if (v >= 0.1) 3 else 4
        return String.format(java.util.Locale.US, "%.${d}f", v)
    }

    fun fmtDist(m: Double, u: Int = unit): String =
        if (u == 0) { if (m < 1000) "%.0f m".format(m) else "%.2f km".format(m / 1000) }
        else num(m / unitFactor(u)) + " " + unitSym(u)

    fun fmtArea(m2: Double, u: Int = unit): String = when (u) {
        1 -> "%.0f m²".format(m2)
        2 -> "%.3f km²".format(m2 / 1_000_000)
        3 -> "%.0f ft²".format(m2 / 0.09290304)
        4 -> if (m2 < 2_589_988.11) "%.2f acre".format(m2 / 4046.8564224) else "%.2f mi²".format(m2 / 2_589_988.110336)
        5 -> "%.3f nmi²".format(m2 / 3_429_904.0)
        else -> when {
            m2 < 10_000 -> "%.0f m²".format(m2)
            m2 < 1_000_000 -> "%.2f ha".format(m2 / 10_000)
            else -> "%.2f km²".format(m2 / 1_000_000)
        }
    }

    fun compass8(b: Double): String =
        listOf("U", "TL", "T", "TG", "S", "BD", "B", "BL")[(((b % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]

    fun fmtDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "%d j %02d m".format(h, m) else "%d m %02d d".format(m, s)
    }

    fun trackStats(pts: List<Pt>): String {
        val d = length(pts)
        val dur = if (pts.size >= 2 && pts.first().t > 0) (pts.last().t - pts.first().t) / 1000 else 0L
        val sb = StringBuilder("Jarak: ${fmtDist(d)} · ${pts.size} titik")
        if (dur > 0) {
            sb.append("\nWaktu: ${fmtDuration(dur)}")
            sb.append("\nKecepatan rata-rata: %.1f km/jam".format(d / dur * 3.6))
        }
        val g = elevationGain(pts)
        if (g > 0) sb.append("\nTanjakan: %.0f m".format(g))
        return sb.toString()
    }

    // ---------- Format koordinat ----------

    fun fmtCoord(lat: Double, lon: Double, format: Int): String = when (format) {
        1 -> dms(lat, 'N', 'S') + "  " + dms(lon, 'E', 'W')
        2 -> utm(lat, lon)
        3 -> mgrs(lat, lon)
        else -> "%.6f, %.6f".format(lat, lon)
    }

    private fun dms(v: Double, pos: Char, neg: Char): String {
        val h = if (v >= 0) pos else neg
        val a = abs(v)
        val d = a.toInt()
        val mf = (a - d) * 60
        val m = mf.toInt()
        val s = (mf - m) * 60
        return "%d°%02d'%05.2f\"%c".format(d, m, s, h)
    }

    class UtmParts(val zone: Int, val band: Char, val e: Double, val n: Double, val south: Boolean)

    private const val WGS_A = 6378137.0
    private const val WGS_F = 1 / 298.257223563
    private const val K0 = 0.9996

    fun utmParts(lat: Double, lon: Double): UtmParts? {
        if (lat < -80.0 || lat > 84.0) return null
        val n = WGS_F / (2 - WGS_F)
        val n2 = n * n
        val n3 = n2 * n
        val aa = WGS_A / (1 + n) * (1 + n2 / 4 + n2 * n2 / 64 + n2 * n2 * n2 / 256)
        val alpha = doubleArrayOf(
            n / 2 - 2 * n2 / 3 + 5 * n3 / 16,
            13 * n2 / 48 - 3 * n3 / 5,
            61 * n3 / 240,
        )
        val zone = min(60, floor((lon + 180) / 6).toInt() + 1)
        val lon0 = Math.toRadians((zone - 1) * 6 - 180 + 3.0)
        val phi = Math.toRadians(lat)
        val dl = Math.toRadians(lon) - lon0
        val e = sqrt(WGS_F * (2 - WGS_F))
        val t = sinh(atanh(sin(phi)) - e * atanh(e * sin(phi)))
        val xi0 = atan2(t, cos(dl))
        val eta0 = atanh(sin(dl) / sqrt(1 + t * t))
        var xi = xi0
        var eta = eta0
        for (j in 1..3) {
            xi += alpha[j - 1] * sin(2 * j * xi0) * cosh(2 * j * eta0)
            eta += alpha[j - 1] * cos(2 * j * xi0) * sinh(2 * j * eta0)
        }
        val easting = 500000 + K0 * aa * eta
        var northing = K0 * aa * xi
        if (lat < 0) northing += 10000000.0
        val band = "CDEFGHJKLMNPQRSTUVWX"[min(19, floor((lat + 80) / 8).toInt())]
        return UtmParts(zone, band, easting, northing, lat < 0)
    }

    /** Kebalikan UTM WGS84 -> (lat, lon) derajat. */
    fun utmToLatLon(zone: Int, south: Boolean, e: Double, nn: Double): Pair<Double, Double> {
        val n = WGS_F / (2 - WGS_F)
        val n2 = n * n
        val n3 = n2 * n
        val aa = WGS_A / (1 + n) * (1 + n2 / 4 + n2 * n2 / 64 + n2 * n2 * n2 / 256)
        val beta = doubleArrayOf(
            n / 2 - 2 * n2 / 3 + 37 * n3 / 96,
            n2 / 48 + n3 / 15,
            17 * n3 / 480,
        )
        val delta = doubleArrayOf(
            2 * n - 2 * n2 / 3 - 2 * n3,
            7 * n2 / 3 - 8 * n3 / 5,
            56 * n3 / 15,
        )
        val northing = if (south) nn - 10000000.0 else nn
        val xi = northing / (K0 * aa)
        val eta = (e - 500000.0) / (K0 * aa)
        var xi1 = xi
        var eta1 = eta
        for (j in 1..3) {
            xi1 -= beta[j - 1] * sin(2 * j * xi) * cosh(2 * j * eta)
            eta1 -= beta[j - 1] * cos(2 * j * xi) * sinh(2 * j * eta)
        }
        val chi = asin(sin(xi1) / cosh(eta1))
        var phi = chi
        for (j in 1..3) phi += delta[j - 1] * sin(2 * j * chi)
        val lon0 = Math.toRadians((zone - 1) * 6 - 180 + 3.0)
        val lam = lon0 + atan2(sinh(eta1), cos(xi1))
        return Math.toDegrees(phi) to Math.toDegrees(lam)
    }

    fun webMercatorToLatLon(x: Double, y: Double): Pair<Double, Double> {
        val r = 6378137.0
        val lon = Math.toDegrees(x / r)
        val lat = Math.toDegrees(2 * atan(exp(y / r)) - Math.PI / 2)
        return lat to lon
    }

    /** UTM WGS84, mis. "49M 315536 E 9997092 N". */
    fun utm(lat: Double, lon: Double): String {
        val u = utmParts(lat, lon) ?: return "di luar jangkauan UTM"
        return "%d%c %.0f E %.0f N".format(u.zone, u.band, u.e, u.n)
    }

    /** MGRS, mis. "49M DE 15536 97092". */
    fun mgrs(lat: Double, lon: Double): String {
        val u = utmParts(lat, lon) ?: return "di luar jangkauan MGRS"
        val colSets = arrayOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
        val col = colSets[(u.zone - 1) % 3][floor(u.e / 100000).toInt() - 1]
        val rowIdx = (floor(u.n / 100000).toInt() + if (u.zone % 2 == 0) 5 else 0) % 20
        val row = "ABCDEFGHJKLMNPQRSTUV"[rowIdx]
        val ee = (floor(u.e).toLong() % 100000).toInt()
        val nn = (floor(u.n).toLong() % 100000).toInt()
        return "%d%c %c%c %05d %05d".format(u.zone, u.band, col, row, ee, nn)
    }
}
