# Phase Wake – Bildschirmfotos

Android-Handy und Galaxy Watch, gerendert aus dem Stand `3b3c39a`. Beispieldaten, keine echten Nächte. Die Apple-Version folgt. Das Widget «Nächster Wecker» fehlt: Es ist mit RemoteViews gebaut, die sich nicht als Compose-Vorschau rendern lassen.

## Handy

### Startseite

Nächster Wecker mit Weckfenster, Nacht-Check, «Dein Morgen» mit Bewegungskurve und der Frage nach dem Befinden, alle Wecker auf einen Blick. Im dunklen Modus ist die ganze Seite Nachthimmel, im hellen nur der Kopf.

<p>
<img src="app/HomeDark.png" width="280" alt="Startseite dunkel">
<img src="app/HomeLight.png" width="280" alt="Startseite hell">
</p>

### Nacht-Check

Stimmt etwas nicht, zeigt die Karte, was fehlt – mit Knopf direkt zur richtigen Einstellung.

<img src="app/HomeCheck.png" width="280" alt="Nacht-Check mit offenen Punkten">

### Ohne Wecker

Noch kein Wecker gestellt: ein Knopf für den ersten, darunter die letzte Nacht.

<p>
<img src="app/HomeEmptyDark.png" width="280" alt="Startseite ohne Wecker, dunkel">
<img src="app/HomeEmptyLight.png" width="280" alt="Startseite ohne Wecker, hell">
</p>

### Wecker einstellen

Weckzeit oder Schlafdauer, Weckfenster von 10 bis 30 Minuten, Wochentage, Weckart für Uhr und Handy und Sonnenaufgang.

<p>
<img src="app/EditorDark.png" width="280" alt="Wecker einstellen, dunkel">
<img src="app/EditorLight.png" width="280" alt="Wecker einstellen, hell">
</p>

### Verlauf

Trends der letzten 7 Tage (Schlafdauer, Regelmässigkeit, Befinden) und jede Nacht mit Bewegungskurve, Weckgrund, Datenqualität und – mit Health Connect – der Schlafphase beim Wecken.

<p>
<img src="app/HistoryDark.png" width="280" alt="Verlauf, dunkel">
<img src="app/HistoryLight.png" width="280" alt="Verlauf, hell">
</p>

### Einrichtung

Beim ersten Start in fünf Schritten, jederzeit überspringbar: Willkommen, Uhr verbinden, Rechte, erster Wecker, Probewecker – dann «Fertig».

<p>
<img src="app/OnboardingWelcomeLight.png" width="200" alt="Einrichtung: Willkommen, hell">
<img src="app/OnboardingWelcomeDark.png" width="200" alt="Einrichtung: Willkommen, dunkel">
<img src="app/OnboardingWatch.png" width="200" alt="Einrichtung: Uhr verbinden">
</p>
<p>
<img src="app/OnboardingRightsLight.png" width="200" alt="Einrichtung: Rechte, hell">
<img src="app/OnboardingRightsDark.png" width="200" alt="Einrichtung: Rechte, dunkel">
<img src="app/OnboardingAlarm.png" width="200" alt="Einrichtung: erster Wecker">
</p>
<p>
<img src="app/OnboardingTest.png" width="200" alt="Einrichtung: Probewecker">
<img src="app/OnboardingDone.png" width="200" alt="Einrichtung: fertig">
</p>

### Powernap

Eine Zeile auf der Startseite: Dauer von 10 bis 90 Minuten, geweckt im leichten Schlaf der letzten Minuten. Läuft ein Powernap, steht seine Weckzeit da.

<p>
<img src="app/NapLight.png" width="200" alt="Powernap, hell">
<img src="app/NapDark.png" width="200" alt="Powernap, dunkel">
<img src="app/NapRunningLight.png" width="200" alt="Powernap läuft, hell">
<img src="app/NapRunningDark.png" width="200" alt="Powernap läuft, dunkel">
</p>

### Überspringen und Feiertage

«Nächstes Mal überspringen» im Menü jedes Weckers, mit «Rückgängig». Wecker mit «An Feiertagen nicht wecken» lassen den Feiertag aus; der Kopf nennt ihn.

<p>
<img src="app/HomeSkipLight.png" width="280" alt="Übersprungener Wecker und Feiertag, hell">
<img src="app/HomeSkipDark.png" width="280" alt="Übersprungener Wecker und Feiertag, dunkel">
</p>

### Probewecker

«In 2 Minuten testen» weckt Uhr und Handy einmal zur Probe. Danach zeigt das Ergebnis je Schritt ✓, ✗ oder «nicht geprüft» – bei ✗ mit der passenden Lösung.

