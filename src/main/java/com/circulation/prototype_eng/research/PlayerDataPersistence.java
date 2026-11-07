package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.persistence.StrictNbt;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.storage.SaveHandler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared vanilla player-file barrier; callers verify their own exact material or delivery evidence.
 */
public final class PlayerDataPersistence {
    private PlayerDataPersistence() {
    }

    public static void persist(EntityPlayerMP player, Runnable ownerCheck, Verification verifySaved) {
        Objects.requireNonNull(ownerCheck).run();
        Objects.requireNonNull(verifySaved);
        var server = Objects.requireNonNull(player.getServer());
        var overworld = Objects.requireNonNull(server.getWorld(0));
        if (!(overworld.getSaveHandler() instanceof SaveHandler handler) || handler.getPlayerNBTManager() != handler) {
            throw new IllegalStateException("Research persistence requires the authoritative vanilla player save handler");
        }
        boolean host = server.isSinglePlayer() && player.getName().equals(server.getServerOwner());
        var playerId = player.getUniqueID();
        var directory = handler.getWorldDirectory().toPath();
        if (host) server.getPlayerList().saveAllPlayerData();
        else handler.getPlayerNBTManager().writePlayerData(player);
        ownerCheck.run();
        try {
            verifySaved.verify(readPlayer(directory.resolve("playerdata").resolve(playerId + ".dat"), playerId, false));
            if (host) {
                var data = Objects.requireNonNull(server.getPlayerList().getHostPlayerData(), "Integrated host player snapshot");
                handler.saveWorldInfoWithPlayer(overworld.getWorldInfo(), data);
                ownerCheck.run();
                verifySaved.verify(readPlayer(directory.resolve("level.dat"), playerId, true));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        ownerCheck.run();
    }

    /**
     * Missing files, missing host data and foreign player identities never prove a completed write.
     */
    public static NBTTagCompound readPlayer(Path file, UUID player, boolean host) throws IOException {
        NBTTagCompound saved;
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            channel.force(true);
            saved = CompressedStreamTools.readCompressed(Channels.newInputStream(channel));
        }
        if (host) saved = StrictNbt.requireCompound(StrictNbt.requireCompound(saved, "Data"), "Player");
        if (!saved.hasUniqueId("UUID") || !Objects.requireNonNull(player).equals(saved.getUniqueId("UUID"))) {
            throw new IOException("Saved research player identity differs");
        }
        return saved;
    }

    @FunctionalInterface
    public interface Verification {
        void verify(NBTTagCompound savedPlayer) throws IOException;
    }
}
