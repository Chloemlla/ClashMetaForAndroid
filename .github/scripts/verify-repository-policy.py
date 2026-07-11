#!/usr/bin/env python3
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"
errors: list[str] = []


def require(condition: bool, message: str) -> None:
    if not condition:
        errors.append(message)


tracked = subprocess.check_output(
    ["git", "ls-files"], cwd=ROOT, text=True, encoding="utf-8"
).splitlines()
sensitive_suffixes = (".keystore", ".jks", ".p12", ".pfx")
require(
    not any(
        path.lower().endswith(sensitive_suffixes) and (ROOT / path).is_file()
        for path in tracked
    ),
    "private-key container is tracked by Git",
)
require(
    "signing.properties" not in tracked or not (ROOT / "signing.properties").is_file(),
    "signing.properties is tracked by Git",
)

manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
application = manifest.find("./application")
activities = {
    node.get(ANDROID + "name"): node
    for node in manifest.findall("./application/activity")
}
external = activities.get(".ExternalControlActivity")
internal = activities.get(".InternalControlActivity")
require(external is not None, "ExternalControlActivity is missing")
require(
    internal is not None and internal.get(ANDROID + "exported") == "false",
    "InternalControlActivity must be non-exported",
)
require(
    application is not None
    and application.get(ANDROID + "dataExtractionRules") == "@xml/data_extraction_rules",
    "Android 12+ data extraction rules are not configured",
)
forbidden_actions = {
    "com.github.metacubex.clash.meta.action.START_CLASH",
    "com.github.metacubex.clash.meta.action.STOP_CLASH",
    "com.github.metacubex.clash.meta.action.TOGGLE_CLASH",
}
if external is not None:
    exported_actions = {
        action.get(ANDROID + "name")
        for action in external.findall("./intent-filter/action")
    }
    require(
        exported_actions.isdisjoint(forbidden_actions),
        "exported activity still exposes a VPN control action",
    )

main_activity = (ROOT / "app/src/main/java/com/github/kr328/clash/MainActivity.kt").read_text(
    encoding="utf-8"
)
require(
    main_activity.count("InternalControlActivity::class.java.name") == 3
    and "ExternalControlActivity::class.java.name" not in main_activity,
    "dynamic shortcuts do not exclusively target the internal control activity",
)

required_excludes = {
    ("database", "."),
    ("file", "imported"),
    ("file", "pending"),
    ("file", "clash"),
}
legacy_backup = ET.parse(ROOT / "app/src/main/res/xml/full_backup_content.xml")
legacy_excludes = {
    (node.get("domain"), node.get("path")) for node in legacy_backup.findall("./exclude")
}
require(required_excludes <= legacy_excludes, "legacy backup rules include profile secrets")

extraction = ET.parse(ROOT / "app/src/main/res/xml/data_extraction_rules.xml")
for section in ("cloud-backup", "device-transfer"):
    excludes = {
        (node.get("domain"), node.get("path"))
        for node in extraction.findall(f"./{section}/exclude")
    }
    require(required_excludes <= excludes, f"{section} includes profile secrets")

app_build = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
require("releases/download/latest" not in app_build, "mutable Geo latest URL is present")
require(
    re.search(r'geoReleaseRevision\s*=\s*"[0-9a-f]{40}"', app_build) is not None,
    "Geo revision is not pinned to a commit",
)
require(
    len(set(re.findall(r'"([0-9a-f]{64})"', app_build))) >= 4,
    "Geo SHA-256 manifest is incomplete",
)

root_build = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
require("signingConfigs[\"debug\"]" not in root_build, "release silently falls back to debug signing")
require("keystore.file" in root_build, "release keystore path is not externally configured")

release_workflow = (ROOT / ".github/workflows/build-release.yaml").read_text(encoding="utf-8")
test_index = release_workflow.find("JVM unit tests")
lint_index = release_workflow.find("Android Lint")
build_index = release_workflow.find("Build signed release APKs")
verify_index = release_workflow.find("Verify signed APKs")
push_index = release_workflow.find("Commit version and push verified tag")
require(
    0 <= test_index < lint_index < build_index < verify_index < push_index,
    "release test/lint/build/verify/push order is unsafe",
)
require("cat signing.properties" not in release_workflow, "release workflow prints signing properties")
for secret_name in ("KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"):
    require(
        f"secrets.{secret_name}" in release_workflow,
        f"release workflow does not use the shared {secret_name} secret",
    )
require(
    "SIGNING_KEYSTORE_BASE64" not in release_workflow
    and "SIGNING_STORE_PASSWORD" not in release_workflow
    and "SIGNING_KEY_ALIAS" not in release_workflow
    and "SIGNING_KEY_PASSWORD" not in release_workflow,
    "release workflow still uses legacy stable-only signing secret names",
)

if errors:
    for error in errors:
        print(f"policy error: {error}", file=sys.stderr)
    raise SystemExit(1)

print("repository security and release policy checks passed")
