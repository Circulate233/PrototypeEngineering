package com.circulation.prototype_eng.api.recipe.process;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.RenamedAttributeModifier;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** Exact local attribute ownership for built-in requirements, including nested canonical paths. */
public final class RequirementAttributeState implements ProcessAbortListener {
    public static final ComponentType<RecipeProcess, RequirementAttributeState> TYPE = new ComponentType<>(
        new ResourceLocation(Tags.MOD_ID, "requirement_attributes"), RequirementAttributeState::new, ObjectLists.emptyList());

    private final RecipeProcess process;
    private final Object2ObjectLinkedOpenHashMap<Reference, AttributeModifier> owned = new Object2ObjectLinkedOpenHashMap<>();
    private final ObjectLinkedOpenHashSet<Reference> aborted = new ObjectLinkedOpenHashSet<>();

    private RequirementAttributeState(RecipeProcess process) { this.process = process; }

    public static RequirementAttributeState getOrCreate(RecipeProcess process) {
        var existing = process.components().get(TYPE);
        return existing == null ? process.components().create(TYPE, process) : existing;
    }

    /** Existing foreign IDs remain untouched and are never claimed by this requirement. */
    @Nullable
    public Installation install(RequirementPath path, ResourceLocation attribute, AttributeModifier modifier) {
        var reference = new Reference(path, attribute);
        var previous = owned.get(reference);
        if (previous != null) {
            requireInstalled(reference, previous);
            return null;
        }
        var renamed = new RenamedAttributeModifier(reference.modifierId(), Objects.requireNonNull(modifier));
        if (!process.attributes().addModifier(attribute, renamed)) { return null; }
        owned.put(reference, renamed);
        return new Installation(reference, renamed);
    }

    /** Freezes the exact objects for deferred END completion; START parameters need not remain current. */
    public List<Installation> ownedAt(RequirementPath path) {
        var result = new ObjectArrayList<Installation>();
        owned.forEach((reference, modifier) -> {
            if (reference.path().equals(path)) {
                requireInstalled(reference, modifier);
                result.add(new Installation(reference, modifier));
            }
        });
        return ObjectLists.unmodifiable(result);
    }

    /** Used by START rollback and END completion; only this exact installation can be removed. */
    public void remove(Installation installation) {
        remove(installation, false);
    }

    private void remove(Installation installation, boolean abort) {
        var reference = installation.reference();
        if (owned.get(reference) != installation.modifier()) {
            throw new IllegalStateException("Requirement attribute ownership changed: " + reference);
        }
        requireInstalled(reference, installation.modifier());
        if (!process.attributes().removeModifier(reference.attribute(), reference.modifierId())) {
            throw new IllegalStateException("Requirement attribute disappeared before cleanup: " + reference);
        }
        owned.remove(reference);
        if (abort) { aborted.add(reference); }
    }

    private void requireInstalled(Reference reference, AttributeModifier expected) {
        if (process.attributes().localModifier(reference.attribute(), reference.modifierId()) != expected) {
            throw new IllegalStateException("Owned requirement modifier changed: " + reference);
        }
    }

    /** Successful abort receipts verify absence; unrelated factory attributes are preserved. */
    @Override
    public void onAborted(RecipeProcess owner) {
        if (owner != process) { throw new IllegalArgumentException("Attribute state belongs to another process"); }
        var installations = new ObjectArrayList<Installation>();
        owned.forEach((reference, modifier) -> installations.add(new Installation(reference, modifier)));
        Throwable failure = null;
        for (int index = installations.size() - 1; index >= 0; index--) {
            try {
                remove(installations.get(index), true);
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) { failure = cleanup; }
                else if (failure != cleanup) { failure.addSuppressed(cleanup); }
            }
        }
        if (failure instanceof RuntimeException runtime) { throw runtime; }
        if (failure instanceof Error error) { throw error; }
    }

    public Snapshot snapshot() {
        owned.forEach(this::requireInstalled);
        for (var reference : aborted) { requireAbsent(reference); }
        return new Snapshot(new ObjectArrayList<>(owned.keySet()), new ObjectArrayList<>(aborted));
    }

    /** Binds restored local modifiers without applying them; receipts never replay cleanup. */
    public void restore(Snapshot snapshot) {
        if (!owned.isEmpty() || !aborted.isEmpty()) { throw new IllegalStateException("Attribute restore requires fresh state"); }
        var restored = new Object2ObjectLinkedOpenHashMap<Reference, AttributeModifier>();
        var receipts = new ObjectLinkedOpenHashSet<Reference>();
        for (var reference : snapshot.owned()) {
            var modifier = process.attributes().localModifier(reference.attribute(), reference.modifierId());
            if (!(modifier instanceof RenamedAttributeModifier) || restored.putIfAbsent(reference, modifier) != null) {
                throw new IllegalArgumentException("Absent, invalid or duplicate restored requirement attribute: " + reference);
            }
        }
        for (var reference : snapshot.aborted()) {
            requireAbsent(reference);
            if (restored.containsKey(reference) || !receipts.add(reference)) {
                throw new IllegalArgumentException("Duplicate or live requirement attribute abort receipt: " + reference);
            }
        }
        owned.putAll(restored);
        aborted.addAll(receipts);
    }

    private void requireAbsent(Reference reference) {
        if (process.attributes().localModifier(reference.attribute(), reference.modifierId()) != null) {
            throw new IllegalArgumentException("Aborted requirement modifier remains installed: " + reference);
        }
    }

    public record Reference(RequirementPath path, ResourceLocation attribute) {
        public Reference {
            Objects.requireNonNull(path);
            Objects.requireNonNull(attribute);
        }
        public String modifierId() { return "req:" + path + ":" + attribute; }
    }

    public record Installation(Reference reference, AttributeModifier modifier) {
        public Installation {
            Objects.requireNonNull(reference);
            Objects.requireNonNull(modifier);
        }
    }

    public record Snapshot(List<Reference> owned, List<Reference> aborted) {
        public Snapshot {
            owned = ObjectLists.unmodifiable(new ObjectArrayList<>(owned));
            aborted = ObjectLists.unmodifiable(new ObjectArrayList<>(aborted));
        }
    }
}
