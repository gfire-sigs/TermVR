#!/usr/bin/env bash
# Check and synchronize TermVR release versions against upstream gvx tags.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

exec python3 - "${DEFAULT_REPO_ROOT}" "$@" <<'PY'
import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

EXPECTED_UPDATE_CHECK_MODE = r"Tags ^v[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?$"
TAG_RE = re.compile(r"^v([0-9]+)\.([0-9]+)\.([0-9]+)$")
EXPECT_TAG_RE = re.compile(r"^v[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?$")
SHA40_RE = re.compile(r"^[0-9a-fA-F]{40}$")
GVX_STAMP_RE = re.compile(r"^1([0-9]{3})(20[0-9]{2})(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])([0-9]{2})$")
STAMP_RE = re.compile(r"^[0-9]*(20[0-9]{2})(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])([0-9]{2})$")
VERSION_CODE_RE = re.compile(r"^([2-9][0-9])(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])([0-9]{2})([0-9])$")


class SyncError(RuntimeError):
    pass


def log(msg: str) -> None:
    print(f"[sync-gvx] {msg}")


def atomic_write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    mode = path.stat().st_mode if path.exists() else 0o644
    fd, tmp_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=str(path.parent))
    tmp_path = Path(tmp_name)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(tmp_path, mode)
        os.replace(tmp_path, path)
    except Exception:
        tmp_path.unlink(missing_ok=True)
        raise


def parse_gvx_tag(tag: str) -> tuple[int, int, int, str]:
    match = TAG_RE.match(tag)
    if not match:
        raise SyncError(f"Invalid gvx tag '{tag}' (expected v<major>.<minor>.<patch>)")
    major_s, minor_s, patch_s = match.groups()
    return int(major_s), int(minor_s), int(patch_s), patch_s


def extract_gvx_stamp(patch_str: str) -> int | None:
    match = GVX_STAMP_RE.match(patch_str)
    if not match:
        return None
    ccc_s, year_s, month_s, day_s, _ss_s = match.groups()
    year, month, day = int(year_s), int(month_s), int(day_s)
    try:
        datetime.date(year, month, day)
    except ValueError:
        return None
    if year < 2020 or year > 2099:
        return None
    return int(ccc_s)


def compute_gvx_stamp_version_code(major: int, minor: int, ccc: int, app_rev: int) -> int:
    if not (0 <= major <= 18):
        raise SyncError(f"gvx major {major} out of supported 0..18 range")
    if not (0 <= minor <= 999):
        raise SyncError(f"gvx minor {minor} out of supported 0..999 range")
    if not (0 <= ccc <= 999):
        raise SyncError(f"gvx counter {ccc} out of supported 0..999 range")
    if not (0 <= app_rev <= 9):
        raise SyncError(f"app_revision must be in 0..9, got {app_rev}")
    version_code = (major + 3) * 100_000_000 + minor * 100_000 + ccc * 10 + app_rev
    if version_code <= 261_109_001 or version_code > 2_147_483_647:
        raise SyncError(f"Computed versionCode {version_code} violates monotonic 32-bit Android Int bounds")
    return version_code


def extract_embedded_stamp(patch_str: str) -> tuple[int, int, int, int] | None:
    match = STAMP_RE.match(patch_str)
    if not match:
        return None
    year_s, month_s, day_s, gg_s = match.groups()
    year, month, day, gg = int(year_s), int(month_s), int(day_s), int(gg_s)
    try:
        datetime.date(year, month, day)
    except ValueError:
        return None
    if year < 2020 or year > 2099:
        return None
    return year % 100, month, day, gg


