# Immersive Engineering — Networking

Technical documentation for the custom packet system in Immersive Engineering (Minecraft 1.12.2 /
Forge `SimpleNetworkWrapper`).

## Overview

Immersive Engineering uses a single Forge `SimpleNetworkWrapper` channel for all of its custom
client/server messaging. The channel is created at class-load and every packet is registered during
the `init` lifecycle phase. Packets fall into a few categories:

- **Client → Server** input/intent packets (e.g. "rotate my revolver", "switch chemthrower tank",
  "set ghost slot").
- **Server → Client** sync packets (e.g. "here is the mineral list", "this minecart has this shader",
  "show this obstructed-connection overlay").
- **Bidirectional** packets registered with both a server and a client handler under separate message
  ids (`MessageTileSync`, `MessageMinecartShaderSync`, `MessageShaderManual`).

Every handler defers its actual work onto the correct thread via `world.addScheduledTask(...)` /
`Minecraft.getMinecraft().addScheduledTask(...)` and returns `null` (no reply packet). This is the
standard Forge pattern that avoids touching world state from the Netty thread.

## Code organization

All packet classes live in `common/util/network/`. Each is a single `IMessage` implementation with
its `IMessageHandler`(s) as nested static classes. The `SimpleNetworkWrapper` is created and the
packets are registered in the mod's main class.

| File | Direction | Purpose |
|---|---|---|
| `MessageMineralListSync.java` | S→C | Sync excavator mineral list to client |
| `MessageTileSync.java` | C→S and S→C | Generic tile-entity NBT sync |
| `MessageSpeedloaderSync.java` | S→C | Revolver speedloader reload feedback |
| `MessageSkyhookSync.java` | S→C | Skyline hook entity position/connection |
| `MessageMinecartShaderSync.java` | C→S and S→C | Minecart shader cosmetic sync |
| `MessageRequestBlockUpdate.java` | C→S | Request a server-side block update |
| `MessageNoSpamChatComponents.java` | S→C | Status-bar chat without spam |
| `MessageShaderManual.java` | C→S and S→C | Shader unlock/sync/spawn from manual |
| `MessageBirthdayParty.java` | S→C | Firework FX on a headshot kill |
| `MessageMagnetEquip.java` | C→S | Shield magnet offhand swap |
| `MessageChemthrowerSwitch.java` | C→S | Switch chemthrower tank |
| `MessageObstructedConnection.java` | S→C | Render a failed-wire overlay |
| `MessageSetGhostSlots.java` | C→S | Set GUI ghost-slot contents |
| `MessageMaintenanceKit.java` | C→S | Apply tool config from maintenance kit |
| `MessageRevolverRotate.java` | C→S | Rotate the revolver cylinder |
| `MessageGridSync.java` | S→C | Push the virtual power grid to an open console, panel, terminal or linker |
| `MessageGridAction.java` | C→S | One console/panel edit; gated on `ContainerGridBase` |
| `MessageFluidNetSync.java` | S→C | The fluid network's counterpart of `MessageGridSync` |
| `MessageFluidNetAction.java` | C→S | One fluid console edit; gated on `ContainerFluidNetBase` |
| `MessagePumpSettings.java` | C→S | Gas pump settings |
| `MessageCrawlerInput.java` | C→S | Hydraulic Crawler control input |
| `MessageLinkerSelect.java` | C→S | A linking tool's chosen segment or main; gated on `ContainerNetworkLinker`, and the only thing that container may do |

## Channel setup and registration

The channel singleton is created at class-load (`ImmersiveEngineering.java:63`):

```java
public static final SimpleNetworkWrapper packetHandler =
        NetworkRegistry.INSTANCE.newSimpleChannel(MODID);
```

All packets are registered in `ImmersiveEngineering.init` (`ImmersiveEngineering.java:121`–`:139`)
with a monotonically increasing `messageId`. Bidirectional packets register the same message class
twice under two ids — once with a `Side.SERVER` handler and once with a `Side.CLIENT` handler:

```java
int messageId = 0;                                                              // :121
packetHandler.registerMessage(MessageMineralListSync.Handler.class,      MessageMineralListSync.class,      messageId++, Side.CLIENT);
packetHandler.registerMessage(MessageTileSync.HandlerServer.class,       MessageTileSync.class,             messageId++, Side.SERVER);
packetHandler.registerMessage(MessageTileSync.HandlerClient.class,       MessageTileSync.class,             messageId++, Side.CLIENT);
// ... MessageSpeedloaderSync, MessageSkyhookSync,
//     MessageMinecartShaderSync (SERVER+CLIENT), MessageRequestBlockUpdate,
//     MessageNoSpamChatComponents, MessageShaderManual (SERVER+CLIENT),
//     MessageBirthdayParty, MessageMagnetEquip, MessageChemthrowerSwitch,
//     MessageObstructedConnection, MessageSetGhostSlots, MessageMaintenanceKit,
//     MessageRevolverRotate
```

