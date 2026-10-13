# Partial-file layout and atomic publication

AJDM writes payload directly into one unique `.ajdmpart` in the chosen destination folder. It never downloads segment files and merges them, or makes a second payload copy to publish. These behaviors implement part of PRD ENG-05; native filesystem coverage, physical block allocation and the measured disk-usage acceptance gate remain open.

## Known-size layout

After source validation and disk admission, the coordinator truncates uncheckpointed tail bytes above the highest durable interval. The bounded disk executor then extends the partial to the known total by a positional write at the last byte and forces file data/metadata. The allocation write does not acknowledge payload or update the range ledger. Unknown-size streams grow as bytes arrive; if a full response first reveals its length, full-size disk admission and extension happen before reading its body. Empty files remain empty.

Single-stream retries first invalidate saved coverage, then truncate and re-extend the partial. Source refresh remains subject to identity and capacity checks; retained bytes require the same known size. All gaps are downloaded again on resume, including gaps below a saved high interval. A truncated file is checked against the durable end before extension, so allocation cannot conceal missing committed bytes.

This is logical preallocation. The filesystem may create sparse extents, and bytes in extended gaps are unspecified; neither physical block reservation nor zero-filled gaps are assumed. Capacity checks remain snapshots rather than reservations. A full volume, physical power loss and the <=size+64 MiB peak extra disk gate require additional acceptance measurements. [Java FileChannel documentation](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html#write(java.nio.ByteBuffer,long)) defines extension behavior.

## Publication paths

| Policy/path | Primitive | Interrupted recovery |
|---|---|---|
| Rename / Ask | No-clobber hard link | Persisted destination plus ready partial; same-file identity reconciles an already created final link |
| Overwrite with links | Owned staging hard link, atomic replacement | Partial remains through completion commit; final/partial identity proves publication |
| Overwrite without links | Persisted move intent and SHA-256, direct atomic replacement | If partial remains, re-verify and retry; if absent, verify the regular final file against the saved size and SHA-256 before completing |

The fallback is permitted only for explicitly selected Overwrite. It runs after verification and file close, saves `publication_intent=1` and a 64-digit SHA-256 in one SQLite transaction, then performs the atomic move. No payload copy, temporary second body or non-atomic move is allowed. If atomic replacement is unsupported, publication fails and retains the original destination and verified partial. A provider may reject atomic replacement of an existing target even when it supports other atomic moves; this remains a safe failure.

Recovery needs the ready marker and journal, checks a nonsymlink regular target and exact byte count, and recomputes SHA-256 on the disk executor. Matching length alone, an unrelated final file or an old record without intent cannot establish completion. Missing/wrong/symlink finals fail without overwriting them or contacting the source. This proof verifies saved local content; without a supplied expected checksum it does not authenticate the remote source.

`resume(id, policy)` may change the choice while the partial remains. After a journaled move consumes it, first call ordinary `resume(id)` to reconcile publication; changing policy is rejected before modifying saved state. A caller's new choice cannot retroactively change an already moved file. A destination change clears an unmoved journal while preserving verified bytes/hash. Checkpoint invalidation clears ready/hash/intent together.

Unrelated staging paths are never reused or deleted. Observed directories and target symlinks are rejected for Overwrite. Completed-state cleanup is best effort; a failed cleanup does not invalidate the final file. These checks do not establish resistance to an adversary concurrently rewriting the destination directory.

## Portability boundary

Java's `ATOMIC_MOVE` ignores the other options and makes existing-target replacement provider-dependent. An ordinary move without replacement does not guarantee that the existence check and move are atomic. Consequently neither is an adequate portable no-clobber fallback for Rename/Ask. Those policies fail safely if links are unavailable, retaining the verified partial for a caller decision. A qualified native atomic no-replace adapter remains needed for broader filesystem support. See [Java Files.move documentation](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...)).

`PublicationFiles` is the operation seam used to inject link/move failures in tests. Production delegates to Java filesystem primitives. Linux process-kill tests exercise real atomic moves with simulated unsupported links; they do not constitute native FAT/exFAT, Windows or macOS validation, directory fsync guarantees, or physical power-loss evidence.

## State compatibility

Schema v5 transactionally adds `publication_intent` with default false. Old options, conflict policies, paths, coverage, ready markers and hashes remain intact. Genuine v1/v2/v3/v4 migration fixtures exercise the previous table shapes. The store rejects malformed journal proofs and intent without a ready marker; creating intent requires full known-size coverage, VERIFYING state and Overwrite. No new dependency or external payload downloader is introduced.
