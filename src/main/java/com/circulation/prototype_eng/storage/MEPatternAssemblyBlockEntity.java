package com.circulation.prototype_eng.storage;

import ae2.api.AECapabilities;
import ae2.api.config.Actionable;
import ae2.api.crafting.IPatternDetails;
import ae2.api.crafting.PatternDetailsHelper;
import ae2.api.implementations.blockentities.PatternContainerGroup;
import ae2.api.inventories.InternalInventory;
import ae2.api.inventories.VersionedInternalInventory;
import ae2.api.networking.GridFlags;
import ae2.api.networking.GridHelper;
import ae2.api.networking.IGrid;
import ae2.api.networking.IGridNode;
import ae2.api.networking.IGridNodeListener;
import ae2.api.networking.IInWorldGridNodeHost;
import ae2.api.networking.IManagedGridNode;
import ae2.api.networking.crafting.ICraftingProvider;
import ae2.api.networking.security.IActionHost;
import ae2.api.networking.security.IActionSource;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import ae2.api.stacks.KeyCounter;
import ae2.api.storage.StorageHelper;
import ae2.api.util.AEColor;
import ae2.block.IOwnerAwareTile;
import ae2.crafting.pattern.AEProcessingPattern;
import ae2.helpers.patternmodifier.PatternModifierLogic;
import ae2.helpers.patternprovider.PatternContainer;
import ae2.items.misc.GenericResourcePackageItem;
import ae2.tile.AEBaseTile;
import ae2.util.inv.AppEngInternalInventory;
import ae2.util.inv.InternalInventoryHost;
import ae2.util.inv.filter.IAEItemFilter;
import com.circulation.prototype_eng.MEAssemblyConfig;
import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.HatchCapabilities;
import com.circulation.prototype_eng.api.storage.ResourceHatchGrouping;
import com.circulation.prototype_eng.localization.MEAssemblyText;
import com.circulation.prototype_eng.structure.tool.BuilderDropRecovery;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.capabilities.Capability;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.Objects;

/**
 * Native AE crafting provider whose resources remain in the machine's transactional ledgers.
 */
