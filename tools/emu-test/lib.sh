# helper per le prove sull'emulatore: source tools/emu-test/lib.sh
ADB=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
OUT=${OUT:-$(dirname "${BASH_SOURCE[0]}")/out}
mkdir -p "$OUT"
PKG=com.the151universe.storage
shot() { $ADB exec-out screencap -p > "$OUT/$1.png"; echo "shot $OUT/$1.png"; }
clearlog() { $ADB logcat -c; $ADB logcat -b crash -c 2>/dev/null; }
crashes() { $ADB logcat -d -b crash; $ADB logcat -d '*:E' | grep -E "AndroidRuntime|FATAL|151Storage|151Viewer|$PKG" | head -60; }
alive() { $ADB shell pidof $PKG >/dev/null && echo "processo vivo" || echo "processo MORTO"; }
top_activity() { $ADB shell dumpsys activity activities | grep -E "topResumedActivity|ResumedActivity:" | head -2; }
# view <nome-shot> <uri> <mime> [extra am args]
view() { local n=$1 u=$2 t=$3; shift 3; clearlog; $ADB shell am start -W -a android.intent.action.VIEW -d "$u" -t "$t" --grant-read-uri-permission -n $PKG/.ViewerActivity "$@" | grep -E "Status|Error|Activity"; sleep ${WAIT:-4}; shot "$n"; alive; top_activity; crashes; }
# ui_dump -> /tmp/ui.xml ; tap_text "<testo esatto>" [indice]
ui_dump() { $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $ADB shell cat /sdcard/ui.xml > /tmp/ui.xml; }
ui_texts() { ui_dump; grep -oE '(text|content-desc)="[^"]+"' /tmp/ui.xml | sort -u | tr '\n' ' '; echo; }
tap_text() { ui_dump; python3 - "$1" "${2:-0}" <<'PY' > /tmp/tap.txt
import re,sys
x=open('/tmp/ui.xml').read(); want=sys.argv[1]; idx=int(sys.argv[2])
hits=[]
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    t=re.search(r' text="([^"]*)"',n).group(1); d=re.search(r'content-desc="([^"]*)"',n).group(1)
    if t==want or d==want:
        b=list(map(int,re.findall(r'\d+',re.search(r'bounds="([^"]+)"',n).group(1))))
        hits.append(((b[0]+b[2])//2,(b[1]+b[3])//2))
print(*(hits[idx] if len(hits)>idx else ('NONE','NONE')))
PY
  read X Y < /tmp/tap.txt; if [ "$X" = NONE ]; then echo "tap_text: '$1' non trovato"; return 1; fi; $ADB shell input tap $X $Y; echo "tap '$1' @ $X,$Y"; }
# un giro interrotto (o l'emulatore riacceso) può lasciare la modalità aereo: ogni prova parte con la rete accesa
$ADB shell cmd connectivity airplane-mode disable >/dev/null 2>&1
