package com.circulation.prototype_eng.api.network.resource;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * 每个启用的 tick 完整支付一次输入，产生的计算资源仅在该 tick 内有效。
 * 列表与 Map 必须在创建前完成定义，后续不得修改；条件回调在服务器所属线程上只读执行。
 */
public final class ComputerRecipe {
    private final MachineRecipe recipe;
    private final RecipeRequirement<ResourceRequirement> inputs;
    private final Reference2DoubleMap<NetworkResourceType> provided;
    private final Predicate<MachineInstance> condition;

    public ComputerRecipe(ResourceLocation id, List<GenericStack> inputsPerTick,
                          Reference2DoubleMap<NetworkResourceType> provided) {
        this(id, inputsPerTick, provided, _ -> true);
    }

    public ComputerRecipe(ResourceLocation id, List<GenericStack> inputsPerTick,
                          Reference2DoubleMap<NetworkResourceType> provided, Predicate<MachineInstance> condition) {
        inputs = RecipeRequirements.RESOURCES.requirement(id,
            new ResourceRequirement(List.of(), inputsPerTick, List.of(), List.of(), false));
        recipe = new MachineRecipe(id, 1, List.of(inputs));
        NetworkResourceModifier.validateResources(provided);
        this.provided = provided;
        this.condition = condition;
    }

    public ResourceLocation id() {
        return recipe.id();
    }

    /**
     * 每 tick 材料事务的固定定义。计算资源供给由 provided() 单独声明。
     */
    public MachineRecipe materialRecipe() {
        return recipe;
    }

    /**
     * 已完成的只读输入列表，调用方不得修改。
     */
    public List<GenericStack> inputsPerTick() {
        return inputs.payload().inputsPerTick();
    }

    public Reference2DoubleMap<NetworkResourceType> provided() {
        return provided;
    }

    public @Nullable RequirementTransaction prepare(MachineInstance computer, long tick) {
        if (!condition.test(computer)) return null;
        var process = new RecipeProcess(computer, recipe, tick);
        process.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
        return process.withTickContext(new RecipeProcess.Tick(0, 1, tick, Math.incrementExact(tick)),
            () -> inputs.acquire(process, RecipeStage.TICK));
    }
}
