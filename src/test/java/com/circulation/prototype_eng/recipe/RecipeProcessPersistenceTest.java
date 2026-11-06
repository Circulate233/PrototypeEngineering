package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.component.ComponentSystem;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.persistence.AttributeModifierNbt;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayTypes;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.overlay.ResourceRequirementPatch;
import com.circulation.prototype_eng.api.recipe.persistence.ProcessComponentCodec;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.probability.WeightedSampling;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.ResourceInputLocks;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.FuzzyInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RandomOutputPool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveModifierDescriptor;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveState;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeProcessPersistenceTest {
    private static final KeyType KEY_TYPE = new KeyType("process_persistence");
    private static final Key MATERIAL = new Key(KEY_TYPE, "material");
    private static final Key PRODUCT = new Key(KEY_TYPE, "product");
    private static final Key ALTERNATE = new Key(KEY_TYPE, "alternate");
    private static final ResourceLocation SCORE = id("score");
    private static final long CAPACITY = 10000;
    private static final Runnable NO_OP = () -> { };
    private static final ResourceKeyNbt KEYS = new ResourceKeyNbt(location -> location.equals(KEY_TYPE.getId()) ? KEY_TYPE : null);

    @Test
    void blockedTickAndEndRoundTripsKeepCommittedStartResourceEnergyAndInputChoice() {
        var resource = RecipeRequirements.RESOURCES.requirement(id("io"), new ResourceRequirement(
            stacks(MATERIAL, 2), stacks(MATERIAL, 3), List.of(), stacks(PRODUCT, 5), false, ResourceChance.ALWAYS, null,
            List.of(new FuzzyInputGroup(List.of(MATERIAL, ALTERNATE), 3)), List.of()));
        var recipe = recipe(2, resource, energy(7, 11, 13));
        var codec = codec(recipe);
        var env = new Environment();
        env.feed(5, 7);
        var original = process(env, recipe);
        assertInstanceOf(ProcessResult.Blocked.class, original.tick());
        assertEquals(RecipeProcess.Lifecycle.RUNNING, original.state().lifecycle());
        assertEquals(0, original.state().successfulTicks());
        assertEquals(0, env.input.amount(MATERIAL));
        assertEquals(0, env.energy.storedEnergy());
        var choices = Objects.requireNonNull(original.components().get(ResourceInputLocks.TYPE)).snapshot();
        assertEquals(MATERIAL, choices.values().iterator().next());
        var first = codec.decode(env.machine, codec.encode(original));
        assertNotSame(original, first);
        assertEquals(original.state(), first.state());
        assertEquals(choices, Objects.requireNonNull(first.components().get(ResourceInputLocks.TYPE)).snapshot());
        env.input.insert(ALTERNATE, 7, Actionable.MODULATE);
        env.feed(6, 22);
        env.output.insert(PRODUCT, CAPACITY, Actionable.MODULATE);
        env.generated.receiveEnergy(CAPACITY, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, first.tick());
        assertEquals(1, first.state().successfulTicks());
        assertEquals(3, env.input.amount(MATERIAL));
        assertEquals(11, env.energy.storedEnergy());
        var second = codec.decode(env.machine, codec.encode(first));
        assertInstanceOf(ProcessResult.Blocked.class, second.tick());
        assertEquals(2, second.state().successfulTicks());
        assertEquals(2, second.state().progress());
        assertEquals(0, env.input.amount(MATERIAL));
        assertEquals(0, env.energy.storedEnergy());
        var ending = codec.decode(env.machine, codec.encode(second));
        assertInstanceOf(ProcessResult.Blocked.class, ending.tick());
        assertEquals(second.state(), ending.state());
        env.output.extract(PRODUCT, CAPACITY, Actionable.MODULATE);
        env.generated.extractEnergy(CAPACITY, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, ending.tick());
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, ending.state().lifecycle());
        assertEquals(2, ending.state().successfulTicks());
        assertEquals(5, env.output.amount(PRODUCT));
        assertEquals(13, env.generated.storedEnergy());
        assertEquals(7, env.input.amount(ALTERNATE));
        assertEquals(0, original.state().successfulTicks());
        assertEquals(1, first.state().successfulTicks());
    }

    @Test
    void probabilitySeedTuningAndSuccessfulTickSaltsSurviveRepeatedSavesAndBlockedRetries() {
        var pool = new RandomOutputPool(List.of(
            new WeightedSampling.Weighted<>(new GenericStack(PRODUCT, 2), 2),
            new WeightedSampling.Weighted<>(new GenericStack(ALTERNATE, 1), 1)), 1);
        var resource = RecipeRequirements.RESOURCES.requirement(id("chance"), new ResourceRequirement(
            stacks(MATERIAL, 2), stacks(MATERIAL, 1), stacks(PRODUCT, 1), stacks(PRODUCT, 3), false, new ResourceChance(140, null), pool));
        var recipe = recipe(5, resource, energy(7, 11, 13));
        var codec = codec(recipe);
        var baselineEnv = new Environment();
        var restoredEnv = new Environment();
        baselineEnv.machine.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 4);
        restoredEnv.machine.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 4);
        baselineEnv.feed(1000, 1000);
        restoredEnv.feed(1000, 1000);
        var tuning = new ProbabilityTuning(true, 32);
        var baseline = new RecipeProcess(baselineEnv.machine, recipe, 991, tuning);
        var restored = new RecipeProcess(restoredEnv.machine, recipe, 991, tuning);
        var previousDefault = ProbabilityTuning.defaultTuning();
        try {
            ProbabilityTuning.setDefault(new ProbabilityTuning(false, 0));
            for (int tick = 0; tick < 5; tick++) {
                assertSame(ProcessResult.SUCCESS, baseline.tick());
                if (tick == 2) {
                    long removed = restoredEnv.input.extract(MATERIAL, CAPACITY, Actionable.MODULATE);
                    var before = restored.state();
                    assertInstanceOf(ProcessResult.Blocked.class, restored.tick());
                    assertEquals(before.successfulTicks(), restored.state().successfulTicks());
                    restored = codec.decode(restoredEnv.machine, codec.encode(restored));
                    restoredEnv.input.insert(MATERIAL, removed, Actionable.MODULATE);
                }
                if (tick == 4) { restoredEnv.generated.receiveEnergy(CAPACITY, Actionable.MODULATE); }
                var result = restored.tick();
                if (tick == 4) {
                    assertInstanceOf(ProcessResult.Blocked.class, result);
                    assertEquals(5, restored.state().successfulTicks());
                    restored = codec.decode(restoredEnv.machine, codec.encode(restored));
                    restoredEnv.generated.extractEnergy(CAPACITY, Actionable.MODULATE);
                    assertSame(ProcessResult.SUCCESS, restored.tick());
                } else { assertSame(ProcessResult.SUCCESS, result); }
                assertEquals(baseline.state(), restored.state());
                assertEquals(baselineEnv.balances(), restoredEnv.balances());
                var saved = codec.encode(restored);
                restored = codec.decode(restoredEnv.machine, saved);
                assertEquals(tuning, restored.probabilityTuning());
                assertEquals(991, restored.seed());
                assertEquals(baseline.state(), restored.state());
            }
        } finally {
            ProbabilityTuning.setDefault(previousDefault);
        }
    }

    @Test
    void selectorEffectsRoundTripWithoutReplayingSideEffectsAndEndRemovesOnlyOwnedLayers() {
        var env = new Environment();
        env.feed(100, 100);
        env.machine.attributes().setBase(SCORE, 2);
        env.machine.attributes().addModifier(SCORE, new NumericAttributeModifier("parent", 1, AttributeModifier.Operation.MULTIPLY_TOTAL));
        var resource = RecipeRequirements.RESOURCES.requirement(id("io"), new ResourceRequirement(
            stacks(MATERIAL, 2), stacks(MATERIAL, 1), List.of(), stacks(PRODUCT, 3), false));
        int[] applications = {0};
        var candidate = new RecipeRequirementType<String>(id("candidate_type"), new RecipeRequirementSystem<>() { })
            .requirement(id("candidate"), "optional");
        var selection = RecipeRequirements.SELECTIVE.requirement(id("choice"), new SelectiveRequirement(List.of(candidate), List.of(
            new SelectiveModifierDescriptor(id("effect"), context -> {
                applications[0]++;
                context.putProcessAttributeModifier(SCORE, new NumericAttributeModifier("bonus", 3, AttributeModifier.Operation.ADDITION));
                context.putRequirementOverlay(resource, "output", RecipeOverlayTypes.RESOURCE_IO,
                    new ResourceRequirementPatch(null, null, null, stacks(PRODUCT, 4), null));
            }))));
        var recipe = recipe(2, selection, resource, energy(5, 7, 11));
        var codec = codec(recipe);
        var original = process(env, recipe);
        original.attributes().addModifier(SCORE, new NumericAttributeModifier("foreign", 5, AttributeModifier.Operation.ADDITION));
        var overlays = RecipeOverlays.getOrCreate(original);
        overlays.put(resource, "foreign_before", RecipeOverlayTypes.RESOURCE_IO,
            new ResourceRequirementPatch(null, null, null, stacks(PRODUCT, 6), null)).complete();
        assertSame(ProcessResult.SUCCESS, original.tick());
        overlays.put(resource, "foreign_after", RecipeOverlayTypes.RESOURCE_IO,
            new ResourceRequirementPatch(null, stacks(MATERIAL, 3), null, null, null)).complete();
        var restored = codec.decode(env.machine, codec.encode(original));
        assertEquals(1, applications[0]);
        assertEquals(20, restored.attributes().value(SCORE));
        assertEquals(overlays.snapshot(), RecipeOverlays.getOrCreate(restored).snapshot());
        env.generated.receiveEnergy(CAPACITY, Actionable.MODULATE);
        assertInstanceOf(ProcessResult.Blocked.class, restored.tick());
        assertEquals(2, restored.state().successfulTicks());
        assertEquals(94, env.input.amount(MATERIAL));
        assertEquals(20, restored.attributes().value(SCORE));
        restored = codec.decode(env.machine, codec.encode(restored));
        env.generated.extractEnergy(CAPACITY, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, restored.tick());
        assertEquals(4, env.output.amount(PRODUCT));
        assertEquals(11, env.generated.storedEnergy());
        assertEquals(14, restored.attributes().value(SCORE));
        var finalLayers = RecipeOverlays.getOrCreate(restored);
        assertEquals(2, finalLayers.snapshot().size());
        assertEquals(6, finalLayers.resolve(resource).payload().outputs().getFirst().amount());
        assertEquals(3, finalLayers.resolve(resource).payload().inputsPerTick().getFirst().amount());
        assertTrue(Objects.requireNonNull(restored.components().get(SelectiveState.TYPE)).snapshot().get(selection.path(null)).completed());
        assertEquals(1, applications[0]);
        assertEquals(20, original.attributes().value(SCORE));
    }

    @Test
    void externalCodecTagsAreIsolatedFromEncodingInputAndSubsequentDecodes() {
        var fixture = new ExtensionFixture(recipe(1));
        var original = process(new Environment(), fixture.recipe);
        var box = original.components().create(fixture.first, original);
        box.set(10);
        var codec = fixture.codec();
        var encoded = codec.encode(original);
        data(encoded, fixture.first).setInteger("Value", 20);
        assertEquals(10, box.value());
        var restored = codec.decode(original.owner(), encoded);
        assertEquals(20, Objects.requireNonNull(restored.components().get(fixture.first)).value());
        data(encoded, fixture.first).setInteger("Value", 30);
        assertEquals(20, Objects.requireNonNull(restored.components().get(fixture.first)).value());
        Objects.requireNonNull(restored.components().get(fixture.first)).set(40);
        assertEquals(30, data(encoded, fixture.first).getInteger("Value"));
        var next = codec.decode(original.owner(), encoded);
        assertEquals(30, Objects.requireNonNull(next.components().get(fixture.first)).value());
        assertEquals(10, box.value());
    }

    @Test
    void malformedMiddleAndFinalComponentsRevisionAndGraphRejectWithoutChangingTheOldProcess() {
        var fixture = new ExtensionFixture(recipe(1, RecipeRequirements.RESOURCES.requirement(id("graph_io"),
            new ResourceRequirement(List.of(), List.of()))));
        var original = process(new Environment(), fixture.recipe);
        original.components().create(fixture.first, original).set(10);
        original.components().create(fixture.second, original).set(20);
        original.components().create(fixture.third, original).set(30);
        original.components().removeDependency(fixture.first, fixture.second);
        var codec = fixture.codec();
        var pristine = codec.encode(original);
        for (var type : List.of(fixture.second, fixture.third)) {
            var corrupt = pristine.copy();
            data(corrupt, type).setString("Value", "wrong tag type");
            assertThrows(IllegalArgumentException.class, () -> codec.decode(original.owner(), corrupt));
            assertEquals(pristine, codec.encode(original));
        }
        var wrongRevision = pristine.copy();
        wrongRevision.setString("Revision", "another revision");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(original.owner(), wrongRevision));
        var changedGraph = pristine.copy();
        var path = (NBTTagList) changedGraph.getTagList("Graph", Constants.NBT.TAG_LIST).get(0);
        path.getCompoundTagAt(0).setString("Id", "changed:instance");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(original.owner(), changedGraph));
        var wrongDuration = pristine.copy();
        wrongDuration.setInteger("Duration", 99);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(original.owner(), wrongDuration));
        var wrongStateType = pristine.copy();
        wrongStateType.getCompoundTag("State").setFloat("Progress", 0);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(original.owner(), wrongStateType));
        assertEquals(pristine, codec.encode(original));
    }

    @Test
    void allExternalComponentsExistBeforeRestoreAndActualDependenciesOverrideRemovedDefaults() {
        var fixture = new ExtensionFixture(recipe(3));
        fixture.inspectDependencies = true;
        var original = process(new Environment(), fixture.recipe);
        original.components().create(fixture.first, original).set(1);
        original.components().create(fixture.third, original).set(3);
        original.components().create(fixture.second, original).set(2);
        original.components().removeDependency(fixture.first, fixture.second);
        original.components().addDependency(fixture.second, fixture.third);
        original.components().addDependency(fixture.future, fixture.third);
        var codec = fixture.codec();
        var saved = codec.encode(original);
        fixture.events.clear();
        var restored = codec.decode(original.owner(), saved);
        assertEquals(List.of("create:first", "create:third", "create:second", "restore:first", "restore:third", "restore:second",
            "validate:first", "validate:third", "validate:second", "validate:host"), fixture.events);
        assertEquals(Set.of(fixture.third), restored.components().snapshotPendingDependencies().get(fixture.future));
        fixture.events.clear();
        assertSame(ProcessResult.SUCCESS, restored.tick());
        assertEquals(List.of("tick:first", "tick:third", "tick:second"), fixture.events);
        restored.components().create(fixture.future, restored);
        var future = restored.components().snapshotBindings().stream().filter(binding -> binding.type() == fixture.future).findFirst().orElseThrow();
        assertEquals(Set.of(fixture.third), future.dependencies());
        fixture.events.clear();
        assertSame(ProcessResult.SUCCESS, restored.tick());
        assertEquals(List.of("tick:first", "tick:third", "tick:second", "tick:future"), fixture.events);
    }

    @Test
    void openAcquisitionAndFailedRollbackCannotBeSavedEvenWhenTokensAreReleased() {
        var env = new Environment();
        env.feed(20, 20);
        boolean[] failRollback = {false};
        var rollbackFault = new IllegalStateException("rollback violated its contract");
        var external = new RecipeRequirementType<Integer>(id("external"), new RecipeRequirementSystem<>() {
            @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Integer> requirement) {
                var material = env.input.reserveExtract(env.input.snapshot(), MATERIAL, 2);
                var energy = env.energy.reserveExtractEnergy(env.energy.energySnapshot(), 3);
                assertEquals(ReservationResult.Status.ACCEPTED, material.status());
                assertEquals(ReservationResult.Status.ACCEPTED, energy.status());
                return new Transaction(List.of(Objects.requireNonNull(material.reservation()), Objects.requireNonNull(energy.reservation())),
                    () -> { if (failRollback[0]) { throw rollbackFault; } });
            }
        }).requirement(id("external_instance"), 1);
        var recipe = recipe(1, external);
        var codec = codec(recipe);
        var original = process(env, recipe);
        var acquired = external.acquire(original, RecipeStage.START);
        assertThrows(IllegalStateException.class, () -> codec.encode(original));
        ResourceTransactions.rollback(acquired.reservations(), acquired::rollback);
        codec.encode(original);
        assertEquals(20, env.input.availableAmount(MATERIAL));
        assertEquals(20, env.energy.energySnapshot().availableEnergy());
        var failed = external.acquire(original, RecipeStage.START);
        failRollback[0] = true;
        assertSame(rollbackFault, assertThrows(IllegalStateException.class,
            () -> ResourceTransactions.rollback(failed.reservations(), failed::rollback)));
        assertEquals(20, env.input.availableAmount(MATERIAL));
        assertEquals(20, env.energy.energySnapshot().availableEnergy());
        assertThrows(IllegalStateException.class, () -> codec.encode(original));
    }

    @Test
    void realTickPostCompletionFailureRejectsSaveAndCannotReplayCommittedResourcesOrEnergy() {
        var env = new Environment();
        env.feed(20, 20);
        var recipe = recipe(0, RecipeRequirements.RESOURCES.requirement(id("io"), new ResourceRequirement(stacks(MATERIAL, 2), stacks(PRODUCT, 5))),
            energy(3, 0, 7));
        var failure = new IllegalStateException("post completion notification failed");
        var post = new ComponentType<RecipeProcess, Box>(id("post"), _ -> new Box(), List.of(new ComponentSystem<>() {
            @Override public ResourceLocation id() { return RecipeProcessPersistenceTest.id("post_system"); }
            @Override public void onPostTick(RecipeProcess process, Box component) { throw failure; }
        }));
        var codec = builder(recipe).component(post, new BoxCodec("post", new ObjectArrayList<>())).build();
        var original = process(env, recipe);
        original.components().create(post, original).set(1);
        assertSame(failure, assertThrows(IllegalStateException.class, original::tick));
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, original.state().lifecycle());
        assertEquals(18, env.input.amount(MATERIAL));
        assertEquals(5, env.output.amount(PRODUCT));
        assertEquals(17, env.energy.storedEnergy());
        assertEquals(7, env.generated.storedEnergy());
        assertThrows(IllegalStateException.class, () -> codec.encode(original));
        assertSame(ProcessResult.SUCCESS, original.tick());
        assertEquals(5, env.output.amount(PRODUCT));
        assertEquals(7, env.generated.storedEnergy());
    }

    private static RecipeProcessNbt codec(MachineRecipe recipe) { return builder(recipe).build(); }
    private static RecipeProcessNbt.Builder builder(MachineRecipe recipe) {
        return RecipeProcessNbt.builder(recipe, "revision-1").resources(KEYS).attributes(new AttributeModifierNbt());
    }
    private static RecipeProcess process(Environment env, MachineRecipe recipe) { return new RecipeProcess(env.machine, recipe, 17); }
    private static MachineRecipe recipe(int duration, RecipeRequirement<?>... requirements) {
        return new MachineRecipe(id("recipe"), duration, ObjectArrayList.of(requirements));
    }
    private static RecipeRequirement<EnergyRequirement> energy(long input, long tick, long output) {
        return RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(input, tick, 0, output, false));
    }
    private static List<GenericStack> stacks(Key key, long amount) { return List.of(new GenericStack(key, amount)); }
    private static ResourceLocation id(String name) { return new ResourceLocation("process_persistence_test", name); }
    private static NBTTagCompound data(NBTTagCompound tag, ComponentType<RecipeProcess, ?> type) {
        var components = tag.getTagList("Components", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < components.tagCount(); i++) {
            var entry = components.getCompoundTagAt(i);
            if (entry.getString("Type").equals(type.id().toString())) { return entry.getCompoundTag("Data"); }
        }
        throw new AssertionError("Saved component is absent: " + type.id());
    }

    private static SlottedResourceBuffer buffer() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(KEY_TYPE, CAPACITY);
        return new SlottedResourceBuffer(4, capacities, NO_OP);
    }

    private record Ports(ResourceStorage input, ResourceStorage output) implements ResourceHatch {
        @Override public boolean isValid() { return input.isValid() && output.isValid(); }
    }
    private record Balances(long material, long alternateInput, long product, long alternateOutput, long energy, long generated) { }
    private record Transaction(List<ResourceReservation> reservations, Runnable cleanup) implements RequirementTransaction {
        @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
        @Override public void commit() { }
        @Override public void rollback() { cleanup.run(); }
    }

    private static final class Environment {
        private final SlottedResourceBuffer input = buffer();
        private final SlottedResourceBuffer output = buffer();
        private final EnergyBuffer energy = new EnergyBuffer(CAPACITY, NO_OP);
        private final EnergyBuffer generated = new EnergyBuffer(CAPACITY, NO_OP);
        private final MachineInstance machine = new MachineInstance();
        private Environment() {
            machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(new Ports(input, output)), List.of(energy), List.of(generated)));
        }
        private void feed(long material, long amount) {
            input.insert(MATERIAL, material, Actionable.MODULATE);
            energy.receiveEnergy(amount, Actionable.MODULATE);
        }
        private Balances balances() {
            return new Balances(input.amount(MATERIAL), input.amount(ALTERNATE), output.amount(PRODUCT), output.amount(ALTERNATE),
                energy.storedEnergy(), generated.storedEnergy());
        }
    }

    /** Deliberately retains codec tags to test the enclosing process codec's defensive copying. */
    private static final class Box {
        private NBTTagCompound tag = new NBTTagCompound();
        private boolean restored;
        private int value() { return tag.getInteger("Value"); }
        private void set(int value) { tag.setInteger("Value", value); restored = true; }
    }

    private static class BoxCodec implements ProcessComponentCodec<Box> {
        private final String name;
        private final List<String> events;
        private BoxCodec(String name, List<String> events) { this.name = name; this.events = events; }
        @Override public NBTTagCompound encode(RecipeProcess process, Box component) { return component.tag; }
        @Override public void restore(RecipeProcess process, Box component, NBTTagCompound data) {
            StrictNbt.requireKeys(data, "Value");
            StrictNbt.requireInt(data, "Value");
            component.tag = data;
            component.restored = true;
            events.add("restore:" + name);
        }
        @Override public void validate(RecipeProcess process, Box component) {
            assertTrue(component.restored);
            events.add("validate:" + name);
        }
    }

    private static final class ExtensionFixture {
        private final MachineRecipe recipe;
        private final List<String> events = new ObjectArrayList<>();
        private final ComponentType<RecipeProcess, Box> first;
        private final ComponentType<RecipeProcess, Box> second;
        private final ComponentType<RecipeProcess, Box> third;
        private final ComponentType<RecipeProcess, Box> future;
        private boolean inspectDependencies;
        private ExtensionFixture(MachineRecipe recipe) {
            this.recipe = recipe;
            second = type("second", List.of());
            third = type("third", List.of());
            first = type("first", List.of(second));
            future = type("future", List.of());
        }
        private ComponentType<RecipeProcess, Box> type(String name, List<ComponentType<RecipeProcess, ?>> dependencies) {
            return new ComponentType<>(id(name), _ -> { events.add("create:" + name); return new Box(); }, List.of(new ComponentSystem<>() {
                @Override public ResourceLocation id() { return RecipeProcessPersistenceTest.id(name + "_system"); }
                @Override public void onTick(RecipeProcess owner, Box component) { events.add("tick:" + name); }
            }), dependencies);
        }
        private RecipeProcessNbt codec() {
            return builder(recipe).component(first, componentCodec("first")).component(second, componentCodec("second"))
                .component(third, componentCodec("third")).component(future, componentCodec("future"))
                .validator(process -> {
                    if (inspectDependencies) {
                        assertTrue(Objects.requireNonNull(process.components().get(first)).restored);
                        assertTrue(Objects.requireNonNull(process.components().get(second)).restored);
                        assertTrue(Objects.requireNonNull(process.components().get(third)).restored);
                    }
                    events.add("validate:host");
                }).build();
        }
        private ProcessComponentCodec<Box> componentCodec(String name) {
            return new BoxCodec(name, events) {
                @Override public void restore(RecipeProcess process, Box component, NBTTagCompound data) {
                    if (inspectDependencies) {
                        assertTrue(process.components().get(first) != null && process.components().get(second) != null && process.components().get(third) != null);
                        if (name.equals("first")) { assertFalse(Objects.requireNonNull(process.components().get(second)).restored); }
                        if (name.equals("second")) { assertTrue(Objects.requireNonNull(process.components().get(third)).restored); }
                    }
                    super.restore(process, component, data);
                }
                @Override public void validate(RecipeProcess process, Box component) {
                    if (inspectDependencies) {
                        assertTrue(Objects.requireNonNull(process.components().get(first)).restored);
                        assertTrue(Objects.requireNonNull(process.components().get(second)).restored);
                        assertTrue(Objects.requireNonNull(process.components().get(third)).restored);
                    }
                    super.validate(process, component);
                }
            };
        }
    }
}
