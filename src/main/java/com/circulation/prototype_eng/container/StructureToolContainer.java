package com.circulation.prototype_eng.container;

import ae2.container.AEBaseContainer;
import ae2.container.guisync.GuiSync;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.item.StructureToolItem;
import com.circulation.prototype_eng.localization.StructureToolText;
import com.circulation.prototype_eng.structure.reload.ServerStructureReloads;
import com.circulation.prototype_eng.structure.tool.BuilderState;
import com.circulation.prototype_eng.structure.tool.ScannerState;
import com.circulation.prototype_eng.structure.tool.StructureBuildTasks;
import com.circulation.prototype_eng.structure.tool.StructureToolExport;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTException;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.Loader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/** AE window actions are bound to the exact tool stack and world that opened the screen. */
public final class StructureToolContainer extends AEBaseContainer {
    public static final int GUI_ID = 0x504554;
    private final int slot;
    private final ItemStack tool;
    private final World world;
    private final boolean builder;
    @GuiSync(0)
    public String state = "{}";
    private boolean closed;
    private boolean exportQueued;
    private StructureToolExport.Capture export;
    private NBTTagCompound exportSettings;
    private String decoded = "";
    private NBTTagCompound snapshot = new NBTTagCompound();

    private StructureToolContainer(InventoryPlayer inventory, int slot) {
        super(inventory, null);
        this.slot = slot;
        tool = inventory.getStackInSlot(slot);
        world = inventory.player.world;
        builder = ((StructureToolItem) tool.getItem()).builder();
        setGuiTitle((builder ? StructureToolText.BuilderTitle : StructureToolText.ScannerTitle).text());
        registerClientAction("structure_tool", Action.class, this::accept);
        if (!world.isRemote) {
            if (!builder) autoInitialize();
            synchronize();
        }
    }

    public static @Nullable StructureToolContainer open(InventoryPlayer inventory, int slot) {
        if (slot < 0 || slot >= inventory.getSizeInventory() || !(inventory.getStackInSlot(slot).getItem() instanceof StructureToolItem))
            return null;
        return new StructureToolContainer(inventory, slot);
    }

    public boolean builder() {
        return builder;
    }

    public NBTTagCompound snapshot() {
        if (!decoded.equals(state)) {
            decoded = state;
            try {
                snapshot = JsonToNBT.getTagFromJson(state);
            } catch (NBTException invalid) {
                snapshot = new NBTTagCompound();
            }
        }
        return snapshot;
    }

    private NBTTagCompound data() {
        return builder ? BuilderState.data(tool) : ScannerState.data(tool);
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return !closed && player == getPlayer() && player.world == world && player.isEntityAlive()
            && player.inventory.getStackInSlot(slot) == tool && !tool.isEmpty();
    }

    private boolean active() {
        return !world.isRemote && getPlayer().openContainer == this && canInteractWith(getPlayer());
    }

    public void action(String kind) {
        action(kind, "", "");
    }

    public void action(String kind, String key, String value) {
        sendClientAction("structure_tool", new Action(kind, key, value));
    }

    private void accept(Action action) {
        if (!active() || action == null || action.kind == null || action.key == null || action.value == null || export != null || exportQueued)
            return;
        var tag = data();
        if (builder) {
            if (action.kind.equals("task")) StructureBuildTasks.toggle(getPlayer(), slot, tool);
            else if (action.kind.equals("material") && action.key.length() <= 8192 && action.value.length() <= 8192) {
                var server = getPlayer().getServer();
                if (server == null) return;
                var view = ServerStructureReloads.view(server, MachineTypeRegistry.INSTANCE.structureCatalog());
                if (view != null) for (var entry : view.entries().values())
                    for (var display : entry.displays().values()) {
                        if (!display.key().equals(action.key)) continue;
                        for (var option : display.options())
                            if (BuilderState.optionKey(option).equals(action.value))
                                BuilderState.choose(tag, action.key, action.value);
                    }
            }
        } else switch (action.kind) {
            case "edit" -> {
                for (var field : ScannerState.Field.values())
                    if (field.name().equals(action.key) && !ScannerState.edit(tag, field, action.value)) return;
            }
            case "clear" -> ScannerState.clear(tag);
            case "reset" -> ScannerState.reset(tag, tool.getDisplayName());
            case "originReset" -> {
                var origin = ScannerState.origin(tag);
                if (origin != null) {
                    ScannerState.origin(tag, origin);
                    tag.setBoolean("exportOriginAuto", false);
                }
            }
            case "originSet" -> {
                ScannerState.origin(tag, new BlockPos(tag.getInteger(ScannerState.Field.X.key), tag.getInteger(ScannerState.Field.Y.key), tag.getInteger(ScannerState.Field.Z.key)));
                tag.setBoolean("exportOriginAuto", false);
            }
            case "orientationReset" -> ScannerState.resetOrientation(tag);
            case "facing", "rotation" -> {
                if (action.value.matches("[0-5]")) {
                    int value = Integer.parseInt(action.value);
                    if (action.kind.equals("facing") || value < 4) {
                        tag.setInteger(action.kind.equals("facing") ? "previewFacing" : "previewRot", value);
                        tag.setBoolean("previewAuto", false);
                    }
                }
            }
            case "export" -> startExport();
            default -> {
            }
        }
        synchronize();
    }

