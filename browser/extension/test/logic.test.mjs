import { test } from "node:test";
import assert from "node:assert/strict";
import { shouldCapture, isMediaPage, basename, offerFor, DEFAULTS } from "../logic.js";

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

test("video sites go to the quality picker and names are reduced to their last part", () => {
  assert.equal(isMediaPage("https://www.youtube.com/watch?v=1"), true);
  assert.equal(isMediaPage("https://m.tiktok.com/@a/video/1"), true);
  assert.equal(isMediaPage("https://youtube.com.evil.example/x"), false);
  assert.equal(basename("C:\\Users\\a\\Downloads\\movie.mp4"), "movie.mp4");
  assert.equal(basename("/home/a/Downloads/file.iso"), "file.iso");
  assert.equal(basename(""), undefined);
  assert.deepEqual(offerFor("https://cdn.example/a.zip", { referer: "javascript:alert(1)", filename: "/tmp/a.zip" }),
    { type: "offer", url: "https://cdn.example/a.zip", media: false, filename: "a.zip" });
  assert.equal(offerFor("https://youtu.be/x").media, true);
  assert.equal(DEFAULTS.capture, true);
});
