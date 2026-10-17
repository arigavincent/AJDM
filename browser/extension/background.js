// AJDM extension background: context menus, download capture and the video button, all through the native host.
import { HOST, DEFAULTS, isWebLink, isMediaPage, shouldCapture, offerFor } from "./logic.js";

const api = globalThis.browser ?? globalThis.chrome;

async function settings() {
  return { ...DEFAULTS, ...(await api.storage.local.get(Object.keys(DEFAULTS))) };
}

/** Sends one message to AJDM; resolves to the reply, or a failure when AJDM or its host is not available. */
async function send(message) {
  try { return (await api.runtime.sendNativeMessage(HOST, message)) ?? { ok: false, error: "No reply" }; }
  catch (error) { return { ok: false, error: "AJDM is not installed or not running" }; }
}

async function offer(url, details) {
  if (!isWebLink(url)) return { ok: false, error: "Only web links can be downloaded" };
  return send(offerFor(url, details));
}

function menus() {
  api.contextMenus.removeAll(() => {
    api.contextMenus.create({ id: "ajdm-link", title: "Download link with AJDM", contexts: ["link"] });
    api.contextMenus.create({ id: "ajdm-media", title: "Download this video or audio with AJDM", contexts: ["video", "audio"] });
    api.contextMenus.create({ id: "ajdm-page", title: "Download this page's video with AJDM", contexts: ["page"] });
  });
}
api.runtime.onInstalled.addListener(menus);
api.runtime.onStartup?.addListener(menus);

api.contextMenus.onClicked.addListener(async (info) => {
  if (info.menuItemId === "ajdm-link") await offer(info.linkUrl, { referer: info.pageUrl });
  else if (info.menuItemId === "ajdm-media") {
    // A direct media file downloads as is; site players (blob: sources) go through the page's quality picker.
    if (isWebLink(info.srcUrl) && !isMediaPage(info.pageUrl)) await offer(info.srcUrl, { referer: info.pageUrl, media: false });
    else await offer(info.pageUrl, { media: true });
  } else if (info.menuItemId === "ajdm-page") await offer(info.pageUrl, { media: true });
});

// Capture: pause the browser's download, offer it to AJDM, and cancel it only once AJDM accepted it.
// Anything else (AJDM closed, host missing, refusal) resumes the browser's download: capture fails open.
api.downloads.onCreated.addListener(async (item) => {
  if (!shouldCapture(item, await settings(), api.runtime.id)) return;
  try { await api.downloads.pause(item.id); } catch { return; }
  const reply = await offer(item.finalUrl || item.url, { referer: item.referrer, filename: item.filename, media: false });
  if (reply.ok) {
    try { await api.downloads.cancel(item.id); } catch {}
    try { await api.downloads.erase({ id: item.id }); } catch {}
  } else {
    try { await api.downloads.resume(item.id); } catch {}
  }
});

api.runtime.onMessage.addListener((message, sender, respond) => {
  if (message?.type === "ajdm-media" && sender.tab && isWebLink(sender.tab.url)) {
    offer(sender.tab.url, { media: true }).then(respond);
    return true;
  }
  if (message?.type === "ajdm-ping") { send({ type: "ping" }).then(respond); return true; }
  return false;
});