    private void autoInitialize() {
        autoInitialize(() -> {
        });
    }

    private void autoInitialize(Runnable after) {
        var tag = data();
        var box = ScannerState.box(tag);
        if (box == null || tag.getInteger("dimension") != world.provider.getDimension() || box.exceeds(StructureToolExport.MAX_VOLUME)
            || !tag.getBoolean("exportOriginAuto") && !tag.getBoolean("previewAuto")) {
            after.run();
            return;
        }
        var captured = tag.copy();
        for (var pos : BlockPos.getAllInBoxMutable(box.min(), box.max())) {
            if (world.isBlockLoaded(pos) && world.getTileEntity(pos) instanceof MachineControllerHost host) {
                host.readGuiAtBoundary(() -> {
                    if (!canInteractWith(getPlayer()) || !data().equals(captured) || host.blockEntity().getWorld() != world
                        || world.getTileEntity(host.blockEntity().getPos()) != host) {
                        exportQueued = false;
                        return;
                    }
                    var current = data();
                    if (current.getBoolean("exportOriginAuto"))
                        ScannerState.origin(current, host.blockEntity().getPos());
                    if (current.getBoolean("previewAuto")) ScannerState.orientation(current, host.orientation());
                    synchronize();
                    after.run();
                });
                return;
            }
        }
        after.run();
    }

    private void startExport() {
        exportQueued = true;
        autoInitialize(() -> {
            exportQueued = false;
            if (!active()) return;
            var tag = data();
            var box = ScannerState.box(tag);
            var origin = ScannerState.origin(tag);
            if (box == null || origin == null || tag.getInteger("dimension") != world.provider.getDimension()) {
                getPlayer().sendMessage(StructureToolText.NeedSelection.text());
                return;
            }
            exportSettings = tag.copy();
            export = new StructureToolExport.Capture(world, box, origin, ScannerState.worldToTemplate(tag), tag.getBoolean(ScannerState.Field.NBT.key), false);
        });
    }

    private void synchronize() {
        state = data().toString();
        getPlayer().inventory.markDirty();
    }

    @Override
    public void detectAndSendChanges() {
        if (active()) {
            if (export != null) try {
                if (export.advance(4096)) {
                    var path = Loader.instance().getConfigDir().toPath().resolve("prototype_eng/structures/scanned");
                    var file = StructureToolExport.save(path, exportSettings.getString(ScannerState.Field.ID.key),
                        StructureToolExport.json(exportSettings.getString(ScannerState.Field.ID.key), exportSettings.getString(ScannerState.Field.NAME.key), export.cells()));
                    ScannerState.clear(data());
                    export = null;
                    getPlayer().sendMessage(StructureToolText.Exported.text(file.toString()));
                }
            } catch (IOException | IllegalArgumentException | ArithmeticException failure) {
                export = null;
                getPlayer().sendMessage(StructureToolText.ExportFailed.text(failure.getMessage()));
            }
            synchronize();
        }
        super.detectAndSendChanges();
    }

    @Override
    public void onContainerClosed(@NotNull EntityPlayer player) {
        closed = true;
        export = null;
        super.onContainerClosed(player);
    }

    public static final class Action {
        String kind, key, value;

        @SuppressWarnings("unused")
        public Action() {
        }

        public Action(String kind, String key, String value) {
            this.kind = kind;
            this.key = key;
            this.value = value;
        }
    }
}
