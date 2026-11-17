# Developing the DW-H5600 fork

How the DW-H5600 features in this fork are built, and the patterns to reuse when adding the next one.
Read this before touching `h5600/` or the GShockAPI `H5600SettingsIO`.

Related documents:

| Document | What it holds |
|---|---|
| [`DW-H5600.md`](DW-H5600.md) | What the app does, tab by tab, and the code map |
| [`DW-H5600-feature-map.md`](DW-H5600-feature-map.md) | Every packet the stock app knows, and what the watch **actually** keeps (tested table at the bottom) |
| [`DW-H5600-capture-checklist.md`](DW-H5600-capture-checklist.md) | How to capture the official app's Bluetooth traffic |
| GShockAPI `docs/DW-H5600-protocol.md` | Packet layouts, transport, LifeLog |

---

## 1. The rule that matters most: the watch accepts writes it does not keep

The DW-H5600 answers almost every write with a normal write response, including fields it ignores.
Date order, weekday language, font, operation vibration, auto pause, miles, the HR zone alert and the
whole user profile were all "written successfully" and then read back as before.

**A feature is only real once a write has been read back changed.** Every new setting goes through the
test in section 3 before any UI is built, and the result goes into the tested table in
`DW-H5600-feature-map.md`.

---

## 2. Finding a setting

Three sources, in this order:

1. **The CASIO WATCHES app's code** (section 6). `casiolib` (Java) names every packet and gives its
   code; the Flutter part (`libapp.so`, decompiled with blutter) names every field, its byte, its bit
   mask and its enum values.
2. **A read sweep** of the watch: which codes it answers at all. On 2026-11-17 it answered
   `10 11 13 15 16 18 1D 1E 1F 20 22 23 24 25 26 27 28 2A 2F 39 3A 3B 40 41 42 43 44 45 47 4F 50 51 52 53 56 57 58 5A`;
   everything else is `FF 81 <code>` (rejected).
3. **A capture of the official app** (`DW-H5600-capture-checklist.md`), when the code alone is unclear.

Offsets: the Dart data classes index the data **without** the code byte. Data index `N` = packet byte
`N + 1`. Example: `nightTimeStartHour` is `_getValue(0xc)` → 0x13 packet byte 13.

---

## 3. Testing a setting on the watch (before writing any app code)

The debug build registers a broadcast receiver for raw packets (`H5600ScheduleRunner`, debug only):

```
tools/watch_raw.sh get 52                                     # read request
adb logcat | grep -E "Notification from|raw (GET|SET)"       # the reply
tools/watch_raw.sh set "52 01 0F 0E 11 10 02 09 0A 03 05 06 00 00 00 00 00 00 00 00"
tools/watch_raw.sh get 52                                     # did it stick?
tools/watch_raw.sh set "<the original packet>"                # always restore
```

The procedure, every time:

1. Read the packet and save the original.
2. Change **one** field and write the whole packet.
3. Read it back. Changed = supported. Back to the old value = not supported (see section 1).
4. If it is visible on the watch, ask the user to look (the mode list was confirmed this way).
5. Write the original back and read again to confirm the restore.
6. Add the result to the tested table in `DW-H5600-feature-map.md`.

Never leave a test value on the user's watch, and never write a packet built from zeros.

---

## 4. Adding a setting end to end

### 4.1 Library (GShockAPI)

| Step | Where | Pattern |
|---|---|---|
| Model | `model/H5600Settings.kt` | A data class or enum with a KDoc naming the packet, bytes, bits and the test date |
| Read | `io/H5600SettingsIO.kt` | `readChecked(CODE, SIZE)` → decode |
| Write | `io/H5600SettingsIO.kt` | `modifyChecked(CODE, SIZE) { p -> p.copyOf().also { … } }` — read-modify-write, change only your bits |
| 0x13 fields | `io/SettingsIO.kt` | `freshRaw()` to read, `modifyRaw { b -> … }` to write (shared lock with the 12/24 h and hourly-signal writes) |
| Routing | `protocols/WatchProtocol.kt` | `CODE to RawSettingIO::onReceived`, or the reply never reaches the reader |
| API | `IGShockAPI.kt` | `suspend fun getH5600X(): X? = null` (default for other watches) |
| | `GShockAPI.kt` | `override suspend fun getH5600X() = H5600SettingsIO.getX()` |

