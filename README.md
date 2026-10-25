# Fernix

**A lightweight, offline music player for Android — 100% free and open source.**

Fernix plays the music stored on your phone. It never connects to the internet, needs no account, and works without Google Play Services.

> **Status:** in early development — not yet released.

## Features

- **Plays your local music:** MP3, Ogg Vorbis, Opus, FLAC, WAV and AAC (M4A), with gapless playback.
- **Reads your tags properly:** full support for ID3v2.4 and Vorbis comments, including tags with several values, such as a track with two artists.
- **Splits combined values one at a time:** in Settings you choose the delimiters (`;`, `/`, `,` or your own, as many as you like). On a track's details page, a value such as `Simon; Garfunkel` that contains one of them gets a split button; tap it to turn the value into separate entries, for every track that has it. Nothing is split unless you ask, and Fernix never changes your files.
- **You choose what is scanned:** only the folders you add (on the phone or an SD card), as many as you like. Fernix never browses the rest of your device, and you can add or remove folders any time in Settings.
- **Handles large libraries:** built to stay fast with hundreds of thousands of tracks.
- **Filter by any tag:** besides artist, album and genre, you can filter on any tag in your files (BPM, label, release type, MusicBrainz IDs, your own custom tags…).
- **Problem files:** if a file can't be read or played, Fernix skips it, keeps the rest working, and lists it in Settings → Problem files for 30 days. The list stays on your device.
- **Themes:** the green Fernix theme with a colourful gradient (the default), a Dark theme, or System, which uses Android's own Material colours and follows your phone's light or dark setting. Choose in Settings.
- **Simple screens:**
  - **Now Playing:** album art, playback controls, shuffle/repeat, and the track's title, album and date, plus three tags of your choice — composers, lyricists and artists by default; swap any of them for others such as conductor, genre or BPM in Settings. Tap a name to see every track that person worked on, or any other value to see all tracks that share it.
  - **Tracks:** every track in your library. Tap the search icon to type a search, and add filters on any tag (several values of the same tag mean "either"; different tags must all match). Tap the search icon at the top to search; the ✕ clears the text and the reset button next to the filters clears everything. Sort the list by up to four fields, each ascending or descending (for example year, then album, then track number, or by title, duration or the date added); tracks with an unknown value come last, and your choice is remembered. Then play the result, queue it, or add it to a playlist, including a new one. Play and queue follow the sort order.
  - **Queue:** see, reorder and edit what's playing next
  - **Albums:** albums grouped by name and year, each with its cover; tap the search icon to find one by name
  - **Folders:** browse your music the way it's stored on your device
  - **Playlists:** Fernix keeps four playlists for you, and you can make your own.
    - **Favourites:** tap the heart on the Now Playing screen to add the song you're listening to, and tap it again to remove it. You can also add and remove songs from Favourites in any list.
    - **Recently Added:** your 50 newest songs.
    - **Never Played:** songs you haven't listened to all the way through yet.
    - **Most Played:** your 50 most-played songs.
    - **Your own playlists:** create, rename and delete them, add songs from any list, and remove songs again. A song can be in a playlist only once. Play a playlist in order or shuffled.

    Only Favourites and your own playlists can be changed; the other three update themselves. Fernix remembers when you last played each song, on your device only, to keep them up to date.
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
| Notifications (Android 13+) | To show playback controls, and the progress of a library scan, in notifications. |
| Run in the background (foreground service) | To keep playing, and to finish scanning a large library, when you leave the app. |
| Prevent phone from sleeping | To keep the music from stopping when the screen turns off. |

## Installation

Fernix will be published on [F-Droid](https://f-droid.org/). Until then, it can be built from source; see [docs/DEVELOPMENT_SETUP_FEDORA.md](docs/DEVELOPMENT_SETUP_FEDORA.md).

## Reporting issues

Please report bugs and feature requests at <https://github.com/libregeek/fernix/issues>.

## License

Fernix is free software, licensed under the [GNU General Public License v3.0 or later](LICENSE).
