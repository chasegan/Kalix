"""Punch gaps into pseudo_observed_level.csv so the forced-level test exercises
engaging and disengaging at every scale. Blank field = not forced that day.

Input is the complete level series (the unforced original's own level output);
output overwrites pseudo_observed_level.csv; then
`kalix convert pseudo_observed_level.csv pseudo_observed_level.csv.zip`. Run from this directory:

    python make_gaps.py pseudo_observed_level_full.csv

The schedule is deliberate, not random: every scale appears in an odd year
(inflow present) and an even year (inflow zeroed), one gap straddles a year
boundary so the inflow rule flips mid-gap, and the ALTERNATING runs make every
forced day a first forced day, so sid_flux must be NaN right through them.
"""
import csv, sys
from datetime import date, timedelta

GAPS = [  # inclusive ranges
    ("1889-01-01", "1890-12-31"),   # first two years: run starts unforced
    ("2023-07-01", "2025-06-30"),   # last two years: run ends unforced
    ("1925-03-01", "1925-05-31"),   # three months, odd year
    ("1950-06-01", "1950-08-31"),   # three months, even year
    ("1963-11-15", "1964-02-15"),   # three months straddling odd -> even
    ("1900-04-10", "1900-04-16"),   # a week, even year
    ("1933-09-05", "1933-09-11"),   # a week, odd year
    ("SPILL", "SPILL"),             # a week centred on the record's highest level
]
DAYS = ["1912-07-04", "1961-01-20", "2001-12-31", "2010-01-01"]
ALTERNATING = [  # first blank, last blank; every second day blank
    ("1940-05-02", "1940-05-14"),   # even year
    ("1987-10-11", "1987-10-23"),   # odd year
    ("1999-12-26", "2000-01-07"),   # across a year boundary
]

def d(s): return date.fromisoformat(s)

src = sys.argv[1]
rows = list(csv.reader(open(src)))
hdr, body = rows[0], rows[1:]
levels = {r[0]: float(r[1]) for r in body}

peak = max(levels, key=levels.get)
pk = d(peak)
spill = (str(pk - timedelta(days=3)), str(pk + timedelta(days=3)))
print(f"spill week: {spill[0]} -> {spill[1]} (peak {levels[peak]:.3f} m on {peak})")

blank = set()
for a, b in GAPS:
    if a == "SPILL": a, b = spill
    x = d(a)
    while x <= d(b): blank.add(str(x)); x += timedelta(days=1)
blank.update(DAYS)
for a, b in ALTERNATING:
    x = d(a)
    while x <= d(b): blank.add(str(x)); x += timedelta(days=2)

with open("pseudo_observed_level.csv", "w", newline="") as f:
    w = csv.writer(f, lineterminator="\n"); w.writerow(hdr)
    for r in body: w.writerow([r[0], "" if r[0] in blank else r[1]])
print(f"blanked {len(blank)} of {len(body)} days")