`GShockRepository` delegates to `IGShockAPI`, so the app sees the new calls with no extra code.

Rules:

- **Read-modify-write only.** Keep every unknown byte and every bit you do not manage. Mask, do not
  assign: `it[1] = (it[1] and 0x03.inv()) or mode.code` (0x41 shares byte 1 with alert flags).
- `RawSettingIO` already shares concurrent reads of the same code, retries unanswered reads, fails
  fast on `FF 81`, and serializes writes per code. Do not add your own retry loops.
- A field that exists in two packets (night time: 0x13 bytes 13–16 and 0x4F bytes 2–5) is written to
  both, as the official app does.

### 4.2 App: view model (`h5600/H5600ViewModel.kt`)

State lives in `UiState`. Pick the write strategy by how the control is used:

| Control | Strategy | Helper | Examples |
|---|---|---|---|
| A choice that should feel instant | Optimistic update, write, then read back until the watch agrees | `run(…)` + `confirm(expected) { read }` | sound mode, watch face, HR monitor |
| Steppers and switches tapped quickly | Optimistic update, one write 700 ms after the last tap | `debouncedWrite(key, what) { … }` | step goal, max/resting HR, step reminder |
| A list edited in several steps | Local draft in the card; **Save** sends it once; **Discard** resets | card state + `setModeList` | watch modes |
| A dialog-confirmed side effect | Ask first, then two writes | dialog in the card, `selectFace(item, hrMode)` | HR on the face ↔ all-day monitoring |

Always:

- `run(what, onError = { restore previous state }) { … }` — one queue for all watch writes, snackbar on
  failure, the previous value restored.
- Reads go through `readOrNull { … }` (12 s timeout, no crash); a failed read keeps the last value.
- Values that should show instantly after a restart go to `LastKnown` (`utils/LastKnown.kt`) and are
  the `UiState` defaults.
- Reads that only a tab needs go in `refreshSettings()`, called by that tab (`LaunchedEffect(Unit)`),
  not in `refresh()`, which runs for the Custom tab on every start.
- Never swallow errors silently: a `runCatching` without `onFailure { Timber.e(…) }` hid a Health bug
  for a whole test round.

### 4.3 App: UI (`h5600/H5600SettingsCards.kt`, `h5600/H5600Card.kt`)

Building blocks — reuse them, do not restyle:

| Composable | Use |
|---|---|
| `SettingsCard(title, info) { … }` | A card with the standard padding and header |
| `CardTitle(title, info)` | The header: title and an (i) `InfoButton` with the explanation (HTML; use `<br><br>` for paragraphs) |
| `SwitchRow(label, checked) { … }` | Label + switch |
| `StepperRow(label, −, +)` | Value + small − / + buttons (pair with `debouncedWrite`) |
| `Choices(options, selected, perRow)` / `AppChoiceButton(…, compact = true)` | Pick one |
| `ModeRow` + `dropIndex` | Long-press drag reordering (same code as alarms and world clocks) |
| `LoadingSpinner()` | Until the first read |
| `AppTimePicker` inside a `Dialog` | Times (night time) |

Conventions:

- **Cards show values, not explanations.** Anything that explains goes into the (i) popup.
- **Unknown is not zero.** A value the app does not have is shown as "–" or left blank (step history).
- **No Send buttons**, except for lists edited in several steps (draft + Save).
- **Long lists collapse** (`rememberSaveable` expanded flag, header shows a summary such as
  "12 shown, 0 hidden").
- **Reordering is hold-and-drag** everywhere (`detectDragGesturesAfterLongPress`, `dropIndex`,
  haptics on every slot change). Rows keep their place when switched off during a draft.
