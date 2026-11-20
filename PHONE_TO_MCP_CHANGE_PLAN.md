# phone to mcp — catatan perubahan dan spesifikasi refactor

> status: dokumen rencana perubahan, belum merupakan bukti bahwa perubahan sudah diterapkan
> basis audit: `/legacyAppProject/phone-to-mcp` yang diberikan pada percakapan ini
> fokus: penyederhanaan tool MCP, penguatan file operation, dan terminal dengan backend yang bisa dipilih

---

## 1. tujuan perubahan

phone to mcp diarahkan menjadi lapisan akses kemampuan perangkat android yang dapat dipanggil melalui MCP. aplikasi ini bukan remote-control android dan bukan automation agent yang mengendalikan UI aplikasi lain.

model dapat memanggil tool dan menerima hasil terstruktur, tetapi tidak memiliki jaminan untuk melihat layar aplikasi lain, memahami keadaan UI setelah suatu aksi, atau memverifikasi hasil interaksi visual. karena itu, tool yang hanya membuka aplikasi, menekan tombol sistem, berpindah halaman, atau mengubah UI tanpa mengembalikan data yang dapat diverifikasi tidak menjadi bagian dari fokus produk.

prioritas utama produk:

1. menyediakan informasi perangkat dan runtime android dalam satu tool yang terstruktur;
2. menyediakan operasi filesystem yang cukup lengkap untuk membaca, membuat, memperbarui, memindahkan, menghapus, dan mengarsipkan file;
3. menyediakan manipulasi JSON terstruktur melalui satu tool batch;
4. menyediakan terminal dengan dua backend yang dapat dipilih secara global;
5. menyediakan jalur komunikasi dari model ke pengguna melalui TTS dan notifikasi;
6. mempertahankan infrastruktur MCP, autentikasi, tunnel, logging, dan pemulihan service yang memang mendukung fungsi inti.

hasil yang diharapkan bukan sekadar jumlah tool yang lebih sedikit. setiap tool yang tersisa harus mempunyai tujuan jelas, fungsi yang dapat dijelaskan, parameter yang konsisten, dan output yang dapat digunakan model untuk mengambil keputusan berikutnya.

## 2. kondisi proyek saat diaudit

berdasarkan `java/mcp/android/phone/ToolRegistry.java`:

| metrik | kondisi saat ini |
|---|---:|
| tool terdaftar | 98 |
| aktif secara default | 21 |
| nonaktif secara default | 77 |
| kategori | 30 |

beberapa temuan penting dari implementasi saat ini:

- `device.spec` sudah menerima parameter `section`, sedangkan `device.info` hanya mengembalikan sebagian kecil informasi perangkat. `battery.info`, `display.info`, `storage.info`, dan `network.info` masih terpisah.
- `spec.view` merupakan alias dari `device.spec`.
- operasi file sudah memiliki sebagian besar kemampuan dasar, tetapi belum mempunyai `file.stat` dan belum mempunyai patch engine yang memadai.
- `editor.read_file`, `editor.create_file`, dan `editor.edit_file` menduplikasi sebagian fungsi `file.*`.
- pembacaan dan penulisan Base64 masih diekspos sebagai tool terpisah melalui `file.read_base64` dan `file.write_base64`, padahal `file.write` sudah memiliki parameter encoding.
- `FileOps.deleteRecursive()` menghapus direktori beserta seluruh isinya. deskripsi tool saat ini hanya menyebut direktori kosong, sehingga deskripsi dan perilaku aktual tidak konsisten.
- `editor.edit_file` hanya melakukan replace literal atau regex pada seluruh isi file, lalu menulis ulang file. belum ada line-based patch, context guard, dry-run, backup, maupun diff.
- `terminal.exec` saat ini hanya berjalan melalui Termux `RUN_COMMAND`. registry bahkan menyembunyikan tool terminal apabila Termux tidak terpasang.
- `TerminalExecManager` menggunakan `bash -lc` dan direktori kerja default Termux. timeout saat ini menghentikan waktu tunggu MCP, bukan jaminan bahwa proses command dihentikan.
- `notifications.read` sudah tersedia melalui `PhoneNotificationListener`, tetapi namanya belum konsisten dengan `notification.send`.
- `tts.speak` saat ini menggunakan endpoint Google Translate TTS melalui jaringan, bukan Android `TextToSpeech` API.
- fitur accessibility dan screen capture mempunyai service, activity, konfigurasi, permission, dan tombol UI sendiri.
- tombol `Enable All` saat ini mengaktifkan seluruh tool, termasuk tool yang dapat menghapus data atau melakukan tindakan eksternal.
- permission manifest masih mencakup banyak fitur yang tidak menjadi fokus rancangan baru.

arsip yang diaudit berisi source Java, `AndroidManifest.xml`, resource layout/value/XML, asset font, dan native library cloudflared. arsip ini tidak menyertakan konfigurasi build Gradle atau wrapper, sehingga validasi build penuh membutuhkan konteks build proyek aslinya.

## 3. prinsip desain setelah refactor

### 3.1 tool harus berorientasi hasil

utamakan tool yang mengembalikan data atau hasil operasi yang eksplisit. hindari tool yang hanya memicu UI dan tidak menyediakan cara bagi model untuk memastikan apa yang terjadi.

### 3.2 jangan pecah satu kemampuan menjadi banyak tool duplikat

kemampuan yang berbeda hanya pada parameter atau encoding sebaiknya berada dalam satu tool. contoh: Base64 menjadi parameter `encoding` pada `file.read` dan `file.write`, bukan tool baru.

### 3.3 jangan menyembunyikan kegagalan dengan fallback otomatis

jika backend terminal yang aktif gagal, hasil harus menunjukkan backend, shell, exit code, stderr, dan error yang terjadi. aplikasi tidak boleh diam-diam menjalankan command yang sama melalui backend lain.

### 3.4 akses dibatasi berdasarkan root dan permission

filesystem API hanya boleh mengakses root yang memang dikelola aplikasi. terminal mempunyai konteks aksesnya sendiri. jangan menganggap Android shell, app-private storage, shared storage, dan Termux home adalah satu filesystem yang mempunyai akses identik.

### 3.5 tool write harus bisa diprediksi

operasi yang berpotensi mengubah atau menghapus data harus mempunyai perilaku yang eksplisit. untuk patch, gunakan guard, backup, dry-run, match count, dan diff. untuk delete, jangan menyamarkan penghapusan rekursif sebagai penghapusan direktori kosong.

### 3.6 default permission jangan terlalu luas

tool yang memiliki efek samping tidak perlu aktif hanya karena pengguna menekan satu tombol `Enable All`. aktifkan sesuai kebutuhan dan jelaskan dampaknya.

---

## 4. rancangan tool MCP yang dipertahankan

nama di bawah merupakan nama target setelah refactor. tool yang tidak disebut sebagai bagian dari target inti dibahas pada bagian penghapusan atau opsional.

### 4.1 informasi perangkat

#### `device.info`

jadikan `device.info` sebagai satu-satunya tool utama untuk informasi perangkat.

parameter:

| parameter | tipe | wajib | default | keterangan |
|---|---|---:|---|---|
| `section` | string | tidak | `all` | bagian informasi yang diminta |

nilai `section` yang didukung:

- `all`
- `model`
- `android`
- `storage`
- `memory`
- `display`
- `cpu`
- `battery`
- `network`

