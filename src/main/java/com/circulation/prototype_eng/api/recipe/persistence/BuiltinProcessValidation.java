package com.circulation.prototype_eng.api.recipe.persistence;

import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementAttributeState;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.process.ResourceInputLocks;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveState;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cross-component checks for the built-in execution graph at a quiescent process boundary. External
 * wrapper activation is deliberately left to host validation. A positive checkpoint nested inside
 * another checkpoint sees only that parent's triggering tick, whose full historical interval is
 * not persisted; only zero-time and provably coincident thresholds imply mandatory child state.
 */
public final class BuiltinProcessValidation {
    private BuiltinProcessValidation() {
    }

    /** Applies strict resource-group checks when historical overlays preserve those groups. */
    @SuppressWarnings("unused")
    public static void validate(RecipeProcess process, RecipeDefinitionIndex index) {
        validate(process, index, true);
    }

    /**
     * Set stableResourceGroups=false whenever a registered external resource overlay could have
     * changed START groups, even if its layers have since been removed. In that case the host must
     * validate historical group membership and completeness. Current layers cannot establish history.
     * The built-in checkpoint/selector completeness walk assumes their structural scheduling was
     * not historically changed by an external overlay; those extensions require host validation.
     */
    public static void validate(RecipeProcess process, RecipeDefinitionIndex index, boolean stableResourceGroups) {
        validate(process, index, stableResourceGroups, true);
    }

    /**
     * Set stableStructure=false for historical external checkpoint/selector overlays. Paths and
     * effect ownership are still checked, while candidate indices and activation/completeness must
     * be checked by the host using its own historical state.
     */
    public static void validate(RecipeProcess process, RecipeDefinitionIndex index, boolean stableResourceGroups,
                                boolean stableStructure) {
        Objects.requireNonNull(process, "process").requirePersistenceBoundary();
        Objects.requireNonNull(index, "index");
        var lockComponent = process.components().get(ResourceInputLocks.TYPE);
        Map<ResourceInputLocks.Key, AEKey> locks = lockComponent == null ? Map.of() : lockComponent.snapshot();
        var selectionComponent = process.components().get(SelectiveState.TYPE);
        Map<RequirementPath, SelectiveState.SelectionSnapshot> selections = selectionComponent == null ? Map.of() : selectionComponent.snapshot();
        var attributeComponent = process.components().get(RequirementAttributeState.TYPE);
        var attributes = attributeComponent == null ? new RequirementAttributeState.Snapshot(List.of(), List.of()) : attributeComponent.snapshot();
        var receipts = selectionComponent == null ? List.<SelectiveState.AbortReceipt>of() : selectionComponent.abortReceipts();
        validateAttributeReferences(index, attributes);
        validateAbortReceipts(process, index, receipts);
        boolean aborted = process.state().lifecycle() == RecipeProcess.Lifecycle.FAILED || process.state().lifecycle() == RecipeProcess.Lifecycle.CANCELLED;
        if (aborted) {
            if (!locks.isEmpty() || !selections.isEmpty() || !attributes.owned().isEmpty()) {
                throw invalid("Aborted process retains live built-in ownership");
            }
            return;
        }
        if (!attributes.aborted().isEmpty() || !receipts.isEmpty()) {
            throw invalid("Non-aborted process contains abort receipts");
        }
        validateLocks(process, index, locks, stableResourceGroups);
        validateSelections(process, index, selections, stableStructure);
        if (!process.state().started()) {
            if (!locks.isEmpty() || !selections.isEmpty() || !attributes.owned().isEmpty()) {
                throw invalid("Unstarted process contains committed START decisions");
            }
            return;
        }
        if (!stableStructure) {
            return;
        }
        var walker = new Walker(process, index, locks, selections, stableResourceGroups);
        var ongoing = new Execution(true, process.state().lifecycle() == RecipeProcess.Lifecycle.COMPLETED, Window.NONE);
        for (var root : process.recipe().orderedRequirements()) {
            walker.require(root, root.path(null), ongoing);
        }
    }

    private static void validateAttributeReferences(RecipeDefinitionIndex index, RequirementAttributeState.Snapshot snapshot) {
        var seen = new ObjectOpenHashSet<RequirementAttributeState.Reference>();
        for (var references : List.of(snapshot.owned(), snapshot.aborted())) {
            for (var reference : references) {
                if (index.require(reference.path()).type() != RecipeRequirements.ATTRIBUTES || !seen.add(reference)) {
                    throw invalid("Unknown or duplicate requirement attribute ownership: " + reference);
                }
            }
        }
        // The component binds exact installed identities and checks receipt absence. Historical
        // attribute overlays may change the attribute ID; external historical validators still apply.
    }

