#!/bin/bash
# Runner unico di 151 Storage. 1) le pagine dei documenti (le tre suite ereditate dal 151 Viewer, Chromium);
# 2) il cloud, i caricamenti e le anteprime del cloud sull'emulatore (serve l'AVD acceso con l'APK installato). Non-zero se una fallisce
# o se manca l'emulatore (una prova saltata non è verde).
cd "$(dirname "$0")/.."
rc=0
bash tools/web-test/run-all.sh || rc=1
if "${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb" get-state >/dev/null 2>&1; then
  bash tools/emu-test/run-storage.sh | grep -E "^(PASS|FAIL)"; [ ${PIPESTATUS[0]} -eq 0 ] || rc=1
  bash tools/emu-test/run-comfort.sh | grep -E "^(PASS|FAIL)"; [ ${PIPESTATUS[0]} -eq 0 ] || rc=1
  bash tools/emu-test/run-thumbs.sh | grep -E "^(PASS|FAIL)"; [ ${PIPESTATUS[0]} -eq 0 ] || rc=1
  bash tools/emu-test/run-random.sh | grep -E "^(PASS|FAIL)"; [ ${PIPESTATUS[0]} -eq 0 ] || rc=1
else
  echo "FAIL emulatore spento: la prova del cloud non è girata"; rc=1
fi
exit $rc
