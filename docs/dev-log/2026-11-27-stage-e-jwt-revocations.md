# Gateway Tahap E: Verifikasi Token + Daftar Pencabutan

**Tanggal:** 27 November 2026
**Branch:** `feat/gateway-jwt-revocations`
**Dasar:** ADR 0001 (repo `auth-service`), keputusan 3 dan 4
**Status:** Selesai di kode. Aman di-merge sebelum konfigurasi server; aktif penuh setelah env di EC2 dipasang (lihat "Deploy").

---

## Jadi, sebenarnya ini ngapain?

Gateway adalah **satpam di pintu depan**. Untuk pintu ke ruang Auth (`/api/auth/**`), satpam cukup mengantar tamu masuk, karena Auth yang memeriksa sendiri. Mulai sekarang ada **pintu baru ke ruang arsip lamaran** (`/api/career/**`), dan untuk pintu ini satpam harus memeriksa kartu tamu dulu:

1. **Kartu asli atau palsu?** Tanda tangan RS256 dicek dengan kunci publik dari Auth (JWKS).
2. **Kartu untuk tempat ini?** `iss`, `aud`, jenisnya harus kartu akses (bukan refresh), pemiliknya harus UUID, dan belum kadaluarsa.
3. **Kartu sudah dicabut?** Satpam memegang **salinan papan pengumuman "kartu yang dicabut"** dari Auth, diperbarui setiap 5 detik. Kartu dari sesi yang sudah logout, atau yang dibuat sebelum "logout semua device", ditolak.

Kartu yang tidak lolos tidak pernah sampai ke career-service.

---

## Yang berubah

| File | Perubahan |
|---|---|
| `security/SecurityConfig.java` | **Dua rantai security**: rantai 1 untuk `/api/career/**` (resource server JWT), rantai 2 untuk yang lain (tidak berubah) |
| `security/AccessTokenValidators.java` | Validator `exp` (30 detik), `iss`, `aud`, `type=ACCESS`, `sub` UUID, **tidak dicabut** |
| `security/RevocationList.java` | Salinan daftar pencabutan di memori; diganti utuh (*atomic swap*) setiap polling |
| `security/RevocationPoller.java` | Polling `GET /internal/revocations` setiap 5 detik dengan `X-Internal-Api-Key` |
| `security/AccessTokenProperties.java`, `RevocationProperties.java` | Konfigurasi yang divalidasi saat startup |
| `application.yml` | Route `career-service` (`/api/career/**`) + konfigurasi token dan pencabutan |
| `pom.xml` | `spring-boot-starter-oauth2-resource-server` |
| README, `.env.example` | Dokumentasi env baru |

---

## Keputusan teknis dan alasannya

### 1. Dua rantai security, bukan satu (jebakan utama)
Kalau verifikasi JWT dipasang untuk seluruh gateway, Spring Security akan memeriksa **setiap** header `Authorization: Bearer`, termasuk request ke `/api/auth/me` dan `/api/auth/logout`. Token lama (HMAC) yang **masih sah di Auth sampai 4 Des** akan ditolak di gateway sebelum sampai ke Auth, dan user yang sedang login ter-logout.

Solusinya, rantai 1 hanya berlaku untuk `/api/career/**` (`securityMatcher`), sedangkan rantai 2 untuk `/api/auth/**` tidak pernah melihat token. **Cek balik membuktikannya:** kalau verifikasi JWT ditambahkan ke rantai 2, test `authRoutesStillPassThroughWithAnyBearerToken` langsung gagal.

### 2. Pencabutan dicek sebagai validator token
Daftar pencabutan ada di memori, jadi pengecekannya sinkron dan murah. Cara paling bersih adalah menjadikannya validator di rantai yang sama dengan `iss`/`aud`/`type`. Aturannya persis seperti kontrak Auth:
- `sid` ada di daftar SESSION → ditolak.
- Ada catatan USER untuk `sub`, dan `iat < notBefore` → ditolak. Token tanpa `iat` juga ditolak, karena tidak bisa membuktikan dirinya lebih baru.
- Beberapa catatan USER untuk user yang sama → batas waktu **paling akhir** yang dipakai.

### 3. Snapshot utuh, diganti secara atomik
Setiap polling membangun daftar baru lalu menukarnya sekaligus (`AtomicReference`). Request yang sedang diproses selalu melihat daftar yang konsisten, tanpa kunci (*lock*).

