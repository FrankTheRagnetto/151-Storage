// SVG mostrato come <img>: niente script, niente risorse esterne. Zoom = zoom nativo della WebView.
(function () {
  var p = new URLSearchParams(location.search);
  if (p.get("theme") === "dark") document.documentElement.classList.add("theme-dark");
  document.getElementById("img").src = (p.get("src") || "/file/none/") + "doc";
  document.getElementById("img").addEventListener("click", function () {
    if (window.Android) window.Android.toggleChrome();
  });
})();