isi tiap bagian:

- `model`: manufacturer, brand, model, device, product, hardware, dan informasi identitas model yang memang tersedia melalui Android API;
- `android`: Android release, API level, security patch, fingerprint, dan informasi runtime Android yang relevan;
- `storage`: total dan ruang tersedia untuk internal data storage serta shared storage;
- `memory`: total RAM, available RAM, used RAM, persentase, dan status low-memory;
- `display`: resolusi, density, density DPI, refresh rate, serta catatan bahwa refresh rate bukan pengukuran FPS GPU;
- `cpu`: jumlah core yang tersedia bagi proses, ABI, dan arsitektur yang dapat dibaca aplikasi;
- `battery`: persentase, status, health, suhu, tegangan, charging source, dan properti tambahan yang tersedia;
- `network`: status jaringan aktif dan informasi alamat/network yang dapat diakses aplikasi.

ketentuan implementasi:

1. gabungkan implementasi yang sekarang berada di `device.spec`, `battery.info`, `display.info`, `storage.info`, dan `network.info` ke dalam `device.info`.
2. tambahkan parameter `section` ke schema `device.info`.
3. `section=all` mengembalikan seluruh bagian yang tersedia.
4. section yang tidak dikenal harus mengembalikan error validasi, bukan object kosong.
5. bagian yang tidak dapat dibaca karena batasan OS harus ditandai sebagai unavailable atau disertai alasan. jangan mengarang nilai.
6. pertahankan struktur JSON yang konsisten agar model tidak perlu menebak lokasi field.
7. hindari mengembalikan field yang sama dengan nama berbeda pada beberapa bagian.

#### `time.now`

tetap dipertahankan untuk mendapatkan waktu perangkat. output minimal memuat waktu ISO-8601 dan Unix epoch dalam millisecond.

### 4.2 filesystem dan storage

filesystem adalah salah satu kemampuan inti phone to mcp. tool harus mencakup operasi umum tanpa perlu membuat tool editor terpisah.

#### root filesystem

pertahankan dua root untuk filesystem API:

| root | target | batas akses |
|---|---|---|
| `shared` | shared/internal shared storage Android, umumnya `/storage/emulated/0` | memerlukan akses shared storage yang sesuai dengan versi Android dan permission aplikasi |
| `internal` | direktori private milik aplikasi melalui `Context.getFilesDir()` | hanya file dalam sandbox private aplikasi |

catatan penting:

- `internal` bukan seluruh `/data/data` dan bukan akses bebas ke private directory aplikasi lain.
- `shared` bukan akses ke semua filesystem Linux Android.
- Termux home bukan root filesystem API. akses ke Termux home dilakukan melalui `terminal.exec` ketika backend Termux aktif.
- jangan menambahkan root `termux` palsu pada `FileOps` karena proses aplikasi Android tidak otomatis memiliki akses ke sandbox Termux.
- setiap path harus di-resolve secara canonical dan ditolak bila keluar dari root yang dipilih.

#### `file.read`

membaca isi file.

parameter target:

| parameter | tipe | wajib | default |
|---|---|---:|---|
| `root` | string | ya | — |
| `path` | string | ya | — |
| `encoding` | string | tidak | `utf8` |
| `maxBytes` | integer | tidak | 2 MB untuk UTF-8 |

`encoding` menerima `utf8` dan `base64`. batas ukuran harus diterapkan terhadap ukuran byte hasil pembacaan. untuk Base64, batasi ukuran file dan ukuran output agar MCP response tidak membengkak tanpa kontrol.

output minimal: `path`, `encoding`, `bytes`, dan `content` atau `base64` sesuai encoding.

#### `file.write`

membuat atau memperbarui isi file. tool ini menggantikan fungsi create dan Base64 write yang terpisah.

parameter target:

| parameter | tipe | wajib | default |
|---|---|---:|---|
| `root` | string | ya | — |
| `path` | string | ya | — |
| `content` | string | ya | — |
| `encoding` | string | tidak | `utf8` |
| `mode` | string | tidak | `overwrite` |

`encoding` menerima `utf8` atau `base64`. `mode` menerima:

- `overwrite`: buat file bila belum ada atau timpa file yang sudah ada;
- `create`: hanya buat file baru dan gagal bila file sudah ada.

penulisan harus menutup stream dengan benar dan sebisa mungkin menggunakan temporary file lalu atomic replace agar kegagalan saat write tidak meninggalkan file setengah tertulis.

#### `file.append`

tetap dipertahankan sebagai tool terpisah karena operasi append mempunyai maksud yang jelas dan berbeda dari overwrite.

parameter minimal: `root`, `path`, `content`, dan opsional `encoding`. append harus menambahkan byte ke akhir file tanpa menimpa isi sebelumnya.

#### `file.patch`

tambahkan tool khusus untuk mengubah bagian tertentu dari file tanpa harus menulis ulang seluruh isi melalui model.

parameter tingkat file:

| parameter | tipe | wajib | default | keterangan |
|---|---|---:|---|---|
| `root` | string | ya | — | `shared` atau `internal` |
| `path` | string | ya | — | path file teks |
| `patches` | array | ya | — | urutan operasi patch |
| `dry_run` | boolean | tidak | `false` | hitung hasil tanpa menyimpan |
| `backup` | boolean | tidak | `true` | buat backup sebelum perubahan disimpan |
| `expected_sha256` | string | tidak | — | hash file yang diharapkan sebelum patch |

setiap item `patches` mempunyai `op` dan parameter yang sesuai. operasi yang harus didukung:

| operasi | parameter utama | perilaku |
|---|---|---|
| `replace` | `find`, `replace` | mengganti seluruh kemunculan teks literal |
| `replace_first` | `find`, `replace` | mengganti kemunculan pertama saja |
| `regex` | `pattern`, `replace`, `flags`, `occurrence` | mengganti teks berdasarkan regular expression |
| `line_replace` | `line`, `content` | mengganti satu baris tertentu |
| `line_range` | `start_line`, `end_line`, `content` | mengganti rentang baris inklusif |
| `insert` | `line`, `content` | menyisipkan konten sebelum nomor baris yang ditentukan; `total_lines + 1` berarti setelah baris terakhir |
| `delete_lines` | `start_line`, `end_line` | menghapus rentang baris inklusif |
| `replace_context` | `context_before`, `find`, `context_after`, `replace` | mengganti teks yang ditemukan hanya bila konteks sekitarnya cocok |
| `prepend` | `content` | menambahkan konten di awal file |
| `append` | `content` | menambahkan konten di akhir file |

ketentuan tambahan patch:

