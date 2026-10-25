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
| Toolchain | Gradle 9.8.2, AGP 9.4.1, Kotlin 2.4.21 (the Compose compiler plugin), KSP 2.3.12, JDK 17 bytecode target |
| NDK / CMake | 28.2.13676358 / 3.31.6 (SDK packages) |
| Native ABIs | `arm64-v8a`, `armeabi-v7a`, `x86_64` (emulator) |
| Distribution | F-Droid |
| License | GPL-3.0-or-later — all dependencies must be GPLv3-compatible |

### Audio formats

MP3, Ogg Vorbis, Opus, FLAC, WAV and **AAC in M4A** (`.m4a`, added 2026-10-24). All are decoded by the platform `MediaCodec` on Android 10+, so no FFmpeg or other decoder extension is needed. (An `.m4a` can also hold Apple Lossless: TagLib reads its tags and bit depth, but playback depends on the device's decoder, which is not guaranteed.)

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

### Tag splitting (manual, one value at a time)

A single tag value may hold several entries written with a delimiter, e.g. `ARTIST=Simon; Garfunkel`. **Scanning never splits values on its own, and there is no bulk splitting**: the user splits a value they are looking at, as in Quod Libet:

1. In **Settings** (§9) the user sets the **delimiters**, any number of them (default `;`, `/`, `,`; any string such as ` feat. `, used as typed, spaces included). Stored in `SharedPreferences`.
2. On **Track details** (§9) every tag value that contains one of the delimiters shows a **split button** next to it (`TITLE`, `ALBUM`, `DATE`, `TRACKNUMBER` and `DISCNUMBER` never do: they hold one thing, and `3/12` is track 3 of 12).
3. The button asks for confirmation, showing the parts the value would become and how many tracks in the whole library carry it.
4. Confirming splits the value in **every track carrying that exact (tag, value)**, not just the one on screen: the joined value is replaced by its parts in `track_tags` and in the fixed column, and deleted from the dictionary once unused. Parts are trimmed, empties dropped, case-insensitive duplicates dropped, order kept. The summary tables are updated and the tags on the screen update by themselves (they are a Room `Flow`).

**Nothing is stored about a split**: there are no split rules. A split is a plain edit of the database, and since a scan reads only new or changed files (§6.2), the other tracks keep it across rescans. A track whose file changes is read again and shows what the file says, so its value comes back whole (and its split button with it). Fernix never writes to audio files. Changing or removing a delimiter does not affect splits already made. There is no undo.

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
| `dateAdded` | INTEGER, epoch seconds, from MediaStore `DATE_ADDED`; index. Orders *Recently Added* (§5 *Default playlists*). Refreshed with the other MediaStore columns, never used for change detection. |
| `playCount`, `lastPlayedAt` | INTEGER `NOT NULL DEFAULT 0`; and epoch milliseconds, nullable (`NULL` = never played). Written by the playback service (§8), never by a scan or a tag re-parse. Indexes on `playCount` and `lastPlayedAt` serve *Most Played* and *Never Played*. |
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

**`playlists`** — `(id, name, kind, createdAt, updatedAt)`; `kind` is `user` (created by the user) or `favourites` (the one built-in editable playlist, see *Default playlists*).

**`playlist_entries`** — `(playlistId, position, trackId)`, PK `(playlistId, position)`; **unique index `(playlistId, trackId)`** (a track is in a playlist at most once); index `(trackId)` for the cascade; cascading deletes from both `playlists` and `tracks`.

- No reordering. Adding appends at `MAX(position) + 1`; removing deletes the row. Gaps in `position` are fine — entries are read `ORDER BY position`.
- **No duplicates:** a track is in a playlist at most once (the unique index above), in Favourites and in every user-created playlist. Adding a track that is already there is a silent no-op (the insert is `INSERT … WHERE NOT EXISTS`), also when a whole folder, album or list is added.

#### Default playlists

Four playlists are always present, in this order, above the user's own playlists. Of these, only **Favourites** can be changed by the user; the other three are read-only and maintained by the app. They cannot be renamed, deleted, added to or removed from, and they are not offered by *Add to playlist*. The user's own playlists (below them) stay fully editable: create, rename, delete, add and remove tracks.

| Playlist | Stored? | Contents and order |
|---|---|---|
| **Favourites** | Yes: one `playlists` row with `kind = 'favourites'`, created with the database, plus `playlist_entries` | The tracks the user added, in the order added (append at `MAX(position) + 1`). A track appears once. The user adds and removes tracks (Now Playing shortcut, *Add to playlist*, *Remove from playlist*). Cannot be renamed or deleted. |
| **Recently Added** | No: a query | The **50** tracks with the latest `dateAdded`: `ORDER BY dateAdded DESC, id DESC LIMIT 50`. |
| **Never Played** | No: a query | Tracks with `lastPlayedAt IS NULL`, by `title, id`, keyset-paged. |
| **Most Played** | No: a query | The **50** tracks with `playCount > 0`, `ORDER BY playCount DESC, lastPlayedAt DESC, id`. |

The three read-only playlists have no rows of their own, so they are always current and cost no storage. Their detail screen is the same as any playlist's (Play, Shuffle, track list) without the remove action. A scan or tag re-parse keeps `playCount` and `lastPlayedAt`; deleting a track removes it from Favourites through the cascade, like any playlist.

**What counts as a play:** a track counts as played when it **plays through to its end** (ExoPlayer moves on to the next item, or the queue ends, on its own). Skipping, removing it from the queue or stopping before the end does not count, and nor does pausing. When it counts, the playback service sets `lastPlayedAt` to the current time and adds 1 to `playCount` (§8).

`key_values` and `people` are recomputed for the (key, value) pairs a scan batch or a split touched. Unused `tag_values` are deleted after each scan and each split.

### Filtering

Filters are built with `@RawQuery` as track-id subqueries combined with `INTERSECT` (an AND between filters on **different** keys, the person and the text; several values of the **same** key are an OR inside that key's subquery), and applied as `t.id IN (…)`. Values are always bound as arguments, never concatenated into SQL.

| Filter | Track-id subquery |
|---|---|
| Tag filter: key `K`, values `V1…Vn` | `SELECT trackId FROM track_tags WHERE keyId = ? AND valueId IN (?, …)`: one subquery per **key**, so several values of the same key are ORed |
| Person filter (any role) | `SELECT trackId FROM track_tags WHERE valueId = ? AND keyId IN (people key ids)` |
| Free text | `SELECT rowid FROM tracks_fts WHERE tracks_fts MATCH ?` |

Results are sorted by `title, id` and keyset-paged. With no filter at all the query lists **every track** (the *Tracks* screen, §9). With a person filter active, each result also shows that person's roles on the track (the tag keys of the matching rows).

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

- **First launch:** after the storage permission is granted, a short screen asks what to index (*All external storage*, preselected, or *Choose folders*) before the first scan starts. The same screen is in Settings (§9, *Library folders*). The app then opens on the **Tracks** screen (the first tab), whose list fills in by itself while the first scan runs and once it has finished, so the user never has to leave and come back.
- **In-app folder chooser:** reads only `VOLUME_NAME` and `RELATIVE_PATH` from the MediaStore audio table and collects the distinct folders into a tree (memory grows with the number of folders, not tracks). It lists each volume with its audio folders and a checkbox per folder; ticking a folder includes its sub-folders, which then show as ticked and disabled (untick the parent to change them, since a selection can't express "all of this except that"). Done is disabled while nothing is ticked. It uses no system file-picker intent and no permission beyond §3. Paths are used only as values to filter MediaStore rows, never to open files.
- **Filtering:** the discovery query is limited to the mounted volumes by `VOLUME_NAME` (bound arguments). Whether a row's folder is inside the selected folders is tested in code during the merge-walk, so no long `OR` selection is built.
- **Unmounted volumes:** a volume that is not mounted (e.g. a removed SD card) is skipped. Its tracks stay in the database, and so do their playlist entries; they are shown as unavailable and cannot be played until the volume is back. A track is deleted only when its volume is mounted and the file is gone or out of scope.
- **Changing the scope:** widening it only adds tracks on the next scan. Narrowing it deletes the tracks that fall outside, like vanished files, and since playlist entries cascade (§5) it removes them from playlists too. Settings therefore shows how many tracks would be removed and asks for confirmation before saving.

1. **Discovery** — query `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` (which spans all external volumes) for `_ID`, `SIZE`, `DATE_MODIFIED`, `DATE_ADDED`, `DURATION`, `VOLUME_NAME`, `RELATIVE_PATH`, `DISPLAY_NAME`, ordered by `_ID`, restricted to the mounted volumes and the scan scope above.
2. **Diff** — merge-walk the MediaStore cursor and a `tracks` cursor (joined to `folders`), both ordered by `mediaStoreId`, in constant memory (no map of the whole library). New or changed files (size, date, folder or name) are queued for parsing; vanished or out-of-scope ones are deleted (except tracks on unmounted volumes).
3. **Tag ingestion** — `ContentResolver.openFileDescriptor(uri, "r")` → fd passed to native code → TagLib reads through a file-descriptor stream (`TagLib::FileStream(fd, true)` or a custom `IOStream`) → returns the `PropertyMap` as `Map<String, List<String>>`.
4. **Persist** — values are normalised, then each batch of ~500 tracks is written in one Room transaction (`folders`, `albums`, `tracks`, `tag_keys`, `tag_values`, `track_tags`, `key_values`, `people`). A re-parsed track has its `track_tags` rows replaced and keeps its `playCount` and `lastPlayedAt`. Key and value ids and the split rules are cached in memory for the duration of the scan.
5. **Clean up** — after the scan, unused folders, albums and tag values are deleted.

**Errors (2026-10-24).** A bad file or a bad moment never costs the library or stops the scan:
- **Unreadable file** (damaged, gone before it was read, not openable): the track is stored without tags, listed by its file name, and marked as never seen (`dateModified = 0`), so the next scan reads it again; one that is really gone is then removed by the diff. The scan counts them, and Settings says how many ("N files could not be read; tried again at the next scan"). The native readers catch every TagLib exception, so a corrupt file returns "unreadable" instead of crashing the process (`CorruptFilesTest` cuts and damages every sample format).
- **The problem-files log:** every file that cannot be indexed (above) or played (§8) is written to `problem-files.log` in the app's private storage: time, kind, **file name**, its MediaStore folder (`<volume>/<relative path>`, never a raw path) and a reason code, one tab-separated line each. Entries are kept **30 days** and dropped after (the file is rewritten on the next read or write; at most 5000 are kept), and the same file failing again within a day is not logged again, since a damaged file would otherwise add a line at every scan. **Settings → Problem files** shows the count and opens the list (newest first, with Clear). It never leaves the device. The class is `ProblemLog`.
- **MediaStore cannot answer** (a null cursor, a `SecurityException`, e.g. access revoked): the scan fails with a message and Retry / Dismiss in a bar above the mini-player. It is never read as "no files", which would delete every stored track.
- **No storage permission:** no scan is started; the app shows the permission screen (§9) and re-checks when it is resumed. Revoking the permission in system settings ends the process, and the library in the database is untouched.
- **Unmounted volume:** see above; the tracks stay.

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
- Compiled-in TagLib modules: MPEG (MP3), Vorbis/Opus/FLAC/Ogg (`WITH_VORBIS`), RIFF/WAV (`WITH_RIFF`), **MP4 (`WITH_MP4`, for the tags, cover and properties of `.m4a`)**, APE (`WITH_APE`, needed for APEv2 tags in MP3 files), zlib. Disabled: ASF, DSF, Matroska, MOD, Shorten, TrueAudio, C bindings. M4A atoms map to the usual property keys (`©nam` → `TITLE`, `©ART` → `ARTIST`, `©wrt` → `COMPOSER`, `trkn` → `TRACKNUMBER` as `3/12`, the cover → the `PICTURE` complex property); other atoms become tag keys of their own, like any other tag.
- Files are opened from Kotlin with `ParcelFileDescriptor.detachFd()`; ownership of the fd passes to native code, where TagLib's `FileStream(fd, true)` closes it.
- NDK and CMake versions are pinned in `app/build.gradle.kts` (`ndkVersion`, `externalNativeBuild.cmake.version`); the SDK-provided CMake is used, not the system one.
- **Audio properties:** the track details screen (§9) shows technical details from TagLib's `AudioProperties` (length, bitrate, sample rate, channels, bits per sample where the format has it: FLAC, WAV and Apple Lossless, not AAC, which is lossy), read on demand through a second JNI call on the same fd. They are not stored in the database. The codec/format is derived from the MIME type or file extension, since `AudioProperties` does not name it.
- **Pictures:** embedded pictures are read on demand through TagLib's complex properties (`PICTURE`: picture type, description, MIME type, bytes) via another JNI call, and handed to Coil as bytes. Full-size art is never stored in the database or on disk; Coil's memory cache holds decoded images. Only small list thumbnails are cached on disk (*Thumbnails* below). Picture types follow ID3v2 `APIC` types (and the FLAC/Vorbis `METADATA_BLOCK_PICTURE` types, which are the same list).
- **Thumbnails:** list rows (Albums, Tracks) show a thumbnail of the album art, loaded lazily for the rows on screen. A row shows the placeholder (the monochrome app logo, `ic_launcher_monochrome`, the same placeholder as Now Playing, track details, album details and the mini player) until its art arrives, so scrolling never waits for a file.
  - **Cache by album.** The thumbnail is keyed by album (not by track): the first time a row needs it, the album's art is read from its file (§9.9), scaled down to at most **160 px**, and saved as a small JPEG (about 5–10 KB) in the app's **cache directory** (`cacheDir/thumbnails/`; art that doesn't exist is remembered with an empty marker file, so it isn't searched for again); later requests, in this session or after a restart, read that file instead of the audio file. Coil's memory cache keeps the decoded thumbnails of the rows in view.
  - **Bounded and disposable.** The folder is capped at **20 MB** (oldest files are deleted when it is full), is cleared by Android when it needs space, and is cleared by Fernix after a scan that added, changed or removed files (so changed art is never stale). It is the only thing Fernix writes for pictures: no database column, nothing in shared storage, no full-size copies. Now Playing, Track details and the album header still read the full-size picture from the file.
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
- **A file that cannot be played** (gone, unreadable, damaged) is logged in the problem-files log (§6 *Errors*) and skipped: the service moves to the next item and keeps playing (`SkipOnError`), and the UI shows a short message. When every item in the queue has failed in a row (the storage went away, or repeat-all would loop for ever) it stops.
- Gapless playback uses ExoPlayer's built-in support (MP3 LAME/iTunes gapless info, Ogg, FLAC).
- **Sound quality is never traded for performance or size.** Playback must stay bit-perfect with the source except for what the user chooses:
  - No resampling, downmixing, bit-depth reduction or lower-quality decoder settings to save CPU, memory or battery; ExoPlayer's decoders and audio sink run with their default (full-quality) settings, and any change to them needs the user's approval.
  - No volume normalisation, loudness processing or other audio effect unless the user turns it on.
  - The equalizer is off by default and, when off, adds nothing to the signal path; its settings are restored only if the user had enabled it.
  - **Verified by `AudioPathTest`** (2026-10-24): what the player hands to the platform's `AudioTrack` has the source's own sample rate, channel layout and, for 16-bit sources, bit depth (WAV 8 kHz, FLAC and MP3 and Ogg Vorbis 22.05 kHz, Opus 48 kHz, hi-res FLAC 96 kHz stereo and hi-res WAV 44.1 kHz stereo), and the player is left on its defaults (normal speed, no silence skipping, volume 1). What Android's mixer then does for the device's output is the platform's, outside Fernix.
  - **Float output is on (approved 2026-10-24).** With ExoPlayer's defaults a 24-bit source reached the `AudioTrack` as 16-bit, which the rule above forbids. `PlayerFactory` therefore builds the player with `DefaultRenderersFactory.setEnableAudioFloatOutput(true)`: a 24-bit source is delivered as 32-bit float, which holds it exactly (`AudioPathTest.hiRes24Bit_isNotReducedTo16Bit`). Side effects, checked: WAV, MP3, Ogg Vorbis and Opus are unchanged (16-bit PCM); a **FLAC arrives as float even when it is 16-bit** (the platform decoder then outputs float), which is also exact. The rate and channel layout are never changed. How much of that the *device* keeps depends on its audio hardware path: the emulator's output is 48 kHz 16-bit whatever the app sends.
  - Optimisation targets the library, scanning, UI and memory (§5, §6, §9), never the decode and output path. A change that touches the audio path is checked by ear and against the source, not only by benchmark.
- Equalizer: `android.media.audiofx.Equalizer` on ExoPlayer's audio session id.
- **Play history:** the service itself records plays (the UI may not be running). When an item finishes on its own (a `Player.Listener` transition with reason `AUTO`, `STATE_ENDED` on the last item, or a `REPEAT` transition while repeat-one is on; `PlayRecorder`) it writes `lastPlayedAt` and `playCount + 1` for that `tracks.id` on `Dispatchers.IO` (§5 *Default playlists*). It only listens to player events and does not touch the audio path.
- The play queue is not capped: "Play" on any list queues all of its tracks. Queue items carry only the track id, content URI and the title/artist/album shown in the notification; anything else is loaded from the database when needed.

---

## 9. User Interface

Material 3 components with the app's own look (2026-10-24, from review), light and dark following the system theme unless the user picks one in **Settings → Appearance**: **Fernix** (the light green theme, the **default**), **Dark**, or **System** (exactly the phone's light or dark setting, in stock Material colours: the wallpaper's dynamic colours on Android 12+, Material's baseline palette on 10 and 11; no gradient and none of the Fern/Burgundy/Purple palette; the type and compact rows stay), kept in `Settings.themeMode`, applied at once without restarting, with the system-bar icons following the chosen theme):
- **Fernix colour depth:** the Fernix theme only (not System) sits every screen on a vertical gradient: Fern Green at the top, fading through the middle into a faint Purple and then Burgundy tint at the bottom; the bars, lists and rows are transparent over it. Its surfaces (navigation bar, mini-player, sheets) are a deeper green, and the action capsule is Purple, so the three colours all show. The Dark theme is flat, except Now Playing, which has its own wash in both themes.
- **Colour:** a fixed palette, not the wallpaper's dynamic colour: **Fern Green** is the main colour (primary: play button, selected tab pill, the seek bar, the current queue row), **Burgundy** the accent for what is personal (secondary: the favourite heart, the selected filter chips) and **Purple** for what you can tap through to (tertiary: people and tag links, the album link, role pills, person avatars). The neutrals are tinted green. Light and dark schemes are in `ui/theme/Theme.kt`; the window background is set for both (`values-night/themes.xml`) so a dark system theme never flashes white at launch.
- **Type:** the system's condensed sans (`sans-serif-condensed`, Roboto Condensed on Android) for every style, bold for titles and labels: more fits a line, and no font file or library is added to the APK.
- **Compact:** list rows use the app's own `ListItem` (48 dp minimum, not Material's 56 to 88 dp), the top bar and the bottom bar are slim, and rounded shapes are 8 to 16 dp.
- **Now Playing fits one screen without scrolling:** the art takes the space the other rows leave (a square, as large as fits), then the title with the heart, the album with its date in brackets, one line per slot (label and values side by side), the seek bar and one row of small controls (previous, next, shuffle, repeat 40 dp; play/pause 52 dp). In landscape the art is on the left and the details and controls on the right; on a very short screen only the details scroll, the controls never shrink. A soft wash of the main colour sits behind it. Bottom navigation for the five library screens, in this order: **Tracks, Albums, Folders, Playlists, People** (the app starts on Tracks); Now Playing and Queue are reached from a persistent **mini-player** bar. The mini-player has three lines like a row of the Tracks list: the **title**, then the **album with its date in brackets**, then the **artists**, the last two in a smaller font; they come from the playing track's database row (a Room `Flow`), with the player's title and artist shown until it arrives.

