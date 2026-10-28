#!/usr/bin/env python3
"""Erzeugt die Feiertagsdaten für «Feiertage überspringen» (alle Länder und Regionen, offline in der App).

Quelle: Python-Paket «holidays» (MIT). Ausgabe: kompakte JSON-Datei
{"v":1,"from":2026,"to":2036,"countries":{"CH":{"name":"Switzerland","regions":{"BE":"Bern",…},
 "days":{"":[["2026-01-01","New Year's Day"],…],"BE":[[…]]}}}}
Unter "" stehen die landesweiten Feiertage, unter einer Region nur die zusätzlichen.
Aufruf: make-holidays.py AUSGABE.json
"""
import json
import sys

import holidays

YEARS = range(2026, 2037)


def days(cls, subdiv=None):
    try:
        h = cls(years=YEARS, subdiv=subdiv, language="en_US") if subdiv else cls(years=YEARS, language="en_US")
    except Exception:
        h = cls(years=YEARS, subdiv=subdiv) if subdiv else cls(years=YEARS)
    return {d.isoformat(): name for d, name in sorted(h.items())}


def main():
    out = {"v": 1, "from": YEARS.start, "to": YEARS.stop - 1, "countries": {}}
    for code, subdivs in sorted(holidays.list_supported_countries().items()):
        cls = holidays.country_holidays.__globals__.get("holidays") and None
        try:
            base_obj = holidays.country_holidays(code, years=YEARS)
        except Exception as e:
            print("skip", code, e, file=sys.stderr)
            continue
        cls = type(base_obj)
        base = days(cls)
        entry = {"name": getattr(cls, "country", code) if isinstance(getattr(cls, "country", None), str) else code,
                 "regions": {}, "days": {"": [[d, n] for d, n in base.items()]}}
        names = getattr(cls, "subdivisions_aliases", {}) or {}
        alias = {}
        for a, s in names.items():
            alias.setdefault(s, a)
        for s in subdivs:
            try:
                extra = {d: n for d, n in days(cls, s).items() if d not in base}
            except Exception as e:
                print("skip", code, s, e, file=sys.stderr)
                continue
            entry["regions"][s] = alias.get(s, s)
            if extra:
                entry["days"][s] = [[d, n] for d, n in extra.items()]
        out["countries"][code] = entry
    with open(sys.argv[1], "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print(len(out["countries"]), "Länder")


if __name__ == "__main__":
    main()
