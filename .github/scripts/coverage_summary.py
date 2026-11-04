"""Turns JaCoCo's CSV into a Markdown table for the GitHub Actions job summary."""
import csv
import sys
from collections import defaultdict

ROOT = "com.kgiannopoulou.ledgerforge"

totals = defaultdict(lambda: [0, 0, 0, 0])
with open(sys.argv[1], encoding="utf-8") as report:
    for row in csv.DictReader(report):
        package = row["PACKAGE"].removeprefix(ROOT).lstrip(".").split(".")[0] or "(root)"
        for key in (package, "TOTAL"):
            totals[key][0] += int(row["LINE_COVERED"])
            totals[key][1] += int(row["LINE_MISSED"])
            totals[key][2] += int(row["BRANCH_COVERED"])
            totals[key][3] += int(row["BRANCH_MISSED"])


def percent(covered, missed):
    return f"{100 * covered / (covered + missed):.1f}%" if covered + missed else "n/a"


print("### Coverage\n\n| Module | Lines | Branches |\n|---|---|---|")
for name in sorted(k for k in totals if k != "TOTAL") + ["TOTAL"]:
    lines_hit, lines_missed, branches_hit, branches_missed = totals[name]
    print(f"| {name} | {percent(lines_hit, lines_missed)} | {percent(branches_hit, branches_missed)} |")
