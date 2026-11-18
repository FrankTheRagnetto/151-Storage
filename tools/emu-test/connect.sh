#!/bin/bash
# Collega l'app (già aperta su Cloud, non collegata) a un cloud WebDAV di prova. Uso: connect.sh <url> <utente> <password>
cd "$(dirname "$0")"; source ./lib.sh
hidekb() { $ADB shell dumpsys input_method | grep -q "mInputShown=true" && $ADB shell input keyevent 4; sleep 0.6; }
$ADB shell am start -W -n $PKG/.MainActivity -e place cloud >/dev/null; sleep 4
tap_text "WebDAV" >/dev/null; sleep 1
ui_dump; python3 - <<'PY' > /tmp/f.txt
import re
x=open('/tmp/ui.xml').read()
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    if 'EditText' in n:
        b=list(map(int,re.findall(r'\d+',re.search(r'bounds="([^"]+)"',n).group(1)))); print((b[0]+b[2])//2,(b[1]+b[3])//2)
PY
vals=("$1" "$2" "$3"); i=0
while read -u 3 X Y; do hidekb; $ADB shell input tap $X $Y; sleep 0.8; $ADB shell input keyevent KEYCODE_MOVE_END
  for k in $(seq 1 30); do $ADB shell input keyevent KEYCODE_DEL; done; $ADB shell input text "${vals[$i]}"; sleep 0.5; i=$((i+1)); done 3< /tmp/f.txt
hidekb; tap_text Connect >/dev/null; sleep 5
