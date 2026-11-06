package com.circulation.prototype_eng.container;

import ae2.container.AEBaseContainer;
import ae2.container.guisync.GuiSync;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.energy.EnergyHatchBlockEntity;
import com.circulation.prototype_eng.localization.GuiText;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;

public final class EnergyHatchContainer extends AEBaseContainer {
    private static final String INPUT_RATE = "inputRate";
    private static final String OUTPUT_RATE = "outputRate";
    private static final String AUTO_INPUT = "autoInput";
    private static final String AUTO_OUTPUT = "autoOutput";
    private final EnergyHatchBlockEntity tile;
    @GuiSync(0) public long energy;
    @GuiSync(1) public long capacity;
    @GuiSync(2) public long inputRate;
    @GuiSync(3) public long outputRate;
    @GuiSync(4) public boolean autoInput;
    @GuiSync(5) public boolean autoOutput;

    public EnergyHatchContainer(InventoryPlayer player, EnergyHatchBlockEntity tile) {
        super(player, tile);
        this.tile = tile;
        syncFields();
        registerClientAction(INPUT_RATE, Long.class, 20, value -> {
            if (canInteractWith(getPlayer()) && tile.mode().hasInput()) { tile.setMaxInputRate(value); }
        });
        registerClientAction(OUTPUT_RATE, Long.class, 20, value -> {
            if (canInteractWith(getPlayer()) && tile.mode().hasOutput()) { tile.setMaxOutputRate(value); }
        });
        registerClientAction(AUTO_INPUT, () -> {
            if (canInteractWith(getPlayer()) && tile.mode().hasInput()) { tile.setAutoInput(!tile.autoInput()); }
        });
        registerClientAction(AUTO_OUTPUT, () -> {
            if (canInteractWith(getPlayer()) && tile.mode().hasOutput()) { tile.setAutoOutput(!tile.autoOutput()); }
        });
        addPlayerInventorySlots(8, 162);
        setGuiTitle(GuiText.energyHatch(tile.mode()).text(tile.tier().level()));
    }

    public HatchMode mode() { return tile.mode(); }
    public void setInputRate(long value) { sendClientAction(INPUT_RATE, value); }
    public void setOutputRate(long value) { sendClientAction(OUTPUT_RATE, value); }
    public void toggleInput() { sendClientAction(AUTO_INPUT); }
    public void toggleOutput() { sendClientAction(AUTO_OUTPUT); }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return !tile.isInvalid() && tile.storage().isValid() && super.canInteractWith(player);
    }

    private void syncFields() {
        energy = tile.storage().storedEnergy();
        capacity = tile.storage().capacity();
        inputRate = tile.maxInputRate();
        outputRate = tile.maxOutputRate();
        autoInput = tile.autoInput();
        autoOutput = tile.autoOutput();
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) { syncFields(); }
        super.broadcastChanges();
    }
}
