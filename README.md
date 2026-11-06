# PdfMaps

Aplikasi Android (Kotlin + Jetpack Compose) untuk membuka peta PDF dan menampilkan posisi GPS di atasnya, mirip Avenza Maps.

## Fitur
- Impor PDF (multi-halaman), gambar JPG/PNG, atau dari URL; semua tersimpan permanen
- Georeferensi otomatis dari GeoPDF OGC, atau kalibrasi manual (min. 3 titik)
- Posisi GPS offline + kompas, navigasi ke titik (jarak dan arah)
- Titik dengan nama, catatan, foto; layer (tampil/sembunyi, layer aktif, cari)
- Ukur/gambar garis dan area; rekam track dengan statistik; rata-rata GPS
- Foto bergeotag jadi titik; geofence radius dengan peringatan
- Koordinat DD / DMS / UTM
- Ekspor GPX, KML, CSV; impor KML dan GPX

## Dokumen
- `PANDUAN_UPLOAD.md`: dari ZIP ke APK lewat GitHub Actions
- `docs/FITUR_AVENZA.md`: peta konsep fitur dan roadmap

## Build
Buka folder ini di Android Studio (Koala atau lebih baru), sync Gradle, lalu Run di perangkat.

## Keterbatasan
- GeoPDF format Adobe/TerraGo (`/LGIDict`) dan GeoTIFF belum didukung
- Rekam track dan geofence hanya jalan saat app terbuka (belum pakai foreground service)
- PDF sangat besar dirender dalam satu bitmap (maks. 4096 px); belum ada tile
- Transformasi peta memakai affine lat/lon, cukup akurat untuk area lokal
- Shapefile, KMZ, MGRS, Bluetooth GPS, dan Map Store belum ada

## Fitur tambahan (versi terbaru)
- Rekam track & geofence tetap jalan saat layar mati (foreground service + notifikasi)
- GeoTIFF (termasuk BigTIFF & JPEG-in-TIFF), KMZ (impor/ekspor), Shapefile (ekspor ZIP), MGRS
- Atribut kustom per layer (Layer → Atribut)
- Render ulang PDF resolusi penuh saat zoom
- GPS Bluetooth (NMEA/SPP) lewat Alat → GPS Bluetooth
- Impor peta lewat QR (berisi URL)
- Catatan: modul murni (GeoTIFF, Shapefile, NMEA, UTM/MGRS) sudah diuji; UI/service/Bluetooth/QR baru terverifikasi saat dibuild di GitHub Actions.
- Putar peta dengan dua jari; tombol kompas di pojok kanan atas menunjuk utara sejati dan bila diketuk mengembalikan utara ke atas (Alat → Putar peta: terkunci untuk mematikan rotasi)
- Penggaris 2 titik (jarak + arah, dengan tanda skala) dan skala peta di pojok kiri bawah
- Satuan ukur dapat diganti: otomatis, meter, kilometer, kaki, mil, mil laut (berlaku untuk jarak, luas, penggaris, skala, navigasi)
