<h1 align="center"> Changelog </h1>

# v2.3.0
### Security
- Capacity packet checks distance before reading the block.
- Expansion needs a nearby cartography table, has a 1 s cooldown, and the map family is capped.
- Discovery uploads only count pixels near the player.
- System waypoints are only sent for pixels the player has discovered.
- Rejected-packet logs are throttled and sanitized.
- Waypoint bounds match the tile size (64 x scale blocks).
- Rejected waypoint edits, deletes and shares resync the client, so no ghost waypoints.
- Server reads `config/explorermap-server.json` (paper cost), not the client Cloth config.
- Cloth Config is no longer required on dedicated servers.
- Server accepts any well-formed waypoint icon id.

### Waypoints
- Slots and share charges are per player and per map; old shared pools move to current waypoint owners.
- Slots count your waypoints across all expansion tiles.
- Re-sharing a waypoint no longer costs a charge.
- Ops can edit and delete system waypoints.
- The Waypoints x/y counter shows your own waypoints, matching the server.
- Edit screen keeps custom colors; name limit is now 64.
- Sync keeps your own waypoints first when over the limit.
- Waypoints without an id get a stable id across loads.
- Unknown icons are no longer dropped on load.

### Structures
- Fixed structure ids (`village_*`, `mineshaft_mesa`, `mansion`); removed `dungeon` and `jungle_temple`.
- Large structures seen from the side are detected.
- Underground structures no longer get waypoints from the ground above.
- Duplicate check only compares automatic waypoints of the same type.
- Pending-chunk list is saved on change and raised to 16,384.
- Automatic waypoint names are translated per player.

### Maps and expansion
- Expansion tiles now render: the server paints and syncs their colors.
- Cartography table opens with either hand when a map is in the off-hand.
- Framed maps sync discovery and waypoints.
- HD toggle keeps the viewed framed map; framed maps are view-only.
- Shared discovery now reaches the recipient even if others found it first.

### UI
- Toolbar wraps at high GUI scales; cartography panel fits the window.
- Minimap arrows and dimension label stay on screen with small padding.
- Cartography table shows failure messages and refreshes its buttons.
- Expand buttons need a table in reach.
- Removed minimap click-to-open (the cursor is grabbed in-game).
- Translated hard-coded labels.

### Discovery and performance
- Upload interval is real time (3 s) instead of discovery ticks.
- Rays use the vertical FOV and window aspect, with jitter to fill gaps and fewer rays per tick.
- Hit points use the block center; world-to-pixel uses floor.
- Tile grid, canvas and tile lookup are cached.
- Recency fade repaints every 4th tick.
- Biome color lookup is skipped when no provider is registered.
- With fog off, only pixels in range are marked.

### Data and config
- Discovery bitmasks are saved as byte arrays (old saves still load).
- Skipped invalid entries are logged as warnings.
- Config values are clamped on load; added tooltips; removed unused lang keys.
- `expansionPaperCost` moved to the server config.

### Build and cleanup
- Loom pinned to 1.18.4.
- Added unit tests (limiter, tracker, structure ids) and a GitHub Actions build.
- Removed dead code, unused imports, redundant `server.execute()` hops and the redundant use-key loop.

# v2.2.1
1. `hasSynced()`, a flaw introduced by the previous update.
  - `broadcastTo` sent to any player whose synced set contained the map, even if their held map no longer reached it.
  - The exposure is small. `visibleWaypoints` still filters per waypoint, so only system waypoints, the player's own and ones shared with them could go out. System waypoints come from everyone's combined discovery, though, so it was still a real leak and the wrong principle.
  - Fix: broadcasts now require current access through `isAccessibleFrom` on the player's held map, and `hasSynced()` is removed.
  - When a player has no current access, their cached copy of that map is marked stale. The next family sync resends it, so their client doesn't keep an out-of-date copy.

2. non-monotonic acks:
  - The strict out-of-order case can't happen, because acks arrive in the order the server processes uploads.
  - It can happen through a retry: the server acks both copies of a retried upload, a server broadcast then bumps the client's generation, and the second, older ack arrives afterwards and moves the stored value backwards.
  - That caused a redundant upload.
  - Fix: the stored acknowledged generation now only moves forward.

3. the 256-map limit: 
  - The server logs a warning once per player and map when a family is cut off. The old check could also warn when nothing was actually cut off; fixed that.
  - The `maxExpansionTiles` tooltip now says the server synchronizes at most 256 maps per family.
  - I kept the limit at 256.

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