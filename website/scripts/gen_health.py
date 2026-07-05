#!/usr/bin/env python3
"""Parse the regression-model verification log into project-health data.

Source: ``regression_tests/simulations/verify_all_models_log.txt`` — the log
produced by verifying every regression MODEL (not unit tests) against its pinned
mass-balance baseline. Each model appears as a ``Verifying: <path>`` line followed
by ``[PASS] VERIFIED!`` or a failure marker.

Writes ``website/data/health.json`` for the Code page's "Project health" panel.
"""

import json
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent.parent
LOG = REPO_ROOT / "regression_tests" / "simulations" / "verify_all_models_log.txt"
OUT = HERE.parent / "data" / "health.json"


def main() -> int:
    if not LOG.exists():
        print(f"[gen_health] {LOG} not found; leaving existing health.json.")
        return 0

    text = LOG.read_text(encoding="utf-8", errors="replace")

    found = re.search(r"Found\s+(\d+)\s+model file", text)
    declared_total = int(found.group(1)) if found else None

    models = []
    for m in re.finditer(r"^Verifying:\s*(.+?)\s*$\n\s*\[(PASS|FAIL)\]", text, re.MULTILINE):
        models.append({"model": m.group(1).strip(), "passed": m.group(2) == "PASS"})

    passed = sum(1 for x in models if x["passed"])
    total = len(models) if models else (declared_total or 0)

    health = {
        "models_total": total,
        "models_passed": passed,
        "models_failed": total - passed,
        "all_passed": total > 0 and passed == total,
        # A few example names for the panel (kept short).
        "sample": [Path(x["model"]).parent.name or x["model"] for x in models[:6]],
    }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(health, indent=2) + "\n", encoding="utf-8")
    print(f"[gen_health] {passed}/{total} models verified -> {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
