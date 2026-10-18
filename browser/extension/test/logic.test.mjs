import { test } from "node:test";
import assert from "node:assert/strict";
import { shouldCapture, isMediaPage, basename, offerFor, knownSize, detectStream, streamLabel, sizeText, DEFAULTS } from "../logic.js";

const MiB = 1024 * 1024;

test("captures web downloads at or above the minimum size and unknown sizes", () => {
  assert.equal(shouldCapture({ url: "https://cdn.example/debian.iso", fileSize: 600 * MiB }, {}), true);
  assert.equal(shouldCapture({ url: "https://cdn.example/a.zip", fileSize: -1, totalBytes: 0 }, {}), true);
  assert.equal(shouldCapture({ url: "https://cdn.example/small.pdf", fileSize: 200 * 1024 }, {}), false);
  assert.equal(shouldCapture({ url: "https://cdn.example/small.pdf", fileSize: 200 * 1024 }, { minSizeMiB: 0 }), true);
});

test("never captures blob, data or file links, excluded sites, its own downloads, or when off", () => {
  for (const url of ["blob:https://site/abc", "data:text/plain,hi", "file:///etc/passwd", "ftp://host/a.zip"]) {
    assert.equal(shouldCapture({ url, fileSize: 10 * MiB }, {}), false, url);
  }
  assert.equal(shouldCapture({ url: "https://files.bank.example/x.pdf", fileSize: 9 * MiB }, { excluded: ["bank.example"] }), false);
  assert.equal(shouldCapture({ url: "https://notbank.example/x.pdf", fileSize: 9 * MiB }, { excluded: ["bank.example"] }), true);
  assert.equal(shouldCapture({ url: "https://cdn.example/a.iso", fileSize: 9 * MiB, byExtensionId: "me" }, {}, "me"), false);
  assert.equal(shouldCapture({ url: "https://cdn.example/a.iso", fileSize: 9 * MiB }, { capture: false }), false);
  assert.equal(shouldCapture({ url: "https://cdn.example/redirect", finalUrl: "blob:x", fileSize: 9 * MiB }, {}), false);
});

test("offers carry the browser's size and a chosen quality, and only safe values", () => {
  assert.equal(isMediaPage("https://www.youtube.com/watch?v=1"), true);
  assert.equal(isMediaPage("https://youtube.com.evil.example/x"), false);
  assert.equal(basename("C:\\Users\\a\\Downloads\\movie.mp4"), "movie.mp4");
  assert.equal(basename(""), undefined);
  assert.equal(knownSize({ fileSize: -1, totalBytes: 5 * MiB }), 5 * MiB);
  assert.deepEqual(offerFor("https://cdn.example/a.zip", { referer: "javascript:alert(1)", filename: "/tmp/a.zip", sizeBytes: 3 * MiB }),
    { type: "offer", url: "https://cdn.example/a.zip", media: false, filename: "a.zip", sizeBytes: 3 * MiB });
  assert.deepEqual(offerFor("https://youtu.be/x", { formatId: "137", audioId: "140" }),
    { type: "offer", url: "https://youtu.be/x", media: true, formatId: "137", audioId: "140" });
  assert.equal(offerFor("https://youtu.be/x", { formatId: "137; rm -rf" }).formatId, undefined);
  assert.equal(offerFor("https://cdn.example/a.zip", { formatId: "137" }).formatId, undefined, "Files never carry a format");
  assert.equal(DEFAULTS.capture, true);
});

const headers = (type, length) => [{ name: "Content-Type", value: type }, ...(length ? [{ name: "content-length", value: String(length) }] : [])];

test("stream detection lists playlists and whole media files, not player pieces or segments", () => {
  const base = { tabId: 3, statusCode: 200 };
  assert.deepEqual(detectStream({ ...base, url: "https://cdn.example/live/master.m3u8", responseHeaders: headers("application/vnd.apple.mpegurl") }),
    { url: "https://cdn.example/live/master.m3u8", kind: "hls", size: -1, type: "application/vnd.apple.mpegurl" });
  assert.equal(detectStream({ ...base, url: "https://cdn.example/v/manifest.mpd", responseHeaders: headers("application/dash+xml") }).kind, "dash");
  assert.equal(detectStream({ ...base, url: "https://cdn.example/films/clip.mp4", responseHeaders: headers("video/mp4", 80 * MiB) }).size, 80 * MiB);
  assert.equal(detectStream({ ...base, url: "https://cdn.example/a/song.mp3", responseHeaders: headers("audio/mpeg", 6 * MiB) }).kind, "audio");
  assert.equal(detectStream({ ...base, url: "https://cdn.example/films/tiny.mp4", responseHeaders: headers("video/mp4", 200 * 1024) }), null, "Previews are skipped");
  assert.equal(detectStream({ ...base, url: "https://cdn.example/live/seg-00012.ts", responseHeaders: headers("video/mp2t", 2 * MiB) }), null, "Segments are skipped");
  assert.equal(detectStream({ ...base, statusCode: 206, url: "https://cdn.example/films/clip.mp4", responseHeaders: headers("video/mp4", 80 * MiB) }).size, 80 * MiB, "Chrome reports whole media files as 206 without a range");
  assert.equal(detectStream({ ...base, statusCode: 206, url: "https://cdn.example/films/clip.mp4",
    responseHeaders: [...headers("video/mp4", 65536), { name: "Content-Range", value: "bytes 0-65535/83886080" }] }).size, 80 * MiB, "A player's range names the whole file");
  assert.equal(detectStream({ ...base, url: "https://rr3---sn-x.googlevideo.com/videoplayback?id=1", responseHeaders: headers("video/mp4", 9 * MiB) }), null, "YouTube pieces are read from the page");
  assert.equal(detectStream({ ...base, url: "https://cdn.example/page.html", responseHeaders: headers("text/html", 9 * MiB) }), null);
  assert.equal(detectStream({ tabId: -1, statusCode: 200, url: "https://cdn.example/a.m3u8", responseHeaders: headers("application/x-mpegurl") }), null, "Background requests have no tab");
  assert.equal(streamLabel({ url: "https://cdn.example/films/My%20Clip.mp4", kind: "video" }), "Video · My Clip.mp4");
  assert.equal(sizeText(80 * MiB), "80.0 MB"); assert.equal(sizeText(-1), "");
});
