# Fernix — Implementation Plan

Checklist derived from `spec/architecture.md` (the source of truth; section numbers in brackets).
Items are ticked **only after the maintainer approves them**.

Ground rules for every item: SPDX header on new files, no `INTERNET` permission, no unapproved library or permission,
MediaStore URIs only, DB/tag work on `Dispatchers.IO`, bind SQL arguments (CLAUDE.md).
Dependencies and permissions listed below are already approved in the spec (§2, §3) but are added **only in the phase that needs them**.

Phases are ordered so each one ends in something runnable and testable.

---

## Phase 0 — Foundation (scaffold already in the repo, needs sign-off)

- [ ] Gradle project, version catalog with pinned versions, wrapper with `distributionSha256Sum` [§10]
- [ ] `dependenciesInfo` disabled, R8 enabled for release [§10]
- [ ] TagLib submodule (+ utfcpp) pinned; `CMakeLists.txt` builds a static TagLib with only MPEG/Vorbis/RIFF/APE/zlib [§7]
- [ ] JNI bridge `tag_reader.cpp` + `TagReader.kt` (fd → flat `String[]` → `Map<String, List<String>>`, no exceptions across JNI) [§7]
- [ ] Instrumented `TagReaderTest` (MP3 ID3v2.4, Ogg Vorbis, FLAC, non-audio, invalid fd) passing on API 29 (and API 36)
- [ ] Theme (Material 3, dynamic color on 12+, light/dark) and placeholder `MainActivity`
- [ ] Verify: `./gradlew assembleDebug` and `assembleRelease` pass; merged manifest has no `INTERNET`
- [ ] Add missing deps to the catalog/Gradle as phases need them: `ksp` plugin applied, Room, Media3, Coil, coroutines, lifecycle-viewmodel-compose, `room-testing` [§2]

## Phase 1 — App skeleton

- [ ] `AppContainer` owned by `FernixApp` (manual DI) [§2, §4]
- [ ] `TagValues` codec: join/split on `U+001F` + unit tests [§5]
- [ ] Hand-rolled back stack + bottom navigation shell (Folders, Search, Albums, Playlists, People) with empty screens [§2, §9]
- [ ] `SharedPreferences` wrapper for settings (Now Playing slots, last split choice) [§2]
- [ ] People-tag-keys constant (incl. `PERFORMER:*` matching) + unit tests [§5]

## Phase 2 — Database (Room)

- [ ] Add Room + KSP dependencies [§2]
- [ ] Entities: `tracks`, `folders`, `albums`, `tag_keys`, `tag_values`, `track_tags`, `key_values`, `people`, `split_rules`, `split_rule_tracks`, `playlists`, `playlist_entries` with the exact indexes/collations/FKs/cascades from §5
- [ ] `tracks_fts` (`@Fts4`, external content, `unicode61 remove_diacritics=1`)
- [ ] DAOs with keyset paging (`(sortKey, id) > (?, ?) … LIMIT 200`) for tracks, albums, people, folders, key values
- [ ] Filter builder with `@RawQuery` + `INTERSECT` (tag, person, FTS), all values bound [§5]
- [ ] FTS query builder (quoted prefix terms, bound MATCH expression)
- [ ] Value normalisation (trim, drop empty, skip `LYRICS`, `ACOUSTID_FINGERPRINT`, values > 512 chars) + tests [§5]
- [ ] Instrumented tests: schema, cascades, album `year = 0` uniqueness, FTS diacritics, paging, filter combos
- [ ] Scale check: synthetic 1M-track DB; confirm queries use indexes (`EXPLAIN QUERY PLAN`) and stay responsive

## Phase 3 — Storage permission & scanning

- [ ] Approved permissions in the manifest: `READ_MEDIA_AUDIO`, `READ_EXTERNAL_STORAGE` (`maxSdkVersion=32`) [§3]
- [ ] Runtime permission flow + denied/rationale state
- [ ] MediaStore discovery cursor ordered by `_ID` [§6.1]
- [ ] Constant-memory merge-walk diff against `tracks` cursor (new / changed / vanished) [§6.2]
- [ ] Folder tree builder from `VOLUME_NAME` + `RELATIVE_PATH` [§5]
- [ ] Tag ingestion: `openFileDescriptor` → `TagReader`; derive `trackNumber`, `discNumber`, `year`, album link [§6.3]
- [ ] Batched persistence (~500 tracks/transaction), id caches, `track_tags` replace on re-parse, `key_values` and `people` recompute [§6.4]
- [ ] Post-scan cleanup (unused folders, albums, tag values) [§6.5]
- [ ] Scan runner on `Dispatchers.IO` with progress `StateFlow`; resumable after interruption
- [ ] Trigger: first launch after permission + manual rescan action
- [ ] Tests: diff logic (JVM), ingestion with fixture files (instrumented), interrupted-scan resume
- [ ] Verify on emulator with MP3 (ID3v2.4 multi-value), Ogg Vorbis, Opus, FLAC, WAV fixtures

