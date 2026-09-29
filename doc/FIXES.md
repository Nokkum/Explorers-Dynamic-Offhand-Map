# Fix log

### 1. Expanded-map discoveries were never uploaded

`ExplorationEngine` built a `TileGrid` (root + expansions) and could discover
pixels on any tile in it via `markRayAcrossTiles` -> `owner.entry().discover(...)`.
But the upload check at the end of `tick()` only ever looked at the root
map's `MapEntryData` and `MapIdentity`. Discoveries on an expanded tile
changed that tile's own `MapEntryData`, whose generation was never checked
and whose bitmask was never sent - so an expanded tile could visually update
client-side but never actually sync to the server.

Fix: `SyncDiscoveryPayload` tracks upload state per `MapIdentity`
(`isDirty`/`markUploaded`) instead of a single global generation.
`ExplorationEngine.uploadDirtyTiles()` walks every tile in the current grid
each upload window and sends an `Upload` for each dirty one, capped at
`MAX_UPLOADS_PER_WINDOW` (8) per window so a large map family can't spam
packets in one tick.

This required relaxing `SyncDiscoveryPayload.Upload`'s server-side
authorization from "must be the exact held map" to "must be the held map or
a member of its family" (`MapIdentity.resolveAuthorized`), since a player
uploads discovery for expansion tiles they aren't directly holding.

### 2. TileGrid only followed direct expansions

`TileGrid.build()` added the root tile, then looped over the root's own
`getExpansions()` once - an expansion two hops away (an expansion of an
expansion) was never added to the grid, never rendered, and (before fix #1)
never had its own discovery tracked or uploaded either.

Fix: `TileGrid.build()` does a breadth-first walk over the expansion graph
starting at the root, following each visited tile's own expansions in turn,
with a `visited` set (also serves as the cycle guard for #3) and a
`MAX_TILES` cap (256) as a sanity limit.

### 3. Expansion graph could contain cycles

Nothing prevented an expansion chain from looping back on itself. The
`visited` set added for #2 makes any client-side traversal cycle-safe
regardless. For prevention rather than just tolerance,
`RequestExpansionPayload` now also rejects a new expansion server-side if
the resolved target can already reach back to the source
(`savedData.isAccessibleFrom(newMapId, mapId)`), which would close a loop.
Rejection reuses a new `ExpansionFailedPayload.Reason.CYCLE_DETECTED`
(enum + lang key + `ExpansionFeedback` message added).

### 4. SaveWaypointPayload's Waypoint codec was unbounded over the network 

`Waypoint.CODEC` (used for both NBT persistence and, via
`PacketCodecs.codec(...)`, network transport) uses plain `Codec.STRING` for
name/iconId/ownerUuid with no length limit. A client could send an
arbitrarily large string in a `SaveWaypointPayload` and the server would
fully allocate it during decode, before any of the existing post-decode
length checks in `SaveWaypointPayload.handleOnServer` ever ran.

Fix: added `Waypoint.PACKET_CODEC`, a separate network-only `PacketCodec`
built with `PacketByteBuf.writeString/readString(value, maxLength)`, which
enforces the bound during decode itself (throws rather than allocates past
the limit). Used by `SaveWaypointPayload` and `SyncWaypointsPayload` in
place of `PacketCodecs.codec(Waypoint.CODEC)`. The persistence codec
(`Waypoint.CODEC`) is intentionally left as-is; `MapEntryData.isValidOnLoad`
already bounds it after decode, and the two codecs are allowed to have
different compatibility requirements.

### 5. Persistent discovery bitmask list was unbounded at decode time

`DiscoveryRow`'s `bitmask` field decoded via plain `Codec.BYTE.listOf()`,
so a save with a huge byte list would be fully materialized into a
`List<Byte>` (one boxed object per byte) before the surrounding `xmap`
truncated it to `MapEntryData.BYTE_COUNT` (2048). Same shape of problem
`CodecUtil.boundedList` was already written to solve for entries / tiles /
shares / discoveries / waypoints / expansions in an earlier round - this
field was simply missed.

Fix: swapped `Codec.BYTE.listOf()` for
`CodecUtil.boundedList(Codec.BYTE, MapEntryData.BYTE_COUNT, "bitmask")`.

### 6. Structure detection could miss structures in unloaded chunks

The delta-based structure-detection design (from an earlier round) is
stateless: a chunk that's unloaded when its discovery delta arrives is just
skipped, on the assumption a later full/join-time check will retry it. That
assumption breaks if the area is never revisited and nothing ever loads that
chunk again - the structure could go permanently undetected.

Fix: `MapEntryData` gained a persisted, bounded (`MAX_PENDING_STRUCTURE_CHUNKS`
= 4096) `pendingStructureChunks: Set<Long>` (packed `ChunkPos`). When
`StructureWaypointDetector.checkAndPlace` hits an unloaded chunk, it's added
to this set instead of silently dropped. A new `retryPending()` method
re-checks every pending chunk for a map and removes it from the set once
successfully scanned (regardless of whether a waypoint was placed).
`ServerEventHandler` calls `retryPending` for every map with a non-empty
pending set on a slow sweep (`PENDING_STRUCTURE_RETRY_INTERVAL_TICKS` = 400,
~20s) independent of any discovery event, since the scenario this guards
against is specifically "no new discovery ever happens again."
The chunk-scan logic itself was extracted into a shared
`scanChunkForStructures` helper used by both `checkAndPlace` and
`retryPending`, so the two paths can't drift apart.
