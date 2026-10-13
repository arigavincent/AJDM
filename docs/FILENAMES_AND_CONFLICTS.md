# Filename discovery and destination policies

AJDM's probe returns a safe `filename()` and `contentType()` alongside final URI, size, ranges and validators. A usable GET-range response filename takes precedence over HEAD metadata; otherwise HEAD metadata or the final URL path supplies the suggestion. Empty files retain HEAD metadata without an unnecessary range probe.

Content-Disposition parsing prefers a valid `filename*` over `filename`, decodes UTF-8 extended values strictly, accepts legacy ISO-8859-1, and falls back on malformed encodings/unsupported charsets. Quoted semicolons and escaped quotes are handled. Duplicate parameters invalidate the header. Plain `filename` values are not percent-decoded, and `+` is not changed to a space. These choices follow [RFC 6266](https://www.rfc-editor.org/rfc/rfc6266.html#section-4.3) and the extended encoding in [RFC 8187](https://www.rfc-editor.org/rfc/rfc8187.html#section-3.2); tolerant invalid-header recovery does not imply a complete HTTP header validator.

`SafeFilename` strips directory components using both slash conventions, removes control/format/line-separator characters and invalid surrogate code points, replaces forbidden Windows punctuation, trims trailing dots/space, normalizes Unicode to NFC, and prefixes reserved device names. Bounds are 200 UTF-16 units and 240 UTF-8 bytes, preserving ordinary extensions (up to 32 UTF-16 units) and complete code points. Oversized extensions are treated as part of the name and may be truncated. Empty names and dot/dot-dot become `download`. Numbered conflict names obey the same bounds.

The metadata is advisory: it never chooses a parent folder, launches a file, or changes a previously selected target when a source URL refreshes. `ProbeResult.toString()` redacts URL/metadata details. Source URI accessors and the private state database still contain URLs required for transfer/resume.

## API and compatibility

```java
// `probe` is metadata obtained by AJDM's extraction/probe layer.
TransferRequest request = TransferRequest.inDirectory(sourceUri, chosenDirectory, probe);
TransferHandle handle = engine.start(request); // Rename by default.

// Explicit replacement is a caller-selected policy, never inferred from a server header.
TransferRequest replace = request.withConflictPolicy(ConflictPolicy.OVERWRITE);

// After an ASK collision, the caller can choose a policy for the retained download.
TransferHandle resumed = engine.resume(downloadId, ConflictPolicy.RENAME);
```

`TransferRequest.of(uri, target)` and `inDirectory(...)` default to Rename. The five-argument constructor and existing persisted records retain Ask/no-clobber behavior, including development benchmark/runner requests. The six-argument constructor carries an explicit policy. Existing constructors/options remain available; old HTTP call patterns and benchmark comparisons are unchanged.

New targets are checked by product factories, target-change operations and engine start before any directories, partials, state records or network requests are created. Explicit unsafe leaf names are rejected rather than silently rewritten. Reading old stored local target names remains possible; migration does not rename old files or make history unreadable. Resume retains those previously selected local paths. A full filename/decision UI and public numeric validation-error integration remain future work.

## Policy behavior

| Policy | Existing target | Collision during publication |
|---|---|---|
| Rename | Choose a free numbered sibling in the same folder | No-clobber hard-link attempt detects races; persist another sibling and retry without redownloading |
| Ask | Stop with TARGET_EXISTS / AJDM-2007 and retain partial/state | Keep verified partial and ready marker; caller can resume with a chosen policy |
| Overwrite | Permit a regular file only, preserving it during transfer | Replace only after size and any supplied checksum verification, using an atomic move of an owned staging hard link, or journaled direct move when links fail |

Ask supplies the engine decision point; it does not present a GUI prompt or block a worker waiting for human input. Existing-target Ask stops before probing; a late Ask collision preserves the verified ready-to-publish file. `resume(id, policy)` persists the explicit decision and preserves same-engine credentials/refresh-provider registration. Other resume overloads retain the stored policy.

Overwrite rejects observed directories and symbolic links, and never follows a target symlink to write elsewhere. A target changed to a regular file during the transfer is covered by the explicit Overwrite policy. Rename also treats directories/symlinks as occupied names. Concurrent Rename downloads cannot clobber one another's final files: the final no-clobber filesystem operation decides the winner.

## Publication and crash recovery

Every Rename target change commits to SQLite before publishing to that path. The ready marker remains valid because the verified partial and byte identity do not change. Recovery reads the committed target, re-verifies as required, and either creates the final link or reconciles it by same-file identity. `TransferResult.target()` and persisted history identify the actual chosen destination.

Overwrite creates `<unique-partial>.publish` as a hard link to the verified partial, then atomically moves that staging link onto the final target. The original partial link remains until SQLite commits COMPLETED. A killed process before replacement retains the old destination plus the verified partial/stage; after replacement, final and partial are the same inode and recovery recognizes completion without another download. Existing stage paths are reused only when they are regular files sharing the partial's file identity. Unrelated stages are neither overwritten nor deleted.

Completion removes redundant owned stage/partial names; failed cleanup does not invalidate the completed file. If a staged Overwrite fails and the caller chooses Rename on resume, recovery publishes to the sibling and cleans the owned stage. This prevents retained staging links when changing policies.

Rename/Ask still require hard-link support. When an Overwrite staging link is unavailable, AJDM saves SHA-256 and publication intent, then atomically moves the partial itself. Recovery with a missing partial requires that journal and a matching regular final file; length alone cannot prove completion. Overwrite always requires atomic replacement and fails with the original/partial intact if unsupported. There is no copy/delete fallback. Known-size partials are now logically preallocated. Portable no-clobber support, physical allocation/directory durability and native Windows/macOS behavior remain release gates. See [file layout and publication](FILE_LAYOUT_AND_PUBLICATION.md). A final link exposed before a crash is a complete verified file, not a partially copied result.

## Persistence

SQLite v4 added `conflict_policy`, `suggested_filename` and `content_type`. Schema v5 adds `publication_intent`; v1/v2/v3/v4 migrate transactionally. Old records receive no move intent, so missing old partials cannot be mistaken for published files. Pre-v4 records receive Ask; v4 records retain their selected policy. All retain their targets/options/coverage and derive a URL filename fallback when old metadata is absent. Validated URL replacement commits fresh metadata with the source and headers, retaining the selected target/policy. Same-directory destination/policy changes preserve ranges, hashes and ready markers and reject changes to terminal completed/canceled downloads.
