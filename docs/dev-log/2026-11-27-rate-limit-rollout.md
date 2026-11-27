# Rollout & Verifikasi Rate Limit per Client

**Tanggal:** 27 November 2026
**Status:** ✅ Live di production dan terverifikasi
**Lanjutan dari:** [`2026-11-27-per-client-rate-limit.md`](2026-11-27-per-client-rate-limit.md) (desain dan kode)

---

## Jadi, sebenarnya ini ngapain?

### Masalah awal

Bayangkan pintu masuk sebuah gedung dengan satpam yang aturannya "maksimal 60 orang masuk per menit, **untuk semua orang**". Satu orang iseng cukup bolak-balik 60 kali, dan semua orang lain tertahan di luar.

Begitulah rate limit login CareerTrack sebelumnya: **satu jatah untuk seluruh user.** Satu orang yang spam login bisa membuat semua user lain tidak bisa login.

### Kenapa tidak bisa langsung "per IP"?

Satpamnya (API Gateway) tidak pernah melihat wajah tamu yang sebenarnya. Semua tamu datang **diantar oleh satu kurir yang sama**, yaitu server Next.js di Vercel (BFF). Yang terlihat oleh satpam hanya wajah kurir. Kalau dihitung per wajah, semua tamu tetap dianggap satu orang.

### Solusinya

```
Browser ──▶ Vercel (BFF) ─────────────────────────▶ Gateway
            "Tamu ini IP-nya 203.0.113.9"           1. Cek kartu kurir (secret) cocok?
            + kartu identitas kurir (secret)           Ya: percaya IP tamu
                                                       Tidak: abaikan
                                                    2. Hitung jatah per tamu
                                                    3. Sobek kartu kurir sebelum tamu
                                                       diantar ke Auth
```

1. **BFF tahu IP asli user.** Vercel sendiri yang mengisi `x-real-ip`/`x-forwarded-for`, jadi browser tidak bisa memalsukannya. BFF meneruskan IP itu ke gateway lewat header `X-CareerTrack-Client-IP`.
2. **Kenapa butuh secret (`GATEWAY_BFF_KEY`)?** Tanpa secret, siapa pun di internet bisa mengirim "IP saya 1.2.3.4" dengan angka acak di setiap request untuk mendapat jatah baru terus-menerus. Gateway hanya memercayai header IP kalau kartu kurirnya (`X-CareerTrack-BFF-Key`) cocok. Hanya Vercel dan server EC2 yang tahu nilainya.
3. **Gateway menghitung jatah per user:** login 10/menit, register 5/menit, refresh 30/menit.
4. **Header dibuang** sebelum request diteruskan ke Auth Service, supaya secret tidak tersebar ke service lain.

### Yang dikerjakan saat rollout

| Langkah | Kenapa |
|---|---|
| Membuat secret acak 64 karakter | Gateway menolak start kalau kurang dari 32 karakter |
| Menyimpan secret di `.env` server dengan `read -rs` | Tidak tampil di layar dan tidak tersimpan di history terminal |
| Menambahkan `GATEWAY_BFF_KEY: ${GATEWAY_BFF_KEY:?missing}` di compose gateway | `.env` hanya dibaca Docker Compose. Container baru menerima variable yang disebut di compose file. `:?missing` membuat Compose menolak jalan kalau variable kosong. |
| Recreate container gateway saja | Container harus dibuat ulang supaya membaca variable baru. Service lain tidak tersentuh. |
| Mengecek skrip deploy otomatis (CD) | Memastikan deploy berikutnya tidak menghapus baris tadi. Skrip hanya mengganti baris `image:` dan memakai `--env-file .env` yang sama, jadi aman. |
| Set `GATEWAY_BFF_KEY` di Vercel lalu Redeploy | BFF baru mulai mengirim IP user setelah tahu secret-nya. Variable baru hanya terbaca oleh deployment baru. |

Prosedur langkah demi langkahnya ada di runbook server pribadi, yang tidak disimpan di repo ini.

---

## Hasil verifikasi production

### 1. Limit bekerja (dari laptop)

12 login salah berturut-turut ke `careertrack-frontend-ten.vercel.app` dengan akun yang tidak terdaftar:

```
1:401 2:401 3:401 4:401 5:401 6:401 7:401 8:401 9:401 10:401 11:401 12:429
{"code":"429","message":"Too many login attempts. Please try again later."}
```

### 2. Satu bucket per user, dengan isi ulang bertahap

Request ke-11 lolos karena Bucket4j mengisi ulang token **bertahap**: 10 token per 60 detik, berarti 1 token setiap ±6 detik. Pengukuran 30 request:

```
1s–7s : 401 ×11   ← 10 token awal + 1 isi ulang
8s–13s: 429       ← habis
13s   : 401       ← isi ulang berikutnya
14s–19s: 429
lolos 12 dalam 19 detik; perkiraan untuk satu bucket = 10 + 19/6 ≈ 13
```

Kalau secret tidak cocok, request akan tersebar ke beberapa bucket IP Vercel, dan jumlah yang lolos akan jauh lebih banyak dari 13.

### 3. Tidak bisa diakali

| Percobaan | Hasil |
|---|---|
| Mengirim `X-Forwarded-For` palsu ke Vercel | 429, karena Vercel menimpa header ini |
| Mengirim `X-Real-IP` palsu | 429 |
| Mengirim `X-CareerTrack-Client-IP` + key tebakan dari internet | Diabaikan, karena BFF membuat header sendiri dan tidak meneruskan header dari browser |

### 4. User lain tidak ikut terblokir (tes penentu)

Saat laptop sedang kena "Too many login attempts", **HP dengan data seluler (IP berbeda) tetap bisa login** dengan akun asli. ✅

Tes ini membuktikan dua hal sekaligus: limit benar-benar per user, dan `GATEWAY_BFF_KEY` di Vercel serta EC2 cocok.

### 5. Lain-lain

- Web `/login` 200, gateway `/api/auth/me` 401 untuk anonim: production sehat.
- `/api/v1/auth/me` tetap normal saat login sedang kena limit, karena limit hanya berlaku per endpoint.

---

## Catatan kejadian saat rollout

- **SSH ke server tidak bisa** (`kex_exchange_identification: Connection closed by remote host`). Ternyata `ssh.service` sudah sengaja dimatikan sejak 15 Okt 2026. Akses server sekarang lewat **AWS Session Manager**, yang lebih aman karena tidak butuh port 22 dan tidak ada file kunci yang bisa bocor.
- **Terminal Session Manager di browser memotong baris panjang**, sehingga perintah rusak (`sed: extra characters`, `flag needs an argument`, atau terminal diam menunggu input). Solusinya: pecah perintah jadi baris-baris pendek.
- **PR di-merge sebelum secret dipasang.** Tidak berbahaya: tanpa secret, BFF tidak mengirim header apa pun dan gateway sementara menghitung per IP Vercel. Urutan yang ideal tetap: secret dulu, lalu gateway, lalu frontend.

---

## Batasan yang masih ada

- Limitnya **per IP**, belum per akun. Penyerang dengan banyak IP masih bisa mencoba banyak password ke satu akun. Langkah berikutnya: limit atau lockout per email di Auth Service.
- Bucket disimpan di memori gateway, jadi reset saat gateway restart, dan tidak dibagi antar replika. Saat ini hanya ada satu instance.
- Kalau `GATEWAY_BFF_KEY` bocor, efeknya hanya rate limit yang bisa diakali, tanpa akses ke data. Ganti nilainya di Vercel dan EC2 sekaligus.
