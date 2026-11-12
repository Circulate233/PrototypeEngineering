package com.circulation.prototype_eng.integration.jei;

import ae2.api.client.PatternRecipeTransferEvent;
import ae2.api.client.PatternRecipeTransferSlot;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import ae2.client.patternimport.PatternImportPriorityContextImpl;
import ae2.client.patternimport.PatternImportPrioritySelector;
import ae2.client.patternimport.PatternTransferPlan;
import ae2.container.me.patternencode.ContainerPatternEncodingTerm;
import ae2.container.slot.FakeSlot;
import ae2.core.AELog;
import ae2.core.network.InitNetwork;
import ae2.core.network.serverbound.InventoryActionPacket;
import ae2.helpers.InventoryAction;
import ae2.integration.modules.hei.HeiBookmarkHelper;
import ae2.mixins.hei.AccessorRecipeLayout;
import ae2.parts.encoding.EncodingMode;
import com.circulation.prototype_eng.api.client.jei.JeiPatternRecipe;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRecipe;
import com.circulation.prototype_eng.localization.JeiText;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Function;
import java.util.function.BooleanSupplier;

/** Uses the complete wrapper data and AE2S's priority, event, filter packet and encoding APIs. */
final class PatternRecipeTransfer<C extends ContainerPatternEncodingTerm> implements IRecipeTransferHandler<C> {
    private final Class<C> containerClass;
    private final IRecipeTransferHandlerHelper errors;

    PatternRecipeTransfer(Class<C> containerClass, IRecipeTransferHandlerHelper errors) {
        this.containerClass = containerClass;
        this.errors = errors;
    }

    enum EventOutcome { PROCEED, CANCELLED, STALE }

    static EventOutcome dispatch(BooleanSupplier current, BooleanSupplier event) {
        if (!current.getAsBoolean()) return EventOutcome.STALE;
        if (event.getAsBoolean()) return EventOutcome.CANCELLED;
        return current.getAsBoolean() ? EventOutcome.PROCEED : EventOutcome.STALE;
    }

    /** Mirrors AE's selected-key merge and candidate intersection, with a preflight instead of losing overflow. */
    static @Nullable List<PatternRecipeTransferSlot> plan(List<List<GenericStack>> groups,
                                                          Function<List<GenericStack>, GenericStack> select, boolean merge) {
        var result = new ObjectArrayList<PatternRecipeTransferSlot>(groups.size());
        var byKey = new Object2ObjectOpenHashMap<AEKey, PatternRecipeTransferSlot>(groups.size());
        for (var candidates : groups) {
            var selected = select.apply(candidates);
            if (selected == null || !candidates.contains(selected)) return null;
            var keys = new ObjectArrayList<AEKey>(candidates.size());
            for (var candidate : candidates) if (!keys.contains(candidate.what())) keys.add(candidate.what());
            var prior = merge ? byKey.get(selected.what()) : null;
            if (prior == null) {
                var slot = new PatternRecipeTransferSlot(result.size(), selected, keys);
                result.add(slot);
                if (merge) byKey.put(selected.what(), slot);
            } else {
                var previous = prior.getStack();
                if (previous == null || previous.amount() > Long.MAX_VALUE - selected.amount()) return null;
                prior.setStack(new GenericStack(selected.what(), previous.amount() + selected.amount()));
                prior.getCandidates().retainAll(keys);
            }
        }
        return result;
    }

    private static boolean fits(List<PatternRecipeTransferSlot> inputs, List<PatternRecipeTransferSlot> outputs,
                                ContainerPatternEncodingTerm container) {
        return inputs.size() <= container.getProcessingInputSlots().length && outputs.size() <= container.getProcessingOutputSlots().length;
    }

    private static List<InventoryActionPacket> packets(ContainerPatternEncodingTerm container, FakeSlot[] targets, List<PatternRecipeTransferSlot> values) {
        var packets = new ObjectArrayList<InventoryActionPacket>(targets.length);
        for (int i = 0; i < targets.length; i++) {
            var stack = i < values.size() ? GenericStack.wrapInItemStack(values.get(i).getStack()) : ItemStack.EMPTY;
            packets.add(new InventoryActionPacket(container.windowId, InventoryAction.SET_FILTER, targets[i].slotNumber, stack));
        }
        return packets;
    }

    @Override
    public @NotNull Class<C> getContainerClass() {
        return containerClass;
    }

