# Status 502/503/504 yang Benar Saat Auth Bermasalah (P3)

**Tanggal:** 27 November 2026
**Branch:** `fix/gateway-upstream-error-status`
**Status:** Selesai, siap review.

---

## Jadi, sebenarnya ini ngapain?

Gateway ibarat **resepsionis** yang meneruskan tamu ke ruangan Auth Service. Kalau ruangan itu bermasalah, resepsionis harus bisa menjelaskan **apa** masalahnya ke tamu.

Sebelumnya, apa pun masalahnya, resepsionis cuma bilang **"ada error"** (HTTP 500). Status 500 artinya "**saya sendiri** yang rusak", padahal gateway-nya sehat. Yang bermasalah adalah ruangan di belakangnya. Akibatnya:
- Monitoring dan log tidak bisa membedakan bug di gateway dengan Auth yang sedang mati.
- Client (BFF) tidak tahu apakah sebaiknya mencoba lagi.
- Body error default Spring berbeda-beda bentuknya.

Sekarang resepsionis memberi jawaban yang tepat:

| Yang terjadi | Jawaban gateway | Artinya |
|---|---|---|
| Pintu ruangan Auth terkunci atau tidak ada (container mati, DNS hilang, connect timeout) | **503** Service Unavailable + `Retry-After: 5` | "Layanannya sedang tidak tersedia, coba lagi 5 detik lagi" |
| Pintu terbuka tapi tidak ada yang menjawab dalam 10 detik | **504** Gateway Timeout | "Layanannya terlalu lama menjawab" |
| Sempat dijawab tapi teleponnya putus di tengah | **502** Bad Gateway | "Jawaban dari layanan rusak atau tidak lengkap" |
| **Auth sendiri** menjawab "saya error" (500/503) | **Diteruskan apa adanya** | Gateway tidak boleh mengganti jawaban milik Auth |

Semua jawaban gateway memakai bentuk yang sama:

```json
{"code":"503","message":"Upstream service is unavailable. Please try again shortly."}
```

---

## Yang berubah

| File | Perubahan |
|---|---|
| `error/UpstreamErrorHandler.java` (baru) | `WebExceptionHandler` dengan `@Order(-2)`, sehingga berjalan sebelum handler default Spring Boot (`-1`). Mengklasifikasikan exception, menulis status, header, dan body JSON. |
| `AuthUnavailableIntegrationTest` | Dari "5xx apa saja" menjadi tepat 503 + `Retry-After` + body, plus tidak membocorkan alamat internal. Ditambah kasus route login (yang punya rate limit). |
| `UpstreamFailureIntegrationTest` (baru) | 504 (Auth lambat), 502 (koneksi diputus), serta 500/503 milik Auth diteruskan apa adanya |
| `UpstreamErrorHandlerTest` (baru) | Unit test klasifikasi setiap jenis exception |
| `README.md` | Bagian "Upstream errors" |

## Cara kerjanya

### Klasifikasi lewat rantai `cause`
Exception dari Netty dan Spring biasanya dibungkus beberapa lapis. Handler menelusuri rantai `getCause()` dan berhenti di exception pertama yang dikenali:

| Exception | Status |
|---|---|
| `ResponseStatusException(504)` (dilempar Spring Cloud Gateway saat `response-timeout` habis), `TimeoutException`, `ReadTimeoutException` | 504 |
| `ConnectException` (termasuk `AnnotatedConnectException` dan `ConnectTimeoutException` milik Netty, yang subclass-nya), `NoRouteToHostException`, `UnknownHostException` | 503 |
| `PrematureCloseException` (Reactor Netty: koneksi ditutup sebelum respons selesai) | 502 |
| Lainnya | Tidak disentuh, diteruskan ke handler default |

### Kenapa error milik Auth tidak ikut diubah?
Respons 500 atau 503 dari Auth **bukan exception**. Dari sisi gateway, itu respons HTTP normal yang diteruskan apa adanya, jadi handler ini tidak pernah terpanggil. Ini disengaja: kalau Auth bilang "maintenance", client harus tahu persis itu yang terjadi.

### Kenapa pesan exception tidak ditampilkan?
Pesan seperti `Connection refused: /172.18.0.5:8081` membocorkan alamat internal Docker. Body hanya berisi pesan generik. Detail teknisnya ada di log server:

```
upstream failure status=503 cause=AnnotatedConnectException method=GET path=/api/auth/me
```

Yang dicatat di log adalah exception **yang menentukan status**, bukan ujung rantai `cause`. Reactor Netty kadang menempelkan exception internal yang tidak relevan di ujung rantai. Ini sempat terlihat saat smoke test, lalu diperbaiki.

### Kenapa `Retry-After` hanya untuk 503?
503 artinya "sebentar lagi mungkin pulih" (misalnya container Auth sedang restart saat deploy). 504 dan 502 tidak memberi petunjuk kapan sebaiknya mencoba lagi.

### Relevansi untuk production
Kalau container `auth` mati, Docker menghapus nama DNS `auth` dari jaringan internal, sehingga gateway mendapat `UnknownHostException` dan membalas 503. Saat deploy otomatis me-restart Auth, user yang kebetulan login di detik itu kini mendapat 503 yang jujur, bukan 500.

## Verifikasi

### Test: **48/48** (sebelumnya 38, +10)

### Cek balik: handler dimatikan sementara

| Skenario | Tanpa handler | Dengan handler |
|---|---|---|
| Auth tidak bisa dihubungi | **500** | 503 |
| Route login saat Auth mati | **500** | 503 |
| Koneksi diputus | **500** | 502 |
| Auth lambat | 504, tapi body default Spring tanpa `code` | 504 + body konsisten |
| Auth membalas 500/503 sendiri | diteruskan | diteruskan ✅ |

### Smoke test dengan jar sungguhan

```
HTTP/1.1 503 Service Unavailable
Content-Type: application/json
Cache-Control: no-store
Retry-After: 5
X-Correlation-ID: 410776bd-...
{"code":"503","message":"Upstream service is unavailable. Please try again shortly."}
```

| Upstream | Status | Penyebab di log |
|---|---|---|
| Port tertutup (`127.0.0.1:59999`) | 503 | `AnnotatedConnectException` |
| Host tidak ada (`*.invalid`) | 503 | `UnknownHostException` |

## Dampak ke frontend

Tidak ada perubahan yang diperlukan. BFF memetakan status selain 200/400/401/429 dari gateway menjadi 502 dengan pesan "Authentication service is temporarily unavailable", jadi tampilan untuk user tetap sama. Kalau nanti ingin pesan yang lebih spesifik (misalnya "coba lagi dalam beberapa detik" untuk 503), BFF tinggal membaca status 503/504 dari gateway.

## Setelah merge

CI mem-publish image, lalu timer EC2 men-deploy gateway otomatis. `GATEWAY_BFF_KEY` tetap terbawa karena skrip CD hanya mengganti baris `image:`.