def resolve_commit_utc_date(repo: str, commit_sha: str, explicit_date: str | None) -> datetime.date:
    if explicit_date:
        try:
            return datetime.date.fromisoformat(explicit_date)
        except ValueError as exc:
            raise SyncError(f"Invalid --commit-date '{explicit_date}' (expected YYYY-MM-DD)") from exc

    local_repo = Path(repo)
    if local_repo.exists():
        res = subprocess.run(
            ["git", "-C", str(local_repo), "show", "-s", "--format=%ct", commit_sha],
            capture_output=True,
            text=True,
            timeout=15,
        )
        if res.returncode == 0 and res.stdout.strip().isdigit():
            ts = int(res.stdout.strip())
            return datetime.datetime.fromtimestamp(ts, tz=datetime.timezone.utc).date()

    with tempfile.TemporaryDirectory(prefix="termvr-gvx-date.") as tmp_dir:
        init_res = subprocess.run(["git", "init", "-q", tmp_dir], capture_output=True, text=True, timeout=10)
        if init_res.returncode != 0:
            raise SyncError(f"git init failed while resolving commit date: {init_res.stderr.strip()}")
        fetch_res = subprocess.run(
            ["git", "-C", tmp_dir, "fetch", "--depth=1", repo, commit_sha],
            capture_output=True,
            text=True,
            timeout=20,
        )
        if fetch_res.returncode != 0:
            raise SyncError(
                f"Unable to resolve commit UTC date for {commit_sha} from {repo}; "
                "pass --commit-date YYYY-MM-DD when offline"
            )
        show_res = subprocess.run(
            ["git", "-C", tmp_dir, "show", "-s", "--format=%ct", "FETCH_HEAD"],
            capture_output=True,
            text=True,
            timeout=10,
        )
        if show_res.returncode != 0 or not show_res.stdout.strip().isdigit():
            raise SyncError(f"git show failed for {commit_sha}")
        ts = int(show_res.stdout.strip())
        return datetime.datetime.fromtimestamp(ts, tz=datetime.timezone.utc).date()


def compute_versions(
    tag: str,
    commit_sha: str,
    app_rev: int,
    repo: str,
    explicit_commit_date: str | None = None,
) -> tuple[str, int]:
    if not (0 <= app_rev <= 9):
        raise SyncError(f"app_revision must be in 0..9, got {app_rev}")
    if not SHA40_RE.match(commit_sha):
        raise SyncError(f"Invalid 40-hex commit SHA: {commit_sha}")
    major, minor, patch, patch_str = parse_gvx_tag(tag)
    base_name = f"{major}.{minor}.{patch_str}"
    version_name = base_name if app_rev == 0 else f"{base_name}.{app_rev}"

    ccc = extract_gvx_stamp(patch_str)
    if ccc is not None:
        version_code = compute_gvx_stamp_version_code(major, minor, ccc, app_rev)
    else:
        stamp = extract_embedded_stamp(patch_str)
        if stamp is not None:
            yy, mm, dd, gg = stamp
        else:
            commit_date = resolve_commit_utc_date(repo, commit_sha.lower(), explicit_commit_date)
            if commit_date.year < 2020 or commit_date.year > 2099:
                raise SyncError(f"Commit UTC year {commit_date.year} out of supported 2020..2099 range")
            yy = commit_date.year % 100
            mm = commit_date.month
            dd = commit_date.day
            gg = patch % 100

        version_code = int(f"{yy:02d}{mm:02d}{dd:02d}{gg:02d}{app_rev}")
    if version_code <= 0 or version_code > 2_147_483_647:
        raise SyncError(f"Computed versionCode {version_code} overflows signed 32-bit Android Int")
    return version_name, version_code


def fetch_latest_upstream_tag(repo: str) -> tuple[str, str]:
    res = subprocess.run(
        ["git", "ls-remote", "--tags", repo],
        capture_output=True,
        text=True,
        timeout=20,
    )
    if res.returncode != 0:
        raise SyncError(f"git ls-remote failed for {repo}: {res.stderr.strip()}")

    raw_tags: dict[str, str] = {}
    peeled_tags: dict[str, str] = {}
    for line in res.stdout.splitlines():
        line = line.strip()
        if not line or "\t" not in line:
            continue
        sha, ref = line.split("\t", 1)
        if not SHA40_RE.match(sha):
            continue
        if not ref.startswith("refs/tags/"):
            continue
        name = ref[len("refs/tags/"):]
        if name.endswith("^{}"):
            peeled_tags[name[:-3]] = sha.lower()
        else:
            raw_tags[name] = sha.lower()

    candidates: list[tuple[tuple[int, int, int], str, str]] = []
    for tag_name, direct_sha in raw_tags.items():
        match = TAG_RE.match(tag_name)
        if not match:
            continue
        major, minor, patch = (int(match.group(1)), int(match.group(2)), int(match.group(3)))
        commit_sha = peeled_tags.get(tag_name, direct_sha)
        candidates.append(((major, minor, patch), tag_name, commit_sha))

    if not candidates:
        raise SyncError(f"No matching v<major>.<minor>.<patch> tags found at {repo}")

    candidates.sort(key=lambda item: item[0])
    _, best_tag, best_sha = candidates[-1]
    return best_tag, best_sha


