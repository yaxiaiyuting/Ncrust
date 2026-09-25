# `p1-baseline` — real-device media-session metadata inventory (Ncrust v2.1.4-gpl, vcode 34)

Read-only investigation. **No source file was modified, nothing was rebuilt, nothing was installed
or uninstalled, no app data was cleared.** The only device-side state change was the
task-mandated `am start` of the app under test.

* Findings and the capability matrix: **[`FINDINGS.md`](FINDINGS.md)**
* Per device (model, exact commands, raw captures): [`0715f763f54c023a/`](0715f763f54c023a/README.md),
  [`3B15CD00GB700000/`](3B15CD00GB700000/README.md), [`WVQ6R22124000968/`](WVQ6R22124000968/README.md)
* Re-run the baseline capture: `./capture.sh <serial>`
* Re-run the Huawei Control Center discovery: `./t3_huawei_controlcenter.sh WVQ6R22124000968 WVQ6R22124000968`

## Devices

| Serial | Model | Android | API | App flags |
|---|---|---|---|---|
| `0715f763f54c023a` | SM-G9209 (Samsung Galaxy S6) | 7.0 | 24 | `DEBUGGABLE`, rooted |
| `3B15CD00GB700000` | PLC110 (reports brand `OnePlus`; ROM is ColorOS-derived) | 16 | 36 | not debuggable, **rooted** |
| `WVQ6R22124000968` | WGR-W09 (Huawei tablet, HarmonyOS 4.2) | 12 | 31 | not debuggable, **no `su`** |

All three report the same app: `versionName=2.1.4-gpl`, `versionCode=34`, `minSdk=24 targetSdk=36`.

## Evidence files in this directory

| File | What it is |
|---|---|
| `SOURCE_PROVENANCE.txt` | git HEAD / dirty-tree state and the exact committed metadata-writing block that the installed build corresponds to |
| `LOCAL_LIBRARY_lyrics_key_evidence.txt` | `javap`/`strings` dump of `MediaMetadataCompat` (androidx.media 1.7.0) and media3 1.5.0 key constants — shows whether a lyrics key exists to publish at all |
| `LOCAL_LIBRARY_scan_positive_control.txt` | the control that validates the `strings`-over-`.class` scan (the first control attempt was wrong and is documented as wrong) |
| `capture.sh` | the Task-1 baseline capture script |
| `t3_huawei_controlcenter.sh` | the Task-3 Huawei Control Center discovery script |

## Two caveats that apply to everything here

1. **Provenance.** The devices run the committed `v2.1.4-gpl` build (`git HEAD` = `d6b0fce`). The
   working tree was **dirty during this investigation** — another agent had v2.1.5 edits in flight in
   `PlaybackService.kt`, `PlayerViewModel.kt`, `PreloadSlot.kt`, `MusicSource.kt` plus two new files.
   All source claims below were therefore re-verified against `git show HEAD:…`, not against the
   working tree. See `SOURCE_PROVENANCE.txt`. (`PlaybackService.kt` line numbers quoted below are
   **HEAD** line numbers; the working-tree copy has the same 5-key set shifted by +61 lines.)
2. **The device clock reads 2026-09-25.** Timestamps inside the dumps are the devices' own.
