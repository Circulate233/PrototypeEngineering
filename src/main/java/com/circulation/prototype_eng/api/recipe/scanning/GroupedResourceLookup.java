package com.circulation.prototype_eng.api.recipe.scanning;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.storage.ResourceInputGroups;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Each inventory group receives a separate lookup; only recipe identities and maxima are merged.
 */
public final class GroupedResourceLookup implements RecipeCandidateIndex.Cursor {
    private final MachineInstance machine;
    private final Function<ResourceInputGroups.Group, RecipeCandidateIndex.Cursor> factory;
    private final ToIntFunction<MachineRecipe> priority;
    private final ObjectArrayList<RecipeCandidateIndex.Cursor> observed = new ObjectArrayList<>();
    private final ObjectArrayList<MachineRecipe> results = new ObjectArrayList<>();
    private final Int2ObjectAVLTreeMap<MachineRecipe> chosen = new Int2ObjectAVLTreeMap<>();
    private final Reference2IntOpenHashMap<MachineRecipe> limits = new Reference2IntOpenHashMap<>();
    private ResourceInputGroups.Capture capture;
    private RecipeCandidateIndex.Cursor active;
    private Iterator<MachineRecipe> pending;
    private Iterator<MachineRecipe> ordered;
    private int groupIndex;
    private boolean complete;

    public GroupedResourceLookup(MachineInstance machine, ToIntFunction<MachineRecipe> priority,
                                 Function<ResourceInputGroups.Group, RecipeCandidateIndex.Cursor> factory) {
        this.machine = Objects.requireNonNull(machine);
        this.priority = Objects.requireNonNull(priority);
        this.factory = Objects.requireNonNull(factory);
    }

    @Override
    public boolean advance(RecipeWorkBudget budget) {
        if (complete) return true;
        if (capture == null) {
            capture = MachineHatches.get(machine).inputGroups();
        }
        if (!current()) return false;
        while (active != null || groupIndex < capture.groups().size()) {
            if (active == null) {
                if (groupIndex > 0 && !budget.take()) return false;
                active = Objects.requireNonNull(factory.apply(capture.groups().get(groupIndex++)));
                observed.add(active);
            }
            if (pending == null) {
                if (!active.advance(budget)) return false;
                Collection<MachineRecipe> opinion = active.result();
                if (opinion == null) throw new IllegalStateException("Grouped input lookup needs a complete opinion");
                pending = opinion.iterator();
            }
            while (pending.hasNext()) {
                if (!budget.take()) return false;
                var recipe = pending.next();
                chosen.put(priority.applyAsInt(recipe), recipe);
                limits.put(recipe, Math.max(limits.getInt(recipe), active.inputParallelismLimit(recipe)));
            }
            pending = null;
            active = null;
        }
        if (ordered == null) ordered = chosen.values().iterator();
        while (ordered.hasNext()) {
            if (!budget.take()) return false;
            results.add(ordered.next());
        }
        complete = true;
        return true;
    }

    @Override
    public boolean current() {
        if (capture != null && !capture.current(MachineHatches.get(machine))) return false;
        for (var lookup : observed) if (!lookup.current()) return false;
        return true;
    }

    @Override
    public @NotNull List<MachineRecipe> result() {
        if (!complete || !current()) throw new IllegalStateException("Grouped input lookup is incomplete or stale");
        return results;
    }

    @Override
    public int inputParallelismLimit(MachineRecipe recipe) {
        if (!complete) throw new IllegalStateException("Grouped input lookup is incomplete");
        return limits.getOrDefault(recipe, Integer.MAX_VALUE);
    }

    @Override
    @SuppressWarnings("ReturnOfInnerClass") // The proof retains this lookup's exact group and child observations.
    public RecipeCandidateIndex.Validation beginValidation() {
        return new RecipeCandidateIndex.Validation() {
            private int cursor;
            private RecipeCandidateIndex.Validation child;
            private boolean ownerChecked;
            private boolean done;
            private boolean valid = true;

            @Override
            public boolean advance(RecipeWorkBudget budget) {
                if (done) return true;
                if (!ownerChecked) {
                    if (!budget.take()) return false;
                    ownerChecked = true;
                    if (capture != null && !capture.current(MachineHatches.get(machine))) valid = false;
                }
                while (valid && cursor < observed.size()) {
                    if (child == null) child = observed.get(cursor).beginValidation();
                    if (!child.advance(budget)) return false;
                    valid = child.current();
                    child = null;
                    cursor++;
                }
                done = true;
                return true;
            }

            @Override
            public boolean current() {
                if (!done) throw new IllegalStateException("Group proof is incomplete");
                return valid;
            }
        };
    }
}
