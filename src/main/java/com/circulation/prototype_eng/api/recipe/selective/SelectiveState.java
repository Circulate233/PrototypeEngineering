package com.circulation.prototype_eng.api.recipe.selective;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayType;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Process-owned selection decisions and their exact local attribute/requirement additions, keyed by nested
 * requirement identity. Provisional choices are visible during composite acquisition and are
 * discarded on START rollback. Persistence binds installed effects without rerunning their creation;
 * cancellation recovery is a separate lifecycle concern.
 */
public final class SelectiveState {
    public static final ComponentType<RecipeProcess, SelectiveState> TYPE =
        new ComponentType<>(new ResourceLocation(Tags.MOD_ID, "selective_state"), SelectiveState::new, ObjectLists.emptyList());

    private final RecipeProcess process;
    private Object2ObjectLinkedOpenHashMap<RequirementPath, Selection> selections = new Object2ObjectLinkedOpenHashMap<>();

    private SelectiveState(RecipeProcess process) {
        this.process = process;
    }

    public static SelectiveState getOrCreate(RecipeProcess process) {
        var state = process.components().get(TYPE);
        return state == null ? process.components().create(TYPE, process) : state;
    }

    @Nullable
    public Selection selection(RequirementPath path) {
        return selections.get(Objects.requireNonNull(path));
    }

    /** Managed by the selector while holding exclusive ownership of this process. */
    public Selection begin(RequirementPath path, int index) {
        if (index < -1) {
            throw new IllegalArgumentException("Invalid selection index");
        }
        var selection = new Selection(process, index);
        if (selections.putIfAbsent(Objects.requireNonNull(path), selection) != null) {
            throw new IllegalStateException("Selection is already decided: " + path);
        }
        return selection;
    }

    /** Abandons only the provisional/committing transaction's own decision. */
    public void discard(RequirementPath path, Selection expected) {
        selections.remove(path, expected);
    }

    public Map<RequirementPath, SelectionSnapshot> snapshot() {
        var snapshot = new Object2ObjectLinkedOpenHashMap<RequirementPath, SelectionSnapshot>();
        selections.forEach((path, selection) -> snapshot.put(path, selection.snapshot()));
        return Collections.unmodifiableMap(snapshot);
    }

    /** Binds existing installed effects in a fresh component; no modifier application or layer put. */
    public void restore(Map<RequirementPath, SelectionSnapshot> snapshot) {
        if (!selections.isEmpty()) { throw new IllegalStateException("Restore requires an empty selective component"); }
        var existingOverlays = process.components().get(RecipeOverlays.TYPE);
        if (existingOverlays != null) { existingOverlays.snapshot(); }
        var restored = new Object2ObjectLinkedOpenHashMap<RequirementPath, Selection>();
        var attributes = process.attributes().localSnapshot();
        var claimedModifiers = new ObjectOpenHashSet<ModifierReference>();
        var claimedOverlays = new ObjectOpenHashSet<OverlayReference>();
        for (var entry : snapshot.entrySet()) {
            var path = Objects.requireNonNull(entry.getKey());
            var saved = Objects.requireNonNull(entry.getValue());
            if (!saved.committed() || saved.index() < -1) { throw new IllegalArgumentException("Invalid committed selection snapshot"); }
            if ((saved.index() == -1 || saved.completed()) && (!saved.modifiers().isEmpty() || !saved.overlays().isEmpty())) {
                throw new IllegalArgumentException("Disabled or completed selection owns live effects");
            }
            var selection = new Selection(process, saved.index());
            selection.committed = true;
            selection.startCompleted = true;
            selection.completed = saved.completed();
            for (var reference : saved.modifiers()) {
                Objects.requireNonNull(reference);
                if (!claimedModifiers.add(reference)) { throw new IllegalArgumentException("Modifier ownership is repeated"); }
                var attribute = attributes.get(reference.attribute());
                var modifier = attribute == null ? null : attribute.modifiers().get(reference.id());
                if (modifier == null) { throw new IllegalArgumentException("Owned local modifier is absent: " + reference.id()); }
                var owned = new OwnedModifier(reference);
                owned.installed = true;
                owned.expected = modifier;
                selection.modifiers.put(reference, owned);
            }
            for (var reference : saved.overlays()) {
                Objects.requireNonNull(reference);
                if (!claimedOverlays.add(reference)) { throw new IllegalArgumentException("Overlay ownership is repeated"); }
                var overlays = process.components().get(RecipeOverlays.TYPE);
                if (overlays == null || !overlays.hasLayer(reference.target(), reference.path(), reference.layerId())) {
                    throw new IllegalArgumentException("Owned overlay layer is absent: " + reference.layerId());
                }
                var owned = new OwnedOverlay(reference);
                owned.claim = overlays.claim(reference.target(), reference.path(), reference.layerId());
                selection.ownedOverlays.put(reference, owned);
            }
            if (restored.putIfAbsent(path, selection) != null) { throw new IllegalArgumentException("Duplicate selection path"); }
        }
        selections = restored;
    }

