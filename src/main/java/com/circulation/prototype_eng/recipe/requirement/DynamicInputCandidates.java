package com.circulation.prototype_eng.recipe.requirement;

import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Stable dynamic alternatives from all available source keys, without storage or world access.
 */
public final class DynamicInputCandidates {
    private static final byte[] NO_DATA = new byte[0];
    private static final Comparator<ResourceLocation> RESOURCE_ID_ORDER = Comparator.nullsFirst(Comparator.naturalOrder());

    private DynamicInputCandidates() {
    }

    /**
     * Callers merge nonzero available snapshot keys across all distinct storage contexts first. Every
     * matching unique key participates before truncation. The default order is type ID, resource ID,
     * then canonical full key NBT; no hash or JVM object identity participates in ordering.
     *
     * <p>Canonical bytes are computed once per matching key. Cost and memory scale with all matches
     * and their NBT, then sorting costs O(matches*log(matches)); maxCandidates bounds only the result.
     */
    public static List<AEKey> enumerate(Collection<AEKey> availableKeys, DynamicInputGroup group) {
        var configuredOrder = group.candidateOrder();
        if (configuredOrder != null) {
            return enumerate(availableKeys, group, configuredOrder);
        }
        var matches = matching(availableKeys, group);
        var ordered = new ObjectArrayList<StableKey>(matches.size());
        for (int index1 = 0; index1 < matches.size(); index1++) {
            var key = matches.get(index1);
            var resourceId = key.getId();
            ordered.add(new StableKey(key, key.getType().getId(),
                resourceId, canonical(key.toTag())));
        }
        ordered.sort(DynamicInputCandidates::compare);
        for (int index = 1; index < ordered.size(); index++) {
            if (compare(ordered.get(index - 1), ordered.get(index)) == 0) {
                throw new IllegalArgumentException("Unequal resource keys share a serialized identity; provide a stable explicit order");
            }
        }
        var result = new ObjectArrayList<AEKey>(Math.min(group.maxCandidates(), ordered.size()));
        for (int index = 0; index < ordered.size() && index < group.maxCandidates(); index++) {
            result.add(ordered.get(index).key());
        }
        return result;
    }

    /**
     * Extension order for types whose serialization cannot distinguish all keys. The comparator must
     * be pure, total on unequal keys and stable across restoration, without identity/hash tie-breaks.
     * Equal-order unequal keys are rejected across the full result before applying maxCandidates.
     */
    public static List<AEKey> enumerate(Collection<AEKey> availableKeys, DynamicInputGroup group, Comparator<AEKey> stableOrder) {
        var matches = matching(availableKeys, group);
        matches.sort(stableOrder);
        for (int index = 1; index < matches.size(); index++) {
            if (stableOrder.compare(matches.get(index - 1), matches.get(index)) == 0) {
                throw new IllegalArgumentException("Explicit resource order must distinguish unequal keys");
            }
        }
        var result = new ObjectArrayList<AEKey>(Math.min(group.maxCandidates(), matches.size()));
        for (int index = 0; index < matches.size() && index < group.maxCandidates(); index++) {
            result.add(matches.get(index));
        }
        return result;
    }

    private static ObjectArrayList<AEKey> matching(Collection<AEKey> availableKeys, DynamicInputGroup group) {
        var matches = new ObjectArrayList<AEKey>();
        var seen = new ObjectOpenHashSet<AEKey>();
        for (var key : availableKeys) {
            if (seen.add(key) && group.matcher().matches(key, group.pattern())) {
                matches.add(key);
            }
        }
        return matches;
    }

    private static int compare(StableKey first, StableKey second) {
        int order = first.typeId().compareTo(second.typeId());
        if (order == 0) {
            order = RESOURCE_ID_ORDER.compare(first.resourceId(), second.resourceId());
        }
        return order != 0 ? order : Arrays.compareUnsigned(first.tag(), second.tag());
    }

