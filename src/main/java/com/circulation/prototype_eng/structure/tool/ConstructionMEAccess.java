package com.circulation.prototype_eng.structure.tool;

import ae2.api.config.Actionable;
import ae2.api.features.Locatables;
import ae2.api.implementations.blockentities.IWirelessAccessPoint;
import ae2.api.implementations.guiobjects.ItemGuiHost;
import ae2.api.inventories.InternalInventory;
import ae2.api.networking.IGrid;
import ae2.api.networking.IGridNode;
import ae2.api.networking.security.IActionHost;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import ae2.api.storage.ISubGuiHost;
import ae2.container.ISubGui;
import ae2.container.implementations.ContainerCraftConfirm;
import ae2.core.definitions.AEItems;
import ae2.core.gui.locator.ItemGuiHostLocator;
import ae2.core.localization.GuiText;
import ae2.core.localization.PlayerMessages;
import ae2.crafting.TemporaryPseudoCraftingProvider;
import ae2.me.helpers.PlayerSource;
import ae2.tile.networking.TileWirelessAccessPoint;
import ae2.tile.qnb.TileQuantumBridge;
import ae2.util.inv.AppEngInternalInventory;
import ae2.util.inv.InternalInventoryHost;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.container.ContainerStructureTool;
import com.circulation.prototype_eng.item.BuilderInstrumentItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.common.DimensionManager;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 一个会话绑定玩家背包槽位中的原构建器，locator 必须提供玩家背包槽位。
 * 任务与延迟回收必须复用该会话，禁止另查玩家持有的终端。
 */
