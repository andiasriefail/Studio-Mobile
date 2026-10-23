#!/usr/bin/env python3
"""Adds the Flutter SDK archive (built and released by the workflow of the flutter-for-studio-mobile repository) to the bundled catalog and
re-pins the catalog hash.

Run by build.yml after the Flutter executables were bundled. Version, repository, release tag, asset name, SHA-256 and size come from
tools/flutter-sdk.json (the same file build.yml reads), so there is a single place to change when Flutter is upgraded.
"""
import hashlib, json, os, re, sys

spec = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "flutter-sdk.json")))
for key in ("version", "repo", "tag", "asset", "sha256", "bytes"):
    if not spec.get(key):
        sys.exit('tools/flutter-sdk.json needs a "%s" entry' % key)
if "/" not in spec["repo"]:
    sys.exit('tools/flutter-sdk.json: "repo" must look like OWNER/REPO')
if not re.fullmatch(r"[0-9a-f]{64}", spec["sha256"]):
    sys.exit('tools/flutter-sdk.json: "sha256" must be 64 lowercase hex characters')
if not spec["asset"].endswith(".tar.xz"):
    sys.exit('tools/flutter-sdk.json: "asset" must be a .tar.xz archive')

VERSION = spec["version"]
BYTES = int(spec["bytes"])
catalog_path = "app/src/main/assets/build-tools/catalog.json"
java_path = "app/src/main/java/com/example/studiomobile/build/dependency/BuildToolCatalog.java"
catalog = json.load(open(catalog_path))
catalog["components"] = [c for c in catalog["components"] if not c["id"].startswith("flutter-sdk-")] + [{
    "id": "flutter-sdk-" + VERSION,
    "version": "Flutter %s for Android arm64 (repackaged from GeneralKaos666/flutter-for-termux)" % VERSION,
    "url": "https://github.com/%s/releases/download/%s/%s" % (spec["repo"], spec["tag"], spec["asset"]),
    "installPath": spec["asset"],
    "sha256": spec["sha256"],
    "license": "BSD-3-Clause (Flutter)",
    "abi": "arm64-v8a",
    "archive": "tar.xz",
    # The catalog accepts at most 2 GiB per component.
    "maxBytes": min(2 * 1024 * 1024 * 1024, max(512 * 1024 * 1024, BYTES * 2)),
}]
catalog["catalogVersion"] = max(catalog.get("catalogVersion", 1), 7)
data = (json.dumps(catalog, indent=2) + "\n").encode()
open(catalog_path, "wb").write(data)
digest = hashlib.sha256(data).hexdigest()
source = open(java_path).read()
source, count = re.subn(r'BUNDLED_CATALOG_SHA256 = "[0-9a-f]{64}"', 'BUNDLED_CATALOG_SHA256 = "%s"' % digest, source)
if count != 1:
    sys.exit("BUNDLED_CATALOG_SHA256 not found in " + java_path)
open(java_path, "w").write(source)
print("Pinned flutter-sdk-%s from %s (catalog sha256 %s)" % (VERSION, spec["repo"], digest))
