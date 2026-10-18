// AJDM extension background: download capture, context menus, the in-page video menu and stream detection.
import { HOST, DEFAULTS, isWebLink, isMediaPage, shouldCapture, offerFor, knownSize, detectStream } from "./logic.js";

const api = globalThis.browser ?? globalThis.chrome;
const NOT_RUNNING = "AJDM is not installed or not running";

async function settings() {
  return { ...DEFAULTS, ...(await api.storage.local.get(Object.keys(DEFAULTS))) };
}

// ---- One long-lived connection to AJDM ------------------------------------------------------------------------
// The helper stays running while the browser is open, so a click costs a local round trip instead of starting a
// program each time. Each request carries an id; the reply names it. If the port cannot open, a one-off message is
// tried instead, and failing that the answer says AJDM is not available.
let port = null, nextId = 1;
const waiting = new Map();

function connect() {
  if (port) return port;
  try {
    port = api.runtime.connectNative(HOST);
    port.onMessage.addListener((reply) => {
      const done = waiting.get(reply?.id);
      if (done) { waiting.delete(reply.id); done(reply); }
    });
    port.onDisconnect.addListener(() => {
      port = null;
      for (const done of waiting.values()) done({ ok: false, error: NOT_RUNNING });
      waiting.clear();
    });
  } catch { port = null; }
  return port;
}

async function once(message) {
  try { return (await api.runtime.sendNativeMessage(HOST, message)) ?? { ok: false, error: "No reply" }; }
  catch { return { ok: false, error: NOT_RUNNING }; }
}

/** Sends one request to AJDM; resolves to its reply, or a failure when AJDM or its helper is not available. */
function send(message, timeoutMs = 8000) {
  const open = connect();
  if (!open) return once(message);
  const id = nextId++;
  return new Promise((resolve) => {
    const timer = setTimeout(() => { waiting.delete(id); resolve({ ok: false, error: "AJDM did not answer" }); }, timeoutMs);
    waiting.set(id, (reply) => { clearTimeout(timer); resolve(reply); });
    try { open.postMessage({ ...message, id }); }
    catch { waiting.delete(id); clearTimeout(timer); once(message).then(resolve); }
  });
}

async function offer(url, details) {
  if (!isWebLink(url)) return { ok: false, error: "Only web links can be downloaded" };
  return send(offerFor(url, details));
}

// ---- Context menus ----------------------------------------------------------------------------------------------
function menus() {
  api.contextMenus.removeAll(() => {
    api.contextMenus.create({ id: "ajdm-link", title: "Download with AJDM", contexts: ["link"] });
    api.contextMenus.create({ id: "ajdm-media", title: "Download this video or audio with AJDM", contexts: ["video", "audio"] });
    api.contextMenus.create({ id: "ajdm-page", title: "Download this page's video with AJDM", contexts: ["page"] });
  });
}
api.runtime.onInstalled.addListener(menus);
api.runtime.onStartup?.addListener(menus);

api.contextMenus.onClicked.addListener(async (info) => {
  if (info.menuItemId === "ajdm-link") await offer(info.linkUrl, { referer: info.pageUrl });
  else if (info.menuItemId === "ajdm-media") {
    // A direct media file downloads as is; site players (blob: sources) go through the page's quality list.
    if (isWebLink(info.srcUrl) && !isMediaPage(info.pageUrl)) await offer(info.srcUrl, { referer: info.pageUrl, media: false });
    else await offer(info.pageUrl, { media: true });
  } else if (info.menuItemId === "ajdm-page") await offer(info.pageUrl, { media: true });
});

// ---- Download capture -------------------------------------------------------------------------------------------
// Pause the browser's download, offer it to AJDM (with the size the browser knows, so the File Info box shows it at
// once), and cancel it only once AJDM accepted it. Anything else resumes the browser's download: capture fails open.
api.downloads.onCreated.addListener(async (item) => {
  if (!shouldCapture(item, await settings(), api.runtime.id)) return;
  try { await api.downloads.pause(item.id); } catch { return; }
  const reply = await offer(item.finalUrl || item.url, { referer: item.referrer, filename: item.filename, media: false, sizeBytes: knownSize(item) });
  if (reply.ok) {
    try { await api.downloads.cancel(item.id); } catch {}
    try { await api.downloads.erase({ id: item.id }); } catch {}
  } else {
    try { await api.downloads.resume(item.id); } catch {}
  }
});

// ---- Streams the page plays (IDM's video detection) ---------------------------------------------------------------
const streams = new Map();   // tabId -> Map(url -> stream)
function remember(tabId, stream) {
  let list = streams.get(tabId);
  if (!list) { list = new Map(); streams.set(tabId, list); }
  if (list.size >= 40 && !list.has(stream.url)) list.delete(list.keys().next().value);
  list.set(stream.url, stream);
}
api.webRequest?.onHeadersReceived.addListener((details) => {
  if (details.type === "main_frame") { streams.delete(details.tabId); return; }
  const stream = detectStream(details);
  if (stream) remember(details.tabId, stream);
}, { urls: ["http://*/*", "https://*/*"], types: ["main_frame", "media", "xmlhttprequest", "other"] }, ["responseHeaders"]);
api.tabs?.onRemoved?.addListener((tabId) => streams.delete(tabId));

// ---- Messages from the page's "Download this video" button ------------------------------------------------------
api.runtime.onMessage.addListener((message, sender, respond) => {
  const tab = sender.tab;
  const page = tab && isWebLink(tab.url) ? tab.url : null;
  switch (message?.type) {
    case "ajdm-qualities":
      if (!page || !isMediaPage(page)) { respond({ ok: true, qualities: [] }); return false; }
      send({ type: "qualities", url: page }, 120000).then(respond); return true;
    case "ajdm-streams":
      respond({ ok: true, streams: tab ? [...(streams.get(tab.id)?.values() ?? [])] : [] }); return false;
    case "ajdm-prefetch":
      if (page && isMediaPage(page)) send({ type: "prefetch", url: page });
      return false;
    case "ajdm-media":
      if (!page) { respond({ ok: false, error: "This page cannot be downloaded" }); return false; }
      offer(page, { media: true, formatId: message.formatId, audioId: message.audioId }).then(respond); return true;
    case "ajdm-stream":
      if (!isWebLink(message.url)) { respond({ ok: false, error: "Not a web link" }); return false; }
      // HLS/DASH playlists go through AJDM's quality list; whole files open the File Info box.
      offer(message.url, { media: message.kind === "hls" || message.kind === "dash", referer: page, sizeBytes: message.size }).then(respond); return true;
    case "ajdm-ping":
      send({ type: "ping" }).then(respond); return true;
    default:
      return false;
  }
});
