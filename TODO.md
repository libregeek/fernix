# Fernix — Implementation Plan

Checklist derived from `spec/architecture.md` (the source of truth; section numbers in brackets).
Items are ticked **only after the maintainer approves them**.

Ground rules for every item: SPDX header on new files, no `INTERNET` permission, no unapproved library or permission,
MediaStore URIs only, DB/tag work on `Dispatchers.IO`, bind SQL arguments (CLAUDE.md).
**Never trade sound quality for performance** (spec §8): no resampling, downmixing, bit-depth reduction or extra audio processing to save CPU, memory, battery or size.
Dependencies and permissions listed below are already approved in the spec (§2, §3) but are added **only in the phase that needs them**.

Phases are ordered so each one ends in something runnable and testable.

---

## Phase 0 — Foundation (scaffold already in the repo, needs sign-off)

- [x] Gradle project, version catalog with pinned versions, wrapper with `distributionSha256Sum` [§10]
- [x] `dependenciesInfo` disabled, R8 enabled for release [§10]
- [x] TagLib submodule (+ utfcpp) pinned; `CMakeLists.txt` builds a static TagLib with only MPEG/Vorbis/RIFF/APE/zlib [§7]
- [x] JNI bridge `tag_reader.cpp` + `TagReader.kt` (fd → flat `String[]` → `Map<String, List<String>>`, no exceptions across JNI) [§7]
- [x] Instrumented `TagReaderTest` (MP3 ID3v2.4, Ogg Vorbis, FLAC, non-audio, invalid fd) passing on API 29 (and API 36)
- [x] Theme (Material 3, dynamic color on 12+, light/dark) and placeholder `MainActivity`
- [x] Verify: `./gradlew assembleDebug` and `assembleRelease` pass; merged manifest has no `INTERNET`

## Phase 1 — App skeleton

- [x] `AppContainer` owned by `FernixApp` (manual DI) [§2, §4]
- [x] `TagValues` codec: join/split on `U+001F` + unit tests [§5]
- [x] Hand-rolled back stack + bottom navigation shell with empty screens [§2, §9]; tab order since 2026-10-24: Tracks, Albums, Folders, Playlists, People (the app starts on Tracks)
- [x] `SharedPreferences` wrapper for settings (Now Playing slots, last split choice) [§2]
- [x] People-tag-keys constant (incl. `PERFORMER:*` matching) + unit tests [§5]

## Phase 2 — Database (Room)

- [x] Add Room + KSP dependencies [§2]
- [x] Entities: `tracks`, `folders`, `albums`, `tag_keys`, `tag_values`, `track_tags`, `key_values`, `people`, `playlists`, `playlist_entries` with the exact indexes/collations/FKs/cascades from §5
- [x] `tracks_fts` (`@Fts4`, external content, `unicode61 remove_diacritics=1`)
- [x] DAOs with keyset paging (`(sortKey, id) > (?, ?) … LIMIT 200`) for tracks, albums, people, folders, key values
- [x] Filter builder with `@RawQuery` + `INTERSECT` (tag, person, FTS), all values bound [§5]
- [x] FTS query builder (quoted prefix terms, bound MATCH expression)
- [x] Value normalisation (trim, drop empty, skip `LYRICS`, `ACOUSTID_FINGERPRINT`, values > 512 chars) + tests [§5]
- [x] Instrumented tests: schema, cascades, album `year = 0` uniqueness, FTS diacritics, paging, filter combos
- [x] Scale check: synthetic 1M-track DB; confirm queries use indexes (`EXPLAIN QUERY PLAN`) and stay responsive

### Default playlists and play history (added 2026-10-24) [§5]

