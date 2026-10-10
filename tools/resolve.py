#!/usr/bin/env python3
"""Manual Maven dependency resolver/downloader (Gradle is unusable in this sandbox:
Java processes cannot open outbound TCP connections, which breaks the Gradle daemon IPC).

Resolves the full transitive closure of compile+runtime dependencies from POMs,
handling parent POMs, properties, dependencyManagement and BOM imports, plus the
KMP '-android' artifact variant scheme. Downloads AARs/JARs into deps/.
"""
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

REPOS = [
    "https://dl.google.com/dl/android/maven2",
    "https://repo1.maven.org/maven2",
    "https://jitpack.io",
]
DEPS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "deps")
os.makedirs(DEPS_DIR, exist_ok=True)

NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def _t(el, name):
    c = el.find(f"m:{name}", NS)
    return c.text.strip() if c is not None and c.text else None


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "zenimusic-resolver/1.0"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def url_exists(url):
    req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "zenimusic-resolver/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return int(r.headers.get("Content-Length", "0"))
    except Exception:
        return 0


def gpath(group):
    return group.replace(".", "/")


def substitute(text, props):
    def rep(m):
        return props.get(m.group(1), m.group(0))
    prev = None
    while prev != text:
        prev = text
        text = re.sub(r"\$\{([^}]+)\}", rep, text)
    return text


class Pom:
    def __init__(self, group, artifact, version):
        self.coords = (group, artifact, version)
        self.props = {}
        self.dep_mgmt = {}   # (g,a) -> version
        self.deps = []       # (g,a,version_or_None,scope,optional)
        self._load(group, artifact, version)

    def _pom_text(self, group, artifact, version):
        errs = []
        for repo in REPOS:
            url = f"{repo}/{gpath(group)}/{artifact}/{version}/{artifact}-{version}.pom"
            try:
                return fetch(url).decode("utf-8", "replace")
            except Exception as e:
                errs.append(f"{url}: {e}")
        raise RuntimeError(f"POM not found for {group}:{artifact}:{version}\n" + "\n".join(errs[:3]))

    def _load(self, group, artifact, version):
        # Walk up the parent chain, collecting from root down so child overrides parent.
        chain = []
        g, a, v = group, artifact, version
        seen = set()
        while (g, a, v) not in seen:
            seen.add((g, a, v))
            text = self._pom_text(g, a, v)
            root = ET.fromstring(text)
            chain.append((root, g, a, v))
            parent = root.find("m:parent", NS)
            if parent is None:
                break
            pg, pa, pv = _t(parent, "groupId"), _t(parent, "artifactId"), _t(parent, "version")
            if not (pg and pa and pv):
                break
            g, a, v = pg, pa, pv
        chain.reverse()

        props = {}
        dep_mgmt = {}
        deps = []
        for root, g0, a0, v0 in chain:
            props["project.groupId"] = g0
            props["project.artifactId"] = a0
            props["project.version"] = v0
            p = root.find("m:properties", NS)
            if p is not None:
                for c in p:
                    tag = c.tag.split("}")[-1]
                    props[tag] = (c.text or "").strip()
            dm = root.find("m:dependencyManagement/m:dependencies", NS)
            if dm is not None:
                for d in dm.findall("m:dependency", NS):
                    dg, da = _t(d, "groupId"), _t(d, "artifactId")
                    dv, ds, dt = _t(d, "version"), _t(d, "scope"), _t(d, "type")
                    if not (dg and da):
                        continue
                    if ds == "import" and (dt in (None, "pom")) and dv:
                        # BOM import: merge its dependencyManagement
                        try:
                            bom = Pom(dg, da, substitute(dv, props))
                            for k, bv in bom.dep_mgmt.items():
                                dep_mgmt.setdefault(k, bv)
                            # also inherit bom properties needed for its versions
                            for k, pv2 in bom.props.items():
                                props.setdefault(k, pv2)
                        except Exception as e:
                            print(f"  [warn] BOM import failed {dg}:{da}:{dv}: {e}", flush=True)
                    elif dv:
                        dep_mgmt[(dg, da)] = substitute(dv, props)
            ds = root.find("m:dependencies", NS)
            if ds is not None:
                for d in ds.findall("m:dependency", NS):
                    dg, da = _t(d, "groupId"), _t(d, "artifactId")
                    dv, dscope = _t(d, "version"), _t(d, "scope")
                    dopt = _t(d, "optional")
                    if dg and da:
                        deps.append((dg, da, dv, dscope or "compile", dopt == "true"))
        # Final substitution pass with complete props
        self.props = props
        self.dep_mgmt = {(g, a): substitute(v, props) for (g, a), v in dep_mgmt.items()}
        self.deps = [(g, a, substitute(v, props) if v else None, s, o) for (g, a, v, s, o) in deps]


