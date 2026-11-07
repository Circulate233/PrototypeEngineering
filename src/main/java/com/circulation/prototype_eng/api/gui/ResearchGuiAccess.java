package com.circulation.prototype_eng.api.gui;

import com.circulation.prototype_eng.PrototypeEngineering;
import net.minecraft.entity.player.EntityPlayerMP;

/** A downstream item, block or command can open the library's research screen on the server. */
public final class ResearchGuiAccess {
    public static final int GUI_ID = 1;
    private ResearchGuiAccess() { }

    @SuppressWarnings("unused") // Public library entry; no gameplay item or machine is registered for it.
    public static void open(EntityPlayerMP player) {
        var server = player.getServer();
        if (server == null || !server.isCallingFromMinecraftThread()) throw new IllegalStateException("Research GUI must be opened on the server thread");
        if (server.getPlayerList().getPlayerByUUID(player.getUniqueID()) != player || !player.isEntityAlive()) return;
        player.openGui(PrototypeEngineering.instance, GUI_ID, player.world, 0, 0, 0);
    }
}