<p>
<img src="app/TestResultMixed.png" width="280" alt="Ergebnis des Probeweckers: Stopp kam nicht an">
<img src="app/TestResultPhone.png" width="280" alt="Ergebnis des Probeweckers: Handy hat nicht mitgeklingelt">
</p>

### Einstellungen

Hell, Dunkel oder System, Export, Verlauf löschen, Health Connect, Unterstützen.

<p>
<img src="app/SettingsDark.png" width="280" alt="Einstellungen, dunkel">
<img src="app/SettingsLight.png" width="280" alt="Einstellungen, hell">
</p>

Feiertage nach Land und Region, dazu «Wecker testen»:

<p>
<img src="app/SettingsHolidaysLight.png" width="280" alt="Einstellungen: Feiertage, hell">
<img src="app/SettingsHolidaysDark.png" width="280" alt="Einstellungen: Feiertage, dunkel">
</p>

### Sicherung

Verschlüsselt mit eigenem Passwort; vor dem Wiederherstellen zeigt die App, was die Sicherung enthält, und fragt nach der Gerätesperre.

<p>
<img src="app/BackupCreateLight.png" width="200" alt="Sicherung: Passwort wählen, hell">
<img src="app/BackupCreateDark.png" width="200" alt="Sicherung: Passwörter verschieden, dunkel">
<img src="app/RestorePreviewLight.png" width="200" alt="Wiederherstellen: Vorschau, hell">
<img src="app/RestorePreviewDark.png" width="200" alt="Wiederherstellen: Vorschau, dunkel">
</p>

### Diagnose

Versteckt (7× auf die Versionszeile): Zustand von Handy und Uhr und die Ereignisse beider Geräte, nur lokal; Teilen nur auf Wunsch.

<p>
<img src="app/DiagnosticsLight.png" width="280" alt="Diagnose, hell">
<img src="app/DiagnosticsDark.png" width="280" alt="Diagnose, dunkel, Uhr ohne Antwort">
</p>

### Wecken auf dem Handy

Der Zweitwecker klingelt zusammen mit der Uhr. Stoppen oder 5 Minuten schlummern, höchstens 3×.

<img src="app/RingPhone.png" width="280" alt="Wecken auf dem Handy">

### Sonnenaufgang (Option)

Der Bildschirm leuchtet erst schwach dunkelorange und wird bis zu hellem, warmem Licht bei voller Helligkeit – ab Weckfenster oder beim Wecken. Die Schrift wechselt mit dem Licht von hell auf dunkel.

<p>
<img src="app/SunriseEarly.png" width="200" alt="Sonnenaufgang am Anfang">
<img src="app/SunriseMiddle.png" width="200" alt="Sonnenaufgang in der Mitte">
<img src="app/RingSunrise.png" width="200" alt="Wecken mit Sonnenaufgang">
</p>

## Uhr

### Startseite

Nächster Wecker mit dem Weckfenster als Ring. Ohne Wecker verweist die Uhr aufs Handy.

<p>
<img src="wear/WatchHome.png" width="220" alt="Uhr Startseite">
<img src="wear/WatchEmpty.png" width="220" alt="Uhr ohne Wecker">
</p>

### Gute Nacht

Für Wecker mit Schlafdauer: Ab «Gute Nacht» erkennt die Uhr, wann du einschläfst, und weckt nach der gewählten Dauer.

<img src="wear/WatchGoodNight.png" width="220" alt="Gute Nacht auf der Uhr">

### Wecken und Schlummern

Sanfte Vibration im leichten Schlaf, in drei Stufen stärker. Beim Schlummern zeigt die Uhr, bis wann.

<p>
<img src="wear/WatchRing.png" width="220" alt="Wecken auf der Uhr">
<img src="wear/WatchSnoozed.png" width="220" alt="Schlummern auf der Uhr">
</p>

### Powernap

Chip unter dem Weckfenster, danach die Dauer wählen. Läuft ein Powernap, beendet ein Tippen ihn.

<p>
<img src="wear/WatchNap.png" width="220" alt="Uhr: Powernap">
<img src="wear/WatchNapPicker.png" width="220" alt="Uhr: Dauer des Powernaps">
<img src="wear/WatchNapRunning.png" width="220" alt="Uhr: Powernap läuft">
</p>

### Überspringen

Im Editor der Uhr: «Nächstes Mal überspringen» mit dem Datum des ausgelassenen Termins.

<img src="wear/WatchEditorSkip.png" width="220" alt="Uhr: Wecker überspringen">

### Diagnose

Nächster Wecker, Version und die Ereignisse der Uhr, neueste zuerst.

<img src="wear/WatchDiagnostics.png" width="220" alt="Uhr: Diagnose">
