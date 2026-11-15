package com.circulation.prototype_eng.research.reward;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.storage.SaveHandler;
import net.minecraftforge.common.util.FakePlayer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

public final class RewardDeliveryRecovery {
    private RewardDeliveryRecovery() {
    }

    public static void reconcileDeliveryJournal(EntityPlayer player) {
        if (player.world.isRemote || !(player instanceof EntityPlayerMP actual) || player instanceof FakePlayer) return;
        var server = actual.server;
        if (!server.isCallingFromMinecraftThread())
            throw new IllegalStateException("Reward player loading requires the server thread");
        var id = EntityPlayer.getUUID(player.getGameProfile());
        if (!id.equals(player.getUniqueID())) {
            throw new IllegalArgumentException("Loaded reward player identity differs from the authenticated profile");
        }
        var loaded = RewardDeliveryJournal.read(player.getEntityData(), id);
        var overworld = server.getWorld(0);
        if (!(overworld.getSaveHandler() instanceof SaveHandler handler) || handler.getPlayerNBTManager() != handler) {
            // The delivery adapter also rejects this writer; it cannot establish a new proof.
            return;
        }
        var directory = handler.getWorldDirectory().toPath();
        var playerFile = directory.resolve("playerdata").resolve(id + ".dat");
        var hostFile = directory.resolve("level.dat");
        try {
            var saved = Files.exists(playerFile) ? RewardDeliveryPersistence.readPlayerJournal(playerFile, id)
                : RewardDeliveryJournal.read(new NBTTagCompound(), id);
            RewardDeliveryJournal host = null;
            if (server.isSinglePlayer() && player.getName().equals(server.getServerOwner())) {
                host = Files.exists(hostFile) ? RewardDeliveryPersistence.readHostJournal(hostFile, id)
                    : RewardDeliveryJournal.read(new NBTTagCompound(), id);
            }
            var reconciled = RewardDeliveryJournal.reconcile(loaded, saved, host);
            if (!reconciled.snapshot().equals(loaded.snapshot())) reconciled.writeTo(player.getEntityData());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

}