- [x] Schema: `tracks.dateAdded`, `tracks.playCount`, `tracks.lastPlayedAt` (+ indexes), `playlists.kind`; unique index `(playlistId, trackId)` on `playlist_entries` (the migration removes any existing duplicate entries first); Room version bump with a migration (backfill `dateAdded` from MediaStore on the next scan); update schema export and DAO/entity tests
- [x] Create the Favourites row (`kind = 'favourites'`) with the database (new-database callback and the migration); it can't be renamed or deleted (DAO guards)
- [x] Favourites toggle: add or remove a track by id (DAO) + test; `isFavourite` as a `Flow`
- [x] Scan: read `DATE_ADDED` (rows stored before the column get it from a cheap `DateAdded` diff event, no re-parse); a re-parse keeps `playCount` and `lastPlayedAt`
- [x] Queries (keyset-paged where unbounded) + tests: Recently Added (50, `dateAdded DESC`), All Songs, Never Played, Most Played (50, `playCount > 0`), Favourites membership as a `Flow`

## Phase 3 — Storage permission & scanning

- [x] Approved permissions in the manifest: `READ_MEDIA_AUDIO`, `READ_EXTERNAL_STORAGE` (`maxSdkVersion=32`) [§3]
- [x] Runtime permission flow + denied/rationale state
- [x] Scan scope settings: `scan_scope` (`all` | `selected`) and `scan_folders` in `Settings` + tests [§6]
- [x] Library folders screen: in-app chooser (volumes → folder tree, reads only `VOLUME_NAME` + `RELATIVE_PATH`), shown on first launch before the first scan [§6, §9]
- [x] MediaStore discovery cursor ordered by `_ID`, limited to mounted volumes and the scan scope (folder test in code) [§6.1]
- [x] Constant-memory merge-walk diff against `tracks` cursor (new / changed / vanished / out of scope) [§6.2]
- [ ] Unmounted volumes: skip them, keep their tracks (and playlist entries), show as unavailable [§6] — skip/keep logic done; the "unavailable" display waits for the Phase 5 screens
- [x] Folder tree builder from `VOLUME_NAME` + `RELATIVE_PATH` [§5]
- [x] Tag ingestion: `openFileDescriptor` → `TagReader`; derive `trackNumber`, `discNumber`, `year`, album link [§6.3]
- [x] Batched persistence (~500 tracks/transaction), id caches, `track_tags` replace on re-parse, `key_values` and `people` recompute [§6.4]
- [x] Post-scan cleanup (unused folders, albums, tag values) [§6.5]
- [x] Scan runner on `Dispatchers.IO` with progress `StateFlow`; resumable after interruption
- [x] Trigger: first launch after permission + manual rescan action
- [x] Tests: diff logic (JVM), ingestion with fixture files (instrumented), interrupted-scan resume
- [x] Verify on emulator with MP3 (ID3v2.4 multi-value), Ogg Vorbis, Opus, FLAC, WAV fixtures
- [x] Verify with an emulated SD card: its files appear as their own volume, and `MediaStore` `_ID`s don't collide with the primary volume's (if they do, key `tracks` by volume + id and update §5)

## Phase 4 — Playback

- [x] Add Media3 `exoplayer` + `session` [§2]
- [x] Approved permissions/service: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `WAKE_LOCK`; service with `foregroundServiceType="mediaPlayback"` [§3]
- [x] `MediaSessionService` hosting one ExoPlayer (`setWakeMode(WAKE_MODE_LOCAL)`, audio focus, gapless) [§8]
- [x] `MediaItem.mediaId = tracks.id`; queue items carry id, content URI, title/artist/album only
- [x] `MediaController` wrapper in `AppContainer` exposing playback state as `StateFlow`
- [x] Notification / lock-screen controls
- [x] `POST_NOTIFICATIONS` runtime request (API 33+)
- [x] Play / enqueue helpers: play all of a list, uncapped queue
- [x] Equalizer on ExoPlayer audio session id [§8] — engine, controller commands and persistence done; the bottom sheet is Phase 6
- [x] Record plays in the service: when a track plays through to its end (auto-transition or end of queue), set `lastPlayedAt` and `playCount + 1` on `Dispatchers.IO`; skipping or stopping early doesn't count [§5, §8] (added 2026-10-24)
- [ ] Verify: background playback, gapless between tracks, resume after process death

