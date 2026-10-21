# Fernix — Architecture Specification

Architecture and implementation guide for Fernix, a lightweight, native, 100% open-source, fully offline Android music player.

Design priorities:

- Strict **ID3v2.4** and **Ogg Vorbis comment** compliance, including multi-value tags.
- Filtering on **any** tag present in the user's library, not just a fixed set.
- Zero dependencies on proprietary tools or Google Play Services; F-Droid compliant.
- Small APK and low resource use — prefer platform APIs over libraries.

---

## 1. Platform & Targets

| Item | Value |
|---|---|
| Application ID / base package | `io.github.libregeek.fernix` |
| `minSdk` | 29 (Android 10) |
| `targetSdk` / `compileSdk` | 37 / 37 (Android 17) |
| Language | Kotlin (app), C++ (TagLib JNI bridge) |
| Toolchain | Gradle 9.8.1, AGP 9.4.1, Kotlin 2.4.20, KSP 2.3.12, JDK 17 bytecode target |
| NDK / CMake | 28.2.13676358 / 3.31.6 (SDK packages) |
| Native ABIs | `arm64-v8a`, `armeabi-v7a`, `x86_64` (emulator) |
| Distribution | F-Droid |
| License | GPL-3.0-or-later — all dependencies must be GPLv3-compatible |

### Audio formats

MP3, Ogg Vorbis, Opus, FLAC, WAV. All are decoded by the platform `MediaCodec` on Android 10+, so no FFmpeg or other decoder extension is needed.

---

## 2. Technical Stack (approved dependencies)

Exact versions are pinned in `gradle/libs.versions.toml` (libraries, plugins), `app/build.gradle.kts` (SDK, NDK, CMake) and the TagLib submodule commit. Version changes need the user's approval, like new libraries.

| Area | Library | License | Purpose / notes |
|---|---|---|---|
| Audio engine | `androidx.media3:media3-exoplayer` | Apache 2.0 | Core playback, gapless, audio focus. Core module only. |
| Session & service | `androidx.media3:media3-session` | Apache 2.0 | Background playback, notification, lock-screen controls. |
| Metadata parser | TagLib 2.x (C++ via NDK, built from source) | LGPL 2.1 / MPL 1.1 | Strict ID3v2.4, Vorbis comments, multi-value tags, custom `TXXX` frames. Linked statically into the app's single JNI library `libfernix.so` with `c++_static` (no `libc++_shared.so`). Static linking is LGPL-compliant because the complete app source is published under GPLv3. Only the formats Fernix needs are compiled in (§7). |
| TagLib build dep | utfcpp (header-only) | BSL 1.0 | Required by TagLib 2.x; comes as TagLib's own pinned submodule (`external/taglib/3rdparty/utfcpp`). Compiled in, no runtime component. |
| Compression | zlib (NDK system `libz`) | zlib | Used by TagLib for compressed ID3v2 frames. Part of the platform; nothing bundled. |
| Database | AndroidX Room (runtime, ktx, compiler via KSP) | Apache 2.0 | Hybrid fixed-column + tag-table storage (§5). |
| Image loading | Coil 3 (`coil-compose` only) | Apache 2.0 | Embedded/local album art. No `coil-network-*` module. |
| UI | Jetpack Compose (BOM) + Material 3 | Apache 2.0 | Declarative UI. No `material-icons-extended`; needed icons are copied in as vector drawables. |
| Basics | `kotlinx-coroutines-android`, `androidx.core:core-ktx`, `androidx.activity:activity-compose`, `androidx.lifecycle` (runtime/viewmodel compose) | Apache 2.0 | |
| Build only | Kotlin, Android Gradle Plugin, Compose compiler plugin, KSP | Apache 2.0 | Not shipped in the APK. |
| Tests only | JUnit 4, AndroidX Test (runner, ext-junit), Room testing | EPL 1.0 / Apache 2.0 | Not shipped in the APK. |
| File indexing | AOSP `MediaStore` (platform) | — | Scoped-storage-compliant discovery via `ContentResolver`. |
| Equalizer | `android.media.audiofx.Equalizer` (platform) | — | Attached to ExoPlayer's audio session id. |

### Deliberately not used

| Instead of | We use | Why |
|---|---|---|
| Hilt / Koin / Dagger | Manual DI via an `AppContainer` owned by the `Application` | Smaller APK, faster builds; the object graph is small. |
| navigation-compose | Small hand-rolled back stack | Only six screens. |
| DataStore | `SharedPreferences` | A handful of simple settings. |
| Media3 HLS/DASH/RTSP/SmoothStreaming, Cast, FFmpeg, `datasource-okhttp` | — | Offline, local files only; platform decoders cover all formats. |
| `MediaMetadataRetriever` for tags | TagLib | It drops custom fields and mangles multi-value ID3v2.4 tags. |

---

## 3. Permissions

