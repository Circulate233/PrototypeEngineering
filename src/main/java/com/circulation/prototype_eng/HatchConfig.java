package com.circulation.prototype_eng;

import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.AEKeyTypes;
import com.circulation.prototype_eng.api.energy.EnergyHatchConfiguration;
import com.circulation.prototype_eng.api.storage.HatchConfiguration;
import com.circulation.prototype_eng.api.storage.HatchConfigurationUpdates;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.api.storage.ResourceHatchConfiguration;
import com.circulation.prototype_eng.storage.HatchConfigurationSnapshot;
import com.circulation.prototype_eng.storage.ServerHatchConfigurations;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import net.minecraftforge.common.config.Configuration;

import java.io.File;
import java.util.List;
import java.util.UUID;

/**
 * File defaults and explicit script targets are published as a single immutable generation.
 */
public final class HatchConfig {
    private static volatile State state = new State(0, defaults(), Int2ObjectMaps.emptyMap());

    private HatchConfig() {
    }

    private static List<Defaults> defaults() {
        var result = new ObjectArrayList<Defaults>(HatchTier.VALUES.length);
        for (var tier : HatchTier.VALUES)
            result.add(new Defaults(
                tier.slotsPerSide(HatchMode.INPUT), tier.slotsPerSide(HatchMode.MIXED), tier.itemsPerSlot(),
                tier.energyCapacity(), tier.energyTransfer()));
        return result;
    }

    public static synchronized void load(File directory) {
        ServerHatchConfigurations.requireConfigurationOwner();
        var config = new Configuration(new File(directory, Tags.MOD_ID + "/hatches.cfg"));
        config.load();
        var before = state;
        var next = new ObjectArrayList<Defaults>(HatchTier.VALUES.length);
        for (var tier : HatchTier.VALUES) {
            var old = before.defaults.get(tier.ordinal());
            String category = "tier" + tier.level();
            next.add(new Defaults(positiveInt(config, category, "slots", old.slots),
                positiveInt(config, category, "mixedSlotsPerSide", old.mixedSlots),
                itemEquivalent(config, category, old.items),
                positiveLong(config, category, "energyCapacity", old.energy), positiveLong(config, category, "energyTransfer", old.transfer)));
        }
        if (config.hasChanged()) config.save();
        // Script targets set before the Forge file load keep their explicit precedence.
        state = new State(Math.incrementExact(before.revision), next, before.overrides);
        ServerHatchConfigurations.changed();
    }

    private static long itemEquivalent(Configuration config, String category, long fallback) {
        long value = Long.parseLong(config.getString("itemsPerSlot", category, Long.toString(fallback),
            "Per-slot item-equivalent capacity. Each type uses value * AEKeyType.getAmountPerUnit(); fluids use mB (1000 per bucket). Legacy fluidPerSlot is ignored for new targets; saved effective capacities remain unchanged."));
        if (value <= 0) throw new IllegalArgumentException(category + ".itemsPerSlot must be positive");
        return value;
    }

    private static int positiveInt(Configuration config, String category, String name, int fallback) {
        int value = Integer.parseInt(config.getString(name, category, Integer.toString(fallback), "Positive logical slot count."));
        if (value <= 0) throw new IllegalArgumentException(category + "." + name + " must be positive");
        return value;
    }

    private static long positiveLong(Configuration config, String category, String name, long fallback) {
        long value = Long.parseLong(config.getString(name, category, Long.toString(fallback), "Positive long value."));
        if (value <= 0) throw new IllegalArgumentException(category + "." + name + " must be positive");
        return value;
    }

