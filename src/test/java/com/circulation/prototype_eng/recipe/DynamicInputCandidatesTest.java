package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.matching.ResourceMatchers;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.recipe.requirement.DynamicInputCandidates;
import com.circulation.prototype_eng.recipe.requirement.NbtLongArrayAccess;
import com.circulation.prototype_eng.scheduler.planned.PlannedRecipeCoordinator;
import com.circulation.prototype_eng.scheduler.planned.PlannedTaskExecutor;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLongArray;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicInputCandidatesTest {
    private static final NamedResourceMatcher ALL = new NamedResourceMatcher(id("all"), (_, _) -> true);

    private static NBTTagCompound nested(int first, int second, boolean reversed) {
        var nested = new NBTTagCompound();
        // These two names share Java's string hash; their insertion order must still be irrelevant.
        if (reversed) {
            nested.setInteger("BB", second);
            nested.setInteger("Aa", first);
        } else {
            nested.setInteger("Aa", first);
            nested.setInteger("BB", second);
        }
        var tag = new NBTTagCompound();
        tag.setTag("nested", nested);
        return tag;
    }

    private static NBTTagCompound orderedList(int first, int second) {
        var list = new NBTTagList();
        list.appendTag(new NBTTagInt(first));
        list.appendTag(new NBTTagInt(second));
        var tag = new NBTTagCompound();
        tag.setTag("list", list);
        return tag;
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("dynamic_test", path);
    }

    @Test
    void allSourceKeysAreSortedBeforeTruncationAndPresentationKeysDoNotFilterRuntimeKeys() {
        var type = new KeyType("dynamic_keys");
        var a = new Key(type, "a");
        var b = new Key(type, "b");
        var z = new Key(type, "z");
        var group = new DynamicInputGroup(ALL, z, 1, List.of(z), 2);
        var available = new ObjectArrayList<AEKey>(List.of(z, b, a, new Key(type, "b")));
        assertEquals(List.of(a, b), DynamicInputCandidates.enumerate(available, group));
        available.sort(Comparator.comparing(key -> Objects.requireNonNull(key.getId()).toString()));
        assertEquals(List.of(a, b), DynamicInputCandidates.enumerate(available, group));
        assertThrows(UnsupportedOperationException.class, () -> DynamicInputCandidates.enumerate(available, group).clear());
    }

    @Test
    void matcherBindingsWithTheSameNameRemainIndependentAndExactFiltersBeforeTruncation() {
        var type = new KeyType("dynamic_matching");
        var ore = new Key(type, "ore");
        var ingot = new Key(type, "ingot");
        var available = List.<AEKey>of(ingot, ore);
        var oreBinding = new NamedResourceMatcher(id("custom"), (candidate, _) -> candidate.equals(ore));
        var ingotBinding = new NamedResourceMatcher(id("custom"), (candidate, _) -> candidate.equals(ingot));
        assertEquals(List.of(ore), DynamicInputCandidates.enumerate(available, new DynamicInputGroup(oreBinding, ore, 1)));
        assertEquals(List.of(ingot), DynamicInputCandidates.enumerate(available, new DynamicInputGroup(ingotBinding, ore, 1)));
        assertEquals(List.of(ore), DynamicInputCandidates.enumerate(available, new DynamicInputGroup(ResourceMatchers.EXACT, ore, 1)));
    }

    @Test
    void numericArraysParticipateByTheirCompleteValuesRatherThanLengths() {
        var type = new TaggedKeyType("arrays");
        for (int kind = 0; kind < 3; kind++) {
            var lowerTag = new NBTTagCompound();
            var higherTag = new NBTTagCompound();
            switch (kind) {
                case 0 -> {
                    lowerTag.setByteArray("data", new byte[]{1, 2});
                    higherTag.setByteArray("data", new byte[]{1, 9});
                }
                case 1 -> {
                    lowerTag.setIntArray("data", new int[]{1, 2});
                    higherTag.setIntArray("data", new int[]{1, 9});
                }
                case 2 -> {
                    lowerTag.setTag("data", new NBTTagLongArray(new long[]{1, 2}));
                    higherTag.setTag("data", new NBTTagLongArray(new long[]{1, 9}));
                }
                default -> throw new AssertionError();
            }
            var lower = new TaggedKey(type, lowerTag, "");
            var higher = new TaggedKey(type, higherTag, "");
            var group = new DynamicInputGroup(ALL, higher, 1, List.of(), 1);
            assertEquals(List.of(lower), DynamicInputCandidates.enumerate(List.of(higher, lower), group));
            assertEquals(List.of(lower), DynamicInputCandidates.enumerate(List.of(lower, higher), group));
        }
    }

    @Test
    void nestedCompoundInsertionOrderIsNormalizedWhileListOrderIsPreserved() {
        var type = new TaggedKeyType("nested");
        var low = new TaggedKey(type, nested(2, 9, false), "");
        var high = new TaggedKey(type, nested(5, 1, true), "");
        var group = new DynamicInputGroup(ALL, high, 1);
        assertEquals(List.of(low, high), DynamicInputCandidates.enumerate(List.of(high, low), group));
        var restoredLow = new TaggedKey(type, nested(2, 9, true), "");
        var restoredHigh = new TaggedKey(type, nested(5, 1, false), "");
        assertEquals(List.of(low, high), DynamicInputCandidates.enumerate(List.of(restoredHigh, restoredLow), group));

        var listLow = new TaggedKey(type, orderedList(1, 2), "");
        var listHigh = new TaggedKey(type, orderedList(2, 1), "");
        assertEquals(List.of(listLow, listHigh), DynamicInputCandidates.enumerate(List.of(listHigh, listLow), group));
    }

    @Test
    void ambiguousSerializationNeedsAnExplicitStableOrderRetainedByTheGroup() {
        var type = new TaggedKeyType("ambiguous");
        var a = new TaggedKey(type, new NBTTagCompound(), "a");
        var b = new TaggedKey(type, new NBTTagCompound(), "b");
        var available = List.<AEKey>of(b, a);
        var canonical = new DynamicInputGroup(ALL, a, 1, List.of(), 1);
        assertThrows(IllegalArgumentException.class, () -> DynamicInputCandidates.enumerate(available, canonical));
        var stable = new DynamicInputGroup(ALL, a, 1, List.of(), 1,
            Comparator.comparing(key -> ((TaggedKey) key).discriminator));
        assertEquals(List.of(a), DynamicInputCandidates.enumerate(available, stable));
        var ambiguous = new DynamicInputGroup(ALL, a, 1, List.of(), 1, (_, _) -> 0);
        assertThrows(IllegalArgumentException.class, () -> DynamicInputCandidates.enumerate(available, ambiguous));
    }

    @Test
    void realDynamicRecipeReadsEveryLongArrayElementAcrossPureSlicesBeforeChoosingTheCanonicalKey() {
        var type = new TaggedKeyType("sliced_long_arrays");
        long[] firstValues = new long[512];
        long[] secondValues = new long[512];
        firstValues[511] = 1;
        secondValues[511] = 9;
        var firstTag = new NBTTagCompound();
        firstTag.setTag("data", new ReadableLongArray(firstValues));
        var secondTag = new NBTTagCompound();
        secondTag.setTag("data", new ReadableLongArray(secondValues));
        var first = new TaggedKey(type, firstTag, "first");
        var second = new TaggedKey(type, secondTag, "second");
        var warehouse = new BufferedResourceHatch(HatchMode.INPUT, 2, Map.of(type, 10L), () -> {
        });
        var input = Objects.requireNonNull(warehouse.input());
        input.insert(second, 1, Actionable.MODULATE);
        input.insert(first, 1, Actionable.MODULATE);
        var group = new DynamicInputGroup(ALL, first, 1, List.of(), 1);
        assertEquals(List.of(first), DynamicInputCandidates.enumerate(List.of(second, first), group));
        var requirement = RecipeRequirements.RESOURCES.requirement(id("long_array_material"), new ResourceRequirement(
            List.of(), List.of(), List.of(), List.of(), false, ResourceChance.ALWAYS, null, List.of(), List.of(group)));
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(warehouse), List.of(), List.of()));
        var slots = new MachineWorkSlots(machine, List.of());
        var process = new RecipeProcess(machine, new MachineRecipe(id("long_array_recipe"), 0, List.of(requirement)), 13);
        assertTrue(slots.start(process));
        try (var workers = Executors.newFixedThreadPool(2); var lane = Executors.newSingleThreadExecutor()) {
            var tasks = new PlannedTaskExecutor(workers, new ExecutorService[]{lane});
            var deadline = PlannedDeadline.after(Duration.ofSeconds(10));
            var coordinator = new PlannedRecipeCoordinator(tasks, List.of(slots), deadline, new PlannedCancellationToken(), () -> true);
            coordinator.workPerAdvance(64);
            assertFalse(coordinator.advance(PlannedDeadline.after(Duration.ofSeconds(10))));
            int slices = 0;
            while (!coordinator.advance(PlannedDeadline.after(Duration.ofSeconds(10)))) {
                assertTrue(++slices < 10_000);
            }
            assertTrue(slices > 100, "Late long-array data must be read and compared under the original allowance");
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
            assertEquals(0, input.amount(first));
            assertEquals(1, input.amount(second));
        }
    }

    /**
     * Supplies the same read-only view the production Mixin supplies, without starting FML.
     */
    private static class ReadableLongArray extends NBTTagLongArray implements NbtLongArrayAccess {
        private final long[] values;

        private ReadableLongArray(long[] values) {
            super(values);
            this.values = values;
        }

        @Override
        public @Nonnull NBTTagLongArray copy() {
            return new ReadableLongArray(values.clone());
        }

        @Override
        public int prototype_eng$length() {
            return values.length;
        }

        @Override
        public long prototype_eng$value(int index) {
            return values[index];
        }
    }

    private static final class TaggedKeyType extends AEKeyType {
        private TaggedKeyType(String name) {
            super(id(name), TaggedKey.class, new TextComponentString(name));
        }

        @Override
        public AEKey readFromPacket(PacketBuffer input) {
            throw new UnsupportedOperationException("This fixture exercises snapshot keys");
        }

        @Override
        public AEKey loadKeyFromTag(NBTTagCompound tag) {
            return new TaggedKey(this, tag, "");
        }
    }

    private static final class TaggedKey extends AEKey {
        private final TaggedKeyType type;
        private final NBTTagCompound tag;
        private final String discriminator;

        private TaggedKey(TaggedKeyType type, NBTTagCompound tag, String discriminator) {
            this.type = type;
            this.tag = tag.copy();
            this.discriminator = discriminator;
        }

        @Override
        public AEKeyType getType() {
            return type;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public Object getPrimaryKey() {
            return type;
        }

        @Override
        public ResourceLocation getId() {
            return id("same_resource");
        }

        @Override
        public Object getReadOnlyStack() {
            return this;
        }

        @Override
        public NBTTagCompound toTag() {
            return tag.copy();
        }

        @Override
        public NBTBase get(String componentId) {
            return tag.getTag(componentId);
        }

        @Override
        protected ITextComponent computeDisplayName() {
            return new TextComponentString(discriminator);
        }

        @Override
        public void writeToPacket(PacketBuffer output) {
            throw new UnsupportedOperationException("This fixture exercises snapshot keys");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TaggedKey key && type == key.type && tag.equals(key.tag) && discriminator.equals(key.discriminator);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, tag, discriminator);
        }
    }
}
