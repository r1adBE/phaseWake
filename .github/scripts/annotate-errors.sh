#!/usr/bin/env bash
# Fasst Fehler und Warnungen aus einem Build-Log zu einer Annotation zusammen,
# damit sie ohne Log-Download über die Checks-API lesbar sind.
set -euo pipefail
log=$1
lines=$(grep -E "^e: |^w: |error:|warning:|What went wrong|^> |FAILED|failed|XCTAssert|Dependency|requires|[0-9]+\. " -A2 "$log" \
  | grep -vE "^--$|Run with|Get more help|^> Run|^> Get|^> Task|^> Configure" | head -80 || true)
[ -z "$lines" ] && lines=$(tail -40 "$log")
msg=$(printf '%s' "$lines" | sed -e 's/%/%25/g' -e 's/\r//g' | awk 'BEGIN{ORS="%0A"}{print}')
echo "::error title=Build-Log::$msg"