1. nomor baris menggunakan indeks 1-based.
2. setiap operasi harus memvalidasi parameter wajib sebelum diproses.
3. dukung `expected` untuk memverifikasi teks yang seharusnya ada sebelum perubahan dilakukan.
4. dukung `occurrence` untuk memilih kemunculan tertentu bila teks muncul lebih dari sekali. nilai dapat berupa nomor kemunculan atau `all` jika operasi mengizinkan semua kemunculan.
5. dukung `expected_count` agar model dapat menetapkan jumlah match yang diharapkan. bila jumlah aktual berbeda, batalkan patch.
6. `replace_context` harus gagal bila konteks tidak cocok atau terlalu ambigu. jangan memilih lokasi secara diam-diam.
7. seluruh patch diproses berurutan di buffer memory. simpan file hanya setelah seluruh operasi berhasil divalidasi.
8. jika salah satu operasi gagal, jangan menyimpan sebagian patch.
9. `dry_run=true` tidak boleh mengubah file maupun membuat backup. hasilnya tetap menyertakan match count dan diff.
10. `backup=true` membuat satu backup dari versi original sebelum hasil akhir ditulis.
11. output harus menyertakan `changed`, `dry_run`, `backup_path` bila ada, `operations_applied`, `match_count`, ukuran sebelum/sesudah, dan unified diff.
12. bila file terlalu besar atau bukan file teks UTF-8 yang valid, kembalikan error yang jelas. jangan memperlakukan file binary sebagai teks.
13. gunakan batas maksimal file dan batas panjang patch/diff untuk mencegah memory atau response berlebihan.
14. tulis hasil akhir secara atomic sejauh filesystem mendukung.

contoh bentuk pemanggilan:

```json
{
  "root": "shared",
  "path": "project/src/config.json",
  "dry_run": true,
  "backup": true,
  "patches": [
    {
      "op": "replace_first",
      "find": "\"debug\": false",
      "replace": "\"debug\": true",
      "expected": "\"debug\": false",
      "expected_count": 1
    },
    {
      "op": "insert",
      "line": 12,
      "content": "// additional configuration"
    }
  ]
}
```

#### `file.list`

tetap dipertahankan. mendukung daftar direktori biasa dan recursive listing dengan `limit`. output minimal memuat nama, relative path, tipe file/direktori, ukuran, dan waktu modifikasi.

#### `file.stat`

tambahkan sebagai pengganti `file.exists`.

output minimal:

- `exists`;
- `type` (`file`, `directory`, `missing`, atau jenis lain bila relevan);
- `sizeBytes`;
- `modifiedAt` atau timestamp millisecond;
- `canRead` dan `canWrite` bila informasi itu dapat diperoleh secara bermakna;
- relative path dan root yang digunakan.

jika path belum ada, tool mengembalikan `exists=false` tanpa menganggapnya sebagai error. error permission atau path invalid harus dibedakan dari file yang memang tidak ada.

#### `file.mkdir`

tetap dipertahankan untuk membuat direktori. hasil harus membedakan direktori berhasil dibuat, sudah ada, dan gagal dibuat.

#### `file.copy`

tetap dipertahankan untuk copy file maupun direktori secara recursive.

pengamanan wajib:

- tolak source yang tidak ada;
- cegah menyalin direktori ke dalam dirinya sendiri atau descendant-nya;
- tetapkan kebijakan overwrite tujuan secara eksplisit;
- jangan melaporkan sukses bila sebagian isi gagal disalin;
- kembalikan path sumber dan tujuan.

#### `file.move`

tetap dipertahankan untuk memindahkan file/direktori.

pengamanan wajib:

- tolak source yang tidak ada;
- cegah pemindahan direktori ke descendant-nya sendiri;
- tetapkan kebijakan tujuan yang sudah ada;
- bila implementasi fallback copy lalu delete digunakan, validasi copy selesai sebelum source dihapus;
- kembalikan status dan path sumber/tujuan.

#### `file.delete`

tetap dipertahankan, tetapi kontrak dan pengamanannya harus diperbaiki.

parameter target:

- `root`;
- `path`;
- `recursive` boolean, default `false`.

perilaku:

- file biasa boleh dihapus;
- direktori kosong boleh dihapus;
- direktori yang tidak kosong harus gagal secara default;
- direktori tidak kosong hanya boleh dihapus bila `recursive=true`;
- tolak penghapusan root itu sendiri (`.` atau root canonical);
- hasil harus menyertakan target dan jumlah item yang terhapus bila recursive.

perubahan ini penting karena implementasi saat ini selalu memakai `deleteRecursive`, sehingga direktori tidak kosong ikut terhapus walaupun deskripsi tool menyebut direktori kosong.

#### `zip.create` dan `zip.extract`

tetap dipertahankan.

`zip.extract` saat ini sudah melakukan pemeriksaan zip-slip. pertahankan pemeriksaan canonical path dan tambahkan perlindungan terhadap arsip bomb atau ekstraksi berlebihan, seperti batas jumlah entry, total ukuran hasil ekstraksi, dan ukuran satu entry.

untuk keduanya, validasi source dan destination agar arsip tidak menimpa file yang tidak dimaksud secara diam-diam.

### 4.3 manipulasi JSON

#### `json.batch`

tetap dipertahankan sebagai tool khusus untuk manipulasi JSON terstruktur.

kemampuan yang sudah ada dan perlu dipertahankan:

- memproses 1 sampai 10 file JSON dalam satu pemanggilan;
- action `get`, `set`, `add`, `insert`, `remove`, `merge`, `rename`, `find`, dan `get_structure`;
- mendukung JSON Pointer dan dot path;
- mempertahankan tipe JSON asli untuk `value`;
- mendukung `create_missing`, `merge_mode`, `dry_run`, backup, dan pretty print;
- membatasi jumlah action per file.

catatan transaksi: setiap file diproses dan divalidasi secara independen. jangan mendeskripsikannya sebagai transaksi global atomik untuk seluruh array `files`. bila satu file gagal, output harus menunjukkan file dan action yang gagal secara jelas.

jangan mengganti `json.batch` dengan operasi terminal atau file.patch. tool ini memiliki semantik manipulasi JSON yang lebih tepat untuk perubahan struktur dan tipe data.

### 4.4 terminal dengan dua backend

terminal tetap menjadi salah satu kemampuan inti. perubahan paling penting adalah memisahkan pemilihan backend dari pemanggilan command.

#### backend yang didukung

| backend | shell | fungsi |
|---|---|---|
| `android` | `/system/bin/sh` | shell bawaan Android yang dijalankan oleh process/UID aplikasi Phone To MCP |
| `termux` | `$PREFIX/bin/bash` melalui Termux `RUN_COMMAND` | shell environment Termux dengan akses ke package dan home Termux sesuai izin Termux |

ketentuan:

1. Termux bukan dependency wajib.
2. backend `android` harus tetap tersedia walaupun Termux tidak terpasang.
3. bila Termux terpasang, pengguna tetap bebas memilih backend Android atau Termux.
4. backend aktif dipilih melalui pengaturan global aplikasi dan disimpan secara persisten.
5. backend tidak menjadi parameter wajib pada setiap pemanggilan `terminal.exec`.
6. default awal yang disarankan adalah `android`, sehingga instalasi baru tidak bergantung pada Termux. pengguna atau model dapat mengubahnya ke `termux`.
7. jangan menyamakan kemampuan Android shell dengan Termux. Android shell berjalan dengan privilege aplikasi, bukan root, dan tidak otomatis dapat mengakses sandbox Termux.
8. Termux backend tetap membutuhkan Termux yang kompatibel, izin `com.termux.permission.RUN_COMMAND`, serta konfigurasi `allow-external-apps=true` di Termux.

#### `terminal.info`

tambahkan tool untuk mengetahui konfigurasi terminal sebelum menjalankan command.

output minimal:

- backend aktif;
- daftar backend yang terpasang/tersedia;
- status instalasi Termux;
- status izin RUN_COMMAND;
- shell executable yang benar-benar digunakan;
- working directory default;
- batasan backend yang diketahui;
- informasi PATH atau environment yang aman dan relevan bila dapat dibaca.

