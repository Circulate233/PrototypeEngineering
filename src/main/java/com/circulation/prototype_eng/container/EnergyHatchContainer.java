package com.circulation.prototype_eng.container;

import ae2.container.AEBaseContainer;
import ae2.container.guisync.GuiSync;
import com.circulation.prototype_eng.energy.EnergyHatchBlockEntity;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.text.TextComponentTranslation;

public final class EnergyHatchContainer extends AEBaseContainer {
    private final EnergyHatchBlockEntity tile;
    @GuiSync(0)
    public long energy;
    @GuiSync(1)
    public long capacity;

    public EnergyHatchContainer(InventoryPlayer player, EnergyHatchBlockEntity tile) {
        super(player, tile);
        this.tile = tile;
        energy = tile.storage().storedEnergy();
        capacity = tile.storage().capacity();
        addPlayerInventorySlots(8, 88);
        setGuiTitle(new TextComponentTranslation("tile.prototype_eng.energy_hatch.name"));
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            energy = tile.storage().storedEnergy();
            capacity = tile.storage().capacity();
        }
        super.broadcastChanges();
    }
}
