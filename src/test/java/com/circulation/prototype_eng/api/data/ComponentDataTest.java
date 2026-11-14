package com.circulation.prototype_eng.api.data;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeProcessFactory;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentDataTest {
    @Test
    void ownedValuesAndLaterWritesLeaveCapturedSnapshotsStable() {
        var data = new ComponentData();
        var nested = new NBTTagCompound();
        nested.setInteger("Count", 3);
        data.set("Nested", nested);
        var captured = data.snapshot();
        nested.setInteger("Count", 8);
        assertEquals(3, ((NBTTagCompound) Objects.requireNonNull(data.get("Nested"))).getInteger("Count"));
        assertEquals(3, captured.data().getCompoundTag("Nested").getInteger("Count"));
        assertEquals(1, data.revision());
        data.set("Nested", captured.data().getCompoundTag("Nested"));
        data.merge(captured.data());
        data.remove("Absent");
        assertEquals(1, data.revision());
        var changed = new NBTTagCompound();
        var replacement = new NBTTagCompound();
        replacement.setInteger("Other", 4);
        changed.setTag("Nested", replacement);
        changed.setInteger("Added", 5);
        data.merge(changed);
        replacement.setInteger("Other", 99);
        var merged = (NBTTagCompound) Objects.requireNonNull(data.get("Nested"));
        assertEquals(4, merged.getInteger("Other"));
        assertFalse(merged.hasKey("Count"));
        assertEquals(new NBTTagInt(5), data.get("Added"));
        assertEquals(2, data.revision());
        assertEquals(3, captured.data().getCompoundTag("Nested").getInteger("Count"));
        data.clear();
        assertEquals(3, data.revision());
        assertEquals(3, captured.data().getCompoundTag("Nested").getInteger("Count"));
    }

    @Test
    void savedProcessDataRestoresIntoAnIndependentActualProcess() {
        var recipe = new MachineRecipe(new ResourceLocation("data_test", "recipe"), 4, List.of());
        var process = DataComponents.withProcessData(RecipeProcessFactory.STANDARD)
                                    .create(new MachineInstance(), recipe, 42, ProbabilityTuning.defaultTuning());
        var data = Objects.requireNonNull(process.components().get(DataComponents.PROCESS));
        data.set("Count", new NBTTagInt(7));
        var codec = RecipeProcessNbt.builder(recipe, "v1").component(DataComponents.PROCESS, DataComponents.PROCESS_CODEC).build();
        var encoded = codec.encode(process);
        var restored = codec.decode(new MachineInstance(), encoded);
        var restoredData = Objects.requireNonNull(restored.components().get(DataComponents.PROCESS));
        assertNotSame(data, restoredData);
        assertEquals(new NBTTagInt(7), restoredData.get("Count"));
        restoredData.set("Count", new NBTTagInt(11));
        assertEquals(new NBTTagInt(7), data.get("Count"));
    }

    @Test
    void addingDataPreservesDelegateCreationBudgetAndFreshness() {
        var owner = new MachineInstance();
        var recipe = new MachineRecipe(new ResourceLocation("data_test", "recipe"), 4, List.of());
        var process = new RecipeProcess(owner, recipe, 19);
        boolean[] current = {true};
        var delegate = new RecipeProcessFactory() {
            @Override
            public RecipeProcess create(MachineInstance machine, MachineRecipe definition, long seed, ProbabilityTuning tuning) {
                throw new AssertionError("The cooperative cursor must be retained");
            }

            @Override
            @SuppressWarnings("ReturnOfInnerClass") // The fixture must prove the original cooperative cursor is used.
            public Creation beginCreation(MachineInstance machine, MachineRecipe definition, long seed, ProbabilityTuning tuning) {
                assertSame(owner, machine);
                assertSame(recipe, definition);
                return new Creation() {
                    private int steps;

                    @Override
                    public boolean advance(RecipeWorkBudget budget) {
                        if (budget.take()) steps++;
                        return steps >= 2;
                    }

                    @Override
                    public boolean current() {
                        return current[0];
                    }

                    @Override
                    public RecipeProcess result() {
                        assertEquals(2, steps);
                        return process;
                    }
                };
            }
        };
        var creation = DataComponents.withProcessData(delegate).beginCreation(owner, recipe, 19, ProbabilityTuning.defaultTuning());
        assertFalse(creation.advance(new RecipeWorkBudget(1)));
        assertTrue(creation.advance(new RecipeWorkBudget(1)));
        assertSame(process, creation.result());
        var data = Objects.requireNonNull(process.components().get(DataComponents.PROCESS));
        data.set("Kept", new NBTTagInt(1));
        assertSame(process, creation.result());
        assertSame(data, process.components().get(DataComponents.PROCESS));
        current[0] = false;
        assertFalse(creation.current());
    }
}