jangan mengklaim sebuah command tersedia hanya berdasarkan nama backend. informasi command availability dapat diberikan bila benar-benar diperiksa.

#### `terminal.set_backend`

tambahkan tool untuk mengganti backend global yang aktif.

parameter:

| parameter | tipe | wajib | nilai |
|---|---|---:|---|
| `backend` | string | ya | `android` atau `termux` |

perilaku:

- memvalidasi backend yang diminta;
- menolak pemilihan `termux` bila Termux tidak terpasang atau permission RUN_COMMAND belum tersedia;
- menyimpan pilihan ke konfigurasi global aplikasi;
- mengembalikan backend sebelumnya dan backend baru;
- tidak menjalankan command apa pun sebagai bagian dari pergantian backend.

perubahan dari UI pengaturan aplikasi dan pemanggilan `terminal.set_backend` harus memakai setting persisten yang sama. model boleh memutuskan berpindah backend setelah mendiagnosis kegagalan command, tetapi tidak boleh mengganti backend hanya karena command mengembalikan exit code non-zero tanpa memahami penyebabnya.

#### `terminal.exec`

schema target:

| parameter | tipe | wajib | default | keterangan |
|---|---|---:|---|---|
| `command` | string | ya | — | command yang akan dijalankan |
| `cwd` | string | tidak | tergantung backend | working directory |
| `timeoutMs` | integer | tidak | 30000 | batas waktu menunggu hasil |

backend ditentukan dari global setting, bukan dari parameter `terminal.exec`.

working directory default:

- Android: shared storage root atau direktori default Android yang ditetapkan aplikasi secara konsisten;
- Termux: home directory Termux.

`cwd` yang dikirim model harus sesuai dengan backend yang sedang aktif. bila path tidak tersedia di backend tersebut, kembalikan error path; jangan mengubah backend secara otomatis.

setiap response wajib memuat setidaknya:

```json
{
  "backend": "android",
  "shell": "/system/bin/sh",
  "command": "pwd",
  "cwd": "/storage/emulated/0",
  "stdout": "/storage/emulated/0",
  "stderr": "",
  "exitCode": 0,
  "completed": true,
  "timedOut": false,
  "durationMs": 12
}
```

field tambahan dapat mencakup `error`, `executionId`, dan informasi logging. contoh di atas hanya ilustrasi format response.

#### kebijakan kegagalan dan fallback

**tidak boleh ada automatic fallback.**

contoh alasan command gagal:

- command tidak ditemukan;
- permission denied;
- path tidak valid atau tidak dapat diakses;
- syntax error;
- dependency tidak terpasang;
- executable tidak kompatibel;
- process gagal dijalankan;
- timeout;
- Termux tidak merespons atau tidak mengirim result.

semua kondisi itu tidak berarti backend lain pasti dapat menjalankan command yang sama. menjalankan ulang otomatis dapat membuat efek samping ganda bila command pertama sebenarnya masih berjalan.

alur yang diharapkan:

1. model memanggil `terminal.exec` menggunakan backend aktif;
2. aplikasi mengembalikan output, exit code, backend, shell, dan error;
3. model menganalisis penyebab kegagalan;
4. jika penyebabnya memang keterbatasan backend, model dapat memanggil `terminal.set_backend`;
5. model memeriksa ulang konfigurasi melalui `terminal.info` bila diperlukan;
6. model menjalankan command ulang hanya setelah menilai bahwa pengulangan aman.

#### timeout dan proses

- timeout harus ditandai secara eksplisit pada response dan terminal log.
- Android backend sebaiknya menghentikan process yang dikelola aplikasi saat timeout, termasuk child process sejauh dapat dilakukan dengan aman.
- Termux backend saat ini hanya menunggu result melalui PendingIntent callback. timeout pada sisi MCP tidak menjamin proses Termux dihentikan. jangan mengubah deskripsi menjadi seolah-olah proses pasti dibunuh sebelum mekanisme kill/cancel benar-benar tersedia.
- jangan otomatis mengirim ulang command setelah timeout.

#### terminal log

pertahankan `TerminalLogDb`, `TerminalLogActivity`, dan `TerminalResultService` selama masih digunakan oleh Termux backend.

modifikasi log agar setiap eksekusi mencatat:

- backend;
- shell executable;
- command;
- cwd;
- waktu mulai dan durasi;
- stdout dan stderr;
- exit code;
- completed/timedOut;
- error dispatch atau callback.

riwayat harus menunjukkan backend yang benar-benar menjalankan command, bukan sekadar backend yang sedang dipilih setelah eksekusi selesai.

### 4.5 notifikasi

pertahankan kemampuan mengirim notifikasi kepada pengguna dan membaca notifikasi perangkat.

#### `notification.send`

pertahankan nama dan fungsi saat ini. parameter utama: `id`, `title`, `message`.

output harus menunjukkan apakah notifikasi berhasil dikirim dan ID yang digunakan. dokumentasikan bahwa pada versi Android tertentu notifikasi memerlukan permission `POST_NOTIFICATIONS`.

#### `notification.read`

ubah nama tool saat ini dari `notifications.read` menjadi `notification.read` agar konsisten dengan `notification.send`.

fungsi tetap membaca:

- notifikasi aktif;
- riwayat notifikasi yang ditangkap ketika Notification Listener aktif.

jelaskan batasannya: riwayat sebelum listener diaktifkan tidak otomatis tersedia. tool harus mengembalikan status koneksi/akses listener, bukan mengembalikan array kosong tanpa konteks.

hapus `notifications.dismiss` dari tool inti. menutup notifikasi bukan kebutuhan utama dalam rancangan ini.

### 4.6 text-to-speech

pertahankan:

- `tts.speak` untuk menyampaikan teks kepada pengguna;
- `tts.stop` untuk menghentikan playback atau antrean yang sedang berjalan.

modifikasi yang direkomendasikan:

1. pindahkan implementasi utama dari Google Translate TTS endpoint tidak resmi ke Android `TextToSpeech` API;
2. gunakan engine yang tersedia pada perangkat dan bahasa yang didukung engine;
3. kembalikan status engine, language availability, dan status antrean secara eksplisit;
4. pertahankan dukungan queue/flush;
5. `tts.stop` harus menghentikan antrean dan utterance aktif;
6. jangan menyatakan TTS offline bila engine yang dipakai ternyata membutuhkan jaringan.

alasan: implementasi saat ini membangun URL Google Translate TTS dan memutar audio melalui MediaPlayer. ketergantungan ini membuat TTS bergantung pada endpoint jaringan eksternal dan bukan engine TTS native Android.

### 4.7 download

pertahankan `download.url` sebagai tool opsional.

alasan: tool ini menerima URL umum dan mempunyai fungsi praktis, berbeda dari downloader khusus satu platform.

pertahankan penggunaan DownloadManager atau mekanisme setara, tetapi pastikan response mengembalikan status/job ID dan lokasi hasil unduhan bila tersedia. jangan mengklaim file selesai sebelum DownloadManager mengonfirmasi status selesai.

### 4.8 tool administrasi dan diagnosis

pertahankan hanya tool administrasi yang mempunyai manfaat langsung untuk memeriksa MCP:

- `server.status`: status server, port, autentikasi, dan URL tunnel terakhir;
- `mcp.tools_enabled`: daftar tool yang sedang diaktifkan.