def pick_artifact(group, artifact, version):
    """Pick the first candidate (name variant + kind) whose file exists AND
    actually contains classes. Returns (eff_name, kind, repo, url, dest)."""
    base = artifact[:-len("-android")] if artifact.endswith("-android") else artifact
    cands = []
    for name in (base + "-android", base):
        cands.append((name, "aar"))
    for name in (base + "-jvm", base, base + "-android"):
        cands.append((name, "jar"))
    seen, ordered = set(), []
    for n, k in cands:
        if (n, k) not in seen:
            seen.add((n, k))
            ordered.append((n, k))
    tried = []
    for name, kind in ordered:
        for repo in REPOS:
            url = f"{repo}/{gpath(group)}/{name}/{version}/{name}-{version}.{kind}"
            if url_exists(url) <= 0:
                continue
            dest_dir = os.path.join(DEPS_DIR, group, artifact, version)
            os.makedirs(dest_dir, exist_ok=True)
            dest = os.path.join(dest_dir, f"{name}-{version}.{kind}")
            download(url, dest)
            if looks_real(dest, kind):
                return name, kind, repo, url, dest
            os.remove(dest)
            tried.append(f"stub:{name}-{version}.{kind}")
    raise RuntimeError(
        f"No class-carrying artifact for {group}:{artifact}:{version} (tried {tried[:6]})"
    )


