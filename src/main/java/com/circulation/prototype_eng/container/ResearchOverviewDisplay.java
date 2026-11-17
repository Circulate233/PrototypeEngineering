package com.circulation.prototype_eng.container;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.reward.RewardChild;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Keeps one container's changed-section baseline; no mutable source is shared with the client.
 */
public final class ResearchOverviewDisplay {
    private final List<ResearchOverviewPacket.Definition> definitions;
    private final Object2ObjectOpenHashMap<ResourceLocation, ResearchOverviewPacket.State> states = new Object2ObjectOpenHashMap<>();
    private List<ResearchOverviewPacket.Queue> queue = List.of();
    private List<ResearchOverviewPacket.Database> databases = List.of();
    private @Nullable ResearchOverviewPacket.Current current;
    private ResearchOverviewPacket.Environment environment = ResearchOverviewPacket.Environment.EMPTY;
    private boolean initialized;

    public ResearchOverviewDisplay(ResearchRegistry registry) {
        var definitions = new ObjectArrayList<ResearchOverviewPacket.Definition>();
        var research = registry.sortedById();
        for (int index = 0; index < research.size(); index++) {
            var definition = research.get(index);
            var stages = new ObjectArrayList<ResearchOverviewPacket.Stage>();
            for (int stageIndex = 0; stageIndex < definition.stages().size(); stageIndex++) {
                var stage = definition.stages().get(stageIndex);
                var prerequisites = new ObjectArrayList<ResearchOverviewPacket.Required>();
                for (var entry : stage.prerequisites().object2IntEntrySet())
                    prerequisites.add(new ResearchOverviewPacket.Required(entry.getKey(), entry.getIntValue()));
                var mutex = new ObjectArrayList<ResearchOverviewPacket.Required>();
                for (int mutexIndex = 0; mutexIndex < stage.mutexRules().size(); mutexIndex++) {
                    var rule = stage.mutexRules().get(mutexIndex);
                    mutex.add(new ResearchOverviewPacket.Required(rule.researchId(), rule.minLevel()));
                }
                var materials = new ObjectArrayList<ResearchOverviewPacket.Material>();
                for (var entry : stage.materialTotals().object2LongEntrySet())
                    materials.add(new ResearchOverviewPacket.Material(entry.getKey(), entry.getLongValue()));
                var resources = new ObjectArrayList<ResearchOverviewPacket.Resource>();
                var totals = stage.resourceDemandForLevel(stage.firstLevel());
                var last = stage.resourceDemandForLevel(stage.lastLevel());
                for (var entry : totals.reference2DoubleEntrySet()) {
                    double slope = stage.firstLevel() == stage.lastLevel() ? 0
                        : (last.getDouble(entry.getKey()) - entry.getDoubleValue()) / ((long) stage.lastLevel() - stage.firstLevel());
                    resources.add(new ResearchOverviewPacket.Resource(entry.getKey(), entry.getDoubleValue(), slope));
                }
                var rewards = new ObjectArrayList<ResearchOverviewPacket.Rewards>();
                for (var entry : stage.rewardGroupsByLevel().int2ObjectEntrySet()) {
                    rewards.add(new ResearchOverviewPacket.Rewards(entry.getIntKey(), rewardGroups(entry.getValue())));
                }
                stages.add(new ResearchOverviewPacket.Stage(stage.firstLevel(), stage.lastLevel(), stage.researchTier(), stage.durationTicks(), prerequisites, mutex, materials, resources, rewards));
            }
            definitions.add(new ResearchOverviewPacket.Definition(definition.id(), definition.maxLevel(), definition.specialOnly(), definition.deliveryPolicy(), stages));
        }
        this.definitions = definitions;
    }

    private static List<ResearchOverviewPacket.RewardGroup> rewardGroups(List<RewardGroup> source) {
        var groups = new ObjectArrayList<ResearchOverviewPacket.RewardGroup>();
        for (int groupIndex = 0; groupIndex < source.size(); groupIndex++) {
            var group = source.get(groupIndex);
            var items = new ObjectArrayList<GenericStack>();
            for (int childIndex = 0; childIndex < group.children().size(); childIndex++)
                if (group.children().get(childIndex) instanceof RewardChild.Item item)
                    items.add(new GenericStack(item.resource(), item.amount()));
            groups.add(new ResearchOverviewPacket.RewardGroup(group.id(), items));
        }
        return groups;
    }

    public @Nullable ResearchOverviewPacket capture(List<ResearchOverviewPacket.State> nextStates,
                                                    List<ResearchOverviewPacket.Queue> nextQueue,
                                                    List<ResearchOverviewPacket.Database> nextDatabases,
                                                    @Nullable ResearchOverviewPacket.Current nextCurrent,
                                                    ResearchOverviewPacket.Environment nextEnvironment) {
        boolean reset = !initialized || !environment.teamId().equals(nextEnvironment.teamId());
        var changed = new ObjectArrayList<ResearchOverviewPacket.State>();
        if (reset) states.clear();
        for (int index = 0; index < nextStates.size(); index++) {
            var state = nextStates.get(index);
            if (nextCurrent != null && nextCurrent.id().equals(state.id()) && state.progress() != null)
                state = new ResearchOverviewPacket.State(state.id(), state.personalLevel(), state.teamLevel(), state.startableTarget(),
                    state.downloadedLevel(), state.downloadedTo(), state.canStart(), state.canDownload(), state.canForget(), state.selectable(),
                    state.processableLevel(), new ResearchOverviewPacket.Progress(state.progress().targetLevel(), 0, List.of(), List.of()),
                    state.claims(), state.otherMemberLevel(), state.availability());
            if (!state.equals(states.get(state.id()))) {
                states.put(state.id(), state);
                changed.add(state);
            }
        }
        boolean queueChanged = reset || !queue.equals(nextQueue), databasesChanged = reset || !databases.equals(nextDatabases);
        boolean currentChanged = reset || !Objects.equals(current, nextCurrent), environmentChanged = reset || !environment.equals(nextEnvironment);
        var beforeCurrent = current;
        queue = nextQueue;
        databases = nextDatabases;
        current = nextCurrent;
        environment = nextEnvironment;
        initialized = true;
        if (!reset && changed.isEmpty() && !queueChanged && !databasesChanged && !currentChanged && !environmentChanged)
            return null;
        return new ResearchOverviewPacket(reset, reset ? definitions : null, changed, queueChanged ? queue : null,
            databasesChanged ? databases : null, currentChanged, current, environmentChanged ? environment : null, beforeCurrent);
    }

    public ResearchOverviewPacket fullPacket() {
        return new ResearchOverviewPacket(true, definitions, new ObjectArrayList<>(states.values()), queue, databases, true, current, environment);
    }
}
