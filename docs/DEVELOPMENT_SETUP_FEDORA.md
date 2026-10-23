# Development Environment Setup — Fedora 44

This guide sets up everything needed to build, run, and test **Fernix** on Fedora 44 (x86_64).

Two paths are described; both share sections 1–3:

- **Path A — Android Studio** (easiest; full IDE, layout inspector, emulator UI).
- **Path B — Command line + FOSS editor** (no Android Studio; VSCodium or IntelliJ IDEA Community).

> The SDK, NDK, and CMake versions below match those pinned in the project build files
> (`app/build.gradle.kts`, `gradle/libs.versions.toml`). If they change there, use the
> values from those files.

---

## 1. System packages

```bash
sudo dnf install -y git unzip which java-25-openjdk-devel android-tools
```

| Package | Why |
|---|---|
| `git` | Source control; TagLib is a git submodule. |
| `unzip`, `which` | Needed by the SDK command-line tools and Gradle scripts. |
| `java-25-openjdk-devel` | JDK to run Gradle. Fedora 44 ships JDK 25 (the project uses Gradle ≥ 9.1, which supports it). Android Studio bundles its own JDK, so Path A users may skip it. |
| `android-tools` | System `adb`/`fastboot` and udev rules so physical devices are accessible without root. (The SDK also ships its own `adb`; either works — keep them on the same major version to avoid "adb server version mismatch".) |

Verify:

```bash
java -version      # openjdk 25...
git --version
```

Do **not** rely on Fedora's system `cmake`/`ninja` for the native build — the Android Gradle Plugin uses the CMake installed through the SDK (section 4), which keeps builds reproducible.

## 2. Emulator hardware acceleration (KVM) — check only

Needed **only if you use the emulator** (not for a physical device). The Linux emulator cannot run x86_64 system images without KVM.

On Fedora this normally works out of the box: the `kvm_intel`/`kvm_amd` module loads automatically and `/dev/kvm` is world-accessible (`crw-rw-rw-`). Quick check:

```bash
ls -l /dev/kvm            # exists and shows rw for others → nothing to do
```

Only if something is wrong:

- `/dev/kvm` missing → enable virtualization (Intel VT-x / AMD-V, sometimes called SVM) in the firmware/BIOS settings.
- Emulator reports "KVM permission denied" (e.g. a hardened system with stricter permissions) → `sudo usermod -aG kvm "$USER"`, then log out and back in.

## 3. Shared Android SDK (default for all users)

All users share **one SDK in `/opt/android-sdk`**: it is downloaded once and saves ~5–10 GB per user. Members of the `androiddev` group can install and update SDK packages. A user who needs a separate SDK can opt out (section 3.4).

### 3.1 Create the shared SDK directory (admin, once)

```bash
sudo groupadd -f androiddev
sudo usermod -aG androiddev <username>     # repeat for each developer; they must log out/in

sudo mkdir -p /opt/android-sdk
sudo chown root:androiddev /opt/android-sdk
sudo chmod 2775 /opt/android-sdk           # setgid: new files inherit the androiddev group
# Make everything any member creates group-writable (regardless of their umask) and readable by others:
sudo setfacl -R -m g:androiddev:rwX -m d:g:androiddev:rwX -m o::rX -m d:o::rX /opt/android-sdk
```

### 3.2 Environment for all users (admin, once)

```bash
sudo tee /etc/profile.d/android-sdk.sh >/dev/null <<'EOF'
# Android SDK: shared SDK by default; a personal SDK in ~/Android/Sdk takes precedence if it exists.
if [ -d "$HOME/Android/Sdk" ]; then
    ANDROID_HOME="$HOME/Android/Sdk"
else
    ANDROID_HOME="/opt/android-sdk"
fi
export ANDROID_HOME
export PATH="/opt/android-studio/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
EOF
```

Takes effect at the next login (or `source /etc/profile.d/android-sdk.sh` in an open terminal).