`access.permissions` dan `access.special_status` tidak perlu menjadi tool umum yang selalu ditawarkan ke model. informasi permission dapat tetap ditampilkan pada UI aplikasi atau diakses melalui mekanisme diagnosis bila memang dibutuhkan.

### 4.9 tool berbagi

`share.text` dan `share.file` bukan prioritas inti. keduanya membuka Android chooser dan membutuhkan interaksi pengguna.

pilihan implementasi:

- keluarkan dari daftar tool MCP default;
- pertahankan hanya sebagai fitur opsional apabila masih ada kebutuhan eksplisit untuk memicu Android share sheet;
- jangan memasukkannya ke kemampuan otomatis yang diasumsikan dapat diselesaikan model tanpa tindakan pengguna.

---

## 5. tool yang dihapus, digabung, atau diganti

bagian ini membedakan penghapusan fitur dari penggabungan nama tool. tujuannya menghindari tool yang hanya menjadi alias atau wrapper dari fungsi lain.

### 5.1 digabung ke `device.info`

hapus nama tool berikut dari registry setelah implementasinya dipindahkan:

- `device.spec`;
- `battery.info`;
- `display.info`;
- `storage.info`;
- `network.info`;
- `spec.view`.

semua informasi tersebut diakses melalui `device.info` dengan parameter `section`.

`spec.view` merupakan alias yang tidak perlu dipertahankan.

### 5.2 filesystem/editor yang digabung

hapus nama tool berikut setelah fungsinya dipindahkan:

- `file.create` → `file.write` dengan `mode=create`;
- `file.exists` → `file.stat`;
- `file.read_base64` → `file.read` dengan `encoding=base64`;
- `file.write_base64` → `file.write` dengan `encoding=base64`;
- `editor.read_file` → `file.read`;
- `editor.create_file` → `file.write` dengan `mode=create`;
- `editor.edit_file` → `file.patch`.

jangan mempertahankan alias lama hanya untuk membuat daftar tool terlihat kompatibel. bila ada migration period, alias lama harus diberi batas waktu dan tidak boleh muncul bersamaan sebagai tool aktif yang membingungkan model.

### 5.3 tool duplikat atau typo

hapus:

- `spec.view`, alias `device.spec`;
- `screen.capcure`, alias typo dari `screen.capture`.

`screen.capture` sendiri juga dikeluarkan dari rancangan inti, sehingga kedua nama tersebut tidak perlu dipertahankan.

### 5.4 UI control dan aksi yang tidak dapat diverifikasi

hapus dari MCP registry:

- `toast.show`;
- `url.open`;
- `accessibility.status`;
- `accessibility.global_action`;
- `app.open`;
- `settings.app_info`;
- `settings.wifi`;
- `settings.bluetooth`;
- `settings.location`;
- `settings.notifications`;
- `display.brightness_get`;
- `display.brightness_set`.

alasan:

- tool tersebut berorientasi membuka atau mengubah UI/perangkat;
- model tidak mempunyai jaminan dapat melihat layar hasilnya;
- perubahan UI tidak memberi manfaat utama pada fokus filesystem, JSON, terminal, informasi perangkat, dan komunikasi.

penghapusan `settings.*` dari MCP tidak berarti pengguna kehilangan akses ke Settings Android. pengguna tetap dapat membukanya secara manual melalui sistem operasi.

### 5.5 hardware, sensor, dan konektivitas khusus

keluarkan dari tool inti:

- `sensor.list`;
- `sensor.read`;
- `clipboard.read`;
- `clipboard.write`;
- `vibrate`;
- `flashlight.set`;
- `location.get`;
- `wifi.info`;
- `wifi.scan`;
- `bluetooth.info`;
- `bluetooth.paired`;
- `audio.volume_get`;
- `audio.volume_set`.

alasan: fungsi tersebut tidak termasuk kebutuhan utama yang telah ditetapkan. sebagian juga membutuhkan permission khusus atau hasil yang bergantung pada kebijakan Android.

informasi jaringan dasar tetap tersedia melalui `device.info(section=network)` sejauh Android API mengizinkan.

### 5.6 app inspection dan screen capture

hapus dari tool registry:

- `app.list`;
- `app.info`;
- `app.current`;
- `pm.read`;
- `app.uninstall_request`;
- `screen.capture`;
- `screen.capcure`;
- `usage.stats`;
- `logcat.read`.

fitur ini bukan bagian dari jalur inti yang disepakati. `logcat.read` dapat dipertimbangkan lagi sebagai tool diagnosis developer di masa depan, tetapi tidak perlu berada pada registry default.

### 5.7 komunikasi dan data pribadi yang tidak menjadi fokus

keluarkan dari rancangan inti:

- `contacts.read`;
- `contacts.list`;
- `contacts.create`;
- `contacts.update`;
- `contacts.delete`;
- `sms.read`;
- `sms.send`;
- `sms.delete`;
- `sms.mark_read`;
- `calllog.read`;
- `calllog.delete`;
- `phone.dial`;
- `phone.call`;
- `calendar.read`;
- `calendar.create`;
- `calendar.update`;
- `calendar.delete`.

alasan: kemampuan tersebut membutuhkan izin sensitif dan tidak berhubungan langsung dengan sasaran utama refactor. jangan meminta permission kontak, SMS, call log, telepon, atau kalender jika tool yang menggunakannya sudah tidak tersedia.

### 5.8 media dan audio capture

keluarkan dari rancangan inti:

- `media.images`;
- `media.videos`;
- `media.audio`;
- `media.delete`;
- `camera.open`;
- `audio.record_start`;
- `audio.record_stop`.

operasi file umum tetap dapat membaca atau memindahkan file yang memang berada dalam root yang diizinkan. tidak perlu mempertahankan tool MediaStore khusus hanya untuk mengulang kemampuan listing file.

### 5.9 downloader khusus dan notification dismissal

hapus:

- `tiktok.download`;
- `notifications.dismiss`.

`download.url` tetap dipertahankan sebagai downloader URL generik.

### 5.10 tool yang tetap dipertahankan secara opsional

- `share.text`;
- `share.file`.

keduanya tidak termasuk core tool dan tidak boleh otomatis diaktifkan. pertahankan hanya bila masih ada use case yang disetujui.

---

## 6. perubahan arsitektur dan file source

nama file di bawah berdasarkan struktur source yang ada di arsip.

### 6.1 `ToolRegistry.java`

perubahan utama:

1. hapus seluruh definisi tool yang dikeluarkan dari rancangan;
2. daftarkan tool target dengan schema baru;
3. ubah `device.info` agar memiliki parameter `section`;
4. daftarkan `file.stat`, `file.patch`, `terminal.info`, dan `terminal.set_backend`;
5. gabungkan nama alias/duplikat ke satu nama kanonis;
6. ubah `notifications.read` menjadi `notification.read`;
7. hilangkan kondisi yang menyembunyikan `terminal.exec` hanya karena Termux tidak terpasang;
8. pastikan schema dan handler mempunyai nama parameter yang sama;
9. gunakan deskripsi yang menjelaskan hasil dan batasan aktual, bukan deskripsi generik.

### 6.2 `PhoneMcpService.java`

perubahan utama:

