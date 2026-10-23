# Fernix — Architecture Specification

Architecture and implementation guide for Fernix, a lightweight, native, 100% open-source, fully offline Android music player.

Design priorities:

- Strict **ID3v2.4** and **Ogg Vorbis comment** compliance, including multi-value tags.
- Filtering on **any** tag present in the user's library, not just a fixed set.
- Zero dependencies on proprietary tools or Google Play Services; F-Droid compliant.
- Small APK and low resource use — prefer platform APIs over libraries.
- **Sound quality comes first:** performance and size work must never reduce audio quality (§8).

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
| Image loading | Coil 3 (`coil-compose` only) | Apache 2.0 | Embedded album art and role pictures (§7). No `coil-network-*` module. |
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
| navigation-compose | Small hand-rolled back stack | Only eight screens. |
| androidx.paging | Hand-written keyset paging (§5) | A few `LIMIT` queries; no extra library. |
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

`android.permission.ACCESS_NETWORK_STATE`, which `media3-exoplayer` adds through manifest merging (it only tunes buffering for streaming), is removed with `tools:node="remove"`; Fernix plays local files only. After any dependency change, list the merged permissions and make sure only those above remain.

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

### Scale target

Expected library: ~16K tracks today, ~25K within 2–3 years. The schema and every query must stay usable at **1,000,000 tracks**. This drives the rules below:

- **No string is stored once per track-tag row.** Tag keys and values live in dictionary tables; the high-volume row table `track_tags` holds integers only.
- **Every list is paged** with hand-written keyset paging (`WHERE (sortKey, id) > (?, ?) ORDER BY sortKey, id LIMIT 200`; row values need SQLite 3.15, Android 10 ships 3.22). No screen loads a whole table into memory. (`androidx.paging` is not used — it is not an approved dependency.)
- **Every filter, sort and lookup is served by an index**; filters are evaluated from the index side (`t.id IN (…)`), never as a correlated subquery per track row.
- **Free-text search uses FTS4** (part of the platform SQLite; Room `@Fts4`, no extra library), not `LIKE '%…%'`.
- Counts shown in lists are stored in small summary tables (`key_values`, `people`), recomputed only for the rows a scan batch or a split touched.
- Rough size: ~2 KB per track (≈50 MB at 25K, ≈2 GB at 1M), dominated by `track_tags` and the FTS index.

### Approach: hybrid fixed columns + normalised tag tables

Pure Entity-Attribute-Value (EAV) storage turns every list screen into a pivot query. Instead, six frequently displayed/sorted tags are **fixed columns** on `tracks`, and **all** tags (including those six) are also stored in normalised tag tables for arbitrary filtering.

Fixed fields (TagLib `PropertyMap` keys): `TITLE`, `ALBUM`, `DATE`, `ARTIST`, `COMPOSER`, `LYRICIST`.

### Multi-value encoding

All six fixed fields are multi-value (ID3v2.4 null-separated text frames; repeated Vorbis comment keys). The column stores the track's values for that key — after any split rules (below) are applied — joined with the unit separator **`U+001F`**, never `\0`, which truncates in SQLite and JNI string APIs. A shared `TagValues` codec does join/split; list rows render them as `"A; B"`.

### Value normalisation

At ingestion every value is trimmed and empty values are dropped, once, before anything is written. Keys are uppercased, and a value repeated under the same key (ignoring case) is kept once, in its first position, since `track_tags` has one row per (track, key, value). Not stored in the tag tables (still readable from the file through TagLib when needed): the keys `LYRICS` and `ACOUSTID_FINGERPRINT`, and any value longer than 512 characters.

### Tag splitting (manual, user-controlled)

A single tag value may hold several entries written with a separator, e.g. `ARTIST=Simon; Garfunkel`. **Scanning never splits values on its own.** The user splits them from a track list (§9):

