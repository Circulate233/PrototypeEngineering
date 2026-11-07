package com.circulation.prototype_eng.recipe.requirement;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.RenamedAttributeModifier;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayType;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveContext;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveModifierDescriptor;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveState;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Objects;

/**
 * PM's first available alternative, with provisional selection and one enclosing stage settlement.
 * Rejected candidates undo business and tokens immediately; accepted tokens remain owned by the
 * parent stage. Modifier additions are local, tracked, and removed by START undo, END or abort.
 */
public final class SelectiveRequirementSystem implements RecipeRequirementSystem<SelectiveRequirement> {
    private static final Runnable NO_OP = () -> { };
    private static final CapturedTransaction EMPTY = new CapturedTransaction(ProcessResult.SUCCESS, ObjectLists.emptyList(), RequirementTransaction.SUCCESS);

    @Override
    public List<RecipeRequirement<?>> children(SelectiveRequirement payload) { return payload.candidates(); }

    @Override
    public RequirementTransaction start(RecipeProcess process, RecipeRequirement<SelectiveRequirement> requirement) {
        var path = process.requirementPath();
        var state = SelectiveState.getOrCreate(process);
        if (state.selection(path) != null) {
            return RequirementTransaction.SUCCESS;
        }
        var payload = requirement.payload();
        for (int index = 0; index < payload.candidates().size(); index++) {
            var candidate = capture(process, payload.candidates().get(index), RecipeStage.START);
            if (candidate.result() != ProcessResult.SUCCESS) {
                ResourceTransactions.rollback(candidate.reservations(), candidate::rollback);
                continue;
            }
            return begin(process, state, path, index, candidate, payload.modifiers());
        }
        return begin(process, state, path, -1, EMPTY, ObjectLists.emptyList());
    }

