# SkinShop — BedWars1058 Shop Keeper Random Team-Skin Addon

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-11%2B-blue.svg)](https://openjdk.org/)
[![Spigot/Paper](https://img.shields.io/badge/Spigot%2FPaper-1.8.8%20--%201.20%2B-brightgreen.svg)](https://papermc.io)
[![Build](https://img.shields.io/badge/build-Maven-c71a36.svg)](#-building-from-source)

**Author:** NedayAzady  
**Version:** 1.0.0  
**Java Version:** 11+  
**Target Plugin:** BedWars1058 by andrei1058 ([GitHub](https://github.com/andrei1058/BedWars1058))  
**License:** [MIT](LICENSE)

> Give every BedWars team's Shopkeeper NPC a real Minecraft skin — picked randomly from that team's players, with a configurable fallback for empty teams, dual Citizens 2 / ZNPCsPlus support, and Hypixel-style head tracking.

---

## 📖 Overview

**SkinShop** is a high-performance Spigot/Paper addon for **BedWars1058**. It dynamically transforms the Shopkeeper (and optionally Upgrade) NPCs in each team's island base into real Minecraft player skins:

- **When an arena starts:**
  - If a team has **at least one player**: Picks one random player from that team and applies their real Minecraft skin to the island's Shopkeeper NPC.
  - If a team has **multiple players**: The random selection is locked for the duration of the match (does not change mid-game) unless configured otherwise.
  - If a team is **empty** (e.g. unfilled solo/duo team slots): Automatically applies the configurable fallback skin — default Minecraft account **"Dewier"**.
- **Waiting-Phase Fix**: When the arena is in the **waiting** state, every Shop NPC already visible from the lobby is immediately fixed with the fallback skin, so they never show default villagers before the game starts.
- **Player-Tracking Head Rotation (`npc-look-at-players`):**
  - NPCs automatically rotate and turn their heads to look at approaching players when nearby!
  - Supports Hypixel-style per-player head rotation (`per-player: true`), head-only rotation (`head-only: true` — default, avoids the Citizens 1.8 position-packet NPE), customizable detection range, and realistic smooth turning (`realistic-looking: true`). Note: the ZNPCsPlus provider implements this with its `Per Player` look in ZNPCsPlus look type (full body/head tracking), since ZNPCsPlus has no head-only mode.
- **Dual NPC provider support:** works with **Citizens 2** OR **ZNPCsPlus** (`npc-provider: auto`). If both are installed, Citizens is preferred.
- **Mid-game join handling:**
  - If an initially empty team receives a player mid-game (e.g. rejoin/late assignment), the NPC automatically updates from the fallback skin to that player's skin.
- **Team Skin Rendering Fix (`team-skin-render-fix`):**
  - **Unique Player Profile Isolation:** Every arena player's own `GameProfile` skin properties (textures + signature NBT) are captured and kept **strictly isolated per UUID**. Two teammates' skins can never cross-contaminate each other, even when a plugin re-sends a stripped GameProfile.
  - **Team Packet Sync Fix:** Skin metadata is protected against overwrites from scoreboard-team / tablist packets (`PacketPlayOutPlayerInfo`, `PacketPlayOutScoreboardTeam`, `PacketPlayOutNamedEntitySpawn`) that assign team colors or glowing outlines. A Netty interceptor re-injects each player's own textures whenever one was rebuilt without them.
  - **Match Join Skin Refresh:** Each player's correct skin properties are re-applied and refreshed immediately upon first spawn / rejoin into the BedWars arena world (plus a proactive corrected `ADD_PLAYER` push at match init).
- **Zero-Latency Skin Extraction & Caching:**
  - For online players in the match, their skin texture data (value + cryptographic signature) is read directly from their active `GameProfile` in server memory with **0ms network delay** and **no Mojang API rate limits**.
  - For configured usernames or offline lookups, textures are resolved asynchronously via the Mojang API and cached locally in `skin-cache.yml` according to `cache-skins-minutes`.
  - Proper error handling: If Mojang is down or rate-limited, the plugin falls back safely (to the configured empty-team skin / safety texture) without crashing the arena.

---

## 📋 Requirements

1. **Minecraft Server:** Spigot, Paper, or Purpur (1.8.8 - 1.20+ supported).
2. **Java:** Java 11 or newer (Temurin, OpenJDK, etc.).
3. **BedWars1058:** Installed and active (`depend: [BedWars1058]`).
4. **Citizens 2** or **ZNPCsPlus** (`softdepend: [Citizens, ZNPCsPlus]`):
   - *Why?* Vanilla Minecraft villagers cannot display player skins by Minecraft client design. SkinShop needs a player-NPC library to render real skins:
     - **Citizens 2** (the classic choice): skinShop spawns real Citizens Player entities and applies the skin via `SkinTrait`.
     - **ZNPCsPlus** (by Pyrbu): if Citizens is absent but ZNPCsPlus is installed, SkinShop works too — it hides the default BedWars villager (invisibility effect, kept as the real shop click target) and displays a skinned packet-based fake-player NPC on top of it.

---

## 🚀 Installation

1. Stop your server.
2. Ensure **BedWars1058** is placed in your `plugins/` directory.
3. Install an NPC provider: **Citizens 2** and/or **ZNPCsPlus** (at least one is required for player skins to render).
4. Place `SkinShop-1.0.0.jar` into your `plugins/` directory.
5. Start your server to generate `plugins/SkinShop/config.yml`.
6. Customize `config.yml` as desired and run `/skinshop reload`.

---

## ⚙️ Configuration (`config.yml`)

```yaml
# ============================================================================== #
#                   BedWars1058 Shop Keeper Random Team-Skin Addon               #
#                               Author: NedayAzady                               #
# ============================================================================== #

# Fallback skin applied when a team is empty (no players assigned to it,
# e.g. solo/duo mode with unfilled team slots) or when a skin lookup fails.
# Skin Name: "Dewier" (default) - set the account username whose skin should show.
empty-team-skin:
  # Source type options:
  #   - USERNAME         : Fetches the skin of the specified Minecraft account via Mojang API
  #   - VALUE_SIGNATURE  : Uses raw Base64 texture value & signature data
  #   - URL              : Uses a direct skin URL (e.g. textures.minecraft.net)
  type: USERNAME

  # If type is USERNAME:
  # The Minecraft username whose skin will be applied to empty team shopkeepers.
  username: "Dewier"

  # Shorthand value field (works for USERNAME or URL):
  value: "Dewier"

  # If type is VALUE_SIGNATURE:
  # The base64 texture value and cryptographic signature from Mojang.
  texture-value: ""
  texture-signature: ""

  # If type is URL:
  # Direct URL to the skin texture.
  # Example: "http://textures.minecraft.net/texture/4679720b523f..."
  url: ""

# Cache skin textures locally in minutes to prevent hitting Mojang API rate limits.
# Default: 60 minutes
cache-skins-minutes: 60

# Apply skins to the regular Team Shopkeeper NPC
apply-to-shop: true

# Apply skins to the Team Upgrades NPC
apply-to-upgrades: true

# Re-roll random pick only when the arena (re)starts.
# When true, mid-game respawns or player joins will keep the team's chosen skin
# without mid-game re-rolling.
reroll-only-on-arena-start: true

# NPC provider selection. Which NPC library is used to display player skins:
#   - auto       : Use Citizens if it is installed, otherwise fall back to ZNPCsPlus.
#   - citizens   : Require Citizens (real player entities).
#   - znpcsplus  : Require ZNPCsPlus (packet-based fake NPCs, no Citizens needed).
npc-provider: auto

# ============================================================================== #
#                  NPC Look-At-Player / Head Rotation Settings                   #
#                          (bayad kalashoon becharkhe)                           #
# ============================================================================== #
npc-look-at-players:
  # When enabled, shopkeeper & upgrade NPCs will rotate their head/body to look
  # at approaching players when players are near their base.
  enabled: true

  # The maximum detection distance (in blocks) within which NPCs will look at players.
  range: 10.0

  # Per-player packet view:
  #   - true: Every player will see the NPC looking directly at them individually
  #           (Hypixel-style per-player head tracking).
  #   - false: The NPC turns globally towards the closest player in the world.
  per-player: true

  # Head-only rotation:
  #   - true: Only the NPC's head rotates towards players; body stays in place.
  #           (RECOMMENDED. Avoids the Citizens 1.8 "Cannot read field 'xLoc'"
  #           NMS NPE that occurs with full body-position rotation packets.)
  #   - false: Both the NPC's body and head turn towards the player.
  head-only: true

  # Enable smooth and realistic head rotation instead of instant snapping.
  realistic-looking: true

# Citizens NPC support:
# When enabled and Citizens is present on the server, if BedWars1058 spawned
# default vanilla villagers, SkinShop will convert/replace them with Citizens
# Player NPCs so custom player skins can be displayed in-game.
# Note: This setting only affects the Citizens provider. When falling back to
# ZNPCsPlus, the default BedWars villagers are hidden (invisibility effect) and
# kept as the real shop click target, with a skinned fake-player NPC on top.
spawn-citizens-npc-if-missing: true

# Delay in ticks after arena starts before applying NPC skins.
# 25 ticks = 1.25 seconds (allows BedWars to finish spawning base entities).
apply-delay-ticks: 25

# ============================================================================== #
#                    Team Skin Rendering Fix (packet-level)                      #
# ============================================================================== #
# Fixes the BedWars rendering conflict where two teammates with active custom
# skins can show duplicate / wrong / default textures. The addon keeps each
# player's GameProfile skin properties (textures + signature) strictly isolated
# per UUID, fixes outbound player-info (PacketPlayOutPlayerInfo / tablist),
# named-entity-spawn and scoreboard-team packets, and re-applies the correct
# skin the moment a player spawns into the arena world.
team-skin-render-fix:
  # Master switch for the whole team-skin render fix module.
  enabled: true

  # Capture and strictly isolate every arena player's own skin texture/signature
  # per UUID at match init / join. Nothing is ever shared between teammates.
  isolate-profiles: true

  # Intercept PlayerInfo/tablist packets and re-inject the isolated profile when
  # one was rebuilt without textures (the root cause of wrong/duplicate skins).
  fix-player-info: true

  # Watch scoreboard-team packets (team colors / glowing outlines) and refresh
  # the referenced players' isolated profiles so team assignment never wins over
  # an individual player's own skin.
  fix-scoreboard-team: true

  # Before named-entity spawns are delivered, ensure the spawned player's own
  # isolated profile is present so clients render the correct skin immediately.
  fix-named-entity-spawn: true

  # Proactively push fully-corrected ADD_PLAYER profile packets on match start
  # and on first spawn / rejoin (belt & suspenders on top of packet fixing).
  proactive-refresh: true

  # Log the packet fixes performed for debugging.
  debug: false

# Enable detailed debug messages in the server console
debug: false
```

---

## 🔍 How Skin Logic is Triggered

1. **Waiting / Arena Start Trigger (`GameStateChangeEvent`):**
   - When an arena enters `GameState.waiting`, SkinShop immediately fixes every visible Shop/Upgrade NPC with the configured empty-team fallback skin (**"Dewier"** by default), so the lobby never shows default villagers.
   - When the arena transitions to `GameState.playing`, SkinShop registers the event and waits `apply-delay-ticks` (default: 25 ticks / 1.25s) to let BedWars finish spawning team base elements.
2. **Team Inspection (`arena.getTeams()`):**
   - For each team:
     - Retrieves alive team members via `team.getMembers()`.
     - **If team has members:** Selects a random player (`ThreadLocalRandom.current().nextInt(...)`) and binds their name to the team.
     - **If team is empty:** Binds the fallback empty-team username (e.g. `"Dewier"`).
3. **Skin Resolution Hierarchy:**
   - **Step 1 (Online Profile):** If the selected player is online, reads their skin texture value and signature directly from memory via reflection on `CraftPlayer.getProfile()`. (0ms delay, no HTTP calls).
   - **Step 2 (Local Cache):** If offline or checking the fallback skin, checks the `skin-cache.yml` for an entry newer than `cache-skins-minutes`.
   - **Step 3 (Mojang API):** Asynchronously contacts `api.mojang.com` (UUID) and `sessionserver.mojang.com` (Textures). Upon arrival, saves to cache.
   - **Step 4 (Safety Fallback):** If Mojang fails, rate-limits (HTTP 429), or the user doesn't exist, applies the configured empty-team username skin (or the hardcoded safety texture) and logs a warning. Never crashes the arena.
4. **NPC Application (`team.getShop()` and `team.getTeamUpgrades()`):**
   - BedWars1058's `ShopOpenListener` listens for interactions on **any** entity located at the shop coordinate block.
   - **Citizens provider:** SkinShop finds the Citizens NPC at `team.getShop()` (or converts the vanilla villager to a Citizens Player NPC), applies the skin using `net.citizensnpcs.trait.SkinTrait`, and refreshes via `npc.despawn()` / `npc.spawn()`.
   - **ZNPCsPlus provider:** SkinShop hides the BedWars vanilla villager at each block with an invisibility effect — keeping it alive so BedWars' shop-interaction (`ShopOpenListener`/`UpgradeOpenListener`) still opens the shop/upgrade GUI — and overlays a skinned packet-based fake-player NPC via the ZNPCsPlus API (`NpcApiProvider.get()` → `NpcRegistry.create` + `skin` property), configuring the `look`/`look_distance` properties for player-tracking. The NPC entry is recreated from scratch on every apply (ZNPCsPlus only reads the skin when an NPC is spawned to a viewer), non-saveable, and cleaned up per arena.
5. **Arena Cleanup (`ArenaRestartEvent` / `ArenaDisableEvent`):**
   - When an arena resets or disables, all dynamic NPCs spawned for that match are destroyed (both Citizens and ZNPCsPlus entries) and arena cache mappings are cleared.

---

## 🛠️ BedWars1058 API Class & Method Reference

The following classes and methods from the BedWars1058 API (`com.github.andrei1058.BedWars1058:bedwars-api`) are utilized. You can verify these against your server's BedWars1058 jar:

| Interface / Class | Location | Method Used | Purpose |
|-------------------|----------|-------------|---------|
| `BedWars` | `com.andrei1058.bedwars.api.BedWars` | Registered via `ServicesManager` | Access BedWars global utilities & arenas |
| `IArena` | `com.andrei1058.bedwars.api.arena.IArena` | `getTeams()`, `getStatus()`, `getArenaName()` | Get list of teams and current match state |
| `ITeam` | `com.andrei1058.bedwars.api.arena.team.ITeam` | `getMembers()`, `getShop()`, `getTeamUpgrades()`, `getName()` | Retrieve team members & shop coordinates |
| `GameState` | `com.andrei1058.bedwars.api.arena.GameState` | `GameState.playing`, `GameState.restarting` | Detect match start and match end |
| `GameStateChangeEvent` | `com.andrei1058.bedwars.api.events.gameplay` | `getArena()`, `getNewState()` | Trigger skin selection on arena start |
| `PlayerFirstSpawnEvent` | `com.andrei1058.bedwars.api.events.player` | `getArena()`, `getTeam()`, `getPlayer()` | Handle first spawn / late join |
| `PlayerReJoinEvent` | `com.andrei1058.bedwars.api.events.player` | `getArena()`, `getPlayer()` | Handle rejoin to an active game |
| `PlayerLeaveArenaEvent` | `com.andrei1058.bedwars.api.events.player` | `getArena()`, `getPlayer()` | Detect when a team becomes empty mid-game |
| `ArenaRestartEvent` | `com.andrei1058.bedwars.api.events.server` | `getArenaName()` | Clean up dynamic NPCs on map reset |
| `ArenaDisableEvent` | `com.andrei1058.bedwars.api.events.server` | `getArenaName()` | Clean up dynamic NPCs on arena disable |

---

## 💻 Commands & Permissions

| Command | Permission | Description |
|---------|------------|-------------|
| `/skinshop reload` | `skinshop.admin` | Reloads `config.yml` and refreshes skin cache / render-fix |
| `/skinshop info <arena>` | `skinshop.admin` | Displays current team skin assignments for an arena |
| `/skinshop update <arena>` | `skinshop.admin` | Forces skin re-application for all teams in an arena |
| `/skinshop refresh <arena>` | `skinshop.admin` | Forces isolated-profile packet refresh for all players in an arena |

---

## 🔨 Building from Source

```bash
git clone <repository-url>
cd skinshop
mvn clean package
```

The compiled jar will be available at `target/SkinShop-1.0.0.jar`.

---

## 📦 Download

Grab the latest `SkinShop-*.jar` from the [Releases](../../releases) page, or build it yourself from source (see above).

---

## 🤝 Contributing

Contributions are welcome! Feel free to open an issue or submit a pull request.

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes (`git commit -m "Add my feature"`)
4. Push to the branch (`git push origin feature/my-feature`)
5. Open a Pull Request

Please make sure `mvn clean package` succeeds before submitting.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).

```
MIT License

Copyright (c) 2026 NedayAzady
...
```

See [LICENSE](LICENSE) for the full text.

---

## 🙏 Credits

- **[BedWars1058](https://github.com/andrei1058/BedWars1058)** by andrei1058 — the base plugin this addon extends
- **[Citizens](https://github.com/CitizensDev/Citizens2)** — player NPC provider
- **[ZNPCsPlus](https://github.com/Pyrbu/ZNPCsPlus)** — packet-based fake-player NPC provider
