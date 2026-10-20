#!/usr/bin/env python3
"""Adds the on-device JDK (one entry per ABI) and the Gradle distribution to the bundled catalog and re-pins the catalog hash.

Usage: pin_gradle_jdk_catalog.py <resolved.json>

tools/gradle-jdk.json says WHICH release/version to use. The CI step in build.yml downloads each file, verifies it against the
checksum published next to it (the release's .sha256 file, Gradle's .sha256 file) and writes the verified SHA-256 and size of every
file to <resolved.json>. This script only turns that into catalog entries, so no hash is typed by hand and none can be stale.

The verified JDK archive of every ABI is also copied into the app assets (app/src/main/assets/jdk/<installPath>), so the app can install
the complete JDK without downloading it; the URL stays in the catalog only as a fallback.
"""
import hashlib, json, os, re, shutil, sys

here = os.path.dirname(os.path.abspath(__file__))
spec = json.load(open(os.path.join(here, "gradle-jdk.json")))
resolved = json.load(open(sys.argv[1]))
catalog_path = "app/src/main/assets/build-tools/catalog.json"
java_path = "app/src/main/java/com/example/studiomobile/build/dependency/BuildToolCatalog.java"
bundled_dir = "app/src/main/assets/jdk"
download_dir = os.path.join(os.environ.get("RUNNER_TEMP", ""), "jre")

jdk, gradle = spec["jdk"], spec["gradle"]
repo = os.environ.get("JDK_REPO") or jdk["repo"]
entries = []
for abi, asset in jdk["assets"].items():
    info = resolved["jdk"].get(abi)
    if not info:
        continue  # an ABI whose build failed (armeabi-v7a is optional) simply gets no entry
    install_path = "jdk-%d-%s.tar.xz" % (jdk["major"], abi)
    source_archive = os.path.join(download_dir, asset)
    if os.path.isfile(source_archive):
        os.makedirs(bundled_dir, exist_ok=True)
        shutil.copyfile(source_archive, os.path.join(bundled_dir, install_path))
        print("Bundled %s into the app assets as %s/%s" % (asset, bundled_dir, install_path))
    else:
        print("WARNING: %s not found; the app will have to download the JDK for %s" % (source_archive, abi))
    entries.append({
        "id": "jdk-%d" % jdk["major"],
        "version": "OpenJDK %d for Android %s" % (jdk["major"], abi),
        "url": "https://github.com/%s/releases/download/%s/%s" % (repo, jdk["tag"], asset),
        "installPath": install_path,
        "sha256": info["sha256"],
        "license": "GPL-2.0 WITH Classpath-exception-2.0 (OpenJDK)",
        "abi": abi,
        "archive": "tar.xz",
        "maxBytes": min(max(400 * 1024 * 1024, info["bytes"] * 2), 1024 * 1024 * 1024),
    })
g = resolved["gradle"]
entries.append({
    "id": "gradle-" + gradle["version"],
    "version": gradle["version"] + " (services.gradle.org, bin distribution)",
    "url": gradle["url"],
    "installPath": "gradle-%s-bin.zip" % gradle["version"],
    "sha256": g["sha256"],
    "license": "Apache-2.0 (Gradle)",
    "abi": "any",
    "archive": "zip",
    "maxBytes": min(max(300 * 1024 * 1024, g["bytes"] * 2), 1024 * 1024 * 1024),
})
if not any(e["abi"] != "any" for e in entries):
    sys.exit("no JDK entry was produced; refusing to publish a catalog without a runnable JDK")

catalog = json.load(open(catalog_path))
catalog["components"] = [c for c in catalog["components"] if not (c["id"].startswith("jdk-") or c["id"].startswith("gradle-"))] + entries
catalog["catalogVersion"] = max(catalog.get("catalogVersion", 1), 5)
data = (json.dumps(catalog, indent=2) + "\n").encode()
open(catalog_path, "wb").write(data)
digest = hashlib.sha256(data).hexdigest()
source = open(java_path).read()
source, count = re.subn(r'BUNDLED_CATALOG_SHA256 = "[0-9a-f]{64}"', 'BUNDLED_CATALOG_SHA256 = "%s"' % digest, source)
if count != 1:
    sys.exit("BUNDLED_CATALOG_SHA256 not found in " + java_path)
open(java_path, "w").write(source)
print("Pinned %d catalog entries (catalog sha256 %s)" % (len(entries), digest))
