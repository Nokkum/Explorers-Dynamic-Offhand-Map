<h1 align="center">Design</h1>

## Already Implemented:
- Holding a filled map in your off-hand triggers a hands-free mini-map HUD in a corner.
- Updates dynamically as you explore, only revealing areas you’ve actually seen.
- Supports vanilla and modded worlds, including biomes, structures, and dimensions.
- Maps can be expanded strategically, with directional control and resource cost. (through the Cartography table, which is still in development)
- Mini-map HUD only shows if the off-hand has a filled map.
- Optional toggle: hide map HUD in menus or when sneaking.
- Each tick, track the player’s camera vector and field of view. (Note: Not every tick exactly, as that would be laggy as hell, but you get the idea.)
- Only mark map pixels as “discovered” if visually observed, not just walked near.
- Partially explored chunks remain blank until the player looks at them.
- Optional fog-of-discovery overlay for undiscovered areas.
- To see beyond current map coverage, spend paper to unlock a new area. (to the Cartography table)
- Player chooses one direction per expansion: North, South, East, or West. (in the Cartography table)
- Newly unlocked area updates dynamically as the player explores it.
- Optional “high-detail expansion” using ink + compass for waypoints.
- Faded edges show map coverage limits
- Arrows show which directions can be expanded (in a Cartography table UI)
- After expansion, players can place waypoints using banners or with the compass item.
- Waypoints only appear for discovered areas.
- Optional icons for biome-specific discoveries (like modded biomes).
- Small overlay in configurable corner.
- Shows discovered pixels only, gradually filling as exploration happens.
- Scales dynamically or via configuration.
- Optional compass overlay to indicate player orientation.
- Optional zoom/pan controls for finer map navigation.
- Clicking mini-map opens a larger map GUI (similar to Xaero’s Minimap full map).
- Still tied to your actual map items, not auto-revealed minimap.
- Respect partially explored areas: unseen areas remain hidden.
- Allows for advanced waypoint management.
- Maintains immersive exploration: nothing is revealed without being observed.
- Multiplayer sync: optional map sharing while preserving individual discovery.
- Customizable HUD placement, scale, and transparency.
- Fog-of-discovery shading: highlights areas recently seen vs. old discoveries.
- High-detail expansions for creating waypoints or marking special locations.
- Locked maps in item frames – A map placed in an item frame can't be expanded while it's there; you'd have to remove it and hold it in your offhand to expand it.
- Map UI via right-click – Once placed, right-clicking the map opens a UI where you can reposition the map to any discovered area, displaying structures, waypoints, etc.
- Directional map expansion via cartography table – Instead of vanilla expansion, the cartography table UI lets you choose which of the four directions to expand, along with the required resources (ink, paper).
- Waypoints via cartography table – Placing waypoints becomes an option in the cartography table and requires a compass.
- Limited waypoints per compass – Each compass allows a max of 5 waypoints; to add more, you must return to the cartography table to insert another compass or erase an existing waypoint.
- Remove banner name from vanilla map UI (keep it clean—names only, no icons/emblems, like real maps).
- Click a region → coordinate popup shows for ~5–10 seconds.
- Add toggleable grid lines: 16x16 (1 chunk) or 32x32 (4 chunks).
- Add map zoom like Xaero's Minimap.
- Waypoint sharing: every 5 waypoints (1 compass) = share 1 waypoint to someone.
- No more F3 needed for coords—map in offhand shows X, Y, Z to the right of the minimap.
- Map permissions — a root map can access maps expanded in several levels deep.
- Waypoint ownership — creators get their UUID assigned; clients can't fake or reassign ownership.
- Waypoints played by a player will have edit/delete perms only to that Waypoint and not other players' Waypoints.
- Waypoint sharing — a shared waypoint only goes to its owner, intended recipients, and system-visible players.
- High-detail expansion — it no longer auto-reveals the new title.
- Old Saved-data stays backwards-compatible — old ownerless waypoints just load with an empty owner field.

<details>
<summary>Configuration options:</summary>

- Corner placement (top-left, top-right, etc.)
- Scale and opacity
- Enable/disable expansion arrows
</details>

<details>
<summary>Unimplemented:</summary>

- Cartography-table integration
- Per-player discovery storage and sharing
- Item frame and vanilla map interaction hooks
- Waypoint capacity model
- Biome/Mod integration API
</details>

<details>
<summary>Never Implement:</summary>

- Skipping chunk borders + light level (debug features).
- Skipping biome name (debug feature + gets repetitive fast).
</details>
