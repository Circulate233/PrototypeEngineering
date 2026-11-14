package com.circulation.prototype_eng.machine.render;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.machine.ControllerDisplayData;
import com.circulation.prototype_eng.machine.MachineControllerBlockEntity;
import com.circulation.prototype_eng.scheduler.ServerTaskSchedulers;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkWatchEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Native TE channel only. Watch replay revalidates the original chunk, tile and player connection.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
@SuppressWarnings("resource") // The FML server session owns the borrowed scheduler, never this packet helper.
public final class ControllerRenderSync {
    private ControllerRenderSync() {
    }

    public static NBTTagCompound tag(MachineControllerBlockEntity tile, ControllerRenderChunks.Part part) {
        return tag(tile, part, false, null);
    }

    private static NBTTagCompound tag(MachineControllerBlockEntity tile, ControllerRenderChunks.Part part, boolean ordinary,
                                      @Nullable ControllerDisplayData dataDisplay) {
        var position = tile.getPos();
        var tag = new NBTTagCompound();
        tag.setInteger("x", position.getX());
        tag.setInteger("y", position.getY());
        tag.setInteger("z", position.getZ());
        var display = ordinary ? Objects.requireNonNull(dataDisplay) : part.display();
        tag.setTag("Display", display.encode());
        tag.setTag(ordinary ? "Data" : "Render", ordinary ? dataTag(part, display) : part.encode());
        if (ordinary) tag.setBoolean("DataOnly", true);
        return tag;
    }

    /**
     * Data's revision clock is independent of render-only metadata updates.
     */
    public static NBTTagCompound dataTag(ControllerRenderChunks.Part part) {
        return dataTag(part, part.display());
    }

    public static NBTTagCompound dataTag(ControllerRenderChunks.Part part, ControllerDisplayData display) {
        if (display.generation() != part.display().generation() || !display.type().equals(part.display().type()))
            throw new IllegalArgumentException("Data replay header belongs to a different attachment");
        var tag = new NBTTagCompound();
        tag.setTag("Display", display.encode());
        tag.setTag("Payload", part.encode());
        return tag;
    }

    /**
     * First part is delivered by the original notifyBlockUpdate/getUpdatePacket path.
     */
    public static void remaining(MachineControllerBlockEntity tile, List<ControllerRenderChunks.Part> parts) {
        remaining(tile, parts, false);
    }

    public static void remainingData(MachineControllerBlockEntity tile, List<ControllerRenderChunks.Part> parts) {
        remaining(tile, parts, true);
    }

    private static void remaining(MachineControllerBlockEntity tile, List<ControllerRenderChunks.Part> parts, boolean ordinary) {
        if (!(tile.getWorld() instanceof WorldServer world))
            throw new IllegalStateException("Render sync requires a server world");
        requireOwner(world);
        if (parts.size() <= 1 || !attached(world, tile) || currentParts(tile, ordinary) != parts) return;
        var position = tile.getPos();
        var entry = world.getPlayerChunkMap().getEntry(position.getX() >> 4, position.getZ() >> 4);
        if (entry == null) return;
        var elements = entry.getWatchingPlayers();
        for (int index2 = 0; index2 < elements.size(); index2++) {
            var player = elements.get(index2);
            send(world, tile, parts, player, player.connection, 1, ordinary, null);
        }
    }

    @SubscribeEvent
    public static void watching(ChunkWatchEvent.Watch event) {
        var player = event.getPlayer();
        var chunk = event.getChunkInstance();
        if (chunk == null || !(player.getEntityWorld() instanceof WorldServer world) || chunk.getWorld() != world)
            return;
        requireOwner(world);
        var connection = player.connection;
        var tiles = new ObjectArrayList<MachineControllerBlockEntity>();
        for (var tile : chunk.getTileEntityMap().values())
            if (tile instanceof MachineControllerBlockEntity controller) tiles.add(controller);
        var server = Objects.requireNonNull(world.getMinecraftServer());
        ServerTaskSchedulers.forServer(server).runAtBoundary(() -> {
            if (!loaded(world, chunk)) return;
            for (int index1 = 0; index1 < tiles.size(); index1++) {
                var tile = tiles.get(index1);
                if (!currentWatcher(world, tile, player, connection)) continue;
                var parts = tile.serverRenderParts();
                var dataParts = tile.serverDataParts();
                var replayDisplay = tile.refreshDataReplayAtBoundary();
                send(world, tile, parts, player, connection, 0, false, null);
                if (replayDisplay != null) send(world, tile, dataParts, player, connection, 0, true, replayDisplay);
            }
        });
    }

    private static void send(WorldServer world, MachineControllerBlockEntity tile, List<ControllerRenderChunks.Part> parts,
                             EntityPlayerMP player, NetHandlerPlayServer connection, int first, boolean ordinary,
                             @Nullable ControllerDisplayData replayDisplay) {
        var canonicalDisplay = ordinary ? tile.serverDataDisplay() : null;
        if (ordinary && canonicalDisplay == null) return;
        var dataDisplay = replayDisplay == null ? canonicalDisplay : replayDisplay;
        if (!currentWatcher(world, tile, player, connection) || currentParts(tile, ordinary) != parts) return;
        var pos = tile.getPos();
        for (int index = first; index < parts.size(); index++) {
            connection.sendPacket(new SPacketUpdateTileEntity(pos, 0, tag(tile, parts.get(index), ordinary, dataDisplay)));
        }
    }

    private static boolean currentWatcher(WorldServer world, MachineControllerBlockEntity tile,
                                          EntityPlayerMP player, NetHandlerPlayServer connection) {
        var server = Objects.requireNonNull(world.getMinecraftServer());
        var position = tile.getPos();
        return attached(world, tile) && player.getEntityWorld() == world && player.connection == connection &&
            connection.netManager.isChannelOpen() && server.getPlayerList().getPlayerByUUID(player.getUniqueID()) == player &&
            world.getPlayerChunkMap().isPlayerWatchingChunk(player, position.getX() >> 4, position.getZ() >> 4);
    }

    private static List<ControllerRenderChunks.Part> currentParts(MachineControllerBlockEntity tile, boolean ordinary) {
        return ordinary ? tile.serverDataParts() : tile.serverRenderParts();
    }

    private static boolean attached(WorldServer world, MachineControllerBlockEntity tile) {
        var pos = tile.getPos();
        return tile.getWorld() == world && !tile.isInvalid() && world.isBlockLoaded(pos, false) && world.getTileEntity(pos) == tile;
    }

    private static boolean loaded(WorldServer world, Chunk chunk) {
        return world.getChunkProvider().getLoadedChunk(chunk.x, chunk.z) == chunk;
    }

    private static void requireOwner(WorldServer world) {
        if (!Objects.requireNonNull(world.getMinecraftServer()).isCallingFromMinecraftThread())
            throw new IllegalStateException("Native controller render sync requires its server owner");
    }
}
