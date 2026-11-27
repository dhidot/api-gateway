# Rate Limit per Client (P3)

**Tanggal:** 27 November 2026
**Branch:** `feat/gateway-per-client-rate-limit` (pasangannya di frontend: `feat/bff-forward-client-ip`)
**Status:** Selesai, siap review. Perlu `GATEWAY_BFF_KEY` di EC2 dan Vercel sebelum aktif penuh (lihat "Urutan deploy").

## Masalah

Bucket4j rate limit memakai **path** sebagai kunci (`authPathKeyResolver`). Artinya satu bucket dipakai **semua user**:

```
key = "/api/auth/login"  →  60 request/menit untuk SELURUH dunia
```

Satu orang yang mengirim 60 login per menit membuat semua user lain menerima 429. Ini celah *denial of service* yang murah sekali.

Masalah kedua: semua request browser lewat BFF di Vercel. Dari sudut pandang gateway (dan Caddy), pengirimnya adalah **IP server Vercel**, bukan IP user. Mengganti kunci jadi "IP pengirim" saja tidak cukup, karena semua user akan tetap berbagi IP Vercel.

## Solusi

```
Browser ──▶ Vercel BFF ──────────────────────────────▶ Caddy ──▶ Gateway
            menambah:                                   XFF=      ClientAddressFilter:
            X-CareerTrack-Client-IP: <IP user>          IP Vercel  1. key BFF cocok → pakai Client-IP
            X-CareerTrack-BFF-Key:   <secret>                      2. selain itu → hop terakhir XFF / socket
                                                                   3. hapus kedua header
                                                                   4. simpan sebagai atribut exchange
                                                                 RateLimitConfig:
                                                                   key = path + "|" + client
```

### Komponen baru

| File | Peran |
|---|---|
| `ratelimit/ClientAddress.java` | Mengubah string IP jadi kunci. Hanya menerima **literal IP** (tidak pernah DNS lookup). IPv6 dikelompokkan per **/64**. |
| `filter/ClientAddressFilter.java` | `WebFilter` yang menentukan client: header tepercaya kalau key cocok, kalau tidak hop terakhir `X-Forwarded-For`, kalau tidak alamat socket. Kedua header dihapus sebelum diteruskan. |
| `config/RateLimitConfig.java` | `authClientKeyResolver` = path + client. Cache bucket dari 100 menjadi 50.000. |
| `application.yml` | Limit per client yang bisa diatur lewat env, plus `careertrack.gateway.bff-key`. |

### Limit baru (per client per menit)

| Endpoint | Sebelum (global) | Sesudah (per client) |
|---|---|---|
| login | 60 | 10 |
| register | 30 | 5 |
| refresh | 120 | 30 |

Angka per client lebih kecil tapi jauh lebih longgar untuk user normal. Dulu 60 login dibagi seluruh pengguna, sekarang setiap orang punya 10 sendiri. Semuanya bisa diubah lewat env tanpa build ulang.

## Keputusan desain dan alasannya

### 1. Kenapa header IP perlu "ditandatangani" dengan secret?
Kalau gateway percaya `X-CareerTrack-Client-IP` begitu saja, penyerang cukup mengirim IP acak di setiap request untuk mendapat bucket baru terus-menerus, dan rate limit jadi tidak berguna. Header itu hanya dipercaya kalau `X-CareerTrack-BFF-Key` cocok dengan `GATEWAY_BFF_KEY`. Hanya BFF di Vercel yang tahu nilainya.

Perbandingan key memakai `MessageDigest.isEqual` (*constant-time*), supaya waktu respons tidak membocorkan berapa karakter yang sudah cocok.

### 2. Kenapa hanya hop terakhir `X-Forwarded-For`?
`X-Forwarded-For: <isi dari client>, <IP yang dilihat Caddy>`. Bagian depan bisa diisi bebas oleh client, sedangkan hop terakhir ditulis Caddy dari koneksi TCP yang sebenarnya. `XForwardedRemoteAddressResolver.maxTrustedIndex(1)` hanya mengambil hop terakhir itu.

### 3. Kenapa header dihapus sebelum diteruskan?
- Secret tidak boleh sampai ke Auth Service atau tercatat di log upstream.
- Auth Service mencatat IP untuk info device. Header palsu dari internet tidak boleh ikut terbawa.

### 4. Kenapa hanya literal IP?
Kalau string seperti `evil.example` diteruskan ke `InetAddress.getByName`, Java akan melakukan **DNS lookup**: lambat, dan bisa dipakai untuk membanjiri DNS. `ClientAddress` memvalidasi format dulu. Untuk IPv6, string dibungkus `[...]` sehingga Java wajib mem-parse-nya sebagai literal dan langsung gagal kalau tidak valid, tanpa jatuh ke DNS.

