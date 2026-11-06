package com.circulation.prototype_eng.api.recipe.selective;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Process-owned selection decisions and their exact local attribute additions, keyed by nested
 * requirement identity. Provisional choices are visible during composite acquisition and are
 * discarded on START rollback. NBT restoration and cancellation recovery are separate migration work.
 */
public final class SelectiveState {
    public static final ComponentType<RecipeProcess, SelectiveState> TYPE =
        new ComponentType<>(new ResourceLocation(Tags.MOD_ID, "selective_state"), SelectiveState::new, ObjectLists.emptyList());

    private final RecipeProcess process;
    private final Object2ObjectOpenHashMap<RequirementPath, Selection> selections = new Object2ObjectOpenHashMap<>();

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
        var selection = new Selection(process.attributes(), index);
        if (selections.putIfAbsent(Objects.requireNonNull(path), selection) != null) {
            throw new IllegalStateException("Selection is already decided: " + path);
        }
        return selection;
    }

    /** Abandons only the provisional/committing transaction's own decision. */
    public void discard(RequirementPath path, Selection expected) {
        selections.remove(path, expected);
    }

    public static final class Selection {
        private final MachineAttributes attributes;
        private final int index;
        private final Object2ObjectLinkedOpenHashMap<ModifierReference, OwnedModifier> modifiers = new Object2ObjectLinkedOpenHashMap<>();
        private boolean committed;
        private boolean completed;

        private Selection(MachineAttributes attributes, int index) {
            this.attributes = attributes;
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

        /** Registers ownership before the mutation, so a partially throwing apply remains cleanable. */
        public void putModifier(ResourceLocation attribute, AttributeModifier modifier) {
            var reference = new ModifierReference(attribute, Objects.requireNonNull(modifier).id());
            var owned = modifiers.computeIfAbsent(reference, _ -> new OwnedModifier(reference));
            if (owned.installed) {
                attributes.removeModifier(reference.attribute(), reference.id());
                owned.installed = false;
            }
            if (!attributes.addModifier(attribute, modifier)) {
                throw new IllegalStateException("Unknown existing selective modifier: " + reference.id());
            }
            owned.installed = true;
        }

        /** Reverse-order local cleanup; never restores a layer snapshot or another selection's effects. */
        public void cleanupModifiers() {
            var owned = new ObjectArrayList<>(modifiers.values());
            Throwable failure = null;
            for (int index = owned.size() - 1; index >= 0; index--) {
                var modifier = owned.get(index);
                if (!modifier.installed) { continue; }
                try {
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
            cleanupModifiers();
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

        private OwnedModifier(ModifierReference reference) {
            this.reference = reference;
        }
    }
}
