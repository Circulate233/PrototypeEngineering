package com.circulation.prototype_eng.container;

import ae2.container.AEBaseContainer;
import ae2.container.guisync.GuiSync;
import com.circulation.prototype_eng.api.gui.ParallelControllerGuiHost;
import com.circulation.prototype_eng.api.machine.ParallelController;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;

/**
 * Every setting request rechecks the original world-owned host and its live bound.
 */
public final class ContainerParallelController extends AEBaseContainer {
    private static final String SELECTED = "parallelism";
    private final ParallelControllerGuiHost host;
    private final ParallelController controller;
    @GuiSync(0)
    public int selected;
    @GuiSync(1)
    public int maximum;

    public ContainerParallelController(InventoryPlayer player, ParallelControllerGuiHost host) {
        super(player, host.blockEntity());
        this.host = host;
        controller = host.controller();
        syncFields();
        setGuiTitle(host.displayName().text());
        registerClientAction(SELECTED, Integer.class, value -> {
            if (value != null && canInteractWith(getPlayer()) && value >= 0 && value <= controller.snapshot().maximum())
                host.setSelectedParallelism(value);
        });
    }

    public void select(int amount) {
        if (amount != selected) {
            selected = amount;
            sendClientAction(SELECTED, amount);
        }
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return host.controller() == controller && controller.snapshot().valid() && super.canInteractWith(player);
    }

    private void syncFields() {
        var state = controller.snapshot();
        selected = state.selected();
        maximum = state.maximum();
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) syncFields();
        super.broadcastChanges();
    }
}
