# Fernix

**A lightweight, offline music player for Android — 100% free and open source.**

Fernix plays the music stored on your phone. It never connects to the internet, needs no account, and works without Google Play Services.

> **Status:** in early development — not yet released.

## Features

- **Plays your local music:** MP3, Ogg Vorbis, Opus, FLAC and WAV, with gapless playback.
- **Reads your tags properly:** full support for ID3v2.4 and Vorbis comments, including tags with several values, such as a track with two artists.
- **Splits combined values when you ask:** in any track list, tap *Split tags* to turn a value such as `Simon; Garfunkel` or `Rock/Pop` into separate entries. You choose the separators (`;`, `/`, `,` or your own) and which values to split, so names like `AC/DC` stay whole. Fernix remembers your choice, never changes your files, and lets you undo it in Settings.
- **Handles large libraries:** built to stay fast with hundreds of thousands of tracks.
- **Filter by any tag:** besides artist, album and genre, you can filter on any tag in your files (BPM, label, release type, MusicBrainz IDs, your own custom tags…).
- **Simple screens:**
  - **Now Playing:** album art, playback controls, shuffle/repeat, and the track's title, album and date, plus three tags of your choice — composers, lyricists and artists by default; swap any of them for others such as conductor, genre or BPM in Settings. Tap a name to see every track that person worked on, or any other value to see all tracks that share it.
  - **Folders:** browse your music the way it's stored on your device
  - **Search:** search, and combine tag filters
  - **Queue:** see, reorder and edit what's playing next
  - **Albums:** albums grouped by name and year
  - **Playlists:** create and manage your own playlists; play them in order or shuffled
  - **People:** everyone credited on your tracks — artists, composers, lyricists, conductors, performers and more. Tap a name to see all their tracks.
- **Background playback:** controls in the notification and on the lock screen.
- **Built-in equalizer:** open it from the Now Playing screen.

## Privacy

- **No internet access.** Fernix does not request the internet permission, so Android itself blocks it from going online.
- No ads, tracking, analytics or crash reporting.
- No Google services. It works the same on LineageOS, GrapheneOS, CalyxOS and other de-Googled systems.

## Requirements

Android 10 or newer.

## Permissions

| Permission | Why Fernix needs it |
|---|---|
| Music and audio (storage on Android 10–12) | To find and play the music on your device. |
| Notifications (Android 13+) | To show playback controls in the notification. |
| Run in the background (foreground service) | To keep playing when you leave the app. |
| Prevent phone from sleeping | To keep the music from stopping when the screen turns off. |

## Installation

Fernix will be published on [F-Droid](https://f-droid.org/). Until then, it can be built from source; see [docs/DEVELOPMENT_SETUP_FEDORA.md](docs/DEVELOPMENT_SETUP_FEDORA.md).

## Reporting issues

Please report bugs and feature requests at <https://github.com/libregeek/fernix/issues>.

## License

Fernix is free software, licensed under the [GNU General Public License v3.0 or later](LICENSE).
