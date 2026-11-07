package com.circulation.prototype_eng.container;

import ae2.container.AEBaseContainer;
import ae2.container.guisync.GuiSync;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;

import com.circulation.prototype_eng.api.machine.MachineControllerHost;

/** Default real player inventory and a read-only machine page, synchronized through AE2. */
public final class MachineContainer extends AEBaseContainer {
    private static final String VIEW = "view";
    private final MachineControllerHost tile;
    private int requestedOffset;
    private int visibleRows = MachineGuiSnapshot.MAX_ROWS;
    private boolean captureQueued;

    public MachineContainer(InventoryPlayer player, MachineControllerHost tile) {
        super(player, tile.blockEntity());
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
    @GuiSync(0) public MachineGuiSnapshot snapshot = MachineGuiSnapshot.EMPTY;

    public void requestView(int offset, int rows) { sendClientAction(VIEW, new View(offset, rows)); }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        var entity = tile.blockEntity();
        return !entity.isInvalid() && player.world == entity.getWorld() && player.world.isBlockLoaded(entity.getPos(), false)
            && player.world.getTileEntity(entity.getPos()) == entity && super.canInteractWith(player);
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
    public static final class View {
        int offset;
        int rows;

        @SuppressWarnings("unused") // AE2's default Gson codec constructs and fills this wire DTO.
        public View() { }

        public View(int offset, int rows) {
            this.offset = offset;
            this.rows = rows;
        }
    }
}
