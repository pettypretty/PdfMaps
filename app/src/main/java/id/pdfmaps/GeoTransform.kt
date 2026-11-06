package id.pdfmaps

/** Titik kontrol: koordinat bitmap (px) <-> lat/lon. */
data class ControlPoint(val px: Double, val py: Double, val lat: Double, val lon: Double)

/**
 * Transformasi affine lat/lon -> pixel (dan kebalikannya), dicocokkan dengan
 * least squares dari >= 3 titik kontrol. Cukup akurat untuk peta berskala lokal.
 */
class GeoTransform private constructor(
    private val a: DoubleArray, // px = a0*lon + a1*lat + a2
    private val b: DoubleArray, // py = b0*lon + b1*lat + b2
) {
    fun toPixel(lat: Double, lon: Double) =
        (a[0] * lon + a[1] * lat + a[2]) to (b[0] * lon + b[1] * lat + b[2])

    fun toLatLon(px: Double, py: Double): Pair<Double, Double> {
        val det = a[0] * b[1] - a[1] * b[0]
        val dx = px - a[2]
        val dy = py - b[2]
        val lon = (dx * b[1] - a[1] * dy) / det
        val lat = (a[0] * dy - dx * b[0]) / det
        return lat to lon
    }

    companion object {
        fun fit(points: List<ControlPoint>): GeoTransform? {
            if (points.size < 3) return null
            val ax = solve(points) { it.px } ?: return null
            val ay = solve(points) { it.py } ?: return null
            return GeoTransform(ax, ay)
        }

        // Normal equations 3x3 untuk target = c0*lon + c1*lat + c2
        private fun solve(pts: List<ControlPoint>, target: (ControlPoint) -> Double): DoubleArray? {
            val m = Array(3) { DoubleArray(4) }
            for (p in pts) {
                val row = doubleArrayOf(p.lon, p.lat, 1.0)
                val t = target(p)
                for (i in 0..2) {
                    for (j in 0..2) m[i][j] += row[i] * row[j]
                    m[i][3] += row[i] * t
                }
            }
            for (c in 0..2) {
                var piv = c
                for (r in c + 1..2) if (kotlin.math.abs(m[r][c]) > kotlin.math.abs(m[piv][c])) piv = r
                if (kotlin.math.abs(m[piv][c]) < 1e-12) return null
                val tmp = m[c]; m[c] = m[piv]; m[piv] = tmp
                for (r in 0..2) if (r != c) {
                    val f = m[r][c] / m[c][c]
                    for (k in c..3) m[r][k] -= f * m[c][k]
                }
            }
            return DoubleArray(3) { m[it][3] / m[it][it] }
        }
    }
}
