package pe.visual.examples.run;

import com.circulation.prototype_eng.api.component.ComponentSystem;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.data.ComponentData;
import com.circulation.prototype_eng.api.data.DataComponents;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.recipe.persistence.ProcessComponentCodec;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeProcessFactory;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import net.minecraft.nbt.NBTPrimitive;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Objects;

/** Ordinary callbacks on real owners. No world reads occur on recipe or machine workers. */
public final class NativeRunCases {
    public enum Marker { INSTANCE }
    public static final ComponentType<MachineInstance, Marker> MACHINE_TRACE = new ComponentType<>(
        id("native_machine_trace"), owner -> {
            owner.addRecipeWorkGate(() -> number(Objects.requireNonNull(owner.components().get(DataComponents.MACHINE)), "run_paused") == 0);
            return Marker.INSTANCE;
        }, List.of(new ComponentSystem<>() {
            @Override public ResourceLocation id() { return NativeRunCases.id("machine_post"); }
            @Override public void onPostTick(MachineInstance owner, Marker component) {
                var data = Objects.requireNonNull(owner.components().get(DataComponents.MACHINE));
                data.set("machine_ticks", new NBTTagLong(Math.incrementExact(number(data, "machine_ticks"))));
                data.set("worker", new NBTTagString(Thread.currentThread().getName()));
                var pending = data.get("last_tick_pending");
                if (pending != null) {
                    data.set("last_tick_nonce", pending);
                    data.remove("last_tick_pending");
                }
            }
        }));
    public static final ComponentType<RecipeProcess, Marker> PROCESS_TRACE = new ComponentType<>(
        id("native_process_trace"), _ -> Marker.INSTANCE, List.of(new ComponentSystem<>() {
            @Override public ResourceLocation id() { return NativeRunCases.id("process_post"); }
            @Override public void onPostTick(RecipeProcess process, Marker component) {
                var data = Objects.requireNonNull(process.components().get(DataComponents.PROCESS));
                data.set("observed_ticks", new NBTTagLong(process.state().successfulTicks()));
                data.set("seed", new NBTTagLong(process.seed()));
                data.set("worker", new NBTTagString(Thread.currentThread().getName()));
                if (process.state().lifecycle() == RecipeProcess.Lifecycle.COMPLETED) {
                    var ownerData = Objects.requireNonNull(process.owner().components().get(DataComponents.MACHINE));
                    String countKey = "completed:" + process.recipe().id();
                    ownerData.set(countKey, new NBTTagLong(Math.incrementExact(number(ownerData, countKey))));
                    ownerData.set("last_completed_ticks", new NBTTagLong(process.state().successfulTicks()));
                    ownerData.set("last_completed_seed", new NBTTagLong(process.seed()));
                    ownerData.set("last_completed_recipe", new NBTTagString(process.recipe().id().toString()));
                }
            }
        }));
    public static final ProcessComponentCodec<Marker> PROCESS_TRACE_CODEC = new ProcessComponentCodec<>() {
        @Override public NBTTagCompound encode(RecipeProcess process, Marker component) { return new NBTTagCompound(); }
        @Override public void restore(RecipeProcess process, Marker component, NBTTagCompound saved) {
            if (!saved.isEmpty()) throw new IllegalArgumentException("Unexpected trace marker state");
        }
    };
    private NativeRunCases() { }
    public static ResourceLocation id(String path) { return new ResourceLocation("pe_run_tests", path); }
    public static long number(ComponentData data, String key) {
        var value = data.get(key);
        return value instanceof NBTPrimitive primitive ? primitive.getLong() : 0;
    }
    public static RecipeScanner decorate(RecipeScanner scanner) {
        return scanner.decorateProcessFactory(delegate -> {
            var withData = DataComponents.withProcessData(delegate);
            return new RecipeProcessFactory() {
                @Override public RecipeProcess create(MachineInstance machine,
                        com.circulation.prototype_eng.api.recipe.MachineRecipe recipe, long seed,
                        com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning tuning) {
                    return attach(withData.create(machine, recipe, seed, tuning));
                }
                @Override public Creation beginCreation(MachineInstance machine,
                        com.circulation.prototype_eng.api.recipe.MachineRecipe recipe, long seed,
                        com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning tuning) {
                    var creation = withData.beginCreation(machine, recipe, seed, tuning);
                    return new Creation() {
                        @Override public boolean advance(com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget budget) {
                            return creation.advance(budget);
                        }
                        @Override public boolean current() { return creation.current(); }
                        @Override public RecipeProcess result() { return attach(creation.result()); }
                    };
                }
            };
        }, true);
    }
    private static RecipeProcess attach(RecipeProcess process) {
        if (process.components().get(PROCESS_TRACE) == null)
            process.components().put(PROCESS_TRACE, Marker.INSTANCE);
        return process;
    }
}