## Phase 5 — Library screens

Each screen: one ViewModel, `StateFlow` UI state, paged `LazyColumn`.

- [x] Persistent mini-player bar → Now Playing / Queue [§9]
- [x] Folders: tree browse (volume roots; skip level when single volume), play/enqueue folder or track
- [x] Albums: list (name + year, year hidden when 0) and album detail by disc/track (art waits for Coil)
- [x] Search (now the Tracks screen, see Phase 5b): FTS box, tag-key picker → value picker (paged, prefix-narrowed), person-filter chip, AND-combination, title-sorted results with roles when person-filtered; drop stale person chip
- [x] People: paged list with track counts; tap → person details (see below), (its "show in Search" action was renamed "Show in Tracks" in Phase 5b and then removed, 2026-10-24)
- [x] Playlists: create / rename / delete; add from any track list; remove entries; detail with Play and Shuffle
- [x] No duplicate tracks in a playlist: adding one already present is a no-op (DAO); the unique index comes with the schema migration above (added 2026-10-24)
- [x] Default playlists on the Playlists tab, above the user's own: Favourites (editable), Recently Added, All Songs (removed in Phase 5b), Never Played, Most Played (read-only: no remove/rename/delete, not offered by *Add to playlist*); detail screen reused with Play and Shuffle [§5, §9] (added 2026-10-24)
- [x] *Add to playlist* picker offers Favourites and user playlists only (added 2026-10-24)
- [x] Track details screen: cover art, all tags (every `track_tags` key/value, multi-values listed), technical details from TagLib `AudioProperties` (format, duration, bitrate, sample rate, channels, bits per sample; read on demand, not stored) [§7, §9], file details (name, folder, volume, size, modified date, content URI); reachable from every track row
- [x] Person details screen: image from an embedded picture typed Artist/Lead artist/Band/Conductor/Composer/Lyricist (matched by role key and description, initials placeholder otherwise) [§7], roles (the tag keys they appear under, e.g. COMPOSER, PERFORMER:*) and the list of all their tracks; reached from the People list and from tappable people values
- [ ] Album details via album name: tapping an album name anywhere (track rows, Now Playing, track details) opens album art, name, year (hidden when 0) and the track list by disc/track — done from Now Playing, track details and the Albums list; the album name on plain track rows isn't tappable (those rows show no album)
- [x] JNI additions: `AudioProperties` call and `PICTURE` complex-properties call (picture type, description, MIME, bytes) in `tag_reader.cpp` / `TagReader.kt`, with instrumented tests on MP3, FLAC, Ogg incl. role-typed pictures [§7] — both done (`TagReader.readPictures` and `readAudioProperties`, tested on MP3/FLAC/Ogg/Opus/WAV)
- [x] Coil (`coil-compose`) album art from embedded art via content URI [§2] — `TrackArt` model + fetcher; used on Now Playing and the album details header; person images still to wire
- [ ] Vector icons copied in as drawables (no `material-icons-extended`), including heart (filled and outline) for Favourites
- [x] Scan progress indicator (a bar and the file count above the mini-player on every screen; Folders, Albums and People reload when a scan finishes)

## Phase 5b — Tracks screen redesign (2026-10-24)

The Search screen becomes **Tracks** [§9.3]; All Songs leaves the playlists [§5, §9.6].

