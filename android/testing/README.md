# Test-Schlüssel

`debug.keystore` signiert nur die Testversionen (Debug-Builds). Er ist absichtlich öffentlich:
So sind Handy- und Uhr-App gleich signiert (Pflicht für den Wearable Data Layer), und neue
Testversionen lassen sich über alte installieren. Store-Versionen werden mit einem eigenen,
geheimen Schlüssel signiert, der nicht ins Repository gehört.

## Kennung der Testversion

Testversionen (Debug-Builds) von Handy und Uhr haben die Kennung `com.phasewake.app.test`
(`applicationIdSuffix ".test"`), die Store-Version bleibt `com.phasewake.app`. Handy und Uhr tragen
dieselbe, weil der Data Layer sie verlangt: Die Test-App des Handys spricht nur mit der Test-App der Uhr.
So lässt sich die Testversion neben der Store-Version installieren.

Wechsel von einer älteren Testversion (sie hatte noch die Kennung der Store-Version): auf Handy und Uhr
einmal deinstallieren, vorher die Daten mit «Sichern» als `.pwbak` ablegen und in der neuen Testversion
wiederherstellen.
