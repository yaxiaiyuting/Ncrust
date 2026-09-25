# FINDINGS — Android media-session capability boundary, Ncrust v2.1.4-gpl on 3 real devices

Scope: read-only. No source modified, nothing rebuilt, nothing installed/uninstalled, no app data
cleared. The only device state change was `am start -n com.takahashirinta.ncrust/.MainActivity`.
Raw evidence: this directory (see [`README.md`](README.md) for the file map and the exact command
behind every file).

Headline: on the two devices that had a live session, Ncrust published a **lyric line in
`METADATA_KEY_TITLE`**, and the Huawei ROM's own SystemUI **demonstrably ingested it**. The reason
lyrics cannot be exposed through a *dedicated* key is not app reluctance or ROM hostility: **a
standard lyrics metadata key does not exist** in the platform key set, in `MediaMetadataCompat`, or
in media3 1.5.0.

---

## 1. Capability matrix

| | `0715f763f54c023a` | `3B15CD00GB700000` | `WVQ6R22124000968` |
|---|---|---|---|
| Model | SM-G9209 (Galaxy S6) | PLC110 | WGR-W09 (Huawei tablet) |
| OS reported | Android 7.0 | Android 16 | Android 12 (HarmonyOS 4.2 / EMUI 14.2.0 per brief) |
| API | 24 | 36 | 31 |
| Brand reported by device | `samsung` | **`OnePlus`** (brief said OPPO) | `HUAWEI` |
| App version | 2.1.4-gpl / vcode 34 | 2.1.4-gpl / vcode 34 | 2.1.4-gpl / vcode 34 |
| **Ncrust session count** | **0** | **2** | **2** |
| Session names | — (only `HeadsetMediaButton com.android.server.telecom`) | `NcrustSession` + `androidx.media3.session.id.` | `NcrustSession` + `androidx.media3.session.id.` |
| **Metadata keys actually present** | **none observable (no session)** | legacy session `metadata: size=5`; media3 session `metadata: size=3` | legacy session `metadata: size=5`; media3 session `metadata: size=3` |
| Key *identities* (title/artist/album as dumped) | — | title `我一定让自己让自己坚定`, artist `爱笑的眼睛 · 林俊杰`, album `null` | title `像一幅画`, artist `紫荆花盛开 · 李荣浩/梁咏琪`, album `null` |
| **Lyric line present in TITLE?** | **cannot determine** (no session) | **YES** | **YES** |
| Ncrust notification | not currently posted (archive only) | channel `ncrust_playback`, id `1`, tag `null`; `android.title=我一定让自己让自己坚定`, `android.text=爱笑的眼睛 · 林俊杰` | channel `ncrust_playback`, id `1`, tag `null`; `android.title=像一幅画`, `android.text=紫荆花盛开 · 李荣浩/梁咏琪` |
| Playback state at capture | n/a (no session) | `PAUSED(2)`, position 171747 | `state=2` (paused), position 70205; Ncrust held audio focus |
| `lyrics_in_media_session` | **`true`** (read via `run-as` **and** `su`) | **`true`** (read via `su`) | **cannot be read** (`run-as` refused, no `su`) |
| `lyrics_word_animation` | **`0`** | **`0`** | **cannot be read** |
| Read path used | `run-as` + `su` | `su` only | neither works |
| Ncrust metadata source keys (committed build, `HEAD`) | `TITLE`, `ARTIST`, `DURATION`, `DISPLAY_SUBTITLE=""`, `ART` | same | same |
| Does a lyrics metadata key exist to publish? | no — see §5 | no | no |

---

## 2. Task 1 — baseline metadata inventory (quoted)

### `0715f763f54c023a` — **there is no Ncrust session. Stated explicitly rather than invented.**

`media_session.txt` contains exactly one session and only two Ncrust bookkeeping lines:
```
Sessions Stack - have 1 sessions:
  HeadsetMediaButton com.android.server.telecom/HeadsetMediaButton
…
User Records:
Record for user 0
  MediaButtonReceiver:PendingIntent{c7dd10f: PendingIntentRecord{7dec59c com.takahashirinta.ncrust startService}}
  Restored ButtonReceiver:ComponentInfo{com.takahashirinta.ncrust/com.takahashirinta.ncrust.player.PlaybackService}
  1 Sessions:
  com.android.server.telecom/HeadsetMediaButton
```
The app process *was* alive (`pidof.txt` → `28762`) and `am_start.txt` says
`Warning: Activity not started, its current task has been brought to the front`. Playback was not
active, so `PlaybackService` was not running a session. In `notification.txt` the only Ncrust record
sits inside `mArchive=Archive (58 notifications)` — i.e. posted earlier, then removed — and no Ncrust
entry appears in `dumpsys audio` at all.