1. **Split tags** in a track list's overflow menu acts on the tracks in that list.
2. A dialog picks the **separators** (`;`, `/`, `,`, plus any custom string such as ` feat. `) and the **tags** to split (default: people tag keys + `GENRE`; `TITLE`, `ALBUM` and `DATE` cannot be split). The last choice is remembered in `SharedPreferences`.
3. A **preview** lists each distinct (tag, value) in those tracks that contains a chosen separator, the parts it would become, and how many tracks in the whole library carry it. Each row has a checkbox, so values such as `AC/DC` can be left whole.
4. **Apply** creates one **split rule** per checked row and applies it library-wide: on every track carrying that (tag, value), the joined value is replaced by its parts in `track_tags` and in the fixed column, and the joined value is deleted from the dictionary once unused. Parts are trimmed, empties dropped, case-insensitive duplicates dropped, order kept.

Split rules are stored (`split_rules`) so the decision survives rescans: when a file is ingested, a value matching a rule is stored as its parts. A rule is keyed by (tag, value), so a new file carrying the same joined value is split the same way.

Fernix never writes to audio files; splitting changes only the database. Rules are listed in **Settings**, where deleting one re-reads the files of the tracks it was applied to (`split_rule_tracks`), restoring the joined value.

### People tag keys

One constant in code: `ARTIST`, `ALBUMARTIST`, `AUTHOR`, `COMPOSER`, `LYRICIST`, `WRITER`, `CONDUCTOR`, `REMIXER`, `ARRANGER`, `PRODUCER`, `ENGINEER`, `MIXER`, `DJMIXER`, `ORIGINALARTIST`, `ORIGINALLYRICIST`, `PERFORMER`, and every `PERFORMER:*` key (TagLib maps ID3v2.4 `TMCL` musician credits to `PERFORMER:<instrument>` and `TIPL` to `ARRANGER`/`ENGINEER`/`PRODUCER`/`MIXER`/`DJMIXER`).

### Tables

**`tracks`**

| Column | Notes |
|---|---|
| `id` | PK, autogenerate. Also the Media3 `MediaItem.mediaId`, so the playing item maps back to its row. |
| `mediaStoreId` | MediaStore `_ID`; unique index. The content URI is derived (`ContentUris.withAppendedId(EXTERNAL_CONTENT_URI, mediaStoreId)`), not stored. Verified on an API 29 emulator with an SD card: ids are unique across volumes and that URI resolves to the right file (`VolumeIdsTest`), so the id alone identifies a track. If that test ever fails on another Android version, key tracks by (volume, id). |
| `folderId` | FK → `folders.id`; index |
| `displayName` | File name |
| `durationMs`, `dateModified`, `sizeBytes` | From MediaStore; used for change detection |
| `title`, `album`, `date`, `artist`, `composer`, `lyricist` | Fixed multi-value fields (`U+001F`-joined). `title` is `COLLATE NOCASE` with an index, used to sort search results. |
| `trackNumber`, `discNumber` | Parsed from `TRACKNUMBER` / `DISCNUMBER` |
| `year` | INTEGER, nullable; parsed from the first `DATE` value |
| `albumId` | FK → `albums.id` (`ON DELETE SET NULL`), nullable; index `(albumId, discNumber, trackNumber)` for album detail |

**`tracks_fts`** — Room `@Fts4(contentEntity = TrackEntity::class)` over `title`, `album`, `artist`, `composer`, `lyricist`; tokenizer `unicode61` with `remove_diacritics=1` (so `Beyonce` finds `Beyoncé`). External-content table: Room keeps it in sync with triggers, and text is not stored twice. Each word typed by the user becomes a quoted prefix term (`"beat*"`; FTS4 needs the star inside the quotes); the whole MATCH expression is bound as an argument.

**`folders`** — `(id, parentId, name, path)`

- `name` is `COLLATE NOCASE`, so children sort case-insensitively.
- Built from MediaStore `VOLUME_NAME` + `RELATIVE_PATH`; `path` (`<volume>/<relativePath>`) has a unique index; index `(parentId, name)` lists a folder's children in order.
- Volumes are the root nodes; with a single volume the UI starts one level down.
- Folders left without tracks or sub-folders are deleted after each scan.

**`albums`** — `(id, album, year)` with a **unique index on `(album, year)`**: an album is identified by album name + year.

