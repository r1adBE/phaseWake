#!/usr/bin/env python3
"""Prüft den Store-Build (Release) von Handy und Uhr auf Testreste (Audit PW-02).

Aufruf aus android/ nach `assembleRelease`: release_check.py
Gelesen wird das zusammengeführte Release-Manifest jeder App. Fehler, wenn
- die App als debuggable markiert ist,
- eine Vorschau- oder Test-Activity enthalten ist (z. B. PreviewActivity aus ui-tooling),
- die App Netzzugriff anfordert (INTERNET oder ACCESS_NETWORK_STATE, z. B. über eine Bibliothek eingeschleppt),
- die Kennung nicht com.phasewake.app ist (nur Testversionen tragen «.test», Audit M-04),
- die exportierten Komponenten oder die angeforderten Rechte von der Positivliste abweichen (Audit 17.11., SEC-02),
- für eine App kein Release-Manifest gefunden wird (Build fehlte).
"""
import glob
import sys
import xml.etree.ElementTree as ET

NS = "{http://schemas.android.com/apk/res/android}"
APPS = ("app", "wear")
FORBIDDEN = ("PreviewActivity", "ComponentActivity", "TestActivity")
# Phase Wake arbeitet ohne Netz; die Manifeste entfernen diese Rechte mit tools:node="remove".
NETWORK = ("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")
# Kennung der Store-Version von Handy und Uhr (Data Layer: beide gleich).
APP_ID = "com.phasewake.app"
COMPONENTS = ("activity", "activity-alias", "service", "receiver", "provider")

# Positivliste (Stand 17.11.2026, aus dem zusammengeführten Release-Manifest). Bringt eine Bibliothek eine neue
# exportierte Komponente oder ein neues Recht mit, wird die CI rot: prüfen, ob es nötig ist (sonst mit
# tools:node="remove" entfernen), dann hier und in der Play-Erklärung (docs/PLAY.md) nachführen.
# Exportiert: (Element, Name, nötiges Recht des Aufrufers oder "").
EXPORTED = {
    "app": {
        ("activity", "com.phasewake.app.ui.MainActivity", ""),
        ("activity", "com.phasewake.app.health.HealthRationaleActivity", ""),
        ("activity-alias", "com.phasewake.app.ViewPermissionUsageActivity",
         "android.permission.START_VIEW_PERMISSION_USAGE"),
        ("service", "com.phasewake.app.sync.PhoneListenerService", ""),
        ("receiver", "com.phasewake.app.ring.BackupAlarm$SystemReceiver", ""),
        ("service", "androidx.health.platform.client.impl.sdkservice.HealthDataSdkService", ""),
        ("receiver", "androidx.profileinstaller.ProfileInstallReceiver", "android.permission.DUMP"),
    },
    "wear": {
        ("activity", "com.phasewake.app.wear.ui.MainActivity", ""),
        ("service", "com.phasewake.app.wear.sync.WatchListenerService", ""),
        ("service", "com.phasewake.app.wear.tile.NextAlarmTileService",
         "com.google.android.wearable.permission.BIND_TILE_PROVIDER"),
        ("service", "com.phasewake.app.wear.complication.NextAlarmComplicationService",
         "com.google.android.wearable.permission.BIND_COMPLICATION_PROVIDER"),
        ("receiver", "com.phasewake.app.wear.schedule.SystemEventReceiver", ""),
        ("service", "androidx.health.services.client.VersionApiService",
         "com.google.android.wearable.healthservices.permission.PASSIVE_DATA_BINDING"),
        ("receiver", "androidx.profileinstaller.ProfileInstallReceiver", "android.permission.DUMP"),
    },
}
# Rechte: (Name, maxSdkVersion oder ""). Das Recht für dynamische Empfänger legt AndroidX selbst an.
_COMMON = {
    ("android.permission.POST_NOTIFICATIONS", ""),
    ("android.permission.USE_FULL_SCREEN_INTENT", ""),
    ("android.permission.VIBRATE", ""),
    ("android.permission.WAKE_LOCK", ""),
    ("android.permission.RECEIVE_BOOT_COMPLETED", ""),
    ("android.permission.FOREGROUND_SERVICE", ""),
    ("android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED", ""),
    ("android.permission.USE_EXACT_ALARM", ""),
    ("android.permission.SCHEDULE_EXACT_ALARM", "32"),
    (f"{APP_ID}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", ""),
}
PERMISSIONS = {
    "app": _COMMON | {
        ("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK", ""),
        ("android.permission.RECORD_AUDIO", ""),
        ("android.permission.FOREGROUND_SERVICE_MICROPHONE", ""),
        ("android.permission.health.READ_SLEEP", ""),
        ("android.permission.USE_BIOMETRIC", ""),
        ("android.permission.BLUETOOTH_CONNECT", ""),
        ("android.permission.BLUETOOTH", "30"),
    },
    "wear": _COMMON | {
        ("android.permission.BODY_SENSORS", "35"),
        ("android.permission.BODY_SENSORS_BACKGROUND", "35"),
        ("android.permission.health.READ_HEART_RATE", ""),
        ("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND", ""),
        ("android.permission.ACTIVITY_RECOGNITION", ""),
        ("android.permission.FOREGROUND_SERVICE_HEALTH", ""),
    },
}


