package com.circulation.prototype_eng.api.machine;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.research.station.ResearchStation;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;

/**
 * Research work is driven by the shared research service, not a recipe-controller tick.
 */
public class ResearchStationController extends MachineInstance implements ResearchStation {
    private final int researchLevel;
    private final Reference2DoubleMap<NetworkResourceType> rates;
    private final long energyPerTick;

    /**
     * The completed rate map is borrowed read-only for this controller's lifetime.
     */
    public ResearchStationController(MachineType type, int level, Reference2DoubleMap<NetworkResourceType> rates, long energyPerTick) {
        super(type);
        if (level < 1 || energyPerTick < 0)
            throw new IllegalArgumentException("Invalid research station level or energy cost");
        for (var entry : rates.reference2DoubleEntrySet()) {
            var resource = entry.getKey();
            double rate = entry.getDoubleValue();
            if (resource.internal() || !Double.isFinite(rate) || rate < 0)
                throw new IllegalArgumentException("Invalid station rate for " + resource.id());
        }
        researchLevel = level;
        this.rates = rates;
        this.energyPerTick = energyPerTick;
    }

    @Override
    public int researchLevel() {
        return researchLevel;
    }

    @Override
    public Reference2DoubleMap<NetworkResourceType> researchRates() {
        return rates;
    }

    @Override
    public long energyPerTick() {
        return energyPerTick;
    }

    @Override
    public boolean requiresTick() {
        return false;
    }

    @Override
    public boolean requiresNetwork() {
        return true;
    }
}
