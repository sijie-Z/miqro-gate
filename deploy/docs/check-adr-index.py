#!/usr/bin/env python3
"""The ADR index must not lie about what is still open (#1062).

`docs/decisions/README.md` is the surface a reader scans to learn which
decisions are settled and which still wait for the owner's word. Nothing kept
it honest, so it drifted three ways at once: ADR-0012/0013 had been Accepted
for a fortnight while the index still called them drafts, ADR-0024 flipped to
"Accepted（部分）" in #1018 without the index following, and ADR-0009–0011 were
never indexed at all. Every one of those PRs was fine on its own; the index was
simply never re-read. That is what a machine is for.

Three checks per index entry, and three per file:

  - every `docs/decisions/NNNN-*.md` appears exactly once, as a link;
  - the line's status family (Accepted / Proposed / Superseded) matches the
    family in the file's own `- 状态：` (or `- Status:`) line;
  - the line sits under the heading that family belongs to:
    「已拍板」 (Accepted) / 「已被取代」 (Superseded) / 「待拍板」 (Proposed).

Only the family is compared, never the prose: an ADR may say "Accepted（部分）"
and explain in its own words what is still open, and that detail belongs in the
file. Plain bullets without an ADR link are ignored, so 「待拍板」 can also carry
a note like "ADR-0024's second phase".

Usage: check-adr-index.py [repo-root]   (defaults to this script's ../..)
Exit 0 = consistent; 1 = findings; 2 = usage.
"""

import os
import re
import sys
from pathlib import Path

ADR_FILE = re.compile(r"^(\d{4})-[0-9a-z-]+\.md$")
INDEX_LINK = re.compile(r"\[([^\]]+)\]\((\d{4}-[0-9a-z-]+\.md)\)")
SECTION_HEADING = re.compile(r"^##\s+(.*?)\s*$")
# Heading token -> family. Checked in order, so a heading containing several
# tokens resolves to the most specific one.
SECTIONS = (("已被取代", "superseded"), ("待拍板", "proposed"), ("已拍板", "accepted"))

FINDINGS: list[str] = []


def family_of(sentence: str) -> str | None:
    """The status family a status line or an index annotation declares.

    Only the head of the sentence is inspected: every ADR states its family in
    the first few words ("**Accepted（部分）——…**"), while the prose that follows
    legitimately talks about *other* ADRs and their statuses.
    """
    head = sentence.strip().lstrip("*").strip()[:80].lower()
    if head.startswith("superseded"):
        return "superseded"
    if head.startswith("proposed") or head.startswith("草案"):
        return "proposed"
    if head.startswith("accepted"):
        return "accepted"
    return None


def annotation_family(link_text: str) -> str | None:
    """The family named by the first balanced （…） group of an index link text.

    Balanced, because the family is itself often parenthesised:
    「…整流重试（Accepted（部分）：选项 B 已交付…）」. Title-only groups such as
    「平台 OIDC 登录（P0a）」 name no family and are skipped.
    """
    families = [family_of(group) for group in balanced_groups(link_text)]
    named = [family for family in families if family is not None]
    if len(named) > 1:
        return "ambiguous"
    return named[0] if named else None


def balanced_groups(text: str) -> list[str]:
    groups, depth, start = [], 0, None
    for index, char in enumerate(text):
        if char == "（":
            if depth == 0:
                start = index + 1
            depth += 1
        elif char == "）" and depth > 0:
            depth -= 1
            if depth == 0 and start is not None:
                groups.append(text[start:index])
    return groups


def status_line(path: Path) -> str | None:
    """The file's own status declaration, in either spelling the repo uses."""
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.match(r"^-\s*(?:状态|Status)\s*[:：]\s*(.+)$", line)
        if match:
            return match.group(1)
    return None


def report(readme: Path, root: Path, lineno: int | None, message: str) -> None:
    location = f"{readme.relative_to(root)}:{lineno}" if lineno else str(readme.relative_to(root))
    if os.environ.get("GITHUB_ACTIONS") == "true":
        where = f"file={readme.relative_to(root)},line={lineno}" if lineno else f"file={readme.relative_to(root)}"
        print(f"::error {where},title=ADR index::{message}")
    else:
        FINDINGS.append(f"{location}: {message}")


def check(root: Path) -> int:
    decisions = root / "docs" / "decisions"
    readme = decisions / "README.md"
    files = sorted(p for p in decisions.glob("*.md") if ADR_FILE.match(p.name))
    if not readme.is_file():
        print(f"missing: {readme.relative_to(root)}", file=sys.stderr)
        return 1

    indexed: dict[str, int] = {}
    section_family: str | None = None
    for lineno, line in enumerate(readme.read_text(encoding="utf-8").splitlines(), start=1):
        heading = SECTION_HEADING.match(line)
        if heading:
            section_family = next(
                (family for token, family in SECTIONS if token in heading.group(1)), None
            )
            continue
        for link_text, target in INDEX_LINK.findall(line):
            if target in indexed:
                report(readme, root, lineno, f"{target} indexed more than once (first at :{indexed[target]})")
                continue
            indexed[target] = lineno
            if not (decisions / target).is_file():
                report(readme, root, lineno, f"{target} is indexed but does not exist")
                continue
            declared = status_line(decisions / target)
            if declared is None:
                report(readme, root, lineno, f"{target} declares no `- 状态：` line — add one (see 0009)")
                continue
            from_file = family_of(declared)
            if from_file is None:
                report(readme, root, lineno, f"{target} has an unrecognised status: {declared[:60]!r}")
                continue
            from_index = annotation_family(link_text)
            if from_index is None:
                report(readme, root, lineno, f"{target} line carries no status annotation; file says {from_file}")
            elif from_index == "ambiguous":
                report(readme, root, lineno, f"{target} line names more than one status family; keep the file's ({from_file})")
            elif from_index != from_file:
                report(readme, root, lineno, f"{target} indexed as {from_index} but the file says {from_file}")
            if section_family is None:
                report(readme, root, lineno, f"{target} sits outside the settled / superseded / pending sections")
            elif section_family != from_file:
                report(readme, root, lineno, f"{target} is {from_file} but sits in the {section_family} section")

    for path in files:
        if path.name not in indexed:
            report(readme, root, None, f"{path.name} is not in the index at all")

    if FINDINGS:
        print("ADR index and docs/decisions/*.md disagree:", file=sys.stderr)
        for finding in FINDINGS:
            print(f"  {finding}", file=sys.stderr)
        print(
            "Fix the status in the ADR file first, then repeat it in the index line "
            "(and put the line in the matching section).",
            file=sys.stderr,
        )
        return 1
    print(f"ok: {len(files)} ADRs, all indexed once, statuses and sections agree")
    return 0


def main() -> int:
    if len(sys.argv) > 2:
        print(__doc__)
        return 2
    root = Path(sys.argv[1]).resolve() if len(sys.argv) == 2 else Path(__file__).resolve().parents[2]
    if not (root / "docs" / "decisions").is_dir():
        print(f"not a MiQroKey checkout: {root}", file=sys.stderr)
        return 2
    return check(root)


if __name__ == "__main__":
    raise SystemExit(main())
