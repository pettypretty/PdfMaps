# Peta konsep: fitur aplikasi sejenis Avenza Maps

Dokumen ini memetakan fitur Avenza Maps yang dijelaskan secara publik (situs resmi, deskripsi App Store, panduan lapangan) menjadi konsep teknis yang bisa dibangun sendiri. Tidak ada kode atau aset Avenza yang dibongkar. Semua fitur di bawah dibangun dari teknik terbuka.

## Inti produk
Aplikasi peta offline: pengguna mengimpor peta ber-georeferensi (PDF, GeoPDF, GeoTIFF), dan GPS perangkat menampilkan posisi di atas peta tanpa sinyal internet.

## Pemetaan fitur → cara membangun

| Fitur Avenza | Konsep teknis | Status di PdfMaps |
|---|---|---|
| Impor Geospatial PDF / GeoPDF | Baca `/VP` → `/Measure` → `/GPTS`,`/LPTS` (OGC/ISO 32000), atau `/LGIDict` (format lama TerraGo) | OGC ✅ (multi-halaman ✅), LGIDict ❌ (format lama, tidak bisa diverifikasi) |
| Impor GeoTIFF | Baca tag GeoTIFF (ModelTiepoint, ModelPixelScale, GeoKeyDirectory); butuh library TIFF/GDAL | ✅ pembaca TIFF sendiri (strip/tile, 8/16-bit, LZW/Deflate/PackBits/JPEG; BigTIFF; UTM, WGS84, Web Mercator). Belum: TIFF float/elevasi |
| JPG + file referensi | Parse world file (.jgw) atau titik kontrol | JPG/PNG + kalibrasi manual ✅, world file ❌ |
| Posisi GPS di peta, offline | Transformasi koordinat dunia → pixel; LocationManager | ✅ |
| Placemark (titik) + catatan | Model fitur: id, nama, lat/lon, atribut, waktu | ✅ nama, catatan, foto |
| Rekam track GPS | Simpan titik berkala; statistik jarak, waktu, kecepatan, elevasi | ✅ + tetap merekam di latar belakang (foreground service) |
| Ukur jarak dan luas | Haversine untuk jarak; shoelace pada koordinat terproyeksi untuk luas | ✅ + simpan sebagai garis/area |
| Layer | Fitur dikelompokkan per layer; toggle visibilitas; satu layer aktif per peta | ✅ + pencarian fitur |
| Foto bergeotag | Baca EXIF GPS → buat placemark | ✅ |
| Ekspor/impor KML, GPX, CSV, Shapefile | Serialisasi format; SHP butuh library tambahan | Ekspor GPX, KML, KMZ, Shapefile (ZIP), CSV ✅; impor KML, KMZ, GPX ✅ |
| Atribut/skema kustom | Field kustom per layer (teks, angka, pilihan) | ✅ kolom teks per layer, ikut ke KML/CSV/Shapefile |
| Geofence + notifikasi | Poligon/radius; cek posisi vs fitur; notifikasi saat masuk/keluar | ✅ radius per titik, peringatan + getar; opsi pantau di latar belakang ✅ |
| Kompas dan navigasi ke titik | Sensor orientasi; bearing dan jarak ke target | ✅ |
| Format koordinat (DD, DMS, UTM, MGRS) | Konversi proyeksi (proj4j) | DD, DMS, UTM, MGRS ✅ |
| GPS averaging | Rata-rata beberapa fix jadi satu titik lebih akurat | ✅ |
| GPS eksternal via Bluetooth | NMEA over Bluetooth SPP | ✅ (belum diuji dengan perangkat nyata) |
| Map Store | Backend katalog + pembayaran + distribusi file peta | di luar cakupan |
| Impor via QR / URL / Dropbox | Unduh file lalu lewat jalur impor yang sama | URL ✅, QR ✅, Dropbox lewat pemilih file sistem |

## Model bisnis (sebagai konteks)
Versi gratis membatasi jumlah peta yang bisa diimpor; langganan berbayar membuka impor tanpa batas dan fitur profesional seperti shapefile, atribut kustom, dan geofence tanpa batas. Toko peta adalah sumber pendapatan lain.

## Urutan pengerjaan yang disarankan
1. **Fondasi data**: simpan peta, titik, dan layer ke database lokal (Room/SQLite) agar tidak hilang saat app ditutup.
2. **Track dan ukur**: rekam track, ukur jarak/luas, statistik track.
3. **Layer dan atribut**: kelompokkan fitur, edit/hapus, skema atribut.
4. **Format tambahan**: impor KML/GPX, GeoTIFF, world file.
5. **Proyeksi**: UTM dan MGRS lewat proj4j (penting untuk Indonesia: UTM zona 47–51 S/N).
6. **Navigasi**: kompas, arah dan jarak ke titik, geofence.
7. **Performa**: render ulang area yang terlihat dengan resolusi penuh saat di-zoom ✅.

## Batas hukum
Fitur dan alur kerja aplikasi tidak dilindungi hak cipta, jadi aman ditiru. Yang perlu dihindari: memakai nama, logo, atau aset desain Avenza, serta membongkar atau menyalin kode aplikasinya. "GeoPDF" adalah merek dagang TerraGo/Avenza; gunakan istilah "Geospatial PDF" untuk produkmu.