def replace_exact_once(text: str, pattern: str, replacement: str, label: str) -> str:
    new_text, count = re.subn(pattern, replacement, text, count=1, flags=re.M)
    if count != 1:
        raise SyncError(f"Expected 1 match for {label}, found {count}")
    return new_text


def parse_toml_versions(toml_text: str) -> dict[str, str]:
    in_versions = False
    values: dict[str, str] = {}
    for raw_line in toml_text.splitlines():
        line = raw_line.split("#", 1)[0].strip()
        if not line:
            continue
        if line.startswith("["):
            in_versions = line == "[versions]"
            continue
        if in_versions and "=" in line:
            k, v = [part.strip() for part in line.split("=", 1)]
            if len(v) >= 2 and v[0] == '"' and v[-1] == '"':
                v = v[1:-1]
            values[k] = v
    return values


def update_versions_toml(toml_text: str, gvx_tag: str, gvx_commit: str, version_name: str, version_code: int) -> str:
    toml_text = replace_exact_once(toml_text, r'^gvx\s*=\s*"[^"]*"$', f'gvx = "{gvx_tag}"', "libs.versions.toml gvx")
    toml_text = replace_exact_once(
        toml_text, r'^gvxCommit\s*=\s*"[^"]*"$', f'gvxCommit = "{gvx_commit}"', "libs.versions.toml gvxCommit"
    )
    toml_text = replace_exact_once(
        toml_text,
        r'^termvrVersionName\s*=\s*"[^"]*"$',
        f'termvrVersionName = "{version_name}"',
        "libs.versions.toml termvrVersionName",
    )
    toml_text = replace_exact_once(
        toml_text,
        r'^termvrVersionCode\s*=\s*"[^"]*"$',
        f'termvrVersionCode = "{version_code}"',
        "libs.versions.toml termvrVersionCode",
    )
    return toml_text


def update_fdroid_yml(yml_text: str, gvx_commit: str, version_name: str, version_code: int) -> str:
    yml_text = replace_exact_once(
        yml_text, r"^(\s*-\s*versionName:\s*).*$", rf"\g<1>{version_name}", "fdroid Builds versionName"
    )
    yml_text = replace_exact_once(
        yml_text, r"^(\s*versionCode:\s*).*$", rf"\g<1>{version_code}", "fdroid Builds versionCode"
    )
    yml_text = replace_exact_once(
        yml_text, r"^(\s*commit:\s*).*$", rf"\g<1>v{version_name}", "fdroid Builds commit"
    )
    yml_text = replace_exact_once(
        yml_text, r"^(\s*-\s*gvx@)[0-9a-fA-F]{40}\s*$", rf"\g<1>{gvx_commit}", "fdroid srclibs gvx"
    )
    yml_text = replace_exact_once(
        yml_text,
        r"^(\s*UpdateCheckMode:\s*).*$",
        lambda m: f"{m.group(1)}{EXPECTED_UPDATE_CHECK_MODE}",
        "fdroid UpdateCheckMode",
    )
    yml_text = replace_exact_once(
        yml_text, r"^(\s*CurrentVersion:\s*).*$", rf"\g<1>{version_name}", "fdroid CurrentVersion"
    )
    yml_text = replace_exact_once(
        yml_text, r"^(\s*CurrentVersionCode:\s*).*$", rf"\g<1>{version_code}", "fdroid CurrentVersionCode"
    )
    return yml_text


def ensure_fastlane_changelog(repo_root: Path, version_name: str, version_code: int, gvx_tag: str, gvx_commit: str) -> Path:
    changelog_path = repo_root / "fastlane/metadata/android/en-US/changelogs" / f"{version_code}.txt"
    if not changelog_path.exists():
        stub = (
            f"TermVR {version_name} aligns with upstream gvx {gvx_tag}.\n"
            f"- Updated pinned gvx runtime commit to {gvx_commit[:12]}.\n"
        )
        if len(stub.encode("utf-8")) > 500:
            raise SyncError("Generated Fastlane changelog exceeds 500 bytes")
        atomic_write_text(changelog_path, stub)
        log(f"Created Fastlane changelog stub: {changelog_path.relative_to(repo_root)}")
    return changelog_path