| Permission | Scope | Why |
|---|---|---|
| `READ_MEDIA_AUDIO` | API 33+ | Read the audio library. |
| `READ_EXTERNAL_STORAGE` | `maxSdkVersion="32"` | Read the audio library on Android 10–12. |
| `FOREGROUND_SERVICE` | all | Keep playing in the background. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | API 34+ | Required service-type permission for media playback. |
| `POST_NOTIFICATIONS` | API 33+ | Show the playback notification. |
| `WAKE_LOCK` | all | Keep the CPU awake during playback with the screen off (`setWakeMode(C.WAKE_MODE_LOCAL)`; no Wi-Fi lock). |

Added automatically by `androidx.core` (approved): `io.github.libregeek.fernix.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — a signature-level permission the app defines for itself to protect its own non-exported broadcast receivers. Not user-visible; grants no access.

The playback service is declared with `android:foregroundServiceType="mediaPlayback"`.

**`android.permission.INTERNET` is never declared** — network traffic is structurally prevented at the OS level.

---

## 4. Code Organisation

Single Gradle module `app`, organised by package:

```
io.github.libregeek.fernix
├── FernixApp.kt          Application; owns AppContainer (manual DI)
├── data/
│   ├── db/               Room database, entities, DAOs
│   └── scan/             MediaStore discovery + incremental ingestion
├── tags/                 Kotlin side of the TagLib JNI bridge
├── playback/             MediaSessionService, ExoPlayer setup, queue
└── ui/                   Compose screens, ViewModels, theme
app/src/main/cpp/         JNI glue + CMakeLists.txt
external/taglib/          TagLib source (git submodule, pinned release tag; includes utfcpp as its own submodule)
```

UI state: coroutines + `StateFlow`, one ViewModel per screen.

---

## 5. Database (Room)

### Approach: hybrid fixed columns + tag table

Pure Entity-Attribute-Value (EAV) storage turns every list screen into a pivot query. Instead, six frequently displayed/sorted tags are **fixed columns** on `tracks`, and **all** tags (including those six) are also stored one-value-per-row in the EAV table `track_tags` for arbitrary filtering.

Fixed fields (TagLib `PropertyMap` keys): `TITLE`, `ALBUM`, `DATE`, `ARTIST`, `COMPOSER`, `LYRICIST`.

### Multi-value encoding

All six fixed fields are multi-value (ID3v2.4 null-separated text frames; repeated Vorbis comment keys). The column stores the full list joined with the unit separator **`U+001F`** — never `\0`, which truncates in SQLite and JNI string APIs. A shared `TagValues` codec does join/split; the UI renders lists as `"A; B"`.

### Tables

**`tracks`**

| Column | Notes |
|---|---|
| `id` | PK, autogenerate |
| `mediaStoreId` | MediaStore `_ID`; unique index |
| `contentUri` | e.g. `content://media/external/audio/media/12345` |
| `relativePath`, `displayName` | MediaStore `RELATIVE_PATH` (e.g. `Music/Artist/Album/`) and file name; drive the Folders screen |
| `durationMs`, `dateModified`, `sizeBytes` | From MediaStore; used for change detection |
| `title`, `album`, `date`, `artist`, `composer`, `lyricist` | Fixed multi-value fields (`U+001F`-joined) |
| `trackNumber`, `discNumber` | Parsed from `TRACKNUMBER` / `DISCNUMBER` |
| `year` | INTEGER, nullable; parsed from the first `DATE` value |
| `albumId` | FK → `albums.id`, nullable |

**`albums`** — `(id, album, year)` with a **unique index on `(album, year)`**: an album is identified by album name + year.

- `album` is the full stored `ALBUM` value.
- Tracks without a year form an album with `year = NULL`. SQLite `UNIQUE` permits duplicate NULLs, so album lookup/upsert must treat NULL as a single value in code.
- Tracks with an empty/missing album get `albumId = NULL`.
- Albums left without tracks are deleted after each scan.

**`track_tags`** — `(tagId, trackId, tagKey, tagValue)`

- One row **per value**; `tagKey` normalised to uppercase.
- Includes the six fixed fields, so all filtering — including matching a single value of a multi-value field — uses one code path.
- Indices: `(tagKey, tagValue, trackId)` for filtering, `(trackId)` for cascades. FK to `tracks` with `ON DELETE CASCADE`.

**`playlists`** — `(id, name, createdAt, updatedAt)`

**`playlist_entries`** — `(playlistId, position, trackId)`, PK `(playlistId, position)`; cascading deletes from both `playlists` and `tracks`.

### Filtering

Arbitrary filters are built with `@RawQuery` using `EXISTS` / `INTERSECT` subqueries over `track_tags`. Filters combine with AND. Values are always bound as arguments, never concatenated into SQL.

---

## 6. Storage Access & Scanning

Direct file paths (`/storage/emulated/0/...`) are never used. Content URIs from MediaStore are passed to ExoPlayer, TagLib (via file descriptor) and Coil.

