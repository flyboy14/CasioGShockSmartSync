# DW-H5600: features we can add (from the decompiled CASIO WATCHES app)

Source: CASIO WATCHES APK pulled from the phone on 2026-11-17. Java `casiolib` gives the packet
codes (`RemoteCasioWatchFeaturesService` read/write calls). The Flutter code (`libapp.so`,
decompiled with blutter) gives the field names (`casio_watch_lib/public/*_data.dart`).
Only packets the DW-H5600 answered in our captures are listed.

Status: **Have** = already in the fork · **Ready** = layout known and the watch answers; only UI and code needed
· **Partly** = packet known, some fields still need decoding.

## Health and fitness

| Feature | Packet | Fields (CASIO names) | Our watch now | Status |
|---|---|---|---|---|
| Heart-rate monitor mode | `0x41` | byte1 bits0-1 `monitoringCondition` (0 Off, 1 RunActOnly, 2 AlwaysOn) | `41 01 00 00 00` | **Have** (Settings tab) |
| HR zone alert during activity | `0x41` | byte1 bits 2-4, byte2 zone | – | **Not supported**: the DW-H5600 accepts the write but keeps only bits 0-1 (tested 2026-11-17) |
| Daily step goal (+ "goal reached" notification) | `0x43` | `steps` (u16 LE, `10 27` = 10000), `enabledNotifyDailyStepsAchievemnet`, `dailyDistance`, `runDistance`, `runningTime`, `elevationGain`, `monthlyWatchFace` | 10000 steps | **Have** (bytes 1-3 LE, notify = byte 16 bit 0x01) |
| User profile | `0x45` | `birthYear/Month/Day`, `height`, `weight` | `45 02 87 fe …` | Partly (encoding looks packed) |
| Sleep schedule, max/resting HR, VO2max | `0x4F` | `preferedSleepTime…` (23:00 → 08:20), `maxmumHeartRate` (0xBE = 190), `restingHeartRate` (0x37 = 55), `vo2Max` (0x35 = 53), use-value flags, `trainingBackground` | `4F 02 17 00 08 14 00 BE 37 35` | **Have** (sleep time, max/resting HR; VO2max shown) |
| Step / inactivity reminder | `0x13` | `stepReminder` bit | – | Partly (bit position not yet found) |
| Health history (steps, HR, sleep, kcal) | LifeLog frames | – | – | Have (steps); HR samples (`0x52` frames) and sleep (`0x53`) still to decode |

## Watch display and modes

| Feature | Packet | Fields | Status |
|---|---|---|---|
| Watch face 1/2 + face-2 item | `0x51` | `customizeFace1`, item codes | **Have** |
| Mode (widget) list: order and visibility of watch screens | `0x52` | list of mode IDs (izivkov's watch: `09 02 0A 08 0E 0F 10 11 07 03 05 06`) | Partly (ID→name map in the app's `widget_view`) |
| Activity types on the watch (run, walk, gym, …) | `0x53` | list of activity IDs (ours: `02 0E`) | Partly |
| Main font, date order, weekday language | `0x13` | `font`, `dateOrder`, `weekLanguage` | **Not supported**: written, but the DW-H5600 reads back 0 (tested 2026-11-17) |
| 12/24h | `0x13` | `isSet24H` | **Have** |
| Units (km/mile, °C/°F) | `0x2F` | `useMileForGps`, `useFahrenheitForTemp`, … (ours `2F 0C 04 00 00 00`) | Partly |

## Sound, light, notifications

| Feature | Packet | Fields | Status |
|---|---|---|---|
| Sound mode (off / sound / vibe / both / custom) | `0x42` | `sound` | **Have** |
| CUSTOM per-function sound table | `0x42` bytes 2-5 | function list is in the app's `sound_view` | Partly (still the big unmapped table) |
| Button tone, operation vibration | `0x13` | `enabledKeySound`, `enabledOperationVibration`, `buttonBeepVibe` | Ready |
| Auto light, light duration | `0x13` | `enabledAutoLight`, `lightTime` | Have (stock Settings cards) |
| Hourly time signal | `0x13` | `timeSignal` | **Have** |
| Night time (quiet hours) | `0x13` bytes 13-16 | `nightTimeStart/EndHour/Min` (ours 23:00 → 08:20) | **Have** (Night time card, also writes the 0x4F sleep time; tested) |
| Mute notifications at night, notification text size, language | `0x3D` | `settingForTurningOffNotificationWithNight`, `popupSize`, `languageSetting…` | Partly |
| Power saving | `0x13` | `enabledPowerSaving` | Have (stock card) |