    /** The completed START boundary is implicit in this save; pending or provisional state cannot produce it. */
    public record SelectionSnapshot(boolean committed, int index, boolean completed, List<ModifierReference> modifiers,
                                    List<OverlayReference> overlays) {
        public SelectionSnapshot {
            modifiers = ObjectLists.unmodifiable(new ObjectArrayList<>(modifiers));
            overlays = ObjectLists.unmodifiable(new ObjectArrayList<>(overlays));
        }
    }

    public record OverlayReference(RequirementPath path, RecipeRequirement<?> target, String layerId) {
        public OverlayReference {
            Objects.requireNonNull(path);
            Objects.requireNonNull(target);
            if (target != target.definition()) { throw new IllegalArgumentException("Owned overlay target must be canonical"); }
            if (!target.id().equals(path.requirementId()) || !target.type().id().equals(path.typeId())) {
                throw new IllegalArgumentException("Owned overlay path does not identify its target");
            }
            if (Objects.requireNonNull(layerId).isBlank()) { throw new IllegalArgumentException("Blank overlay layer id"); }
        }
    }

    public static final class Selection {
        private final RecipeProcess process;
        private final MachineAttributes attributes;
        private final int index;
        private final Object2ObjectLinkedOpenHashMap<ModifierReference, OwnedModifier> modifiers = new Object2ObjectLinkedOpenHashMap<>();
        private final ObjectArrayList<RecipeOverlays.Change> pendingOverlays = new ObjectArrayList<>();
        private final Object2ObjectLinkedOpenHashMap<OverlayReference, OwnedOverlay> ownedOverlays = new Object2ObjectLinkedOpenHashMap<>();
        private boolean committed;
        private boolean startCompleted;
        private boolean completed;

        private Selection(RecipeProcess process, int index) {
            this.process = process;
            attributes = process.attributes();
            this.index = index;
        }

        public int index() { return index; }
        public boolean committed() { return committed; }
        public boolean completed() { return completed; }

        public List<ModifierReference> modifiers() {
            var installed = new ObjectArrayList<ModifierReference>();
            for (var modifier : modifiers.values()) {
                if (modifier.installed) { installed.add(modifier.reference); }
            }
            return ObjectLists.unmodifiable(installed);
        }

        public void markCommitted() {
            committed = true;
        }

        public SelectionSnapshot snapshot() {
            if (!committed || !startCompleted || !pendingOverlays.isEmpty()) {
                throw new IllegalStateException("Selection START has not completed");
            }
            var local = attributes.localSnapshot();
            for (var owned : modifiers.values()) {
                if (!owned.installed) { continue; }
                var attribute = local.get(owned.reference.attribute());
                if (attribute == null || attribute.modifiers().get(owned.reference.id()) != owned.expected) {
                    throw new IllegalStateException("Owned local modifier changed before snapshot");
                }
            }
            var overlayReferences = new ObjectArrayList<OverlayReference>();
            for (var owned : ownedOverlays.values()) {
                Objects.requireNonNull(owned.claim, "owned overlay claim").validate();
                overlayReferences.add(owned.reference);
            }
            var modifierReferences = modifiers();
            if ((index == -1 || completed) && (!modifierReferences.isEmpty() || !overlayReferences.isEmpty())) {
                throw new IllegalStateException("Disabled or completed selection owns live effects");
            }
            return new SelectionSnapshot(committed, index, completed, modifierReferences, overlayReferences);
        }

        /** Promotes exact final layer identities into lifetime cleanup rights and relinquishes stage undo. */
        public void completeStart() {
            if (startCompleted) { return; }
            if (!committed) { throw new IllegalStateException("Cannot complete an uncommitted selection"); }
            Throwable failure = null;
            for (var owned : ownedOverlays.values()) {
                try {
                    Objects.requireNonNull(owned.expected, "installed overlay identity").validate();
                    owned.claim = owned.expected;
                } catch (RuntimeException | Error cleanup) {
                    failure = accumulate(failure, cleanup);
                }
            }
            for (var change : pendingOverlays) {
                try {
                    change.complete();
                } catch (RuntimeException | Error cleanup) {
                    failure = accumulate(failure, cleanup);
                }
            }
            pendingOverlays.clear();
            rethrow(failure);
            startCompleted = true;
        }

        /** Registers ownership before the mutation, so a partially throwing apply remains cleanable. */
        public void putModifier(ResourceLocation attribute, AttributeModifier modifier) {
            requireApplying();
            var reference = new ModifierReference(attribute, Objects.requireNonNull(modifier).id());
            var owned = modifiers.computeIfAbsent(reference, _ -> new OwnedModifier(reference));
            if (owned.installed) {
                var local = attributes.localSnapshot().get(reference.attribute());
                if (local == null || local.modifiers().get(reference.id()) != owned.expected) {
                    throw new IllegalStateException("Owned local modifier changed before replacement");
                }
                attributes.removeModifier(reference.attribute(), reference.id());
                owned.installed = false;
            }
            if (!attributes.addModifier(attribute, modifier)) {
                throw new IllegalStateException("Unknown existing selective modifier: " + reference.id());
            }
            owned.installed = true;
            owned.expected = modifier;
        }

