# Test-Schlüssel

`debug.keystore` signiert nur die Testversionen (Debug-Builds). Er ist absichtlich öffentlich:
So sind Handy- und Uhr-App gleich signiert (Pflicht für den Wearable Data Layer), und neue
Testversionen lassen sich über alte installieren. Store-Versionen werden mit einem eigenen,
geheimen Schlüssel signiert, der nicht ins Repository gehört.