def download(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return
    tmp = dest + ".part"
    req = urllib.request.Request(url, headers={"User-Agent": "zenimusic-resolver/1.0"})
    with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
        while True:
            chunk = r.read(65536)
            if not chunk:
                break
            f.write(chunk)
    os.replace(tmp, dest)


def looks_real(path, kind):
    import zipfile
    try:
        z = zipfile.ZipFile(path)
        names = z.namelist()
        if kind == "aar":
            cj = [n for n in names if n == "classes.jar"]
            if not cj:
                return False
            with z.open("classes.jar") as cjj:
                inner = zipfile.ZipFile(cjj)
                return any(n.endswith(".class") for n in inner.namelist())
        else:
            return any(n.endswith(".class") for n in names)
    except Exception:
        return False


WANTED = [
    ("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-android", "1.8.1"),
    ("androidx.core", "core-ktx", "1.15.0"),
    ("androidx.activity", "activity", "1.9.2"),
    ("androidx.lifecycle", "lifecycle-runtime-ktx", "2.8.6"),
    ("androidx.lifecycle", "lifecycle-runtime-compose", "2.8.6"),
    ("androidx.lifecycle", "lifecycle-viewmodel", "2.8.6"),
    ("androidx.lifecycle", "lifecycle-viewmodel-compose", "2.8.6"),
    ("androidx.activity", "activity-compose", "1.9.2"),
    ("androidx.compose.runtime", "runtime", "1.7.5"),
    ("androidx.compose.runtime", "runtime-saveable", "1.7.5"),
    ("androidx.compose.ui", "ui", "1.7.5"),
    ("androidx.compose.ui", "ui-graphics", "1.7.5"),
    ("androidx.compose.material3", "material3", "1.3.1"),
    ("androidx.compose.material", "material-icons-core", "1.7.5"),
    ("androidx.compose.material", "material-icons-extended", "1.7.5"),
    ("androidx.media3", "media3-exoplayer", "1.5.1"),
    ("androidx.media3", "media3-session", "1.5.1"),
    ("androidx.media3", "media3-ui", "1.5.1"),
    ("com.github.TeamNewPipe", "NewPipeExtractor", "v0.26.5"),
    ("com.squareup.okhttp3", "okhttp", "4.12.0"),
    ("io.coil-kt", "coil-compose", "2.7.0"),
    ("com.google.guava", "guava", "33.2.1-android"),
]

SKIP = {
    ("org.jetbrains.kotlin", "kotlin-stdlib"),  # provided explicitly
}


def normalize_version(v):
    v = v.strip()
    if v[:1] in "[(":
        # version range: take the lower bound ([1.2] -> 1.2, [1.0,2.0) -> 1.0)
        inner = v[1:].split(",")[0].strip().rstrip("])")
        return inner or v
    return v


def nkey(group, artifact):
    """Normalized dedup key: KMP '-android'/'-jvm' variants are the same library."""
    return (group, re.sub(r"-(android|jvm)$", "", artifact))


def module_deps(group, artifact, version):
    """Parse Gradle module metadata (.module) for API dependencies.
    Returns list of (group, name, version) or None if unavailable."""
    for repo in REPOS:
        url = f"{repo}/{gpath(group)}/{artifact}/{version}/{artifact}-{version}.module"
        try:
            data = fetch(url)
            if data[:1] != b"{":
                continue
            d = __import__("json").loads(data.decode("utf-8", "replace"))
        except Exception:
            continue
        variants = d.get("variants", [])
        # prefer release API publication variant
        best = None
        for v in variants:
            n = v.get("name", "")
            if "Api" in n and v.get("dependencies"):
                if "Release" in n or best is None:
                    best = v
        if best is None:
            for v in variants:
                if v.get("dependencies"):
                    best = v
                    break
        if best is None:
            return []
        out = []
        for dep in best.get("dependencies", []):
            g2, a2 = dep.get("group"), dep.get("module")
            ver = dep.get("version", {})
            v2 = ver.get("requires") or ver.get("strictly") or ver.get("prefers")
            if g2 and a2 and v2:
                out.append((g2, a2, normalize_version(str(v2))))
        return out
    return None


def main():
    resolved = {}   # nkey -> dict(version, name, kind, path, orig)
    queue = list(WANTED)
    order = []
    while queue:
        g, a, v = queue.pop(0)
        key = nkey(g, a)
        if key in resolved or key in SKIP:
            # upgrade path: an 'empty' placeholder may be replaced by a real variant
            if key in resolved and resolved[key]["kind"] == "empty":
                pass
            else:
                continue
        print(f"[resolve] {g}:{a}:{v}", flush=True)
        try:
            eff_name, kind, repo, url, dest = pick_artifact(g, a, v)
        except RuntimeError as e:
            print(f"  [empty] {e}", flush=True)
            if key not in resolved:
                resolved[key] = {"version": v, "name": None, "kind": "empty",
                                 "path": None, "orig": (g, a)}
                order.append(key)
            continue
        # Dependencies: prefer Gradle module metadata (accurate for KMP '-android'
        # artifacts whose POMs are redirect stubs); fall back to POM parsing.
        dep_list = module_deps(g, a, v)
        if not dep_list:
            try:
                pom = Pom(g, eff_name, v)
            except RuntimeError:
                pom = Pom(g, a, v)
            dep_list = []
            for dg, da, dv, scope, optional in pom.deps:
                if scope not in ("compile", "runtime"):
                    continue
                if optional:
                    continue
                if dv is None:
                    dv = pom.dep_mgmt.get((dg, da))
                if dv is None:
                    print(f"  [warn] no version for transitive {dg}:{da}; skipping", flush=True)
                    continue
                dep_list.append((dg, da, normalize_version(dv)))
        resolved[key] = {"version": v, "name": eff_name, "kind": kind,
                         "path": dest, "orig": (g, a)}
        if key not in order:
            order.append(key)
        for dg, da, dv in dep_list:
            nk = nkey(dg, da)
            if nk in SKIP:
                continue
            if nk in resolved and resolved[nk]["kind"] != "empty":
                continue
            if (dg, da, dv) not in queue:
                queue.append((dg, da, dv))
    print(f"\nResolved {len(resolved)} artifacts into {DEPS_DIR}", flush=True)
    with open(os.path.join(DEPS_DIR, "resolved.txt"), "w") as f:
        for k in order:
            r = resolved[k]
            og, oa = r["orig"]
            f.write(f"{og}:{oa}:{r['version']} [{r['kind']}] {r['path']}\n")
    print("Wrote resolved.txt", flush=True)


if __name__ == "__main__":
    main()
