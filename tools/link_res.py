#!/usr/bin/env python3
"""Compile all resources with aapt2, link into base.apk, and generate per-library
R.java files (non-final fields, matching how AAR classes.jar references them)
from the final symbol table."""
import os
import re
import subprocess
import sys

TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(TOOLS)
BUILD = os.path.join(PROJ, "build")
SDK = os.environ.get("ANDROID_HOME", os.path.expanduser("~/workspace/android-sdk"))
AAPT2 = os.path.join(SDK, "build-tools", "34.0.0", "aapt2")
ANDROID_JAR = os.path.join(SDK, "platforms", "android-34", "android.jar")
APP_RES = os.path.join(PROJ, "app", "src", "main", "res")
MANIFEST = os.path.join(PROJ, "app", "src", "main", "AndroidManifest.xml")
COMPILED = os.path.join(BUILD, "compiled-res")
GEN_APP_R = os.path.join(BUILD, "gen")
GEN_LIB_R = os.path.join(BUILD, "gen-r")
SYMBOLS = os.path.join(BUILD, "symbols.txt")
BASE_APK = os.path.join(BUILD, "base.apk")
CLASSES_R = os.path.join(BUILD, "classes-r")


def run(cmd, **kw):
    short = " ".join(cmd[:8]) + (" ..." if len(cmd) > 8 else "")
    print("+", short, flush=True)
    subprocess.run(cmd, check=True, **kw)


def compile_res():
    os.makedirs(COMPILED, exist_ok=True)
    merged = os.path.join(BUILD, "merged-res")
    if not os.path.isdir(merged):
        print("merged-res missing; run merge_res.py first", file=sys.stderr)
        sys.exit(1)
    out = os.path.join(COMPILED, "merged")
    os.makedirs(out, exist_ok=True)
    run([AAPT2, "compile", "--dir", merged, "-o", out])
    flats = []
    for root, _, files in os.walk(out):
        for f in files:
            if f.endswith(".flat"):
                flats.append(os.path.join(root, f))
    print(f"compiled {len(flats)} resource files")
    return flats


def link(flats):
    os.makedirs(GEN_APP_R, exist_ok=True)
    cmd = [
        AAPT2, "link",
        "-o", BASE_APK,
        "-I", ANDROID_JAR,
        "--manifest", MANIFEST,
        "--java", GEN_APP_R,
        "--output-text-symbols", SYMBOLS,
        "--auto-add-overlay",
        "--min-sdk-version", "26",
        "--target-sdk-version", "34",
        "--version-code", "27",
        "--version-name", "2.9.1",
    ] + flats
    run(cmd)
    print("linked ->", BASE_APK)


def parse_symbols(path):
    """symbols.txt -> {(type, name): value_hex} plus {('styleable-array', name): [hex...]}"""
    vals = {}
    for line in open(path):
        line = line.strip()
        m = re.match(r"int (\w+) (\w+) (0x[0-9a-fA-F]+)", line)
        if m:
            vals[(m.group(1), m.group(2))] = m.group(3)
            continue
        m = re.match(r"int\[\] styleable (\w+) \{ ([^}]*) \}", line)
        if m:
            arr = [x.strip() for x in m.group(2).split(",") if x.strip()]
            vals[("styleable-array", m.group(1))] = arr
    return vals


def parse_aar_rtxt(path):
    """AAR R.txt -> list of (kind, type, name, payload)."""
    entries = []
    for line in open(path):
        line = line.strip()
        m = re.match(r"int (\w+) (\w+) (0x[0-9a-fA-F]+)", line)
        if m:
            entries.append(("int", m.group(1), m.group(2), m.group(3)))
            continue
        m = re.match(r"int\[\] styleable (\w+) \{ ([^}]*) \}", line)
        if m:
            entries.append(("array", "styleable", m.group(1), None))
    return entries


def gen_lib_r(symbols):
    r_txt_dir = os.path.join(BUILD, "r-txt")
    os.makedirs(GEN_LIB_R, exist_ok=True)
    count = 0
    for fname in sorted(os.listdir(r_txt_dir)):
        if not fname.endswith(".txt"):
            continue
        pkg = fname[:-4]
        entries = parse_aar_rtxt(os.path.join(r_txt_dir, fname))
        by_type = {}
        missing = 0
        for kind, rtype, name, _ in entries:
            if kind == "array":
                val = symbols.get(("styleable-array", name))
            else:
                val = symbols.get((rtype, name))
            if val is None:
                missing += 1
                continue
            by_type.setdefault(rtype, []).append((kind, name, val))
        if missing:
            print(f"  [warn] {pkg}: {missing} symbols not found in final table")
        # Indices for styleable attributes: R.txt doesn't carry them reliably;
        # derive from the styleable array order in symbols.txt is complex, so
        # parse the AAR's R.txt index lines too.
        lines = open(os.path.join(r_txt_dir, fname)).read().splitlines()
        idx_map = {}
        for line in lines:
            m = re.match(r"int styleable (\w+) (\d+)", line.strip())
            if m:
                idx_map[m.group(1)] = m.group(2)
        src = [f"package {pkg};", "", "public final class R {"]
        for rtype in sorted(by_type):
            src.append(f"    public static final class {rtype} {{")
            for kind, name, val in by_type[rtype]:
                if kind == "array":
                    src.append(f"        public static int[] {name} = {{ {', '.join(val)} }};")
                else:
                    if rtype == "styleable" and name in idx_map:
                        src.append(f"        public static int {name} = {idx_map[name]};")
                    else:
                        src.append(f"        public static int {name} = {val};")
            src.append("    }")
        src.append("}")
        out_dir = os.path.join(GEN_LIB_R, *pkg.split("."))
        os.makedirs(out_dir, exist_ok=True)
        with open(os.path.join(out_dir, "R.java"), "w") as f:
            f.write("\n".join(src) + "\n")
        count += 1
    print(f"generated R.java for {count} library packages")


def compile_r():
    os.makedirs(CLASSES_R, exist_ok=True)
    javas = []
    for root, _, files in os.walk(GEN_LIB_R):
        for f in files:
            if f.endswith(".java"):
                javas.append(os.path.join(root, f))
    if not javas:
        print("no library R.java to compile")
        return
    javac = os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "javac")
    run([javac, "-nowarn", "-d", CLASSES_R] + javas)
    print(f"compiled {len(javas)} R.java files")


def main():
    flats = compile_res()
    if not flats:
        print("no resources found!", file=sys.stderr)
        sys.exit(1)
    link(flats)
    symbols = parse_symbols(SYMBOLS)
    print(f"final symbol table: {len(symbols)} entries")
    gen_lib_r(symbols)
    compile_r()


if __name__ == "__main__":
    main()