`Side` here is the side the packet is **received** on: `Side.SERVER` handlers run on the server (for
C→S packets), `Side.CLIENT` handlers run on the client (for S→C packets).

### Sending

Packets are dispatched through the same `packetHandler` with the usual Forge methods:

- `sendToServer(msg)` — client → server (e.g. `MessageRequestBlockUpdate` from
  `ClientEventHandler.java:803`).
- `sendTo(msg, EntityPlayerMP)` — server → one client.
- `sendToAll(msg)` — server → every client (`MessageMineralListSync` on login, `EventHandler.java:485`).
- `sendToDimension(msg, dim)` — server → every client in a dimension (`MessageMinecartShaderSync`
  server handler, `MessageMinecartShaderSync.java:76`).
- `sendToAllAround(msg, TargetPoint)` — server → clients near a point (`MessageObstructedConnection`,
  `ApiUtils.java:753`, 64-block radius).

## Packet reference

### MessageMineralListSync (S→C)
- **Payload**: `HashMap<MineralMix, Integer>` weights, serialized as a count followed by one NBT tag
  per mineral with an embedded `weight` (`MessageMineralListSync.java:52`).
- **When sent**: on `PlayerLoggedInEvent` to all players (`EventHandler.java:485`), so the client knows
  the excavator mineral veins for the manual.
- **Handler**: clears and repopulates `ExcavatorHandler.mineralList`, then refreshes the manual
  (`MessageMineralListSync.java:73`).
- **Notes**: gated by `ExcavatorHandler.allowPackets`, toggled true on login / false on logout
  (`EventHandler.java:478`, `:492`). Payload size scales with the number of registered mineral mixes;
  it is a one-shot per-login send, not per-tick.

### MessageTileSync (C→S and S→C)
- **Payload**: `BlockPos` + an arbitrary `NBTTagCompound` (`MessageTileSync.java:48`).
- **Direction**: generic two-way sync for any `TileEntityIEBase`. The server handler calls
  `receiveMessageFromClient(nbt)` (`MessageTileSync.java:65`); the client handler calls
  `receiveMessageFromServer(nbt)` (`:83`).
- **When sent**: from GUIs and tiles whenever a tile's custom state must round-trip (e.g.
  `GuiTurret.java:128`, `:204`).
- **Notes**: the server handler guards with `world.isBlockLoaded(pos)` before touching the tile. The
  NBT is opaque to the packet — each tile defines its own schema.

### MessageSpeedloaderSync (S→C)
- **Payload**: `byte slot` + `byte hand` (`MessageSpeedloaderSync.java:48`).
- **When sent**: server → client after a revolver reload, to play the reload sound, set a 60-tick
  `reload` NBT timer, and drop a speedloader item into the indicated slot
  (`MessageSpeedloaderSync.java:59`).

### MessageSkyhookSync (S→C)
- **Payload**: entity id + the `Connection` (as NBT) + `linePos` + `speed`
  (`MessageSkyhookSync.java:54`).
- **When sent**: server → client to keep the zipline hook entity's position and the line it is riding
  in sync.
- **Handler**: resolves the entity and calls `setConnectionAndPos` (`MessageSkyhookSync.java:75`).
- **Notes**: NBT-encodes a full wire `Connection` per packet. Sent while a player is actively riding a
  skyline; see performance notes below.

### MessageMinecartShaderSync (C→S and S→C)
- **Payload**: entity id + a `request` boolean; if not a request, an `ItemStack` (the shader)
  (`MessageMinecartShaderSync.java:55`).
- **Direction**: the client sends a *request* (no stack) when a shaded minecart enters the world
  (`EventHandler.java:357`); the server replies by broadcasting the shader to the whole dimension
  (`MessageMinecartShaderSync.java:76`). The server also pushes directly to one player when a shader
  is applied to a cart (`EventHandler.java:292`).
- **Handler (client)**: stores the stack in `ModelShaderMinecart.shadedCarts`
  (`MessageMinecartShaderSync.java:94`).
- **Notes**: the server reply uses `sendToDimension`, so each request fans out to every client in the
  dimension.

### MessageRequestBlockUpdate (C→S)
- **Payload**: `BlockPos` (`MessageRequestBlockUpdate.java:40`).
- **When sent**: client → server when the client needs the server to push a block update (e.g. a
  voltmeter/connector model refresh, `ClientEventHandler.java:803`).
- **Handler**: if the block is loaded, enqueues `(dim, pos)` into
  `EventHandler.requestedBlockUpdates` (`MessageRequestBlockUpdate.java:55`), which is drained in the
  world-tick handler.

### MessageNoSpamChatComponents (S→C)
- **Payload**: an `ITextComponent[]`, JSON-serialized (`MessageNoSpamChatComponents.java:42`).
- **When sent**: server → client to display de-duplicated status messages via
  `ChatUtils.sendClientNoSpamMessages` (`MessageNoSpamChatComponents.java:55`).

