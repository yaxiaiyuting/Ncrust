# p1-baseline — `WVQ6R22124000968` (Huawei WGR-W09 tablet)

| | |
|---|---|
| Serial | `WVQ6R22124000968` |
| `ro.product.model` | `WGR-W09` |
| `ro.product.brand` / `manufacturer` | `HUAWEI` / `HUAWEI` |
| `ro.build.version.release` (Android) | `12` |
| `ro.build.version.sdk` (API) | `31` |
| `ro.build.display.id` | `WGR-W09 4.2.0.213(C00E100R3P9)` (HarmonyOS 4.2 / EMUI 14.2.0 per brief) |
| `ro.build.characteristics` | `tablet` |
| Device clock at capture | `Fri Sep 25 12:10:45 CST 2026` |
| App under test | `com.takahashirinta.ncrust` `versionName=2.1.4-gpl` `versionCode=34` `minSdk=24 targetSdk=36`, package flags **no `DEBUGGABLE`** |

## Commands that produced each file

`ADB=/usr/bin/adb`. Raw stdout+stderr redirected verbatim.

### Task 1 — baseline metadata inventory

| File | Command |
|---|---|
| `am_start.txt` | `adb -s WVQ6R22124000968 shell am start -n com.takahashirinta.ncrust/.MainActivity` |
| `pidof.txt` | `adb -s WVQ6R22124000968 shell pidof com.takahashirinta.ncrust` |
| `media_session.txt` | `adb -s WVQ6R22124000968 shell dumpsys media_session` (run 15 s after `am start`) |
| `notification.txt` | `adb -s WVQ6R22124000968 shell dumpsys notification --noredact` (run 15 s after `am start`) |
| `audio.txt` | `adb -s WVQ6R22124000968 shell dumpsys audio` |
| `device_props.txt` | the eight `getprop` / `date` calls listed in the file itself |
| `*.exit` | `echo "exit=$?"` immediately after the corresponding adb command |

### Task 2 — settings read attempts

| File | Command |
|---|---|
| `settings_runas.txt` | `adb -s WVQ6R22124000968 shell run-as com.takahashirinta.ncrust cat /data/data/com.takahashirinta.ncrust/shared_prefs/ncrust_settings.xml` |
| `settings_su.txt` | `adb -s WVQ6R22124000968 shell su -c 'cat /data/data/com.takahashirinta.ncrust/shared_prefs/ncrust_settings.xml'` |

### Task 3 — Control Center static capability discovery

Reproduce all of it with `../t3_huawei_controlcenter.sh WVQ6R22124000968 .`

| File | Command |
|---|---|
| `t3_pm_list_packages.txt` | `adb -s WVQ6R22124000968 shell "pm list packages \| grep -iE 'systemui\|media\|hw'"` |
| `t3_dumpsys_SystemUIService.txt` | `adb -s WVQ6R22124000968 shell "dumpsys activity service SystemUIService 2>/dev/null \| head -40"` |
| `t3_dumpsys_SystemUIService_FULL.txt` | `adb -s WVQ6R22124000968 shell "dumpsys activity service SystemUIService"` (untruncated, 1956 lines) |
| `t3_huawei_mediacontroller_pkg.txt` | `adb -s WVQ6R22124000968 shell "dumpsys package com.huawei.mediacontroller"` (+ a `grep` for version/flags) |
| `t3_rom_metadata_key_scan.txt` | `unzip -p <rom-apk> <member> \| strings \| grep …` over `classes*.dex` — see the file header for the exact loop |
| `t3_rom_strings_lyrics_scan.txt` | same pipeline, `grep -Ei 'lyric'`, run separately for both APKs |
| `t3_lyrics_tokens_context.txt` | same pipeline, grepping for `LYRICS` / action-shaped / `lyric_state` tokens |
| `t3_cmd_media_session.txt` | `adb -s WVQ6R22124000968 shell cmd media_session list-sessions` and `cmd media_session` (usage) |

**Nothing was written on the device and nothing was pulled off it.** The APK scan pipes
`unzip -p` straight into `strings`; the ROM APKs stay on `/system`. See
`../LOCAL_LIBRARY_scan_positive_control.txt` for the control that validates this scan method.

## Result of this capture

This was a genuine cold start (`am_start.txt` has no "brought to the front" warning;
`pidof.txt` → `22676`).

**Two Ncrust sessions**:

```
NcrustSession com.takahashirinta.ncrust/NcrustSession (userId=0)
  active=true  flags=3  controllers=4
  state=PlaybackState {state=2, position=70205, buffered position=210667, speed=1.0, …}
  metadata: size=5, description=像一幅画, 紫荆花盛开 · 李荣浩/梁咏琪, null

androidx.media3.session.id. com.takahashirinta.ncrust/androidx.media3.session.id. (userId=0)
  active=true  flags=7  controllers=1
  metadata: size=3, description=null, null, null
  queueTitle=null, size=2
```

`NcrustSession` is also the **media button session**:
`Media button session is com.takahashirinta.ncrust/NcrustSession (userId=0)`.

`description=像一幅画, 紫荆花盛开 · 李荣浩/梁咏琪, null` → title `像一幅画` is a lyric line of
《紫荆花盛开》, **not** the song title, and artist is `歌名 · 艺人`. The notification agrees:
`android.title=String (像一幅画)`, `android.text=String (紫荆花盛开 · 李荣浩/梁咏琪)`, channel
`ncrust_playback`, id `1`, tag `null`. Note `android.largeIcon=null` here although the session
metadata carries an ART bitmap — the notification dump also shows
`android.reduced.images=Boolean (true)`, i.e. the system dropped the bitmap for this notification.

## Settings — **could not be read** (recorded honestly, not guessed)

`run-as` refuses:
```
run-as: package not debuggable: com.takahashirinta.ncrust
exit=1
```
`su` does not exist on this device:
```
/system/bin/sh: su: inaccessible or not found
exit=127
```
So the value of `lyrics_in_media_session` **cannot be read** on this device from adb.
It is nonetheless *behaviourally* proven to be ON, because the observed TITLE is a lyric line
(see `../FINDINGS.md` §3).
