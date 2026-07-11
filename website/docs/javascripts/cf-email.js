/* Decode Cloudflare-style obfuscated email addresses client-side.
   Addresses are stored as data-cfemail hex on .__cf_email__ spans so they never
   appear in the raw page source; this renders them readable and makes the
   surrounding link a mailto:. Scoped by class, so it is a harmless no-op on
   every page that has no obfuscated emails. */
(function () {
  function decode(hex) {
    var key = parseInt(hex.substr(0, 2), 16), out = "";
    for (var i = 2; i < hex.length; i += 2) {
      out += String.fromCharCode(parseInt(hex.substr(i, 2), 16) ^ key);
    }
    return out;
  }
  function run() {
    document.querySelectorAll(".__cf_email__").forEach(function (el) {
      var data = el.getAttribute("data-cfemail");
      if (!data) return;
      var email = decode(data);
      el.textContent = email;
      var a = el.closest("a");
      if (a) a.setAttribute("href", "mailto:" + email);
    });
  }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", run);
  } else {
    run();
  }
})();