- sederhanakan dispatch `execute()` sesuai tool target;
- implementasikan route `device.info(section)`;
- implementasikan `file.stat` dan `file.patch`;
- dispatch `terminal.exec` ke backend global yang sedang dipilih;
- implementasikan `terminal.info` dan `terminal.set_backend`;
- ubah handler read notification ke nama baru `notification.read`;
- hapus handler yang hanya digunakan tool yang sudah dihapus;
- hapus `screenCapture`, `tiktokDownload`, `accessibility`, dan handler UI/hardware yang tidak dipakai;
- pastikan semua error tool mengembalikan `isError=true` dan pesan yang dapat ditindaklanjuti.

### 6.3 `FileOps.java`

pertahankan fungsi resolve root, read/write, list, copy, move, delete, ZIP, dan utilitas path yang masih dipakai.

perubahan:

- tambahkan helper `stat`;
- tambahkan patch engine atau kelas helper khusus `FilePatchManager`;
- dukung UTF-8/Base64 pada read/write yang sama;
- tambah proteksi copy/move ke descendant;
- tambah batas penghapusan recursive;
- tambah atomic write untuk write dan patch;
- tambah validasi ukuran dan jumlah entry saat ekstraksi ZIP;
- jangan membiarkan patch menulis file bila salah satu operasi gagal.

lebih baik menempatkan patch engine dalam file/kelas baru daripada membuat `FileOps.java` menjadi satu kelas terlalu besar. bila dibuat `FilePatchManager.java`, jaga agar validasi path tetap menggunakan resolver root yang sama.

### 6.4 terminal manager

`TerminalExecManager.java` saat ini khusus Termux. pisahkan tanggung jawab agar satu entry point dapat memilih backend.

struktur yang disarankan:

- `TerminalBackend`: interface/kontrak eksekusi;
- `AndroidShellBackend`: implementasi `/system/bin/sh`;
- `TermuxShellBackend`: implementasi `RUN_COMMAND` yang memanfaatkan mekanisme saat ini;
- `TerminalManager`: membaca setting aktif, memilih backend, dan menormalkan response;
- `TerminalSettings`: menyimpan backend aktif secara persisten.

nama kelas ini adalah rancangan; boleh disesuaikan dengan struktur proyek, selama pemisahan backend dan setting global tetap jelas.

semua backend harus menghasilkan format response seragam. perbedaan spesifik backend ditempatkan pada `backend`, `shell`, dan metadata capability.

### 6.5 `TerminalLogDb.java`, `TerminalLogActivity.java`, dan `TerminalResultService.java`

pertahankan fitur log. modifikasi skema database dan UI agar mencatat backend serta shell aktual. callback `TerminalResultService` tetap dibutuhkan untuk menerima hasil Termux, tetapi Android backend tidak boleh bergantung pada service callback tersebut.

perhatikan migration database lokal bila skema tabel berubah. jangan menghapus riwayat lama secara diam-diam.

### 6.6 `ToolSettings.java`

perubahan:

- hapus kategori tool yang sudah tidak ada;
- pertahankan kompatibilitas pembacaan preference lama bila aman;
- jangan mengaktifkan tool baru secara tidak sengaja akibat key preference lama;
- tambahkan penyimpanan backend terminal melalui preference terpisah atau kelas setting khusus;
- pastikan perubahan backend dari UI dan MCP menggunakan nilai yang sama.

### 6.7 `MainActivity.java` dan `main.xml`

UI tetap berfungsi sebagai panel konfigurasi lokal. tidak perlu menjadikan UI sebagai remote-control untuk model.

perubahan yang dibutuhkan:

- tambahkan pengaturan backend terminal: Android shell atau Termux;
- tampilkan status backend aktif;
- tampilkan alasan Termux tidak tersedia bila backend Termux belum dapat dipakai;
- jangan sembunyikan seluruh fitur terminal ketika Termux tidak terpasang, karena Android shell selalu menjadi backend dasar;
- rapikan daftar tool berdasarkan kategori inti yang tersisa;
- ubah penjelasan tool agar menekankan filesystem, JSON, device info, terminal, notifikasi, dan TTS;
- tinjau ulang tombol `Enable All`.

`Enable All` sebaiknya diganti dengan preset yang jelas, misalnya:

- **read-only core**: device info, time, file read/list/stat, terminal info, server status;
- **core write**: file write/append/patch, mkdir/copy/move, ZIP;
- **communication**: notification send/read dan TTS;
- **terminal execution**: terminal.exec.

preset bukan pengganti izin per tool. setiap tool write tetap dapat diaktifkan/dinonaktifkan secara individual.

### 6.8 `AndroidManifest.xml` dan permission

hapus komponen yang tidak lagi digunakan:

- deklarasi `PhoneAccessibilityService`;
- deklarasi accessibility intent filter dan metadata;
- metadata/resource accessibility service;
- deklarasi `ScreenCaptureActivity` bila screen capture dihapus;
- permission foreground service khusus microphone, camera, location, connected device, atau media projection bila tidak ada lagi fitur yang menggunakannya.

review dan hapus permission yang hanya dipakai fitur yang dihapus, termasuk bila memang tidak ada pemakaian lain:

- contacts: `READ_CONTACTS`, `WRITE_CONTACTS`;
- SMS: `READ_SMS`, `RECEIVE_SMS`, `SEND_SMS`, `WRITE_SMS`;
- call log dan telepon: `READ_CALL_LOG`, `WRITE_CALL_LOG`, `READ_PHONE_STATE`, `READ_PHONE_NUMBERS`, `READ_BASIC_PHONE_STATE`, `CALL_PHONE`;
- calendar: `READ_CALENDAR`, `WRITE_CALENDAR`;
- camera/microphone: `CAMERA`, `RECORD_AUDIO`;
- location: `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`;
- Bluetooth: `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`;
- Wi-Fi scan: `CHANGE_WIFI_STATE`, `NEARBY_WIFI_DEVICES` bila sudah tidak dibutuhkan;
- app inventory/usage: `QUERY_ALL_PACKAGES`, `PACKAGE_USAGE_STATS`;
- UI/system control: `SYSTEM_ALERT_WINDOW`, `WRITE_SETTINGS`, `REQUEST_INSTALL_PACKAGES`, `REQUEST_DELETE_PACKAGES`, `ACCESS_NOTIFICATION_POLICY` bila tidak digunakan fitur lain.

jangan menghapus permission hanya berdasarkan nama. periksa semua pemakaian di source terlebih dahulu. permission yang mungkin tetap dibutuhkan:

- `INTERNET` untuk MCP server, tunnel, download, dan kebutuhan jaringan;
- `ACCESS_NETWORK_STATE` untuk informasi network;
- `WAKE_LOCK` jika masih dibutuhkan oleh lifecycle service;
- `RECEIVE_BOOT_COMPLETED` untuk boot recovery bila tetap digunakan;
- `POST_NOTIFICATIONS` untuk notifikasi pada versi Android yang mewajibkannya;
- `MANAGE_EXTERNAL_STORAGE` bila shared filesystem root memang membutuhkan akses tersebut;
- permission Termux `com.termux.permission.RUN_COMMAND` untuk backend Termux;
- `FOREGROUND_SERVICE` dan tipe service yang memang masih digunakan.

permintaan permission runtime sebaiknya dilakukan sesuai fitur yang diaktifkan, bukan meminta seluruh permission proyek dalam satu kali tindakan.

### 6.9 file/class yang dapat dihapus setelah dependency bersih

kandidat penghapusan setelah seluruh referensi diputus:

