#!/usr/bin/env python3
"""Adds the Dart SDK (built and released by a separate repository) to the bundled catalog and re-pins the catalog hash.

Run by build.yml after the Dart executables were bundled. The version, repository, SHA-256 and optional size come from tools/dart-sdk.json (the same file
build.yml reads), so there is a single place to change when Dart is upgraded. The Dart SDK is built and released by a separate repository
(the "repo" entry of tools/dart-sdk.json), so the app downloads it from there and not from the repository that builds Studio Mobile.
"""
import hashlib, json, os, re, sys

spec = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "dart-sdk.json")))
VERSION = spec["version"]
SHA256 = spec["sha256"]
BYTES = spec.get("bytes", 0)
repo = spec.get("repo")
if not repo or "/" not in repo:
    sys.exit('tools/dart-sdk.json needs a "repo" entry such as OWNER/dart-android')

catalog_path = "app/src/main/assets/build-tools/catalog.json"
java_path = "app/src/main/java/com/example/studiomobile/build/dependency/BuildToolCatalog.java"
catalog = json.load(open(catalog_path))
catalog["components"] = [c for c in catalog["components"] if not c["id"].startswith("dart-sdk-")] + [{
    "id": "dart-sdk-" + VERSION,
    "version": VERSION + " (built from dart.googlesource.com/sdk for Android arm64)",
    "url": "https://github.com/%s/releases/download/dart-android-arm64-%s/dart-sdk-android-arm64-%s.tar.gz" % (repo, VERSION, VERSION),
    "installPath": "dart-sdk-android-arm64-%s.tar.gz" % VERSION,
    "sha256": SHA256,
    "license": "BSD-3-Clause (Dart SDK)",
    "abi": "arm64-v8a",
    "archive": "tar.gz",
    "maxBytes": max(200 * 1024 * 1024, BYTES * 2),
}]
catalog["catalogVersion"] = max(catalog.get("catalogVersion", 1), 4)
data = (json.dumps(catalog, indent=2) + "\n").encode()
open(catalog_path, "wb").write(data)
digest = hashlib.sha256(data).hexdigest()
source = open(java_path).read()
source, count = re.subn(r'BUNDLED_CATALOG_SHA256 = "[0-9a-f]{64}"', 'BUNDLED_CATALOG_SHA256 = "%s"' % digest, source)
if count != 1:
    sys.exit("BUNDLED_CATALOG_SHA256 not found in " + java_path)
open(java_path, "w").write(source)
print("Pinned dart-sdk-%s for %s (catalog sha256 %s)" % (VERSION, repo, digest))