    private static void validateAbortReceipts(RecipeProcess process, RecipeDefinitionIndex index, List<SelectiveState.AbortReceipt> receipts) {
        var paths = new ObjectOpenHashSet<RequirementPath>();
        var modifiers = new ObjectOpenHashSet<SelectiveState.ModifierReference>();
        var layers = new ObjectOpenHashSet<SelectiveState.OverlayReference>();
        var attributes = process.attributes().localSnapshot();
        var overlays = process.components().get(RecipeOverlays.TYPE);
        for (var receipt : receipts) {
            var path = receipt.selectionPath();
            if (index.require(path).type() != RecipeRequirements.SELECTIVE || !paths.add(path)) {
                throw invalid("Unknown or duplicate selector abort receipt: " + path);
            }
            String framed = path.toString().length() + ":" + path;
            for (var reference : receipt.modifiers()) {
                var local = attributes.get(reference.attribute());
                if (!reference.id().startsWith("selective/" + framed + "/") || !modifiers.add(reference)
                    || local != null && local.modifiers().containsKey(reference.id())) {
                    throw invalid("Invalid or still installed aborted selector modifier: " + reference.id());
                }
            }
            for (var reference : receipt.overlays()) {
                if (!reference.layerId().startsWith("selective_overlay/" + framed) || !layers.add(reference)
                    || index.require(reference.path()) != reference.target()
                    || overlays != null && overlays.hasLayer(reference.target(), reference.path(), reference.layerId())) {
                    throw invalid("Invalid or still installed aborted selector overlay: " + reference.layerId());
                }
            }
        }
    }

    private static void validateLocks(RecipeProcess process, RecipeDefinitionIndex index,
                                      Map<ResourceInputLocks.Key, AEKey> locks, boolean stableResourceGroups) {
        for (var entry : locks.entrySet()) {
            var key = Objects.requireNonNull(entry.getKey(), "input lock");
            var chosen = Objects.requireNonNull(entry.getValue(), "chosen input");
            var definition = index.require(key.requirement());
            if (definition.type() != RecipeRequirements.RESOURCES || key.stage() != RecipeStage.START
                || key.groupIndex() < 0) {
                throw invalid("Input lock does not identify a built-in START resource group: " + key);
            }
            if (!stableResourceGroups) {
                continue;
            }
            var payload = resources(process, definition, key.requirement());
            boolean valid = switch (key.kind()) {
                case FUZZY -> key.groupIndex() < payload.fuzzyInputs().size()
                    && payload.fuzzyInputs().get(key.groupIndex()).candidates().contains(chosen);
                case DYNAMIC -> key.groupIndex() < payload.dynamicInputs().size()
                    && payload.dynamicInputs().get(key.groupIndex()).matcher().matches(chosen,
                    payload.dynamicInputs().get(key.groupIndex()).pattern());
            };
            if (!valid) {
                throw invalid("Input lock is absent from its stable resource group: " + key);
            }
        }
    }

    private static void validateSelections(RecipeProcess process, RecipeDefinitionIndex index,
                                           Map<RequirementPath, SelectiveState.SelectionSnapshot> selections,
                                           boolean stableStructure) {
        var attributes = process.attributes().localSnapshot();
        var overlays = process.components().get(RecipeOverlays.TYPE);
        var modifiersClaimed = new ObjectOpenHashSet<SelectiveState.ModifierReference>();
        var overlaysClaimed = new ObjectOpenHashSet<SelectiveState.OverlayReference>();
        for (var entry : selections.entrySet()) {
            var path = Objects.requireNonNull(entry.getKey(), "selection path");
            var definition = index.require(path);
            if (definition.type() != RecipeRequirements.SELECTIVE || !(definition.payload() instanceof SelectiveRequirement payload)) {
                throw invalid("Selection path does not identify a built-in selector: " + path);
            }
            var selection = Objects.requireNonNull(entry.getValue(), "selection");
            if (!selection.committed() || selection.index() < -1 || stableStructure && selection.index() >= payload.candidates().size()) {
                throw invalid("Selection has an invalid committed candidate index: " + path);
            }
            if ((selection.index() == -1 || selection.completed()) && (!selection.modifiers().isEmpty() || !selection.overlays().isEmpty())) {
                throw invalid("Disabled or completed selection owns live effects: " + path);
            }
            if (stableStructure && !selection.completed() && hasKnownCheckpointAncestor(path, index)) {
                throw invalid("A recorded checkpoint selection has not completed its full lifecycle: " + path);
            }
            for (var reference : selection.modifiers()) {
                if (!modifiersClaimed.add(reference)) {
                    throw invalid("Local modifier is owned by multiple selections");
                }
                var attribute = attributes.get(reference.attribute());
                if (attribute == null || !attribute.modifiers().containsKey(reference.id())) {
                    throw invalid("Selection-owned local modifier is absent: " + reference.id());
                }
            }
            for (var reference : selection.overlays()) {
                if (!overlaysClaimed.add(reference)) {
                    throw invalid("Overlay layer is owned by multiple selections");
                }
                if (index.require(reference.path()) != reference.target() || reference.target() != reference.target().definition()
                    || overlays == null || !overlays.hasLayer(reference.target(), reference.path(), reference.layerId())) {
                    throw invalid("Selection-owned overlay is absent or its target is not canonical: " + reference.layerId());
                }
            }
        }
    }

