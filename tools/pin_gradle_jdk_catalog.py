#!/usr/bin/env python3
"""Adds every verified JDK (one entry per major and ABI) and every verified Gradle distribution to the bundled catalog and re-pins
the catalog hash.

Usage: pin_gradle_jdk_catalog.py <resolved.json>

tools/gradle-jdk.json says WHICH majors/versions exist; resolve_gradle_jdk.py verified each file and wrote its SHA-256 and size to
<resolved.json>. This script only turns that into catalog entries, so no hash is typed by hand and none can be stale. Only the majors
in "bundledMajors" ("all" = every JDK found in the release) are copied into the APK (app/src/main/assets/jdk/<installPath>); every other JDK and every Gradle is downloaded on
first use from its catalog URL and checked against the pinned SHA-256.
"""
import hashlib, json, os, re, shutil, sys

here = os.path.dirname(os.path.abspath(__file__))
spec = json.load(open(os.path.join(here, "gradle-jdk.json")))
resolved = json.load(open(sys.argv[1]))
catalog_path = "app/src/main/assets/build-tools/catalog.json"
java_path = "app/src/main/java/com/example/studiomobile/build/dependency/BuildToolCatalog.java"
bundled_dir = "app/src/main/assets/jdk"
download_dir = os.path.join(os.environ.get("RUNNER_TEMP") or "/tmp", "jre")

jdk, gradle = spec["jdk"], spec["gradle"]
repo = os.environ.get("JDK_REPO") or jdk["repo"]
entries = []
for major in jdk["majors"]:
    for abi, arch in jdk["abis"].items():
        info = resolved["jdk"].get(str(major), {}).get(abi)
        if not info: continue   # a JDK or ABI whose archive is not in the release simply gets no entry
        asset = jdk["assetPattern"].format(major=major, arch=arch)
        install_path = "jdk-%d-%s.tar.xz" % (major, abi)
        if jdk["bundledMajors"] == "all" or major in jdk["bundledMajors"]:
            source = os.path.join(download_dir, asset)
            if os.path.isfile(source):
                os.makedirs(bundled_dir, exist_ok=True)
                shutil.copyfile(source, os.path.join(bundled_dir, install_path))
                print("Bundled %s into the APK assets as %s/%s" % (asset, bundled_dir, install_path))
            else: print("WARNING: %s not found; the app will download JDK %d for %s" % (source, major, abi))
        label = "OpenJDK %d for Android %s" % (major, abi) + ("" if major in jdk["verifiedMajors"] else " (experimental build, unverified)")
        entries.append({
            "id": "jdk-%d" % major, "version": label,
            "url": "https://github.com/%s/releases/download/%s/%s" % (repo, jdk["tag"], asset),
            "installPath": install_path, "sha256": info["sha256"],
            "license": "GPL-2.0 WITH Classpath-exception-2.0 (OpenJDK)", "abi": abi, "archive": "tar.xz",
            "maxBytes": min(max(400 * 1024 * 1024, info["bytes"] * 2), 1024 * 1024 * 1024),
        })
for version, info in resolved["gradle"].items():
    entries.append({
        "id": "gradle-" + version, "version": version + " (services.gradle.org, bin distribution)",
        "url": gradle["urlPattern"].format(version=version), "installPath": "gradle-%s-bin.zip" % version,
        "sha256": info["sha256"], "license": "Apache-2.0 (Gradle)", "abi": "any", "archive": "zip",
        "maxBytes": min(max(300 * 1024 * 1024, info["bytes"] * 2), 1024 * 1024 * 1024),
    })
if not any(e["id"].startswith("jdk-") for e in entries):
    sys.exit("no JDK entry was produced; refusing to publish a catalog without a runnable JDK")
if not any(e["id"].startswith("gradle-") for e in entries):
    sys.exit("no Gradle entry was produced; refusing to publish a catalog without a Gradle distribution")

catalog = json.load(open(catalog_path))
catalog["components"] = [c for c in catalog["components"] if not (c["id"].startswith("jdk-") or c["id"].startswith("gradle-"))] + entries
catalog["catalogVersion"] = max(catalog.get("catalogVersion", 1), 6)
data = (json.dumps(catalog, indent=2) + "\n").encode()
open(catalog_path, "wb").write(data)
digest = hashlib.sha256(data).hexdigest()
source = open(java_path).read()
source, count = re.subn(r'BUNDLED_CATALOG_SHA256 = "[0-9a-f]{64}"', 'BUNDLED_CATALOG_SHA256 = "%s"' % digest, source)
if count != 1: sys.exit("BUNDLED_CATALOG_SHA256 not found in " + java_path)
open(java_path, "w").write(source)
print("Pinned %d catalog entries (catalog sha256 %s)" % (len(entries), digest))
