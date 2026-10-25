<h1 align="center"> Changelog </h1>

# v2.2.0
1. A new `SyncExpansionsPayload`, plus `ExpansionFamilySync` on the server. When a player's held map changes or they join, the server walks the expansion family (visited set, 256-map cap, same traversal as the client grid) and sends the expansion links. It sends each map's waypoints and non-empty discovery once per session. Expanding also triggers this for the requester and for anyone whose held map reaches the expanded map.
2. `broadcastTo` now also sends to players whose held map reaches the changed map via `isAccessibleFrom`, and to players who already have that map synced. I added that second condition because each map is sent once per session, so a client that already holds a map must keep getting its updates.
3. One shared `CodecUtil.boundedString`. It rejects oversized values on load and never on save, so a save can't fail on data already in memory. Bounded the two dimension-string fields and the direction name in persistent data, which were the same pattern.

# v2.1.1
1. `ExpansionFailedPayload.Reason`: it now travels as a varint ordinal, the same way`Direction` was changed, so an unbounded string can no longer be read. Out-of-range values are rejected with a `DecoderException`.
2. `Direction.CODEC` (saved data): an unknown direction in a save now returns a decode error instead of throwing. The affected expansion record is skipped, as the other malformed-data checks already do, and the rest of the save loads.
3. Cross-dimension warning in `TileGrid.build`: each expansion that can't be shown in the current dimension is now logged once per session instead of on every grid build. Renamed `resetTruncationReports()` to `resetReportedWarnings()` because it now clears both sets of already-reported warnings, and the disconnect handler in `ExplorerMapClient` calls the new name.


# v2.1.0
1. The waypoint collection codec is now capped at 512 during decode. The server also truncates its list to 512 (with a warning) before sending, because the capped codec would otherwise throw on larger lists. Removed the client-side size check, which could no longer trigger.
2. `Direction` is sent as a single varint ordinal instead of a string, and out-of-range values are rejected.
3. The persistent discovery bitmask now uses existing `CodecUtil.boundedList`, capped at 256 longs.
4. New `DiscoveryUploadLimiter` on the server, per player, using wall-clock time. It enforces an upload-count budget, a byte budget, and a 30 s cooldown after repeated invalid packets (wrong size, no map in hand, inaccessible map). It also has a global cap of 400 uploads per second, and it runs before any map resolution. A client sending at its maximum pace was never throttled in my checks.
5. Persistent waypoint id, name and icon have length bounds, and the owner must be empty or a canonical UUID. Anything else makes that waypoint fail to load and it is skipped. The bounds apply on load only, so a save can never fail on data already in memory. Name, icon and owner lengths are now shared with the network codec, and the network owner limit is 36 instead of 64.
6. The tile limit is a new config option (16–512, default 256) with a tooltip, and a warning is logged once per root map when the grid is cut off. The old code could miss truncation when it stopped exactly at the cap; that case is now detected.
7. Uploads carry a generation number and the server replies with a new `Ack` packet, including when the upload changed nothing. The client retries up to 4 times with backoff (5, 10, 20, then 40 s), then stops until that map's discovery changes again.