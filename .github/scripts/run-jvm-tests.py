#!/usr/bin/env python3
import re
import subprocess
import sys


command = ["./gradlew", "--no-daemon", "--stacktrace", "--info", "testAlphaDebugUnitTest"]
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
if status == 0:
    raise SystemExit(0)

patterns = (
    re.compile(r"^(?:e:|error:)", re.IGNORECASE),
    re.compile(r"(?:DataBinding|kapt).*(?:error|exception|failed)", re.IGNORECASE),
    re.compile(r"^Caused by:"),
    re.compile(r"(?:cannot find symbol|unresolved reference|Compilation error)", re.IGNORECASE),
)
diagnostics: list[str] = []
for pattern in patterns:
    for line in lines:
        if pattern.search(line) and line not in diagnostics:
            diagnostics.append(line)
        if len(diagnostics) >= 20:
            break
    if len(diagnostics) >= 20:
        break

if not diagnostics:
    diagnostics = lines[-20:]

for diagnostic in diagnostics:
    escaped = diagnostic.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::error title=Gradle JVM test diagnostic::{escaped}")

raise SystemExit(status)