    private static byte[] canonical(NBTTagCompound tag) {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            writeCanonical(tag, output);
        } catch (IOException failure) {
            // The destination is memory; encoding failures indicate an invalid/unserializable key.
            throw new UncheckedIOException("Cannot encode stable resource identity", failure);
        }
        return bytes.toByteArray();
    }

    private static void writeCanonical(NBTBase tag, DataOutputStream output) throws IOException {
        output.writeByte(tag.getId());
        switch (tag) {
            case NBTTagCompound compound -> {
                var names = new ObjectArrayList<>(compound.getKeySet());
                names.sort(Comparator.naturalOrder());
                output.writeInt(names.size());
                for (int index2 = 0; index2 < names.size(); index2++) {
                    var name = names.get(index2);
                    writeString(name, output);
                    writeCanonical(compound.getTag(name), output);
                }
            }
            case NBTTagList list -> {
                output.writeByte(list.getTagType());
                output.writeInt(list.tagCount());
                for (int index = 0; index < list.tagCount(); index++) {
                    writeCanonical(list.get(index), output);
                }
            }
            case NBTTagString string -> writeString(string.getString(), output);
            default -> {
                // Public binary serialization includes every numeric/array value, including long arrays
                // that have no public getter in 1.12. No reflection or potentially abbreviated toString.
                var leaf = new NBTTagCompound();
                leaf.setTag("value", tag);
                CompressedStreamTools.write(leaf, output);
            }
        }
    }

    private static void writeString(String value, DataOutputStream output) throws IOException {
        output.writeInt(value.length());
        for (int index = 0; index < value.length(); index++) {
            output.writeChar(value.charAt(index));
        }
    }

    /**
     * Full matching, canonical encoding and stable ordering retain private cursors across slices.
     */
    public static class Cursor {
        private final Iterator<AEKey> available;
        private final DynamicInputGroup group;
        private final boolean retainAll;
        private final ObjectOpenHashSet<AEKey> seen = new ObjectOpenHashSet<>();
        private final ObjectArrayList<StableKey> matches = new ObjectArrayList<>();
        private final ObjectArrayList<AEKey> result = new ObjectArrayList<>();
        @Nullable
        private AEKey encodingKey;
        @Nullable
        private CanonicalKeyEncoding encoding;
        @Nullable
        private PureMergeSort<StableKey> sorted;
        @Nullable
        private PureMergeSort.Comparison comparison;
        private int checked = 1;
        private boolean complete;

        Cursor(Collection<AEKey> independentKeys, DynamicInputGroup group, boolean retainAll) {
            this.retainAll = retainAll;
            available = independentKeys.iterator();
            this.group = group;
        }

        @SuppressWarnings("DataFlowIssue")
        public boolean advance(RecipeWorkBudget budget) {
            if (complete) {
                return true;
            }
            while (sorted == null && (encodingKey != null || available.hasNext())) {
                if (encodingKey == null) {
                    if (!budget.take()) {
                        return false;
                    }
                    var key = available.next();
                    if (!seen.add(key) || !group.matcher().matches(key, group.pattern())) {
                        continue;
                    }
                    encodingKey = key;
                    if (group.candidateOrder() == null) {
                        encoding = new CanonicalKeyEncoding(key.toTag());
                    }
                }
                if (encoding != null && !encoding.advance(budget)) {
                    return false;
                }
                var key = encodingKey;
                var resourceId = key.getId();
                if (!budget.take()) {
                    return false;
                }
                matches.add(new StableKey(key, key.getType().getId(),
                    resourceId, encoding == null ? NO_DATA : encoding.result()));
                encoding = null;
                encodingKey = null;
            }
            if (sorted == null) {
                sorted = new PureMergeSort<>(matches, this::compareKeys);
            }
            if (!sorted.advance(budget)) {
                return false;
            }
            var ordered = sorted.result();
            while (checked < ordered.size()) {
                if (comparison == null) {
                    comparison = compareKeys(ordered.get(checked - 1), ordered.get(checked));
                }
                if (!comparison.advance(budget)) {
                    return false;
                }
                if (comparison.result() == 0) {
                    throw new IllegalArgumentException("Stable order must distinguish unequal resource keys");
                }
                comparison = null;
                checked++;
            }
            int limit = retainAll ? ordered.size() : Math.min(ordered.size(), group.maxCandidates());
            while (result.size() < limit) {
                if (!budget.take()) {
                    return false;
                }
                result.add(ordered.get(result.size()).key());
            }
            complete = true;
            return true;
        }

        private PureMergeSort.Comparison compareKeys(StableKey first, StableKey second) {
            var order = group.candidateOrder();
            if (order == null) {
                return new KeyComparison(first, second);
            }
            return new PureMergeSort.Comparison() {
                private boolean complete;
                private int result;

                @Override
                public boolean advance(RecipeWorkBudget budget) {
                    if (!complete) {
                        if (!budget.take()) {
                            return false;
                        }
                        result = order.compare(first.key(), second.key());
                        complete = true;
                    }
                    return true;
                }

                @Override
                public int result() {
                    return result;
                }
            };
        }

        public List<AEKey> result() {
            if (!complete) {
                throw new IllegalStateException("Dynamic candidate enumeration is incomplete");
            }
            return result;
        }
    }

    private static class KeyComparison implements PureMergeSort.Comparison {
        private final StableKey first;
        private final StableKey second;
        private int textIndex;
        private int phase;
        private int cursor;
        private int result;

        private KeyComparison(StableKey first, StableKey second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            while (phase < 4) {
                var firstId = phase < 2 ? first.typeId() : first.resourceId();
                var secondId = phase < 2 ? second.typeId() : second.resourceId();
                if (firstId == null || secondId == null) {
                    result = firstId == null && secondId == null ? 0 : firstId == null ? -1 : 1;
                } else {
                    String left = (phase & 1) == 0 ? firstId.getNamespace() : firstId.getPath();
                    String right = (phase & 1) == 0 ? secondId.getNamespace() : secondId.getPath();
                    while (textIndex < left.length() && textIndex < right.length()) {
                        if (!budget.take()) return false;
                        result = left.charAt(textIndex) - right.charAt(textIndex);
                        textIndex++;
                        if (result != 0) {
                            phase = 5;
                            return true;
                        }
                    }
                    result = left.length() - right.length();
                }
                if (result != 0) {
                    phase = 5;
                    return true;
                }
                phase++;
                textIndex = 0;
            }
            if (phase == 5) {
                return true;
            }
            while (cursor < first.tag().length && cursor < second.tag().length) {
                if (!budget.take()) {
                    return false;
                }
                result = Byte.toUnsignedInt(first.tag()[cursor]) - Byte.toUnsignedInt(second.tag()[cursor]);
                cursor++;
                if (result != 0) {
                    phase = 5;
                    return true;
                }
            }
            result = first.tag().length - second.tag().length;
            phase = 5;
            return true;
        }

        @Override
        public int result() {
            return result;
        }
    }

    private record StableKey(AEKey key, ResourceLocation typeId, @Nullable ResourceLocation resourceId, byte[] tag) {
    }
}