I deliberately did **not** force a session on this device: the only available levers (media key /
`am startservice` followed by a play command) would have started audible playback in the user's
environment, which is beyond a read-only investigation. So **API 24 metadata behaviour is not
measured in this round** — it is a gap, not a negative result.

### `3B15CD00GB700000` — 2 sessions
```
NcrustSession com.takahashirinta.ncrust/NcrustSession/290 (userId=0)
  ownerPid=6487, ownerUid=10334, userId=0
  active=true
  flags=3
  controllers: 6
  state=PlaybackState {state=PAUSED(2), position=171747, buffered position=253573, speed=1.0, …}
  metadata: size=5, description=我一定让自己让自己坚定, 爱笑的眼睛 · 林俊杰, null
androidx.media3.session.id. com.takahashirinta.ncrust/androidx.media3.session.id./289 (userId=0)
  active=true
  flags=7
  controllers: 3
  state=PlaybackState {state=PAUSED(2), position=171750, …, speed=0.0, …}
  metadata: size=3, description=null, null, null
  queueTitle=null, size=1
```
Notification (`notification.txt`):
```
NotificationRecord(… pkg=com.takahashirinta.ncrust … id=1 tag=null importance=2 key=0|com.takahashirinta.ncrust|1|null|10334:
  Notification(channel=ncrust_playback … category=transport actions=3 vis=PUBLIC))
    extras={
        android.title=String (我一定让自己让自己坚定)
        android.template=String (android.app.Notification$MediaStyle)
        android.text=String (爱笑的眼睛 · 林俊杰)
        android.largeIcon=Icon (Icon(typ=BITMAP size=168x168))
        android.mediaSession=Token (android.media.session.MediaSession$Token@af9a16a)
        android.colorized=Boolean (true)
        android.compactActions=int[] (3)
    }
  effectiveNotificationChannel=NotificationChannel{mId='ncrust_playback', mName=Ncrust 音乐播放, …}
```

### `WVQ6R22124000968` — 2 sessions
```
Media button session is com.takahashirinta.ncrust/NcrustSession (userId=0)
Sessions Stack - have 2 sessions:
  NcrustSession com.takahashirinta.ncrust/NcrustSession (userId=0)
    ownerPid=22676, ownerUid=10274, userId=0
    active=true
    flags=3
    controllers: 4
    state=PlaybackState {state=2, position=70205, buffered position=210667, speed=1.0, …}
    metadata: size=5, description=像一幅画, 紫荆花盛开 · 李荣浩/梁咏琪, null
  androidx.media3.session.id. com.takahashirinta.ncrust/androidx.media3.session.id. (userId=0)
    active=true
    flags=7
    controllers: 1
    metadata: size=3, description=null, null, null
    queueTitle=null, size=2
```
Notification: `channel=ncrust_playback … id=1 tag=null … category=transport`,
`android.title=String (像一幅画)`, `android.text=String (紫荆花盛开 · 李荣浩/梁咏琪)`,
`android.template=String (android.app.Notification$MediaStyle)`, but `android.largeIcon=null`
alongside `android.reduced.images=Boolean (true)` (the system dropped the bitmap for this
notification, while the session metadata still carried it — see §4).
`cmd media_session list-sessions` independently confirms the same two sessions:
```
Sessions:
  tag=NcrustSession, package=com.takahashirinta.ncrust
  tag=androidx.media3.session.id., package=com.takahashirinta.ncrust
```

