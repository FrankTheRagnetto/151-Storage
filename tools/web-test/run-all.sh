#!/bin/bash
cd "$(dirname "$0")/../.."
export PW_CORE="${PW_CORE:?set PW_CORE to the playwright-core folder of a local install, e.g. node_modules/playwright-core}"
export CHROME="${CHROME:-$(ls -d "$HOME"/.cache/ms-playwright/chromium-*/chrome-linux64/chrome 2>/dev/null | tail -1)}"
# i campioni non stanno in git: si preparano qui la prima volta (servono gs, Pillow e la rete)
[ -f tools/web-test/out/text.pdf ] || bash tools/web-test/make-fixtures.sh >/dev/null || exit 1
[ -n "$(ls -A tools/samples-real 2>/dev/null)" ] || bash tools/web-test/fetch-real-samples.sh || exit 1
python3 tools/web-test/serve.py 8151 >/tmp/vw-serve.log 2>&1 &
SP=$!
sleep 1
rc=0
for t in run run-v3 run-v5; do
  node tools/web-test/$t.mjs > /tmp/vw-$t.log 2>&1; r=$?
  echo "$t exit=$r pass=$(grep -c '^PASS' /tmp/vw-$t.log) fail=$(grep -c '^FAIL' /tmp/vw-$t.log)"
  [ $r -ne 0 ] && rc=1
done
kill $SP
exit $rc