All long lists (tracks, albums, people, folders, filter values) are keyset-paged from the database (§5) and rendered in `LazyColumn`s.

**Search on library screens.** **Tracks, Albums, People and Playlists** have the top-bar search described under Tracks (§9.3): a search icon opens a field under the title. On Albums, People and Playlists it matches any part of the name, ignoring case (the typed text is bound as a `LIKE '%text%'` pattern with `%`, `_` and `\` escaped, never concatenated into SQL; the paging keeps its keyset), has no filters, and is kept in the view model's saved state. On Playlists it narrows Favourites, the default playlists (by their shown names) and your own. Folders has no search. Each of these screens draws its own top bar (`LibraryTopBar`); Folders uses the plain one from the app shell.

**Process death and rotation.** What the user has set up on a screen survives the process being killed and a rotation: the Tracks search text and filters, the open Folders path (folders a scan has removed drop out) and the ticked folders in the folder chooser live in each view model's `SavedStateHandle` (as strings and ids, never as rows); dialogs and pickers that hold input (Settings, playlist rename/delete, the scope confirmation) use `rememberSaveable`; the back stack is saved too. Lists are re-queried from the database, not saved. Tracks and Person details put their header (search box, roles, actions) inside the list so a landscape screen scrolls as one.


1. **Now Playing** — album art, title, album, date, the user's **Now Playing tags**, seek bar, play/pause/next/previous, shuffle/repeat.
   - Title, album and date are always shown and cannot be changed.
   - **Now Playing tags:** **3 slots**, each set in Settings to any tag key in the library (`tag_keys`) except `TITLE`, `ALBUM` and `DATE`, or left empty. Defaults, in order: `COMPOSER`, `LYRICIST`, `ARTIST`. With title, album and date, Now Playing shows at most **6 tags**. Stored in `SharedPreferences`. A tag the playing track doesn't have is hidden. Each tag is shown one entry per value, as stored in `track_tags` (after any split rules).
   - All details come from the database, never from the file: the playing `MediaItem.mediaId` is `tracks.id`; title, album and date come from the `tracks` row, and the tags from the "tags on the playing track" query (§5), which reads only that track's rows via the `track_tags` PK. Both are observed as Room `Flow`s, so a split applied while the track plays updates the screen immediately.
   - Every value is tappable and opens **Tracks** pushed onto the back stack (Back returns to Now Playing). A value of a people tag key opens a person filter (§5), listing every track the person contributed to in any role; any other tag (e.g. `GENRE`, `BPM`) opens Tracks with a tag filter for that key and value, as on Track details.
   - An equalizer icon in the top app bar opens the equalizer in a bottom sheet (not a separate screen).
   - **Favourite button:** a heart next to the title that adds the playing track to Favourites (§5) with a **single tap**, and removes it with another tap. It is filled while the track is in Favourites, observed as a Room `Flow` so it also follows changes made elsewhere. No dialog and no confirmation.
2. **Folders** — browse the `folders` tree (built from the DB, not the filesystem); play/enqueue a folder or a track.
3. **Tracks** — every track in the library, by title (keyset-paged), narrowed by a **search box** and by **filters**; it replaces the former Search screen.
   - **Search in the top bar (like Musicolet):** the bar shows the title with a **search icon**, an **add-filter icon** (a funnel) and the settings gear. Tapping the search icon opens a slim pill-shaped **search field** (free-text search (FTS) over title, album, artist, composer and lyricist) in a row **under** the bar; the **title stays**. The cross in that row clears the text (with nothing typed it closes the search); the search icon and the system Back close it and clear the text; the list narrows as you type. A search left in place stays open when you return to the tab. The funnel icon opens a **bottom sheet** (the same kind as Add to playlist, not a dialog): first the list of tag keys, then, after one is tapped, its values with a narrowing field and the track count of each, and an arrow back to the keys. **Filter chips** wrap in a row at the top of the list, followed by a **reset icon** that removes every search term at once: the text and all the chips, so the list is every track again.
   - **Filters:** any number, each a removable chip; a long chip is cut with an ellipsis and the chips **wrap onto more lines**, so the add-filter icon is never pushed out of view. A tag filter is a tag key picked from the library plus a value; a person filter is a chip with the person's name. **Filters on the same tag are ORed; filters on different tags are ANDed** (Genre: Rock, Genre: Pop and Label: X means (Rock or Pop) and X), and the text and the person filter are ANDed with the rest.
   - **Actions on the result list:** one capsule above the results with three icons, **Play**, **Add to queue** and **Add to playlist**. They act on every track matching the current search and filters (not only the rows loaded so far). Add to playlist opens the sheet of §9.6 (a playlist of yours, Favourites, or a **new playlist** made from the results); a track already in the playlist is skipped (§5).
   - **Row:** the thumbnail, then the **title**; below it the **album with its date in brackets** (`Album (1996)`, from the track's `DATE` tag; the album alone or just `(1996)` when the other is missing), then the **artists**, both in a smaller font. With a person filter the person's roles follow in a last line.
   - **Results** are sorted by title. If a person filter refers to a person deleted by a rescan or a split, the chip is dropped.
   - Reached with a person already set from Now Playing, and with a tag value as a filter from Track details and Now Playing (§9.1, §9.10).
   - **Thumbnails:** every row has a small thumbnail of its **album art** (the album's art as in §9.9; a track without an album shows its own front cover or first picture), with the album placeholder while loading or when there is none. See §7 *Thumbnails*.
4. **Queue** — current play queue from the `MediaController`, opened from the queue icon in Now Playing's top bar; tap a row to jump to it, the ✕ removes it, and the handle drags it to a new place (it moves by the number of rows it was dragged over, on release). The playing row is highlighted and the list opens on it. The `MediaController` wrapper publishes the queue as a list only when the queue itself changes (not on every playback tick), so a queue of tens of thousands of tracks stays cheap.
5. **Albums** — albums (name + year), sorted by album then year, each row with a **thumbnail of the album art** (§7 *Pictures*, *Thumbnails*); album detail lists tracks by disc/track number.
6. **Playlists** — the four **default playlists** first (Favourites, Recently Added, Never Played, Most Played; §5), then the user's own playlists. Only Favourites is editable among the defaults; the other three are read-only and show no remove, rename or delete action. For the user's own playlists: create, rename, delete; add tracks from any track list through the **Add to playlist sheet**: a bottom sheet with a highlighted **New playlist** row (it opens a name field in place, with the keyboard up, and creates the playlist from the tracks) followed by Favourites and the user's playlists as rounded tiles with their track counts; read-only playlists are never offered, and a toast confirms where the tracks went; remove entries (no reordering). Playlist detail has **Play** (in playlist order) and **Shuffle** (same queue with ExoPlayer shuffle mode on).
7. **People** — every person once (no grouping by role), sorted by name, with their track count. Tapping a person opens the **person details** screen (below).
   - **Person details:** image (from a role-typed embedded picture, §7 *Person images*, else initials), name, roles (each people tag key the person appears under, with the track count per role, from `track_tags`), each shown as a **small, transparent pill with a thin border** and a reduced font of 11 sp (`COMPOSER · 3`); each track row shows the title, below it the **album with its date in brackets** (as on Tracks), and below that the same pills for the roles the person has on that track and the paged list of all their tracks (the person filter of §5, sorted by title, each row showing the roles on that track). The same three icons as on Tracks (**Play**, **Add to queue**, **Add to playlist**, as one capsule) act on all of the person's tracks (there is no button that opens the person in Tracks). The screen is also reached by tapping a people value in Now Playing and track details.
9. **Album details** — opened by tapping an album name anywhere (track rows, Now Playing, track details, Albums list): album art, name, year (hidden when 0) and the tracks by disc/track number; Play/enqueue the album. Art is the front-cover picture (type 3, else the first picture) of the first track that has one.
10. **Track details** — opened from the overflow menu of any track row, or from Now Playing. Read from the file on demand (the fd of the content URI) plus the database. A **compact** page, from top to bottom:
   - the **cover art** (front cover, else first picture), kept small and centred;
   - the **title**, **centred**, and below it, also centred, the **album name in bold**, in a smaller font than the title, with the **date in brackets** after it (the first `DATE` value; the name opens the album details);
   - **Composer**, **Lyricist** and **Artists**, one row each: the name of the tag on the left, its values side by side on the right;
   - **Other tags:** every other `track_tags` key (so not title, album, date, composer, lyricist or artist), in the same rows, values in `ord` order (after any splits);
   - a tag value is plain **accent-coloured text**, not a pill or a chip. A **person's** (any people tag key) opens their **person details**; **any other value opens the Tracks screen with that value turned into a filter** (`key: value`), which Back leaves again. A value holding one of the Settings delimiters has a small **split button** after it (§5 *Tag splitting*), which asks for confirmation and splits it for every track that carries it;
   - **technical details:** format, duration, bitrate, sample rate, channels, bits per sample (§7 *Audio properties*; fields TagLib doesn't report are hidden);
   - **file details:** file name, folder, volume, size, modified date, content URI, from the `tracks` and `folders` rows. Technical and file details use the same compact two-column rows.
8. **Settings** — reached with one tap on the **gear icon** at the right of the title bar of each of the five library screens (no menu in between).
   - **Library folders:** shows the current scope (*All external storage*, or the selected folders listed) and **Change folders…**, which opens the first-launch choice (*All external storage* or *Selected folders* with the in-app chooser, §6) starting from the current scope; Rescan is next to it. Saving a scope that would remove tracks first shows how many tracks and playlist entries go (counted per folder from the `folders`, `tracks` and `playlist_entries` tables, leaving out folders on unmounted volumes, whose tracks the scan keeps) and asks for confirmation; one that removes nothing is applied at once. Applying saves the scope and rescans.
   - **Now Playing tags:** set, change or clear each of the 3 tag slots shown on Now Playing, and restore the defaults.
   - **Split delimiters:** the delimiters used by the split button on Track details (§5): add any string, remove, restore the defaults.

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
