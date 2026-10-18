import { test } from "node:test";
import assert from "node:assert/strict";

// A minimal browser: records calls; `reply(message)` decides what the native helper answers (or throws to simulate
// a missing helper). The helper is reached through one long-lived port, like a real browser.
function fakeBrowser(reply, { portBroken = false } = {}) {
  const calls = [], listeners = {};
  const event = (name) => ({ addListener: (fn, filter, extra) => { listeners[name] = fn; listeners[name + ":filter"] = filter; listeners[name + ":extra"] = extra; } });
  const port = { listeners: {}, onMessage: { addListener: (fn) => { port.listeners.message = fn; } },
    onDisconnect: { addListener: (fn) => { port.listeners.disconnect = fn; } },
    postMessage: (message) => {
      calls.push(["native", message]);
      queueMicrotask(() => {
        let answer;
        try { answer = reply(message); } catch { port.listeners.disconnect(); return; }
        port.listeners.message({ ...answer, id: message.id });
      });
    } };
  globalThis.chrome = {
    runtime: { id: "ajdm-test", onInstalled: event("installed"), onStartup: event("startup"), onMessage: event("message"),
      connectNative: () => { calls.push(["connect"]); if (portBroken) throw new Error("no host"); return port; },
      sendNativeMessage: async (host, message) => { calls.push(["once", message]); return reply(message); } },
    storage: { local: { get: async () => ({}) } },
    contextMenus: { removeAll: (done) => done(), create: (menu) => calls.push(["menu", menu.id]), onClicked: event("clicked") },
    downloads: { onCreated: event("created"),
      pause: async (id) => calls.push(["pause", id]), resume: async (id) => calls.push(["resume", id]),
      cancel: async (id) => calls.push(["cancel", id]), erase: async (q) => calls.push(["erase", q.id]) },
    webRequest: { onHeadersReceived: event("headers") },
    tabs: { onRemoved: event("removed") },
  };
  return { calls, listeners };
}
let generation = 0;
async function load(reply, options) {
  const fake = fakeBrowser(reply, options);
  await import(`../background.js?run=${generation++}`);
  return fake;
}
const MiB = 1024 * 1024;
const natives = (calls) => calls.filter((c) => c[0] === "native").map((c) => c[1]);
const ask = (listeners, message, tab = { id: 3, url: "https://www.youtube.com/watch?v=1" }) =>
  new Promise((resolve) => { if (!listeners.message(message, { tab }, resolve)) resolve(undefined); });

test("an accepted download is paused, offered with its size over one connection, then cancelled in the browser", async () => {
  const { calls, listeners } = await load(() => ({ ok: true }));
  await listeners.created({ id: 7, url: "https://cdn.example/debian.iso", fileSize: 700 * MiB, referrer: "https://www.debian.org/", filename: "/home/a/debian.iso" });
  await listeners.created({ id: 8, url: "https://cdn.example/b.iso", fileSize: 50 * MiB });
  assert.deepEqual(calls.filter((c) => c[0] === "connect").length, 1, "One helper serves every click");
  const first = natives(calls)[0];
  assert.deepEqual({ ...first, id: undefined }, { type: "offer", url: "https://cdn.example/debian.iso", media: false, referer: "https://www.debian.org/",
    filename: "debian.iso", sizeBytes: 700 * MiB, id: undefined });
  assert.deepEqual(calls.filter((c) => ["pause", "cancel", "erase"].includes(c[0])).map((c) => c[0]), ["pause", "cancel", "erase", "pause", "cancel", "erase"]);
});

test("when AJDM or its helper is missing the browser download resumes (fail-open)", async () => {
  const missing = await load(() => { throw new Error("Specified native messaging host not found."); });
  await missing.listeners.created({ id: 8, url: "https://cdn.example/a.zip", fileSize: 50 * MiB });
  assert.deepEqual(missing.calls.filter((c) => ["pause", "resume", "cancel"].includes(c[0])).map((c) => c[0]), ["pause", "resume"]);
  const refused = await load(() => ({ ok: false, error: "AJDM is not running" }));
  await refused.listeners.created({ id: 9, url: "https://cdn.example/b.zip", fileSize: 50 * MiB });
  assert.deepEqual(refused.calls.filter((c) => ["pause", "resume", "cancel"].includes(c[0])).map((c) => c[0]), ["pause", "resume"]);
  const noPort = await load(() => ({ ok: true }), { portBroken: true });
  await noPort.listeners.created({ id: 10, url: "https://cdn.example/c.zip", fileSize: 50 * MiB });
  assert.equal(noPort.calls.filter((c) => c[0] === "once").length, 1, "Without a port, a one-off message is used");
  assert.ok(noPort.calls.some((c) => c[0] === "cancel"));
});