- `album` is the full stored `ALBUM` value, `COLLATE NOCASE` (so the unique index and the album sort ignore case: `Abbey Road` and `abbey road` are one album).
- `year` is `NOT NULL`; **`0` means unknown**. Tracks with `tracks.year = NULL` belong to the album with `year = 0`, so the unique index alone prevents duplicate albums. The UI shows no year for `0`.
- Tracks with an empty/missing album get `albumId = NULL`.
- Albums left without tracks are deleted after each scan.

**`tag_keys`** — `(id, key)`; `key` is the uppercase tag key, unique. Small (hundreds of rows).

**`tag_values`** — `(id, value)`; `value` is `COLLATE NOCASE` with a unique index. One row per **distinct value across all keys**, so `AC/DC` and `Ac/Dc` are one value (the first spelling seen is kept), and the same name under `COMPOSER` and `CONDUCTOR` is one value — which is what makes a person a single entity.

**`track_tags`** — `(trackId, keyId, valueId, ord)`, PK `(trackId, keyId, valueId)`; index `(valueId, keyId, trackId)` for filtering. FK `trackId` → `tracks` `ON DELETE CASCADE`.

- One row per value of a tag on a track, integers only. `ord` is the value's position among the track's values for that key (preserves tag order).
- Includes the six fixed fields and all people tags, so every filter uses one path.

**`key_values`** — `(keyId, valueId, trackCount)`, PK `(keyId, valueId)`. Which values occur under each key and on how many tracks; drives the filter value picker.

**`people`** — `(valueId, trackCount)`, PK `valueId`, FK → `tag_values` `ON DELETE CASCADE`.

- A person is a value that occurs under at least one people tag key, regardless of role: one person can be artist on one track and composer + lyricist on another.
- `trackCount` is the number of distinct tracks the person is credited on.

**`split_rules`** — `(id, keyId, value, parts, createdAt)`; unique index `(keyId, value)`, `value` `COLLATE NOCASE`; `parts` is the `U+001F`-joined list of parts.

**`split_rule_tracks`** — `(ruleId, trackId)`, PK `(ruleId, trackId)`; index `(trackId)`; cascading deletes from both `split_rules` and `tracks`. The tracks a rule was applied to, used to undo it.

**`playlists`** — `(id, name, createdAt, updatedAt)`

**`playlist_entries`** — `(playlistId, position, trackId)`, PK `(playlistId, position)`; index `(trackId)`; cascading deletes from both `playlists` and `tracks`.

- No reordering. Adding appends at `MAX(position) + 1`; removing deletes the row. Gaps in `position` are fine — entries are read `ORDER BY position`.
- Duplicates are allowed (the same track twice in a playlist).

`key_values` and `people` are recomputed for the (key, value) pairs a scan batch or a split touched. Unused `tag_values` are deleted after each scan and each split.

### Filtering

Filters are built with `@RawQuery` as track-id subqueries combined with `INTERSECT`, and applied as `t.id IN (…)`. Values are always bound as arguments, never concatenated into SQL.

| Filter | Track-id subquery |
|---|---|
| Tag filter: key `K`, value `V` | `SELECT trackId FROM track_tags WHERE valueId = ? AND keyId = ?` |
| Person filter (any role) | `SELECT trackId FROM track_tags WHERE valueId = ? AND keyId IN (people key ids)` |
| Free text | `SELECT rowid FROM tracks_fts WHERE tracks_fts MATCH ?` |

Results are sorted by `title, id` and keyset-paged. With a person filter active, each result also shows that person's roles on the track (the tag keys of the matching rows).

The value picker for a tag key lists its `key_values` joined to `tag_values`, sorted by value, paged, and narrowed by typing (`LIKE ? || '%'` on the `NOCASE` column, which uses the index).

### People queries

