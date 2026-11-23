package com.circulation.prototype_eng.api.client.structure;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import ae2.api.orientation.BlockOrientation;
import ae2.client.gui.AEBaseGui;
import ae2.container.AEBaseContainer;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.client.gui.GuiStructurePreview;
import com.circulation.prototype_eng.client.preview.PreviewScreenTransitions;
import com.circulation.prototype_eng.client.preview.StructurePreviewPanel;
import com.circulation.prototype_eng.client.preview.StructurePreviewScreensAnchor;
import com.circulation.prototype_eng.client.preview.StructurePreviewView;
import com.circulation.prototype_eng.structure.tool.ConstructionTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Java opens the same authoritative catalog viewer used by machines, commands and HEI.
 */
@SideOnly(Side.CLIENT)
public final class StructurePreviewScreens {
    private StructurePreviewScreens() {
    }

    public static boolean open(ResourceLocation id, StructurePreviewSettings settings) {
        return open(id, settings, null);
    }

    /**
     * Java callers may supply the exact declaration needed for forming model preview.
     */
    public static boolean open(ResourceLocation id, StructurePreviewSettings settings, @Nullable MachineType machine) {
        var mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null || !mc.player.isEntityAlive() || mc.player.getHealth() <= 0)
            return false;
        if (mc.currentScreen instanceof GuiContainer container && mc.player.openContainer != container.inventorySlots)
            return false;
        var service = StructurePreviews.client();
        var anchor = StructurePreviewScreensAnchor.current();
        var target = machine == null ? ConstructionTarget.resolve(mc.world, anchor) : null;
        var external = target != null && target.controller() == null && target.structure().equals(id) ? target : null;
        if (service.request(id, settings) == null) return false;
        var view = external == null ? new StructurePreviewView(service, id, settings, machine)
            : new StructurePreviewView(service, id, settings, external.anchorState(), external.name());
        view.anchor(anchor);
        var navigation = PreviewScreenTransitions.capture(service);
        var screen = mc.currentScreen instanceof AEBaseGui<?> parent ? new GuiStructurePreview<>(parent, view, navigation)
            : GuiStructurePreview.standalone(mc.player.inventory, view, navigation);
        return navigation.open(screen);
    }

    public static boolean open(MachineType machine) {
        return open(machine, new StructurePreviewSettings(BlockOrientation.NORTH_UP));
    }

    public static boolean open(MachineType machine, StructurePreviewSettings settings) {
        return open(machine.structureId(), settings, machine);
    }

    public static <C extends AEBaseContainer> boolean open(AEBaseGui<C> parent, MachineType machine, BlockOrientation orientation) {
        var mc = Minecraft.getMinecraft();
        if (mc.currentScreen != parent || mc.player == null || mc.world == null || !mc.player.isEntityAlive() || mc.player.getHealth() <= 0 || mc.player.openContainer != parent.getContainer())
            return false;
        var service = StructurePreviews.client();
        var settings = new StructurePreviewSettings(orientation);
        if (service.request(machine.structureId(), settings) == null) return false;
        var view = new StructurePreviewView(service, machine.structureId(), settings, machine);
        view.anchor(parent.getContainer().getTarget() instanceof TileEntity tile ? tile.getPos() : StructurePreviewScreensAnchor.current());
        var navigation = PreviewScreenTransitions.capture(service);
        return navigation.open(new GuiStructurePreview<>(parent, view, navigation));
    }

    /**
     * Independent custom pool pages can compose this concrete panel without extending the default page.
     */
    @SuppressWarnings("unused") // Public composition entry for addon recipe pages.
    public static @Nullable StructurePreviewPanel panel(MachineType machine, StructurePreviewSettings settings) {
        var service = StructurePreviews.client();
        if (service.request(machine.structureId(), settings) == null) return null;
        return new StructurePreviewPanel(new StructurePreviewView(service, machine.structureId(), settings, machine));
    }
}
