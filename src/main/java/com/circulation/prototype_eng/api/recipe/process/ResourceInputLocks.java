package com.circulation.prototype_eng.api.recipe.process;

import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/** Per-process chosen resource identities, not inventory mutexes or quantity reservations. */
public final class ResourceInputLocks implements ProcessAbortListener {
    public static final ComponentType<RecipeProcess, ResourceInputLocks> TYPE = new ComponentType<>(
        new ResourceLocation(Tags.MOD_ID, "resource_input_locks"), _ -> new ResourceInputLocks(), ObjectLists.emptyList());
    private Object2ObjectLinkedOpenHashMap<Key, AEKey> choices = new Object2ObjectLinkedOpenHashMap<>();

    public static ResourceInputLocks getOrCreate(RecipeProcess process) {
        var existing = process.components().get(TYPE);
        return existing == null ? process.components().create(TYPE, process) : existing;
    }

    @Nullable
    public AEKey get(Key key) {
        return choices.get(key);
    }

    /** Installs an absent choice; an existing different choice is a broken process-state contract. */
    public boolean putIfAbsent(Key key, AEKey chosen) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(chosen);
        AEKey previous = choices.putIfAbsent(key, chosen);
        if (previous != null && !previous.equals(chosen)) {
            throw new IllegalStateException("Input choice changed for " + key);
        }
        return previous == null;
    }

    /** Undo only the expected choice made by the current acquisition. */
    public void remove(Key key, AEKey expected) {
        if (!choices.remove(key, expected)) {
            throw new IllegalStateException("Input choice lost before rollback: " + key);
        }
    }

    /** Immutable local state for host persistence; AEKey follows AE2's immutable identity contract. */
    public Map<Key, AEKey> snapshot() {
        return Collections.unmodifiableMap(new Object2ObjectLinkedOpenHashMap<>(choices));
    }

    /** Restore only at an exclusive lifecycle boundary with no acquired requirement transactions. */
    public void restore(Map<Key, AEKey> snapshot) {
        var restored = new Object2ObjectLinkedOpenHashMap<Key, AEKey>();
        for (var entry : snapshot.entrySet()) {
            restored.put(Objects.requireNonNull(entry.getKey()), Objects.requireNonNull(entry.getValue()));
        }
        choices = restored;
    }

    /** Choices carry no inventory ownership; terminal abort only forgets the chosen identities. */
    @Override
    public void onAborted(RecipeProcess process) {
        choices.clear();
    }

    public enum Kind { FUZZY, DYNAMIC }

    public record Key(RequirementPath requirement, RecipeStage stage, Kind kind, int groupIndex) {
        public Key {
            Objects.requireNonNull(requirement);
            Objects.requireNonNull(stage);
            Objects.requireNonNull(kind);
            if (groupIndex < 0) { throw new IllegalArgumentException("Negative input group index"); }
        }
    }
}