1. **Discovery** — query `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` for `_ID`, `SIZE`, `DATE_MODIFIED`, `DURATION`, `RELATIVE_PATH`, `DISPLAY_NAME`.
2. **Diff** — compare with `tracks` by `mediaStoreId`; only new/changed files are re-parsed; vanished ones are deleted.
3. **Tag ingestion** — `ContentResolver.openFileDescriptor(uri, "r")` → fd passed to native code → TagLib reads through a file-descriptor stream (`TagLib::FileStream(fd, true)` or a custom `IOStream`) → returns the `PropertyMap` as `Map<String, List<String>>`.
4. **Persist** — each batch is written in a single Room transaction (`tracks`, `albums`, `track_tags`).

Scanning runs on `Dispatchers.IO` and never blocks the UI.

---

## 7. Metadata Extraction (TagLib)

TagLib's `PropertyMap` interface normalises all formats to key → list-of-values.

- **ID3v2.4:** UTF-8 strings, unsynchronisation, null-byte multi-value delimiters (multiple artists/genres).
- **Vorbis comments:** duplicate keys preserved (`ARTIST=Artist A`, `ARTIST=Artist B`), custom uppercase tags.
- **Arbitrary tags:** `MUSICBRAINZ_TRACKID`, `RELEASETYPE`, `BPM`, `ORGANIZATION`, custom `TXXX` frames, etc.

### Native build

- TagLib is a git submodule pinned to a release tag; utfcpp comes in as TagLib's own submodule at the commit TagLib pins (`git submodule update --init --recursive`). Built from `app/src/main/cpp/CMakeLists.txt` via `add_subdirectory`.
- TagLib is built as a static library (`BUILD_SHARED_LIBS=OFF`) and linked into `libfernix.so`; STL is `c++_static`.
- Compiled-in TagLib modules: MPEG (MP3), Vorbis/Opus/FLAC/Ogg (`WITH_VORBIS`), RIFF/WAV (`WITH_RIFF`), APE (`WITH_APE`, needed for APEv2 tags in MP3 files), zlib. Disabled: ASF, DSF, Matroska, MOD, MP4, Shorten, TrueAudio, C bindings.
- Files are opened from Kotlin with `ParcelFileDescriptor.detachFd()`; ownership of the fd passes to native code, where TagLib's `FileStream(fd, true)` closes it.
- NDK and CMake versions are pinned in `app/build.gradle.kts` (`ndkVersion`, `externalNativeBuild.cmake.version`); the SDK-provided CMake is used, not the system one.
- The JNI layer returns a flat `String[]` of alternating key/value pairs (a multi-value tag repeats its key once per value); Kotlin groups it into `Map<String, List<String>>`. No exceptions cross the boundary: failures return null and are logged.

---

## 8. Playback & Background Service

- `MediaSessionService` hosts a single ExoPlayer instance and runs independently of the UI lifecycle; the UI connects via `MediaController`.
- Only core and session Media3 modules are included (see §2).
- Gapless playback uses ExoPlayer's built-in support (MP3 LAME/iTunes gapless info, Ogg, FLAC).
- Equalizer: `android.media.audiofx.Equalizer` on ExoPlayer's audio session id.

---

## 9. User Interface

Material 3 defaults, dynamic color on Android 12+, light/dark themes. Bottom navigation for the library screens; Now Playing and Queue are reached from a persistent mini-player bar.

1. **Now Playing** — album art, title/artist/album, seek bar, play/pause/next/previous, shuffle/repeat, equalizer entry.
2. **Folders** — browse by `relativePath` hierarchy (built from the DB, not the filesystem); play/enqueue a folder or a track.
3. **Search & Filter** — free-text search over the fixed fields plus arbitrary tag filters (pick any tag key present in the library, then one or more values; filters combine with AND). Results can be played or enqueued.
4. **Queue** — current play queue from the `MediaController`; drag to reorder, remove, jump to item.
5. **Albums** — albums (name + year), sorted by album then year; album detail lists tracks by disc/track number.
6. **Playlists** — create, rename, delete; add from any track list; reorder/remove entries.

---

## 10. FOSS, F-Droid & Reproducible Builds

- **Zero proprietary SDKs:** no Google Play Services, Firebase, Crashlytics, analytics or ads — not even transitively.
- **De-Googled ROM compatibility:** functions identically on LineageOS, GrapheneOS and CalyxOS.
- **Air-gapped operation:** no `INTERNET` permission (§3).
- Gradle Kotlin DSL + version catalog (`gradle/libs.versions.toml`); every version pinned, no dynamic (`+`) versions.
- Gradle wrapper includes `distributionSha256Sum`.
- `android { dependenciesInfo { includeInApk = false; includeInBundle = false } }` (F-Droid requirement).
- R8 minification enabled for release builds.
- No build-time network access other than dependency resolution from Google Maven / Maven Central.