- **People list:** `SELECT v.id, v.value, p.trackCount FROM people p JOIN tag_values v ON v.id = p.valueId … ORDER BY v.value` — keyset-paged.
- **Tags on the playing track** (Now Playing): `track_tags` joined to `tag_keys` and `tag_values` for `trackId = ?` and the user's Now Playing tag keys (bound as arguments, at most 3), ordered by key and `ord`. Each value carries its value id; for a people tag key that id is the person id. A `PERFORMER` slot also matches every `PERFORMER:*` key and shows the instrument as a label.

---

## 6. Storage Access & Scanning

Direct file paths (`/storage/emulated/0/...`) are never used. Content URIs from MediaStore are passed to ExoPlayer, TagLib (via file descriptor) and Coil.

### Scan scope

Fernix **never walks the file system**. It only reads MediaStore's index of audio files on **external storage**: the primary shared storage plus any mounted SD card or USB volume (listed with `MediaStore.getExternalVolumeNames`). App-private directories, system directories and the rest of the device are never read; MediaStore itself honours `.nomedia`. Within that, the user chooses what is indexed:

- **All external storage** (the default): every audio file MediaStore reports on a mounted external volume.
- **Selected folders**: only files whose volume and `RELATIVE_PATH` lie inside one of the chosen folders, sub-folders included. Choosing the scope but no folder indexes nothing.

The scope is stored in `SharedPreferences`: `scan_scope` (`all` or `selected`) and `scan_folders` (a set of `<volume>/<relativePath>` strings, the same format as `folders.path`).

