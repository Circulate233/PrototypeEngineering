package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.component.ComponentSystem;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.ComputerController;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleListener;
import com.circulation.prototype_eng.api.machine.persistence.MachineComponentCodec;
import com.circulation.prototype_eng.api.network.resource.ComputerInputRecipe;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Objects;

/**
 * 保存普通配方进度；资源事件结果仅作用于本次计算机工作周期，在下一次网络发布使用。
 */
public final class ComputerInputWork implements MachineLifecycleListener {
    public static final ComponentType<MachineInstance, ComputerInputWork> TYPE = new ComponentType<>(
        new ResourceLocation("prototype_eng", "computer_input_recipes"), ComputerInputWork::new,
        List.of(new ComponentSystem<>() {
            @Override
            public ResourceLocation id() {
                return new ResourceLocation("prototype_eng", "computer_input_work");
            }

            @Override
            public void onTick(MachineInstance owner, ComputerInputWork component) {
                component.tick();
            }
        }));
    public static final MachineComponentCodec<ComputerInputWork> CODEC = new MachineComponentCodec<>() {
        @Override
        public NBTTagCompound encode(MachineInstance owner, ComputerInputWork work) {
            var tag = new NBTTagCompound();
            var saved = new NBTTagList();
            for (int index = 0; index < work.processes.length; index++) {
                var process = work.processes[index];
                if (process == null) continue;
                var entry = new NBTTagCompound();
                entry.setInteger("Index", index);
                entry.setTag("Process", work.recipes.get(index).codec().encode(process));
                saved.appendTag(entry);
            }
            tag.setTag("Processes", saved);
            return tag;
        }

        @Override
        public ComputerInputWork restore(MachineInstance owner, ComputerInputWork work, NBTTagCompound tag) {
            var list = tag.getTagList("Processes", 10);
            for (int index = 0; index < list.tagCount(); index++) {
                var entry = list.getCompoundTagAt(index);
                int slot = entry.getInteger("Index");
                work.processes[slot] = work.recipes.get(slot).codec().decode(owner, entry.getCompoundTag("Process"));
            }
            return work;
        }
    };
    private final MachineInstance owner;
    private final List<ComputerInputRecipe> recipes;
    private final RecipeProcess[] processes;
    private NetworkResourceState effects;
    private long lastTick = Long.MIN_VALUE;

    private ComputerInputWork(MachineInstance owner) {
        this.owner = owner;
        recipes = Objects.requireNonNull(owner.type()).computerInputRecipes();
        processes = new RecipeProcess[recipes.size()];
    }

    public NetworkResourceState effects() {
        return Objects.requireNonNull(effects);
    }

    public boolean hasEffects(long tick) {
        return effects != null && tick == lastTick + 1;
    }

    public void invalidateEffects() {
        effects = null;
    }

    public void effects(NetworkResourceState value) {
        effects = value;
    }

    public RecipeProcess process(int index) {
        return processes[index];
    }

    private void tick() {
        if (!owner.canProcessRecipes()) return;
        var data = Objects.requireNonNull(owner.networkDevice()).networkData();
        if (lastTick == data.tick()) return;
        lastTick = data.tick();
        var installed = ((ComputerController) owner).installedInventory();
        effects = new NetworkResourceState(installed.resourcesPerTick(), installed.requiredResources(), installed.concurrencyPerTick());
        for (int index = 0; index < recipes.size(); index++) {
            var process = processes[index];
            if (process == null || process.state().lifecycle().isTerminal())
                processes[index] = process = new RecipeProcess(owner, recipes.get(index).recipe(), data.tick());
            process.tick();
        }
    }

    @Override
    public void onFormedChanged(MachineLifecycleEvent.FormedChanged event) {
        invalidateEffects();
    }
}