**Key-identity caveat.** `dumpsys media_session` on these ROMs prints only
`metadata: size=<N>, description=<title>, <artist>, <album>` — it does **not** enumerate the key set.
So "the full list of metadata keys present" is *not* directly readable via adb. What is readable is
the **count** plus three values. The count `size=5` for `NcrustSession` on both devices is exactly
consistent with the five keys the committed build writes (`TITLE`, `ARTIST`, `DURATION`,
`DISPLAY_SUBTITLE`, `ART`), and `size=5` (rather than 4) additionally implies the `ART` bitmap was
present — corroborated on PLC110 by `android.largeIcon=Icon(typ=BITMAP size=168x168)` and on the
Huawei tablet by SystemUI's `artwork=Icon(typ=BITMAP size=512x512)` (§4). That is strong
correspondence, **not** key-by-key proof; see §6.

The lyric line is provably in TITLE, not merely "different from the song title": the committed
[`MediaDisplayLines.of`](../../../../app/src/main/java/com/takahashirinta/ncrust/player/MediaDisplayLines.kt)
rule is *lyric present* → `title = lyric`, `subtitle = "song · artist"`; *no lyric* →
`title = songTitle`, `subtitle = songArtist`. Both live devices show the first shape
(`像一幅画` / `紫荆花盛开 · 李荣浩/梁咏琪`), which the second branch cannot produce.

---

## 3. Task 2 — is lyric publishing enabled?

| Device | `run-as` | `su` | `lyrics_in_media_session` | `lyrics_word_animation` |
|---|---|---|---|---|
| `0715f763f54c023a` | works (package is `DEBUGGABLE`) | works (rooted) | `true` | `0` |
| `3B15CD00GB700000` | `run-as: package not debuggable: com.takahashirinta.ncrust` (exit=1) | **works** (rooted — not expected from the brief) | `true` | `0` |
| `WVQ6R22124000968` | `run-as: package not debuggable: com.takahashirinta.ncrust` (exit=1) | `/system/bin/sh: su: inaccessible or not found` (exit=127) | **cannot be read** | **cannot be read** |

Package identity confirmed on all three (`dumpsys package com.takahashirinta.ncrust`):
`versionCode=34 minSdk=24 targetSdk=36`, `versionName=2.1.4-gpl`; only the S6 carries the
`DEBUGGABLE` flag.

The Huawei value is unreadable from adb, but it does not block the conclusion: the observed TITLE is
a lyric line, so on that device lyric publishing is **behaviourally ON** regardless of the pref.
Note this is a stronger statement than the pref read — it proves the whole chain (setting → sampling →
`mediaLyricLine` → metadata republish) was live at capture time on both devices.

`lyrics_word_animation = 0` on both readable devices means word-by-word animation is off there; it is
a display preference and does not affect which metadata keys are published.

---

## 4. Task 3 — does the Huawei Control Center consume any of the candidate keys?

**Yes — provably for `TITLE`, `ARTIST` and `ART`.** The evidence is the ROM's own parsed model, not
an inference.

### 4.1 Which packages own the media-control UI

```
$ adb -s WVQ6R22124000968 shell pm path com.android.systemui
package:/system/priv-app/SystemUI/SystemUI.apk
$ adb -s WVQ6R22124000968 shell pm path com.huawei.mediacontroller
package:/system/priv-app/MediaPlaybackController/MediaPlaybackController.apk
```
`com.huawei.mediacontroller` owns the action `com.huawei.intent.action.MEDIA_CONTROLLER_CENTER`
(`com.huawei.mediacontroller/.MainActivity`). Other `hw`/`media` packages on the device
(`com.huawei.multimedia.audioengine`, `com.huawei.imedia.sws`, `com.huawei.hwbluetoothpencilmanager`,
`ohos.media.medialibrary`, …) show no media-session participation in the dumps. The single
`OnMediaKeyEventSessionChangedListener` is `com.huawei.hwbluetoothpencilmanager` (stylus), not a
Control Center component.

### 4.2 The decisive observation: SystemUI's own MediaDataManager already holds the lyric

```
$ adb -s WVQ6R22124000968 shell dumpsys activity service SystemUIService
SERVICE com.android.systemui/.SystemUIService 816fe4b pid=2249 user=0
  MediaDataManager:
    listeners: [com.android.systemui.media.MediaTimeoutListener@b001f4a, com.android.systemui.media.MediaResumeListener@a8f6fd8]
    mediaEntries: {0|com.takahashirinta.ncrust|1|null|10274=MediaData(
        userId=0, initialized=true, backgroundColor=-14136744, app=Ncrust,
        artist=紫荆花盛开 · 李荣浩/梁咏琪,
        song=像一幅画,
        artwork=Icon(typ=BITMAP size=512x512),
        actions=[…上一首, 播放, 下一首…], actionsToShowInCompact=[0, 1, 2],
        packageName=com.takahashirinta.ncrust, token=android.media.session.MediaSession$Token@845bf2b,
        device=null, active=false, resumeAction=null, resumption=false,
        notificationKey=0|com.takahashirinta.ncrust|1|null|10274, hasCheckedForResume=true)}
    useMediaResumption: true
```