- `PhoneAccessibilityService.java`;
- `res/xml/accessibility_service_config.xml`;
- `ScreenCaptureActivity.java`;
- `ScreenCaptureManager.java`;
- method screen capture di `PhoneMcpService.java`;
- handler TikTok downloader dan helper khusus yang hanya dipakai olehnya;
- handler aplikasi, kontak, SMS, call log, telepon, kalender, media, kamera, audio recording, location, Bluetooth, Wi-Fi scan, clipboard, accessibility, brightness, dan UI action yang tidak lagi diregistrasikan;
- resource/icon/layout khusus fitur yang sudah tidak ada bila tidak digunakan komponen lain.

jangan menghapus file hanya karena tool registry-nya dihapus. lakukan pencarian seluruh referensi class, manifest, layout ID, permission, receiver, dan resource sebelum menghapus.

---

## 7. keamanan dan konsistensi

### 7.1 file path

- semua path filesystem harus di-resolve ke canonical path;
- path traversal ke luar root wajib ditolak;
- operasi delete/copy/move harus menolak target root itu sendiri;
- copy/move direktori ke dalam descendant harus ditolak;
- output error harus membedakan path tidak ada, akses ditolak, file bukan direktori, dan kegagalan IO.

### 7.2 file patch

- gunakan expected text/hash sebagai guard untuk patch penting;
- batalkan seluruh patch bila match count tidak sesuai;
- tampilkan diff pada dry-run;
- backup sebelum commit bila backup diaktifkan;
- lakukan commit setelah seluruh operasi selesai divalidasi;
- batasi ukuran file, panjang patch, jumlah operasi, dan ukuran diff;
- jangan menjalankan regex tanpa batas yang dapat menyebabkan pemrosesan berlebihan.

### 7.3 terminal

`terminal.exec` adalah tool berisiko tinggi karena dapat menjalankan command dengan seluruh akses yang diberikan kepada proses backend. karena itu:

- nonaktif secara default atau hanya diaktifkan melalui konfigurasi eksplisit;
- tampilkan backend aktif dengan jelas di UI;
- catat semua command dan hasilnya;
- jangan menyembunyikan backend aktual pada response;
- jangan melakukan fallback otomatis;
- jangan menganggap Android shell berjalan sebagai root;
- jangan menganggap Termux dapat membaca private directory aplikasi Android;
- jangan mengulang command yang timeout tanpa menganalisis kemungkinan command pertama masih berjalan.

### 7.4 notifikasi dan TTS

- jelaskan status permission Notification Listener dan POST_NOTIFICATIONS;
- `notification.read` tidak boleh menyiratkan akses ke notifikasi historis sebelum listener aktif;
- `tts.speak` harus mengembalikan status yang jujur bila language/engine tidak tersedia;
- `tts.stop` harus menghentikan antrean yang dikelola aplikasi.

### 7.5 tool enablement

hindari satu tombol yang mengaktifkan seluruh tool berisiko tanpa pemisahan. gunakan kategori/preset yang jelas dan pertahankan toggle per tool.

---

## 8. migrasi dan kompatibilitas

perubahan nama tool dapat membuat konfigurasi client MCP lama tidak lagi cocok. strategi yang disarankan:

1. registry baru hanya menampilkan nama kanonis;
2. jangan tampilkan alias lama dan nama baru bersamaan;
3. bila perlu migration sementara, alias lama hanya diterima di handler internal dan tidak diekspos pada `tools/list`;
4. migration harus mempunyai batas versi yang jelas;
5. preference tool lama yang sudah tidak mempunyai definisi harus diabaikan, bukan membuat tool baru aktif;
6. perubahan database terminal log harus memakai migration version yang menjaga riwayat lama;
7. backend terminal aktif disimpan dalam preference tersendiri dengan nilai default `android` bila belum pernah dikonfigurasi.

nama yang berubah:

| nama lama | nama baru |
|---|---|
| `notifications.read` | `notification.read` |
| `device.spec` | `device.info` dengan `section` |
| `battery.info` | `device.info` dengan `section=battery` |
| `display.info` | `device.info` dengan `section=display` |
| `storage.info` | `device.info` dengan `section=storage` |
| `network.info` | `device.info` dengan `section=network` |
| `file.exists` | `file.stat` |
| `editor.edit_file` | `file.patch` |

nama lama yang digabung ke tool existing:

| nama lama | pengganti |
|---|---|
| `file.create` | `file.write(mode=create)` |
| `file.read_base64` | `file.read(encoding=base64)` |
| `file.write_base64` | `file.write(encoding=base64)` |
| `editor.read_file` | `file.read` |
| `editor.create_file` | `file.write(mode=create)` |

---

## 9. target daftar tool setelah refactor

### core

| tool | fungsi |
|---|---|
| `device.info` | informasi perangkat melalui `section` |
| `time.now` | waktu perangkat |
| `file.read` | membaca file UTF-8/Base64 |
| `file.write` | membuat/menimpa file UTF-8/Base64 |
| `file.append` | menambahkan isi ke file |
| `file.patch` | patch teks dengan guard, backup, dry-run, dan diff |
| `file.list` | daftar isi direktori |
| `file.stat` | metadata/keberadaan file |
| `file.mkdir` | membuat direktori |
| `file.copy` | menyalin file/direktori |
| `file.move` | memindahkan file/direktori |
| `file.delete` | menghapus file/direktori dengan guard recursive |
| `zip.create` | membuat arsip ZIP |
| `zip.extract` | ekstraksi ZIP dengan proteksi path dan ukuran |
| `json.batch` | manipulasi struktur JSON |
| `terminal.info` | status backend terminal |
| `terminal.set_backend` | mengganti backend global |
| `terminal.exec` | menjalankan command melalui backend aktif |
| `notification.send` | mengirim notifikasi ke pengguna |
| `notification.read` | membaca notifikasi melalui Notification Listener |
| `tts.speak` | menyampaikan teks melalui TTS |
| `tts.stop` | menghentikan TTS |
| `download.url` | download URL umum |

### administrasi/diagnostik

| tool | fungsi |
|---|---|
| `server.status` | status MCP server dan tunnel |
| `mcp.tools_enabled` | daftar tool yang aktif |

### opsional

| tool | catatan |
|---|---|
| `share.text` | hanya bila Android share sheet masih dibutuhkan |
| `share.file` | hanya bila Android share sheet masih dibutuhkan |

jumlah akhir tidak perlu dipaksakan ke angka tertentu. yang penting registry hanya berisi tool yang benar-benar didukung, mempunyai tujuan jelas, dan tidak menggandakan kemampuan tool lain.

---

## 10. checklist implementasi dan acceptance criteria

### 10.1 registry dan MCP

- [ ] tool registry hanya memuat nama target yang disetujui.
- [ ] `tools/list` tidak menampilkan alias lama.
- [ ] schema setiap tool cocok dengan parameter yang dibaca handler.
- [ ] `device.info` menerima seluruh section yang ditetapkan.
- [ ] section tidak dikenal menghasilkan error validasi.
- [ ] `terminal.exec` tetap terdaftar walaupun Termux tidak terpasang.
- [ ] response setiap tool mempunyai bentuk konsisten dan error ditandai `isError=true`.

### 10.2 filesystem

