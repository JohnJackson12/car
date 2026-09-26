# Voice Music - Android

Native Kotlin rewrite of your Windows voice-controlled music player, built to run on phones/tablets
AND cheap Android car head units. This is a from-scratch Android app (not a Python wrapper) that
reimplements every feature: wake-word voice control, offline speech recognition, library scanning,
playlists, ratings, trim points, 10-band EQ + bass boost + virtualizer + speed, ReplayGain, themes,
and more.

## How to build the APK (GitHub Actions - no PC setup needed)

1. Create a new **public or private** GitHub repo.
2. Upload every file in this zip, keeping the folder structure exactly as-is - including `gradlew`,
   `gradlew.bat`, and the `gradle/` folder at the repo root. These are the real Gradle wrapper (not
   placeholders), so the build doesn't depend on GitHub's runner having a compatible Gradle version
   pre-installed. If you upload via GitHub's web "Add file" interface rather than `git push`, it can
   strip the executable bit off `gradlew` - the workflow re-sets it automatically, so this is handled
   either way, but if you ever run `./gradlew` yourself locally and get "Permission denied", run
   `chmod +x gradlew` first.
3. Push to the `main` branch (or click **Actions -> Build APKs -> Run workflow** to trigger it
   manually).
4. Wait for the build to finish (~10-15 min the first time - it downloads a speech-recognition
   model, cached after that).
5. Open the finished run, scroll to **Artifacts**, download **VoiceMusic-APKs**. It contains two
   files:
   - `app-standard-release.apk` - for phones, tablets, and most modern car units.
   - `app-car-release.apk` - a second build with permissions pre-approved at install time, for
     head-unit ROMs whose runtime permission dialogs are broken or missing (a common reason an APK
     that works on a tablet doesn't work in the car). Try the standard one first; use this one if
     the app can't get storage/mic permission on your unit.

Both APKs are signed with the keystore included in this repo (`keystore/voicemusic.jks`) so you can
install updates over an existing install without uninstalling first. Sideload by copying the APK to
a USB stick or downloading it directly on the unit, then installing from Files/a file manager.

## Why a rewrite instead of converting the Windows app directly

The Windows app is built on Tkinter, VLC's DLLs, and PortAudio - none of which exist on Android.
Wrapping that in a Python-for-Android shim is exactly the kind of thing that tends to work on one
device and silently fail on another (which matches what you saw: working on a Samsung tablet, not
in the car). This is a native Android app instead, which is far more predictable across the huge
range of Android versions and hardware cheap head units use.

## What's carried over from the Windows app

- **Voice control**: wake word + aliases, "hey <name>" prefix, custom approve/delete words,
  two-stage listening (say the wake word alone, then the command), auto-duck while listening,
  per-word confidence filtering, rate 1-N by voice, skip-intro (global and per-command), trim by
  voice, play-by-title, undo, next/previous/pause/play/status. The wake-word/command parsing logic
  was ported line-for-line and checked against the original Python on 92 test phrases plus a
  796-phrase grammar - byte-for-byte identical output.
- **Offline speech recognition**: same engine (Vosk) and the same grammar-constrained recognizer
  approach as the PC app, bundled into the APK at build time so it works with no internet.
- **Library**: folder scanning, songs/albums/artists/genres/folders views, playlists, favorites,
  recently added, most played, ratings written into the file's own tags (ID3 POPM for MP3/WAV,
  Vorbis comments for FLAC/OGG), trim points, soft-delete with undo, move/copy files.
- **Trimming**: MP3 trimming is a byte-for-byte port of the PC app's frame-accurate cutter (checked
  against the Python version on 9 test files - identical output). WAV is a direct PCM cut. M4A/AAC
  and OGG/Opus use Android's built-in muxer for a lossless cut (no ffmpeg needed on-device).
- **Sound**: 10-band EQ with the same preset curves, bass boost, virtualizer (stereo widening),
  variable playback speed, ReplayGain-based volume leveling - verified numerically (a +12 dB boost
  measures out to +12 dB before headroom, cuts are exact, no clipping).
- **Themes**: all 6 color palettes from the Windows app, switchable instantly (no restart needed,
  unlike the Tk version).

## Built for car head units specifically

- Every ABI is included in one APK (many head units are 32-bit ARM, not 64-bit).
- Supports Android 5.0 and up.
- Two build flavors (see above) for ROMs with broken permission dialogs.
- Its own folder browser instead of the system file picker (some head-unit ROMs strip that out).
- Standard MediaSession + notification controls, so Bluetooth/steering-wheel next/prev/play buttons
  work the same way they do for any other Android music app.
- Responds normally to phone calls / nav prompts taking audio focus (ducks or pauses, then resumes).
- Optional "start on boot" so the app can come up automatically when the car turns on.

## Differences from the Windows app (disclosed, not hidden)

- **Microphone selection**: Windows used a PC audio API (PortAudio) that could listen on every
  microphone at once. Android's audio model doesn't map onto that the same way, so this version
  listens on one microphone at a time - auto-detected by default, or pick a specific audio source
  and (Android 6+) a specific physical input device in Settings.
- **FLAC files can't be trimmed in place on Android** (no FLAC re-encoder built into the OS); trim
  points still work fine during playback, just not "bake into file" for that one format.