public final class ConstructionMEAccess extends ItemGuiHost<BuilderInstrumentItem>
    implements IActionHost, ISubGuiHost, InternalInventoryHost {
    private static final String SINGULARITY = "BuilderSingularity";
    private final ItemStack tool;
    private final PlayerSource source;
    private final AppEngInternalInventory singularity = new AppEngInternalInventory(this, 1, 1);
    private @Nullable IGridNode node;
    private @Nullable NBTBase upgradeData;
    private @Nullable NBTBase singularityData;
    private boolean quantumCard;
    private ITextComponent status = PlayerMessages.DeviceNotLinked.text();
    private long refreshedAt = Long.MIN_VALUE;

    public ConstructionMEAccess(BuilderInstrumentItem item, EntityPlayer player, ItemGuiHostLocator locator) {
        super(item, player, locator);
        tool = getItemStack();
        source = new PlayerSource(player, this);
        if (!isClientSide()) connect();
    }

    private boolean hasQuantumCard() {
        var tag = tool.getTagCompound();
        var data = tag == null ? null : tag.getTag("upgrades");
        if (upgradeData != data) {
            quantumCard = getUpgrades().isInstalled(AEItems.QUANTUM_BRIDGE_CARD.item());
            upgradeData = data;
        }
        return quantumCard;
    }

    public InternalInventory singularity() {
        var tag = getItemStack().getTagCompound();
        var data = tag == null ? null : tag.getTag(SINGULARITY);
        if (singularityData != data) {
            singularity.setItemDirect(0, ItemStack.EMPTY);
            if (tag != null) singularity.readFromNBT(tag, SINGULARITY);
            singularityData = data;
        }
        return singularity;
    }

    @Override
    public void saveChangedInventory(AppEngInternalInventory inventory) {
        var tag = BuilderState.data(getItemStack());
        inventory.writeToNBT(tag, SINGULARITY);
        singularityData = tag.getTag(SINGULARITY);
        refreshedAt = Long.MIN_VALUE;
    }

    private @Nullable IGrid linkedGrid() {
        var tag = tool.getTagCompound();
        if (tag == null || !tag.hasKey(BuilderInstrumentItem.NETWORK, 10)) {
            status = PlayerMessages.DeviceNotLinked.text();
            return null;
        }
        var link = tag.getCompoundTag(BuilderInstrumentItem.NETWORK);
        var world = DimensionManager.getWorld(link.getInteger("Dimension"));
        var pos = BlockPos.fromLong(link.getLong("Position"));
        if (world == null || !world.isBlockLoaded(pos)
            || !(world.getTileEntity(pos) instanceof IWirelessAccessPoint accessPoint)) {
            status = PlayerMessages.LinkedNetworkNotFound.text();
            return null;
        }
        var grid = accessPoint.getGrid();
        status = (grid == null ? PlayerMessages.LinkedNetworkNotFound : PlayerMessages.OutOfRange).text();
        return grid;
    }

    private void refresh() {
        long now = getPlayer().world.getTotalWorldTime();
        if (refreshedAt == now) return;
        refreshedAt = now;
        node = null;
        var grid = linkedGrid();
        if (grid != null) {
            for (var accessPoint : grid.getMachines(TileWirelessAccessPoint.class)) {
                var location = accessPoint.getLocation();
                if (location.getLevel() != getPlayer().world || !accessPoint.isActive()) continue;
                var pos = location.getPos();
                double distance = getPlayer().getDistanceSq(pos.getX(), pos.getY(), pos.getZ());
                double range = accessPoint.getRange();
                if (distance < range * range) {
                    node = accessPoint.getActionableNode();
                    break;
                }
            }
            if (node != null) return;
        }
        if (!hasQuantumCard()) return;
        var paired = singularity().getStackInSlot(0);
        if (!TileQuantumBridge.isValidEntangledSingularity(paired)) {
            status = GuiText.WirelessTerminalNoSingularity.text();
            return;
        }
        @SuppressWarnings("DataFlowIssue") // AE 的有效纠缠奇点检查已要求存在 NBT。
        long frequency = paired.getTagCompound().getLong("entangled_singularity_id");
        if (frequency == 0) {
            status = GuiText.WirelessTerminalNoSingularity.text();
            return;
        }
        var bridge = Locatables.quantumNetworkBridges().get(getPlayer().world, frequency);
        if (bridge == null) bridge = Locatables.quantumNetworkBridges().get(getPlayer().world, -frequency);
        var bridgeNode = bridge == null ? null : bridge.getActionableNode();
        if (bridgeNode == null || !bridgeNode.isActive()) {
            status = GuiText.WirelessTerminalBridgeMissing.text();
            return;
        }
        if (grid != null && bridgeNode.grid() != grid) {
            status = GuiText.WirelessTerminalDifferentNetwork.text();
            return;
        }
        if (!bridgeNode.grid().getEnergyService().isNetworkPowered()) {
            status = GuiText.WirelessTerminalBridgeUnpowered.text();
            return;
        }
        node = bridgeNode;
    }

    public @Nullable IGridNode connect() {
        if (isClientSide() || getItemStack() != tool || tool.isEmpty()) {
            node = null;
            return null;
        }
        refresh();
        return node != null && node.isActive() ? node : null;
    }

    public ITextComponent unavailableReason() {
        return status;
    }

    @Override
    public void tick() {
        connect();
    }

    @Override
    public @Nullable IGridNode getActionableNode() {
        return node;
    }

    long extract(AEKey key, long amount, Actionable action) {
        if (amount <= 0) return 0;
        var current = connect();
        if (current == null) return 0;
        return current.grid().getStorageService().getInventory().extract(key, amount, action, source);
    }

    long insert(AEKey key, long amount) {
        return insert(getPlayer(), key, amount, Actionable.MODULATE);
    }

    @Override
    public long insert(EntityPlayer player, AEKey key, long amount, Actionable action) {
        if (amount <= 0) return 0;
        var current = connect();
        if (current == null) return 0;
        return current.grid().getStorageService().getInventory().insert(key, amount, action, source);
    }

    boolean craftable(AEKey key) {
        var current = connect();
        return current != null && current.grid().getCraftingService().isCraftable(key);
    }

    @SuppressWarnings("DataFlowIssue")
        // connect 成功时原构建器非空，AEItemKey.of 返回有效物品键。
    boolean openCrafting(List<GenericStack> missing) {
        if (missing.isEmpty() || connect() == null) return false;
        var result = AEItemKey.of(tool);
        var provider = new TemporaryPseudoCraftingProvider(missing, List.of(new GenericStack(result, 1)));
        return ContainerCraftConfirm.openWithBatchCrafting(this, (EntityPlayerMP) getPlayer(), getLocator(), missing,
            provider, getPlayer().inventoryContainer);
    }

    @SuppressWarnings("DataFlowIssue") // 本宿主的 locator 按类说明提供玩家背包槽位。
    public void openMain() {
        var player = getPlayer();
        player.openGui(PrototypeEngineering.instance, ContainerStructureTool.GUI_ID, player.world, getPlayerInventorySlot(), 0, 0);
    }

    @Override
    public void returnToMainContainer(EntityPlayer player, ISubGui subGui) {
        openMain();
    }

    @Override
    public ItemStack getMainContainerIcon() {
        return getItemStack();
    }
}
