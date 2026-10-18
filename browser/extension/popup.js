// The toolbar popup, like IDM's: connection status, the capture switch and this page's videos.
import { sizeText } from "./logic.js";
const api = globalThis.browser ?? globalThis.chrome;
const $ = (id) => document.getElementById(id);
const ask = (message) => new Promise((resolve) => api.runtime.sendMessage(message, (reply) => resolve(reply ?? { ok: false })));

const { capture = true } = await api.storage.local.get({ capture: true });
$("capture").checked = capture;
$("captureNote").textContent = capture ? "Downloads you start open in AJDM." : "Downloads stay in the browser.";
$("capture").addEventListener("change", async () => {
  await api.storage.local.set({ capture: $("capture").checked });
  $("captureNote").textContent = $("capture").checked ? "Downloads you start open in AJDM." : "Downloads stay in the browser.";
});
$("settings").addEventListener("click", () => api.runtime.openOptionsPage());
$("privacy").addEventListener("click", () => api.tabs.create({ url: "https://github.com/arigavincent/AJDM/blob/main/docs/PRIVACY_POLICY.md" }));

const ping = await ask({ type: "ajdm-ping" });
$("status").textContent = ping.ok ? "Connected" : "Not running";
$("status").className = "pill " + (ping.ok ? "on" : "off");

const [tab] = await api.tabs.query({ active: true, currentWindow: true });
const where = { tabId: tab?.id, url: tab?.url };
const media = $("media");
function row(label, detail, message) {
  const div = document.createElement("div"); div.className = "item";
  const b = document.createElement("b"); b.textContent = label; const span = document.createElement("span"); span.textContent = detail;
  div.append(b, span);
  div.addEventListener("click", async () => {
    span.textContent = "Sending…";
    const reply = await ask({ ...message, ...where });
    span.textContent = reply.ok ? "Opened in AJDM" : "AJDM is not running";
    if (reply.ok) setTimeout(() => window.close(), 700);
  });
  return div;
}
const [qualities, streams] = await Promise.all([ask({ type: "ajdm-qualities", ...where }), ask({ type: "ajdm-streams", ...where })]);
const rows = [];
for (const q of qualities.qualities ?? []) rows.push(row(q.label, (q.approximate ? "≈ " : "") + sizeText(q.sizeBytes), { type: "ajdm-media", formatId: q.formatId, audioId: q.audioId }));
for (const s of streams.streams ?? []) {
  const name = (() => { try { return decodeURIComponent(new URL(s.url).pathname.split("/").pop()) || "media"; } catch { return "media"; } })();
  const kind = { hls: "HLS stream", dash: "DASH stream", audio: "Audio", video: "Video" }[s.kind] || "Media";
  rows.push(row(`${kind} · ${name}`, sizeText(s.size), { type: "ajdm-stream", url: s.url, kind: s.kind, size: s.size }));
}
media.replaceChildren(...(rows.length ? rows : [Object.assign(document.createElement("div"), { className: "note",
  textContent: !ping.ok ? "Start AJDM to download from this page." : "No video found on this page yet. Play the video, then open this again." })]));