- [x] Rename Search → Tracks: bottom-nav label and a music-note icon instead of the magnifier, screen/back-stack names, strings, README
- [x] Tracks lists **every track** when there is no search text and no filter (title, id; keyset-paged); the tab is the library's full track list
- [x] ✕ button in the search box removes all search terms: the text and every filter chip (person chip included)
- [x] Multiple filters: filters on the **same** tag are ORed, filters on different tags are ANDed, and the text and the person filter are ANDed with the rest [§5 *Filtering*, §9.3]; `TrackQueryBuilder` builds one `keyId = ? AND valueId IN (…)` subquery per key; update its unit tests and add one for same-key OR, different-key AND, plus text and person together
- [x] **Add to playlist** on the result list: every track matching the current search and filters (all pages, not only the loaded rows), into an existing playlist or a new one made from them (the existing picker); tracks already in a playlist are skipped
- [x] Thumbnail of the album art on every row of the Albums list and of the Tracks list (`AlbumArt` model; a track with no album uses its own picture); lazy, placeholder meanwhile; served from a small on-disk thumbnail cache in `cacheDir/thumbnails/` (JPEG, at most 160 px, keyed by album, capped at 20 MB, oldest deleted first, cleared after a scan that changed files) plus Coil's memory cache; `TrackRow` gains `albumId` so a row can ask for its album's art [§7 *Thumbnails*, §9.3, §9.5]
- [x] Remove **All Songs** from the Playlists tab and its detail screen (`DefaultPlaylists`, strings); four defaults remain: Favourites, Recently Added, Never Played, Most Played; README says four (done with the README line below)
- [x] Thumbnail cache: write/read/evict test (cap respected, cleared after a changing scan, a missing file is regenerated; a half-written file is never visible, since it is written to a temporary file and renamed) [§7 *Thumbnails*]
- [ ] Check scrolling the full track list with thumbnails on a large library (smoothness, memory); on the emulator the lists show and cache thumbnails (4 albums, 762 bytes to 11 KB each) — a real 16K-track library is still to be tried
- [x] Tests: empty filter lists all tracks (paging across pages), ✕ clears text and chips, add-all-results to a new and to an existing playlist (more results than one page), All Songs gone

### Mini-player, Track details and tag pills (2026-10-24, from review)

- [x] Mini-player: three lines like a Tracks row (title; album with its date in brackets; artists, smaller), from the playing track's database row
- [x] Track details: cover, title, album in bold (smaller than the title) with the date in brackets, then Composer, Lyricist and Artists, then **Other tags** (renamed from Tags: everything except those and title/album/date)
- [x] Every tag value is tappable: a person's opens the person details, any other value opens Tracks with the value as a filter (`BackStack` entries gain a second argument); the split button follows a splittable value (shown as pills at first, replaced by plain accent-coloured text, see below)
- [x] Track details made compact (2026-10-24, from review): no pills; the title and the album with its date are centred; a small cover; Composer, Lyricist, Artists, Other tags, Technical and File are all the same slim label/values rows
- [x] Now Playing: tapping a non-person tag value opens Tracks filtered by it too

- [x] First launch: after the scope choice the app shows the Tracks screen, and its list reloads while the first scan runs and when it finishes (as Folders, Albums and People already do)

- [x] Person details: the same three icons as on Tracks (shared `ListActions` capsule) instead of the Play / Add to queue buttons; "Show in Tracks" removed

- [x] Person details: roles as small transparent pills with a thin border and an 11 sp font (first tried at 9 sp, too small), in the roles list and on every track row (`RolePill`); track rows also show the album with its date in brackets

### Tracks screen layout (2026-10-24, from review)