- **Screens that must fit** use `BoxWithConstraints` and give the flexible parts `weight(1f)` inside a
  column of exactly `maxHeight` (Health tab), keeping an outer `verticalScroll` so pull-to-refresh works.
- A new tab: `Screens.kt`, `BottomNavigationItem.kt` (DW-H5600 only), `BottomNavigationBarWithPermissions.kt`
  (route and the `LastTab` set).

### 4.4 Data the watch sends only once

The LifeLog sync is incremental: the watch sends only what the phone has not fetched yet, and the
request (`00 2E FF FFFFFFFF`) has no date parameter, so nothing can be fetched again. Anything like
this must be **stored on the phone, keyed by time**, and merged, so a re-sent record replaces itself
instead of counting twice (`H5600Steps.merge`: 5-minute slots by epoch minute, daily totals by date,
kept 8 and 400 days). Charts are built from the store, not from the last sync.

### 4.5 Settings the phone changes over time (the scheduler)

Some watch settings are changed by the phone on a timetable (`H5600ScheduleRunner`): the sound mode
for alarms, the face in Auto, the sun data / weather. The rule for all of them:

- **The user's choice is the base.** Store it on the phone (`H5600Prefs`, e.g. `baseSoundMode`).
  Something may *borrow* the watch for a window and must give it back; it never replaces the base.
- **The decision is a pure function of (data, now, base)** in a plain object, so it is unit-tested
  (`AlarmPool.desiredSoundMode`, `AlarmPool.baseAt`, `SunFace.desiredFace`; tests in `AlarmPoolTest`).
- **Windows are explicit:** start and end times (`AlarmPool.soundWindow`: 5 min before the alarm
  (before a smart window) → end of the ring / snooze / stopped snooze; night window for DND).
- **The scheduler wakes at every edge** (`nextAlarmBoundary` collects `nextSoundBoundary`,
  `nextNightBoundary`, slot reloads) with exact Android alarms, and each pass writes only when the
  watch differs (`api.setSoundMode` reads first and skips equal writes).
- **Protect running alarms:** time syncs and alarm-slot writes cancel a snooze on the watch, so
  automatic passes hold them during a snooze window (`AlarmPool.snoozing`).
- A user change that affects the timetable (alarm edit, night time, DND switch) calls
  `runner.applyAlarms()` so the watch is updated at once and the next wake-up is re-planned.

### 4.6 Finishing

1. Build: `./gradlew assembleGithubDebug` (tests: `testGithubDebugUnitTest`).
2. Install and try it on the phone (section 5); read the packet back after using the UI.
3. Update `DW-H5600-feature-map.md`, `DW-H5600.md`, the protocol doc and, for user-visible changes,
   the README.
4. Commit and push both repositories (branch `h5600-sound-mode`, remote `origin` = the flyboy14 forks).

---

## 5. Testing on the phone

```
adb install -r app/build/outputs/apk/github/debug/app-github-debug.apk
adb shell monkey -p org.avmedia.gshockGoogleSync -c android.intent.category.LAUNCHER 1
tools/adb_ui.py list                 # what is on screen, with bounds
tools/adb_ui.py tap "Watch modes"    # tap by text
adb exec-out screencap -p > shot.png
```

Things that will trip you up:

- The app opens on the **Ride to unlock** cover: `adb shell input swipe 534 1236 1060 1236 600`.
- A swipe up on a tab that is already at its bottom **locks the app** (pull-up-to-lock). Scroll with
  short swipes, and not at all on short tabs.
- Rows move when the list changes (a hidden mode used to jump to the bottom): list again before a
  second tap.
- adb gestures are not a thumb: long-press drags and the ride slider behave differently. Ask the user
  to try gestures by hand, or record the screen (`adb shell screenrecord`).
- USB debugging switches itself off now and then (`lsusb` shows no Google device). Ask the user to
  turn it on; stop any queued adb command so a half-finished test cannot run later.
- Reinstalling drops the watch connection; it reconnects by itself within ~15 s, or after the
  watch's connect button.
