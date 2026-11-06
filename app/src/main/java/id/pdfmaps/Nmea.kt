package id.pdfmaps

class NmeaFix(val lat: Double, val lon: Double, val alt: Double, val accuracy: Float)

/** Parser kalimat NMEA 0183 (GGA dan RMC) dari GPS eksternal. */
object Nmea {
    fun parse(line: String): NmeaFix? {
        val s = line.trim()
        if (!s.startsWith("$")) return null
        var body = s.substring(1)
        val star = body.indexOf('*')
        if (star >= 0) {
            val given = body.substring(star + 1).trim().toIntOrNull(16) ?: return null
            var x = 0
            for (c in body.substring(0, star)) x = x xor c.code
            if (x != given) return null
            body = body.substring(0, star)
        }
        val f = body.split(",")
        if (f.isEmpty() || f[0].length < 5) return null
        return when (f[0].substring(2)) {
            "GGA" -> {
                if (f.size < 10) return null
                val q = f[6].toIntOrNull() ?: 0
                if (q <= 0) return null
                val lat = coord(f[2], f[3], 2) ?: return null
                val lon = coord(f[4], f[5], 3) ?: return null
                val hdop = f[8].toDoubleOrNull() ?: 2.0
                NmeaFix(lat, lon, f[9].toDoubleOrNull() ?: Double.NaN, (hdop * 5).toFloat())
            }
            "RMC" -> {
                if (f.size < 7 || f[2] != "A") return null
                val lat = coord(f[3], f[4], 2) ?: return null
                val lon = coord(f[5], f[6], 3) ?: return null
                NmeaFix(lat, lon, Double.NaN, 10f)
            }
            else -> null
        }
    }

    private fun coord(v: String, hemi: String, degDigits: Int): Double? {
        if (v.length < degDigits + 2) return null
        val d = v.substring(0, degDigits).toDoubleOrNull() ?: return null
        val m = v.substring(degDigits).toDoubleOrNull() ?: return null
        val r = d + m / 60.0
        return if (hemi == "S" || hemi == "W") -r else r
    }
}