- **A few Android-only settings** were added where the desktop app had no equivalent: keep-screen-on,
  start-on-boot, UI scale, and a cap on how many song titles feed the voice grammar (keeps very large
  libraries from slowing down voice recognition - the desktop app had a similar setting in its config
  file that was defined but never actually wired up to anything).
- **Multi-select** uses Android's standard long-press-to-select + action-bar pattern instead of the
  desktop app's Ctrl/Shift-click, since a touchscreen doesn't have those keys.

## What changed after a full re-audit (you were right to push on this)

After you raised concerns, I went back and actually read the files I'd only skimmed before - all of
`main.py` (the real command dispatcher - it's not in `gui.py`, which only logs what happened),
all of `settings_dialog.py`, all of `feedback.py`, and the bulk of `gui.py` - instead of building
from partial memory of an earlier read. That surfaced real problems:

**Behavior bugs, now fixed:**
- Voice "skip 10 seconds" was changing the permanent global skip point instead of jumping forward
  once in the current song.
- Voice "delete" had a leftover dead code path with a possible race against the file move.
- "Rate" is supposed to also mark the song approved, and "keep" is supposed to advance to the next
  song - both were missing.
- Baking a trim into the currently-playing file left playback stopped instead of reloading it.
- Undo wasn't putting the restored song back into the play queue or updating the voice grammar.

**A feedback-sound system that didn't match yours at all:** wrong option names, and no per-event
distinct tones. Rewritten to match `feedback.py` exactly - rating plays one note per star, delete/
undo/error/generic-acknowledge each have their own fixed tone shape, regardless of which style
you've picked (matching the original's design of "you can tell these apart by ear without looking").

**Features that were genuinely missing, now added:**
- Search box + genre filter for the library list (a real gap for a large library - not sure how I
  missed this the first time).
- Volume slider + mute button, and direct tap-to-rate stars / tap-to-favorite heart on the now-
  playing bar (the desktop app's Now Playing card has both; my first pass had neither).
- Quick-set genre from the existing genre list, on one song or a batch.
- "Remove from this playlist" and reorder up/down, when viewing a playlist's contents.
- "Unfavorite" as its own batch action, separate from "Favorite".
- A "Recently Played" section, separate from "Recently Added" (I'd merged the two).
- Filename renaming + the two quick-fill templates in the edit-tags screen.
- The in-app voice-command reference and library tips (Settings).
- A real Queue view, and a custom sound file picker for feedback tones.
- An accent color picker (preset swatches + custom hex + reset).

**A risk I introduced myself:** your `feedback.py` has a comment explaining that the developer hit
emoji glyphs rendering inconsistently on some Windows setups and switched to hand-drawn icons
because of it. My first pass used emoji glyphs for every icon button in the Android UI - the exact
same risk, on hardware (cheap head-unit ROMs) that's if anything more likely to have an incomplete
emoji font. Transport controls (play/pause/skip/shuffle/repeat, the mic indicator, the folder
browser) now use plain text/ASCII instead, which every font can render.

**What I'm still not certifying as 100% audited:** `gui.py` is 2,541 lines; I've now read the large
majority of it directly (plus all of the other files in full), but I have not verified every single
line against the Android build. If you spot something from the original that isn't here, tell me
specifically what it is and I'll port it - that's a much faster and more reliable path than me
re-guessing at completeness from memory again.

## A build failure you may have already hit, and the fix

An earlier version of this project cloned the tag-editing library (jaudiotagger) from its GitHub
repo as a CI step and copied it into the source tree at build time. If your build failed with
errors like `Unresolved reference: jaudiotagger` in `TagIO.kt`, that's why - it's a fragile design
I shouldn't have used (one more network call that can fail, one more way to get a path wrong). It's
fixed now: the library's source is committed directly in this zip, under
`app/src/main/java/org/jaudiotagger/` (about 470 files, LGPL-2.1 licensed - see
`THIRD_PARTY_NOTICES.txt`), so there's nothing to clone and nothing to go wrong there. The workflow
now just double-checks that folder made it into your repo and fails with a clear message if not,
instead of the confusing "unresolved reference" error.

## How this was verified before being handed to you

I don't have a real Android device or emulator in this environment, so I couldn't do what would
normally be the last step - actually installing and running the APK. What I could and did do:
- Fetched the real source of every third-party library used (ExoPlayer/Media3, the Vosk speech
  engine, the Android tag-writing library) from their official repositories and checked every method
  signature against it before using it, rather than relying on memory.
- Compiled the tag-writing library from source against a real Android platform jar - it builds clean.
- Type-checked all ~26 Kotlin files in this project against that same real Android platform jar plus
  hand-written stubs matching the exact signatures found in the fetched library source.
- Ran the voice-command parser, the MP3 trimmer, and the equalizer math side-by-side against the
  original Python on a JVM, byte-for-byte and bit-exact where applicable (details above).

That's real verification, but it's not the same as a build actually succeeding on GitHub's servers
and an APK actually running on your hardware. **The first GitHub Actions run is the real test.** If
it fails, copy the error from the Actions log (the "Build APKs" step) and send it to me - that's a
fast, precise fix, much faster than debugging blind on-device. Likewise, if the APK installs but
something's off on your specific head unit, send me what happens (or the in-app crash log from
Settings -> Diagnostics) and I'll fix it from there.
