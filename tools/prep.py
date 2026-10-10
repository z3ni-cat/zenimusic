#!/usr/bin/env python3
"""Extract resolved AARs/JARs into a build tree:
   build/libs/*.jar        - all Java/Kotlin classes
   build/aar-res/<pkg>/    - resources per AAR (for aapt2 compile)
   build/r-txt/<pkg>.txt   - R.txt per AAR package (for R.java generation)
   build/pkgmap.txt        - aar file -> java package mapping
Also reports AAR manifests that declare application components (would need merging).
"""
import os
import re
import shutil
import sys
import zipfile
import xml.etree.ElementTree as ET

TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(TOOLS)
DEPS = os.path.join(PROJ, "deps")
BUILD = os.path.join(PROJ, "build")
LIBS = os.path.join(BUILD, "libs")
AAR_RES = os.path.join(BUILD, "aar-res")
R_TXT = os.path.join(BUILD, "r-txt")

for d in (LIBS, AAR_RES, R_TXT):
    os.makedirs(d, exist_ok=True)

NS_ANDROID = "{http://schemas.android.com/apk/res/android}"


def manifest_package(aar_path):
    try:
        z = zipfile.ZipFile(aar_path)
        data = z.read("AndroidManifest.xml")
        # AAR manifests are plain XML (not binary)
        root = ET.fromstring(data)
        return root.get("package")
    except Exception as e:
        return None


def manifest_has_components(aar_path):
    try:
        z = zipfile.ZipFile(aar_path)
        data = z.read("AndroidManifest.xml").decode("utf-8", "replace")
        app = re.search(r"<application.*?</application>", data, re.S)
        if not app:
            return []
        found = re.findall(r"<(activity|service|receiver|provider)[\s>]", app.group(0))
        return found
    except Exception:
        return []


def main():
    resolved = os.path.join(DEPS, "resolved.txt")
    if not os.path.exists(resolved):
        print("resolved.txt missing; run resolve.py first", file=sys.stderr)
        sys.exit(1)
    pkgmap = []
    n_jars = n_aars = 0
    for line in open(resolved):
        line = line.strip()
        if not line:
            continue
        coords, path = line.rsplit(" ", 1)
        g, a, v = coords.split(":")
        kind = "aar" if "[aar]" in line else "jar"
        if not os.path.exists(path):
            print(f"[skip-missing] {path}")
            continue
        if kind == "jar":
            dest = os.path.join(LIBS, f"{a}-{v}.jar")
            if not os.path.exists(dest):
                shutil.copy(path, dest)
            n_jars += 1
        else:
            pkg = manifest_package(path)
            if not pkg:
                # fall back to artifact-derived package guess
                pkg = a.replace("-", "_")
                print(f"[warn] no package in manifest for {a}; using {pkg}")
            comps = manifest_has_components(path)
            if comps:
                print(f"[manifest-components] {a}: {comps}  <-- may need manual merging!")
            z = zipfile.ZipFile(path)
            names = z.namelist()
            # classes.jar -> libs
            if "classes.jar" in names:
                dest = os.path.join(LIBS, f"{a}-{v}-classes.jar")
                if not os.path.exists(dest):
                    with z.open("classes.jar") as src, open(dest, "wb") as out:
                        shutil.copyfileobj(src, out)
                n_aars += 1
            else:
                print(f"[warn] {a} has no classes.jar")
            # res/ -> aar-res/<pkg>/
            res_names = [n for n in names if n.startswith("res/") and not n.endswith("/")]
            if res_names:
                outdir = os.path.join(AAR_RES, pkg)
                os.makedirs(outdir, exist_ok=True)
                for n in res_names:
                    target = os.path.join(outdir, n[4:])
                    os.makedirs(os.path.dirname(target), exist_ok=True)
                    if not os.path.exists(target):
                        with z.open(n) as src, open(target, "wb") as out:
                            shutil.copyfileobj(src, out)
            # R.txt -> r-txt/<pkg>.txt
            if "R.txt" in names:
                target = os.path.join(R_TXT, f"{pkg}.txt")
                if not os.path.exists(target):
                    with z.open("R.txt") as src, open(target, "wb") as out:
                        shutil.copyfileobj(src, out)
            pkgmap.append(f"{a}\t{pkg}")
    with open(os.path.join(BUILD, "pkgmap.txt"), "w") as f:
        f.write("\n".join(pkgmap) + "\n")
    print(f"prep done: {n_jars} jars, {n_aars} aars with classes")
    print("packages:", len(pkgmap))


if __name__ == "__main__":
    main()
