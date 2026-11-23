#!/usr/bin/env python3
"""Audit nach Dimensionen: prüft ein Git-Repo und schreibt einen Bericht als Markdown und als Text.

Aufruf: python3 .github/scripts/repo_audit.py [<repo>] [--out <ordner>] [--github owner/name] [--no-run]
        [--extra <befunde.md>] [--notes <einordnung.json>]
  <repo>     Repo-Ordner (Standard: das Repo dieses Skripts)
  --out      Zielordner für audit-bericht.md und audit-bericht.txt (Standard: ./audit)
  --github   fragt zusätzlich Einstellungen über die GitHub-REST-API ab (braucht ein angemeldetes «gh»)
  --no-run   führt die Prüfskripte des Repos (.github/scripts) nicht aus
  --extra    Markdown mit Befunden von Hand, steht nach der Übersicht im Bericht
  --notes    JSON {Teil des Titels: Einordnung}, hängt eine Bewertung an passende Prüfungen
Dimensionen: Sicherheit, Datenschutz, Dokumentation, Tests, CI/CD, Code-Qualität, Abhängigkeiten,
Lokalisierung, Repo-Hygiene. Nur lesend: Das Werkzeug ändert am Repo nichts. Ergebnis je Prüfung: OK, HINWEIS
oder BEFUND; Note je Dimension 100 minus 25 je BEFUND und 5 je HINWEIS (mindestens 0). Läuft nicht in der CI
(die CI prüft die Regeln selbst); gedacht für einen Überblick, z. B. mit audit.bat aus dem Test-Werkzeug.
Selbsttest: test_repo_audit.py.
"""
import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from collections import Counter, defaultdict

OK, HINT, FIND = "OK", "HINWEIS", "BEFUND"
TEXT_EXT = {".kt", ".kts", ".swift", ".py", ".sh", ".yml", ".yaml", ".xml", ".json", ".md", ".txt",
            ".toml", ".properties", ".gradle", ".plist", ".entitlements", ".strings", ".xcstrings", ".pro",
            ".ps1", ".bat", ".html", ".css", ".js", ".night", ".vectors", ".csv", ".cfg", ".ini", ".pbxproj"}
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


