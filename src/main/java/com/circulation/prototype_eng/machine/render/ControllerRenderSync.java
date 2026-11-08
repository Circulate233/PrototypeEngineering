package com.circulation.prototype_eng.machine.render;

import net.minecraft.network.NetHandlerPlayServer;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.machine.MachineControllerBlockEntity;
import com.circulation.prototype_eng.scheduler.ServerTaskSchedulers;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.ChunkWatchEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.List;
import java.util.Objects;

/** Native TE channel only. Watch replay revalidates the original chunk, tile and player connection. */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
@SuppressWarnings("resource") // The FML server session owns the borrowed scheduler, never this packet helper.
public final class ControllerRenderSync {
    private ControllerRenderSync() {
    }

    public static NBTTagCompound tag(MachineControllerBlockEntity tile, ControllerRenderChunks.Part part) {
        var position = tile.getPos();
        var tag = new NBTTagCompound();
        tag.setInteger("x", position.getX());
        tag.setInteger("y", position.getY());
        tag.setInteger("z", position.getZ());
        tag.setTag("Display", part.display().encode());
        tag.setTag("Render", part.encode());
        return tag;
    }

    /** First part is delivered by the original notifyBlockUpdate/getUpdatePacket path. */
    public static void remaining(MachineControllerBlockEntity tile, List<ControllerRenderChunks.Part> parts) {
        if (!(tile.getWorld() instanceof WorldServer world))
            throw new IllegalStateException("Render sync requires a server world");
        requireOwner(world);
        if (parts.size() <= 1 || !attached(world, tile) || tile.serverRenderParts() != parts) return;
        var position = tile.getPos();
        var entry = world.getPlayerChunkMap().getEntry(position.getX() >> 4, position.getZ() >> 4);
        if (entry == null) return;
        for (var player : new ObjectArrayList<>(entry.getWatchingPlayers()))
            send(world, tile, parts, player, player.connection, 1);
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
            for (var tile : tiles) {
                if (!attached(world, tile)) continue;
                var parts = tile.serverRenderParts();
                send(world, tile, parts, player, connection, 0);
            }
        });
    }

    private static void send(WorldServer world, MachineControllerBlockEntity tile, List<ControllerRenderChunks.Part> parts,
                             EntityPlayerMP player, NetHandlerPlayServer connection, int first) {
        var server = Objects.requireNonNull(world.getMinecraftServer());
        for (int index = first; index < parts.size(); index++) {
            var pos = tile.getPos();
            if (!attached(world, tile) || tile.serverRenderParts() != parts || player.getEntityWorld() != world || player.connection != connection ||
                !connection.netManager.isChannelOpen() || server.getPlayerList().getPlayerByUUID(player.getUniqueID()) != player ||
                !world.getPlayerChunkMap().isPlayerWatchingChunk(player, pos.getX() >> 4, pos.getZ() >> 4)) return;
            connection.sendPacket(new SPacketUpdateTileEntity(pos, 0, tag(tile, parts.get(index))));
        }
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
