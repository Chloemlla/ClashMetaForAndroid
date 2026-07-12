#!/usr/bin/env python3
import re
import subprocess
import sys
from pathlib import Path


def main(argv: list[str]) -> int:
    tasks = argv[1:] or ["lintAlphaRelease"]
    command = ["./gradlew", "--no-daemon", "--stacktrace", "--info", *tasks]
    process = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    lines: list[str] = []
    assert process.stdout is not None
    for line in process.stdout:
        print(line, end="")
        lines.append(line.rstrip())

    status = process.wait()
    print_reports()

    if status == 0:
        return 0

    patterns = (
        re.compile(r"^(?:e:|error:|Lint found errors)", re.IGNORECASE),
        re.compile(r"\b(?:Error|Fatal)\b.*\b(?:xml|kt|java|AndroidManifest)\b", re.IGNORECASE),
        re.compile(r"^Caused by:"),
        re.compile(r"(?:cannot find symbol|unresolved reference|Compilation error|lintVital)", re.IGNORECASE),
    )
    diagnostics: list[str] = []
    for pattern in patterns:
        for line in lines:
            if pattern.search(line) and line not in diagnostics:
                diagnostics.append(line)
            if len(diagnostics) >= 30:
                break
        if len(diagnostics) >= 30:
            break
    if not diagnostics:
        diagnostics = lines[-30:]

    for diagnostic in diagnostics:
        escaped = diagnostic.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::error title=Android Lint diagnostic::{escaped}")
    return status


def print_reports() -> None:
    roots = [
        Path("."),
    ]
    candidates: list[Path] = []
    for root in roots:
        candidates.extend(root.glob("**/build/reports/lint-results-*.txt"))
        candidates.extend(root.glob("**/build/intermediates/lint_intermediate_text_report/**/lint-results-*.txt"))

    seen: set[str] = set()
    for report in sorted({str(p) for p in candidates if p.is_file()}):
        if report in seen:
            continue
        seen.add(report)
        path = Path(report)
        print()
        print(f"===== lint report: {path} =====")
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError as exc:
            print(f"(unable to read report: {exc})")
            continue
        if len(text) > 2_000_000:
            print("(report larger than 2MB; showing first 4000 lines)")
            print("\n".join(text.splitlines()[:4000]))
        else:
            print(text)
        print(f"===== end: {path} =====")

    if not seen:
        print("No lint report files were produced.")


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))