public final class MEPatternAssemblyBlockEntity extends AEBaseTile implements IInWorldGridNodeHost, IActionHost,
    IOwnerAwareTile, PatternContainer, ICraftingProvider, InternalInventoryHost, ResourceHatchGrouping {
    private static final VarHandle STORAGE_DIRTY;
    private static final IGridNodeListener<MEPatternAssemblyBlockEntity> NODE_LISTENER = (owner, ignored) -> owner.requestStorageSave();

    static {
        try {
            STORAGE_DIRTY = MethodHandles.lookup().findVarHandle(MEPatternAssemblyBlockEntity.class, "storageDirty", boolean.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private int pages = MEAssemblyConfig.patternPages();
    private PatternInventory patterns = new PatternInventory(pages * 36);
    private volatile DecodedPatterns decodedPatterns = DecodedPatterns.EMPTY;
    private long patternRevision;
    private volatile AEColor color = AEColor.TRANSPARENT;
    private @Nullable String customName;
    private boolean terminalVisible = true;
    private boolean batchingPatterns;
    private boolean decodingNeeded = true;
    private @Nullable MEPatternAssemblyStorage storage;
    private @Nullable NBTTagCompound suspendedStorage;
    private @Nullable IManagedGridNode mainNode;
    private @Nullable MEPatternMirrorLinks.Publication mirrorSource;
    private @Nullable NBTTagCompound nodeData;
    @SuppressWarnings("unused") // Workers publish through STORAGE_DIRTY; the server tick drains it.
    private boolean storageDirty;

    @SuppressWarnings("unused") // Native AE entity construction requires the public no-argument constructor.
    public MEPatternAssemblyBlockEntity() {
    }

    private static BufferView view(UnboundedResourceBuffer buffer) {
        var snapshot = buffer.snapshot();
        var stacks = new ObjectArrayList<GenericStack>(snapshot.available().size());
        snapshot.available().forEachEntry((key, amount) -> stacks.add(new GenericStack(key, amount)));
        return new BufferView(snapshot.revision(), stacks);
    }

    @SuppressWarnings("ReturnOfInnerClass") // Stable native facade owned by this tile.
    public InternalInventory patterns() {
        return patterns;
    }

    public int patternPages() {
        return pages;
    }

    public long patternRevision() {
        return patternRevision;
    }

    public NBTTagCompound exportMemoryCardSettings() {
        requireOwner();
        var tag = new NBTTagCompound();
        tag.setString("Mode", mode().name());
        tag.setString("ResourceColor", color.name());
        tag.setBoolean("TerminalVisible", terminalVisible);
        if (customName != null) tag.setString("TerminalName", customName);
        patterns.writeToNBT(tag, "patterns");
        return tag;
    }

    /**
     * Reads this assembly's card format on the server owner; source tags are borrowed read-only.
     */
    public boolean importMemoryCardSettings(NBTTagCompound tag, EntityPlayer player) {
        requireOwner();
        var target = storage();
        if (!target.idle()) return false;
        var nextMode = Mode.valueOf(tag.getString("Mode"));
        var nextColor = AEColor.valueOf(tag.getString("ResourceColor"));
        var nextName = tag.hasKey("TerminalName", 8) ? tag.getString("TerminalName") : null;
        if (!target.setMode(nextMode)) return false;
        color = nextColor;
        customName = nextName;
        terminalVisible = tag.getBoolean("TerminalVisible");
        batchingPatterns = true;
        try {
            MEPatternAssemblyMemoryCard.restorePatterns(tag, patterns, player, world);
        } finally {
            batchingPatterns = false;
            player.inventory.markDirty();
            patternInventoryChanged();
        }
        return true;
    }

    public Mode mode() {
        return storage().mode();
    }

    public AEColor inputColor() {
        return color;
    }

    public UnboundedResourceBuffer catalystStorage() {
        return storage().catalyst();
    }

    public MEPatternAssemblyStorage storage() {
        if (storage == null) {
            storage = suspendedStorage == null ? new MEPatternAssemblyStorage(patterns.size(), this::inputColor, this::requestStorageSave)
                : MEPatternAssemblyStorage.load(suspendedStorage, patterns.size(), this::inputColor, this::requestStorageSave);
            suspendedStorage = null;
            if (isInvalid()) storage.invalidate();
            if (mirrorSource != null) mirrorSource.replace(storage);
        }
        return storage;
    }

    @Override
    public AEColor resourceColor() {
        return color;
    }

    @Override
    public void setResourceColor(AEColor next) {
        setInputColor(next);
    }

    public boolean setInputColor(AEColor next) {
        requireOwner();
        if (color == next) return true;
        if (!storage().idle()) return false;
        color = next;
        configurationChanged();
        return true;
    }

    public boolean setMode(long expectedRevision, Mode next) {
        requireOwner();
        if (expectedRevision != patternRevision || !storage().setMode(next)) return false;
        configurationChanged();
        return true;
    }

    public boolean modifyPatterns(long expectedRevision, int factor, boolean divide) {
        requireOwner();
        if (expectedRevision != patternRevision || factor <= 1 || !storage().idle()) return false;
        var replacements = new ObjectArrayList<ItemStack>(patterns.size());
        boolean changed = false;
        for (var stack : patterns) {
            var next = PatternModifierLogic.modifyAmounts(stack, world, factor, divide);
            changed |= !ItemStack.areItemStacksEqual(stack, next);
            replacements.add(next);
        }
        if (!changed) return false;
        // Inventory iteration skips empty slots, so retain original slot ownership in the second pass.
        int replacement = 0;
        batchingPatterns = true;
        try {
            for (int slot = 0; slot < patterns.size(); slot++)
                if (!patterns.getStackInSlot(slot).isEmpty()) {
                    var next = replacements.get(replacement++);
                    if (!ItemStack.areItemStacksEqual(patterns.getStackInSlot(slot), next))
                        patterns.setItemDirect(slot, next);
                }
        } finally {
            batchingPatterns = false;
        }
        patternInventoryChanged();
        return true;
    }

    public BufferView inputView() {
        return storage().inputView();
    }

    public BufferView catalystView() {
        return view(catalystStorage());
    }

    public BufferView outputView() {
        return view(storage().output());
    }

    public boolean returnInputsToMe(long expectedRevision) {
        requireOwner();
        var transfer = meTransfer();
        return expectedRevision == patternRevision && transfer != null && storage().returnInputs(transfer);
    }

    private void requireOwner() {
        if (world.isRemote || !((WorldServer) world).isCallingFromMinecraftThread())
            throw new IllegalStateException("Pattern assembly edit requires the server thread");
    }

    private void configurationChanged() {
        patternRevision = Math.incrementExact(patternRevision);
        markDirty();
        if (mainNode != null) ICraftingProvider.requestUpdate(mainNode);
    }

    private void patternInventoryChanged() {
        if (batchingPatterns) return;
        patternRevision = Math.incrementExact(patternRevision);
        decodingNeeded = true;
        rebuildPatterns();
        requestStorageSave();
    }

    private void rebuildPatterns() {
        if (!decodingNeeded || world == null || world.isRemote) return;
        DecodedPatterns next = null;
        for (int slot = 0; slot < patterns.size(); slot++) {
            var decoded = PatternDetailsHelper.decodePattern(patterns.getStackInSlot(slot), world);
            if (!(decoded instanceof AEProcessingPattern processing)) continue;
            var inputs = new AEKey2LongMap.OpenHashMap();
            boolean valid = true;
            var elements = processing.getSparseInputs();
            for (int index3 = 0; index3 < elements.size(); index3++) {
                var stack = elements.get(index3);
                if (stack != null && !PatternInputSignature.add(inputs, stack.what(), stack.amount())) valid = false;
            }
            if (!valid || inputs.isEmpty()) continue;
            if (next == null) next = new DecodedPatterns(new Reference2ObjectOpenHashMap<>(),
                new Object2ObjectOpenHashMap<>(), new ObjectArrayList<>());
            var entry = new PatternEntry(slot, inputs);
            next.byDetails.putIfAbsent(processing, entry);
            next.byDefinition.putIfAbsent(processing.getDefinition(), entry);
            next.available.add(processing);
        }
        decodedPatterns = next == null ? DecodedPatterns.EMPTY : next;
        decodingNeeded = false;
        if (mainNode != null) ICraftingProvider.requestUpdate(mainNode);
    }

    private @Nullable PatternEntry entry(IPatternDetails details) {
        var catalog = decodedPatterns;
        var entry = catalog.byDetails.get(details);
        // CPU reload decodes its own details; match the saved definition after preserving live slot identity.
        return entry != null ? entry : catalog.byDefinition.get(details.getDefinition());
    }

    /**
     * Published pattern lists are stable and must not be modified by consumers.
     */
    @Override
    public List<? extends IPatternDetails> getAvailablePatterns() {
        return decodedPatterns.available;
    }

    @Override
    public boolean isBusy() {
        return isInvalid() || mainNode == null || !mainNode.isActive() || storage().busy();
    }

    @Override
    public boolean canMergePatternPush(IPatternDetails details) {
        return entry(details) != null;
    }

    @Override
    public int getMaxPatternPushMultiplier(IPatternDetails details, int requested) {
        var entry = entry(details);
        return entry == null || isBusy() ? 0 : storage().maxMultiplier(entry.slot, entry.inputs, requested);
    }

    @Override
    public boolean pushPattern(IPatternDetails details, KeyCounter[] holders, int multiplier) {
        requireOwner();
        var entry = entry(details);
        if (entry == null || multiplier <= 0 || isBusy()) return false;
        var held = new AEKey2LongMap.OpenHashMap();
        for (var holder : holders)
            for (var amount : holder)
                if (!PatternInputSignature.add(held, amount.getKey(), amount.getLongValue())) return false;
        var accepted = new AEKey2LongMap.OpenHashMap();
        boolean[] valid = {true};
        details.pushInputsToExternalInventory(holders, (key, amount) -> {
            if (!PatternInputSignature.add(accepted, key, amount)) valid[0] = false;
        });
        // CPU holders are already multiplied. Reject malformed or partially represented batches before writing.
        if (!valid[0] || !held.equals(accepted) || held.size() != entry.inputs.size()) return false;
        for (var amount : entry.inputs.object2LongEntrySet())
            if (amount.getLongValue() > Long.MAX_VALUE / multiplier
                || held.getLong(amount.getKey()) != amount.getLongValue() * multiplier) return false;
        return storage().push(entry.slot, accepted);
    }

    private IManagedGridNode node() {
        if (mainNode == null) {
            mainNode = GridHelper.createManagedNode(this, NODE_LISTENER).setInWorldNode(true).setTagName("MeNode")
                                 .setFlags(GridFlags.REQUIRE_CHANNEL).addService(ICraftingProvider.class, this);
            if (nodeData != null) mainNode.loadFromNBT(nodeData);
            nodeData = null;
        }
        return mainNode;
    }

    @SuppressWarnings("unused") // Native extension hook for managed-node ownership and service integration.
    public IManagedGridNode getMainNode() {
        return node();
    }

    @Override
    public @Nullable IGridNode getGridNode(EnumFacing side) {
        return !isInvalid() && mainNode != null ? mainNode.getNode() : null;
    }

    @Override
    public @Nullable IGridNode getActionableNode() {
        return getGridNode(null);
    }

    @Override
    public @Nullable IGrid getGrid() {
        return !isInvalid() && mainNode != null ? mainNode.getGrid() : null;
    }

    @Override
    public void setOwner(EntityPlayer player) {
        node().setOwningPlayer(player);
    }

    @Override
    public boolean canBeRotated() {
        return false;
    }

    @Override
    public void onReady() {
        super.onReady();
        if (world.isRemote) return;
        if (mirrorSource != null) {
            mirrorSource.close();
            mirrorSource = null;
        }
        var resources = storage();
        mirrorSource = MEPatternMirrorLinks.forWorld(world).publish(world.provider.getDimension(), pos.toLong(), resources);
        rebuildPatterns();
        var node = node();
        if (!node.isReady()) {
            node.setVisualRepresentation(new ItemStack(world.getBlockState(pos).getBlock()));
            node.create(world, pos);
        }
    }

    private @Nullable MEResourceHatch.METransfer meTransfer() {
        if (mainNode == null || !mainNode.isActive() || mainNode.getGrid() == null) return null;
        var grid = mainNode.getGrid();
        var inventory = grid.getStorageService().getInventory();
        var energy = grid.getEnergyService();
        var source = IActionSource.ofMachine(this);
        return new MEResourceHatch.METransfer() {
            @Override
            public long insert(AEKey key, long amount) {
                return StorageHelper.poweredInsert(energy, inventory, key, amount, source, Actionable.MODULATE);
            }

            @Override
            public long extract(AEKey key, long amount) {
                return 0;
            }
        };
    }

    @Override
    public void update() {
        super.update();
        if (world.isRemote) return;
        if (!remountStorage()) return;
        rebuildPatterns();
        var transfer = meTransfer();
        if (transfer != null) storage().exportOutput(transfer);
        if ((boolean) STORAGE_DIRTY.getAndSet(this, false)) markDirty();
    }

    private void requestStorageSave() {
        STORAGE_DIRTY.setRelease(this, true);
    }

    @Override
    public void saveChangedInventory(AppEngInternalInventory inventory) {
        requestStorageSave();
    }

    @Override
    public void onChangeInventory(AppEngInternalInventory inventory, int slot) {
        patternInventoryChanged();
    }

    @Override
    public boolean isClientSide() {
        return world != null && world.isRemote;
    }

    @SuppressWarnings("ReturnOfInnerClass") // Terminal edits share the exact versioned native inventory.
    @Override
    public InternalInventory getTerminalPatternInventory() {
        return patterns;
    }

    @Override
    public boolean containsPattern(AEItemKey pattern) {
        return decodedPatterns.byDefinition.containsKey(pattern);
    }

    @Override
    public boolean isVisibleInTerminal() {
        return terminalVisible;
    }

    @Override
    public boolean canEditTerminalName() {
        return true;
    }

    @Override
    public boolean canModifyTerminalVisibility() {
        return true;
    }

    @Override
    public long getTerminalSortOrder() {
        return pos.toLong();
    }

    @Override
    public void setTerminalCustomName(@Nullable String name) {
        requireOwner();
        customName = name == null || name.isBlank() ? null : name;
        configurationChanged();
    }

    @Override
    public void setTerminalVisibility(boolean visible) {
        requireOwner();
        terminalVisible = visible;
        configurationChanged();
    }

    @Override
    public void openTerminalPatternContainerGui(EntityPlayer player) {
        if (player instanceof EntityPlayerMP)
            player.openGui(PrototypeEngineering.instance, 4, world, pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public PatternContainerGroup getTerminalGroup() {
        AEItemKey icon = AEItemKey.of(new ItemStack(world.getBlockState(pos).getBlock()));
        return new PatternContainerGroup(icon, customName == null ? MEAssemblyText.PatternAssembly.text() : new TextComponentString(customName), List.of());
    }

    @Override
    public boolean hasCapability(@NotNull Capability<?> capability, @Nullable EnumFacing side) {
        return !isInvalid() && (capability == HatchCapabilities.RESOURCE || capability == AECapabilities.IN_WORLD_GRID_NODE_HOST)
            || super.hasCapability(capability, side);
    }

    @Override
    public <T> @Nullable T getCapability(@NotNull Capability<T> capability, @Nullable EnumFacing side) {
        if (!isInvalid()) {
            if (capability == HatchCapabilities.RESOURCE) return HatchCapabilities.RESOURCE.cast(storage());
            if (capability == AECapabilities.IN_WORLD_GRID_NODE_HOST)
                return AECapabilities.IN_WORLD_GRID_NODE_HOST.cast(this);
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void saveAdditional(@NotNull NBTTagCompound tag) {
        super.saveAdditional(tag);
        storage().save(tag);
        saveConfiguration(tag);
        if (mainNode != null) mainNode.saveToNBT(tag);
        else if (nodeData != null && nodeData.hasKey("MeNode")) tag.setTag("MeNode", nodeData.getTag("MeNode"));
    }

    private void saveConfiguration(NBTTagCompound tag) {
        tag.setInteger("PatternPages", pages);
        tag.setLong("PatternRevision", patternRevision);
        tag.setString("ResourceColor", color.name());
        tag.setBoolean("TerminalVisible", terminalVisible);
        if (customName != null) tag.setString("TerminalName", customName);
        patterns.writeToNBT(tag, "Patterns");
    }

    @Override
    public void loadTag(@NotNull NBTTagCompound tag) {
        int nextPages = tag.getInteger("PatternPages");
        if (nextPages <= 0 || nextPages > Integer.MAX_VALUE / 36)
            throw new IllegalArgumentException("Invalid saved pattern page count");
        var nextColor = AEColor.valueOf(tag.getString("ResourceColor"));
        long nextRevision = tag.getLong("PatternRevision");
        if (nextRevision < 0) throw new IllegalArgumentException("Negative saved pattern revision");
        boolean nextVisible = tag.getBoolean("TerminalVisible");
        var nextName = tag.hasKey("TerminalName") ? tag.getString("TerminalName") : null;
        var nextStorage = MEPatternAssemblyStorage.load(tag, nextPages * 36, this::inputColor, this::requestStorageSave);
        var nextPatterns = new PatternInventory(nextPages * 36);
        if (tag.hasKey("Patterns")) {
            var savedPatterns = tag.getTagList("Patterns", 10);
            boolean[] visited = new boolean[nextPatterns.size()];
            for (int index = 0; index < savedPatterns.tagCount(); index++) {
                var saved = savedPatterns.getCompoundTagAt(index);
                int slot = Objects.checkIndex(saved.getInteger("Slot"), nextPatterns.size());
                var stack = new ItemStack(saved);
                if (visited[slot] || stack.isEmpty() || stack.getCount() != 1 || !PatternDetailsHelper.isEncodedPattern(stack))
                    throw new IllegalArgumentException("Invalid or repeated saved pattern slot");
                visited[slot] = true;
                nextPatterns.readSlot(slot, stack);
            }
        }
        if (storage != null && !storage.idle())
            throw new IllegalStateException("Cannot reload unresolved pattern storage");
        if (storage != null) storage.invalidate();
        super.loadTag(tag);
        pages = nextPages;
        patterns = nextPatterns;
        patternRevision = nextRevision;
        color = nextColor;
        terminalVisible = nextVisible;
        customName = nextName;
        storage = nextStorage;
        suspendedStorage = null;
        if (mirrorSource != null) mirrorSource.replace(nextStorage);

        if (mainNode != null) mainNode.loadFromNBT(tag);
        else nodeData = tag;
        decodingNeeded = true;
        rebuildPatterns();
    }

    public NBTTagCompound saveForItemDrop() {
        var tag = new NBTTagCompound();
        storage().save(tag);
        saveConfiguration(tag);
        tag.setBoolean("CraftingLocked", false);
        tag.setBoolean("LockRecipeSeen", false);
        tag.setTag("Running", new NBTTagList());
        tag.setTag("Input", new NBTTagList());
        tag.setTag("Catalyst", new NBTTagList());
        tag.setTag("Output", new NBTTagList());
        tag.setTag("Isolated", new NBTTagList());
        tag.removeTag("Patterns");
        return tag;
    }

    @Override
    @SuppressWarnings("DataFlowIssue")
    public void addAdditionalDrops(List<ItemStack> drops) {
        super.addAdditionalDrops(drops);
        if (world == null || world.isRemote) return;
        for (var stack : patterns) drops.add(stack);
        batchingPatterns = true;
        try {
            patterns.clear();
        } finally {
            batchingPatterns = false;
        }
        var removalWorld = world;
        var removalPosition = pos.toImmutable();
        var server = world.getMinecraftServer();
        var destination = BuilderDropRecovery.destination(removalWorld, removalPosition);
        var contents = storage().removeForDrops(deferred -> {
            Runnable deliver = () -> {
                var delayed = new ObjectArrayList<ItemStack>();
                for (int index2 = 0; index2 < deferred.size(); index2++) {
                    var stack = deferred.get(index2);
                    GenericResourcePackageItem.addDrop(stack.what(), stack.amount(), delayed);
                }
                destination.accept(delayed);
            };
            if (server.isCallingFromMinecraftThread()) deliver.run();
            else server.addScheduledTask(deliver);
        });
        for (int index1 = 0; index1 < contents.size(); index1++) {
            var stack = contents.get(index1);
            GenericResourcePackageItem.addDrop(stack.what(), stack.amount(), drops);
        }
    }

    @Override
    protected void clearRemoved() {
        super.clearRemoved();
        remountStorage();
        scheduleInit();
    }

    @Override
    protected void setRemoved() {
        suspend();
        super.setRemoved();
    }

    @Override
    protected void onChunkUnloaded() {
        suspend();
        super.onChunkUnloaded();
    }

    private boolean remountStorage() {
        if (storage != null && !storage.isValid()) {
            if (storage.hasReservations()) return false;
            var saved = new NBTTagCompound();
            storage.save(saved);
            suspendedStorage = saved;
            storage = null;

        }
        return true;
    }

    private void suspend() {
        if (mirrorSource != null) {
            mirrorSource.close();
            mirrorSource = null;
        }
        if (mainNode != null) {
            var saved = new NBTTagCompound();
            mainNode.saveToNBT(saved);
            mainNode.destroy();
            mainNode = null;
            nodeData = saved;
        }
        if (storage != null) storage.invalidate();
        if (world != null && !world.isRemote && (boolean) STORAGE_DIRTY.getAndSet(this, false)) markDirty();
    }

    public enum Mode {
        DEFAULT, BLOCKING, RECIPE_BLOCKING, PATTERN_ISOLATION, CRAFTING_LOCK;
        public static final Mode[] VALUES = values();
    }

    public record BufferView(long revision, List<GenericStack> stacks) {
    }

    private record PatternEntry(int slot, AEKey2LongMap inputs) {
    }

    private record DecodedPatterns(Reference2ObjectMap<IPatternDetails, PatternEntry> byDetails,
                                   Object2ObjectMap<AEItemKey, PatternEntry> byDefinition,
                                   List<IPatternDetails> available) {
        private static final DecodedPatterns EMPTY = new DecodedPatterns(Reference2ObjectMaps.emptyMap(),
            Object2ObjectMaps.emptyMap(), List.of());
    }

    private final class PatternInventory extends AppEngInternalInventory implements VersionedInternalInventory {
        private PatternInventory(int slots) {
            super(MEPatternAssemblyBlockEntity.this, slots, 1, new IAEItemFilter() {
                @Override
                public boolean allowInsert(InternalInventory inventory, int slot, ItemStack stack) {
                    return world != null && PatternDetailsHelper.decodePattern(stack, world) instanceof AEProcessingPattern;
                }
            });
        }

        @Override
        public long getContentsVersion() {
            return patternRevision;
        }

        @Override
        public void setItemDirect(int slot, ItemStack stack) {
            if (!stack.isEmpty() && (stack.getCount() != 1 || !PatternDetailsHelper.isEncodedPattern(stack)
                || world != null && !isItemValid(slot, stack)))
                throw new IllegalArgumentException("Pattern assembly slots accept one processing pattern");
            super.setItemDirect(slot, stack);
        }

        private void readSlot(int slot, ItemStack stack) {
            var previous = batchingPatterns;
            batchingPatterns = true;
            try {
                super.setItemDirect(slot, stack);
            } finally {
                batchingPatterns = previous;
            }
        }
    }
}
