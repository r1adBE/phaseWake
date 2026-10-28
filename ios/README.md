# iOS + watchOS

| Ordner | Inhalt |
|---|---|
| `PhaseWakeCore/` | Swift-Paket mit der Phasenerkennung, identisch zu `android/core` |
| `PhaseWake/` | iPhone-App: Wecker, Verlauf, Einstellungen, Zweitwecker (AlarmKit ab iOS 26) |
| `PhaseWakeWatch/` | Apple-Watch-App: Messung im Weckfenster über die Smart-Alarm-Sitzung |
| `Shared/` | Farben, Schrift, Logo, Texte – für beide Apps |
| `Resources/` | Schrift Manrope |

`Localizable.xcstrings` wird aus den Android-Texten erzeugt, nicht von Hand bearbeiten:

```sh
python3 .github/scripts/gen-xcstrings.py
```

## Tests

```sh
cd ios/PhaseWakeCore
swift test
```

Die Tests lesen dieselben Testnächte wie Android aus `shared/testdata/`.
