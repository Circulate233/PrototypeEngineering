package com.circulation.prototype_eng.container;

import ae2.container.AEBaseContainer;
import ae2.container.guisync.GuiSync;
import com.circulation.prototype_eng.machine.MachineControllerBlockEntity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;

/** Default real player inventory and a read-only machine page, synchronized through AE2. */
public final class MachineContainer extends AEBaseContainer {
    private static final String VIEW = "view";
    private final MachineControllerBlockEntity tile;
    private int requestedOffset;
    private int visibleRows = MachineGuiSnapshot.MAX_ROWS;
    private boolean captureQueued;
    @GuiSync(0) public MachineGuiSnapshot snapshot = MachineGuiSnapshot.EMPTY;

    public MachineContainer(InventoryPlayer player, MachineControllerBlockEntity tile) {
        super(player, tile);
        this.tile = tile;
        setGuiTitle(tile.type().displayName().text());
        addPlayerInventorySlots(214, 204);
        registerClientAction(VIEW, View.class, 80, view -> {
            if (view != null && canInteractWith(getPlayer()) && view.offset >= 0 && view.rows >= 1 && view.rows <= MachineGuiSnapshot.MAX_ROWS) {
                requestedOffset = view.offset;
                visibleRows = view.rows;
            }
        });
    }

    public void requestView(int offset, int rows) { sendClientAction(VIEW, new View(offset, rows)); }

    @Override public boolean canInteractWith(EntityPlayer player) {
        return !tile.isInvalid() && player.world == tile.getWorld() && player.world.isBlockLoaded(tile.getPos(), false)
            && player.world.getTileEntity(tile.getPos()) == tile && super.canInteractWith(player);
    }

    @Override public void broadcastChanges() {
        if (isServerSide() && !captureQueued && canInteractWith(getPlayer())) {
            captureQueued = true;
            tile.readGuiAtBoundary(() -> {
                try {
                    if (getPlayer().openContainer != this || !canInteractWith(getPlayer())) { return; }
                    var machine = tile.machine();
                    snapshot = machine == null || !machine.active() ? MachineGuiSnapshot.EMPTY
                        : MachineGuiSnapshot.capture(machine, requestedOffset, visibleRows);
                } finally { captureQueued = false; }
            });
        }
        super.broadcastChanges();
    }

    /** AE2's Gson client-action codec reads these values; they grant no machine mutation permissions. */
    public record View(int offset, int rows) { }
}
