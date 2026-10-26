import { DEFAULTS } from "./logic.js";
const api = globalThis.browser ?? globalThis.chrome;
const $ = (id) => document.getElementById(id);
const status = (text) => { $("status").textContent = text; };

const saved = { ...DEFAULTS, ...(await api.storage.local.get(Object.keys(DEFAULTS))) };
$("capture").checked = saved.capture; $("button").checked = saved.button;
$("minSizeMiB").value = saved.minSizeMiB; $("excluded").value = (saved.excluded || []).join("\n");

$("save").addEventListener("click", async () => {
  const minSize = Math.max(0, Math.min(100000, Number($("minSizeMiB").value) || 0));
  await api.storage.local.set({
    capture: $("capture").checked, button: $("button").checked, minSizeMiB: minSize,
    excluded: $("excluded").value.split("\n").map((s) => s.trim()).filter(Boolean).slice(0, 500),
  });
  status("Saved.");
});
$("test").addEventListener("click", () => {
  status("Testing…");
  api.runtime.sendMessage({ type: "ajdm-ping" }, (reply) => status(reply && reply.ok ? "AJDM is connected." : "AJDM is not reachable: " + (reply?.error ?? "no reply")));
});
