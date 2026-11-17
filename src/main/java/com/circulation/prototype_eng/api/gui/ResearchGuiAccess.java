package com.circulation.prototype_eng.api.gui;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.container.ContainerDataCenter;
import com.circulation.prototype_eng.container.ContainerResearchOverview;
import com.circulation.prototype_eng.network.InitNetwork;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;

/**
 * Addons decide which items, keys or screens invoke this access API.
 */
public final class ResearchGuiAccess {
    public static final int PLAYER_GUI = 0x504552;
    public static final int DATABASE_GUI = 0x504553;
    public static final int EDITOR_GUI = 0x504556;

    private ResearchGuiAccess() {
    }

    public static void open(EntityPlayerMP player) {
        player.openGui(PrototypeEngineering.instance, PLAYER_GUI, player.world, 0, 0, 0);
    }

    public static void open(EntityPlayerMP player, MachineControllerHost database) {
        var tile = database.blockEntity();
        var pos = tile.getPos();
        player.openGui(PrototypeEngineering.instance, DATABASE_GUI, tile.getWorld(), pos.getX(), pos.getY(), pos.getZ());
    }

    public static void openEditor(EntityPlayerMP player) {
        if (player.canUseCommand(2, "pe_research_edit"))
            player.openGui(PrototypeEngineering.instance, EDITOR_GUI, player.world, 0, 0, 0);
    }

    /**
     * A client entry point requests the same server-owned player container; the library binds no key.
     */
    @SuppressWarnings("unused")
    @SideOnly(Side.CLIENT)
    public static void requestOpen() {
        InitNetwork.requestResearchOverview();
    }

    public static void returnToDatabase(EntityPlayerMP player, MachineControllerHost database, @Nullable ResourceLocation selected) {
        var tile = database.blockEntity();
        var pos = tile.getPos();
        player.openGui(PrototypeEngineering.instance, 0, tile.getWorld(), pos.getX(), pos.getY(), pos.getZ());
        if (selected != null && player.openContainer instanceof ContainerDataCenter container)
            container.selectFromOverview(selected);
    }

    public static @Nullable Object serverElement(int id, EntityPlayer player,
                                                 World world, int x, int y, int z) {
        if (id == PLAYER_GUI) return new ContainerResearchOverview(player.inventory);
        if (id == EDITOR_GUI)
            return world.isRemote || player.canUseCommand(2, "pe_research_edit") ? new ContainerResearchOverview(player.inventory, true) : null;
        if (id != DATABASE_GUI) return null;
        var pos = new BlockPos(x, y, z);
        if (!world.isBlockLoaded(pos)) return null;
        return world.getTileEntity(pos) instanceof MachineControllerHost database ? new ContainerResearchOverview(player.inventory, database) : null;
    }
}
