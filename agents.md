# AGENTS.md — P25 APX1000 PoC Android System & Development Guide

## 1. System, Environment & Multimodal Awareness
AI Agent harus selalu beroperasi dalam konteks lingkungan dan alur kerja berikut:
* **Multimodal UI Context:** Pengguna akan mengunggah gambar referensi antarmuka (UI). AI Agent wajib menganalisis tata letak visual, warna, proporsi, dan elemen grafis dari gambar tersebut untuk diimplementasikan ke dalam kode layout Android.
* **Host Backend Environment:** Berada di laptop server Ubuntu yang diakses secara remote via SSH. Seluruh repositori backend (Signaling Server, Floor Control, REST API) berjalan di lingkungan Ubuntu ini.
* **Target Git Repository:** `https://github.com/Dhali-YD4GXP/P25-APPS.git`
* **Git Workflow & Automated Push:** AI Agent memiliki wewenang untuk mengeksekusi commit dan push otomatis pada laptop server Ubuntu ke repositori GitHub target. Perangkat/klien lokal akan melakukan `git pull` untuk menyinkronkan pembaruan.
* **Public Network Bridge:** Menggunakan **Cloudflare Tunnel (Zero Trust / `cloudflared`)** di server Ubuntu untuk meneruskan lalu lintas REST API dan WebSocket (WSS) ke publik tanpa memerlukan IP publik statis.

---

## 2. Target Platform & Adaptive Screen Layout
Aplikasi wajib berjalan sempurna di dua kategori perangkat dengan karakteristik layar yang sangat berbeda:

### A. Perangkat HT PoC Khusus (e.g., Hytera PNC380, Texas B5)
* **Ukuran Layar:** Layar kecil (2.0 - 2.8 inci), resolusi rendah (320x240 px), rasio 4:3 atau 3:2.
* **Tata Letak:** Tampilan APX1000 memenuhi seluruh layar (*full screen*). Elemen teks (Zone, Channel, Status) menggunakan ukuran font dinamis (`sp`/`dp` fleksibel) agar tidak terpotong (*text clipping*).

### B. Smartphone Android Standar
* **Ukuran Layar:** Layar sentuh besar (5.0 - 6.8 inci), resolusi HD/FHD/4K, rasio tinggi (16:9, 18:9, 20:9).
* **Tata Letak Layout Dual-Mode:**
  * Paruh Atas: Modul Skin APX1000 (Status Bar, Zone, Channel, ID Pembicara) yang menskalakan ukurannya secara proporsional.
  * Paruh Bawah: Area tombol PTT sentuh besar (*touchable PTT area*) untuk mempermudah pengoperasian tanpa melihat layar.

---

## 3. Core Features & Technical Requirements

### A. Authentication & User Profile Management
* **Sign In & Sign Up Flow:**
  * Antarmuka autentikasi fleksibel yang dapat dioperasikan via keypad D-Pad HT maupun layar sentuh HP.
  * Atribut Pengguna: `username`, `password`, dan **`Unit ID`** (contoh: `1001`, `ALPHA`).
  * `Unit ID` bersifat unik untuk setiap akun dan berfungsi sebagai identitas panggil radio P25.
* **Channel Management (Add Channel):**
  * Fitur "Add Channel by Name or Unique Code" untuk bergabung ke dalam *talkgroup*.
  * Pengguna dapat memasukkan Nama Kanal atau **Kode Unik Kanal** (misal: `P25-CH-8891`) untuk menambahkan kanal baru ke dalam daftar Zone/Channel di tampilan APX1000.

### B. Tampilan & Indikator Layar APX1000 (UI & RX Metadata)
* **Status Bar Atas:** 
  * Sinyal: Deteksi otomatis kekuatan sinyal aktif (Wi-Fi via `WifiManager` atau Seluler/4G via `TelephonyManager`/`PhoneStateListener` untuk API 21+).
  * Baterai: Level baterai real-time via `BroadcastReceiver` (`ACTION_BATTERY_CHANGED`).
* **Area Utama (Tengah):** Teks Zone, Teks Channel/Talkgroup, dan **Tampilan ID Pembicara (RX)**.
* **Identitas Pemancar Sisi RX (Format Ketat):** Ketika perangkat menerima aliran vokal (RX), layar APX1000 penerima **wajib menampilkan teks dengan format persis `ID : XXXX`** (contoh: `ID : 1001` atau `ID : ALPHA`) pada baris indikator pembicara, disertai perubahan warna *backlight* atau indikator ke warna Hijau.
* **Soft-Keys & Lighting:** 3 tombol menu bawah (`Chan`, `Scan`, `Cnts`) serta perubahan warna *backlight* (Green=RX, Yellow=TX, Red=Inhibit/Busy).

### C. Side PTT Button & Background Processing
* **Hardware Intercept:** Tangkap `onKeyDown` & `onKeyUp` untuk `KEYCODE_PTT` (228) serta keycode khusus vendor (288, 301, `KEYCODE_MEDIA_RECORD`).
* **Background Service:** Gunakan `ForegroundService` persisten dengan `MediaSessionCompat` dan `BroadcastReceiver` khusus vendor agar tombol PTT samping tetap merespons saat layar mati atau aplikasi di latar belakang.
* **Power Management:** Sertakan `PowerManager.PARTIAL_WAKE_LOCK` pada service agar CPU tidak masuk mode *deep sleep*.

### D. Audio & Tone Management
* **Talk Permit Tone (TPT):** Memuat berkas audio lokal (`res/raw/tpt_p25.wav`) menggunakan `SoundPool`. Streaming audio mikrofon baru aktif setelah TPT selesai berbunyi.
* **Talk Inhibit Tone (314 Hz):** Jika kanal terisi (`isChannelBusy == true`), blokir transmisi vokal, munculkan indikasi "BUSY", dan hasilkan nada **314 Hz PCM** (~350ms) menggunakan `AudioTrack`.

---

## 4. Git Initialization & Self-Push Commands
AI Agent wajib mengeksekusi skrip inisialisasi ini saat pertama kali mengonfigurasi repositori lokal di Ubuntu Server:

```bash
echo "# P25-APPS" >> README.md
git init
git add README.md
git commit -m "first commit"
git branch -M main
git remote add origin [https://github.com/Dhali-YD4GXP/P25-APPS.git](https://github.com/Dhali-YD4GXP/P25-APPS.git)
git push -u origin main