- [ ] root `shared` dapat digunakan sesuai permission yang tersedia.
- [ ] root `internal` hanya mengakses app-private files directory.
- [ ] path traversal ditolak.
- [ ] Base64 berada dalam parameter `encoding`, bukan tool terpisah.
- [ ] `file.write(mode=create)` gagal bila file sudah ada.
- [ ] `file.append` tidak menimpa isi file sebelumnya.
- [ ] `file.stat` membedakan missing file dari permission error.
- [ ] `file.patch` mendukung seluruh operasi yang ditetapkan.
- [ ] `file.patch` mendukung dry-run dan menghasilkan diff.
- [ ] `file.patch` mendukung backup dan expected guard.
- [ ] patch multi-operation tidak menyimpan perubahan parsial bila salah satu operasi gagal.
- [ ] `file.delete` tidak menghapus direktori non-empty tanpa `recursive=true`.
- [ ] copy/move menolak operasi ke descendant sendiri.
- [ ] ZIP extract menolak zip-slip dan arsip yang melewati batas ekstraksi.

### 10.3 JSON

- [ ] `json.batch` mempertahankan semua operasi yang sudah tersedia.
- [ ] tipe JSON tidak berubah menjadi string secara tidak sengaja.
- [ ] dry-run tidak menulis file.
- [ ] error per file/action dilaporkan secara akurat.
- [ ] dokumentasi tidak mengklaim transaksi global lintas file.

### 10.4 terminal

- [ ] Android backend berjalan tanpa Termux.
- [ ] Android backend menggunakan `/system/bin/sh`.
- [ ] Termux backend tetap menggunakan mekanisme RUN_COMMAND.
- [ ] backend aktif disimpan secara global dan persisten.
- [ ] UI dan MCP membaca/menulis setting backend yang sama.
- [ ] `terminal.exec` tidak menerima backend sebagai parameter per-call.
- [ ] `terminal.info` melaporkan backend dan availability secara akurat.
- [ ] `terminal.set_backend` memvalidasi Termux dan permission sebelum mengubah setting.
- [ ] setiap response mencantumkan backend dan shell aktual.
- [ ] kegagalan command tidak memicu fallback otomatis.
- [ ] timeout tidak menyebabkan command otomatis dijalankan ulang.
- [ ] terminal log mencatat backend dan shell aktual.
- [ ] Android shell tidak diklaim sebagai root.
- [ ] keterbatasan penghentian proses Termux setelah timeout dijelaskan.

### 10.5 komunikasi

- [ ] `notification.send` tetap bekerja.
- [ ] `notification.read` melaporkan status Notification Listener.
- [ ] nama lama `notifications.read` tidak muncul lagi di tools/list.
- [ ] `tts.speak` dapat menyampaikan teks dengan engine yang tersedia.
- [ ] `tts.stop` menghentikan playback/queue.
- [ ] status engine/language TTS dilaporkan secara jujur.

### 10.6 cleanup dan permission

- [ ] tidak ada referensi tersisa ke PhoneAccessibilityService bila sudah dihapus.
- [ ] tidak ada deklarasi accessibility service di manifest.
- [ ] tidak ada referensi screen capture bila fiturnya sudah dihapus.
- [ ] permission yang hanya digunakan fitur terhapus ikut dibersihkan.
- [ ] permission yang masih digunakan fitur inti tidak ikut terhapus.
- [ ] request permission dilakukan berdasarkan kebutuhan fitur.
- [ ] tombol Enable All tidak lagi mengaktifkan seluruh operasi berisiko tanpa pemisahan.
- [ ] seluruh layout ID, resource, receiver, service, dan manifest telah dicek dari referensi mati.

### 10.7 pengujian skenario

- [ ] instalasi baru tanpa Termux: `terminal.info` menunjukkan Android backend tersedia dan `terminal.exec` dapat menjalankan command dasar.
- [ ] instalasi dengan Termux tetapi tanpa izin RUN_COMMAND: Android backend tetap berjalan; pemilihan Termux ditolak dengan alasan jelas.
- [ ] instalasi dengan Termux dan permission: backend dapat dipilih dan command berjalan melalui bash Termux.
- [ ] command gagal karena executable tidak ada: backend tidak berubah.
- [ ] command gagal karena permission denied: backend tidak berubah.
- [ ] command timeout: response menyebut backend dan status timeout; command tidak dijalankan ulang otomatis.
- [ ] file operasi pada shared dan internal root berjalan sesuai batas masing-masing.
- [ ] file.patch dry-run menghasilkan diff tanpa mengubah file.
- [ ] file.patch dengan expected mismatch gagal tanpa mengubah file.
- [ ] file.patch multi-action berhasil menyimpan seluruh perubahan sebagai satu hasil akhir.
- [ ] file.patch gagal di action kedua: tidak ada action pertama yang tersimpan.
- [ ] file.delete direktori non-empty tanpa recursive ditolak.
- [ ] ZIP traversal ditolak.

---

## 11. urutan pengerjaan yang disarankan

1. **registry dan desain kontrak** — tetapkan nama tool, schema, parameter, dan output sebelum menghapus handler.
2. **device info** — gabungkan spesifikasi, baterai, display, storage, dan network ke `device.info`.
3. **filesystem** — konsolidasikan Base64/editor/create, tambahkan `file.stat`, kemudian implementasikan `file.patch`.
4. **terminal abstraction** — pisahkan AndroidShellBackend dan TermuxShellBackend, setting global, `terminal.info`, `terminal.set_backend`, dan response seragam.
5. **notifications dan TTS** — rapikan nama read notification dan perbaiki TTS engine.
6. **UI konfigurasi** — tambahkan backend selector, preset tool, dan status availability.
7. **hapus fitur yang tidak lagi didukung** — cabut registry, handler, class, service, resource, dan permission setelah dependency check.
8. **migration dan regression test** — preference, database terminal log, schema MCP, filesystem, dan skenario tanpa Termux.

urutan ini meminimalkan risiko menghapus kode sebelum pengganti dan kontrak tool baru tersedia.

---

## 12. hal yang tidak boleh berubah tanpa alasan

- MCP server tetap menjadi jalur komunikasi utama.
- mekanisme autentikasi/token tetap dipertahankan.
- Cloudflare Tunnel tetap dipertahankan selama masih menjadi opsi koneksi eksternal yang digunakan proyek.
- watchdog/service recovery dan boot receiver tetap dipertahankan selama masih menjadi bagian dari kebutuhan server yang berjalan di background.
- crash diagnostics tetap dipertahankan.
- terminal history/log tetap dipertahankan.
- root filesystem tidak boleh diperluas secara diam-diam ke seluruh Android filesystem.
- tidak boleh ada automatic backend fallback.
- Termux tidak boleh menjadi dependency wajib.
- tool yang hanya mengendalikan UI aplikasi lain tidak boleh ditambahkan kembali tanpa kebutuhan yang jelas dan output yang dapat diverifikasi.

---

## 13. definisi selesai

refactor dianggap selesai ketika registry MCP sudah ramping dan konsisten, seluruh tool inti bekerja sesuai kontraknya, Android shell dapat digunakan tanpa Termux, Termux dapat dipilih sebagai backend alternatif, filesystem memiliki operasi patch yang aman, tool komunikasi dapat menyampaikan informasi kepada pengguna, serta kode dan permission yang tidak lagi relevan sudah dibersihkan.

keberhasilan bukan diukur dari banyaknya tool yang tersedia, melainkan dari seberapa jauh setiap tool yang tersisa benar-benar dapat menyelesaikan tugas yang menjadi tanggung jawabnya.