def run_check(repo_root: Path, expect_tag: str | None) -> None:
    lock_path = repo_root / "scripts/source-lock.json"
    toml_path = repo_root / "gradle/libs.versions.toml"
    gradle_path = repo_root / "app/build.gradle.kts"
    fdroid_path = repo_root / "fdroid/dev.gfire.termvr.yml"

    try:
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
    except Exception as exc:
        raise SyncError(f"Failed to parse {lock_path}: {exc}") from exc

    if "sources" not in lock or "gvx" not in lock["sources"]:
        raise SyncError("scripts/source-lock.json missing sources.gvx")
    if "termvr" not in lock or not isinstance(lock["termvr"], dict):
        raise SyncError("scripts/source-lock.json missing termvr object")

    gvx_meta = lock["sources"]["gvx"]
    termvr_meta = lock["termvr"]
    gvx_tag = str(gvx_meta.get("tag", ""))
    gvx_commit = str(gvx_meta.get("commit", ""))
    app_rev = termvr_meta.get("app_revision")
    version_name = str(termvr_meta.get("version_name", ""))
    version_code = termvr_meta.get("version_code")

    if not SHA40_RE.match(gvx_commit) or gvx_commit != gvx_commit.lower():
        raise SyncError(f"source-lock.json sources.gvx.commit must be 40 lowercase hex chars: {gvx_commit}")
    if not isinstance(app_rev, int) or isinstance(app_rev, bool) or not (0 <= app_rev <= 9):
        raise SyncError(f"source-lock.json termvr.app_revision must be int in 0..9: {app_rev!r}")
    if not isinstance(version_code, int) or isinstance(version_code, bool) or not (1 <= version_code <= 2_147_483_647):
        raise SyncError(f"source-lock.json termvr.version_code out of 32-bit positive Int range: {version_code!r}")

    major, minor, patch, patch_str = parse_gvx_tag(gvx_tag)
    expected_name = f"{major}.{minor}.{patch_str}" if app_rev == 0 else f"{major}.{minor}.{patch_str}.{app_rev}"
    if version_name != expected_name:
        raise SyncError(f"source-lock.json termvr.version_name '{version_name}' != expected '{expected_name}'")

    ccc = extract_gvx_stamp(patch_str)
    if ccc is not None:
        expected_code = compute_gvx_stamp_version_code(major, minor, ccc, app_rev)
        if version_code != expected_code:
            raise SyncError(f"source-lock.json termvr.version_code {version_code} != expected {expected_code}")
    else:
        stamp = extract_embedded_stamp(patch_str)
        if stamp is not None:
            yy, mm, dd, gg = stamp
            expected_code = int(f"{yy:02d}{mm:02d}{dd:02d}{gg:02d}{app_rev}")
            if version_code != expected_code:
                raise SyncError(f"source-lock.json termvr.version_code {version_code} != expected {expected_code}")
        else:
            code_match = VERSION_CODE_RE.match(str(version_code))
            if not code_match:
                raise SyncError(f"version_code {version_code} does not match YYMMDDGGR format")
            yy_s, mm_s, dd_s, gg_s, rev_s = code_match.groups()
            try:
                datetime.date(2000 + int(yy_s), int(mm_s), int(dd_s))
            except ValueError as exc:
                raise SyncError(f"version_code {version_code} embeds invalid calendar date") from exc
            if int(gg_s) != patch % 100 or int(rev_s) != app_rev:
                raise SyncError(
                    f"version_code {version_code} GG/R ({gg_s}/{rev_s}) != expected ({patch % 100:02d}/{app_rev})"
                )

    versions = parse_toml_versions(toml_path.read_text(encoding="utf-8"))
    checks = {
        "gvx": gvx_tag,
        "gvxCommit": gvx_commit,
        "termvrVersionName": version_name,
        "termvrVersionCode": str(version_code),
    }
    for key, expected_val in checks.items():
        actual_val = versions.get(key)
        if actual_val != expected_val:
            raise SyncError(f"gradle/libs.versions.toml {key}='{actual_val}' != expected '{expected_val}'")

    gradle_text = gradle_path.read_text(encoding="utf-8")
    required_gradle_snippets = [
        "versionCode = libs.versions.termvrVersionCode.get().toInt()",
        "versionName = libs.versions.termvrVersionName.get()",
        'buildConfigField("String", "GVX_VERSION", "\\"${libs.versions.gvx.get()}\\"")',
        'buildConfigField("String", "GVX_COMMIT", "\\"${libs.versions.gvxCommit.get()}\\"")',
    ]
    for snippet in required_gradle_snippets:
        if snippet not in gradle_text:
            raise SyncError(f"app/build.gradle.kts missing expected wiring: {snippet}")
    if re.search(r"^\s*versionCode\s*=\s*[0-9]+", gradle_text, re.M):
        raise SyncError("app/build.gradle.kts contains hardcoded numeric versionCode")
    if re.search(r'^\s*versionName\s*=\s*"[^"]+"', gradle_text, re.M):
        raise SyncError("app/build.gradle.kts contains hardcoded string versionName")

    fdroid_text = fdroid_path.read_text(encoding="utf-8")
    fdroid_checks = [
        (rf"^\s*-\s*versionName:\s*{re.escape(version_name)}\s*$", f"versionName: {version_name}"),
        (rf"^\s*versionCode:\s*{version_code}\s*$", f"versionCode: {version_code}"),
        (rf"^\s*commit:\s*v{re.escape(version_name)}\s*$", f"commit: v{version_name}"),
        (rf"^\s*-\s*gvx@{re.escape(gvx_commit)}\s*$", f"gvx@{gvx_commit}"),
        (rf"^\s*UpdateCheckMode:\s*{re.escape(EXPECTED_UPDATE_CHECK_MODE)}\s*$", f"UpdateCheckMode: {EXPECTED_UPDATE_CHECK_MODE}"),
        (rf"^\s*CurrentVersion:\s*{re.escape(version_name)}\s*$", f"CurrentVersion: {version_name}"),
        (rf"^\s*CurrentVersionCode:\s*{version_code}\s*$", f"CurrentVersionCode: {version_code}"),
    ]
    for pattern, desc in fdroid_checks:
        if not re.search(pattern, fdroid_text, re.M):
            raise SyncError(f"fdroid/dev.gfire.termvr.yml missing or mismatched: {desc}")

    changelog_path = repo_root / "fastlane/metadata/android/en-US/changelogs" / f"{version_code}.txt"
    if not changelog_path.is_file():
        raise SyncError(f"Missing Fastlane changelog: {changelog_path.relative_to(repo_root)}")
    changelog_bytes = changelog_path.read_bytes()
    if len(changelog_bytes.strip()) == 0:
        raise SyncError(f"Fastlane changelog is empty: {changelog_path.relative_to(repo_root)}")
    if len(changelog_bytes) > 500:
        raise SyncError(
            f"Fastlane changelog {changelog_path.relative_to(repo_root)} is {len(changelog_bytes)} bytes (limit 500)"
        )

    if expect_tag is not None:
        if not EXPECT_TAG_RE.match(expect_tag):
            raise SyncError(f"Expected tag '{expect_tag}' does not match v<major>.<minor>.<patch>[.<rev>]")
        expected_release_tag = f"v{version_name}"
        if expect_tag != expected_release_tag:
            raise SyncError(f"Expected release tag '{expect_tag}' != '{expected_release_tag}'")

    log(f"OK: gvx={gvx_tag} ({gvx_commit[:12]}) versionName={version_name} versionCode={version_code}")


