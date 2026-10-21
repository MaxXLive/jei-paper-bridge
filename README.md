<p align="center"><img src="icon.png" alt="JEIPaperBridge icon" width="160"></p>

# JEIPaperBridge

A Paper plugin that implements the server side of the [Just Enough Items (JEI)](https://github.com/mezz/JustEnoughItems) mod's network protocol.

JEI is a client mod for browsing items and recipes. Some of its features need JEI on the server too: moving a recipe's ingredients into the crafting grid with the **+** button, cheat mode (giving items, deleting items), and showing the server's recipes. On a Paper server JEI shows *"The server must have JEI installed"*, cheat mode only works in creative mode, and JEI shows the default recipes built into the client with a chat warning.

JEIPaperBridge answers JEI from a Paper server. JEI clients connect as usual and get the same features they would get from a modded server. Players don't need to install anything extra, and players without JEI aren't affected.

> **Unofficial.** This project is not affiliated with or endorsed by mezz or the JEI project. Please don't report problems with this plugin to the JEI issue tracker.

## Requirements

| | |
|---|---|
| Server | [Paper](https://papermc.io) **26.3** (or a Paper fork such as Purpur) |
| Java | 25 |
| Client | JEI for Fabric **26.3** (tested with 31.16.0) with Fabric API |

Spigot/CraftBukkit is **not** supported. The plugin uses Minecraft internals (menus, slots, recipe codecs, the network pipeline), and only Paper exposes them with Mojang names. Folia is untested.

NeoForge clients use the same JEI packets, so recipe transfer and cheat mode should work for them too, but this is untested. Server recipes are only sent to Fabric clients (see [Recipe sync](#recipe-sync)).

## Installation

1. Download the latest `JEIPaperBridge-<version>.jar` from [Hangar](https://hangar.papermc.io/MaxXLive/JEIPaperBridge), [Modrinth](https://modrinth.com/project/bZunXXBE) or [GitHub Releases](https://github.com/MaxXLive/jei-paper-bridge/releases), or [build it yourself](#building). Pick the release whose version starts with your server's Minecraft version.
2. Put it into your server's `plugins/` folder.
3. Restart the server.

The startup log should contain:

```
[JEIPaperBridge] Serving JEI clients (recipe transfer: on, cheat mode: on, recipe sync: on)
```

Join with JEI and run `/jeipb status`. Your name should be listed, and JEI should not print a recipe warning in chat.

## Supported features

| JEI feature | Without the plugin | With the plugin |
|---|---|---|
| Recipe transfer (**+** button, shift for the maximum) | "The server must have JEI installed" | Works in the player inventory, crafting table, crafter, furnace, smoker, blast furnace, brewing stand, anvil and smithing table |
| Cheat mode: give items (one or a stack, to the cursor or the inventory) | Creative mode only | Works for players that are allowed to cheat |
| Cheat mode: set hotbar items with number keys | Not available | Works for players that are allowed to cheat |
| Delete the item on the cursor | Not available | Works for players that are allowed to cheat |
| "No permission" message and turning cheat mode off | – | Same as with JEI on the server |
| Server recipes (datapacks, plugin recipes) | Default recipes from the client, chat warning | The server's recipes, no warning (Fabric clients) |

### Not supported

- **Recipe transfer for modded menus.** Only the vanilla menus JEI has transfer handlers for are accepted.
- **JEI's server config screen.** The settings live in the plugin's `config.yml` instead of JEI's `jei-server.ini`.
- **Items that only exist through components** (for example a renamed item from a datapack recipe) don't appear in JEI's item list. That's how JEI works on any server: the list contains registered items, recipe results are only shown in their recipes.
- **Paper's exact-choice ingredients** (`RecipeChoice.ExactChoice` from plugins) are sent as their plain items, because the network format only knows items and tags.

## Configuration

`plugins/JEIPaperBridge/config.yml`:

```yaml
features:
  # Move ingredients into the open crafting table, furnace, anvil, ... with JEI's "+" button.
  recipe-transfer: true
  # JEI cheat mode: give items, set hotbar items, delete the item on the cursor.
  cheat-mode: true
  # Send the server's recipes (including datapack and plugin recipes) to Fabric clients.
  recipe-sync: true

# Who may use cheat mode, same as JEI's jei-server.ini. A player additionally needs the
# permission "jeipaperbridge.cheat" (default: op).
cheat-mode:
  enable-for-op: true        # operators (permission level 2, like /give)
  enable-for-creative: true  # players in creative mode
  enable-for-give: false     # players who can use the /give command

recipe-transfer:
  # Fire a Bukkit InventoryClickEvent before moving items, so protection and GUI plugins can cancel the transfer.
  fire-inventory-click-event: true

# Packets a single player may send per second, further packets are ignored.
max-packets-per-second: 20

# Log rejected packets and recipe sync details.
debug: false
```

Run `/jeipb reload` after editing. The recipes are sent again to all Fabric clients.

## Commands and permissions

| Command | Description |
|---|---|
| `/jeipaperbridge reload` | Reload `config.yml` and resend the recipes |
| `/jeipaperbridge status` | List online players that use JEI, show the enabled features and the size of the recipe data |

Alias: `/jeipb`.

| Permission | Default | Description |
|---|---|---|
| `jeipaperbridge.admin` | op | Use `/jeipaperbridge` |
| `jeipaperbridge.cheat` | op | Use JEI's cheat mode. JEI's own rules from `cheat-mode` apply as well |

Players in creative mode who are not operators need `jeipaperbridge.cheat` explicitly (for example from a permissions plugin) to use cheat mode.

## How it works

JEI has no handshake or protocol version. A client decides that the server has JEI when the server announced JEI's `jei:delete_player_item` channel. Paper announces the channels of all registered plugin channels when a player joins (`minecraft:register`), so registering JEI's channels with Bukkit's `Messenger` is enough.

| Direction | Channel | Content |
|---|---|---|
| client → server | `jei:request_cheat_permission` | – |
| server → client | `jei:cheat_permission` | allowed flag, translation keys of the allowed cheating methods |
| client → server | `jei:give_item_stack` | item stack, give mode (inventory / cursor) |
| client → server | `jei:set_hotbar_item_stack` | item stack, hotbar slot |
| client → server | `jei:delete_player_item` | item stack on the cursor |
| client → server | `jei:recipe_transfer` | transfer operations (source slot, target slot), crafting slots, inventory slots, max transfer, require complete sets |
| client → server | `jei:recipe_transfer_counted` | like above, with a count per operation |
| client → server | `jei:recipe_transfer_with_result` | like `recipe_transfer`, plus a transfer id |
| client → server | `jei:recipe_transfer_counted_with_result` | like `recipe_transfer_counted`, plus a transfer id |
| server → client | `jei:recipe_transfer_result` | transfer id, success flag |
| server → client | `fabric:recipe_sync` | all recipes, see below |
| server → client | `fabric:split` | parts of a recipe sync packet larger than 1 MiB |

Payloads are decoded with Minecraft's own stream codecs (`RegistryFriendlyByteBuf`, `ItemStack.STREAM_CODEC`, …) in the same byte layout as JEI. The server logic is ported from JEI's `ServerCommandUtil`, `BasicRecipeTransferHandlerServer` and `RecipeTransferUtil`, so the client can't tell the difference. Replies are written to the connection directly, because `Player#sendPluginMessage` drops packets on channels the client hasn't announced yet.

### Recipe sync

Since Minecraft 1.21.2 the client doesn't get the server's recipes anymore. On Fabric, JEI gets them through Fabric API's recipe sync (`fabric:recipe_sync`), which a Fabric server sends right before the vanilla `ClientboundUpdateRecipesPacket`. JEI only uses recipes that arrive before that packet.

The plugin does the same: a netty handler on each connection puts the encoded recipes in front of every recipe update sent to a client whose brand is `fabric`. Fabric clients without a mod that needs the recipes skip the packet. The recipes are encoded on the main thread and cached. Only recipes with vanilla serializers are sent, and each recipe is decoded again after encoding, so a recipe the client couldn't read is skipped instead of breaking the packet. After every recipe update (join, `/minecraft:reload`, plugins adding recipes at runtime) the plugin checks whether the recipes changed in the meantime and, if so, sends them again with another recipe update.

## Security

JEI trusts the client more than a plugin should, so the plugin adds some checks on top of JEI's:

- **Cheat mode** requires JEI's own rules (operator, creative mode or `/give` permission, as configured) **and** the `jeipaperbridge.cheat` permission. Given items must be valid (`ItemStack.validateStrict`), enabled in the world and not larger than their maximum stack size. Like `/give`, every give is announced to operators and logged to the console.
- **Recipe transfer** only works in the menu the player has open, and only in menus JEI has vanilla transfer handlers for. All of JEI's checks are kept: valid slot ids, target slots in the crafting area, no overlapping slots, no output slots, `mayPickup` / `mayPlace` / `allowModification`, and rollback when a full set can't be found. In addition, ingredients may only come from the player's own inventory, and an `InventoryClickEvent` is fired first so protection and GUI plugins can cancel it. Items are only moved, never created.
- **Packets** are rate-limited per player, lists are limited in size, unknown or malformed packets are ignored, and exceptions never reach Paper (which would kick the player).

## Building

```sh
./gradlew build
```

The jar ends up in `build/libs/`. The build uses [paperweight-userdev](https://github.com/PaperMC/paperweight) and needs JDK 25. Gradle downloads the Paper dev bundle on the first run. Local builds are versioned `<minecraft>-dev`.

## Versioning and releases

Versions have the form **`<minecraft version>-patch-<n>`**, for example `26.3-patch-1`, `26.3-patch-2`, `26.4-patch-1`. The Minecraft version says which server the jar is for. The patch number counts the releases for that Minecraft version.

Releases are made by [GitHub Actions](.github/workflows/build.yml):

| Event | What happens |
|---|---|
| Push to `main` | Build, tag `v<version>`, GitHub release with the jar and a changelog, upload to [Modrinth](https://modrinth.com/project/bZunXXBE) and [Hangar](https://hangar.papermc.io/MaxXLive/JEIPaperBridge) |
| Pull request to `main` | Build only. The jar (`…-pr.<number>`) is attached to the workflow run as an artifact |
| Push that only changes `*.md` files | Nothing |
| Push with only `docs:`/`ci:`/`test:`/`style:` commits since the last release | Build only, no release |

[`.github/scripts/release-info.sh`](.github/scripts/release-info.sh) picks the next version and writes the changelog:

- The Minecraft version comes from `minecraftVersion` in `gradle.properties`. When you change it, the patch number starts again at 1.
- The patch number continues from the highest `v<minecraft>-patch-<n>` tag.
- The changelog lists the commits since the previous release. Commits named in the [Conventional Commits](https://www.conventionalcommits.org) style are grouped: `feat:` under *Features*, `fix:`/`perf:` under *Fixes*. `docs:`, `ci:`, `test:` and `style:` commits are left out. All other commits go under *Changes*.

### Updating for a new JEI or Minecraft version

After updating `minecraftVersion` and `paperBuild` in `gradle.properties`, compare these parts of JEI with the plugin:

- **Packets** in `mezz.jei.common.network.packets` (channel ids, `STREAM_CODEC` layouts) and their registration in `mezz.jei.fabric.network.ServerNetworkHandler` → `JeiIds` and `JeiNetworkHandler`.
- **Cheat mode** in `mezz.jei.common.util.ServerCommandUtil` and `mezz.jei.common.config.ServerConfig` → `CheatHandler`.
- **Recipe transfer** in `mezz.jei.common.transfer.BasicRecipeTransferHandlerServer`, `RecipeTransferUtil#validateSlots` and `TransferOperation`, and the menus registered in `VanillaPlugin#registerRecipeTransferHandlers` → `RecipeTransferServer`.
- **Recipe sync** in Fabric API's `fabric-recipe-api-v1` (`ClientboundRecipeSyncPayload`) and the packet splitter in `fabric-networking-api-v1` → `RecipeSync`. JEI marks the vanilla serializers as synced in `mezz.jei.fabric.JustEnoughItems`.

This version follows JEI branch `26.3` (commit [`5b3e4be`](https://github.com/mezz/JustEnoughItems/commit/5b3e4be4d2063286ada115182dd90177e52af551)) and Fabric API `0.161.0+26.3`.

## Credits and license

JEI is made by [mezz](https://github.com/mezz) and contributors and is licensed under the [MIT License](https://github.com/mezz/JustEnoughItems/blob/HEAD/LICENSE.txt).
This plugin contains code ported from JEI (the packet formats, cheat mode and recipe transfer logic). It is distributed under the MIT License as well, with JEI's copyright notice included. See [LICENSE.md](LICENSE.md).

The recipe sync format follows [Fabric API](https://github.com/FabricMC/fabric) (Apache License 2.0). No Fabric API code is included.
