package com.circulation.prototype_eng.container;

import ae2.container.AEBaseContainer;
import ae2.container.SlotSemantics;
import ae2.container.slot.RestrictedInputSlot;
import ae2.core.definitions.AEItems;
import ae2.core.gui.locator.GuiHostLocators;
import com.circulation.prototype_eng.item.BuilderInstrumentItem;
import com.circulation.prototype_eng.localization.StructureToolText;
import com.circulation.prototype_eng.structure.tool.ConstructionMEAccess;
import net.minecraft.entity.player.InventoryPlayer;
import org.jetbrains.annotations.Nullable;

public final class ContainerBuilderUpgrades extends AEBaseContainer {
    public static final int GUI_ID = 0x504555;
    private final ConstructionMEAccess host;
    private final RestrictedInputSlot singularity;

    private ContainerBuilderUpgrades(InventoryPlayer inventory, ConstructionMEAccess host) {
        super(inventory, host);
        this.host = host;
        setGuiTitle(StructureToolText.BuilderUpgrades.text());
        setupUpgrades(host.getUpgrades());
        singularity = new RestrictedInputSlot(RestrictedInputSlot.PlacableItemType.QE_SINGULARITY, host.singularity(), 0);
        singularity.setStackLimit(1);
        addSlot(singularity, SlotSemantics.WIRELESS_SINGULARITY);
        addPlayerInventorySlots(8, 34);
        registerClientAction("back", Void.class, ignored -> host.openMain());
    }

    public static @Nullable ContainerBuilderUpgrades open(InventoryPlayer inventory, int slot) {
        if (slot < 0 || slot >= inventory.getSizeInventory()
            || !(inventory.getStackInSlot(slot).getItem() instanceof BuilderInstrumentItem item)) return null;
        return new ContainerBuilderUpgrades(inventory, item.getGuiHost(inventory.player, GuiHostLocators.forInventorySlot(slot), null));
    }

    public ConstructionMEAccess host() {
        return host;
    }

    public void back() {
        sendClientAction("back");
    }

    @Override
    public void detectAndSendChanges() {
        singularity.setSlotEnabled(host.getUpgrades().isInstalled(AEItems.QUANTUM_BRIDGE_CARD.item())
            || !host.singularity().getStackInSlot(0).isEmpty());
        super.detectAndSendChanges();
    }
}
