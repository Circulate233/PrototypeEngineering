package com.circulation.prototype_eng.storage;

import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.AEKeyTypes;
import com.circulation.prototype_eng.api.energy.EnergyHatchConfiguration;
import com.circulation.prototype_eng.api.storage.HatchConfiguration;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.HatchTier;
import com.circulation.prototype_eng.api.storage.ResourceHatchConfiguration;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Complete read-only display defaults from one server session and target generation.
 * The producer supplies stable profiles; callers must not modify the list after publication.
 */
public record HatchConfigurationSnapshot(UUID session, long revision, List<HatchConfiguration> profiles) {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int PROFILE_COUNT = HatchTier.VALUES.length * HatchMode.VALUES.length * 2;

    public HatchConfigurationSnapshot {
        Objects.requireNonNull(session);
        if (revision < 0 || profiles.size() != PROFILE_COUNT)
            throw new IllegalArgumentException("Incomplete hatch configuration snapshot");
        for (int index = 0; index < profiles.size(); index++) {
            var profile = profiles.get(index);
            if (profile.mode() != HatchMode.VALUES[index / 2 % HatchMode.VALUES.length]
                || (index % 2 == 0 ? !(profile instanceof ResourceHatchConfiguration) : !(profile instanceof EnergyHatchConfiguration)))
                throw new IllegalArgumentException("Hatch configuration profile order mismatch");
        }
    }

    private static int index(HatchTier tier, HatchMode mode) {
        return (tier.ordinal() * HatchMode.VALUES.length + mode.ordinal()) * 2;
    }

    private static void writeSide(DataOutputStream out, ResourceHatchConfiguration.Side side) throws IOException {
        out.writeInt(side.slots());
        out.writeInt(side.capacities().size());
        var entries = new ObjectArrayList<>(side.capacities().reference2LongEntrySet());
        entries.sort(Comparator.comparing(entry -> entry.getKey().getId()));
        for (int index2 = 0; index2 < entries.size(); index2++) {
            var entry = entries.get(index2);
            out.writeUTF(entry.getKey().getId().toString());
            out.writeLong(entry.getLongValue());
            if (out.size() > MAX_BYTES) throw new IllegalArgumentException("Hatch configuration snapshot is too large");
        }
        out.writeBoolean(side.basis() != null);
        if (side.basis() != null) {
            out.writeLong(side.basis().itemEquivalent());
            for (int index1 = 0; index1 < entries.size(); index1++) {
                var entry = entries.get(index1);
                out.writeLong(side.basis().ratios().getLong(entry.getKey()));
            }
        }
    }

    public static HatchConfigurationSnapshot decode(byte[] bytes) {
        if (bytes.length < 28 || bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("Invalid hatch snapshot size");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 1) throw new IllegalArgumentException("Unknown hatch snapshot format");
            var session = new UUID(in.readLong(), in.readLong());
            long revision = in.readLong();
            var profiles = new ObjectArrayList<HatchConfiguration>(HatchTier.VALUES.length * HatchMode.VALUES.length * 2);
            for (var ignored : HatchTier.VALUES) {
                for (var mode : HatchMode.VALUES) {
                    profiles.add(new ResourceHatchConfiguration(mode.hasInput() ? readSide(in) : null, mode.hasOutput() ? readSide(in) : null));
                    profiles.add(new EnergyHatchConfiguration(mode.hasInput() ? new EnergyHatchConfiguration.Side(in.readLong(), in.readLong()) : null,
                        mode.hasOutput() ? new EnergyHatchConfiguration.Side(in.readLong(), in.readLong()) : null));
                }
            }
            if (in.available() != 0) throw new IllegalArgumentException("Trailing hatch snapshot data");
            return new HatchConfigurationSnapshot(session, revision, profiles);
        } catch (IOException | ArithmeticException failure) {
            throw new IllegalArgumentException("Invalid hatch snapshot data", failure);
        }
    }

    private static ResourceHatchConfiguration.Side readSide(DataInputStream in) throws IOException {
        int slots = in.readInt();
        int count = in.readInt();
        if (count <= 0 || count > in.available() / 10)
            throw new IllegalArgumentException("Invalid hatch snapshot type count");
        var capacities = new Reference2LongArrayMap<AEKeyType>(count);
        for (int index = 0; index < count; index++) {
            var type = AEKeyTypes.get(new ResourceLocation(in.readUTF()));
            if (type == null) throw new IllegalArgumentException("Unknown hatch snapshot type");
            if (capacities.containsKey(type)) throw new IllegalArgumentException("Repeated hatch snapshot type");
            capacities.put(type, in.readLong());
        }
        ResourceHatchConfiguration.CapacityBasis basis = null;
        if (in.readBoolean()) {
            long itemEquivalent = in.readLong();
            var ratios = new Reference2LongArrayMap<AEKeyType>(count);
            for (var type : capacities.keySet()) ratios.put(type, in.readLong());
            basis = new ResourceHatchConfiguration.CapacityBasis(itemEquivalent, ratios);
        }
        return new ResourceHatchConfiguration.Side(slots, capacities, basis);
    }

    public ResourceHatchConfiguration resource(HatchTier tier, HatchMode mode) {
        return (ResourceHatchConfiguration) profiles.get(index(tier, mode));
    }

    public EnergyHatchConfiguration energy(HatchTier tier, HatchMode mode) {
        return (EnergyHatchConfiguration) profiles.get(index(tier, mode) + 1);
    }

    public byte[] encode() {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            out.writeLong(session.getMostSignificantBits());
            out.writeLong(session.getLeastSignificantBits());
            out.writeLong(revision);
            for (int index3 = 0; index3 < profiles.size(); index3++) {
                var profile = profiles.get(index3);
                if (profile instanceof ResourceHatchConfiguration(var input, var output)) {
                    if (input != null) writeSide(out, input);
                    if (output != null) writeSide(out, output);
                } else if (profile instanceof EnergyHatchConfiguration(var input, var output)) {
                    if (input != null) {
                        out.writeLong(input.capacity());
                        out.writeLong(input.transfer());
                    }
                    if (output != null) {
                        out.writeLong(output.capacity());
                        out.writeLong(output.transfer());
                    }
                }
                if (bytes.size() > MAX_BYTES)
                    throw new IllegalArgumentException("Hatch configuration snapshot is too large");
            }
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot encode hatch display configuration", failure);
        }
    }
}
