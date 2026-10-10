#!/usr/bin/env python3
"""Merge all AAR res dirs + app res into build/merged-res/, deduping identical
resources across libraries (like AGP's ResourceMerger). Priority: app first,
then artifacts in resolved.txt order (direct deps before transitive)."""
import os
import re
import shutil
import xml.etree.ElementTree as ET

TOOLS = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(TOOLS)
BUILD = os.path.join(PROJ, "build")
AAR_RES = os.path.join(BUILD, "aar-res")
APP_RES = os.path.join(PROJ, "app", "src", "main", "res")
MERGED = os.path.join(BUILD, "merged-res")
DEPS = os.path.join(PROJ, "deps")


def pkg_of_dirname(d):
    return d  # dirname is already the java package


def merge_values_file(files):
    """files: list of (priority, pkg, path). Returns merged XML string."""
    seen = {}   # (tag, name) -> (pkg, canonical_xml)
    order = []
    for prio, pkg, path in files:
        try:
            tree = ET.parse(path)
        except Exception:
            continue
        root = tree.getroot()
        for child in root:
            tag = child.tag
            name = child.get("name")
            key = (tag, name)
            canon = ET.tostring(child, encoding="unicode")
            canon_norm = re.sub(r"\s+", " ", canon).strip()
            if key in seen:
                prev_pkg, prev_norm = seen[key]
                if prev_norm != canon_norm:
                    print(f"  [res-conflict] {tag} name={name}: keeping {prev_pkg}, dropping {pkg}")
                continue
            seen[key] = (pkg, canon_norm)
            order.append(child)
    resources = ET.Element("resources")
    for child in order:
        resources.append(child)
    return ET.tostring(resources, encoding="unicode")


def main():
    if os.path.isdir(MERGED):
        shutil.rmtree(MERGED)
    os.makedirs(MERGED, exist_ok=True)

    # priority order: app first, then resolved.txt order
    ordered_pkgs = []
    rtxt_dir = os.path.join(BUILD, "r-txt")
    if os.path.isdir(rtxt_dir):
        for f in sorted(os.listdir(rtxt_dir)):
            if f.endswith(".txt"):
                ordered_pkgs.append(f[:-4])
    # map pkg -> res dir
    pkg_resdir = {}
    if os.path.isdir(AAR_RES):
        for pkg in os.listdir(AAR_RES):
            d = os.path.join(AAR_RES, pkg)
            if os.path.isdir(d):
                pkg_resdir[pkg] = d

    # collect files: relpath -> list of (prio, pkg, fullpath)
    buckets = {}
    for prio, pkg in enumerate(ordered_pkgs):
        d = pkg_resdir.get(pkg)
        if not d:
            continue
        for root, _, files in os.walk(d):
            for f in files:
                full = os.path.join(root, f)
                rel = os.path.relpath(full, d)
                buckets.setdefault(rel, []).append((prio, pkg, full))
    # app res: highest priority (prio -1)
    if os.path.isdir(APP_RES):
        for root, _, files in os.walk(APP_RES):
            for f in files:
                full = os.path.join(root, f)
                rel = os.path.relpath(full, APP_RES)
                buckets.setdefault(rel, []).insert(0, (-1, "app", full))

    n_merged = n_copied = 0
    for rel, files in sorted(buckets.items()):
        files.sort(key=lambda x: x[0])
        dest = os.path.join(MERGED, rel)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        if len(files) == 1:
            shutil.copy(files[0][2], dest)
            n_copied += 1
        elif rel.endswith(".xml") and "/values" in rel.replace("\\", "/"):
            merged = merge_values_file(files)
            with open(dest, "w", encoding="utf-8") as f:
                f.write('<?xml version="1.0" encoding="utf-8"?>\n')
                f.write(merged)
            n_merged += 1
        else:
            # non-values file dup: keep highest priority
            shutil.copy(files[0][2], dest)
            pkgs = ",".join(p for _, p, _ in files[1:])
            print(f"  [file-dup] {rel}: keeping {files[0][1]}, dropping {pkgs}")
            n_copied += 1
    print(f"merged-res: {n_copied} copied, {n_merged} values files merged")


if __name__ == "__main__":
    main()
