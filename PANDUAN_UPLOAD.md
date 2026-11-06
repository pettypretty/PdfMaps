# Panduan: dari ZIP ke APK (tanpa Android Studio)

Yang dibutuhkan: akun GitHub (gratis) dan komputer untuk langkah upload.

## 1. Buat repo
1. Login ke github.com → tombol **+** → **New repository**.
2. Nama: `PdfMaps`. Pilih **Private** (atau Public). Klik **Create repository**.

## 2. Upload isi proyek
Cara paling mudah (lewat browser):
1. Ekstrak `PdfMaps.zip` di komputer.
2. Di halaman repo kosong, klik **uploading an existing file**.
3. Buka folder `PdfMaps` hasil ekstrak, pilih **semua isinya** (`app`, `.github`, `docs`, `build.gradle.kts`, dst.), lalu seret ke browser.
   - Folder `.github` bersifat tersembunyi. Di Windows: View → Show → Hidden items. Di Mac: tekan Cmd+Shift+titik.
   - Pastikan `.github/workflows/build.yml` ikut terupload. Tanpa file ini APK tidak akan dibuat.
4. Tunggu upload selesai → **Commit changes**.

Cara alternatif (terminal, jika git terpasang):
```
cd PdfMaps
git init && git add -A && git commit -m "first commit"
git branch -M main
git remote add origin https://github.com/USERNAME/PdfMaps.git
git push -u origin main
```

## 3. Tunggu build
1. Buka tab **Actions** di repo. Jika muncul tombol untuk mengaktifkan workflow, klik.
2. Workflow **Build APK** jalan otomatis setiap ada commit. Tunggu sekitar 5–8 menit sampai ada centang hijau.

## 4. Unduh dan install APK
- Dari HP: buka repo → **Releases** (kolom kanan) → rilis **PdfMaps (build terbaru)** → ketuk `app-debug.apk`.
- Atau dari Actions: buka run terbaru → bagian **Artifacts** → `PdfMaps-debug-apk` (berbentuk ZIP).
- Saat install, izinkan **Install dari sumber tidak dikenal** untuk browser/file manager yang dipakai.

## Jika build gagal (centang merah)
Buka run yang gagal → klik langkah yang merah → salin teks error → kirim ke Claude untuk diperbaiki. Kode ini belum pernah di-compile, jadi satu-dua perbaikan kecil di build pertama itu wajar.

## Catatan
- APK yang dihasilkan adalah **debug build**: aman untuk dipakai pribadi, tetapi belum cocok untuk dipublikasikan ke Play Store (perlu keystore dan release build).
- Setiap commit baru akan membuat APK baru dan menimpa rilis `latest`.

## Cara memakai app (ringkas)
1. **Peta → Impor PDF**. Jika PDF-nya GeoPDF, georeferensi terbaca otomatis. Jika tidak, buka **Alat → Kalibrasi manual**, tap tiga titik atau lebih di peta, lalu isi lat/lon masing-masing.
2. Titik biru = posisi GPS kamu. Izinkan akses lokasi saat diminta.
3. **Alat**: tambah titik, ukur jarak/luas (lalu **Simpan**), rekam track, rata-rata GPS, foto bergeotag.
4. Tap titik atau garis di peta untuk edit nama, catatan, geofence, **Navigasi**, atau hapus.
5. **Layer**: tampil/sembunyikan, pilih layer aktif, cari fitur.
6. **Data**: ekspor GPX/KML/CSV (hanya layer yang terlihat) atau impor KML/GPX.
