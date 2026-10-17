import { test } from "node:test";
import assert from "node:assert/strict";

// A minimal browser API: records calls and lets each test choose the native host's reply.
function fakeBrowser(reply) {
  const calls = [], listeners = {};
  const event = (name) => ({ addListener: (fn) => { listeners[name] = fn; } });
  globalThis.chrome = {
    runtime: { id: "ajdm-test", onInstalled: event("installed"), onStartup: event("startup"), onMessage: event("message"),
      sendNativeMessage: async (host, message) => { calls.push(["native", host, message]); if (reply instanceof Error) throw reply; return reply; } },
    storage: { local: { get: async () => ({}) } },
    contextMenus: { removeAll: (done) => done(), create: (menu) => calls.push(["menu", menu.id]), onClicked: event("clicked") },
    downloads: { onCreated: event("created"),
      pause: async (id) => calls.push(["pause", id]), resume: async (id) => calls.push(["resume", id]),
      cancel: async (id) => calls.push(["cancel", id]), erase: async (q) => calls.push(["erase", q.id]) },
  };
  return { calls, listeners };
}
let generation = 0;
async function load(reply) {
  const fake = fakeBrowser(reply);
  await import(`../background.js?run=${generation++}`);
  return fake;
}
const MiB = 1024 * 1024;

test("an accepted download is paused, offered, then cancelled in the browser", async () => {
  const { calls, listeners } = await load({ ok: true });
  await listeners.created({ id: 7, url: "https://cdn.example/debian.iso", fileSize: 700 * MiB, referrer: "https://www.debian.org/", filename: "/home/a/debian.iso" });
  assert.deepEqual(calls.map((c) => c[0]), ["pause", "native", "cancel", "erase"]);
  assert.deepEqual(calls[1][2], { type: "offer", url: "https://cdn.example/debian.iso", media: false, referer: "https://www.debian.org/", filename: "debian.iso" });
});

test("when AJDM is not reachable the browser download resumes (fail-open)", async () => {
  const { calls, listeners } = await load(new Error("Specified native messaging host not found."));
  await listeners.created({ id: 8, url: "https://cdn.example/a.zip", fileSize: 50 * MiB });
  assert.deepEqual(calls.map((c) => c[0]), ["pause", "native", "resume"]);
  const refused = await load({ ok: false, error: "AJDM is not running" });
  await refused.listeners.created({ id: 9, url: "https://cdn.example/b.zip", fileSize: 50 * MiB });
  assert.deepEqual(refused.calls.map((c) => c[0]), ["pause", "native", "resume"]);
});

test("small downloads are left alone and menus send links or video pages", async () => {
  const { calls, listeners } = await load({ ok: true });
  await listeners.created({ id: 10, url: "https://cdn.example/tiny.txt", fileSize: 10 });
  assert.equal(calls.length, 0);
  listeners.installed();
  assert.deepEqual(calls.map((c) => c[1]), ["ajdm-link", "ajdm-media", "ajdm-page"]);
  calls.length = 0;
  await listeners.clicked({ menuItemId: "ajdm-link", linkUrl: "https://cdn.example/a.zip", pageUrl: "https://site.example/" });
  await listeners.clicked({ menuItemId: "ajdm-media", srcUrl: "blob:https://www.youtube.com/abc", pageUrl: "https://www.youtube.com/watch?v=1" });
  await listeners.clicked({ menuItemId: "ajdm-media", srcUrl: "https://cdn.example/clip.mp4", pageUrl: "https://news.example/story" });
  await listeners.clicked({ menuItemId: "ajdm-page", pageUrl: "https://vimeo.com/123" });
  assert.deepEqual(calls.map((c) => [c[2].url, c[2].media]), [
    ["https://cdn.example/a.zip", false], ["https://www.youtube.com/watch?v=1", true],
    ["https://cdn.example/clip.mp4", false], ["https://vimeo.com/123", true]]);
});
