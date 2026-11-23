#!/usr/bin/env python3
"""Selbsttest für `repo_audit.py`: ein kleines Repo mit bekannten Fehlern, die gemeldet werden müssen.

Aufruf: `python3 .github/scripts/test_repo_audit.py` (läuft in der CI mit den übrigen Selbsttests).
"""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
import repo_audit  # noqa: E402

PIN = "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1"
MANIFEST = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <application android:allowBackup="{backup}">
    <receiver android:name=".Open" android:exported="true">
      <intent-filter><action android:name="com.example.OPEN" /></intent-filter>
    </receiver>
  </application>
</manifest>
"""


class RepoAuditTest(unittest.TestCase):
    def make(self, files):
        """Neues Git-Repo mit diesen Dateien, eingecheckt."""
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        for name, text in files.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        git = ["git", "-C", str(root), "-c", "user.name=t", "-c", "user.email=t@users.noreply.github.com"]
        subprocess.run(git[:3] + ["init", "-q"], check=True)
        subprocess.run(git + ["add", "-A"], check=True)
        subprocess.run(git + ["commit", "-qm", "t"], check=True)
        return repo_audit.Audit(str(root), run_scripts=False).run()

    def titles(self, audit, dim, status):
        return [t for s, t, _ in audit.results[dim] if s == status]

    def test_flags_unpinned_action_and_missing_permissions(self):
        audit = self.make({".github/workflows/a.yml": "on:\n  push:\njobs:\n  b:\n    steps:\n"
                                                      "      - uses: actions/checkout@v4\n"})
        found = " ".join(self.titles(audit, "Sicherheit", repo_audit.FIND))
        self.assertIn("ohne feste Commit-Kennung: 1", found)
        self.assertIn("ohne Standard-Rechte", found)

    def test_pinned_action_with_permissions_and_timeout_is_ok(self):
        audit = self.make({".github/workflows/a.yml": "on:\n  workflow_dispatch:\npermissions:\n  contents: read\n"
                                                      f"jobs:\n  b:\n    timeout-minutes: 5\n    steps:\n"
                                                      f"      - uses: {PIN}\n"})
        finds = self.titles(audit, "Sicherheit", repo_audit.FIND)
        self.assertEqual([], [t for t in finds if "Commit-Kennung" in t or "Standard-Rechte" in t])
        self.assertIn("Alle Jobs mit Zeitlimit", self.titles(audit, "CI/CD", repo_audit.OK))

    def test_secret_is_found_but_known_test_password_is_not(self):
        audit = self.make({"a.kts": 'storePassword = "android"\n',
                           "b.py": 'api = "ghp_' + "a" * 36 + '"\n'})
        found = [d for s, t, d in audit.results["Sicherheit"] if t.startswith("Mögliche Geheimnisse")]
        self.assertEqual(1, len(found))
        self.assertIn("b.py:1", found[0])
        self.assertNotIn("a.kts", found[0])

    def test_manifest_backup_and_open_receiver(self):
        audit = self.make({"app/src/main/AndroidManifest.xml": MANIFEST.format(backup="true"),
                           "app/src/main/kotlin/Open.kt": "class Open : BroadcastReceiver()\n"})
        finds = " ".join(self.titles(audit, "Sicherheit", repo_audit.FIND))
        hints = " ".join(self.titles(audit, "Sicherheit", repo_audit.HINT))
        self.assertIn("allowBackup", finds)
        self.assertIn("1 exportierte Komponenten ohne Recht", hints)

    def test_receiver_checking_its_action_is_ok(self):
        audit = self.make({"app/src/main/AndroidManifest.xml": MANIFEST.format(backup="false"),
                           "app/src/main/kotlin/Open.kt": "class Open : BroadcastReceiver() {\n"
                                                          "  fun f() { if (intent.action == A) g() }\n}\n"})
        self.assertIn("app/src/main/AndroidManifest.xml: Manifest sauber", self.titles(audit, "Sicherheit", repo_audit.OK))

    def test_score_and_reports(self):
        audit = self.make({"README.md": "# x\n[kaputt](fehlt.md)\n"})
        score = audit.scores()["Dokumentation"][0]
        self.assertLess(score, 100)
        self.assertIn("Kaputte relative Links: 1", audit.markdown())
        self.assertIn("GESAMTNOTE", audit.text())


class WithoutGitTest(unittest.TestCase):
    """Ein entpacktes ZIP ohne .git: Dateien aus dem Ordner, .gitignore beachtet."""

    def test_files_from_folder_respect_gitignore(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        files = {"README.md": "# x\n", "LICENSE": "MIT\n", ".gitignore": "build/\n*.jks\n!keep.jks\n",
                 "app/build/out.txt": "x", "app/a.kt": "class A\n", "k.jks": "x", "keep.jks": "x"}
        for name, text in files.items():
            (root / name).parent.mkdir(parents=True, exist_ok=True)
            (root / name).write_text(text, encoding="utf-8")
        audit = repo_audit.Audit(str(root), run_scripts=False)
        self.assertFalse(audit.has_git)
        self.assertEqual([".gitignore", "LICENSE", "README.md", "app/a.kt", "keep.jks"], audit.files)
        audit.run()
        self.assertIn("README.md vorhanden", [t for s, t, _ in audit.results["Dokumentation"] if s == repo_audit.OK])
        hints = [t for s, t, _ in audit.results["Datenschutz"] if s == repo_audit.HINT]
        self.assertIn("Commit-Adressen und Zuschreibungen nicht prüfbar (kein Git)", hints)
        self.assertIn("Dateiliste: Dateisystem", audit.markdown())


if __name__ == "__main__":
    unittest.main()
