package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlotsNbt;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayType;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.persistence.ProcessComponentCodec;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.process.ProcessAbortListener;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementAttributeState;
import com.circulation.prototype_eng.api.recipe.requirement.AttributeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementAttributeStateTest {
    private static final KeyType TYPE = new KeyType("requirement_attribute_state");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final Key PRODUCT = new Key(TYPE, "product");
    private static final ResourceLocation GROUP = id("group");
    private static final ResourceLocation SCORE = id("score");
    private static final ResourceLocation OTHER_SCORE = id("other_score");
    private static final ComponentType<RecipeProcess, Listener> LISTENER = new ComponentType<>(id("listener"), _ -> new Listener(), List.of());
    private static final ResourceKeyNbt KEYS = new ResourceKeyNbt(location -> location.equals(TYPE.getId()) ? TYPE : null);
    private static final Runnable NO_OP = () -> {
    };

    private static RecipeProcessNbt codec(MachineRecipe recipe) {
        return builder(recipe).build();
    }

    private static RecipeProcessNbt.Builder builder(MachineRecipe recipe) {
        return RecipeProcessNbt.builder(recipe, "attribute_ownership_v2").resources(KEYS).component(LISTENER, new ListenerCodec());
    }

    private static Listener listener(RecipeProcess process) {
        return Objects.requireNonNull(process.components().get(LISTENER));
    }

    private static NumericAttributeModifier modifier(String name, double amount) {
        return new NumericAttributeModifier(name, amount, AttributeModifier.Operation.ADDITION);
    }

    private static RecipeRequirement<AttributeRequirement> attribute(String name, ResourceLocation attribute, double amount, boolean start, boolean end) {
        return RecipeRequirements.ATTRIBUTES.requirement(id(name), new AttributeRequirement(attribute, modifier(name, amount), start, end));
    }

    private static RecipeRequirement<ResourceRequirement> io() {
        return RecipeRequirements.RESOURCES.requirement(id("io"), new ResourceRequirement(List.of(new GenericStack(MATERIAL, 1)),
            List.of(new GenericStack(MATERIAL, 1)), List.of(), List.of(new GenericStack(PRODUCT, 1)), false));
    }

    private static MachineRecipe recipe(String name, int duration, RecipeRequirement<?>... requirements) {
        return new MachineRecipe(id(name), duration, List.of(requirements), Set.of(GROUP));
    }

    private static SlottedResourceBuffer buffer() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(TYPE, 100);
        return new SlottedResourceBuffer(2, capacities, NO_OP);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("attribute_state_test", path);
    }

    @Test
    void defaultCodecRestoresActiveBuiltinOwnershipAndEndKeepsEffectiveParallelismUntilOutputsComplete() {
        var env = new Environment(20);
        var attribute = attribute("parallel", StandardMachineAttributes.PROCESS_PARALLELISM, 1, true, true);
        var recipe = recipe("active", 2, attribute, io());
        var original = env.process(recipe);
        assertSame(ProcessResult.SUCCESS, original.tick());
        assertEquals(16, env.input.amount(MATERIAL));
        var codec = codec(recipe);
        var restored = codec.decode(env.machine, codec.encode(original));
        assertNotSame(original, restored);
        assertSame(ProcessResult.SUCCESS, restored.tick());
        assertEquals(14, env.input.amount(MATERIAL));
        assertEquals(2, env.output.amount(PRODUCT));
        assertEquals(1, restored.attributes().value(StandardMachineAttributes.PROCESS_PARALLELISM, 1));
        assertTrue(RequirementAttributeState.getOrCreate(restored).snapshot().owned().isEmpty());
        assertEquals(0, listener(restored).calls);
        var completed = codec.decode(env.machine, codec.encode(restored));
        completed.tick();
        assertEquals(14, env.input.amount(MATERIAL));
        assertEquals(2, env.output.amount(PRODUCT));
        assertEquals(0, listener(completed).calls);
    }

    @Test
    void cancellationAndBusinessFailurePersistReceiptsWithoutRefundingOrReplayingAbortCallbacks() {
        var env = new Environment(20);
        var attribute = attribute("retained_parallel", StandardMachineAttributes.PROCESS_PARALLELISM, 1, true, false);
        var recipe = recipe("cancelled", 3, attribute, io());
        var process = env.process(recipe);
        process.attributes().addModifier(SCORE, modifier("factory", 5));
        process.tick();
        var codec = codec(recipe);
        var active = codec.encode(process);
        assertTrue(process.cancel(new ProcessResult.Failure("explicit_cancel")));
        assertEquals(16, env.input.amount(MATERIAL));
        assertEquals(0, env.output.amount(PRODUCT));
        assertEquals(7, process.attributes().value(SCORE));
        assertEquals(1, process.attributes().value(StandardMachineAttributes.PROCESS_PARALLELISM, 1));
        assertEquals(1, listener(process).calls);
        var snapshot = RequirementAttributeState.getOrCreate(process).snapshot();
        assertTrue(snapshot.owned().isEmpty());
        assertEquals(1, snapshot.aborted().size());
        var saved = codec.encode(process);
        var restored = codec.decode(env.machine, saved);
        assertEquals(RecipeProcess.Lifecycle.CANCELLED, restored.state().lifecycle());
        assertTrue(restored.state().started());
        assertFalse(restored.cancel());
        assertEquals(new ProcessResult.Failure("explicit_cancel"), restored.tick());
        assertEquals(1, listener(restored).calls);
        assertEquals(16, env.input.amount(MATERIAL));
        assertEquals(0, env.output.amount(PRODUCT));
        var stale = saved.copy();
        stale.setTag("Attributes", active.getCompoundTag("Attributes").copy());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(env.machine, stale));
        var forged = active.copy();
        forged.setTag("State", saved.getCompoundTag("State").copy());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(env.machine, forged));
        int[] executions = {0};
        var definition = new WorkSlotDefinition("main", Set.of(GROUP));
        var host = new MachineWorkSlots(env.machine, List.of(definition), Set.of(), _ -> executions[0]++);
        host.restore(List.of(new MachineWorkSlots.SlotSnapshot(definition, restored, restored.state().result())));
        var hostCodec = new MachineWorkSlotsNbt("cancelled_layout", List.of(codec));
        hostCodec.restore(host, hostCodec.encode(host));
        var terminal = Objects.requireNonNull(host.requireSlot("main").process());
        host.tickProcesses();
        assertEquals(0, executions[0]);
        assertEquals(1, listener(terminal).calls);
        assertNull(terminal.host());

        var failedEnv = new Environment(20);
        int[] checks = {0};
        var failure = new ProcessResult.Failure("business_failure");
        var failing = new RecipeRequirementType<Boolean>(id("failing_type"), new RecipeRequirementSystem<>() {
            @Override
            public RequirementTransaction tick(RecipeProcess owner, RecipeRequirement<Boolean> requirement) {
                checks[0]++;
                return new Outcome(failure);
            }
        }).requirement(id("failing"), true);
        var failedRecipe = recipe("failed", 3, attribute, io(), failing);
        var failed = failedEnv.process(failedRecipe);
        assertSame(failure, failed.tick());
        assertTrue(failed.state().started());
        assertEquals(0, failed.state().successfulTicks());
        assertEquals(18, failedEnv.input.amount(MATERIAL));
        assertEquals(1, failed.attributes().value(StandardMachineAttributes.PROCESS_PARALLELISM, 1));
        var failedCodec = codec(failedRecipe);
        var loadedFailure = failedCodec.decode(failedEnv.machine, failedCodec.encode(failed));
        assertEquals(RecipeProcess.Lifecycle.FAILED, loadedFailure.state().lifecycle());
        assertFalse(loadedFailure.cancel());
        assertEquals(failure, loadedFailure.tick());
        assertEquals(1, checks[0]);
        assertEquals(1, listener(loadedFailure).calls);
        assertEquals(18, failedEnv.input.amount(MATERIAL));
        assertEquals(0, failedEnv.output.amount(PRODUCT));
    }

    @Test
    void replacedOwnershipFaultsWithoutDeletingForeignModifiersAndCleanupStillAttemptsOtherEntries() {
        var env = new Environment(0);
        var first = attribute("first", SCORE, 1, true, true);
        var second = attribute("second", OTHER_SCORE, 2, true, true);
        var recipe = recipe("replacement", 3, first, second);
        var process = env.process(recipe);
        process.tick();
        var reference = new RequirementAttributeState.Reference(first.path(null), SCORE);
        assertTrue(process.attributes().removeModifier(SCORE, reference.modifierId()));
        var foreign = modifier(reference.modifierId(), 99);
        assertTrue(process.attributes().addModifier(SCORE, foreign));
        assertThrows(IllegalStateException.class, process::cancel);
        assertSame(foreign, process.attributes().localSnapshot().get(SCORE).modifiers().get(reference.modifierId()));
        assertEquals(101, process.attributes().value(SCORE));
        assertEquals(2, process.attributes().value(OTHER_SCORE));
        assertEquals(1, listener(process).calls);
        assertThrows(IllegalStateException.class, () -> codec(recipe).encode(process));
        assertThrows(IllegalStateException.class, process::tick);
        assertEquals(1, listener(process).calls);
    }

    @Test
    void startFlagsRollbackAndChangedEndAttributeRespectActualInstalledOwnership() {
        var blockedEnv = new Environment(0);
        var parallel = attribute("parallel", StandardMachineAttributes.PROCESS_PARALLELISM, 1, true, true);
        var blockedRecipe = recipe("blocked", 3, parallel, io());
        var blocked = blockedEnv.process(blockedRecipe);
        assertInstanceOf(ProcessResult.Blocked.class, blocked.tick());
        assertEquals(1, blocked.attributes().value(StandardMachineAttributes.PROCESS_PARALLELISM, 1));
        assertTrue(RequirementAttributeState.getOrCreate(blocked).snapshot().owned().isEmpty());
        codec(blockedRecipe).decode(blockedEnv.machine, codec(blockedRecipe).encode(blocked));
        assertEquals(0, listener(blocked).calls);
        var unclaimed = attribute("not_applied", SCORE, 100, false, true);
        var unclaimedRecipe = recipe("unclaimed", 1, unclaimed);
        var foreignProcess = blockedEnv.process(unclaimedRecipe);
        var reference = new RequirementAttributeState.Reference(unclaimed.path(null), SCORE);
        var foreign = modifier(reference.modifierId(), 9);
        foreignProcess.attributes().addModifier(SCORE, foreign);
        foreignProcess.tick();
        assertEquals(11, foreignProcess.attributes().value(SCORE));
        assertSame(foreign, foreignProcess.attributes().localSnapshot().get(SCORE).modifiers().get(reference.modifierId()));
        var collision = attribute("collision", SCORE, 100, true, true);
        var collisionProcess = blockedEnv.process(recipe("collision", 1, collision));
        var collisionReference = new RequirementAttributeState.Reference(collision.path(null), SCORE);
        var existing = modifier(collisionReference.modifierId(), 9);
        collisionProcess.attributes().addModifier(SCORE, existing);
        collisionProcess.tick();
        assertSame(existing, collisionProcess.attributes().localSnapshot().get(SCORE).modifiers().get(collisionReference.modifierId()));
        assertEquals(11, collisionProcess.attributes().value(SCORE));
        var retained = attribute("keep_at_end", SCORE, 3, true, false);
        var retainedRecipe = recipe("retained", 1, retained);
        var retainedProcess = blockedEnv.process(retainedRecipe);
        retainedProcess.tick();
        var retainedCodec = codec(retainedRecipe);
        var retainedCopy = retainedCodec.decode(blockedEnv.machine, retainedCodec.encode(retainedProcess));
        retainedCopy.tick();
        assertEquals(5, retainedCopy.attributes().value(SCORE));

        var env = new Environment(20);
        var recipe = recipe("changed_attribute", 2, parallel, io());
        var process = env.process(recipe);
        process.tick();
        var overlay = new RecipeOverlayType<AttributeRequirement, ResourceLocation>(id("attribute_target"), RecipeRequirements.ATTRIBUTES,
            (base, target) -> new AttributeRequirement(target, base.modifier(), base.applyAtStart(), base.removeAtEnd()));
        RecipeOverlays.getOrCreate(process).put(parallel, "changed", overlay, OTHER_SCORE).complete();
        var codec = builder(recipe).overlay(overlay, new IdCodec()).validator(candidate -> {
            var state = candidate.components().get(RequirementAttributeState.TYPE);
            if (state != null) {
                for (var owned : state.snapshot().owned()) {
                    if (!owned.path().equals(parallel.path(null)) || !owned.attribute().equals(StandardMachineAttributes.PROCESS_PARALLELISM)) {
                        throw new IllegalArgumentException("Unexpected historical attribute target");
                    }
                }
            }
        }).build();
        var restored = codec.decode(env.machine, codec.encode(process));
        restored.tick();
        assertEquals(14, env.input.amount(MATERIAL));
        assertEquals(2, env.output.amount(PRODUCT));
        assertEquals(1, restored.attributes().value(StandardMachineAttributes.PROCESS_PARALLELISM, 1));
        assertEquals(2, restored.attributes().value(OTHER_SCORE));
        assertTrue(RequirementAttributeState.getOrCreate(restored).snapshot().owned().isEmpty());
    }

    @Test
    void sharedNestedAttributeDefinitionKeepsSeparatePathOwnershipThroughPersistenceAndEnd() {
        var env = new Environment(20);
        var child = attribute("shared_child", StandardMachineAttributes.PROCESS_PARALLELISM, 1, true, true);
        var first = RecipeRequirements.SELECTIVE.requirement(id("first_selector"), new SelectiveRequirement(List.of(child)));
        var second = RecipeRequirements.SELECTIVE.requirement(id("second_selector"), new SelectiveRequirement(List.of(child)));
        var recipe = recipe("nested", 2, first, second, io());
        var process = env.process(recipe);
        process.tick();
        // Resources acquire before the selector responsibility at START, then TICK sees both modifiers.
        assertEquals(16, env.input.amount(MATERIAL));
        var codec = codec(recipe);
        var restored = codec.decode(env.machine, codec.encode(process));
        restored.tick();
        assertEquals(13, env.input.amount(MATERIAL));
        assertEquals(3, env.output.amount(PRODUCT));
        assertEquals(1, restored.attributes().value(StandardMachineAttributes.PROCESS_PARALLELISM, 1));
        assertTrue(RequirementAttributeState.getOrCreate(restored).snapshot().owned().isEmpty());
    }

    private record Outcome(ProcessResult result) implements RequirementTransaction {
        @Override
        public void commit() {
        }

        @Override
        public void rollback() {
        }
    }

    private static final class Listener implements ProcessAbortListener {
        private int calls;

        @Override
        public void onAborted(RecipeProcess process) {
            calls++;
        }
    }

    private static final class ListenerCodec implements ProcessComponentCodec<Listener> {
        @Override
        public NBTTagCompound encode(RecipeProcess process, Listener component) {
            var tag = new NBTTagCompound();
            tag.setInteger("Calls", component.calls);
            return tag;
        }

        @Override
        public void restore(RecipeProcess process, Listener component, NBTTagCompound data) {
            StrictNbt.requireKeys(data, "Calls");
            component.calls = StrictNbt.requireInt(data, "Calls");
        }
    }

    private static final class IdCodec implements NbtCodec<ResourceLocation> {
        @Override
        public NBTTagCompound encode(ResourceLocation value) {
            var tag = new NBTTagCompound();
            tag.setString("Id", value.toString());
            return tag;
        }

        @Override
        public ResourceLocation decode(NBTTagCompound tag) {
            StrictNbt.requireKeys(tag, "Id");
            return StrictNbt.requireId(tag, "Id");
        }
    }

    private record Ports(@NotNull ResourceStorage input, @NotNull ResourceStorage output) implements ResourceHatch {
        private Ports {
            Objects.requireNonNull(input);
            Objects.requireNonNull(output);
        }

        @Override
        public boolean isValid() {
            return input.isValid() && output.isValid();
        }
    }

    private static final class Environment {
        private final MachineInstance machine = new MachineInstance();
        private final SlottedResourceBuffer input = buffer();
        private final SlottedResourceBuffer output = buffer();

        private Environment(long material) {
            machine.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
            machine.attributes().setBase(SCORE, 2);
            machine.attributes().setBase(OTHER_SCORE, 2);
            machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(new Ports(input, output)), List.of(), List.of()));
            input.insert(MATERIAL, material, Actionable.MODULATE);
        }

        private RecipeProcess process(MachineRecipe recipe) {
            var process = new RecipeProcess(machine, recipe, 17);
            process.components().create(LISTENER, process);
            return process;
        }
    }
}
