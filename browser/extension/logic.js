// Pure decisions for the AJDM extension, shared by the background worker and the Node tests.

export const HOST = "io.ajdm.host";

export const DEFAULTS = Object.freeze({
  capture: true,       // Hand browser downloads to AJDM.
  minSizeMiB: 1,       // Smaller downloads stay in the browser (unknown sizes are captured).
  button: true,        // Show the "Download with AJDM" button on pages with videos.
  excluded: [],        // Sites whose downloads stay in the browser.
});

const MEDIA_SITES = ["youtube.com", "youtu.be", "tiktok.com", "facebook.com", "fb.watch", "instagram.com",
  "x.com", "twitter.com", "vimeo.com", "dailymotion.com"];

function host(url) {
  try { return new URL(url).hostname.toLowerCase(); } catch { return ""; }
}

function matchesSite(hostname, site) {
  const s = String(site).trim().toLowerCase().replace(/^\*\./, "").replace(/^https?:\/\//, "").replace(/\/.*$/, "");
  return s.length > 0 && (hostname === s || hostname.endsWith("." + s));
}

/** A page on a supported video site: offered to AJDM's quality picker rather than downloaded as a file. */
export function isMediaPage(url) {
  const h = host(url);
  return MEDIA_SITES.some((site) => matchesSite(h, site));
}

export function isWebLink(url) {
  return typeof url === "string" && /^https?:\/\//i.test(url) && url.length <= 8192;
}

/** The last path component of a browser-chosen file name (Windows or POSIX separators). */
export function basename(filename) {
  if (!filename) return undefined;
  const leaf = String(filename).split(/[\\/]/).pop();
  return leaf ? leaf : undefined;
}

/** Whether a browser download should be handed to AJDM under the user's settings. */
export function shouldCapture(item, settings, ownId) {
  const s = { ...DEFAULTS, ...settings };
  if (!s.capture || !item) return false;
  if (item.byExtensionId && item.byExtensionId === ownId) return false;
  const url = item.finalUrl || item.url;
  if (!isWebLink(url)) return false;                     // blob:, data:, file: and the like stay in the browser.
  const h = host(url);
  if ((s.excluded || []).some((site) => matchesSite(h, site))) return false;
  const size = Number.isFinite(item.fileSize) && item.fileSize > 0 ? item.fileSize
    : Number.isFinite(item.totalBytes) && item.totalBytes > 0 ? item.totalBytes : -1;
  return size < 0 || size >= s.minSizeMiB * 1024 * 1024;
}

/** The message for a link the user chose to download with AJDM. */
export function offerFor(url, { referer, filename, media } = {}) {
  const message = { type: "offer", url, media: media ?? isMediaPage(url) };
  if (isWebLink(referer)) message.referer = referer;
  const name = basename(filename);
  if (name) message.filename = name;
  return message;
}
