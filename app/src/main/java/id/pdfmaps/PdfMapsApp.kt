package id.pdfmaps

import android.app.Application

/** State aplikasi hidup di level Application agar track dan geofence tetap jalan saat layar mati. */
class PdfMapsApp : Application() {
    val state: AppState by lazy { AppState(applicationContext) }
}
