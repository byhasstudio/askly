# askly

App Android penjawab pertanyaan dengan bubble dan panel mengambang di atas app lain. Bisa tanya dengan mengetik atau dengan screenshot layar. Jawaban dari Gemini lewat Cloudflare Worker, jadi API key tidak ada di dalam app.

## Cara kerja

App (bubble `?`) -> Cloudflare Worker (`worker/worker.js`, menyimpan API key) -> Gemini

## 1. Pasang Worker (sekali saja, bisa dari browser HP)

1. Ambil API key Gemini di https://aistudio.google.com/apikey
2. Cloudflare dashboard -> Workers & Pages -> Create -> Hello World -> Deploy
3. Edit code -> hapus isinya -> tempel isi `worker/worker.js` -> Deploy
4. Settings -> Variables and Secrets -> tambah (tipe Secret):
   - `GEMINI_API_KEY` = key dari langkah 1
   - `APP_TOKEN` = kata sandi bebas buatanmu (mencegah orang lain memakai Worker-mu)
5. Salin URL Worker (`https://....workers.dev`)

Opsional: variabel `GEMINI_MODEL` untuk mengganti model (default `gemini-2.5-flash`).

## 2. Unduh APK

Setiap push ke `main` membangun APK otomatis. Ambil di tab Releases -> "Askly (build terbaru)" -> `app-debug.apk`, lalu install.

## 3. Pakai

1. Buka app Askly, isi URL Worker dan token
2. Tekan "1. Izinkan tampil di atas app lain" lalu aktifkan izinnya
3. Tekan "2. Mulai Askly", izinkan rekam layar
4. Bubble `?` muncul. Tap untuk membuka panel, geser untuk memindahkan
5. "Kirim" untuk pertanyaan ketikan, "Tanya layar" untuk memotret layar dan menjawab isinya
6. Tombol "Singkat/Lengkap" di panel mengganti gaya jawaban

## Catatan

- Bubble tidak ikut terpotret: panel disembunyikan sesaat sebelum screenshot.
- Rotasi layar belum didukung untuk screenshot (mulai ulang Askly setelah memutar layar).
- Konten yang diproteksi app lain (misal app bank) akan tampil hitam di screenshot.
