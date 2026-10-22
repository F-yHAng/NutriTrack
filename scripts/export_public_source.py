#!/usr/bin/env python3
"""Export current source without history or local data. Bounded heuristic checks only.

Findings contain paths and line numbers, never matching values.
"""

import argparse
import csv
import hashlib
import os
import re
import shutil
import sys
import zipfile
from pathlib import Path

ROOT_FILES = {
    ".gitignore", "AGENTS.md", "CURRENT_STATUS.md", "PROJECT_CONTEXT.md", "README.md",
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
    "LICENSE", "LICENSE.md", "NOTICE", "NOTICE.md", "CONTRIBUTING.md",
}
ROOT_TREES = {"docs", "gradle", "scripts"}
APP_FILES = {"app/build.gradle.kts", "app/proguard-rules.pro"}
EXCLUDED_PARTS = {
    ".git", ".gradle", ".idea", ".kotlin", "build", "context-handoff", "__pycache__",
    "node_modules", ".externalNativeBuild", ".cxx", "captures",
}
EXCLUDED_SUFFIXES = {
    ".log", ".db", ".sqlite", ".sqlite3", ".hprof", ".apk", ".aab", ".apks", ".zip",
    ".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".iml", ".pyc",
    ".db-wal", ".db-shm",
}
PATTERNS = {
    "google-api-key": re.compile(rb"AIza[0-9A-Za-z_-]{35}"),
    "private-key": re.compile(rb"-----BEGIN (?:[A-Z]+ )*PRIVATE KEY-----"),
    "github-token": re.compile(rb"gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}"),
    "provider-token": re.compile(rb"sk-(?:proj-)?[A-Za-z0-9_-]{20,}"),
    "personal-home-path": re.compile(rb"/(?:Users|home)/[A-Za-z0-9_.-]+/|[A-Z]:\\Users\\[^\\\s]+"),
    "course-identity-namespace": re.compile(rb"com[./]fit\d+[./]"),
}


def allowed(relative):
    if any(part in EXCLUDED_PARTS for part in relative.parts):
        return False
    name = relative.name.lower()
    if name == ".ds_store" or name == "local.properties" or name.startswith(".env"):
        return False
    if relative.suffix.lower() in EXCLUDED_SUFFIXES:
        return False
    if (name.startswith("google-services") or "service-account" in name
            or "serviceaccount" in name or "firebase-adminsdk" in name
            or name.startswith("credentials")):
        return False
    if len(relative.parts) == 1:
        return relative.name in ROOT_FILES
    if relative.as_posix() == ".github/workflows/android.yml":
        return True
    if relative.parts[0] in ROOT_TREES:
        return True
    return (relative.as_posix() in APP_FILES
            or relative.parts[:2] in (("app", "src"), ("app", "schemas")))


def source_files(root):
    selected = []
    for directory, directories, files in os.walk(root, followlinks=False):
        directories[:] = sorted(d for d in directories
                                if d not in EXCLUDED_PARTS and not Path(directory, d).is_symlink())
        for filename in sorted(files):
            path = Path(directory, filename)
            relative = path.relative_to(root)
            if allowed(relative):
                if path.is_symlink():
                    raise ValueError("Refusing source symlink: " + relative.as_posix())
                selected.append(relative)
    return sorted(selected)


def check(root, files):
    findings = []
    for relative in files:
        data = (root / relative).read_bytes()
        for label, pattern in PATTERNS.items():
            for match in pattern.finditer(data):
                line = data[:match.start()].count(b"\n") + 1
                findings.append(f"{relative.as_posix()}:{line}: {label}")
        if PATTERNS["course-identity-namespace"].search(relative.as_posix().encode()):
            findings.append(f"{relative.as_posix()}: course-identity-path")
    with (root / "app/src/main/assets/data.csv").open(newline="") as stream:
        rows = list(csv.DictReader(stream))
    expected = {(str(i), str(2025550100 + i)) for i in range(1, 10)}
    actual = {(row["User_ID"], row["PhoneNumber"]) for row in rows}
    if len(rows) != 9 or actual != expected:
        findings.append("app/src/main/assets/data.csv: unexpected demo identities")
    return findings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--check", action="store_true", help="check only source selected for export")
    action.add_argument("--output", type=Path, help="new directory outside this repository")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    files = source_files(root)
    findings = check(root, files)
    if findings:
        print("Publication source check failed:\n" + "\n".join(findings), file=sys.stderr)
        return 1
    print(f"Publication source check: {len(files)} files; no configured pattern matches.")
    if args.check:
        return 0
    output = args.output.expanduser().resolve()
    archive = output.with_name(output.name + ".zip")
    if output == root or root in output.parents:
        raise ValueError("Output must be outside this repository")
    if output.exists() or archive.exists():
        raise ValueError("Output directory or ZIP already exists; refusing to overwrite")
    output.mkdir(parents=True)
    for relative in files:
        destination = output / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(root / relative, destination)
    copied = sorted(path.relative_to(output) for path in output.rglob("*") if path.is_file())
    if copied != files or check(output, copied):
        raise ValueError("Export verification failed; do not publish the output")
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
        for relative in files:
            bundle.write(output / relative, (Path(output.name) / relative).as_posix())
    # Read back the actual ZIP, rather than trusting only the source directory.
    with zipfile.ZipFile(archive) as bundle:
        if bundle.testzip() is not None or len(bundle.infolist()) != len(files):
            raise ValueError("ZIP integrity verification failed")
        for relative in files:
            name = (Path(output.name) / relative).as_posix()
            if bundle.read(name) != (output / relative).read_bytes():
                raise ValueError("ZIP content mismatch: " + relative.as_posix())
    print("Exported source: " + str(output))
    print("Verified ZIP: " + str(archive))
    print("ZIP SHA-256: " + hashlib.sha256(archive.read_bytes()).hexdigest())
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, KeyError) as error:
        print("Export failed: " + str(error), file=sys.stderr)
        sys.exit(1)
