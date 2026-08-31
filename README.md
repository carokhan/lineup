# Lineup

Turns a shared event poster or screenshot into a calendar entry.

Share an image → on-device OCR → deterministic event extraction → a small confirm/edit
screen → Android's native "create event" screen. No backend, no accounts, no network.

## Architecture

```
MainActivity  (parses the share intent, never crashes on a malformed one)
  └─ ShareViewModel        owns state across rotation; OCR runs once per URI
       ├─ OcrService        MlKitOcrService → OcrText/OcrBlock/OcrLine (+ boxes)
       ├─ EventParser       LocalEventParser → EventDraft (+ per-field Confidence)
       ├─ PosterTranscriber GeminiNanoTranscriber → OcrText   (fallback, on-device)
       ├─ CalendarWriter    inserts into CalendarContract.Events (the primary action)
       └─ CalendarLauncher  ACTION_INSERT, kept as the way out
  └─ ConfirmScreen         Compose/Material 3
```

### The fallback

ML Kit reads printed posters well and hand-lettering badly: on a real flyer it turned
"8.28.2026" into "L28.2026" and "6-7:30 PM" into "6-130 M1", at every rotation, scale and
contrast treatment tried. That is a *recognition* failure, upstream of the parser, so it is
fixed at the `PosterTranscriber` seam rather than in `EventParser`.

When the local pass leaves the title, date or start time empty, Gemini Nano transcribes the
image through AICore and the transcription goes through the very same `LocalEventParser`.
The model is asked only to read, never to extract - asking it for structured fields was
measurably worse, as it invented dates and times it had not read. Its output fills gaps
only, never overwriting a local result, and where the two agree the field stops being
flagged as a guess.

It runs entirely on-device: no API key, no network permission, nothing leaves the phone.
If the model is not yet downloaded the user is offered a button rather than a surprise
download, and AICore refuses to run at all unless the app is in the foreground.

`EventParser` is the extension point. A future `LlmEventParser`, or a
`LocalEventParser → confidence check → fallback` pipeline, drops in behind that
interface without the OCR, UI or calendar layers changing.

The app name lives only in `res/values/strings.xml` (`app_name`); nothing in the
architecture depends on it.

## Headless development

No Android Studio anywhere in the loop. The toolchain lives in user-local directories and
is exported from `~/.zshrc` / `~/.bashrc`:

- JDK 21 — `~/jdks/jdk-21.0.11+10`
- Android SDK 34 — `~/android-sdk` (also pinned in `local.properties`)

```sh
./gradlew test           # 55 JVM unit tests, no device needed
./gradlew assembleDebug  # → app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug
```

### Debugging a poster that parsed badly

`tools/ocr.sh poster.png` runs the real on-device ML Kit over local image files and prints
the recognised layout as TSV plus the parser's verdict, in about five seconds and with no
share sheet involved. It copies the image into the app's own data directory via `run-as`,
so no storage permission is needed.

Paste that TSV into `app/src/test/resources/` and it becomes a regression fixture (see
`RealPosterTest`), which moves parser iteration into instant JVM tests.

## Installing on a phone

USB devices are not visible to WSL2 by default, so drive the phone with the Windows
`adb.exe` (the `wadb` alias) and hand it the APK over the `\\wsl.localhost` share:

```sh
wadb install -r '\\wsl.localhost\Ubuntu\home\apoorva\agent-stack\workspace\lineup\app\build\outputs\apk\debug\app-debug.apk'
```

To use the WSL-native `adb` instead, attach the phone with `usbipd attach --wsl --busid <id>`
from an elevated Windows shell first.

### Saving

"Add to Calendar" writes the event and closes. Handing the draft to a calendar app meant
confirming twice on two near-identical edit screens, so the app now writes to
`CalendarContract.Events` itself and this screen is the only place anything is confirmed.

Which calendar it writes to is the user's choice - every writable calendar is offered with
its account, and the selection is remembered, so it is a one-time decision. "Open in
calendar app instead" is still there for anyone who wants the full editor, and is also
where the app falls back if permission is refused, no writable calendar exists, or the
insert fails.

## Permissions

`READ_CALENDAR` and `WRITE_CALENDAR`, and nothing else. They are what makes saving a single
tap; this is still the system calendar provider, so there is no account, no OAuth and no
network. The image arrives as a `content://` URI with a temporary read grant, and both the
OCR model and Gemini Nano run on-device. ML Kit's telemetry uploader pulls in `INTERNET`,
which the manifest strips with `tools:node="remove"`.
