package com.circulation.prototype_eng.machine;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlotsNbt;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotRestartPolicy;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.persistence.ProcessComponentCodec;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineWorkSlotsPersistenceTest {
    private static final KeyType TYPE = new KeyType("work_slots_persistence");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final Key MISSING = new Key(TYPE, "missing");
    private static final Key PRODUCT = new Key(TYPE, "product");
    private static final ResourceLocation GROUP = id("group");
    private static final ComponentType<RecipeProcess, Probe> PROBE = new ComponentType<>(id("probe"), _ -> new Probe(), List.of());
    private static final ResourceKeyNbt KEYS = new ResourceKeyNbt(location -> location.equals(TYPE.getId()) ? TYPE : null);
    private static final List<WorkSlotDefinition> LAYOUT = List.of(new WorkSlotDefinition("running", Set.of(GROUP)),
        new WorkSlotDefinition("blocked", Set.of(GROUP)), new WorkSlotDefinition("idle", Set.of(GROUP), false, WorkSlotRestartPolicy.RESCAN));

    private static NBTTagList slots(NBTTagCompound tag) {
        return tag.getTagList("Slots", 10);
    }

    private static NBTTagCompound probeData(NBTTagCompound tag) {
        var components = slots(tag).getCompoundTagAt(0).getCompoundTag("Process").getTagList("Components", 10);
        for (int index = 0; index < components.tagCount(); index++) {
            var component = components.getCompoundTagAt(index);
            if (component.getString("Type").equals(PROBE.id().toString())) {
                return component.getCompoundTag("Data");
            }
        }
        throw new AssertionError("Saved probe component is absent");
    }

    private static RecipeRequirement<ResourceRequirement> io(long start, long tick, long end) {
        return RecipeRequirements.RESOURCES.requirement(id("io"), new ResourceRequirement(stacks(MATERIAL, start), stacks(MISSING, tick),
            List.of(), stacks(PRODUCT, end), false));
    }

    private static List<GenericStack> stacks(Key key, long amount) {
        return amount == 0 ? List.of() : List.of(new GenericStack(key, amount));
    }

    private static MachineRecipe recipe(String name, int duration, RecipeRequirement<?> requirement) {
        return new MachineRecipe(id(name), duration, List.of(requirement), Set.of(GROUP));
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("work_slots_test", path);
    }

    @Test
    void restoresRunningBlockedAndIdleFailureAboveCurrentCapacityWithoutReplayingStart() {
        var fixture = new Fixture();
        var before = fixture.host.snapshot();
        var saved = fixture.codec.encode(fixture.host);
        fixture.machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 1);
        fixture.codec.restore(fixture.host, saved);
        assertEquals(3, fixture.executions);
        assertEquals(2, fixture.host.activeProcesses().size());
        assertEquals(Objects.requireNonNull(before.get(0).process()).state(), fixture.host.activeProcesses().get(0).state());
        assertEquals(Objects.requireNonNull(before.get(1).process()).state(), fixture.host.activeProcesses().get(1).state());
        assertInstanceOf(ProcessResult.Blocked.class, fixture.host.requireSlot("blocked").result());
        assertNull(fixture.host.requireSlot("idle").process());
        assertEquals(new ProcessResult.Failure("work.failed", List.of("idle")), fixture.host.requireSlot("idle").result());
        for (var slot : before) {
            if (slot.process() != null) {
                assertNull(slot.process().host());
            }
        }
        for (var process : fixture.host.activeProcesses()) {
            assertSame(fixture.host, process.host());
        }
        var extra = new RecipeProcess(fixture.machine, fixture.runningRecipe, 91);
        assertFalse(fixture.host.startIn("idle", extra));
        assertNull(extra.host());
        fixture.host.tickProcesses();
        assertEquals(5, fixture.executions);
        assertEquals(7, fixture.input.amount(MATERIAL));
        assertEquals(0, fixture.output.amount(PRODUCT));
    }

    @Test
    void rejectsMalformedLateEntriesAndMetadataWithoutChangingOldSlotsOrPublishingDecodedProcesses() {
        var fixture = new Fixture();
        var saved = fixture.codec.encode(fixture.host);
        var old = fixture.host.snapshot();
        List<Consumer<NBTTagCompound>> corruptions = List.of(
            tag -> {
                var last = slots(tag).getCompoundTagAt(2);
                last.setTag("Process", slots(tag).getCompoundTagAt(1).getCompoundTag("Process").copy());
                last.setTag("Result", slots(tag).getCompoundTagAt(1).getCompoundTag("Result").copy());
                last.getCompoundTag("Process").setString("Recipe", "work_slots_test:unknown");
            },
            tag -> slots(tag).getCompoundTagAt(2).setString("Name", "blocked"),
            tag -> slots(tag).getCompoundTagAt(2).setString("Name", "changed"),
            tag -> {
                var groups = new NBTTagList();
                groups.appendTag(new NBTTagString(id("changed_group").toString()));
                slots(tag).getCompoundTagAt(2).setTag("Groups", groups);
            },
            tag -> slots(tag).getCompoundTagAt(2).setBoolean("Core", true),
            tag -> slots(tag).getCompoundTagAt(2).setString("RestartPolicy", "LOCKED_RETRY"),
            tag -> slots(tag).getCompoundTagAt(2).setString("RestartPolicy", "UNKNOWN"),
            tag -> tag.setString("Revision", "incompatible"),
            tag -> slots(tag).removeTag(2),
            tag -> slots(tag).getCompoundTagAt(2).removeTag("Result"));
        for (int index = 0; index < corruptions.size(); index++) {
            var malformed = saved.copy();
            corruptions.get(index).accept(malformed);
            int decodedBefore = fixture.probes.decoded.size();
            assertThrows(IllegalArgumentException.class, () -> fixture.codec.restore(fixture.host, malformed));
            assertEquals(old, fixture.host.snapshot());
            for (var process : fixture.host.activeProcesses()) {
                assertSame(fixture.host, process.host());
            }
            for (int decodedIndex = decodedBefore; decodedIndex < fixture.probes.decoded.size(); decodedIndex++) {
                assertNull(fixture.probes.decoded.get(decodedIndex).host());
            }
            if (index == 0) {
                assertEquals(2, fixture.probes.decoded.size() - decodedBefore);
            }
        }
        assertEquals(3, fixture.executions);
        assertEquals(7, fixture.input.amount(MATERIAL));
        assertEquals(0, fixture.output.amount(PRODUCT));
    }

    @Test
    void completedProcessRestorationOnlyTransfersAttachmentAndNeverInvokesTheExecutorAgain() {
        var fixture = new Fixture();
        var terminalRecipe = recipe("terminal", 0, io(2, 0, 4));
        var completed = new RecipeProcess(fixture.machine, terminalRecipe, 17);
        assertSame(ProcessResult.SUCCESS, completed.tick());
        assertEquals(4, fixture.output.amount(PRODUCT));
        int[] executorCalls = {0};
        var definition = new WorkSlotDefinition("terminal", Set.of(GROUP));
        var host = new MachineWorkSlots(fixture.machine, List.of(definition), Set.of(), process -> {
            executorCalls[0]++;
            process.tick();
        });
        host.restore(List.of(new MachineWorkSlots.SlotSnapshot(definition, completed, ProcessResult.SUCCESS)));
        var codec = new MachineWorkSlotsNbt("terminal_layout", List.of(RecipeProcessNbt.builder(terminalRecipe, "recipe_v1").resources(KEYS).build()));
        var saved = codec.encode(host);
        codec.restore(host, saved);
        var restored = Objects.requireNonNull(host.requireSlot("terminal").process());
        assertNotSame(completed, restored);
        assertNull(completed.host());
        assertSame(host, restored.host());
        assertEquals(0, executorCalls[0]);
        host.tickProcesses();
        assertEquals(0, executorCalls[0]);
        assertNull(restored.host());
        assertTrue(host.activeProcesses().isEmpty());
        assertEquals(4, fixture.output.amount(PRODUCT));
        assertEquals(5, fixture.input.amount(MATERIAL));
    }

    @Test
    void successiveLoadsAndMutableInputTagsDoNotShareComponentDataOrAttachments() {
        var fixture = new Fixture();
        var source = fixture.host.activeProcesses().getFirst();
        var saved = fixture.codec.encode(fixture.host);
        Objects.requireNonNull(source.components().get(PROBE)).data.setInteger("Stamp", 77);
        assertEquals(1, probeData(saved).getInteger("Stamp"));
        fixture.codec.restore(fixture.host, saved);
        var first = fixture.host.activeProcesses().getFirst();
        var firstProbe = Objects.requireNonNull(first.components().get(PROBE));
        probeData(saved).setInteger("Stamp", 2);
        assertEquals(1, firstProbe.data.getInteger("Stamp"));
        fixture.codec.restore(fixture.host, saved);
        var second = fixture.host.activeProcesses().getFirst();
        var secondProbe = Objects.requireNonNull(second.components().get(PROBE));
        assertNotSame(first, second);
        assertNull(first.host());
        assertSame(fixture.host, second.host());
        assertEquals(2, secondProbe.data.getInteger("Stamp"));
        firstProbe.data.setInteger("Stamp", 99);
        probeData(saved).setInteger("Stamp", 3);
        assertEquals(2, secondProbe.data.getInteger("Stamp"));
        assertEquals(3, fixture.executions);
        assertEquals(7, fixture.input.amount(MATERIAL));
    }

    private enum FailedStart implements RequirementTransaction {
        INSTANCE;

        @Override
        public ProcessResult result() {
            return new ProcessResult.Failure("work.failed", List.of("idle"));
        }

        @Override
        public void commit() {
            throw new AssertionError("Rejected work cannot commit");
        }

        @Override
        public void rollback() {
        }
    }

    private static final class Probe {
        private NBTTagCompound data = new NBTTagCompound();
    }

    private static final class ProbeCodec implements ProcessComponentCodec<Probe> {
        private final ObjectArrayList<RecipeProcess> decoded = new ObjectArrayList<>();

        @Override
        public NBTTagCompound encode(RecipeProcess process, Probe component) {
            return component.data.copy();
        }

        @Override
        public void restore(RecipeProcess process, Probe component, NBTTagCompound data) {
            component.data = data.copy();
            decoded.add(process);
        }
    }

    private static final class Fixture {
        private final MachineInstance machine = new MachineInstance();
        private final @NotNull SlottedResourceStorage input;
        private final @NotNull SlottedResourceStorage output;
        private final MachineRecipe runningRecipe = recipe("running", 5, io(2, 0, 4));
        private final ProbeCodec probes = new ProbeCodec();
        private final MachineWorkSlots host;
        private final MachineWorkSlotsNbt codec;
        private int executions;

        private Fixture() {
            var capacities = new Reference2LongArrayMap<AEKeyType>();
            capacities.put(TYPE, 100);
            var hatch = new BufferedResourceHatch(HatchMode.MIXED, 2, capacities, () -> {
            });
            input = Objects.requireNonNull(hatch.input());
            output = Objects.requireNonNull(hatch.output());
            input.insert(MATERIAL, 10, Actionable.MODULATE);
            machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(hatch), List.of(), List.of()));
            machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 3);
            host = new MachineWorkSlots(machine, LAYOUT, Set.of(), process -> {
                executions++;
                process.tick();
            });
            var blockedRecipe = recipe("blocked", 5, io(1, 1, 2));
            var running = new RecipeProcess(machine, runningRecipe, 11);
            var blocked = new RecipeProcess(machine, blockedRecipe, 12);
            for (var process : List.of(running, blocked)) {
                process.components().create(PROBE, process).data.setInteger("Stamp", 1);
            }
            var failureType = new RecipeRequirementType<>(id("failure"), new RecipeRequirementSystem<String>() {
                @Override
                public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                    return FailedStart.INSTANCE;
                }
            });
            var failing = new RecipeProcess(machine, recipe("failing", 5, failureType.requirement(id("fail"), "failure")), 13);
            assertTrue(host.startIn("running", running));
            assertTrue(host.startIn("blocked", blocked));
            assertTrue(host.startIn("idle", failing));
            host.tickProcesses();
            codec = new MachineWorkSlotsNbt("layout_v1", List.of(
                RecipeProcessNbt.builder(runningRecipe, "recipe_v1").resources(KEYS).component(PROBE, probes).build(),
                RecipeProcessNbt.builder(blockedRecipe, "recipe_v1").resources(KEYS).component(PROBE, probes).build()));
        }
    }
}