- [x] Search box and filters in one bordered container with a labelled **Reset** (replaces the ✕ inside the search box, which didn't say it also reset the filters)
- [x] Add filter is an icon, and the chips wrap onto more lines (long chips cut with an ellipsis) so it is never pushed out of view
- [x] Play, Add to queue and Add to playlist are icons
- [x] Track row: title; then album with its date in brackets and the artists, both smaller (`TrackRow` gains `date`)
- [x] Reset moved to the bottom of the container and made an icon (add-filter icon on its left), so the search box has the full width; the search box is a slim 40 dp pill (`CompactSearchField`)
- [x] The three result actions form one tonal capsule; **Add to playlist** is reworked as a bottom sheet (`AddToPlaylistSheet`: highlighted New playlist row with an inline name field, Favourites and playlists as rounded tiles with counts, a toast on success) instead of a plain dialog
- [ ] The same icon actions on the album and playlist detail screens (they still have text buttons; person details now has them) — only if wanted

## Phase 6 — Now Playing & Queue

- [x] Now Playing: art, title, album, date, seek bar, transport, shuffle/repeat [§9.1]
- [x] "Tags on the playing track" query as Room `Flow`; 3 configurable slots (defaults `COMPOSER`, `LYRICIST`, `ARTIST`); `PERFORMER` slot shows instrument label; hide missing tags
- [x] Favourite heart on Now Playing: one tap adds the playing track to Favourites, another removes it; filled state from a Room `Flow` [§9.1] (added 2026-10-24)
- [x] Tappable values → Tracks (formerly Search) pushed on back stack (person filter for people keys, tag filter otherwise); the back stack entry takes a second argument (the tag key id) for the tag filter
- [x] Equalizer bottom sheet from top app bar
- [x] Queue screen: drag-reorder, remove, jump to item

## Phase 7 — Tag splitting

Reworked on 2026-10-24: one value at a time from Track details, with delimiters set in Settings (no bulk dialog from track lists), and no stored rules (a split is a plain database edit; rescans read only changed files, so splits persist for unchanged tracks; no undo).

- [x] Delimiters in Settings: any number, add / remove / restore defaults, spaces kept [§5, §9]
- [x] Split button next to every tag value on Track details that holds a delimiter (never `TITLE`/`ALBUM`/`DATE`/`TRACKNUMBER`/`DISCNUMBER`); confirmation shows the parts and the library-wide track count
- [x] Split: rewrite `track_tags` + fixed column in every track carrying the value, delete the unused value, refresh `key_values`/`people`; the tags on screen update by themselves
- [x] Schema version 3 drops `split_rules` and `split_rule_tracks` (migration + test)
- [x] Tests: split dedupe/order/trim, `AC/DC` left whole while `/` isn't a delimiter, the split button's ask/confirm/cancel

## Phase 8 — Settings

- [x] Settings screen reached from a gear icon in the library title bar, one tap (a title bar with a gear on the five library screens; Settings has Library folders with Rescan, the Now Playing tags and the split delimiters)
- [x] Library folders: change scope with the chooser; confirm before narrowing (count of tracks and playlist entries removed) [§6, §9]
- [x] Now Playing tag slots: set / clear / restore defaults (any `tag_keys` except `TITLE`, `ALBUM`, `DATE`)
- [x] Split delimiters section (see Phase 7)

## Phase 9 — Hardening & release

- [ ] Process-death / config-change handling in all ViewModels
- [ ] Error handling: unreadable files, files removed mid-scan, permission revoked
- [ ] Performance pass on large library (startup, first-scan time, scroll, memory)
- [ ] Audio-path check: output is unchanged from the source (no resampling, downmix or bit-depth reduction; equalizer off adds nothing); verify before and after every performance change [§8]
- [ ] Accessibility: content descriptions, touch targets, font scaling
- [ ] `./gradlew lint` clean
- [ ] Test matrix on AOSP emulator, API 29 and latest API
- [ ] Merged-manifest check: no `INTERNET`; dependency tree has no Google/proprietary artifacts [CLAUDE.md rules 3–4]
- [ ] R8 release build checked (JNI classes kept in `proguard-rules.pro`)
- [ ] Confirm APK size is reasonable; drop anything unused
- [ ] README updated for user-visible features, permissions, formats (include the default playlists and that play history stays on the device)
- [ ] F-Droid metadata (fastlane / metadata yml), changelog, app icon polish, tag `v0.1.0`
