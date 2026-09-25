# p1-baseline — `0715f763f54c023a` (Samsung Galaxy S6, SM-G9209)

| | |
|---|---|
| Serial | `0715f763f54c023a` |
| `ro.product.model` | `SM-G9209` |
| `ro.product.brand` / `manufacturer` | `samsung` / `samsung` |
| `ro.build.version.release` (Android) | `7.0` |
| `ro.build.version.sdk` (API) | `24` |
| `ro.build.display.id` | `NRD90M.G9209KEU2ERI2` |
| `ro.build.characteristics` | `phone,emulator,china_wlan` |
| Device clock at capture | `Fri Sep 25 12:10:46 CST 2026` |
| App under test | `com.takahashirinta.ncrust` `versionName=2.1.4-gpl` `versionCode=34` `minSdk=24 targetSdk=36`, package flag **`DEBUGGABLE`** |

> The `emulator` token in `ro.build.characteristics` is a Samsung stock-ROM value; the device is
> attached over USB (`adb devices -l` → `usb:3-1 product:zerofltectc model:SM_G9209`), not an AVD.

## Commands that produced each file

All commands were run as recorded, with `ADB=/usr/bin/adb`. Raw stdout+stderr was redirected
verbatim; nothing was filtered at capture time.

| File | Command |
|---|---|
| `am_start.txt` | `adb -s 0715f763f54c023a shell am start -n com.takahashirinta.ncrust/.MainActivity` |
| `pidof.txt` | `adb -s 0715f763f54c023a shell pidof com.takahashirinta.ncrust` |
| `media_session.txt` | `adb -s 0715f763f54c023a shell dumpsys media_session` (run 15 s after `am start`) |
| `notification.txt` | `adb -s 0715f763f54c023a shell dumpsys notification --noredact` (run 15 s after `am start`) |
| `audio.txt` | `adb -s 0715f763f54c023a shell dumpsys audio` |
| `settings_runas.txt` | `adb -s 0715f763f54c023a shell run-as com.takahashirinta.ncrust cat /data/data/com.takahashirinta.ncrust/shared_prefs/ncrust_settings.xml` |
| `settings_su.txt` | `adb -s 0715f763f54c023a shell su -c 'cat /data/data/com.takahashirinta.ncrust/shared_prefs/ncrust_settings.xml'` |
| `device_props.txt` | the eight `getprop` / `date` calls listed in the file itself |
| `*.exit` | `echo "exit=$?"` immediately after the corresponding adb command |

`capture.sh <serial>` in the parent directory re-runs the whole sequence.

## Result of this capture

`am_start.txt`:
```
Starting: Intent { cmp=com.takahashirinta.ncrust/.MainActivity launchParam=MultiScreenLaunchParams { mDisplayId=0 mFlags=0 } }
Warning: Activity not started, its current task has been brought to the front
exit=0
```
The app process was alive (`pidof.txt` → `28762`), but:

**There is NO Ncrust media session on this device in this capture.** `media_session.txt` lists
exactly one session — `HeadsetMediaButton com.android.server.telecom/HeadsetMediaButton` — and the
only Ncrust lines in the whole dump are the two bookkeeping lines under `User Records`
(`MediaButtonReceiver:…` and `Restored ButtonReceiver:ComponentInfo{com.takahashirinta.ncrust/com.takahashirinta.ncrust.player.PlaybackService}`).
There is likewise **no currently-posted Ncrust notification**: the only Ncrust notification record is
inside the archive section (`mArchive=Archive (58 notifications)`), i.e. it was posted earlier and
removed. No Ncrust entry appears in `dumpsys audio` at all.

Reason: playback was not active, so `PlaybackService` was not running its session. This capture
therefore yields **no metadata evidence for API 24** — see
[`../FINDINGS.md`](../FINDINGS.md) §4 for why a live API-24 capture was deliberately *not* forced
(it would have required starting audio playback on the user's device).

`dumpsys media_session` on API 24 does not print a metadata key list at all, so the
`metadata:size=…, description=…` line quoted in `FINDINGS.md` for the other two devices has no
counterpart here.

## Settings (this device is debuggable *and* rooted, so both paths work)

```
<boolean name="lyrics_in_media_session" value="true" />
<int name="lyrics_word_animation" value="0" />
```
Identical values from `run-as` and from `su`, so the two reads corroborate each other.
See `settings_runas.txt` / `settings_su.txt`.
