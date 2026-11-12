package com.circulation.prototype_eng.parallel;

import ae2.core.localization.LocalizationEnum;
import ae2.tile.AEBaseTile;
import ae2.util.SettingsFrom;
import com.circulation.prototype_eng.api.gui.ParallelControllerGuiHost;
import com.circulation.prototype_eng.api.machine.ParallelController;
import com.circulation.prototype_eng.api.machine.ParallelControllerRegistry;
import com.circulation.prototype_eng.api.machine.ParallelControllerType;
import com.circulation.prototype_eng.api.machine.ParallelControllers;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.structure.StructureComponentProvider;
import com.circulation.prototype_eng.block.ParallelControllerBlock;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Saved setting belongs to the physical controller; workers only observe immutable snapshots. */
@SuppressWarnings("NonAtomicOperationOnVolatileField") // The world owner serializes writes; the volatile snapshot only publishes to readers.
public class ParallelControllerBlockEntity extends AEBaseTile implements ParallelController, ParallelControllerGuiHost, StructureComponentProvider {
    @Nullable private ParallelControllerType type;
    private boolean pending = true;
    private volatile Snapshot state = new Snapshot(1, 1, 0, false);

    @SuppressWarnings("unused") // AE/Forge calls the public no-argument constructor through its registered class token.
    public ParallelControllerBlockEntity() {}

    public ParallelControllerBlockEntity(ParallelControllerType type) {
        configure(type, type.maximum());
    }

    private void configure(ParallelControllerType nextType, int selected) {
        type = Objects.requireNonNull(nextType);
        pending = false;
        state = new Snapshot(Math.clamp(selected, 0, type.maximum()), type.maximum(), Math.incrementExact(state.revision()), !isInvalid());
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (pending && world != null && world.getTileEntity(pos) == this
            && world.getBlockState(pos).getBlock() instanceof ParallelControllerBlock block)
            configure(block.type(), block.type().maximum());
        if (!pending) state = new Snapshot(state.selected(), state.maximum(), Math.incrementExact(state.revision()), true);
    }

    @Override
    public Snapshot snapshot() {
        return state;
    }

    @Override
    public void setSelectedParallelism(int amount) {
        if (amount < 0 || amount > state.maximum()) throw new IllegalArgumentException("Parallelism is outside this controller's range");
        if (world != null && (world.isRemote || !Objects.requireNonNull(world.getMinecraftServer()).isCallingFromMinecraftThread()))
            throw new IllegalStateException("Controller changes require the server thread");
        if (pending || !state.valid()) throw new IllegalStateException("Controller is not initialized");
        if (amount != state.selected()) {
            state = new Snapshot(amount, state.maximum(), Math.incrementExact(state.revision()), true);
            markDirty();
            markForUpdate();
        }
    }

    @Override
    protected void setRemoved() {
        state = new Snapshot(state.selected(), state.maximum(), Math.incrementExact(state.revision()), false);
        super.setRemoved();
    }

    @Override
    protected void onChunkUnloaded() {
        state = new Snapshot(state.selected(), state.maximum(), Math.incrementExact(state.revision()), false);
        super.onChunkUnloaded();
    }

    @Override
    protected void clearRemoved() {
        super.clearRemoved();
        if (!pending) state = new Snapshot(state.selected(), state.maximum(), Math.incrementExact(state.revision()), true);
    }

    @Override
    public void exportSettings(SettingsFrom mode, NBTTagCompound output) {
        super.exportSettings(mode, output);
        if (!pending) output.setInteger("SelectedParallelism", state.selected());
    }

    @Override
    public void importSettings(SettingsFrom mode, NBTTagCompound input, @Nullable EntityPlayer player) {
        super.importSettings(mode, input, player);
        if (pending && world != null && world.getBlockState(pos).getBlock() instanceof ParallelControllerBlock block)
            configure(block.type(), block.type().maximum());
        if (input.hasKey("SelectedParallelism")) {
            int value = StrictNbt.requireInt(input, "SelectedParallelism");
            if (value < 0) throw new IllegalArgumentException("Invalid saved controller setting");
            setSelectedParallelism(Math.min(value, state.maximum()));
        }
    }

    @Override
    public Collection<Entry<?>> createStructureComponents(Context context) {
        return List.of(new Entry<>(ParallelControllers.TYPE, "parallel", this));
    }

    @Override
    public void loadTag(NBTTagCompound data) {
        super.loadTag(data);
        if (data.hasKey("ParallelController")) {
            var tag = StrictNbt.requireCompound(data, "ParallelController");
            StrictNbt.requireKeys(tag, "Type", "Selected");
            var loadedType = ParallelControllerRegistry.INSTANCE.require(new ResourceLocation(StrictNbt.requireString(tag, "Type")));
            int selected = StrictNbt.requireInt(tag, "Selected");
            if (selected < 0) throw new IllegalArgumentException("Invalid saved parallelism");
            configure(loadedType, selected);
        }
    }

    @Override
    public void saveAdditional(NBTTagCompound data) {
        super.saveAdditional(data);
        if (type != null) {
            var tag = new NBTTagCompound();
            tag.setString("Type", type.id().toString());
            tag.setInteger("Selected", state.selected());
            data.setTag("ParallelController", tag);
        }
    }

    @Override
    protected void writeToStream(ByteBuf data) {
        super.writeToStream(data);
        data.writeInt(state.selected());
        data.writeInt(state.maximum());
    }

    @Override
    protected boolean readFromStream(ByteBuf data) {
        boolean changed = super.readFromStream(data);
        int selected = data.readInt(), maximum = data.readInt();
        var next = new Snapshot(selected, maximum, state.revision(), true);
        changed |= selected != state.selected() || maximum != state.maximum();
        state = next;
        return changed;
    }

    @Override
    public AEBaseTile blockEntity() {
        return this;
    }

    @Override
    public ParallelController controller() {
        return this;
    }

    @Override
    public LocalizationEnum displayName() {
        if (type != null) return type.displayName();
        if (world != null && world.getBlockState(pos).getBlock() instanceof ParallelControllerBlock block)
            return block.type().displayName();
        throw new IllegalStateException("Controller title is unavailable");
    }
}
