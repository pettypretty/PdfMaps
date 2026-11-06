package id.pdfmaps

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/**
 * Membaca georeferensi GeoPDF format OGC/ISO 32000 (/VP -> /Measure -> /GPTS, /LPTS).
 * Mengembalikan titik kontrol dalam koordinat halaman PDF (points, origin kiri-bawah).
 * Format Adobe/TerraGo (/LGIDict) belum didukung -> pakai kalibrasi manual.
 */
object GeoPdfParser {

    data class PagePoint(val x: Double, val y: Double, val lat: Double, val lon: Double)

    fun readPagePoints(file: File, pageIndex: Int = 0): List<PagePoint> {
        PDDocument.load(file).use { doc ->
            val page = doc.getPage(pageIndex)
            // PdfRenderer memakai CropBox sebagai batas halaman; geser koordinat agar sejajar.
            val cb = page.cropBox
            val ox = cb.lowerLeftX.toDouble()
            val oy = cb.lowerLeftY.toDouble()
            val vps = page.cosObject.getDictionaryObject(COSName.getPDFName("VP")) as? COSArray
                ?: return emptyList()
            for (i in 0 until vps.size()) {
                val vp = vps.getObject(i) as? COSDictionary ?: continue
                val bbox = (vp.getDictionaryObject(COSName.BBOX) as? COSArray)?.nums() ?: continue
                val measure = vp.getDictionaryObject(COSName.getPDFName("Measure")) as? COSDictionary ?: continue
                val gpts = (measure.getDictionaryObject(COSName.getPDFName("GPTS")) as? COSArray)?.nums() ?: continue
                val lpts = (measure.getDictionaryObject(COSName.getPDFName("LPTS")) as? COSArray)?.nums()
                if (bbox.size < 4 || gpts.size < 6) continue

                val n = gpts.size / 2
                // LPTS bernilai 0..1 relatif ke BBox; bila tidak ada, pakai sudut BBox standar.
                val l = lpts ?: doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0).copyOf(n * 2)
                val llx = minOf(bbox[0], bbox[2]); val urx = maxOf(bbox[0], bbox[2])
                val lly = minOf(bbox[1], bbox[3]); val ury = maxOf(bbox[1], bbox[3])
                return (0 until minOf(n, l.size / 2)).map { k ->
                    PagePoint(
                        x = llx + l[2 * k] * (urx - llx) - ox,
                        y = lly + l[2 * k + 1] * (ury - lly) - oy,
                        lat = gpts[2 * k],
                        lon = gpts[2 * k + 1],
                    )
                }
            }
        }
        return emptyList()
    }

    private fun COSArray.nums(): DoubleArray = DoubleArray(size()) {
        ((getObject(it) as? COSNumber) ?: COSNumber.ZERO).doubleValue()
    }
}