class Audit:
    def __init__(self, repo, github=None, run_scripts=True):
        self.repo = os.path.abspath(repo)
        self.github = github
        self.run_scripts = run_scripts
        self.results = defaultdict(list)   # Dimension -> [(status, titel, detail)]
        self.facts = {}
        self.files = self.git("ls-files").splitlines()

    # ---------- Hilfen ----------
    def git(self, *args):
        return subprocess.run(["git", "-C", self.repo, *args], capture_output=True, text=True).stdout

    notes = {}

    def add(self, dim, status, title, detail=""):
        for key, note in self.notes.items():
            if key in title:
                detail = (detail.strip() + "\nEinordnung: " + note).strip()
        self.results[dim].append((status, title, detail.strip()))

    def path(self, f):
        return os.path.join(self.repo, f)

    def read(self, f):
        try:
            with open(self.path(f), encoding="utf-8", errors="replace") as h:
                return h.read()
        except OSError:
            return ""

    def text_files(self, exts=None):
        for f in self.files:
            ext = os.path.splitext(f)[1].lower()
            if (exts and ext in exts) or (not exts and ext in TEXT_EXT):
                yield f

    @staticmethod
    def is_test(f):
        return bool(re.search(r"(/test/|/androidTest/|/testFixtures/|/screenshotTest/|Tests?/|Tests?\.swift$|(^|/)test_[^/]*\.py$)", f))

    def src(self, ext, test=None):
        """Quelldateien einer Endung; test=True nur Tests, False ohne Tests."""
        out = []
        for f in self.files:
            if not f.endswith(ext):
                continue
            is_test = bool(re.search(r"(/test/|/androidTest/|/testFixtures/|/screenshotTest/|Tests?/|Tests?\.swift$)", f))
            if test is None or test == is_test:
                out.append(f)
        return out

    def loc(self, files):
        n = 0
        for f in files:
            n += sum(1 for line in self.read(f).splitlines() if line.strip() and not line.strip().startswith("//"))
        return n

    def gh(self, endpoint):
        try:
            r = subprocess.run(["gh", "api", endpoint], capture_output=True, text=True, timeout=30)
        except (OSError, subprocess.TimeoutExpired):
            return None, "gh nicht verfügbar"
        if r.returncode != 0:
            return None, (r.stdout + r.stderr).strip()[:200]
        try:
            return json.loads(r.stdout), None
        except ValueError:
            return None, "keine JSON-Antwort"

    # ---------- Dimensionen ----------
    def security(self):
        d = "Sicherheit"
        patterns = {
            "Privater Schlüssel": r"-----BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----",
            "AWS-Schlüssel": r"AKIA[0-9A-Z]{16}",
            "GitHub-Token": r"gh[pousr]_[A-Za-z0-9]{36,}",
            "Google-API-Schlüssel": r"AIza[0-9A-Za-z_\-]{35}",
            "Slack-Token": r"xox[baprs]-[0-9A-Za-z-]{10,}",
            "Passwort im Klartext": r"(?i)\b\w*password\w*\s*[=:]\s*['\"][^'\"$]{4,}['\"]",
        }
        hits = []
        for f in self.text_files():
            if f.startswith("licenses/") or f.endswith("strings.xml") or f.endswith(".xcstrings"):
                continue
            t = self.read(f)
            for name, p in patterns.items():
                for m in re.finditer(p, t):
                    line = t.count("\n", 0, m.start()) + 1
                    hits.append(f"{f}:{line} – {name}")
        # Bekannte Testwerte: Android-Debug-Passwort «android» und die Ausnahmen in .gitleaks.toml
        known = {"android"}
        q3 = "'" * 3
        for rx in re.findall(q3 + r"\^?(.*?)\$?" + q3, self.read(".gitleaks.toml")):
            known.add(rx)

        def is_known(h):
            f, line = h.split(" – ")[0].rsplit(":", 1)
            txt = self.read(f).splitlines()[int(line) - 1]
            vals = re.findall(r"['\"]([^'\"]+)['\"]", txt)
            for v in vals:
                for k in known:
                    try:
                        if v == k or re.fullmatch(k, v):
                            return True
                    except re.error:
                        pass
            return False
        allowed_pw = [h for h in hits if "Passwort" in h and (is_known(h) or self.is_test(h.split(":")[0]))]
        real = [h for h in hits if h not in allowed_pw]
        if real:
            self.add(d, FIND, f"Mögliche Geheimnisse im Code ({len(real)})", "\n".join(real[:20]))
        else:
            self.add(d, OK, "Keine Geheimnisse nach bekannten Mustern gefunden",
                     f"{len(allowed_pw)} Treffer sind bekannte Testwerte (Debug-Keystore «android», Testvektoren aus .gitleaks.toml)" if allowed_pw else "")
        keys = [f for f in self.files if re.search(r"\.(jks|keystore|p12|pem|mobileprovision|p8)$", f)]
        if keys:
            st = HINT if all("debug" in k or "testing" in k for k in keys) else FIND
            self.add(d, st, f"Schlüsseldateien im Repo: {len(keys)}", "\n".join(keys) +
                     ("\nNur Test-Schlüssel (öffentlich bekannt, für Store-Builds ungeeignet)." if st == HINT else ""))
        else:
            self.add(d, OK, "Keine Schlüsseldateien im Repo")
        if os.path.exists(self.path(".gitleaks.toml")) or any("gitleaks" in self.read(w) for w in self.workflows()):
            self.add(d, OK, "Geheimnis-Suche (gitleaks) in der CI")
        else:
            self.add(d, FIND, "Keine Geheimnis-Suche in der CI")

        # Android-Manifeste
        for mf in [f for f in self.files if f.endswith("AndroidManifest.xml") and "/main/" in f]:
            try:
                root = ET.fromstring(self.read(mf))
            except ET.ParseError as e:
                self.add(d, FIND, f"{mf} nicht lesbar", str(e))
                continue
            app = root.find("application")
            if app is None or (not app.attrib and root.find("uses-permission") is None):
                continue   # Bibliotheks-Manifest: Attribute kommen aus dem App-Manifest
            a = lambda el, k: el.get(ANDROID_NS + k)
            issues, info = [], []
            if a(app, "allowBackup") != "false":
                issues.append("allowBackup nicht «false» (Daten landen in der Cloud-Sicherung)")
            if a(app, "usesCleartextTraffic") == "true":
                issues.append("usesCleartextTraffic=true")
            if a(app, "debuggable") == "true":
                issues.append("debuggable=true im Hauptmanifest")
            perms = [a(p, "name") for p in root.findall("uses-permission")]
            removed = [a(p, "name") for p in root.findall("uses-permission")
                       if p.get("{http://schemas.android.com/tools}node") == "remove"]
            unguarded = []
            for tag in ("activity", "service", "receiver", "provider", "activity-alias"):
                for el in app.findall(tag):
                    if a(el, "exported") != "true":
                        continue
                    name = a(el, "name") or "?"
                    actions = [a(x, "name") for x in el.iter("action")]
                    launcher = any(c.get(ANDROID_NS + "name", "").endswith("LAUNCHER") for c in el.iter("category"))
                    if a(el, "permission") or launcher:
                        continue
                    cls = (a(el, "name") or "").split(".")[-1].split("$")[-1]
                    body = "\n".join(self.read(f) for f in self.files if f.endswith(".kt") and "/main/" in f
                                     and re.search(r"class\s+" + re.escape(cls) + r"\b", self.read(f)))
                    if tag == "service" and re.search(r"WearableListenerService", body):
                        continue   # nur Google Play-Dienste binden an (Data Layer)
                    if tag == "receiver" and re.search(r"intent\.action\s*(!?in|==|!=)", body):
                        continue   # prüft die Aktion selbst
                    if any("SHOW_PERMISSIONS_RATIONALE" in (x or "") or "VIEW_PERMISSION_USAGE" in (x or "") for x in actions):
                        continue   # von Health Connect verlangt
                    unguarded.append(f"{tag} {name.split('.')[-1]} ({', '.join(x.split('.')[-1] for x in actions) or 'ohne Filter'})")
            if "android.permission.INTERNET" in removed:
                info.append("INTERNET ausdrücklich entfernt (kein Netz)")
            info.append(f"{len(perms) - len(removed)} Rechte")
            if issues:
                self.add(d, FIND, f"{mf}: " + "; ".join(issues))
            self.add(d, HINT if unguarded else OK,
                     f"{mf}: {len(unguarded)} exportierte Komponenten ohne Recht" if unguarded else f"{mf}: Manifest sauber",
                     ("; ".join(info) + ("\nOhne android:permission (Filter prüfen, ob nur System-Absender gemeint sind):\n- "
                                         + "\n- ".join(unguarded) if unguarded else "")))
        # iOS
        ats = [f for f in self.files if f.endswith(".plist") and "NSAllowsArbitraryLoads" in self.read(f)]
        self.add(d, FIND if ats else OK, "iOS: ATS-Ausnahme (NSAllowsArbitraryLoads)" if ats else "iOS: keine ATS-Ausnahme",
                 "\n".join(ats))

        # Workflows
        unpinned, inject, prt, noperm, notimeout = [], [], [], [], []
        risky = r"\$\{\{\s*github\.(event\.(issue|pull_request|comment|review|head_commit|workflow_run\.(head_branch|display_title|head_commit))[^}]*|head_ref)\s*\}\}"
        for w in self.workflows():
            t = self.read(w)
            for m in re.finditer(r"uses:\s*([^\s#]+)", t):
                ref = m.group(1)
                if ref.startswith("./") or ref.startswith("docker://"):
                    continue
                if not re.search(r"@[0-9a-f]{40}$", ref):
                    unpinned.append(f"{w}: {ref}")
            # Ausdrücke aus fremden Eingaben direkt in run:-Blöcken
            for block in re.finditer(r"run:\s*\|?\n?((?:[ \t]+.*\n?)+)", t):
                for m in re.finditer(risky, block.group(1)):
                    inject.append(f"{w}: {m.group(0)}")
            if "pull_request_target" in t:
                prt.append(w)
            if not re.search(r"^permissions:", t, re.M):
                noperm.append(w)
            jobs = re.findall(r"^  ([A-Za-z0-9_-]+):\n((?:    .*\n|\n)+)", t.split("\njobs:\n", 1)[-1] + "\n", re.M)
            for name, body in jobs:
                if "timeout-minutes" not in body and "uses: ./" not in body:
                    notimeout.append(f"{w}: {name}")
        self.facts["workflows"] = len(self.workflows())
        self.add(d, FIND if unpinned else OK,
                 f"Actions ohne feste Commit-Kennung: {len(unpinned)}" if unpinned else "Alle Actions auf feste Commit-Kennung gepinnt",
                 "\n".join(unpinned))
        self.add(d, FIND if inject else OK,
                 f"Fremde Eingaben direkt in run:-Skripten ({len(inject)})" if inject else "Keine fremden Eingaben direkt in run:-Skripten",
                 "\n".join(inject) + ("\nÜber env: übergeben statt ${{ }} im Skript." if inject else ""))
        self.add(d, HINT if prt else OK, "pull_request_target im Einsatz" if prt else "Kein pull_request_target", "\n".join(prt))
        self.add(d, FIND if noperm else OK,
                 f"Workflows ohne Standard-Rechte (permissions:): {len(noperm)}" if noperm else "Alle Workflows setzen permissions: oben",
                 "\n".join(noperm))
        self.facts["notimeout"] = notimeout

        if os.path.exists(self.path(".github/dependabot.yml")):
            eco = re.findall(r"package-ecosystem:\s*(\S+)", self.read(".github/dependabot.yml"))
            self.add(d, OK, "Dependabot-Updates eingerichtet", "Ökosysteme: " + ", ".join(eco))
        else:
            self.add(d, HINT, "Keine automatischen Update-Vorschläge (Dependabot/Renovate)")
        if any("osv" in f.lower() for f in self.files):
            self.add(d, OK, "Schwachstellen-Prüfung der Abhängigkeiten (OSV) im Repo")
        wrapper = self.read("android/gradle/wrapper/gradle-wrapper.properties")
        if wrapper:
            self.add(d, OK if "distributionSha256Sum" in wrapper else HINT,
                     "Gradle-Wrapper mit Prüfsumme" if "distributionSha256Sum" in wrapper else "Gradle-Wrapper ohne Prüfsumme")
        if any(f.endswith("verification-metadata.xml") for f in self.files):
            self.add(d, OK, "Prüfsummen der Gradle-Abhängigkeiten (verification-metadata.xml)")
        reqs = [f for f in self.files if re.search(r"requirements.*\.txt$", f)]
        unhashed = [f for f in reqs if "--hash=" not in self.read(f)]
        if reqs:
            self.add(d, HINT if unhashed else OK,
                     "Python-Anforderungen ohne Prüfsummen" if unhashed else "Python-Anforderungen mit festen Versionen und Prüfsummen",
                     "\n".join(unhashed))
        if not any(os.path.basename(f).upper() in ("SECURITY.MD",) for f in self.files):
            self.add(d, HINT, "Keine SECURITY.md (wie man Sicherheitslücken meldet)",
                     "GitHub zeigt sie im Reiter «Security»; ein kurzer Absatz mit Kontaktweg reicht.")

    def privacy(self):
        d = "Datenschutz"
        mails = Counter()
        phones = []
        for f in self.text_files():
            if f.startswith("licenses/") or "NOTICE" in f:
                continue   # Lizenztexte Dritter
            t = self.read(f)
            for m in re.finditer(r"[\w.+-]+@[\w-]+\.[\w.-]*\w", t):
                e = m.group(0).lower()
                if re.search(r"(noreply|example|users\.noreply\.github\.com|anthropic|apple\.com|google\.com|\.local$)", e):
                    continue
                if re.search(r"\.(png|jpg|svg|kt|swift|xml|gradle)$", e) or e.startswith("android@"):
                    continue
                mails[e] += 1
            for m in re.finditer(r"(?<![\w.])\+?(41|49|43)[\s-]?\(?0?\)?[\s-]?\d{2}[\s-]?\d{3}[\s-]?\d{2}[\s-]?\d{2}(?!\d)", t):
                phones.append(f"{f}: {m.group(0)}")
        policy = self.read("docs/DATENSCHUTZ.md").lower()
        contact = [e for e in mails if e in policy]
        for e in contact:
            mails.pop(e)
        if contact:
            self.add(d, OK, "Kontaktadresse wie in der Datenschutzerklärung: " + ", ".join(contact))
        self.add(d, HINT if mails else OK,
                 f"Weitere E-Mail-Adressen im Repo: {len(mails)}" if mails else "Keine weiteren E-Mail-Adressen im Repo",
                 "\n".join(f"{e} ({n}×)" for e, n in mails.most_common(10)) +
                 ("\nPrüfen, ob jede davon öffentlich sein soll." if mails else ""))
        self.add(d, FIND if phones else OK, f"Telefonnummern im Repo: {len(phones)}" if phones else "Keine Telefonnummern im Repo",
                 "\n".join(phones[:10]))
        authors = Counter(self.git("log", "--format=%ae").split())
        committers = Counter(self.git("log", "--format=%ce").split())
        allm = set(authors) | set(committers)
        public = sorted(e for e in allm if not (e.endswith("noreply.github.com") or e == "noreply@github.com"))
        self.add(d, HINT if public else OK,
                 f"Commit-Adressen ohne noreply: {len(public)}" if public else "Alle Commit-Adressen sind noreply-Adressen",
                 "\n".join(f"{e} ({authors.get(e, 0) + committers.get(e, 0)} Einträge)" for e in public))
        trailers = len([x for x in re.findall(r"(?im)^(?:co-authored-by|claude-session):.*$", self.git("log", "--format=%B"))
                        if "dependabot" not in x.lower()])
        self.add(d, HINT if trailers else OK, f"Fremde Zuschreibungen in Commits: {trailers}" if trailers else
                 "Keine Co-Authored-By/Session-Zeilen in Commits")
        if any(f.endswith("DATENSCHUTZ.md") or "privacy" in f.lower() for f in self.files):
            self.add(d, OK, "Datenschutzerklärung vorhanden")
        else:
            self.add(d, FIND, "Keine Datenschutzerklärung")

    def docs(self):
        d = "Dokumentation"
        must = {"README.md": FIND, "LICENSE": FIND, "SUPPORT.md": HINT, "CONTRIBUTING.md": HINT, "CHANGELOG.md": HINT}
        for f, st in must.items():
            present = any(x.upper() == f.upper() or x.upper().endswith("/" + f.upper()) for x in self.files)
            if present:
                self.add(d, OK, f"{f} vorhanden")
            else:
                why = {"CONTRIBUTING.md": "wie man beiträgt (Zweige, CI, Commit-Regeln) – für Fremde",
                       "CHANGELOG.md": "Änderungen je Version für Tester und Stores"}.get(f, "")
                self.add(d, st, f"{f} fehlt", why)
        docs = [f for f in self.files if f.startswith("docs/") and f.endswith(".md")]
        self.facts["docs"] = docs
        self.add(d, OK, f"{len(docs)} Fachdokumente in docs/", ", ".join(os.path.basename(x) for x in docs))
        # Relative Links in Markdown
        broken = []
        for f in [x for x in self.files if x.endswith(".md")]:
            base = os.path.dirname(f)
            for m in re.finditer(r"\]\(([^)#\s]+)(#[^)]*)?\)", self.read(f)):
                target = m.group(1)
                if re.match(r"^(https?:|mailto:|tel:)", target):
                    continue
                p = os.path.normpath(os.path.join(base, target))
                if p not in self.files and not any(x.startswith(p + "/") for x in self.files):
                    broken.append(f"{f} → {target}")
        self.add(d, FIND if broken else OK, f"Kaputte relative Links: {len(broken)}" if broken else "Alle relativen Links in Markdown führen auf vorhandene Dateien",
                 "\n".join(broken[:20]))
        # Verweise «ENTWICKLUNG.md 3» usw. auf Abschnittsnummern prüfen
        sec_refs = []
        heads = {}
        for f in docs:
            heads[os.path.basename(f)] = set(re.findall(r"^#+\s*(\d+[a-z]?)[.\s]", self.read(f), re.M))
        for f in [x for x in self.files if x.endswith((".md", ".kt", ".swift", ".yml", ".py"))]:
            for m in re.finditer(r"\b([A-Z]+\.md)\s+(\d+[a-z]?)\b", self.read(f)):
                doc, num = m.group(1), m.group(2)
                if doc in heads and heads[doc] and num not in heads[doc]:
                    sec_refs.append(f"{f}: {doc} {num}")
        sec_refs = sorted(set(sec_refs))
        self.add(d, HINT if sec_refs else OK,
                 f"Verweise auf fehlende Abschnitte: {len(sec_refs)}" if sec_refs else "Abschnittsverweise (z. B. «ENTWICKLUNG.md 3») stimmen",
                 "\n".join(sec_refs[:20]))
        # Kommentar-Dichte
        for lang, ext, rx in (("Kotlin", ".kt", r"^\s*/\*\*"), ("Swift", ".swift", r"^\s*///")):
            files = self.src(ext, test=False)
            docd = sum(1 for f in files if re.search(rx, self.read(f), re.M))
            if files:
                share = 100 * docd // len(files)
                self.add(d, OK if share >= 80 else HINT, f"{lang}: {share} % der Quelldateien mit Doku-Kommentar ({docd}/{len(files)})")
        # Lesbarkeit: sehr lange Absätze/Aufzählungspunkte (eine Zeile = ein Absatz in Markdown)
        long_lines = []
        for f in docs + ["README.md"]:
            for i, line in enumerate(self.read(f).splitlines(), 1):
                n = len(line.split())
                if n > 200 and not line.lstrip().startswith("|"):   # Tabellenzeilen (z. B. ENTSCHEIDE) zählen nicht
                    long_lines.append((n, f"{f}:{i} ({n} Wörter)"))
        long_lines.sort(reverse=True)
        self.add(d, HINT if long_lines else OK,
                 f"Absätze über 200 Wörter: {len(long_lines)}" if long_lines else "Keine Absätze über 200 Wörter",
                 "\n".join(x for _, x in long_lines[:8]) +
                 ("\nRegel und Herleitung trennen: die Regel kurz in der Fachdoku, die Geschichte in ENTSCHEIDE.md." if long_lines else ""))
        # Offene Punkte: Alter
        offen = self.read("docs/OFFEN.md")
        if offen:
            n = len(re.findall(r"^\d+[a-z]*\.", offen, re.M))
            self.add(d, OK, f"Offene Punkte gepflegt in docs/OFFEN.md ({n} Einträge)")

    def tests(self):
        d = "Tests"
        kt_src, kt_test = self.src(".kt", False), self.src(".kt", True)
        sw_src, sw_test = self.src(".swift", False), self.src(".swift", True)
        n_kt = sum(len(re.findall(r"@Test\b", self.read(f))) for f in kt_test)
        n_sw = sum(len(re.findall(r"(func test\w+\s*\(|@Test\b)", self.read(f))) for f in sw_test)
        lk, lkt, ls, lst = self.loc(kt_src), self.loc(kt_test), self.loc(sw_src), self.loc(sw_test)
        self.facts.update(kt_src=len(kt_src), kt_test=len(kt_test), sw_src=len(sw_src), sw_test=len(sw_test),
                          n_kt=n_kt, n_sw=n_sw, lk=lk, lkt=lkt, ls=ls, lst=lst)
        for lang, n, a, b in (("Kotlin", n_kt, lk, lkt), ("Swift", n_sw, ls, lst)):
            ratio = b / a if a else 0
            st = OK if ratio >= 0.5 else HINT
            self.add(d, st, f"{lang}: {n} Testfälle, Testcode/Code {ratio:.2f} ({b} / {a} Zeilen)")
        # Module ohne Tests
        mods = sorted({f.split("/")[1] for f in kt_src if f.startswith("android/") and "/src/main/" in f})
        no_test = [m for m in mods if not any(f.startswith(f"android/{m}/") for f in kt_test)]
        self.add(d, HINT if no_test else OK, f"Android-Module ohne Tests: {', '.join(no_test)}" if no_test else
                 f"Alle Android-Module haben Tests ({', '.join(mods)})")
        # Gemeinsame Testdaten auf beiden Plattformen
        shared = [f for f in self.files if f.startswith("shared/testdata/") and not f.endswith(("README.md", "MANIFEST"))]
        corpus_kt = "\n".join(self.read(f) for f in kt_test + kt_src if "test" in f.lower())
        corpus_sw = "\n".join(self.read(f) for f in sw_test)
        man = self.read("shared/testdata/MANIFEST")
        only = []
        for f in shared:
            b = os.path.basename(f)
            stem = os.path.splitext(b)[0]
            in_kt = b in corpus_kt or stem in corpus_kt
            in_sw = b in corpus_sw or stem in corpus_sw
            if not (in_kt and in_sw) and b not in man:
                only.append(f"{b}: " + ("nur Kotlin" if in_kt else "nur Swift" if in_sw else "nirgends erwähnt"))
        if shared:
            uses_manifest = "MANIFEST" in corpus_kt and "MANIFEST" in corpus_sw
            if uses_manifest:
                self.add(d, OK, f"{len(shared)} gemeinsame Testdateien in shared/testdata, beide Plattformen lesen das MANIFEST")
            else:
                self.add(d, HINT if only else OK, f"Gemeinsame Testdateien nicht auf beiden Plattformen: {len(only)}" if only
                         else f"{len(shared)} gemeinsame Testdateien auf beiden Plattformen genutzt", "\n".join(only[:20]))
        gates = []
        for w in self.workflows():
            t = self.read(w).replace("\\\n", " ")
            for m in re.finditer(r"coverage_check\.py\s+\"([^\"]+)\"\s+(\S+)", t):
                gates.append((m.group(1), m.group(2)))
            for m in re.finditer(r"xccov_check\.py\s+\"([^\"]+)\"([^\n]*)", t):
                for fm in re.finditer(r"([\w/+.-]+\.swift)=(\d+)", m.group(2)):
                    gates.append((m.group(1) + " " + os.path.basename(fm.group(1)), fm.group(2)))
        if gates:
            zero = [g for g, v in gates if v == "0"]
            self.add(d, HINT if zero else OK, f"Abdeckungs-Grenzen in der CI: {len(gates)}" + (f", davon {len(zero)} nur gemeldet (0 %)" if zero else ""),
                     "\n".join(f"{g}: {v} %" if v.replace('.', '').isdigit() else f"{g}: {v}" for g, v in gates))
        shots = [f for f in self.files if "screenshotTest" in f or "/screenshots/" in f or "reference" in f.lower() and f.endswith(".png")]
        if shots:
            self.add(d, OK, f"Bildschirmfoto-Tests mit Referenzbildern ({len(shots)} Dateien)")
        # Übersprungene Tests
        ign = []
        for f in kt_test + sw_test:
            for m in re.finditer(r"(@Ignore\b|@Disabled\b|XCTSkip(?!If|Unless)|\.disabled\()", self.read(f)):
                ign.append(f"{f}: {m.group(0)}")
            cond = len(re.findall(r"XCTSkip(If|Unless)", self.read(f)))
            if cond:
                self.facts.setdefault("condskip", []).append(f"{f}: {cond}× bedingt")
        self.add(d, HINT if ign else OK, f"Abgeschaltete Tests: {len(ign)}" if ign else "Keine abgeschalteten Tests",
                 "\n".join(ign[:15] + [f"Nur unter Bedingung übersprungen: {x}" for x in self.facts.get("condskip", [])]))

    def cicd(self):
        d = "CI/CD"
        wfs = self.workflows()
        trig = {}
        for w in wfs:
            t = self.read(w)
            on = re.search(r"^on:\s*\n((?:  .*\n)+)", t, re.M)
            trig[os.path.basename(w)] = ", ".join(re.findall(r"^  ([a-z_]+):", on.group(1), re.M)) if on else "?"
        self.add(d, OK, f"{len(wfs)} Workflows", "\n".join(f"{k}: {v}" for k, v in trig.items()))
        nt = self.facts.get("notimeout", [])
        self.add(d, HINT if nt else OK, f"Jobs ohne timeout-minutes: {len(nt)}" if nt else "Alle Jobs mit Zeitlimit",
                 "\n".join(nt))
        nocc = [w for w in wfs if "concurrency:" not in self.read(w) and re.search(r"^\s+(push|pull_request|schedule):", self.read(w), re.M)]
        self.add(d, HINT if nocc else OK, f"Automatisch startende Workflows ohne concurrency: {len(nocc)}" if nocc else
                 "Automatisch startende Workflows begrenzen parallele Läufe", "\n".join(nocc))
        sched = [w for w in wfs if "schedule:" in self.read(w)]
        self.add(d, OK, f"Zeitgesteuerte Workflows: {len(sched)}", "\n".join(sched))
        caches = [w for w in wfs if re.search(r"setup-gradle|actions/cache|cache:", self.read(w))]
        self.add(d, OK if caches else HINT, f"Build-Cache in {len(caches)} Workflows" if caches else "Kein Build-Cache")
        scripts = [f for f in self.files if f.startswith(".github/scripts/") and f.endswith(".py")]
        tests = [f for f in scripts if os.path.basename(f).startswith("test_")]
        self.add(d, OK if tests else HINT, f"{len(scripts)} Prüfskripte, davon {len(tests)} Selbsttests")
        tracked_cache = [f for f in self.files if "__pycache__" in f or f.endswith(".pyc")]
        if tracked_cache:
            self.add(d, FIND, "Python-Cache im Repo eingecheckt", "\n".join(tracked_cache[:10]))
        if self.run_scripts:
            self.run_repo_checks(d)
        if self.github:
            self.github_settings(d)

    def run_repo_checks(self, d):
        cwd = self.path("android")
        checks = [
            ("Selbsttests der Prüfskripte", ["-B", "-m", "unittest", "discover", "-s", "../.github/scripts", "-p", "test_*.py"]),
            ("Zeilen je Datei", ["../.github/scripts/line_limit.py"]),
            ("Code-Grenzen", ["../.github/scripts/code_limits.py"]),
            ("Keine Build-Nummern im Code", ["../.github/scripts/no_build_numbers.py"]),
            ("Versionen gleich", ["../.github/scripts/version_check.py"]),
            ("Doku-Kommentare", ["../.github/scripts/doc_check.py"]),
            ("Übersetzungen vollständig", ["../.github/scripts/check-translations.py", "app/src/main/res", "wear/src/main/res", "../ios/Strings"]),
            ("iOS-Kataloge aktuell", ["../.github/scripts/gen-xcstrings.py", "--check"]),
        ]
        bad, good = [], []
        for name, args in checks:
            script = args[0] if not args[0].startswith("-") else None
            if script and not os.path.exists(os.path.join(cwd, script)):
                continue
            r = subprocess.run([sys.executable, *args], cwd=cwd, capture_output=True, text=True)
            if r.returncode == 0:
                good.append(name)
            else:
                tail = "\n".join((r.stdout + r.stderr).strip().splitlines()[-6:])
                bad.append(f"{name}:\n{tail}")
        self.add(d, FIND if bad else OK,
                 f"Prüfskripte des Repos: {len(good)} bestanden, {len(bad)} mit Befund",
                 ("Bestanden: " + ", ".join(good)) + ("\n" + "\n".join(bad) if bad else ""))

    def github_settings(self, d):
        rs, err = self.gh(f"repos/{self.github}/rulesets")
        if rs is None:
            self.add(d, HINT, "Regeln für main nicht abfragbar", err)
        else:
            for r in rs:
                det, _ = self.gh(f"repos/{self.github}/rulesets/{r['id']}")
                if not det:
                    continue
                types = [x["type"] for x in det.get("rules", [])]
                checks = [c["context"] for x in det.get("rules", []) if x["type"] == "required_status_checks"
                          for c in x["parameters"]["required_status_checks"]]
                self.add(d, OK, f"Regel «{det['name']}» ({det['enforcement']}): {', '.join(types)}",
                         "Verlangte Prüfungen: " + ", ".join(checks) + (f"\nAusnahmen: {det['bypass_actors']}" if det.get("bypass_actors") else ""))
        repo, err = self.gh(f"repos/{self.github}")
        if repo:
            sa = repo.get("security_and_analysis") or {}
            on = [k for k, v in sa.items() if v.get("status") == "enabled"]
            off = [k for k, v in sa.items() if v.get("status") != "enabled"]
            _, err = self.gh(f"repos/{self.github}/dependabot/alerts?per_page=1")
            alerts_off = bool(err and "disabled" in err)
            if alerts_off:
                off.insert(0, "dependabot_alerts")
            self.add("Sicherheit", HINT if alerts_off or "dependabot_security_updates" in off else OK,
                     "GitHub-Sicherheitsfunktionen" + (": Dependabot-Warnungen aus" if alerts_off else ""),
                     "an: " + ", ".join(on) + "\naus: " + ", ".join(off) +
                     ("\nEinstellungen → Code security → Dependabot alerts; ergänzt die OSV-Prüfung der CI um Actions und pip."
                      if alerts_off else ""))
            self.facts["visibility"] = repo.get("visibility")
        br, _ = self.gh(f"repos/{self.github}/branches?per_page=100")
        if br:
            names = [b["name"] for b in br]
            work = [n for n in names if n.startswith("aenderung/")]
            self.add("Repo-Hygiene", HINT if work else OK, f"{len(names)} Zweige auf GitHub" + (f", davon {len(work)} Arbeitszweige" if work else ""),
                     ", ".join(names))

    def quality(self):
        d = "Code-Qualität"
        sizes = []
        for f in self.src(".kt") + self.src(".swift"):
            sizes.append((len(self.read(f).splitlines()), f))
        sizes.sort(reverse=True)
        over = [s for s in sizes if s[0] > 400]
        self.add(d, HINT if over else OK, f"Dateien über 400 Zeilen: {len(over)}" if over else "Keine Code-Datei über 400 Zeilen",
                 "\n".join(f"{n} {f}" for n, f in over[:10]) or "Grösste: " + ", ".join(f"{os.path.basename(f)} {n}" for n, f in sizes[:3]))
        marks = []
        for f in self.src(".kt") + self.src(".swift") + [x for x in self.files if x.endswith((".py", ".sh"))]:
            if f.endswith(("no_build_numbers.py", "repo_audit.py")):
                continue   # suchen selbst nach diesen Marken
            for i, line in enumerate(self.read(f).splitlines(), 1):
                if re.search(r"\b(TODO|FIXME|HACK|XXX)\b", line):
                    marks.append(f"{f}:{i}: {line.strip()[:90]}")
        self.add(d, HINT if marks else OK, f"TODO/FIXME im Code: {len(marks)}" if marks else "Keine TODO/FIXME im Code", "\n".join(marks[:15]))
        bang, force, tryb, supp, prints = [], [], [], Counter(), []
        for f in self.src(".kt", False):
            t = self.read(f)
            for m in re.finditer(r"\w\)?!!", t):
                bang.append(f"{f}:{t.count(chr(10), 0, m.start()) + 1}")
            for m in re.finditer(r"@Suppress\(([^)]*)\)", t):
                supp[m.group(1)] += 1
            for m in re.finditer(r"(?m)^\s*(println|System\.out\.print)", t):
                prints.append(f)
        for f in self.src(".swift", False):
            t = self.read(f)
            for m in re.finditer(r"\btry!", t):
                tryb.append(f"{f}:{t.count(chr(10), 0, m.start()) + 1}")
            for m in re.finditer(r"\bas!\s", t):
                force.append(f"{f}:{t.count(chr(10), 0, m.start()) + 1}")
            for m in re.finditer(r"(?m)^\s*print\(", t):
                prints.append(f)
        self.add(d, HINT if bang else OK, f"Kotlin «!!» im App-Code: {len(bang)}" if bang else "Kotlin: kein «!!» im App-Code", "\n".join(bang[:10]))
        self.add(d, HINT if (force or tryb) else OK, f"Swift «try!»/«as!» im App-Code: {len(tryb) + len(force)}" if (force or tryb)
                 else "Swift: kein «try!»/«as!» im App-Code", "\n".join((tryb + force)[:10]))
        self.add(d, HINT if prints else OK, f"Konsolen-Ausgaben (print/println) im App-Code: {len(prints)}" if prints else
                 "Keine print/println im App-Code", "\n".join(sorted(set(prints))[:10]))
        if supp:
            self.add(d, HINT if sum(supp.values()) > 20 else OK, f"@Suppress im Kotlin-Code: {sum(supp.values())}",
                     "\n".join(f"{k}: {v}×" for k, v in supp.most_common(8)))
        # Doppelte Dateien (gleicher Inhalt)
        seen = defaultdict(list)
        for f in self.src(".kt") + self.src(".swift"):
            t = re.sub(r"\s+", "", self.read(f))
            if len(t) > 300:
                seen[hash(t)].append(f)
        dup = [v for v in seen.values() if len(v) > 1]
        self.add(d, HINT if dup else OK, f"Inhaltsgleiche Code-Dateien: {len(dup)} Gruppen" if dup else "Keine inhaltsgleichen Code-Dateien",
                 "\n".join(" = ".join(g) for g in dup[:5]))

    def deps(self):
        d = "Abhängigkeiten"
        toml = self.read("android/gradle/libs.versions.toml")
        if toml:
            sect = re.search(r"\[versions\]\n(.*?)\n\[", toml, re.S)
            vers = re.findall(r"^(\S+)\s*=\s*\"([^\"]+)\"", sect.group(1), re.M) if sect else []
            pre = [f"{k} {v}" for k, v in vers if re.search(r"(?i)(alpha|beta|rc|snapshot|dev)", v)]
            libs = len(re.findall(r"^\S+\s*=\s*\{", toml.split("[libraries]")[-1].split("[plugins]")[0], re.M))
            self.add(d, OK, f"Gradle-Versionskatalog: {len(vers)} Versionen, {libs} Bibliotheken")
            self.add(d, HINT if pre else OK, f"Vorab-Versionen (alpha/beta/rc): {len(pre)}" if pre else "Keine Vorab-Versionen",
                     "\n".join(pre) + ("\nBewusst gewählt? In ENTSCHEIDE.md vermerken." if pre else ""))
        gradle_files = [f for f in self.files if f.endswith(".gradle.kts")]
        inline = []
        for f in gradle_files:
            for m in re.finditer(r"\"([a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+:[0-9][^\"]*)\"", self.read(f)):
                inline.append(f"{f}: {m.group(1)}")
        self.add(d, HINT if inline else OK, f"Versionen ausserhalb des Katalogs: {len(inline)}" if inline else
                 "Alle Gradle-Abhängigkeiten über den Versionskatalog", "\n".join(inline[:12]))
        pbx = [f for f in self.files if f.endswith("project.pbxproj")]
        spm = sum(len(re.findall(r"XCRemoteSwiftPackageReference", self.read(f))) for f in pbx)
        self.add(d, OK, f"iOS: {spm // 2 if spm else 0} Swift-Pakete von aussen" if spm else "iOS: keine Swift-Pakete von aussen")
        lic = [f for f in self.files if f.startswith("licenses/")]
        if lic:
            self.add(d, OK, f"Lizenzen von Drittteilen abgelegt ({len(lic)} Dateien in licenses/)")

    def l10n(self):
        d = "Lokalisierung"
        langs_a = {re.sub(r"^values-?", "", f.split("/")[-2]) or "default" for f in self.files
                   if re.search(r"android/app/src/main/res/values[^/]*/strings\.xml$", f)}
        langs_w = {re.sub(r"^values-?", "", f.split("/")[-2]) or "default" for f in self.files
                   if re.search(r"android/wear/src/main/res/values[^/]*/strings\.xml$", f)}
        ios = {f.split("/")[2] for f in self.files if f.startswith("ios/Strings/") and f.count("/") >= 3}
        self.add(d, OK, f"Sprachen: Handy {len(langs_a)}, Uhr {len(langs_w)}, iOS {len(ios)}")
        diff = langs_a ^ langs_w
        if diff:
            self.add(d, HINT, "Handy und Uhr haben unterschiedliche Sprachen", ", ".join(sorted(diff)))

    def hygiene(self):
        d = "Repo-Hygiene"
        big = []
        for f in self.files:
            try:
                s = os.path.getsize(self.path(f))
            except OSError:
                continue
            if s > 1_000_000:
                big.append((s, f))
        big.sort(reverse=True)
        same = defaultdict(list)
        import hashlib
        for s, f in big:
            same[hashlib.md5(open(self.path(f), "rb").read()).hexdigest()].append(f)
        dups = [v for v in same.values() if len(v) > 1]
        if dups:
            self.add(d, HINT, f"Grosse Dateien doppelt (inhaltsgleich): {len(dups)} Gruppen",
                     "\n".join(" = ".join(v) for v in dups))
        self.add(d, HINT if big else OK, f"Dateien über 1 MB: {len(big)}" if big else "Keine Datei über 1 MB",
                 "\n".join(f"{s / 1e6:.1f} MB {f}" for s, f in big[:10]))
        total = sum(os.path.getsize(self.path(f)) for f in self.files if os.path.exists(self.path(f)))
        self.facts["size"] = total
        self.add(d, OK, f"{len(self.files)} Dateien, {total / 1e6:.1f} MB im Arbeitsstand")
        gi = self.read(".gitignore")
        missing = [p for p in ("build/", ".gradle/", "local.properties", "*.jks", "xcuserdata/", "DerivedData/", "__pycache__/") if p not in gi]
        self.add(d, HINT if missing else OK, "Typische Einträge fehlen in .gitignore" if missing else ".gitignore deckt Build, Schlüssel und IDE ab",
                 ", ".join(missing))
        odd = [f for f in self.files if re.search(r"(^|/)(\.DS_Store|Thumbs\.db|.*\.orig|.*\.rej|.*~|.*\.bak|.*\.tmp)$", f)]
        self.add(d, FIND if odd else OK, f"Übrig gebliebene Dateien: {len(odd)}" if odd else "Keine .orig/.bak/.DS_Store im Repo", "\n".join(odd[:10]))
        if os.path.exists(self.path("android/build")) and not any(f.startswith("android/build/") for f in self.files):
            pass
        exe = [f for f in self.files if f.endswith((".sh",)) and not os.access(self.path(f), os.X_OK)]
        if exe:
            self.add(d, HINT, f"Shell-Skripte ohne Ausführrecht: {len(exe)}", "\n".join(exe))
        crlf = [f for f in self.text_files({".kt", ".swift", ".py", ".sh", ".yml", ".md"}) if "\r\n" in self.read(f)]
        self.add(d, HINT if crlf else OK, f"Dateien mit Windows-Zeilenenden: {len(crlf)}" if crlf else "Einheitliche Zeilenenden (LF)", "\n".join(crlf[:10]))
        last = self.git("log", "-1", "--format=%h %cd", "--date=format:%d.%m.%Y %H:%M").strip()
        n = self.git("rev-list", "--count", "HEAD").strip()
        self.facts["head"], self.facts["commits"] = last, n

    def workflows(self):
        return [f for f in self.files if f.startswith(".github/workflows/") and f.endswith((".yml", ".yaml"))]

    # ---------- Bericht ----------
    def run(self):
        for fn in (self.security, self.privacy, self.docs, self.tests, self.cicd, self.quality, self.deps, self.l10n, self.hygiene):
            try:
                fn()
            except Exception as e:  # ein Fehler im Werkzeug darf den Bericht nicht verhindern
                self.add(fn.__name__, HINT, f"Prüfung abgebrochen: {type(e).__name__}", str(e))
        return self

    def scores(self):
        out = {}
        for dim, rows in self.results.items():
            c = Counter(r[0] for r in rows)
            out[dim] = (max(0, 100 - 25 * c[FIND] - 5 * c[HINT]), c[OK], c[HINT], c[FIND])
        return out

    def markdown(self, extra=""):
        now = dt.datetime.now().strftime("%d.%m.%Y %H:%M")
        sc = self.scores()
        total = sum(v[0] for v in sc.values()) // max(1, len(sc))
        L = [f"# Audit {os.path.basename(self.repo)}", "",
             f"Stand `{self.facts.get('head', '?')}` · {self.facts.get('commits', '?')} Commits · erstellt {now} · Werkzeug `repo_audit.py`", "",
             f"**Gesamtnote: {total} / 100**", "",
             "| Dimension | Note | OK | Hinweise | Befunde |", "|---|---:|---:|---:|---:|"]
        for dim, (s, o, h, f) in sc.items():
            L.append(f"| {dim} | {s} | {o} | {h} | {f} |")
        L += ["", "Note je Dimension: 100 − 25 je Befund − 5 je Hinweis.", ""]
        if extra:
            L += [extra.rstrip(), ""]
        icon = {OK: "✅", HINT: "⚠️", FIND: "❌"}
        for dim, rows in self.results.items():
            L += [f"## {dim}", ""]
            for st, title, det in sorted(rows, key=lambda r: {FIND: 0, HINT: 1, OK: 2}[r[0]]):
                L.append(f"- {icon[st]} **{st}** – {title}")
                if det:
                    for line in det.splitlines():
                        L.append(f"  - {line}" if not line.startswith("- ") else f"  {line}")
            L.append("")
        return "\n".join(L)

    @staticmethod
    def plain(md):
        """Markdown ohne Zeichen; Tabellen werden zu Blöcken je Zeile, umbrochen auf 100 Zeichen."""
        import textwrap
        out, head = [], None
        for line in md.splitlines():
            line = re.sub(r"[*`]", "", line)
            if line.startswith("#"):
                t = re.sub(r"^#+\s*", "", line)
                out += [t.upper(), "-" * len(t)]
            elif line.startswith("|"):
                cells = [c.strip() for c in line.strip("|").split("|")]
                if set("".join(cells)) <= set("-: "):
                    continue
                if head is None:
                    head = cells
                    continue
                first = f"{cells[0]}. [{cells[1]}] " if len(cells) > 1 else ""
                out += textwrap.wrap(first + (cells[2] if len(cells) > 2 else ""), 100, subsequent_indent="   ")
                for h, c in list(zip(head, cells))[3:]:
                    out += textwrap.wrap(f"{h}: {c}", 100, initial_indent="   ", subsequent_indent="   ")
                out.append("")
            else:
                head = None if not line.strip() else head
                out += textwrap.wrap(line, 100, subsequent_indent="   ") or [""]
        return "\n".join(out).rstrip()

    def text(self, extra=""):
        now = dt.datetime.now().strftime("%d.%m.%Y %H:%M")
        sc = self.scores()
        total = sum(v[0] for v in sc.values()) // max(1, len(sc))
        L = [f"AUDIT {os.path.basename(self.repo).upper()}", "=" * 72,
             f"Stand {self.facts.get('head', '?')} | {self.facts.get('commits', '?')} Commits | erstellt {now}", "",
             f"GESAMTNOTE: {total} / 100", "",
             f"{'Dimension':<20}{'Note':>6}{'OK':>6}{'Hinw.':>7}{'Bef.':>6}", "-" * 45]
        for dim, (s, o, h, f) in sc.items():
            L.append(f"{dim:<20}{s:>6}{o:>6}{h:>7}{f:>6}")
        L += [""]
        if extra:
            L += [self.plain(extra), ""]
        for dim, rows in self.results.items():
            L += ["", dim.upper(), "-" * len(dim)]
            for st, title, det in sorted(rows, key=lambda r: {FIND: 0, HINT: 1, OK: 2}[r[0]]):
                L.append(f"[{st:<7}] {title}")
                for line in det.splitlines():
                    L.append(f"            {line.lstrip('- ')}")
        return "\n".join(L) + "\n"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("repo", nargs="?", default=str(Path(__file__).resolve().parents[2]))
    ap.add_argument("--out", default="audit")
    ap.add_argument("--github")
    ap.add_argument("--no-run", action="store_true")
    ap.add_argument("--extra", help="Markdown-Datei mit Befunden von Hand, wird in den Bericht übernommen")
    ap.add_argument("--notes", help="JSON {Teil des Titels: Einordnung} – hängt eine Bewertung an passende Prüfungen")
    a = ap.parse_args()
    if a.notes:
        Audit.notes = json.load(open(a.notes, encoding="utf-8"))
    au = Audit(a.repo, a.github, not a.no_run).run()
    extra = open(a.extra, encoding="utf-8").read() if a.extra else ""
    os.makedirs(a.out, exist_ok=True)
    md, txt = os.path.join(a.out, "audit-bericht.md"), os.path.join(a.out, "audit-bericht.txt")
    with open(md, "w", encoding="utf-8") as h:
        h.write(au.markdown(extra))
    with open(txt, "w", encoding="utf-8") as h:
        h.write(au.text(extra))
    sc = au.scores()
    print(f"Gesamtnote {sum(v[0] for v in sc.values()) // max(1, len(sc))} / 100 → {md}, {txt}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
