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

def backup_includes(root_nodes):
    return {(node.get("domain"), node.get("path")) for node in root_nodes}


def assert_sharedpref_only_backup(includes: set[tuple[str | None, str | None]], label: str) -> None:
    require(includes == {("sharedpref", ".")}, f"{label} must include only sharedpref/.")
    forbidden_domains = {"database", "file", "root", "external", "device_file", "device_root"}
    require(
        not any(domain in forbidden_domains for domain, _ in includes),
        f"{label} includes a sensitive domain",
    )


legacy_backup = ET.parse(ROOT / "app/src/main/res/xml/full_backup_content.xml")
legacy_includes = backup_includes(legacy_backup.findall("./include"))
legacy_excludes = legacy_backup.findall("./exclude")
assert_sharedpref_only_backup(legacy_includes, "legacy backup rules")
require(not legacy_excludes, "legacy backup rules should not need excludes when domains are opt-in")

extraction = ET.parse(ROOT / "app/src/main/res/xml/data_extraction_rules.xml")
for section in ("cloud-backup", "device-transfer"):
    includes = backup_includes(extraction.findall(f"./{section}/include"))
    excludes = extraction.findall(f"./{section}/exclude")
    assert_sharedpref_only_backup(includes, section)
    require(not excludes, f"{section} should not need excludes when domains are opt-in")

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
checksum_index = release_workflow.find("Generate APK checksums")
push_index = release_workflow.find("Commit version and push verified tag")
require(
    0 <= test_index < lint_index < build_index < checksum_index < push_index,
    "release test/lint/build/checksum/push order is unsafe",
)
require("cat signing.properties" not in release_workflow, "release workflow prints signing properties")
require(
    "verify-apk-signing.sh" not in release_workflow
    and "SIGNING_CERT_SHA256" not in release_workflow,
    "release workflow still requires SIGNING_CERT_SHA256 verification",
)
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

pre_release_workflow = (ROOT / ".github/workflows/build-pre-release.yaml").read_text(encoding="utf-8")
require(
    "verify-apk-signing.sh" not in pre_release_workflow
    and "SIGNING_CERT_SHA256" not in pre_release_workflow,
    "pre-release workflow still requires SIGNING_CERT_SHA256 verification",
)


dimens = (ROOT / "design/src/main/res/values/dimens.xml").read_text(encoding="utf-8")
require(
    re.search(r'<dimen name="toolbar_image_action_size">48dp</dimen>', dimens) is not None,
    "toolbar image actions are not 48dp",
)
require(
    re.search(r'<dimen name="item_action_size">48dp</dimen>', dimens) is not None,
    "list row icon actions are not 48dp",
)

interactive_layouts = [
    ROOT / "design/src/main/res/layout/adapter_profile.xml",
    ROOT / "design/src/main/res/layout/adapter_file.xml",
    ROOT / "design/src/main/res/layout/adapter_provider.xml",
    ROOT / "design/src/main/res/layout/component_action_text_field.xml",
]
for layout in interactive_layouts:
    content = layout.read_text(encoding="utf-8")
    require(
        "item_action_size" in content,
        f"{layout.name} does not use the shared 48dp item action size",
    )
    require(
        not re.search(
            r'android:id="@\+id/(menu_view|end_view)"[^>]*android:layout_width="wrap_content"',
            content,
            re.S,
        ),
        f"{layout.name} still uses wrap_content for an interactive row action",
    )

history_paths = subprocess.check_output(
    ["git", "log", "--all", "--name-only", "--pretty=format:", "--", "release.keystore"],
    cwd=ROOT,
    text=True,
    encoding="utf-8",
)
require(
    "release.keystore" not in history_paths,
    "release.keystore is still reachable from Git history",
)

# Development agent-session artifacts must not be tracked in a repo preparing for
# public release (audit F-14).
require(
    not any(path.startswith("outputs/runtime/vibe-sessions/") for path in tracked),
    "development agent-session artifacts under outputs/runtime/vibe-sessions/ are tracked by Git",
)

# The Gradle wrapper distribution must be integrity-checked (audit F-07).
wrapper_properties = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text(
    encoding="utf-8"
)
require(
    re.search(r"(?m)^distributionSha256Sum=[0-9a-f]{64}\s*$", wrapper_properties) is not None,
    "gradle-wrapper.properties does not pin distributionSha256Sum",
)

# Release/pre-release builds must check out the recorded submodule commit, never a
# floating branch tip via --remote, so a tagged build is reproducible (audit F-08).
for workflow_name in ("build-release.yaml", "build-pre-release.yaml"):
    workflow_text = (ROOT / ".github/workflows" / workflow_name).read_text(encoding="utf-8")
    require(
        "submodule update --init --recursive --remote" not in workflow_text,
        f"{workflow_name} updates submodules from a floating branch (--remote) in a release build",
    )

if errors:
    for error in errors:
        print(f"policy error: {error}", file=sys.stderr)
    raise SystemExit(1)

print("repository security and release policy checks passed")