This is `com.android.systemui.media.MediaDataManager` — the AOSP component that feeds the media card
in the shade / quick-settings / Control Center area. Its entry for Ncrust was built **from the Ncrust
session metadata**, and it captured:

* `song=像一幅画` ← `METADATA_KEY_TITLE` (the lyric line; note SystemUI has **no** dedicated lyrics slot)
* `artist=紫荆花盛开 · 李荣浩/梁咏琪` ← `METADATA_KEY_ARTIST`
* `artwork=Icon(typ=BITMAP size=512x512)` ← the bitmap key (the app writes no `ART_URI`, so this can
  only be `METADATA_KEY_ART`)

So "the ROM does not consume the key" is **ruled out** for the keys Ncrust actually publishes: the
ROM consumed them and holds the lyric string in its own model. `active=false` is recorded as
observed; it is a state flag of the entry, not evidence about key consumption.

Additional check: the string `lyric` does not occur anywhere in the whole
`dumpsys activity service SystemUIService` output (1956 lines) — `<NO MATCH>`.
`MediaData` as printed exposes `artist`, `song`, `artwork`, `actions`, … and **no lyrics field**.

### 4.3 Which platform metadata keys each component even knows about

Read-only static scan, `unzip -p <apk> classes*.dex | strings | grep` run over the device (nothing
pulled, nothing written; full transcript + exact loop in
`WVQ6R22124000968/t3_rom_metadata_key_scan.txt`):

| Component | `android.media.metadata.*` keys referenced |
|---|---|
| `SystemUI.apk` | **31** = the complete platform set: `TITLE, ARTIST, ALBUM, …, DISPLAY_TITLE, DISPLAY_SUBTITLE, DISPLAY_DESCRIPTION, ART, ART_URI, DURATION, …` |
| `MediaPlaybackController.apk` | **3**: `android.media.metadata.ART`, `android.media.metadata.ARTIST`, `android.media.metadata.TITLE` |

Neither one references any lyrics key. **`LYRICS` is not a member of the 31-key platform set** — so
there was never a key for Ncrust to publish into.

This scan has a **positive control**: the same pipeline returns 31 real key constants for SystemUI,
so it demonstrably finds key constants when they exist. Its absence result is therefore a real
absence of a lyric *key* token, not a broken scan.

### 4.4 Huawei *does* ship a lyrics feature — but it is a private, file-based channel

`MediaPlaybackController.apk` contains a substantial lyric subsystem (95 `lyric`-matching strings),
e.g. `LyricUtil`, `LyricStateData`, `getLyric`, `getLyricState`, `updateLyric`,
`EVENT_LYRIC_STATE_CHANGED`, `key_lyric_state`, `updateMediaCommand LYRIC_STATE`,
`getContentFromLyricFile`, `writeToLyricFile`, `getLocalLyricImg`,
`LocalCardAdapterHelper: globalLrc audio card is null,localLyricBtn is null`,
`jumpLyricPermission`, `LyricUtil packageManager is null in isAppOpAllowed`,
`createLyricDialog`, `dismissLyricDialog`, `ic_sing_lyric_lock`, `ic_sing_lyric_notification`,
`show_lyric_guide`, `lyric_switch_on/off`, `lyric_lock_canceled`, `lyricStateNo`, `/lyric_times`,
and three metadata-shaped tokens `LYRICS_DISPLAY`, `LYRICS_HIDE`, `LYRICS_UNLOOK` (which sit in the
`LYRIC_STATE` value family, not in the `android.media.metadata.*` namespace).

