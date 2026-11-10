package com.circulation.prototype_eng.api.client.structure;

import ae2.api.orientation.BlockOrientation;
import ae2.client.gui.AEBaseGui;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.client.gui.StructurePreviewGui;
import com.circulation.prototype_eng.client.preview.PreviewScreenTransitions;
import com.circulation.prototype_eng.client.preview.StructurePreviewPanel;
import com.circulation.prototype_eng.client.preview.StructurePreviewScreensAnchor;
import com.circulation.prototype_eng.client.preview.StructurePreviewView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Java opens the same authoritative catalog viewer used by machines, commands and HEI. */
public final class StructurePreviewScreens {
    private StructurePreviewScreens() {
    }

    public static boolean open(ResourceLocation id, StructurePreviewSettings settings) {
        return open(id, settings, null);
    }

    /** Java callers may supply the exact declaration needed for forming model preview. */
    public static boolean open(ResourceLocation id, StructurePreviewSettings settings, @Nullable MachineType machine) {
        var mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null || !mc.player.isEntityAlive() || mc.player.getHealth() <= 0)
            return false;
        if (mc.currentScreen instanceof GuiContainer container && mc.player.openContainer != container.inventorySlots)
            return false;
        var service = StructurePreviews.client();
        if (service.request(id, settings) == null) return false;
        var view = new StructurePreviewView(service, id, settings, machine);
        view.anchor(StructurePreviewScreensAnchor.current());
        var navigation = PreviewScreenTransitions.capture(service);
        var screen = mc.currentScreen instanceof AEBaseGui<?> parent ? new StructurePreviewGui(parent, view, navigation)
            : new StructurePreviewGui(mc.player.inventory, view, navigation);
        return navigation.open(screen);
    }

    public static boolean open(MachineType machine) {
        return open(machine, new StructurePreviewSettings(BlockOrientation.NORTH_UP));
    }

    public static boolean open(MachineType machine, StructurePreviewSettings settings) {
        return open(machine.structureId(), settings, machine);
    }

    public static boolean open(AEBaseGui<?> parent, MachineType machine, BlockOrientation orientation) {
        var mc = Minecraft.getMinecraft();
        if (mc.currentScreen != parent || mc.player == null || mc.world == null || !mc.player.isEntityAlive() || mc.player.getHealth() <= 0 || mc.player.openContainer != parent.getContainer())
            return false;
        var service = StructurePreviews.client();
        var settings = new StructurePreviewSettings(orientation);
        if (service.request(machine.structureId(), settings) == null) return false;
        var view = new StructurePreviewView(service, machine.structureId(), settings, machine);
        view.anchor(parent.getContainer().getTarget() instanceof TileEntity tile ? tile.getPos() : StructurePreviewScreensAnchor.current());
        var navigation = PreviewScreenTransitions.capture(service);
        return navigation.open(new StructurePreviewGui(parent, view, navigation));
    }

    /** Independent custom pool pages can compose this concrete panel without extending the default page. */
    @SuppressWarnings("unused") // Public composition entry for addon recipe pages.
    public static @Nullable StructurePreviewPanel panel(MachineType machine, StructurePreviewSettings settings) {
        var service = StructurePreviews.client();
        if (service.request(machine.structureId(), settings) == null) return null;
        return new StructurePreviewPanel(new StructurePreviewView(service, machine.structureId(), settings, machine));
    }
}