### 5. Kenapa IPv6 per /64?
Satu pelanggan internet biasanya mendapat satu blok /64, yaitu 2⁶⁴ alamat. Kalau kuncinya per alamat, satu orang bisa punya miliaran bucket. Dengan per /64, satu pelanggan dihitung satu client.

### 6. Kenapa cache dinaikkan dari 100 ke 50.000?
Bucket yang terusir dari cache akan dibuat ulang dalam keadaan **penuh**. Dengan kapasitas 100, penyerang cukup memutar 100 kunci untuk mereset limit orang lain. 50.000 bucket hanya memakan beberapa MB.

### 7. Key BFF yang pendek ditolak saat startup
Sama seperti validasi `JWT_SECRET` di Auth: kalau `GATEWAY_BFF_KEY` diisi tapi kurang dari 32 karakter, gateway menolak start. Kalau kosong, fitur ini mati dan gateway memakai alamat yang ia lihat sendiri.

## Test

| Test | Isi |
|---|---|
| `ClientAddressTest` (15) | IPv4, IPv6 per /64, IPv4-mapped, dan menolak hostname, IP di luar rentang, daftar IP, script, string terlalu panjang |
| `ClientAddressFilterTest` (3) | Key pendek ditolak; kosong dan 32 karakter diterima |
| `PerClientRateLimitIntegrationTest` (6) | Client A kena limit tidak memblokir client B; IP palsu tanpa key diabaikan; IP tidak valid dengan key valid jatuh ke alamat socket; IPv6 satu /64 berbagi bucket; hanya hop terakhir XFF yang dipercaya; header tidak pernah sampai ke upstream |
| Test lama | Semua tetap lulus. Total **38/38** (sebelumnya 14). |

**Cek balik:** key resolver sempat saya kembalikan sementara ke versi lama (per path). Tiga test inti langsung gagal, jadi test ini memang menangkap bug aslinya.

**E2E dengan Auth sungguhan** (`next start` :3200 → gateway baru :18090 → Auth lokal :8081, limit login 3/menit):

```
client A 203.0.113.10: 401 401 401 429   ← kena limit
client B 203.0.113.20: 401 401           ← tidak terpengaruh
IP palsu tanpa key:     401 401 401 429   ← dihitung sebagai satu client
key muncul di log gateway: 0
```

## Perubahan kecil lain di PR ini

- `workflow_dispatch` di `gateway-image.yml`, sama seperti auth. Publish tetap hanya dari `main`.
- `mvnw` sekarang executable.
- README ditulis ulang sesuai kondisi sekarang. Versi lama masih menyebut rate limit sebagai backlog dan "belum pernah di-build".
- `.env.example` mendokumentasikan semua env baru.

## Urutan deploy (penting)

1. **Buat secret:** `openssl rand -base64 48`
2. **Pasang di dua tempat sebelum merge** (aman, karena kode lama mengabaikannya):
   - **EC2:** tambahkan `GATEWAY_BFF_KEY: ${GATEWAY_BFF_KEY}` di bagian `environment` service gateway (`compose.gateway.yaml`), dan nilainya di `.env` server.
   - **Vercel:** Project → Settings → Environment Variables → `GATEWAY_BFF_KEY` (Production). Jangan pakai prefix `NEXT_PUBLIC_`.
3. **Merge PR gateway dulu.** CI publish image, lalu timer EC2 men-deploy. Gateway mulai menghapus header dan siap memercayai key.
4. **Baru merge PR frontend.** Vercel men-deploy, dan BFF mulai mengirim IP user.

Kenapa gateway harus duluan: gateway lama tidak menghapus header, jadi kalau BFF baru sudah mengirim key sementara gateway masih versi lama, secret-nya ikut diteruskan ke Auth.

Di antara langkah 3 dan 4, semua user berbagi limit per IP Vercel (10 login per menit per IP Vercel). Untuk trafik sekarang itu aman, tapi jangan dibiarkan lama.

## Batasan yang masih ada

- Bucket disimpan di memori: reset saat container restart, dan tidak dibagi antar replika. Saat ini hanya ada satu instance.
- Limit per IP, belum per akun. Serangan yang menyebar ke banyak IP untuk satu email (*credential stuffing*) butuh limit per email di Auth Service.
- Kalau `GATEWAY_BFF_KEY` bocor, efeknya hanya rate limit yang bisa diakali. Tidak memberi akses ke data. Ganti nilainya di kedua tempat.