def run_sync(
    repo_root: Path,
    gvx_tag: str,
    gvx_commit: str,
    app_rev: int,
    repo_url: str,
    commit_date: str | None,
    dry_run: bool,
) -> None:
    gvx_commit = gvx_commit.lower()
    version_name, version_code = compute_versions(gvx_tag, gvx_commit, app_rev, repo_url, commit_date)

    lock_path = repo_root / "scripts/source-lock.json"
    toml_path = repo_root / "gradle/libs.versions.toml"
    fdroid_path = repo_root / "fdroid/dev.gfire.termvr.yml"

    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    lock["sources"]["gvx"]["tag"] = gvx_tag
    lock["sources"]["gvx"]["commit"] = gvx_commit
    lock["termvr"] = {
        "app_revision": app_rev,
        "version_name": version_name,
        "version_code": version_code,
    }
    new_lock = json.dumps(lock, indent=2) + "\n"
    new_toml = update_versions_toml(toml_path.read_text(encoding="utf-8"), gvx_tag, gvx_commit, version_name, version_code)
    new_fdroid = update_fdroid_yml(fdroid_path.read_text(encoding="utf-8"), gvx_commit, version_name, version_code)

    if dry_run:
        log(f"DRY RUN: gvx={gvx_tag} ({gvx_commit[:12]}) app_rev={app_rev} versionName={version_name} versionCode={version_code}")
        return

    atomic_write_text(lock_path, new_lock)
    atomic_write_text(toml_path, new_toml)
    atomic_write_text(fdroid_path, new_fdroid)
    ensure_fastlane_changelog(repo_root, version_name, version_code, gvx_tag, gvx_commit)
    log(f"Synced: gvx={gvx_tag} ({gvx_commit[:12]}) versionName={version_name} versionCode={version_code}")