- **First launch:** after the storage permission is granted, a short screen asks what to index (*All external storage*, preselected, or *Choose folders*) before the first scan starts. The same screen is in Settings (§9, *Library folders*).
- **In-app folder chooser:** reads only `VOLUME_NAME` and `RELATIVE_PATH` from the MediaStore audio table and collects the distinct folders into a tree (memory grows with the number of folders, not tracks). It lists each volume with its audio folders and a checkbox per folder; ticking a folder includes its sub-folders, which then show as ticked and disabled (untick the parent to change them, since a selection can't express "all of this except that"). Done is disabled while nothing is ticked. It uses no system file-picker intent and no permission beyond §3. Paths are used only as values to filter MediaStore rows, never to open files.
- **Filtering:** the discovery query is limited to the mounted volumes by `VOLUME_NAME` (bound arguments). Whether a row's folder is inside the selected folders is tested in code during the merge-walk, so no long `OR` selection is built.
- **Unmounted volumes:** a volume that is not mounted (e.g. a removed SD card) is skipped. Its tracks stay in the database, and so do their playlist entries; they are shown as unavailable and cannot be played until the volume is back. A track is deleted only when its volume is mounted and the file is gone or out of scope.
- **Changing the scope:** widening it only adds tracks on the next scan. Narrowing it deletes the tracks that fall outside, like vanished files, and since playlist entries cascade (§5) it removes them from playlists too. Settings therefore shows how many tracks would be removed and asks for confirmation before saving.

1. **Discovery** — query `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` (which spans all external volumes) for `_ID`, `SIZE`, `DATE_MODIFIED`, `DURATION`, `VOLUME_NAME`, `RELATIVE_PATH`, `DISPLAY_NAME`, ordered by `_ID`, restricted to the mounted volumes and the scan scope above.
2. **Diff** — merge-walk the MediaStore cursor and a `tracks` cursor (joined to `folders`), both ordered by `mediaStoreId`, in constant memory (no map of the whole library). New or changed files (size, date, folder or name) are queued for parsing; vanished or out-of-scope ones are deleted (except tracks on unmounted volumes).
3. **Tag ingestion** — `ContentResolver.openFileDescriptor(uri, "r")` → fd passed to native code → TagLib reads through a file-descriptor stream (`TagLib::FileStream(fd, true)` or a custom `IOStream`) → returns the `PropertyMap` as `Map<String, List<String>>`.
4. **Persist** — values are normalised and existing split rules applied (§5), then each batch of ~500 tracks is written in one Room transaction (`folders`, `albums`, `tracks`, `tag_keys`, `tag_values`, `track_tags`, `split_rule_tracks`, `key_values`, `people`). A re-parsed track has its `track_tags` rows replaced. Key and value ids and the split rules are cached in memory for the duration of the scan.
5. **Clean up** — after the scan, unused folders, albums and tag values are deleted.

Each committed batch is final, so an interrupted scan resumes where it stopped on the next run (the diff skips what is already stored). The library is usable while a scan runs, and the UI shows progress. Scanning runs on `Dispatchers.IO` and never blocks the UI.

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
- **Audio properties:** the track details screen (§9) shows technical details from TagLib's `AudioProperties` (length, bitrate, sample rate, channels, bits per sample where the format has it), read on demand through a second JNI call on the same fd. They are not stored in the database. The codec/format is derived from the MIME type or file extension, since `AudioProperties` does not name it.
- **Pictures:** embedded pictures are read on demand through TagLib's complex properties (`PICTURE`: picture type, description, MIME type, bytes) via another JNI call, and handed to Coil as bytes. Nothing is stored in the database or written to disk; Coil's memory cache holds decoded images. Picture types follow ID3v2 `APIC` types (and the FLAC/Vorbis `METADATA_BLOCK_PICTURE` types, which are the same list).
- The JNI layer returns a flat `String[]` of alternating key/value pairs (a multi-value tag repeats its key once per value); Kotlin groups it into `Map<String, List<String>>`. No exceptions cross the boundary: failures return null and are logged.

---

### Person images

A person's image is an embedded picture whose type is a role: Lead artist (7), Artist (8), Conductor (9), Band (10), Composer (11) or Lyricist (12). Such pictures are imported by tag editors such as foobar2000 and are read from the person's tracks when the person screen opens (§9).

- Picture type → people tag key: 7, 8, 10 → `ARTIST`; 9 → `CONDUCTOR`; 11 → `COMPOSER`; 12 → `LYRICIST`. Cover (3) and other types are never used for people.
- A picture belongs to a person if the track has a single value under the mapped key and it is that person, or if the picture's description equals the person's name (case-insensitive). When several people share the key and the description is empty, the picture is ignored rather than guessed.
- The screen looks at the person's tracks in `id` order, tries at most 20 files, and uses the first match, preferring the type that matches the person's most frequent role. No match → a placeholder with the person's initials.

---

## 8. Playback & Background Service

- `MediaSessionService` hosts a single ExoPlayer instance and runs independently of the UI lifecycle; the UI connects via `MediaController`.
- Only core and session Media3 modules are included (see §2).
- Gapless playback uses ExoPlayer's built-in support (MP3 LAME/iTunes gapless info, Ogg, FLAC).
- **Sound quality is never traded for performance or size.** Playback must stay bit-perfect with the source except for what the user chooses:
  - No resampling, downmixing, bit-depth reduction or lower-quality decoder settings to save CPU, memory or battery; ExoPlayer's decoders and audio sink run with their default (full-quality) settings, and any change to them needs the user's approval.
  - No volume normalisation, loudness processing or other audio effect unless the user turns it on.
  - The equalizer is off by default and, when off, adds nothing to the signal path; its settings are restored only if the user had enabled it.
  - Optimisation targets the library, scanning, UI and memory (§5, §6, §9), never the decode and output path. A change that touches the audio path is checked by ear and against the source, not only by benchmark.
- Equalizer: `android.media.audiofx.Equalizer` on ExoPlayer's audio session id.
- The play queue is not capped: "Play" on any list queues all of its tracks. Queue items carry only the track id, content URI and the title/artist/album shown in the notification; anything else is loaded from the database when needed.

---

## 9. User Interface

Material 3 defaults, dynamic color on Android 12+, light/dark themes. Bottom navigation for the five library screens (Folders, Search, Albums, Playlists, People); Now Playing and Queue are reached from a persistent mini-player bar.

All long lists (tracks, albums, people, folders, filter values) are keyset-paged from the database (§5) and rendered in `LazyColumn`s.

Every track list (Search results, album detail, folder, playlist detail) has **Split tags** in its overflow menu, which runs the manual tag-splitting flow of §5 on the tracks in that list. The split runs in the background with progress shown.

1. **Now Playing** — album art, title, album, date, the user's **Now Playing tags**, seek bar, play/pause/next/previous, shuffle/repeat.
   - Title, album and date are always shown and cannot be changed.
   - **Now Playing tags:** **3 slots**, each set in Settings to any tag key in the library (`tag_keys`) except `TITLE`, `ALBUM` and `DATE`, or left empty. Defaults, in order: `COMPOSER`, `LYRICIST`, `ARTIST`. With title, album and date, Now Playing shows at most **6 tags**. Stored in `SharedPreferences`. A tag the playing track doesn't have is hidden. Each tag is shown one entry per value, as stored in `track_tags` (after any split rules).
   - All details come from the database, never from the file: the playing `MediaItem.mediaId` is `tracks.id`; title, album and date come from the `tracks` row, and the tags from the "tags on the playing track" query (§5), which reads only that track's rows via the `track_tags` PK. Both are observed as Room `Flow`s, so a split applied while the track plays updates the screen immediately.
   - Every value is tappable and opens **Search** pushed onto the back stack (Back returns to Now Playing). A value of a people tag key opens a person filter (§5), listing every track the person contributed to in any role; any other tag (e.g. `GENRE`, `BPM`) opens a tag filter for that key and value.
   - An equalizer icon in the top app bar opens the equalizer in a bottom sheet (not a separate screen).
2. **Folders** — browse the `folders` tree (built from the DB, not the filesystem); play/enqueue a folder or a track.
3. **Search** — free-text search (FTS) over title, album, artist, composer and lyricist, plus arbitrary tag filters (pick any tag key present in the library, then one or more values) and an optional person filter, shown as a removable chip with the person's name. All filters combine with AND. Results are sorted by title. With a person filter, each result shows the person's roles on that track. Results can be played or enqueued. If a person filter refers to a person deleted by a rescan or a split, the chip is dropped.
4. **Queue** — current play queue from the `MediaController`; drag to reorder, remove, jump to item.
5. **Albums** — albums (name + year), sorted by album then year; album detail lists tracks by disc/track number.
6. **Playlists** — create, rename, delete; add tracks from any track list; remove entries (no reordering). Playlist detail has **Play** (in playlist order) and **Shuffle** (same queue with ExoPlayer shuffle mode on).
7. **People** — every person once (no grouping by role), sorted by name, with their track count. Tapping a person opens the **person details** screen (below).
   - **Person details:** image (from a role-typed embedded picture, §7 *Person images*, else initials), name, roles (each people tag key the person appears under, with the track count per role, from `track_tags`) and the paged list of all their tracks (the person filter of §5, sorted by title, each row showing the roles on that track). Play/enqueue the list; **Show in Search** opens Search with the person filter. The screen is also reached by tapping a people value in Now Playing and track details.
9. **Album details** — opened by tapping an album name anywhere (track rows, Now Playing, track details, Albums list): album art, name, year (hidden when 0) and the tracks by disc/track number; Play/enqueue the album. Art is the front-cover picture (type 3, else the first picture) of the first track that has one.
10. **Track details** — opened from the overflow menu of any track row, or from Now Playing. Read from the file on demand (the fd of the content URI) plus the database:
   - cover art (front cover, else first picture);
   - **all tags:** every `track_tags` key with its values in `ord` order (after split rules); people and album values are tappable (person details, album details), other values open a tag filter in Search;
   - **technical details:** format, duration, bitrate, sample rate, channels, bits per sample (§7 *Audio properties*; fields TagLib doesn't report are hidden);
   - **file details:** file name, folder, volume, size, modified date, content URI, from the `tracks` and `folders` rows.
8. **Settings** — reached from the overflow menu in the library screens' top app bar.
   - **Library folders:** choose *All external storage* or *Selected folders* with the in-app chooser (§6), and rescan. Saving a narrower scope shows how many tracks and playlist entries will be removed and asks for confirmation.
   - **Now Playing tags:** set, change or clear each of the 3 tag slots shown on Now Playing, and restore the defaults.
   - **Split rules:** lists the rules (tag, joined value → parts), paged; deleting a rule undoes it (§5).

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
