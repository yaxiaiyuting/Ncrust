QUARANTINED CAPTURE — NOT VALID EVIDENCE
========================================
A first attempt at the PLAYING variant of T3 was captured under the names
06-after-manual-scroll / 07-after-8s-idle.  The PNG mtimes show the gap between
those two captures was measured as 2.4 s, NOT the >= 8 s the procedure requires.
2.4 s is SHORTER than the 5 s userScrolling timeout, so that pair cannot test the
idle behaviour at all.  It was therefore discarded and the trial redone with
in-process timing, giving measured offsets +1.80 s and +8.62 s.

WHAT SURVIVES HERE
  the two uiautomator dumps of that invalid attempt:
    06-after-manual-scroll-GAP-WAS-2.4s-NOT-8s.xml
    07-after-8s-idle-GAP-WAS-2.4s-NOT-8s.xml

WHAT DOES NOT SURVIVE
  the two PNGs of that invalid attempt no longer exist as separate files: the
  correctly-timed captures were installed over the same paths with `cp`, so the
  2.4 s images were overwritten.  This is stated here rather than implied.

REPLACEMENT (correctly timed, measured offsets from the swipe):
  screenshots/06-after-manual-scroll.png  = PLAYING-A-immediate  (+1.80 s)
  screenshots/07-after-8s-idle.png        = PLAYING-B-8s         (+8.62 s)
  ui-06-after-scroll.xml                  = PLAYING-A-immediate.xml
  ui-07-after-8s.xml                      = PLAYING-B-8s.xml
Both originals are still present under their own names in screenshots/ and in
docs/verification/v2.3.0/ as PLAYING-A-immediate.* / PLAYING-B-8s.*.
