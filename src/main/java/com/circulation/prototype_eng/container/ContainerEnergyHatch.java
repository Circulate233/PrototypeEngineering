package com.circulation.prototype_eng.container;

import ae2.container.AEBaseContainer;
import ae2.container.guisync.GuiSync;
import com.circulation.prototype_eng.api.energy.EnergyHatch;
import com.circulation.prototype_eng.api.gui.EnergyHatchGuiHost;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.localization.HatchGuiTitle;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import org.jetbrains.annotations.Nullable;

public final class ContainerEnergyHatch extends AEBaseContainer {
    private static final String INPUT_RATE = "inputRate";
    private static final String OUTPUT_RATE = "outputRate";
    private static final String AUTO_INPUT = "autoInput";
    private static final String AUTO_OUTPUT = "autoOutput";
    private final EnergyHatchGuiHost tile;
    private final EnergyHatch storage;
    private final @Nullable EnergyHatch inputStorage;
    private final @Nullable EnergyHatch outputStorage;
    @GuiSync(0)
    public long energy;
    @GuiSync(1)
    public long capacity;
    @GuiSync(2)
    public long inputRate;
    @GuiSync(3)
    public long outputRate;
    @GuiSync(4)
    public boolean autoInput;
    @GuiSync(5)
    public boolean autoOutput;
    @GuiSync(6)
    public long outputEnergy;
    @GuiSync(7)
    public long outputCapacity;

    public ContainerEnergyHatch(InventoryPlayer player, EnergyHatchGuiHost tile) {
        super(player, tile.blockEntity());
        this.tile = tile;
        storage = tile.storage();
        inputStorage = tile.inputStorage();
        outputStorage = tile.outputStorage();
        syncFields();
        registerClientAction(INPUT_RATE, Long.class, 20, value -> {
            if (value != null && value >= -1 && canInteractWith(getPlayer())) {
                tile.setMaxInputRate(value);
            }
        });
        registerClientAction(OUTPUT_RATE, Long.class, 20, value -> {
            if (value != null && value >= -1 && canInteractWith(getPlayer())) {
                tile.setMaxOutputRate(value);
            }
        });
        registerClientAction(AUTO_INPUT, () -> {
            if (canInteractWith(getPlayer()) && tile.mode().hasInput()) {
                tile.setAutoInput(!tile.autoInput());
            }
        });
        registerClientAction(AUTO_OUTPUT, () -> {
            if (canInteractWith(getPlayer()) && tile.mode().hasOutput()) {
                tile.setAutoOutput(!tile.autoOutput());
            }
        });
        addPlayerInventorySlots(27, 84);
        setGuiTitle(HatchGuiTitle.of(true, tile.mode(), tile.tier()).text());
    }

    public HatchMode mode() {
        return tile.mode();
    }

    public HatchTier tier() {
        return tile.tier();
    }

    public void setInputRate(long value) {
        sendClientAction(INPUT_RATE, value);
    }

    public void setOutputRate(long value) {
        sendClientAction(OUTPUT_RATE, value);
    }

    public void toggleInput() {
        sendClientAction(AUTO_INPUT);
    }

    public void toggleOutput() {
        sendClientAction(AUTO_OUTPUT);
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return tile.storage() == storage && storage.isValid()
            && tile.inputStorage() == inputStorage && tile.outputStorage() == outputStorage
            && (inputStorage == null || inputStorage.isValid()) && (outputStorage == null || outputStorage.isValid())
            && super.canInteractWith(player);
    }

    private void syncFields() {
        energy = storage.storedEnergy();
        capacity = storage.capacity();
        outputEnergy = outputStorage == null ? 0 : outputStorage.storedEnergy();
        outputCapacity = outputStorage == null ? 0 : outputStorage.capacity();
        inputRate = tile.maxInputRate();
        outputRate = tile.maxOutputRate();
        autoInput = tile.autoInput();
        autoOutput = tile.autoOutput();
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            syncFields();
        }
        super.broadcastChanges();
    }
}