        /** Records every replacement so reverse undo restores only this owner's preceding writes. */
        public <C, D> void putOverlay(RecipeRequirement<C> target, RequirementPath path, String layerId,
                                     RecipeOverlayType<C, D> type, D data) {
            requireApplying();
            var reference = new OverlayReference(path, target.definition(), layerId);
            var overlays = RecipeOverlays.getOrCreate(process);
            if (!ownedOverlays.containsKey(reference) && overlays.hasLayer(target, path, layerId)) {
                throw new IllegalStateException("Unknown existing selective overlay layer: " + layerId);
            }
            var previous = ownedOverlays.get(reference);
            if (previous != null) { Objects.requireNonNull(previous.expected).validate(); }
            pendingOverlays.add(overlays.put(target, path, layerId, type, data));
            var owned = ownedOverlays.computeIfAbsent(reference, OwnedOverlay::new);
            owned.expected = overlays.claim(target, path, layerId);
        }

        private void requireApplying() {
            if (startCompleted || completed) { throw new IllegalStateException("Selection modifier application has ended"); }
        }

        /** Tries both effect families even when an extension violates its cleanup contract. */
        public void cleanupEffects() {
            Throwable failure = null;
            if (startCompleted || pendingOverlays.isEmpty()) {
                var owned = new ObjectArrayList<>(ownedOverlays.values());
                for (int index = owned.size() - 1; index >= 0; index--) {
                    var overlay = owned.get(index);
                    try {
                        Objects.requireNonNull(overlay.claim).remove();
                        ownedOverlays.remove(overlay.reference);
                    } catch (RuntimeException | Error cleanup) {
                        failure = accumulate(failure, cleanup);
                    }
                }
            } else {
                for (int index = pendingOverlays.size() - 1; index >= 0; index--) {
                    try {
                        pendingOverlays.get(index).rollback();
                    } catch (RuntimeException | Error cleanup) {
                        failure = accumulate(failure, cleanup);
                    }
                }
            }
            try {
                cleanupModifiers();
            } catch (RuntimeException | Error cleanup) {
                failure = accumulate(failure, cleanup);
            }
            rethrow(failure);
            pendingOverlays.clear();
            ownedOverlays.clear();
        }

        /** Reverse-order local cleanup; never restores a layer snapshot or another selection's effects. */
        public void cleanupModifiers() {
            var owned = new ObjectArrayList<>(modifiers.values());
            var local = attributes.localSnapshot();
            Throwable failure = null;
            for (int index = owned.size() - 1; index >= 0; index--) {
                var modifier = owned.get(index);
                if (!modifier.installed) { continue; }
                try {
                    var attribute = local.get(modifier.reference.attribute());
                    if (attribute == null || attribute.modifiers().get(modifier.reference.id()) != modifier.expected) {
                        throw new IllegalStateException("Owned local modifier changed before cleanup");
                    }
                    attributes.removeModifier(modifier.reference.attribute(), modifier.reference.id());
                    modifier.installed = false;
                } catch (RuntimeException | Error cleanup) {
                    if (failure == null) { failure = cleanup; }
                    else if (failure != cleanup) { failure.addSuppressed(cleanup); }
                }
            }
            if (failure instanceof RuntimeException runtime) { throw runtime; }
            if (failure instanceof Error error) { throw error; }
            modifiers.clear();
        }

        /** Infallible END terminal cleanup after every enclosing business commit succeeds. */
        public void finish() {
            Throwable failure = null;
            try {
                completeStart();
            } catch (RuntimeException | Error cleanup) {
                failure = cleanup;
            }
            try {
                cleanupEffects();
            } catch (RuntimeException | Error cleanup) {
                failure = accumulate(failure, cleanup);
            }
            rethrow(failure);
            completed = true;
        }
    }

    public record ModifierReference(ResourceLocation attribute, String id) {
        public ModifierReference {
            Objects.requireNonNull(attribute);
            Objects.requireNonNull(id);
            if (id.isBlank()) { throw new IllegalArgumentException("Blank modifier id"); }
        }
    }

    private static final class OwnedModifier {
        private final ModifierReference reference;
        private boolean installed;
        @Nullable private AttributeModifier expected;

        private OwnedModifier(ModifierReference reference) {
            this.reference = reference;
        }
    }

    private static final class OwnedOverlay {
        private final OverlayReference reference;
        @Nullable private RecipeOverlays.RemovalClaim expected;
        @Nullable private RecipeOverlays.RemovalClaim claim;

        private OwnedOverlay(OverlayReference reference) { this.reference = reference; }
    }

    private static Throwable accumulate(@Nullable Throwable failure, Throwable cleanup) {
        if (failure == null) { return cleanup; }
        if (failure != cleanup) { failure.addSuppressed(cleanup); }
        return failure;
    }

    private static void rethrow(@Nullable Throwable failure) {
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
    }
}
