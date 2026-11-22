# Store-Texte

Texte für Google Play (`metadata/android/<sprache>/`) und den App Store (`metadata/ios/<sprache>/`) im Ordneraufbau von fastlane (`supply` bzw. `deliver`). Quelle sind `de-DE` und `en-US`; die übrigen Sprachen sind daraus übersetzt, mit denselben Begriffen wie in der App.

Grenzen (Zeichen): Play – Titel 30, Kurzbeschreibung 80, Beschreibung 4000. App Store – Name 30, Untertitel 30, Werbetext 170, Beschreibung 4000, Suchbegriffe 100 (hier höchstens 100 Bytes, kommagetrennt).

Der App Store bietet nicht alle Sprachen der App an; dort fehlen z. B. Albanisch, Bosnisch und Serbisch. Ob Google Play jede Sprache anbietet (z. B. Irisch `ga`), zeigt sich beim Hochladen.

**Neuigkeiten** (Play, je Veröffentlichung, höchstens 500 Zeichen): `metadata/android/<sprache>/changelogs/default.txt`, gepflegt in `de-DE` und `en-US`. Vor jeder Veröffentlichung nachführen, gleich wie `CHANGELOG.md`. Der App Store verlangt «Neuigkeiten» (`release_notes.txt`) erst ab dem ersten Update.