### 3.3 Install the command-line tools into the shared SDK (one `androiddev` member, once)

Download **Command line tools only** for Linux from <https://developer.android.com/studio#command-tools>, then:

```bash
mkdir -p /opt/android-sdk/cmdline-tools
unzip ~/Downloads/commandlinetools-linux-*_latest.zip -d /opt/android-sdk/cmdline-tools
mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest
sdkmanager --version
```

Then install the SDK components from section 4 — also once, by one member; licences accepted there apply to everyone.

Notes for the shared SDK:

- Coordinate updates: two users running `sdkmanager` / Studio's SDK Manager at the same time can corrupt a package install.
- Users not in `androiddev` can build with the installed packages but cannot install new ones (Gradle will fail if the project needs a package that isn't installed yet).
- Emulator AVDs and Studio settings stay per-user in the home directory.

### 3.4 Opting out: personal SDK (per user, only if required)

For example, to test a different NDK or platform without affecting others:

```bash
mkdir -p ~/Android/Sdk/cmdline-tools
unzip ~/Downloads/commandlinetools-linux-*_latest.zip -d ~/Android/Sdk/cmdline-tools
mv ~/Android/Sdk/cmdline-tools/cmdline-tools ~/Android/Sdk/cmdline-tools/latest
```

Log out and back in: because `~/Android/Sdk` now exists, `/etc/profile.d/android-sdk.sh` uses it for this user. Install the section 4 components into it, and in Android Studio set *Settings → Languages & Frameworks → Android SDK → Android SDK Location* to `~/Android/Sdk`. Existing project checkouts keep the old path in `local.properties` (`sdk.dir=…`) — update or delete that file.

To go back to the shared SDK, remove (or rename) `~/Android/Sdk` and log in again.

---

## Path A — Android Studio (installed in `/opt` for all users)

Android Studio is installed once, system-wide, by an administrator. Each user still gets their own settings, plugins and caches (`~/.config/Google/AndroidStudio*`, `~/.cache/Google/`, `~/.local/share/Google/`) and AVDs (`~/.android/avd`); everyone uses the shared SDK from section 3.

> Fedora's default Flathub remote is filtered and the Flatpak sandbox complicates `adb`/emulator access, so the official tarball is used.

### A1. Install into `/opt` (admin, once)

Download the Linux `.tar.gz` from <https://developer.android.com/studio>, then:

```bash
sudo tar --no-same-owner -xzf ~/Downloads/android-studio-*-linux.tar.gz -C /opt
sudo chown -R root:root /opt/android-studio
sudo chmod -R a+rX,go-w /opt/android-studio
sudo restorecon -R /opt/android-studio      # correct SELinux labels
```

This creates `/opt/android-studio`, owned by root and readable/executable by everyone.

### A2. Launch from the terminal

`/opt/android-studio/bin` is already on every user's `PATH` via `/etc/profile.d/android-sdk.sh` (section 3.2), so after logging in again:

```bash
studio.sh &
```

### A3. Optional: desktop menu entry for all users

Skip this if you start Android Studio from the terminal.

```bash
sudo tee /usr/share/applications/android-studio.desktop >/dev/null <<'EOF'
[Desktop Entry]
Version=1.0
Type=Application
Name=Android Studio
GenericName=Android IDE
Comment=Develop Android applications
Exec=/opt/android-studio/bin/studio.sh %f
Icon=/opt/android-studio/bin/studio.svg
Categories=Development;IDE;
Terminal=false
StartupNotify=true
StartupWMClass=jetbrains-studio
EOF

sudo desktop-file-validate /usr/share/applications/android-studio.desktop
sudo update-desktop-database /usr/share/applications
```

Android Studio now appears in the GNOME/KDE application menu for every user (log out and back in if it doesn't show up immediately). Users should **not** use *Tools → Create Desktop Entry* inside Studio — that would create a duplicate per-user entry.

### A4. First launch (each user)

1. Start Android Studio: `studio.sh &` (or from the application menu if you did A3).
2. In the setup wizard choose **Custom** and set the SDK location to **`/opt/android-sdk`**. (Do *not* accept the default `~/Android/Sdk` — that would download a personal SDK and opt you out, see 3.4.)
3. Missing components can be added via *Settings → Languages & Frameworks → Android SDK* (tick *Show Package Details* for exact NDK/CMake versions) — `androiddev` members only, and coordinate with others (section 3.3).
4. *File → Open* the `fernix` directory. Studio writes `sdk.dir=/opt/android-sdk` to the project's untracked `local.properties`.

### A5. Updating Android Studio (admin)

Because `/opt/android-studio` is root-owned, the in-app updater cannot patch it — users will see update notifications but cannot apply them. To update:

```bash
# Ask users to close Android Studio first
sudo mv /opt/android-studio /opt/android-studio.old
sudo tar --no-same-owner -xzf ~/Downloads/android-studio-*-linux.tar.gz -C /opt
sudo chown -R root:root /opt/android-studio
sudo chmod -R a+rX,go-w /opt/android-studio
sudo restorecon -R /opt/android-studio
# after checking the new version starts:
sudo rm -rf /opt/android-studio.old
```

`studio.sh` (and the desktop entry, if created) keeps working since the path is unchanged. On first start after a major update, each user is offered to import settings from the previous version.

## Path B — Command line + FOSS editor

The shared SDK from section 3 (with the components from section 4) is all that's needed to build with `./gradlew`. Editor (pick one):

- **IntelliJ IDEA Community** (Apache 2.0; best Kotlin/Gradle support, includes the Android plugin): `flatpak install flathub com.jetbrains.IntelliJ-IDEA-Community` or the tarball from jetbrains.com. Open the project and point it at `/opt/android-sdk`. (The Flatpak sandbox may need access to `/opt`: `flatpak override --user --filesystem=/opt/android-sdk com.jetbrains.IntelliJ-IDEA-Community`.)
- **VSCodium** (MIT): `flatpak install flathub com.vscodium.codium`, then install the Kotlin language server extension from Open VSX. Build and run with Gradle in the terminal.

---

## 4. SDK components

> **`sdkmanager` deprecation warning.** Recent command-line tools (22.0+) print *"The SDK Manager CLI tool (sdkmanager) is deprecated. Use Android CLI instead."* `sdkmanager` and `avdmanager` still work and are what this guide uses. The replacement `android` binary downloads itself (~90 MB) into `~/.android/` on first run, checks for updates online, and **collects usage metrics by default**. If you use it, always pass `--no-metrics` (e.g. `android --no-metrics sdk install …`).

Run as an `androiddev` member (once, into the shared SDK) — or as yourself if you opted for a personal SDK (3.4). Accept licences, then install (replace placeholders with the versions pinned in the project):

```bash
yes | sdkmanager --licenses

sdkmanager \
  "platform-tools" \
  "platforms;android-37.0" \
  "build-tools;36.0.0" \
  "ndk;28.2.13676358" \
  "cmake;3.31.6" \
  "emulator"
```

Emulator system images — use **AOSP `default`** images (no Google APIs / Play Store) so testing matches de-Googled ROMs. Test the minimum API (29) and the newest API with an AOSP image (36 — there is no AOSP `default` image for API 37 yet; test API 37 on a real device):

```bash
sdkmanager \
  "system-images;android-29;default;x86_64" \
  "system-images;android-36;default;x86_64"
```

## 5. Create emulators

```bash
avdmanager create avd -n fernix-api29  -k "system-images;android-29;default;x86_64"
avdmanager create avd -n fernix-api36  -k "system-images;android-36;default;x86_64"

emulator -avd fernix-api36 &
adb devices
```

To get test music onto the emulator:

```bash
adb push ~/Music/test-album /sdcard/Music/
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/test-album
```

(That broadcast is deprecated and ignored on some newer Android versions. If files don't appear in MediaStore, reboot the emulator with `adb reboot` — the media scanner runs on boot.)

### Emulator with an SD card

To test an SD card (a second MediaStore volume), create an AVD with an emulated card. It can run next to the first AVD on another port:

```bash
avdmanager create avd -n fernix-api29-sd -k "system-images;android-29;default;x86_64" --sdcard 512M
# (the "Could not load devices from ... devices.xml" messages are harmless)

emulator -avd fernix-api29-sd -port 5556 &
adb -s emulator-5556 shell sm list-volumes     # a "public:..." volume appears once the card is mounted
```

Put music on the card with `adb -s emulator-5556 push song.mp3 /storage/<card-id>/Music/`, where `<card-id>` is the id shown under `/storage` (`adb shell ls /storage`). Unmount and remount it with `adb shell sm unmount public:<major>,<minor>` and `adb shell sm mount public:<major>,<minor>` to test the unmounted-volume behaviour.

## 6. Physical device

1. On the phone: *Settings → About phone →* tap *Build number* 7× → *Developer options → USB debugging* on.
2. Connect via USB, accept the RSA prompt on the phone.
3. `adb devices` should list it as `device` (not `unauthorized`). If it shows `no permissions`, ensure `android-tools` is installed (udev rules), unplug/replug, and check you are in the active local session.

Wireless alternative (Android 11+): *Developer options → Wireless debugging → Pair device with pairing code*, then `adb pair <ip>:<port>` and `adb connect <ip>:<port>`.

## 7. Get the source and build

```bash
git clone --recurse-submodules https://github.com/libregeek/fernix.git
cd fernix
# if cloned without submodules:
git submodule update --init --recursive

./gradlew assembleDebug      # APK in app/build/outputs/apk/debug/
./gradlew installDebug       # install on the running emulator/device
./gradlew test lint          # unit tests + lint
```

The first build downloads Gradle and dependencies and compiles TagLib for every ABI; expect several minutes.

## 8. Verify FOSS / offline guarantees

After any dependency change, confirm no network permission slipped in through a library:

```bash
./gradlew :app:processDebugMainManifest
grep -R "<uses-permission[^>]*android.permission.INTERNET" app/build/intermediates/merged_manifest/ && echo "INTERNET FOUND — FIX" || echo "OK: no INTERNET permission"
```

And inspect the dependency tree for anything unexpected:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

## 9. Troubleshooting

| Symptom | Fix |
|---|---|
| `Unsupported class file major version` / Gradle fails to start on JDK 25 | Make sure the wrapper is Gradle ≥ 9.1 (`./gradlew --version`), or run Gradle with Android Studio's bundled JDK: `export JAVA_HOME=/opt/android-studio/jbr`. |
| `NDK not configured` / `CMake '<x>' was not found` | Install the exact `ndk;…` and `cmake;…` versions from `app/build.gradle.kts` with `sdkmanager`. |
| TagLib sources missing (`add_subdirectory given source … which is not an existing directory`) | `git submodule update --init --recursive` |
| Emulator: `KVM permission denied` | Section 2. |
| `Permission denied` / `Failed to install` under `/opt/android-sdk` | You are not in `androiddev` (or haven't logged in again since being added): check with `id -nG`. If files were created before the ACLs were set: `sudo setfacl -R -m g:androiddev:rwX -m d:g:androiddev:rwX /opt/android-sdk`. |
| Wrong SDK used (shared vs personal) | `echo $ANDROID_HOME`; check `sdk.dir` in the project's `local.properties`. See 3.4. |
| Emulator window black / crashes under Wayland | `emulator -avd <name> -gpu swiftshader_indirect`, or start with `QT_QPA_PLATFORM=xcb`. |
| `adb server version (x) doesn't match this client (y)` | Two `adb`s on PATH. `adb kill-server`, then use one consistently (SDK `platform-tools` is first on PATH per section 3). |