    public static synchronized long installTargets(List<HatchConfigurationUpdates.Target> targets) {
        ServerHatchConfigurations.requireConfigurationOwner();
        var before = state;
        var next = new Int2ObjectOpenHashMap<>(before.overrides);
        for (int index = 0; index < targets.size(); index++) {
            var target = targets.get(index);
            next.put(key(target.tier(), target.configuration().mode(), target.configuration() instanceof EnergyHatchConfiguration), target.configuration());
        }
        long revision = Math.incrementExact(before.revision);
        state = new State(revision, before.defaults, next);
        ServerHatchConfigurations.changed();
        return revision;
    }

    public static boolean hasTarget(HatchTier tier, HatchConfiguration configuration) {
        return state.overrides.containsKey(key(tier, configuration.mode(), configuration instanceof EnergyHatchConfiguration));
    }

    public static long targetRevision() {
        return state.revision;
    }

    public static synchronized HatchConfigurationSnapshot snapshot(UUID session) {
        var profiles = new ObjectArrayList<HatchConfiguration>(HatchTier.VALUES.length * HatchMode.VALUES.length * 2);
        for (var tier : HatchTier.VALUES)
            for (var mode : HatchMode.VALUES) {
                profiles.add(resourceConfiguration(tier, mode));
                profiles.add(energyConfiguration(tier, mode));
            }
        return new HatchConfigurationSnapshot(session, state.revision, profiles);
    }

    public static ResourceHatchConfiguration resourceConfiguration(HatchTier tier, HatchMode mode) {
        var current = state;
        var override = current.overrides.get(key(tier, mode, false));
        if (override instanceof ResourceHatchConfiguration resource) return resource;
        var values = current.defaults.get(tier.ordinal());
        var side = ResourceHatchConfiguration.Side.fromItemEquivalent(mode == HatchMode.MIXED ? values.mixedSlots : values.slots, values.items, AEKeyTypes.getAll());
        return new ResourceHatchConfiguration(mode.hasInput() ? side : null, mode.hasOutput() ? side : null);
    }

    public static EnergyHatchConfiguration energyConfiguration(HatchTier tier, HatchMode mode) {
        var current = state;
        var override = current.overrides.get(key(tier, mode, true));
        if (override instanceof EnergyHatchConfiguration energy) return energy;
        var values = current.defaults.get(tier.ordinal());
        return EnergyHatchConfiguration.symmetric(mode, values.energy, values.transfer);
    }

    @SuppressWarnings("DataFlowIssue")
    public static int slotsPerSide(HatchTier tier, HatchMode mode) {
        var current = state;
        var override = current.overrides.get(key(tier, mode, false));
        if (override instanceof ResourceHatchConfiguration(var input, var output))
            return (input == null ? output : input).slots();
        var values = current.defaults.get(tier.ordinal());
        return mode == HatchMode.MIXED ? values.mixedSlots : values.slots;
    }

    public static long energyCapacity(HatchTier tier) {
        return state.defaults.get(tier.ordinal()).energy;
    }

    public static long energyTransfer(HatchTier tier) {
        return state.defaults.get(tier.ordinal()).transfer;
    }

    @SuppressWarnings("unused") // Existing addon capacity query.
    public static Reference2LongMap<AEKeyType> resourceCapacities(HatchTier tier) {
        return capacities(state.defaults.get(tier.ordinal()));
    }

    private static Reference2LongMap<AEKeyType> capacities(Defaults values) {
        var side = ResourceHatchConfiguration.Side.fromItemEquivalent(1, values.items, AEKeyTypes.getAll());
        return side.capacities();
    }

    private record Defaults(int slots, int mixedSlots, long items, long energy, long transfer) {
        @SuppressWarnings("ResultOfMethodCallIgnored")
        private Defaults {
            Math.addExact(mixedSlots, mixedSlots);
        }
    }

    private static int key(HatchTier tier, HatchMode mode, boolean energy) {
        return (tier.ordinal() * HatchMode.VALUES.length + mode.ordinal()) * 2 + (energy ? 1 : 0);
    }

    private record State(long revision, List<Defaults> defaults, Int2ObjectMap<HatchConfiguration> overrides) {
    }
}