- Useful log tags: `RawSettingIO` (every packet read and written), `SettingsIO$modifyRaw` (0x13
  writes), `H5600LifeLog`, `H5600Steps`, `GShockManagerImpl` ("Notification from …").

---

## 6. Reading the CASIO WATCHES app

The app is mostly Flutter. Its Java part (`casiolib`) still owns the Bluetooth layer.

```
adb shell pm path com.casio.wac_app          # base.apk + split_config.arm64_v8a.apk
adb pull <base.apk>; adb pull <split_config.arm64_v8a.apk>
apktool d base.apk -o base                   # smali
unzip split_config.arm64_v8a.apk -d arm      # lib/arm64-v8a/libapp.so (Dart AOT)
```

**Packet codes (Java):** `smali_classes5/com/casio/casiolib/ble/client/RemoteCasioWatchFeaturesService.smali`.
Each `readCasio…` / `writeCasio…` method passes a `GattUuid` name and the code byte to
`readCharacteristic` / `writeCharacteristic` (e.g. `CASIO_SETTING_FOR_HEART_RATE_MONITOR`, `0x41`).
The numbers in `BleConstants$GattUuid` are enum ordinals, not codes. Per-model feature flags are in
`CasioLibUtil$DeviceType` (`isEnable…Setting`), but many DW-H5600 features are decided in Dart, so a
`false` there proves nothing.

**Fields (Dart):** [blutter](https://github.com/worawit/blutter) turns `libapp.so` into readable
assembly with names. It needs capstone; without root, build it locally and point the compiler at it:

```
cmake -S capstone -B capstone/build -G Ninja -DCMAKE_INSTALL_PREFIX=$PWD/cs -DCAPSTONE_ARCHITECTURE_DEFAULT=OFF -DCAPSTONE_ARM64_SUPPORT=ON
ninja -C capstone/build install
PKG_CONFIG_PATH=$PWD/cs/lib/pkgconfig LD_LIBRARY_PATH=$PWD/cs/lib CPLUS_INCLUDE_PATH=$PWD/cs/include/capstone LIBRARY_PATH=$PWD/cs/lib \
  python3 blutter/blutter.py arm/lib/arm64-v8a out
```

Then:

- `out/asm/casio_watch_lib/public/<name>_data.dart` — one class per packet; getters/setters show the
  byte (`ldrb [x1, #N]`), mask (`and w, w, #0x20`) or helper (`Utils::setBCDFromInt`, `getIntFrom…`).
  `tools/blutter_fields.py <file> [field …]` prints just those lines.
- `out/pp.txt` — constant maps, e.g. `Map<HeartRateMonitoringCondition, int>` gives each enum's byte.
- `out/objs.txt` — enum names (`off_10: "AlwaysOn"`).
- `out/asm/wac_app/view/screen/…` — the app's screens (which settings exist, how they call the setters).
- English UI texts: `strings -n 8 libapp.so | grep -i "<words>"`.

Do not commit anything from the CASIO app (APK, smali, blutter output). Only the findings go into the
docs.

---

## 7. Known traps (each cost a test round)

| Trap | Lesson |
|---|---|
| 0x41 byte 1 also holds alert flags | Mask the field; never assign the whole byte |
| 0x52 byte 1 looked like a header | It is the first mode (Time & date); the list starts at byte 1 |
| 0x45 profile is BCD in the CASIO code | The DW-H5600 keeps a different format; writes are dropped |
| `adb shell am broadcast --es set "13 04 …"` | adb's remote shell splits on spaces; `watch_raw.sh` uses commas |
| `InfoButton` renders HTML | `\n` collapses; use `<br>` |
| `scrollTo` in `LaunchedEffect` before layout | Clamped to 0; key the effect on `scroll.maxValue` too |
| `@Composable` above a new top-level `val` | Insert helpers after the annotation, not between it and its function |
| Showing 0 for missing data | The user reads it as "0 steps"; show "–" |
| One write per tap on a multi-step edit | Use a draft and Save |
