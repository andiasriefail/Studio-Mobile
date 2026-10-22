#!/usr/bin/env python3
"""Downloads and verifies every JDK archive and Gradle distribution listed in tools/gradle-jdk.json, extracts the native launchers
(java, javac, jmod, jar, jlink) of EVERY JDK major into app/src/main/jniLibs/<abi>/lib<tool><major>exe.so, and writes the verified
SHA-256 and size of everything to <resolved.json> for pin_gradle_jdk_catalog.py.

Nothing is typed by hand: names, versions, repo and tag all come from tools/gradle-jdk.json, hashes come from the files published
next to the downloads (<asset>.sha256 in the release, <url>.sha256 for Gradle).

Usage: resolve_gradle_jdk.py <resolved.json> [--from-dir DIR]
  --from-dir  take the archives and .sha256 files from DIR instead of GitHub (offline tests); Gradle is then skipped.
Env: GH_TOKEN (gh CLI), JDK_REPO (overrides the repo), RUNNER_TEMP (work folder).
"""
import hashlib, json, os, shutil, subprocess, sys, tarfile, urllib.request

here = os.path.dirname(os.path.abspath(__file__))
spec = json.load(open(os.path.join(here, "gradle-jdk.json")))
args = sys.argv[1:]
from_dir = None
if "--from-dir" in args:
    i = args.index("--from-dir"); from_dir = args[i + 1]; del args[i:i + 2]
resolved_path = args[0]
work = os.path.join(os.environ.get("RUNNER_TEMP") or "/tmp", "jre")
os.makedirs(work, exist_ok=True)
jni_root = os.environ.get("JNI_ROOT", "app/src/main/jniLibs")
E_MACHINE = {"arm64-v8a": 183, "armeabi-v7a": 40}   # EM_AARCH64, EM_ARM

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""): h.update(chunk)
    return h.hexdigest()

def warn(msg): print("::warning::" + msg)

jdk = spec["jdk"]
repo = os.environ.get("JDK_REPO") or jdk["repo"]
resolved = {"jdk": {}, "gradle": {}}
missing = {}

for major in jdk["majors"]:
    for abi, arch in jdk["abis"].items():
        asset = jdk["assetPattern"].format(major=major, arch=arch)
        required = major in jdk["verifiedMajors"] and abi == "arm64-v8a"
        try:
            if from_dir:
                for name in (asset, asset + ".sha256"):
                    shutil.copyfile(os.path.join(from_dir, name), os.path.join(work, name))
            else:
                subprocess.run(["gh", "release", "download", jdk["tag"], "--repo", repo, "--pattern", asset,
                                "--pattern", asset + ".sha256", "--dir", work, "--clobber"], check=True)
            archive = os.path.join(work, asset)
            expected = open(archive + ".sha256").read().split()[0]
            actual = sha256(archive)
            if actual != expected: sys.exit("SHA-256 mismatch for %s (%s, release says %s)" % (asset, actual, expected))
        except (subprocess.CalledProcessError, OSError) as exc:
            if required: sys.exit("required archive %s is not available: %s" % (asset, exc))
            missing.setdefault(major, []).append(abi); continue
        resolved["jdk"].setdefault(str(major), {})[abi] = {"sha256": actual, "bytes": os.path.getsize(archive)}
        top = jdk["topDirPattern"].format(major=major)
        dest = os.path.join(jni_root, abi); os.makedirs(dest, exist_ok=True)
        with tarfile.open(archive, "r:xz") as tar:
            for tool in jdk["launcherTools"]:
                try: member = tar.getmember("%s/bin/%s" % (top, tool))
                except KeyError:
                    if tool == "java": sys.exit("%s/bin/java is not in %s" % (top, asset))
                    warn("%s/bin/%s is not in %s; %s cannot start %s (projects with compileSdk 34+ need it)." % (top, tool, asset, abi, tool)); continue
                data = tar.extractfile(member).read()
                if data[:4] != b"\x7fELF" or int.from_bytes(data[18:20], "little") != E_MACHINE[abi]:
                    sys.exit("lib%s%dexe.so for %s is not a %s ELF binary" % (tool, major, abi, abi))
                target = os.path.join(dest, "lib%s%dexe.so" % (tool, major))
                with open(target, "wb") as f: f.write(data)
                os.chmod(target, 0o755)
        print("launchers for %s taken from JDK %d" % (abi, major))

if missing: print("JDK majors not (fully) in release %s: %s" % (jdk["tag"], ", ".join("%d (%s)" % (m, "/".join(a)) for m, a in sorted(missing.items()))))

if not from_dir:
    gradle = spec["gradle"]
    for version in gradle["versions"]:
        url = gradle["urlPattern"].format(version=version)
        target = os.path.join(work, "gradle-%s-bin.zip" % version)
        try:
            urllib.request.urlretrieve(url, target)
            expected = urllib.request.urlopen(url + ".sha256").read().decode().split()[0]
            actual = sha256(target)
            if actual != expected: raise ValueError("SHA-256 mismatch (%s, published %s)" % (actual, expected))
        except Exception as exc:
            if version in gradle["requiredVersions"]: sys.exit("required Gradle %s failed: %s" % (version, exc))
            warn("Gradle %s skipped: %s" % (version, exc)); continue
        resolved["gradle"][version] = {"sha256": actual, "bytes": os.path.getsize(target)}
        os.remove(target)

json.dump(resolved, open(resolved_path, "w"), indent=1)
print(json.dumps(resolved, indent=1))