def main(argv: list[str]) -> int:
    default_root = Path(argv[1])
    parser = argparse.ArgumentParser(
        prog="sync-gvx-version.sh",
        description="Verify or synchronize TermVR versions with upstream gvx tags.",
    )
    parser.add_argument("--root", type=Path, default=default_root, help="Repository root path")
    parser.add_argument("--check", action="store_true", help="Verify version parity across all manifests")
    parser.add_argument("--expect-tag", help="Expected TermVR release tag (v<versionName>) during --check")
    parser.add_argument("--gvx-tag", help="Target upstream gvx tag (v<major>.<minor>.<patch>)")
    parser.add_argument("--gvx-commit", help="Target upstream gvx 40-hex commit SHA")
    parser.add_argument("--app-rev", type=int, help="TermVR app revision suffix (0..9)")
    parser.add_argument("--fetch-latest", action="store_true", help="Resolve latest upstream gvx tag via git ls-remote")
    parser.add_argument("--repo", help="Git repository URL or local path for --fetch-latest / commit date lookup")
    parser.add_argument("--commit-date", help="Optional YYYY-MM-DD UTC commit date for non-stamp SemVer fallback")
    parser.add_argument("--dry-run", action="store_true", help="Compute and validate without modifying files")
    args = parser.parse_args(argv[2:])

    repo_root = args.root.resolve()
    lock_path = repo_root / "scripts/source-lock.json"
    if not lock_path.is_file():
        raise SyncError(f"Missing lockfile at {lock_path}")
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    default_repo = str(lock.get("sources", {}).get("gvx", {}).get("repository", "https://github.com/gfire-sigs/gvx.git"))
    repo_url = args.repo or default_repo

    explicit_manual = args.gvx_tag is not None or args.gvx_commit is not None
    mode_count = int(bool(args.check)) + int(bool(args.fetch_latest)) + int(explicit_manual)
    if mode_count > 1:
        raise SyncError("Choose exactly one mode: --check, --fetch-latest, or --gvx-tag/--gvx-commit")
    if mode_count == 0:
        args.check = True

    if args.check:
        if args.app_rev is not None or args.dry_run:
            raise SyncError("--app-rev and --dry-run cannot be used with --check")
        run_check(repo_root, args.expect_tag)
        return 0

    if args.expect_tag is not None:
        raise SyncError("--expect-tag is only valid with --check")

    if args.fetch_latest:
        gvx_tag, gvx_commit = fetch_latest_upstream_tag(repo_url)
        current_tag = str(lock.get("sources", {}).get("gvx", {}).get("tag", ""))
        current_commit = str(lock.get("sources", {}).get("gvx", {}).get("commit", ""))
        current_rev = int(lock.get("termvr", {}).get("app_revision", 0))
        if args.app_rev is not None:
            app_rev = args.app_rev
        elif gvx_tag == current_tag and gvx_commit == current_commit:
            app_rev = current_rev
        else:
            app_rev = 0
        run_sync(repo_root, gvx_tag, gvx_commit, app_rev, repo_url, args.commit_date, args.dry_run)
        if not args.dry_run:
            run_check(repo_root, None)
        return 0

    if not args.gvx_tag or not args.gvx_commit:
        raise SyncError("Both --gvx-tag and --gvx-commit are required together")
    app_rev = 0 if args.app_rev is None else args.app_rev
    run_sync(repo_root, args.gvx_tag, args.gvx_commit, app_rev, repo_url, args.commit_date, args.dry_run)
    if not args.dry_run:
        run_check(repo_root, None)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except SyncError as err:
        print(f"[sync-gvx] ERROR: {err}", file=sys.stderr)
        sys.exit(1)
PY