    @Override
    public @Nullable IRecipeTransferError transferRecipe(@NotNull C container, @NotNull IRecipeLayout layout,
                                                         @NotNull EntityPlayer player, boolean maxTransfer, boolean doTransfer) {
        // AE2S already supplies this accessor; no new mixin or global "last drawn recipe" state is needed.
        if (!(layout instanceof AccessorRecipeLayout accessor)) return errors.createInternalError();
        var category = layout.getRecipeCategory();
        final JeiPatternRecipe source;
        final BooleanSupplier current;
        try {
            var wrapper = accessor.ae2$getRecipeWrapper();
            if (wrapper instanceof RecipePoolJeiRecipe recipe && category == recipe.page()) {
                if (!recipe.isCurrent()) return errors.createInternalError();
                source = recipe.patternRecipe();
                current = recipe::isCurrent;
            } else if (wrapper instanceof StructurePreviewJeiRecipe recipe && category instanceof StructurePreviewJeiCategory) {
                var snapshot = recipe.patternRecipe();
                if (snapshot == null) return errors.createUserErrorWithTooltip(JeiText.PatternStructureUnavailable.getLocal());
                source = snapshot.recipe();
                current = snapshot.current();
            } else return errors.createInternalError();
        } catch (ArithmeticException overflow) {
            return errors.createUserErrorWithTooltip(JeiText.PatternAmountOverflow.getLocal());
        } catch (RuntimeException failure) {
            AELog.error(failure, "Could not prepare pattern resources for " + category.getUid());
            return errors.createInternalError();
        }
        if (source == null || source.inputs().isEmpty() || source.outputs().isEmpty()) {
            return errors.createUserErrorWithTooltip(JeiText.PatternNoResources.getLocal());
        }
        var context = PatternImportPriorityContextImpl.create(container, HeiBookmarkHelper.getBookmarkedStacks());
        Function<List<GenericStack>, GenericStack> select = candidates -> PatternImportPrioritySelector.selectIngredient(candidates, context, false);
        var inputs = plan(source.inputs(), select, container.isMergeHeiProcessingInputs());
        var outputs = plan(source.outputs(), select, true);
        if (inputs == null || outputs == null)
            return errors.createUserErrorWithTooltip(JeiText.PatternAmountOverflow.getLocal());
        if (!fits(inputs, outputs, container)) return capacityError(inputs, outputs, container);
        if (!current.getAsBoolean()) return errors.createInternalError();
        if (!doTransfer) return null;

        var event = new PatternRecipeTransferEvent(container, EncodingMode.PROCESSING, category.getUid(),
            category.getTitle(), null, inputs, outputs);
        try {
            var outcome = dispatch(current, () -> MinecraftForge.EVENT_BUS.post(event));
            if (outcome == EventOutcome.CANCELLED) return null;
            if (outcome == EventOutcome.STALE) return errors.createInternalError();
        } catch (RuntimeException failure) {
            AELog.error(failure, "Pattern recipe transfer listener failed for " + category.getUid());
            return errors.createInternalError();
        }
        if (!current.getAsBoolean()) return errors.createInternalError();
        inputs = PatternTransferPlan.packedSlots(inputs);
        outputs = PatternTransferPlan.packedSlots(outputs);
        if (inputs.isEmpty() || outputs.isEmpty())
            return errors.createUserErrorWithTooltip(JeiText.PatternNoResources.getLocal());
        if (!fits(inputs, outputs, container)) return capacityError(inputs, outputs, container);
        // AEKey serialization is addon code. Finish it before the final current-token check and any terminal write.
        var inputPackets = packets(container, container.getProcessingInputSlots(), inputs);
        var outputPackets = packets(container, container.getProcessingOutputSlots(), outputs);
        var candidateTags = PatternTransferPlan.serializeCandidateKeyTags(inputs);
        var categoryUid = category.getUid();
        if (!current.getAsBoolean()) return errors.createInternalError();
        container.setMode(EncodingMode.PROCESSING);
        for (var packet : inputPackets) InitNetwork.sendToServer(packet);
        for (var packet : outputPackets) InitNetwork.sendToServer(packet);
        container.setHeiProcessingRecipe(categoryUid, candidateTags);
        return null;
    }

    private IRecipeTransferError capacityError(List<PatternRecipeTransferSlot> inputs, List<PatternRecipeTransferSlot> outputs,
                                               ContainerPatternEncodingTerm container) {
        return errors.createUserErrorWithTooltip(JeiText.PatternCapacity.getLocal(inputs.size(), outputs.size(),
            container.getProcessingInputSlots().length, container.getProcessingOutputSlots().length));
    }
}