### 4. Kalau Auth tidak bisa dihubungi
- Snapshot terakhir **tetap dipakai**, dan request user tetap jalan.
- Peringatan hanya dicatat **sekali** saat polling mulai gagal (tidak setiap 5 detik), lalu dicatat lagi saat pulih.
- Selama Auth mati, user juga tidak bisa logout, jadi tidak ada pencabutan baru yang terlewat.

### 5. Tanpa key → polling mati, dengan peringatan jelas
Kalau `REVOCATIONS_INTERNAL_API_KEY` kosong, gateway tetap start tapi mencatat:
`revocation polling is DISABLED. Logged-out tokens stay valid at the gateway until they expire.`
Dengan begitu PR ini aman di-merge sebelum key dipasang di server, dan route Auth tidak terpengaruh.

### 6. WebClient dibuat langsung
Spring Boot 4 tidak lagi menyediakan bean `WebClient.Builder` di gateway ini. Daripada menambah dependency, poller membuat `WebClient.builder()` sendiri, karena WebFlux memang sudah ada.

---

## Batasan penting: career-service wajib di belakang gateway

Daftar pencabutan **hanya diterapkan di gateway**, sesuai ADR. career-service memeriksa tanda tangan dan claim sendiri, tapi tidak memeriksa pencabutan. Gladi membuktikannya: token yang sudah logout **ditolak lewat gateway** tetapi **diterima kalau langsung ke career-service**.

Karena itu, di server career-service **tidak boleh punya port publik**. Ia hanya boleh ada di jaringan internal Docker, sama seperti Auth, tanpa route di Caddy.

---

## Verifikasi

### Test: **63/63** (sebelumnya 48, +15)
- `RevocationListTest` (7): daftar kosong, hanya sesi yang logout, batas `iat < notBefore` (tepat di batas tidak dicabut), token tanpa `iat`, batas terakhir menang, entri rusak diabaikan, snapshot diganti utuh
- `CareerRouteIntegrationTest` (8, dengan Auth tiruan berisi JWKS + snapshot sungguhan):
  - Token valid diteruskan **beserta header Authorization**, plus `X-Correlation-ID`
  - 11 jenis token buruk ditolak 401, dan **career-service tidak pernah dipanggil**
  - Logout sesi ditolak dalam satu polling; sesi lain tetap jalan
  - Logout-all: token sebelum batas ditolak, token setelahnya dan milik user lain tetap jalan
  - Auth tidak bisa dihubungi → snapshot terakhir tetap berlaku
  - Polling memakai key yang benar
  - **Route Auth tetap meneruskan token apa pun** (termasuk HMAC)
  - Path lain tetap ditolak

### Cek balik
| Mutasi | Test yang gagal |
|---|---|
| Validator pencabutan dihapus | 3 test logout/logout-all |
| JWT diverifikasi juga di rantai umum | `authRoutesStillPassThroughWithAnyBearerToken`, `otherPathsStayDenied` |

### Gladi dengan tiga service sungguhan
Gateway baru (18090), Auth RS256 (18999), career-service (18083):

| Langkah | Hasil |
|---|---|
| Login lewat gateway, akses career dari laptop dan HP | 200 / 200 |
| Tanpa token | 401 |
| `/api/auth/me` lewat gateway | 200 |
| **Logout laptop** | Ditolak gateway setelah **1,7 detik**; HP tetap 200; `/me` laptop 401 |
| **Logout semua device** | Ditolak setelah **5,0 detik** |
| Login baru setelah logout-all | 200 |
| Token yang sudah logout dipakai **langsung** ke career-service | 200, karena itulah career-service wajib di belakang gateway |

---

## Deploy

Merge aman kapan saja: tanpa env baru, route Auth tidak berubah, `/api/career/**` membalas 401 atau 503, dan gateway mencatat peringatan polling mati. Supaya aktif penuh, ikuti runbook bagian **6C**:

1. Buat `REVOCATIONS_INTERNAL_API_KEY` di server (≥ 32 karakter) dan simpan di `.env`.
2. Tambahkan ke compose **Auth**: `REVOCATIONS_INTERNAL_API_KEY`.
3. Tambahkan ke compose **gateway**: `REVOCATIONS_INTERNAL_API_KEY`, `AUTH_JWKS_URI=http://auth:8081/.well-known/jwks.json`, `AUTH_REVOCATIONS_URL=http://auth:8081/internal/revocations`, `CAREER_SERVICE_URL=http://career:8083`.
4. Recreate Auth, lalu gateway. Cek log gateway: tidak ada peringatan polling.

career-service sendiri di-deploy di langkah P5 berikutnya. Sampai saat itu, `/api/career/**` dengan token valid akan membalas **503** (host `career` belum ada), sesuai `UpstreamErrorHandler`.