This is a Huawei-private lyric protocol: permission-gated
(`jumpLyricPermission` / `isAppOpAllowed`), **file-based** (`getContentFromLyricFile` /
`writeToLyricFile` / `getLocalLyricImg` / "local lyric"), and driven by its own state events
(`updateMediaCommand LYRIC_STATE`, `EVENT_LYRIC_STATE_CHANGED`) rather than by MediaSession metadata.
Whether any given third-party app may use it cannot be established from adb.

**Ncrust does not implement it**: a source search for
`LYRIC_STATE|LyricStateData|huawei.*lyric|lyric.*huawei|com\.huawei|jumpLyricPermission|isAppOpAllowed|MEDIA_CONTROLLER_CENTER`
over `app/src/**/*.kt,*.xml` returns **0 matches**.

---

## 5. "App never published the key" vs "ROM does not consume the key"

These are distinguishable here, and the answer differs per key class.

**(a) For the keys Ncrust does publish — `TITLE`, `ARTIST`, `DURATION`, `DISPLAY_SUBTITLE`, `ART`:**
the ROM **does** consume them. Direct proof in §4.2: SystemUI's `MediaDataManager` entry for Ncrust
was populated with `song`, `artist` and a 512×512 `artwork` bitmap taken from the session, and the
value it holds for `song` is the lyric line. Nothing in the observed path discards a published key.

**(b) For a *dedicated* lyrics key: the key does not exist, so neither explanation applies.**
This is not speculation — it is checked against the three places such a key could come from:

| Source | What was checked | Result |
|---|---|---|
| Platform (`android.media`) | the 31 `android.media.metadata.*` constants in Huawei `SystemUI.apk` | no `LYRICS` |
| `androidx.media:media:1.7.0` → `android.support.v4.media.MediaMetadataCompat` (the class the app builds its metadata with) | `javap -p -constants`: 31 `METADATA_KEY_*` constants, and `strings` over all 226 class files `grep -ic lyric` | `0` — no lyrics key |
| `androidx.media3:media3-common:1.5.0` (the version this app pins) | `MediaMetadata`'s 35 `FIELD_*` constants; `strings` over all 289 class files for any `yric` token | only `PICTURE_TYPE_LYRICIST` — **no lyrics field, and therefore no `setLyrics()` to call** |
| `androidx.media3:media3-session:1.5.0` | its platform-key bridging table | exactly the same 31 keys; no `LYRICS` |

So the accurate statement is: **there is no standard MediaSession metadata slot for lyrics on
Android.** Overloading `METADATA_KEY_TITLE` is the only route by which a lyric line can reach a ROM
that renders a media card — and §4.2 shows that on this Huawei ROM the route works end to end. The
task premise "it does NOT publish `METADATA_KEY_DISPLAY_DESCRIPTION`, any custom
`android.media.metadata.LYRICS` key, or media3 `setLyrics()`" is confirmed and explained: the first
is a deliberate non-publication (the committed code writes only the five keys above), the second is
not a real key, and the third does not exist in the pinned media3 version.

Independently, the **app-side** reason `DISPLAY_SUBTITLE` shows as empty is explicit in the committed
source — it is set to `""` on purpose:

```
1050:            // 老车机 / 蓝牙 AVRCP 读的是 SUBTITLE 那一套。v1.6.0 起歌词已经在 TITLE（第一行）了，
1051:            // 再写一遍 SUBTITLE 会在支持三行的车机上重复显示，所以这里一律写空串清掉旧值。
1052:            builder.putString(
1053:                android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
1054:                ""
1055:            )
```

---

## 6. What is verifiable via adb, and what is NOT

### Verifiable via adb (and verified here)
* Which packages hold MediaSessions, their tags, owner pid/uid, `active`, `flags`, controller counts,
  playback state, and the `metadata:size` **count** plus title/artist/album values.
* That two Ncrust sessions exist on API 36 and API 31, and that the media3 session
  (`androidx.media3.session.id.`) is distinct from the legacy `NcrustSession`.
* That the legacy session's TITLE is a lyric line and its ARTIST is `歌名 · 艺人` (module the
  `MediaDisplayLines` rule, which the no-lyric branch cannot produce).
* Notification identity: channel `ncrust_playback`, id `1`, tag `null`, `MediaStyle` template,
  `android.title` / `android.text` strings, action labels, `android.mediaSession` token presence.
