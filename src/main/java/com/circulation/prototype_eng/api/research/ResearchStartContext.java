package com.circulation.prototype_eng.api.research;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/** Immutable eligibility input, not a reservation or permission to mutate resources. */
@SuppressWarnings("unused")
public record ResearchStartContext(ResourceLocation researchId, int targetLevel, @Nullable UUID playerId,
                                   @Nullable String teamId, Object2IntMap<ResourceLocation> teamSharedResearchLevels,
                                   Object2LongMap<NetworkResourceType> availableResources, int networkCenterLevel) {
    public ResearchStartContext {
        Objects.requireNonNull(researchId);
        if (targetLevel < 1 || networkCenterLevel < 0 || teamId != null && (teamId.isBlank() || !teamId.equals(teamId.trim()))) {
            throw new IllegalArgumentException("Invalid research start identity, target or center level");
        }
        var levels = new Object2IntOpenHashMap<ResourceLocation>();
        for (var entry : Objects.requireNonNull(teamSharedResearchLevels).object2IntEntrySet()) {
            if (entry.getIntValue() < 0) { throw new IllegalArgumentException("Negative shared research level"); }
            levels.put(Objects.requireNonNull(entry.getKey()), entry.getIntValue());
        }
        teamSharedResearchLevels = ResearchSnapshots.levels(levels);
        var amounts = new Object2LongOpenHashMap<NetworkResourceType>();
        var ids = new ObjectOpenHashSet<ResourceLocation>();
        for (var entry : Objects.requireNonNull(availableResources).object2LongEntrySet()) {
            var type = Objects.requireNonNull(entry.getKey());
            if (entry.getLongValue() < 0 || !ids.add(type.id())) {
                throw new IllegalArgumentException("Negative or duplicate research resource snapshot " + type.id());
            }
            amounts.put(type, entry.getLongValue());
        }
        availableResources = NetworkResourceModifier.copyResources(amounts);
    }

    public boolean teamHasNetworkCenter() { return networkCenterLevel > 0; }
}