### MessageShaderManual (C→S and S→C)
- **Payload**: a `MessageType` enum (`SYNC`, `UNLOCK`, `SPAWN`) + a `String[]` of args
  (`MessageShaderManual.java:54`).
- **Direction**: client requests `SYNC`/`UNLOCK`/`SPAWN` from the manual; server processes and (for
  `SYNC`) replies to the client. `SPAWN` consumes the replication cost and drops a shader item; the
  server validates creative mode or ingredient consumption (`MessageShaderManual.java:96`).
- **Handler (client)**: merges synced shader names into `ShaderRegistry.receivedShaders`
  (`MessageShaderManual.java:120`).

### MessageBirthdayParty (S→C)
- **Payload**: entity id (`MessageBirthdayParty.java:42`).
- **When sent**: server → client on a special "headshot" kill; the client spawns a firework explosion
  at the entity and sets a `headshot` flag (`MessageBirthdayParty.java:52`).

### MessageMagnetEquip (C→S)
- **Payload**: `int fetchSlot` (`MessageMagnetEquip.java:40`).
- **When sent**: client → server to swap a magnet-upgraded shield between an inventory slot and the
  offhand. `fetchSlot >= 0` moves the shield to the offhand and records `prevSlot`; `< 0` returns it
  (`MessageMagnetEquip.java:51`).

### MessageChemthrowerSwitch (C→S)
- **Payload**: `boolean forward` (`MessageChemthrowerSwitch.java:39`).
- **When sent**: client → server (sneak+scroll) to switch the active tank on a multitank chemthrower
  (`MessageChemthrowerSwitch.java:51`).

### MessageObstructedConnection (S→C)
- **Payload**: start/end `Vec3d`, start/end/blocking `BlockPos`, and the `WireType`
  (`MessageObstructedConnection.java:60`).
- **When sent**: server → clients near the player (`sendToAllAround`, 64-block radius,
  `ApiUtils.java:753`) when a wire cannot be placed because something blocks the line of sight.
- **Handler**: reconstructs the connection and registers it in
  `ClientEventHandler.FAILED_CONNECTIONS` with a 200-tick lifetime so the client can draw a red
  "obstructed" overlay (`MessageObstructedConnection.java:79`).

### MessageSetGhostSlots (C→S)
- **Payload**: an `Int2ObjectMap<ItemStack>` of slot → stack (`MessageSetGhostSlots.java:56`).
- **When sent**: client → server to set ghost (display-only) slot contents in the open container.
- **Handler**: validates every target slot is actually a `Ghost` slot and logs/aborts otherwise — an
  explicit anti-cheat check (`MessageSetGhostSlots.java:85`).

### MessageMaintenanceKit (C→S)
- **Payload**: an `EntityEquipmentSlot` + an `NBTTagCompound` of config options
  (`MessageMaintenanceKit.java:45`).
- **When sent**: client → server from the maintenance-kit GUI. Keys prefixed `b_`/`f_` are applied as
  boolean/float config options to an `IConfigurableTool` (`MessageMaintenanceKit.java:62`).

### MessageRevolverRotate (C→S)
- **Payload**: `boolean forward` (`MessageRevolverRotate.java:30`).
- **When sent**: client → server to rotate the revolver cylinder; the handler calls
  `ItemRevolver.rotateCylinder` (`MessageRevolverRotate.java:46`).

## Threading and safety pattern

Every handler follows the same shape (illustrated by `MessageTileSync.HandlerServer`,
`MessageTileSync.java:54`):

```
onMessage(msg, ctx):
    world = ctx.getServerHandler().player.getServerWorld()   // or Minecraft on client
    world.addScheduledTask(() -> {
        if (world.isBlockLoaded(msg.pos)) {                  // guard loaded chunks
            ... touch tile/world state ...
        }
    })
    return null                                              // no reply
```

This guarantees world mutation happens on the main server/client thread, and the `isBlockLoaded` /
null-world guards (`MessageTileSync.java:61`, `:79`) prevent crashes when the chunk or world is gone.

## How to extend

1. Create a class implementing `IMessage` in `common/util/network/` with `toBytes`/`fromBytes` and a
   no-arg constructor.
2. Add a nested `static class Handler implements IMessageHandler<YourMessage, IMessage>` whose
   `onMessage` defers work via `addScheduledTask` and returns `null`. Use `HandlerServer` /
   `HandlerClient` if the packet is bidirectional.
3. Register it in `ImmersiveEngineering.init` (`ImmersiveEngineering.java:121`) with
   `packetHandler.registerMessage(...)` and `messageId++`, picking the correct `Side`.
4. Send with the appropriate `packetHandler.sendTo*` method. Prefer targeted sends (`sendTo`,
   `sendToAllAround`) over `sendToAll`/`sendToDimension` for anything that can fire frequently.
