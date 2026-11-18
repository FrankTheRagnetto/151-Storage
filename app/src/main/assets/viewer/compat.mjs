// Compatibilità con WebView vecchie (Chrome < 125): pdf.js 6 scrive le dimensioni delle pagine con la
// funzione CSS round(down, X, passo). Siccome X contiene var(), il valore viene accettato al momento
// dell'assegnazione ma scartato al calcolo: la pagina resta alta 0 e il PDF appare BIANCO.
// Visto sull'emulatore Android 15 con la WebView di fabbrica (Chrome 124): 40 pagine, tutte alte 0.
// Qui, SOLO se round() manca, ogni stile inline che lo usa viene riscritto in calc(X) prima del
// disegno (i MutationObserver girano come microtask, prima del layout). Si perde solo
// l'arrotondamento al pixel, non il documento.
function stripRound(css) {
  let out = "", i = 0;
  for (;;) {
    const at = css.indexOf("round(", i);
    if (at < 0) return out + css.slice(i);
    out += css.slice(i, at);
    // trova la parentesi di chiusura e le virgole di primo livello
    let depth = 0, j = at + 5, commas = [];
    for (; j < css.length; j++) {
      const c = css[j];
      if (c === "(") depth++;
      else if (c === ")") { depth--; if (depth === 0) break; }
      else if (c === "," && depth === 1) commas.push(j);
    }
    const args = [];
    let from = at + 6;
    for (const k of commas) { args.push(css.slice(from, k).trim()); from = k + 1; }
    args.push(css.slice(from, j).trim());
    // round(strategia?, valore, passo?) -> calc(valore)
    const strategies = ["nearest", "up", "down", "to-zero"];
    const vals = strategies.includes(args[0]) ? args.slice(1) : args;
    out += "calc(" + stripRound(vals[0] || "0px") + ")";
    i = j + 1;
  }
}

if (typeof CSS !== "undefined" && !CSS.supports("width", "round(down, 10px, 1px)")) {
  const fixEl = el => {
    const s = el.getAttribute && el.getAttribute("style");
    if (s && s.includes("round(")) el.setAttribute("style", stripRound(s));
  };
  new MutationObserver(list => {
    for (const m of list) {
      if (m.type === "attributes") fixEl(m.target);
      else for (const n of m.addedNodes) {
        if (n.nodeType !== 1) continue;
        fixEl(n);
        n.querySelectorAll?.("[style*='round(']").forEach(fixEl);
      }
    }
  }).observe(document.documentElement, { subtree: true, childList: true, attributes: true, attributeFilter: ["style"] });
  globalThis.__cssRoundShim = true;
}
export { stripRound };