## Alarms and timers

| Feature | Packet | Fields | Status |
|---|---|---|---|
| Alarms 1-4 | `0x15`/`0x16` | – | **Have** |
| Smart alarm | `0x50` | `configurationSnooze`, `smartWakeupNotification` | **Have** — part of the Alarms tab: any alarm can be smart; the phone loads the next one into the watch's single smart slot |
| Countdown timer | `0x18` | `hour`, `minute`, `second` | Have (fixed) |
| Interval / training alerts (every N min / km / kcal) | `0x55` | `time1/2Interval`, `distance1/2Interval`, `caloriesInterval`, `…Repeat` | Partly (watch reply not captured yet) |

## Activity (workout) settings

| Feature | Packet | Fields | Status |
|---|---|---|---|
| Auto lap, auto pause, auto run detection, phone GPS for runs | `0x40` | `autoLapDistance`, `autoLapFace`, `enabledAutoPause`, `enabledAutoRunDetection`, `usePhonesLocationService` (ours `40 08 00 00 00 00`) | Partly |
| Pool length and per-activity settings | `0x57` | `poolLength`, `command` | Partly |

## Connection and info

| Feature | Packet | Fields | Status |
|---|---|---|---|
| Battery level | `0x28` | – | **Have** |
| Connection / advertising parameters (connect interval, timeout) | `0x3A` / `0x3B` | – | Partly (could make reconnects faster) |
| Watch name, firmware version, module ID | `0x23`, `0x20`, `0x26` | – | Ready (read only) |
| Operation log | `0x5A` | – | Partly |

## Tested on the watch (2026-11-17, write then read back)

A read sweep of 0x10-0x7F: the watch answers 10 11 13 15 16 18 1D 1E 1F 20 22 23 24 25 26 27 28 2A 2F
39 3A 3B 40 41 42 43 44 45 47 4F 50 51 52 53 56 57 58 5A; everything else is rejected (`FF 81 xx`).

| Setting | Packet / bits | Result |
|---|---|---|
| HR monitor mode | 0x41 byte 1 bits 0-1 | **kept** |
| HR zone alert, zone | 0x41 byte 1 bits 2-4, byte 2 | dropped |
| Step goal, goal notification | 0x43 bytes 1-3, byte 16 bit 0x01 | **kept** |
| Sleep time, max / resting HR (+ manual flags) | 0x4F | **kept** |
| Night time | 0x13 bytes 13-16 | **kept** |
| Step reminder | 0x13 byte 12 bit 0x02 | **kept** |
| Watch mode list (order, removing a mode) | 0x52 bytes 2.. (IDs below) | **kept** |
| Date order / weekday language / font | 0x13 byte 4 / 5 / byte 8 bit 0x20 | dropped |
| Operation vibration | 0x13 byte 10 bit 0x01 | dropped |
| Auto pause | 0x40 byte 1 bit 0x02 | dropped |
| Distance in miles | 0x2F byte 4 bit 0x02 | dropped |
| User profile | 0x45 | the watch's format is not the BCD layout the app uses: not decoded |

0x52 mode IDs (CASIO WidgetItem): 01 Time & date, 02 Heart rate, 03 Life log, 04 Stress, 05 Nightly
recharge, 06 Activity log, 07 Cardio load, 08 Breathing exercise, 09 Notifications, 0A Blood oxygen,
0B Altimeter, 0C Barometer, 0D Compass, 0E Stopwatch, 0F Timer, 10 World time, 11 Almanac, 12 QR, 13 Tide.
Ours: `52 01 0F 0E 11 10 02 08 09 0A 03 05 06`.
