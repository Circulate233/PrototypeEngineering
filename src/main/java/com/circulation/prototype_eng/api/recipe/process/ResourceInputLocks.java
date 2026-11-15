package com.circulation.prototype_eng.api.recipe.process;

import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Per-process chosen resource identities, not inventory mutexes or quantity reservations.
 */
public final class ResourceInputLocks implements ProcessAbortListener {
    public static final ComponentType<RecipeProcess, ResourceInputLocks> TYPE = new ComponentType<>(
        new ResourceLocation(Tags.MOD_ID, "resource_input_locks"), _ -> new ResourceInputLocks(), ObjectLists.emptyList());
    private Map<Key, AEKey> choices = new Object2ObjectLinkedOpenHashMap<>();

    public static ResourceInputLocks getOrCreate(RecipeProcess process) {
        var existing = process.components().get(TYPE);
        return existing == null ? process.components().create(TYPE, process) : existing;
    }

    @Nullable
    public AEKey get(Key key) {
        return choices.get(key);
    }

    /**
     * Installs an absent choice; an existing different choice is a broken process-state contract.
     */
    public boolean putIfAbsent(Key key, AEKey chosen) {
        AEKey previous = choices.putIfAbsent(key, chosen);
        if (previous != null && !previous.equals(chosen)) {
            throw new IllegalStateException("Input choice changed for " + key);
        }
        return previous == null;
    }

    /**
     * Undo only the expected choice made by the current acquisition.
     */
    public void remove(Key key, AEKey expected) {
        if (!choices.remove(key, expected)) {
            throw new IllegalStateException("Input choice lost before rollback: " + key);
        }
    }

    /**
     * Local state borrowed at an exclusive process boundary. Readers must not modify this map.
     */
    public Map<Key, AEKey> snapshot() {
        return choices;
    }

    /**
     * Restore transfers the completed mutable map at an exclusive lifecycle boundary.
     */
    public void restore(Map<Key, AEKey> snapshot) {
        choices = snapshot;
    }

    /**
     * Choices carry no inventory ownership; terminal abort only forgets the chosen identities.
     */
    @Override
    public void onAborted(RecipeProcess process) {
        choices.clear();
    }

    public enum Kind {FUZZY, DYNAMIC}

    public record Key(RequirementPath requirement, RecipeStage stage, Kind kind, int groupIndex) {
        public Key {
            if (groupIndex < 0) {
                throw new IllegalArgumentException("Negative input group index");
            }
        }
    }
}
