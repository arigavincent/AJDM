// Pure decisions for the AJDM extension, shared by the background worker and the Node tests.

export const HOST = "io.ajdm.host";

export const DEFAULTS = Object.freeze({
  capture: true,       // Hand browser downloads to AJDM.
  minSizeMiB: 1,       // Smaller downloads stay in the browser (unknown sizes are captured).
  button: true,        // Show the "Download this video" button on pages with videos.
  excluded: [],        // Sites whose downloads stay in the browser.
});

const MEDIA_SITES = ["youtube.com", "youtu.be", "tiktok.com", "facebook.com", "fb.watch", "instagram.com",
  "x.com", "twitter.com", "vimeo.com", "dailymotion.com"];
/** Hosts whose media responses are pieces of a site player that AJDM reads from the page instead. */
const PLAYER_PIECES = ["googlevideo.com", "fbcdn.net", "cdninstagram.com", "tiktokcdn.com", "tiktokcdn-us.com", "twimg.com", "vimeocdn.com"];

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

/** The size the browser already knows for a download, or -1. */
export function knownSize(item) {
  if (Number.isFinite(item?.fileSize) && item.fileSize > 0) return item.fileSize;
  if (Number.isFinite(item?.totalBytes) && item.totalBytes > 0) return item.totalBytes;
  return -1;
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
  const size = knownSize(item);
  return size < 0 || size >= s.minSizeMiB * 1024 * 1024;
}

/** The message for a link the user chose to download with AJDM. */
export function offerFor(url, { referer, filename, media, sizeBytes, formatId, audioId } = {}) {
  const message = { type: "offer", url, media: media ?? isMediaPage(url) };
  if (isWebLink(referer)) message.referer = referer;
  const name = basename(filename);
  if (name) message.filename = name;
  if (Number.isFinite(sizeBytes) && sizeBytes > 0) message.sizeBytes = sizeBytes;
  if (message.media && typeof formatId === "string" && /^[A-Za-z0-9._+-]{1,128}$/.test(formatId)) {
    message.formatId = formatId;
    if (typeof audioId === "string" && /^[A-Za-z0-9._+-]{1,128}$/.test(audioId)) message.audioId = audioId;
  }
  return message;
}

function header(headers, name) {
  const found = (headers || []).find((h) => h.name && h.name.toLowerCase() === name);
  return found ? String(found.value || "") : "";
}

/**
 * A media response worth listing under "Download this video", like IDM's stream detection: an HLS or DASH
 * playlist, or a whole audio/video file of at least 1 MiB. Pieces of a site player (YouTube, Facebook, TikTok…),
 * stream segments and partial (range) replies are left out. Returns {url, kind, size, type} or null.
 */
export function detectStream(details) {
  if (!details || !isWebLink(details.url) || details.tabId === undefined || details.tabId < 0) return null;
  if (details.statusCode && details.statusCode !== 200 && details.statusCode !== 206) return null;
  const h = host(details.url);
  if (PLAYER_PIECES.some((site) => matchesSite(h, site))) return null;
  const type = header(details.responseHeaders, "content-type").split(";")[0].trim().toLowerCase();
  const path = (() => { try { return new URL(details.url).pathname.toLowerCase(); } catch { return ""; } })();
  if (type.includes("mpegurl") || path.endsWith(".m3u8")) return { url: details.url, kind: "hls", size: -1, type };
  if (type === "application/dash+xml" || path.endsWith(".mpd")) return { url: details.url, kind: "dash", size: -1, type };
  if (!type.startsWith("video/") && !type.startsWith("audio/")) return null;
  if (/\.(ts|m4s|aac|m4f|cmfv|cmfa)$/.test(path) || /\/(seg|segment|frag|fragment|chunk)[-_]?\d/.test(path)) return null;
  // Players fetch media in ranges (206): the whole file's size is the total in Content-Range.
  const range = /\/(\d+)\s*$/.exec(header(details.responseHeaders, "content-range"));
  const size = Number.parseInt(range ? range[1] : header(details.responseHeaders, "content-length"), 10);
  if (!Number.isFinite(size) || size < 1024 * 1024) return null;
  return { url: details.url, kind: type.startsWith("audio/") ? "audio" : "video", size, type };
}

/** A readable menu label for a detected stream. */
export function streamLabel(stream) {
  const leaf = basename(decodeURIComponent((() => { try { return new URL(stream.url).pathname; } catch { return ""; } })())) || "media";
  const kind = { hls: "HLS stream", dash: "DASH stream", audio: "Audio", video: "Video" }[stream.kind] || "Media";
  return `${kind} · ${leaf.length > 48 ? leaf.slice(0, 45) + "…" : leaf}`;
}

/** "245.3 MB", "900 KB", like the desktop window. */
export function sizeText(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) return "";
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1048576).toFixed(1)} MB`;
  return `${(bytes / 1073741824).toFixed(2)} GB`;
}