## Phase 4 — Playback

- [ ] Add Media3 `exoplayer` + `session` [§2]
- [ ] Approved permissions/service: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `WAKE_LOCK`; service with `foregroundServiceType="mediaPlayback"` [§3]
- [ ] `MediaSessionService` hosting one ExoPlayer (`setWakeMode(WAKE_MODE_LOCAL)`, audio focus, gapless) [§8]
- [ ] `MediaItem.mediaId = tracks.id`; queue items carry id, content URI, title/artist/album only
- [ ] `MediaController` wrapper in `AppContainer` exposing playback state as `StateFlow`
- [ ] Notification / lock-screen controls
- [ ] `POST_NOTIFICATIONS` runtime request (API 33+)
- [ ] Play / enqueue helpers: play all of a list, uncapped queue
- [ ] Equalizer on ExoPlayer audio session id [§8]
- [ ] Verify: background playback, gapless between tracks, resume after process death

## Phase 5 — Library screens

Each screen: one ViewModel, `StateFlow` UI state, paged `LazyColumn`.

- [ ] Persistent mini-player bar → Now Playing / Queue [§9]
- [ ] Folders: tree browse (volume roots; skip level when single volume), play/enqueue folder or track
- [ ] Albums: list (name + year, year hidden when 0) and album detail by disc/track
- [ ] Search: FTS box, tag-key picker → value picker (paged, prefix-narrowed), person-filter chip, AND-combination, title-sorted results with roles when person-filtered; drop stale person chip
- [ ] People: paged list with track counts; tap → Search with person filter
- [ ] Playlists: create / rename / delete; add from any track list; remove entries; detail with Play and Shuffle
- [ ] Coil (`coil-compose`) album art from embedded/local art via content URI [§2]
- [ ] Vector icons copied in as drawables (no `material-icons-extended`)
- [ ] Scan progress indicator

## Phase 6 — Now Playing & Queue

- [ ] Now Playing: art, title, album, date, seek bar, transport, shuffle/repeat [§9.1]
- [ ] "Tags on the playing track" query as Room `Flow`; 3 configurable slots (defaults `COMPOSER`, `LYRICIST`, `ARTIST`); `PERFORMER` slot shows instrument label; hide missing tags
- [ ] Tappable values → Search pushed on back stack (person filter for people keys, tag filter otherwise)
- [ ] Equalizer bottom sheet from top app bar
- [ ] Queue screen: drag-reorder, remove, jump to item

## Phase 7 — Tag splitting

- [ ] Split dialog: separators (`;`, `/`, `,`, custom), tag selection (default people keys + `GENRE`; `TITLE`/`ALBUM`/`DATE` excluded), choice remembered [§5]
- [ ] Preview of distinct (tag, value) with parts and library-wide track count; per-row checkbox
- [ ] Apply: create `split_rules`, rewrite `track_tags` + fixed column library-wide, record `split_rule_tracks`, delete unused values, refresh `key_values`/`people`; background with progress
- [ ] Apply rules during scan ingestion
- [ ] "Split tags" in overflow menu of every track list (Search, album, folder, playlist)
- [ ] Tests: split dedupe/order/trim, rule survives rescan, `AC/DC` left whole

## Phase 8 — Settings

- [ ] Settings screen reached from the library top-bar overflow menu
- [ ] Now Playing tag slots: set / clear / restore defaults (any `tag_keys` except `TITLE`, `ALBUM`, `DATE`)
- [ ] Split rules list (paged); delete re-reads affected files to undo the split

## Phase 9 — Hardening & release

- [ ] Process-death / config-change handling in all ViewModels
- [ ] Error handling: unreadable files, files removed mid-scan, permission revoked
- [ ] Performance pass on large library (startup, first-scan time, scroll, memory)
- [ ] Accessibility: content descriptions, touch targets, font scaling
- [ ] `./gradlew lint` clean
- [ ] Test matrix on AOSP emulator, API 29 and latest API
- [ ] Merged-manifest check: no `INTERNET`; dependency tree has no Google/proprietary artifacts [CLAUDE.md rules 3–4]
- [ ] R8 release build checked (JNI classes kept in `proguard-rules.pro`)
- [ ] Confirm APK size is reasonable; drop anything unused
- [ ] README updated for user-visible features, permissions, formats
- [ ] F-Droid metadata (fastlane / metadata yml), changelog, app icon polish, tag `v0.1.0`
