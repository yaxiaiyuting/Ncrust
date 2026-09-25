# p1-baseline — `3B15CD00GB700000` (PLC110)

| | |
|---|---|
| Serial | `3B15CD00GB700000` |
| `ro.product.model` | `PLC110` |
| `ro.product.brand` / `manufacturer` | **`OnePlus`** / **`OnePlus`** |
| `ro.build.version.release` (Android) | `16` |
| `ro.build.version.sdk` (API) | `36` |
| `ro.build.display.id` | `PLC110_16.0.10.500(CN01)` |
| `ro.build.characteristics` | `default` |
| Device clock at capture | `Fri Sep 25 12:10:45 CST 2026` |
| App under test | `com.takahashirinta.ncrust` `versionName=2.1.4-gpl` `versionCode=34` `minSdk=24 targetSdk=36`, package flags **no `DEBUGGABLE`** |

> ⚠️ The task brief called this device "OPPO/ColorOS". The device itself reports brand/manufacturer
> `OnePlus`. The ROM is nonetheless ColorOS/OxygenOS-derived — the notification dump contains
> `oplus_small_icon`, `oplus_smallicon_use_app_icon` and `com.oplus.bttestmode`. Both statements are
> recorded here rather than picking one, because the brand string is the measured value.

## Commands that produced each file

`ADB=/usr/bin/adb`. Raw stdout+stderr redirected verbatim.

| File | Command |
|---|---|
| `am_start.txt` | `adb -s 3B15CD00GB700000 shell am start -n com.takahashirinta.ncrust/.MainActivity` |
| `pidof.txt` | `adb -s 3B15CD00GB700000 shell pidof com.takahashirinta.ncrust` |
| `media_session.txt` | `adb -s 3B15CD00GB700000 shell dumpsys media_session` (run 15 s after `am start`) |
| `notification.txt` | `adb -s 3B15CD00GB700000 shell dumpsys notification --noredact` (run 15 s after `am start`) |
| `audio.txt` | `adb -s 3B15CD00GB700000 shell dumpsys audio` |
| `settings_runas.txt` | `adb -s 3B15CD00GB700000 shell run-as com.takahashirinta.ncrust cat /data/data/com.takahashirinta.ncrust/shared_prefs/ncrust_settings.xml` |
| `settings_su.txt` | `adb -s 3B15CD00GB700000 shell su -c 'cat /data/data/com.takahashirinta.ncrust/shared_prefs/ncrust_settings.xml'` |
| `device_props.txt` | the eight `getprop` / `date` calls listed in the file itself |
| `*.exit` | `echo "exit=$?"` immediately after the corresponding adb command |

## Result of this capture

`am_start.txt`: `Warning: Activity not started, its current task has been brought to the front`
(so this was a *warm* foreground, not a cold start; `pidof.txt` → `6487`).

**Two Ncrust sessions** are present — one legacy, one media3:

```
NcrustSession com.takahashirinta.ncrust/NcrustSession/290 (userId=0)
  active=true  flags=3  controllers=6
  state=PlaybackState {state=PAUSED(2), position=171747, buffered position=253573, speed=1.0, …}
  metadata: size=5, description=我一定让自己让自己坚定, 爱笑的眼睛 · 林俊杰, null

androidx.media3.session.id. com.takahashirinta.ncrust/androidx.media3.session.id./289 (userId=0)
  active=true  flags=7  controllers=3
  metadata: size=3, description=null, null, null
  queueTitle=null, size=1
```

`size=5` with title `我一定让自己让自己坚定` (a lyric line of 林俊杰《爱笑的眼睛》, **not** the song
title) and artist `爱笑的眼睛 · 林俊杰` (= `歌名 · 艺人`). The notification carries the same two
strings — `android.title=String (我一定让自己让自己坚定)`, `android.text=String (爱笑的眼睛 · 林俊杰)`
— on channel `ncrust_playback`, id `1`, tag `null`, `android.template=android.app.Notification$MediaStyle`.

## Settings (package is NOT debuggable — but it IS rooted)

`run-as` refuses:
```
run-as: package not debuggable: com.takahashirinta.ncrust
exit=1
```
`su` succeeds (this was not expected from the brief, which assumed only the S6 was rooted):
```
<boolean name="lyrics_in_media_session" value="true" />
<int name="lyrics_word_animation" value="0" />
```
See `settings_runas.txt` (the refusal) and `settings_su.txt` (the full XML).