    @Override
    public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<SelectiveRequirement> requirement) {
        var state = process.components().get(SelectiveState.TYPE);
        var selection = state == null ? null : state.selection(process.requirementPath());
        if (selection == null || selection.index() < 0 || selection.completed()) {
            return RequirementTransaction.SUCCESS;
        }
        return capture(process, chosen(requirement.payload(), selection), RecipeStage.TICK);
    }

    @Override
    public RequirementTransaction end(RecipeProcess process, RecipeRequirement<SelectiveRequirement> requirement) {
        var state = process.components().get(SelectiveState.TYPE);
        var selection = state == null ? null : state.selection(process.requirementPath());
        if (selection == null || selection.completed()) {
            return RequirementTransaction.SUCCESS;
        }
        var child = selection.index() < 0 ? EMPTY : capture(process, chosen(requirement.payload(), selection), RecipeStage.END);
        return new EndTransaction(child, selection);
    }

    @Override
    public void cancel(RecipeProcess process, RecipeRequirement<SelectiveRequirement> requirement) {
        var state = process.components().get(SelectiveState.TYPE);
        var selection = state == null ? null : state.selection(process.requirementPath());
        if (selection != null && selection.index() >= 0 && !selection.completed()) {
            chosen(requirement.payload(), selection).cancel(process);
        }
    }

    private static RecipeRequirement<?> chosen(SelectiveRequirement requirement, SelectiveState.Selection selection) {
        if (selection.index() >= requirement.candidates().size()) {
            throw new IllegalStateException("Selected candidate is absent from the immutable definition");
        }
        return requirement.candidates().get(selection.index());
    }

    private static RequirementTransaction begin(RecipeProcess process, SelectiveState state, RequirementPath path,
                                                 int index, CapturedTransaction child, List<SelectiveModifierDescriptor> modifiers) {
        SelectiveState.Selection selection;
        try {
            selection = state.begin(path, index);
        } catch (RuntimeException | Error failure) {
            rollbackAfterFailure(child, child.reservations(), failure);
            throw failure;
        }
        try {
            return new StartTransaction(process, path, state, selection, child, modifiers);
        } catch (RuntimeException | Error failure) {
            state.discard(path, selection);
            rollbackAfterFailure(child, child.reservations(), failure);
            throw failure;
        }
    }

    private static CapturedTransaction capture(RecipeProcess process, RecipeRequirement<?> requirement, RecipeStage stage) {
        var child = Objects.requireNonNull(requirement.acquire(process, stage), "selected child transaction");
        var reservations = new ObjectArrayList<ResourceReservation>();
        try {
            reservations.addAll(Objects.requireNonNull(child.reservations(), "selected child reservations"));
            var result = Objects.requireNonNull(child.result(), "selected child result");
            return new CapturedTransaction(result, reservations, child);
        } catch (RuntimeException | Error failure) {
            rollbackAfterFailure(child, reservations, failure);
            throw failure;
        }
    }

    private static void rollbackAfterFailure(RequirementTransaction child, List<ResourceReservation> reservations, Throwable primary) {
        try {
            ResourceTransactions.rollback(reservations, () -> {
                try {
                    child.rollback();
                } catch (RuntimeException | Error cleanup) {
                    if (primary != cleanup) { primary.addSuppressed(cleanup); }
                }
            });
        } catch (RuntimeException | Error cleanup) {
            if (primary != cleanup) { primary.addSuppressed(cleanup); }
        }
    }

    private record CapturedTransaction(ProcessResult result, List<ResourceReservation> reservations,
                                       RequirementTransaction child) implements RequirementTransaction {
        private CapturedTransaction {
            reservations = ObjectLists.unmodifiable(new ObjectArrayList<>(reservations));
        }
        @Override public boolean prepareCommit() { return child.prepareCommit(); }
        @Override public boolean validateCommit() { return child.validateCommit(); }
        @Override public void commit() { child.commit(); }
        @Override public void complete() { child.complete(); }
        @Override public void rollback() { child.rollback(); }
        @Override public Resolution resolution() { return child.resolution(); }
    }

    private static final class StartTransaction implements RequirementTransaction {
        private final CapturedTransaction child;
        private final TransactionGroup group;

        private StartTransaction(RecipeProcess process, RequirementPath path, SelectiveState state,
                                 SelectiveState.Selection selection, CapturedTransaction child,
                                 List<SelectiveModifierDescriptor> modifiers) {
            this.child = child;
            // Reverse rollback cleans exact effects, then the child, and finally discards our choice.
            group = new TransactionGroup(List.of(
                TransactionGroup.callbacks(NO_OP, NO_OP, () -> state.discard(path, selection)),
                child,
                TransactionGroup.callbacks(() -> applyModifiers(process, path, selection, modifiers),
                    selection::completeStart, selection::cleanupEffects)));
        }

        @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
        @Override public List<ResourceReservation> reservations() { return child.reservations(); }
        @Override public boolean prepareCommit() { return group.prepareCommit(); }
        @Override public boolean validateCommit() { return group.validateCommit(); }
        @Override public void commit() { group.commit(); }
        @Override public void complete() { group.complete(); }
        @Override public void rollback() { group.rollback(); }
        @Override public Resolution resolution() { return group.resolution(); }

        private static void applyModifiers(RecipeProcess process, RequirementPath path,
                                           SelectiveState.Selection selection, List<SelectiveModifierDescriptor> modifiers) {
            for (int ordinal = 0; ordinal < modifiers.size(); ordinal++) {
                var descriptor = modifiers.get(ordinal);
                var context = new Context(process, path, selection, descriptor.id(), ordinal);
                try {
                    descriptor.modifier().apply(context);
                } finally {
                    context.close();
                }
            }
            selection.markCommitted();
        }
    }

    private record EndTransaction(CapturedTransaction child, TransactionGroup group) implements RequirementTransaction {
        private EndTransaction(CapturedTransaction child, SelectiveState.Selection selection) {
            this(child, new TransactionGroup(List.of(child, TransactionGroup.callbacks(NO_OP, selection::finish, NO_OP))));
        }

        @Override public ProcessResult result() { return child.result(); }
        @Override public List<ResourceReservation> reservations() { return child.reservations(); }
        @Override public boolean prepareCommit() { return group.prepareCommit(); }
        @Override public boolean validateCommit() { return group.validateCommit(); }
        @Override public void commit() { group.commit(); }
        @Override public void complete() { group.complete(); }
        @Override public void rollback() { group.rollback(); }
        @Override public Resolution resolution() { return group.resolution(); }
    }

    private static final class Context implements SelectiveContext {
        private final RecipeProcess process;
        private final RequirementPath path;
        private final SelectiveState.Selection selection;
        private final ResourceLocation modifierId;
        private final int ordinal;
        private boolean active = true;

        private Context(RecipeProcess process, RequirementPath path, SelectiveState.Selection selection,
                        ResourceLocation modifierId, int ordinal) {
            this.process = process;
            this.path = path;
            this.selection = selection;
            this.modifierId = modifierId;
            this.ordinal = ordinal;
        }

        @Override public MachineInstance machine() { return process.owner(); }
        @Override public RecipeProcess process() { return process; }
        @Override public RequirementPath selectionPath() { return path; }
        @Override public int candidateIndex() { return selection.index(); }
        @Override public ResourceLocation modifierId() { return modifierId; }
        @Override public int modifierOrdinal() { return ordinal; }

        @Override
        public void putProcessAttributeModifier(ResourceLocation attribute, AttributeModifier modifier) {
            if (!active) { throw new IllegalStateException("Selective modifier context is no longer active"); }
            Objects.requireNonNull(modifier);
            String rawId = Objects.requireNonNull(modifier.id(), "modifier id");
            if (rawId.isBlank()) { throw new IllegalArgumentException("Blank modifier id"); }
            String uniqueId = "selective/" + frame(path.toString()) + "/" + ordinal + "/" + frame(modifierId.toString()) + "/" + frame(rawId);
            selection.putModifier(attribute, new RenamedAttributeModifier(uniqueId, modifier));
        }

        @Override
        public <C, D> void putRequirementOverlay(RecipeRequirement<C> target, RequirementPath targetPath, String layerId,
                                                RecipeOverlayType<C, D> type, D data) {
            if (!active) { throw new IllegalStateException("Selective modifier context is no longer active"); }
            Objects.requireNonNull(layerId);
            if (layerId.isBlank()) { throw new IllegalArgumentException("Blank overlay layer id"); }
            String uniqueId = "selective_overlay/" + frame(path.toString()) + frame(modifierId.toString())
                + frame(Integer.toString(ordinal)) + frame(layerId);
            selection.putOverlay(target, targetPath, uniqueId, type, data);
        }

        private static String frame(String value) { return value.length() + ":" + value; }
        private void close() { active = false; }
    }

}