test("the video button lists AJDM's qualities and the page's streams, and a choice opens AJDM with that quality", async () => {
  const { calls, listeners } = await load((m) => m.type === "qualities"
    ? { ok: true, title: "A video", qualities: [{ label: "MP4  1080p", formatId: "137", audioId: "140", sizeBytes: 9 * MiB }] } : { ok: true });
  assert.deepEqual(listeners["headers:extra"], ["responseHeaders"]);
  listeners.headers({ tabId: 3, type: "media", statusCode: 200, url: "https://cdn.example/clip.mp4",
    responseHeaders: [{ name: "content-type", value: "video/mp4" }, { name: "content-length", value: String(20 * MiB) }] });
  const qualities = await ask(listeners, { type: "ajdm-qualities" });
  assert.equal(qualities.qualities[0].formatId, "137");
  assert.deepEqual(natives(calls).find((m) => m.type === "qualities").url, "https://www.youtube.com/watch?v=1");
  const sniffed = await ask(listeners, { type: "ajdm-streams" });
  assert.deepEqual(sniffed.streams.map((s) => s.url), ["https://cdn.example/clip.mp4"]);
  await ask(listeners, { type: "ajdm-media", formatId: "137", audioId: "140" });
  const chosen = natives(calls).filter((m) => m.type === "offer").at(-1);
  assert.equal(chosen.formatId, "137"); assert.equal(chosen.audioId, "140"); assert.equal(chosen.media, true);
  await ask(listeners, { type: "ajdm-stream", url: "https://cdn.example/clip.mp4", kind: "video", size: 20 * MiB });
  const stream = natives(calls).filter((m) => m.type === "offer").at(-1);
  assert.equal(stream.media, false); assert.equal(stream.sizeBytes, 20 * MiB);
  listeners.message({ type: "ajdm-prefetch" }, { tab: { id: 3, url: "https://www.youtube.com/watch?v=1" } }, () => {});
  await new Promise((r) => setTimeout(r, 0));
  assert.ok(natives(calls).some((m) => m.type === "prefetch"), "Video pages are analysed in the background");
  listeners.headers({ tabId: 3, type: "main_frame", url: "https://www.youtube.com/watch?v=2" });
  assert.deepEqual((await ask(listeners, { type: "ajdm-streams" })).streams, [], "A new page starts a fresh list");
  const plain = await ask(listeners, { type: "ajdm-qualities" }, { id: 4, url: "https://news.example/story" });
  assert.deepEqual(plain.qualities, [], "Ordinary pages are not sent to the extractor");
});

test("menus send links or video pages", async () => {
  const { calls, listeners } = await load(() => ({ ok: true }));
  listeners.installed();
  assert.deepEqual(calls.filter((c) => c[0] === "menu").map((c) => c[1]), ["ajdm-link", "ajdm-media", "ajdm-page"]);
  await listeners.clicked({ menuItemId: "ajdm-link", linkUrl: "https://cdn.example/a.zip", pageUrl: "https://site.example/" });
  await listeners.clicked({ menuItemId: "ajdm-media", srcUrl: "blob:https://www.youtube.com/abc", pageUrl: "https://www.youtube.com/watch?v=1" });
  await listeners.clicked({ menuItemId: "ajdm-media", srcUrl: "https://cdn.example/clip.mp4", pageUrl: "https://news.example/story" });
  await listeners.clicked({ menuItemId: "ajdm-page", pageUrl: "https://vimeo.com/123" });
  assert.deepEqual(natives(calls).map((m) => [m.url, m.media]), [
    ["https://cdn.example/a.zip", false], ["https://www.youtube.com/watch?v=1", true],
    ["https://cdn.example/clip.mp4", false], ["https://vimeo.com/123", true]]);
});