* The pref values on two of three devices, and the exact failure text on the third.
* Huawei: which packages provide the media-control UI, and that SystemUI's `MediaDataManager` has
  already parsed the Ncrust session into `song` / `artist` / `artwork` — i.e. a Control Center
  component *did* ingest the lyric-bearing TITLE.
* Static key vocabulary of the ROM components and of the app's own libraries (with a positive control).

### NOT verifiable from adb alone — stated plainly
1. **"Does the Huawei Control Center visually display the lyrics?" — cannot be determined from adb
   alone.** There are no eyes on the screen. What is established is that the ROM's media data model
   holds the lyric string in its `song` field. Whether the card is on screen at that moment, which
   line/field it renders, how it truncates it, and whether the user perceives it as "lyrics" are all
   **visual** questions that require a screenshot or the physical device. This report therefore does
   **not** claim "the Control Center shows lyrics".
2. **The full identity list of metadata keys per session.** `dumpsys media_session` exposes only
   `size` + title/artist/album. Enumerating the actual keys would need an in-process controller (or a
   debuggable build), which is out of scope. `size=5` matching the committed 5-key set is
   correspondence, not enumeration.
3. **Whether a lyrics-specific key *would* be consumed.** Untestable by observation, because no such
   key exists to publish (§5). Only the key-vocabulary analysis speaks to this.
4. **API 24 (S6) metadata behaviour.** No live session was observed and none was forced, because
   doing so requires starting audible playback. This is a measurement gap.
5. **The Huawei tablet's `lyrics_in_media_session` / `lyrics_word_animation` values** — unreadable
   (no `run-as`, no `su`).
6. **The 3 keys inside the media3 session's `metadata: size=3`** — values are not dumped. They cannot
   be lyrics, because media3 1.5.0 has no lyrics field at all (§5), but their identities are unknown.
7. **Whether `MediaPlaybackController`'s private lyric protocol is reachable by a third-party app**,
   and what it would require (permission name, file location, AIDL/Broadcast contract). The strings
   show it exists and is permission-gated; the contract is not derivable from adb dumps.
8. **Why the Huawei notification's `android.largeIcon` is `null`** while the session metadata carries
   the bitmap. `android.reduced.images=Boolean (true)` is the visible correlate, but the reduction
   policy was not measured.
9. **Anything about the two ROMs' rendering** (truncation, which line is bold, colourisation) —
   `backgroundColor=-14136744` / `color=0xffa0d0e0` are recorded, but appearance is not observable.

---

## 7. Method caveats (so the numbers are not over-read)

* **Provenance:** the installed build is the committed `v2.1.4-gpl` (`git HEAD` = `d6b0fce`). The
  working tree was **dirty** during this investigation (concurrent v2.1.5 edits by another agent), so
  every source claim was re-checked with `git show HEAD:…`. The 5-key metadata set is identical at
  HEAD and in the dirty tree (working-tree lines are +61 relative to HEAD). See
  `SOURCE_PROVENANCE.txt`.
* **First control attempt was wrong and is kept, corrected, in the record:**
  `LOCAL_LIBRARY_scan_positive_control.txt` documents that the initial media3 control searched for
  literal `androidx.media3.common.MediaMetadata.*` key strings, which do not exist because media3
  encodes keys as computed `FIELD_*` constants. The control was rebuilt around `FIELD_*`, which then
  passed, before the "no lyrics field in media3 1.5.0" conclusion was drawn.
* **Timing:** captures are a single 15 s-after-launch snapshot per device, not a time series. The two
  live devices were both **paused** at capture, so lyric-line *churn* (2 Hz sampling /
  `LyricNotifyGate` republish) is not exercised by this evidence; only the steady-state key contents.
* **Warm vs cold start:** PLC110 was already foregrounded (`Warning: Activity not started, its
  current task has been brought to the front`); the Huawei tablet and the S6 were genuine cold-ish
  starts. PLC110's session pid/uid were live before the capture, so its state is a *resumed* session,
  which is fine for a metadata inventory.
* **Brand discrepancy:** the brief describes `3B15CD00GB700000` as "OPPO/ColorOS"; the device reports
  `ro.product.brand=OnePlus` / `ro.product.manufacturer=OnePlus` while its notification extras
  (`oplus_small_icon`, `com.oplus.bttestmode`) show a ColorOS-derived ROM. The measured value is
  recorded rather than the brief's label.
