# iOS + watchOS

| Ordner | Inhalt |
|---|---|
| `PhaseWakeCore/` | Swift-Paket mit der Phasenerkennung, identisch zu `android/core` |

iPhone- und Watch-App folgen und binden `PhaseWakeCore` ein.

## Tests

```sh
cd ios/PhaseWakeCore
swift test
```

Die Tests lesen dieselben Testnächte wie Android aus `shared/testdata/`.