    private static boolean hasKnownCheckpointAncestor(RequirementPath path, RecipeDefinitionIndex index) {
        for (var parent = path.parent(); parent != null; parent = parent.parent()) {
            var type = index.require(parent).type();
            if (type == RecipeRequirements.CHECKPOINT) {
                return true;
            }
            if (type != RecipeRequirements.SELECTIVE) {
                return false;
            }
        }
        return false;
    }

    private static ResourceRequirement resources(RecipeProcess process, RecipeRequirement<?> definition, RequirementPath path) {
        var overlays = process.components().get(RecipeOverlays.TYPE);
        var effective = overlays == null ? definition : overlays.resolve(definition, path);
        if (!(effective.payload() instanceof ResourceRequirement payload)) {
            throw invalid("Resource requirement has an incompatible effective payload: " + path);
        }
        return payload;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private record Walker(RecipeProcess process, RecipeDefinitionIndex index, Map<ResourceInputLocks.Key, AEKey> locks,
                           Map<RequirementPath, SelectiveState.SelectionSnapshot> selections, boolean stableResourceGroups) {

        private void require(RecipeRequirement<?> requirement, RequirementPath path, Execution execution) {
            if (index.require(path) != requirement) {
                throw invalid("Built-in execution target differs from its canonical definition: " + path);
            }
            if (requirement.type() == RecipeRequirements.RESOURCES) {
                if (stableResourceGroups) {
                    var payload = resources(process, requirement, path);
                    requireGroups(path, ResourceInputLocks.Kind.FUZZY, payload.fuzzyInputs().size());
                    requireGroups(path, ResourceInputLocks.Kind.DYNAMIC, payload.dynamicInputs().size());
                }
            } else if (requirement.type() == RecipeRequirements.SELECTIVE && requirement.payload() instanceof SelectiveRequirement payload) {
                var selection = selections.get(path);
                if (selection == null || selection.completed() != execution.endCompleted()) {
                    throw invalid("Required selection is absent or has inconsistent END completion: " + path);
                }
                for (int candidateIndex = 0; candidateIndex < payload.candidates().size(); candidateIndex++) {
                    var candidate = payload.candidates().get(candidateIndex);
                    if (candidateIndex == selection.index()) {
                        require(candidate, candidate.path(path), execution);
                    } else {
                        requireAbsent(candidate.path(path));
                    }
                }
            } else if (requirement.type() == RecipeRequirements.CHECKPOINT
                && requirement.payload() instanceof CheckpointRequirement(var time, var child, var scaled)) {
                var childPath = child.path(path);
                if (time == 0) {
                    require(child, childPath, new Execution(false, true, Window.ZERO));
                } else if (execution.repeatedTicks()) {
                    boolean reached = scaled ? process.state().progress() >= time : process.state().successfulTicks() >= time;
                    if (reached) {
                        require(child, childPath, new Execution(false, true, new Window(time, scaled)));
                    } else {
                        requireAbsent(childPath);
                    }
                } else if (execution.window() == Window.ZERO) {
                    requireAbsent(childPath);
                } else if (execution.window().time() == time && execution.window().scaled() == scaled) {
                    require(child, childPath, execution);
                } else if (!execution.window().scaled() && !scaled) {
                    // Successful ticks advance by exactly one; distinct unscaled thresholds cannot share a tick.
                    requireAbsent(childPath);
                }
                // Other positive nested thresholds depend on the unrecorded historical tick interval.
            }
            // External wrapper children may be conditional; their codec/host validates activation.
        }

        private void requireGroups(RequirementPath path, ResourceInputLocks.Kind kind, int count) {
            for (int groupIndex = 0; groupIndex < count; groupIndex++) {
                if (!locks.containsKey(new ResourceInputLocks.Key(path, RecipeStage.START, kind, groupIndex))) {
                    throw invalid("Committed resource START decision is absent: " + path + " " + kind + " " + groupIndex);
                }
            }
        }

        private void requireAbsent(RequirementPath subtree) {
            for (var path : selections.keySet()) {
                if (within(path, subtree)) {
                    throw invalid("Selection belongs to an unexecuted built-in branch: " + path);
                }
            }
            for (var key : locks.keySet()) {
                if (within(key.requirement(), subtree)) {
                    throw invalid("Input lock belongs to an unexecuted built-in branch: " + key);
                }
            }
        }

        private static boolean within(RequirementPath path, RequirementPath subtree) {
            for (var ancestor = path; ancestor != null; ancestor = ancestor.parent()) {
                if (ancestor.equals(subtree)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Execution(boolean repeatedTicks, boolean endCompleted, Window window) { }

    private record Window(int time, boolean scaled) {
        private static final Window NONE = new Window(-1, false);
        private static final Window ZERO = new Window(0, false);
    }
}
