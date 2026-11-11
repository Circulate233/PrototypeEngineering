package com.circulation.prototype_eng.api.data;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.persistence.MachineComponentCodec;
import com.circulation.prototype_eng.api.recipe.persistence.ProcessComponentCodec;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeProcessFactory;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Objects;

/** Explicit ordinary data components; declarations opt in and bind the matching codec. */
public final class DataComponents {
    public static final ComponentType<MachineInstance, ComponentData> MACHINE =
        new ComponentType<>(new ResourceLocation("prototype_eng", "data"), _ -> new ComponentData(), List.of());
    public static final ComponentType<RecipeProcess, ComponentData> PROCESS =
        new ComponentType<>(new ResourceLocation("prototype_eng", "data"), _ -> new ComponentData(), List.of());

    public static final MachineComponentCodec<ComponentData> MACHINE_CODEC = new MachineComponentCodec<>() {
        @Override
        public NBTTagCompound encode(MachineInstance owner, ComponentData component) {
            return component.snapshot().data();
        }

        @Override
        public ComponentData restore(MachineInstance owner, ComponentData component, NBTTagCompound saved) {
            component.replace(saved);
            return component;
        }
    };

    public static final ProcessComponentCodec<ComponentData> PROCESS_CODEC = new ProcessComponentCodec<>() {
        @Override
        public NBTTagCompound encode(RecipeProcess process, ComponentData component) {
            return component.snapshot().data();
        }

        @Override
        public void restore(RecipeProcess process, ComponentData component, NBTTagCompound data) {
            component.replace(data);
        }
    };

    private DataComponents() {
    }

    /** Adds data to fresh candidates without losing the delegate's cooperative creation cursor. */
    public static RecipeProcessFactory withProcessData(RecipeProcessFactory delegate) {
        Objects.requireNonNull(delegate);
        return new RecipeProcessFactory() {
            @Override
            public RecipeProcess create(MachineInstance machine, MachineRecipe recipe, long seed, ProbabilityTuning tuning) {
                return attach(delegate.create(machine, recipe, seed, tuning));
            }

            @Override
            @SuppressWarnings("ReturnOfInnerClass") // The wrapper retains the delegate's exact cooperative cursor.
            public Creation beginCreation(MachineInstance machine, MachineRecipe recipe, long seed, ProbabilityTuning tuning) {
                var original = delegate.beginCreation(machine, recipe, seed, tuning);
                return new Creation() {
                    @Override
                    public boolean advance(RecipeWorkBudget budget) {
                        return original.advance(budget);
                    }

                    @Override
                    public boolean current() {
                        return original.current();
                    }

                    @Override
                    public RecipeProcess result() {
                        return attach(original.result());
                    }
                };
            }
        };
    }

    private static RecipeProcess attach(RecipeProcess process) {
        Objects.requireNonNull(process);
        if (process.components().get(PROCESS) == null) process.components().put(PROCESS, PROCESS.create(process));
        return process;
    }
}
