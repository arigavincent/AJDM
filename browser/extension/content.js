// IDM's video panel: a "Download this video" button over the largest playing-size video; clicking it lists the
// qualities (video sites, read by AJDM) and the streams this page played, with sizes. Choosing one hands it to AJDM.
(() => {
  const api = globalThis.browser ?? globalThis.chrome;
  if (window.top !== window || !/^https?:$/.test(location.protocol)) return;
  let host, root, button, panel, hidden = false, prefetched = "", open = false;

  const ask = (message) => new Promise((resolve) => {
    try { api.runtime.sendMessage(message, (reply) => resolve(reply ?? { ok: false, error: "AJDM is not running" })); }
    catch { resolve({ ok: false, error: "AJDM is not running" }); }
  });
  const size = (bytes) => !Number.isFinite(bytes) || bytes < 0 ? "" : bytes < 1048576 ? `${Math.max(1, Math.round(bytes / 1024))} KB`
    : bytes < 1073741824 ? `${(bytes / 1048576).toFixed(1)} MB` : `${(bytes / 1073741824).toFixed(2)} GB`;

  function create() {
    host = document.createElement("div");
    host.style.cssText = "position:fixed;z-index:2147483647;top:0;left:0;display:none";
    root = host.attachShadow({ mode: "closed" });
    root.innerHTML = `<style>
      :host{all:initial}
      .bar{display:flex;align-items:stretch;font:600 12.5px system-ui,"Segoe UI",sans-serif;border-radius:18px;overflow:hidden;
           box-shadow:0 4px 16px rgba(20,18,43,.45)}
      .go{display:flex;align-items:center;gap:8px;padding:6px 12px 6px 7px;color:#fff;cursor:pointer;border:0;font:inherit;
          background:linear-gradient(100deg,#9B6BFF,#5E5BFF 55%,#2F8BEF)}
      .go:hover{background:linear-gradient(100deg,#A97DFF,#6E6BFF 55%,#3C98F5)}
      .x{background:#14122B;color:#B8B5E8;border:0;cursor:pointer;padding:0 10px;font:inherit}
      .x:hover{color:#fff}
      svg{width:20px;height:20px}
      .panel{margin-top:6px;min-width:300px;max-width:390px;max-height:350px;overflow:auto;background:#fff;color:#1B1838;
             border-radius:12px;box-shadow:0 10px 30px rgba(20,18,43,.40);font:12.5px system-ui,"Segoe UI",sans-serif}
      .head{padding:9px 12px;font-weight:700;color:#fff;background:linear-gradient(110deg,#14122B,#221C4E 60%,#1B2A57);
            border-bottom:3px solid #5E5BFF;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
      .item{display:flex;gap:12px;justify-content:space-between;padding:8px 12px;cursor:pointer;border-bottom:1px solid #F0EFF8}
      .item:hover{background:#ECEBFF}
      .item b{font-weight:600}.item span{color:#6B6A86;white-space:nowrap}
      .note{padding:10px 12px;color:#6B6A86}
      .more{padding:8px 12px;color:#5E5BFF;font-weight:600;cursor:pointer}.more:hover{background:#F6F6FC}
    </style>
    <div class="bar"><button class="go" title="Download this video with AJDM">
      <svg viewBox="0 0 512 512"><rect x="16" y="16" width="480" height="480" rx="124" fill="#fff" fill-opacity=".22"/>
        <path d="M256 78 L120 372 M256 78 L392 372" stroke="#fff" stroke-opacity=".35" stroke-width="46" stroke-linecap="round" fill="none"/>
        <path fill="#fff" d="M256 100c-19.9 0-36 16.1-36 36v122h-46c-16.4 0-24.6 19.8-13 31.4l80.6 80.6c7.8 7.8 20.5 7.8 28.3 0l80.6-80.6c11.6-11.6 3.4-31.4-13-31.4h-46V136c0-19.9-16.1-36-36-36z"/>
        <rect x="132" y="394" width="248" height="36" rx="18" fill="#fff"/><path fill="#FFC857" d="M396 82l13 31.5 31.5 13-31.5 13L396 171l-13-31.5-31.5-13 31.5-13z"/></svg>
      <span class="label">Download this video</span></button><button class="x" title="Hide">✕</button></div>
    <div class="panel" hidden></div>`;
    button = root.querySelector(".go"); panel = root.querySelector(".panel");
    button.addEventListener("click", (event) => { event.preventDefault(); event.stopPropagation(); toggle(); });
    root.querySelector(".x").addEventListener("click", (event) => { event.preventDefault(); event.stopPropagation(); hidden = true; host.style.display = "none"; });
    document.addEventListener("click", (event) => { if (open && !event.composedPath().includes(host)) close(); }, true);
    document.documentElement.appendChild(host);
  }

  function close() { open = false; panel.hidden = true; }
  function note(text) { const div = document.createElement("div"); div.className = "note"; div.textContent = text; return div; }
  function item(label, detail, choose) {
    const div = document.createElement("div"); div.className = "item";
    const b = document.createElement("b"); b.textContent = label; const span = document.createElement("span"); span.textContent = detail;
    div.append(b, span); div.addEventListener("click", (event) => { event.stopPropagation(); choose(); }); return div;
  }
  async function hand(message) {
    panel.replaceChildren(note("Sending to AJDM…"));
    const reply = await ask(message);
    if (reply?.ok) { close(); flash("Opened in AJDM"); }
    else panel.replaceChildren(note(reply?.error === "AJDM is not installed or not running" ? "Start AJDM, then try again." : (reply?.error || "AJDM did not answer.")));
  }
  function flash(text) {
    const label = root.querySelector(".label"); label.textContent = text;
    setTimeout(() => { label.textContent = "Download this video"; }, 2500);
  }

  async function toggle() {
    if (open) { close(); return; }
    open = true; panel.hidden = false;
    const head = document.createElement("div"); head.className = "head"; head.textContent = "AJDM";
    panel.replaceChildren(head, note("Getting the qualities…"));
    const [qualities, sniffed] = await Promise.all([ask({ type: "ajdm-qualities" }), ask({ type: "ajdm-streams" })]);
    if (!open) return;
    const rows = [];
    if (qualities?.ok && qualities.title) head.textContent = qualities.title;
    for (const q of qualities?.qualities ?? []) {
      rows.push(item(q.label, (q.approximate ? "≈ " : "") + size(q.sizeBytes), () => hand({ type: "ajdm-media", formatId: q.formatId, audioId: q.audioId })));
    }
    const direct = largestVideoElement()?.currentSrc;
    const found = [...(sniffed?.streams ?? [])];
    if (direct && /^https?:/i.test(direct) && !found.some((s) => s.url === direct)) found.push({ url: direct, kind: "video", size: -1 });
    for (const s of found) {
      const name = (() => { try { return decodeURIComponent(new URL(s.url).pathname.split("/").pop()) || "media"; } catch { return "media"; } })();
      const kind = { hls: "HLS stream", dash: "DASH stream", audio: "Audio", video: "Video" }[s.kind] || "Media";
      rows.push(item(`${kind} · ${name.length > 40 ? name.slice(0, 37) + "…" : name}`, size(s.size), () => hand({ type: "ajdm-stream", url: s.url, kind: s.kind, size: s.size })));
    }
    if (rows.length === 0) {
      panel.replaceChildren(head, note(qualities && !qualities.ok && qualities.error ? (qualities.error === "AJDM is not installed or not running"
        ? "Start AJDM, then try again." : "AJDM could not read this video's qualities.") : "No downloadable video found on this page yet. Play the video, then try again."));
    } else panel.replaceChildren(head, ...rows);
    const more = document.createElement("div"); more.className = "more"; more.textContent = "More options in AJDM…";
    more.addEventListener("click", (event) => { event.stopPropagation(); hand({ type: "ajdm-media" }); });
    panel.append(more);
  }

  function largestVideoElement() {
    let best = null, area = 0;
    for (const video of document.querySelectorAll("video")) {
      const r = video.getBoundingClientRect(), a = r.width * r.height;
      if (r.width >= 240 && r.height >= 135 && r.bottom > 0 && r.top < innerHeight && a > area) { best = video; area = a; }
    }
    return best;
  }

  function place() {
    if (hidden) return;
    const video = largestVideoElement();
    if (!video) { if (host && !open) host.style.display = "none"; return; }
    if (!host) create();
    // On video sites, AJDM starts reading the qualities in the background, so the menu is ready when clicked.
    if (prefetched !== location.href) { prefetched = location.href; ask({ type: "ajdm-prefetch" }); }
    const r = video.getBoundingClientRect();
    host.style.display = "block";
    host.style.top = Math.max(4, r.top + 8) + "px";
    host.style.left = Math.max(4, Math.min(innerWidth - 400, r.right - 214)) + "px";
  }

  api.storage.local.get({ button: true }, (s) => {
    if (!s.button) return;
    setInterval(place, 1000);
    addEventListener("scroll", place, { passive: true });
    addEventListener("resize", place, { passive: true });
  });
})();