def manifest(app):
    hits = [p for p in glob.glob(f"{app}/build/intermediates/merged_manifest*/**/AndroidManifest.xml", recursive=True)
            if "release" in p.lower()]
    return sorted(hits)[0] if hits else None


def exported(application):
    if application is None:
        return set()
    return {(c.tag, c.get(NS + "name", ""), c.get(NS + "permission", ""))
            for c in application if c.tag in COMPONENTS and c.get(NS + "exported") == "true"}


def permissions(root):
    return {(p.get(NS + "name", ""), p.get(NS + "maxSdkVersion", ""))
            for tag in ("uses-permission", "uses-permission-sdk-23") for p in root.iter(tag)}


def compare(app, what, actual, expected):
    """Abweichung von der Positivliste in beide Richtungen (neu oder weggefallen)."""
    errors = [f"{app}: {what} neu, nicht in der Positivliste: {' '.join(x for x in item if x)}"
              for item in sorted(actual - expected)]
    errors += [f"{app}: {what} laut Positivliste erwartet, fehlt: {' '.join(x for x in item if x)}"
               for item in sorted(expected - actual)]
    return errors


def check(app, path=None):
    path = path or manifest(app)
    if path is None:
        return [f"{app}: kein Release-Manifest gefunden (assembleRelease gelaufen?)"]
    errors = []
    root = ET.parse(path).getroot()
    if root.get("package") != APP_ID:
        errors.append(f"{app}: Release-Kennung {root.get('package')} statt {APP_ID}")
    requested = permissions(root)
    for name, _ in sorted(requested):
        if name in NETWORK:
            errors.append(f"{app}: Release fordert {name} an")
    application = root.find("application")
    if application is not None and application.get(NS + "debuggable") == "true":
        errors.append(f"{app}: Release-Build ist debuggable")
    for activity in application.findall("activity") if application is not None else []:
        name = activity.get(NS + "name", "")
        if name.rsplit(".", 1)[-1] in FORBIDDEN:
            errors.append(f"{app}: Release enthält {name}")
    errors += compare(app, "exportiert", exported(application), EXPORTED[app])
    errors += compare(app, "Recht", {p for p in requested if p[0] not in NETWORK}, PERMISSIONS[app])
    return errors


def main():
    errors = [e for app in APPS for e in check(app)]
    for e in errors:
        print(f"::error title=Store-Build::{e}")
    if not errors:
        print(f"::notice title=Store-Build::Handy und Uhr: Release als {APP_ID}, ohne debuggable, ohne Vorschau-Activity, "
              "ohne Netzzugriff, exportierte Komponenten und Rechte wie in der Positivliste")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
