// Shows a "Download with AJDM" button over the largest playing-size video on the page, like IDM's video panel.
(() => {
  const api = globalThis.browser ?? globalThis.chrome;
  if (window.top !== window || !/^https?:$/.test(location.protocol)) return;
  let host, button, hidden = false;

  function create() {
    host = document.createElement("div");
    host.style.cssText = "position:fixed;z-index:2147483647;top:0;left:0;display:none";
    const root = host.attachShadow({ mode: "closed" });
    root.innerHTML = `<style>
      button{font:600 13px system-ui,sans-serif;color:#fff;background:#14a39a;border:2px solid #0f2340;border-radius:18px;
             padding:6px 12px 6px 10px;cursor:pointer;box-shadow:0 2px 8px rgba(0,0,0,.35);display:flex;align-items:center;gap:6px}
      button:hover{background:#0f8a82} span.x{margin-left:6px;opacity:.75}</style>
      <button title="Choose a quality and download with AJDM">⬇ Download with AJDM <span class="x" title="Hide">✕</span></button>`;
    button = root.querySelector("button");
    button.addEventListener("click", (event) => {
      event.preventDefault(); event.stopPropagation();
      if (event.target.classList.contains("x")) { hidden = true; host.style.display = "none"; return; }
      button.disabled = true; button.firstChild.textContent = "⬇ Sending to AJDM… ";
      api.runtime.sendMessage({ type: "ajdm-media" }, (reply) => {
        button.disabled = false;
        button.firstChild.textContent = reply && reply.ok ? "⬇ Opened in AJDM " : "⬇ AJDM is not running ";
        setTimeout(() => { button.firstChild.textContent = "⬇ Download with AJDM "; }, 3000);
      });
    });
    document.documentElement.appendChild(host);
  }

  function largestVideo() {
    let best = null, area = 0;
    for (const video of document.querySelectorAll("video")) {
      const r = video.getBoundingClientRect();
      const a = r.width * r.height;
      if (r.width >= 240 && r.height >= 135 && r.bottom > 0 && r.top < innerHeight && a > area) { best = r; area = a; }
    }
    return best;
  }

  function place() {
    if (hidden) return;
    const rect = largestVideo();
    if (!rect) { if (host) host.style.display = "none"; return; }
    if (!host) create();
    host.style.display = "block";
    host.style.top = Math.max(4, rect.top + 8) + "px";
    host.style.left = Math.max(4, rect.right - 210) + "px";
  }

  api.storage.local.get({ button: true }, (s) => {
    if (!s.button) return;
    setInterval(place, 1000);
    addEventListener("scroll", place, { passive: true });
    addEventListener("resize", place, { passive: true });
  });
})();
