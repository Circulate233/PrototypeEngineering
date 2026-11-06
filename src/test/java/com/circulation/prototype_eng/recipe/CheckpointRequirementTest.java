package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckpointRequirementTest {
    private static final KeyType TYPE = new KeyType("checkpoint");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final Runnable NO_OP = () -> { };

    @Test
    void zeroTimeRunsOneChildLifecycleForAZeroDurationRecipeWithScopedTickCallbacks() {
        var child = new Child();
        var process = process(0, checkpoint("zero", 0, child.requirement(), true),
            external("zz_checkpoint", (current, _) -> transaction(() -> assertNoTick(current), NO_OP)));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertEquals(0, process.state().progress());
        assertEquals(0, process.state().successfulTicks());
        assertEquals(List.of(new RecipeProcess.Tick(0, 0, 0, 0)), child.ticks);
        assertEquals(List.of("acquire:START", "acquire:TICK", "acquire:END"), child.acquisitions());
        assertEquals(15, child.input.availableAmount(MATERIAL));
        assertEquals(4, child.output.availableAmount(MATERIAL));
        assertNoTick(process);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(3, child.acquisitions().size());
    }

    @Test
    void scaledCheckpointCrossesAJumpedThresholdOnce() {
        var child = new Child();
        var process = process(5, RecipeRequirements.CHECKPOINT.requirement(id("scaled"), new CheckpointRequirement(2, child.requirement())));
        process.attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, 2.5);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(new RecipeProcess.Tick(0, 2.5, 0, 1), child.ticks.getFirst());
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertEquals(3, child.acquisitions().size());
    }

    @Test
    void unscaledCheckpointUsesSuccessfulTicksInsteadOfScaledProgress() {
        var child = new Child();
        var process = process(9, checkpoint("unscaled", 2, child.requirement(), false));
        process.attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, 3);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertTrue(child.acquisitions().isEmpty());
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(List.of(new RecipeProcess.Tick(3, 6, 1, 2)), child.ticks);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(3, child.acquisitions().size());
    }

    @Test
    void checkpointUsesFrozenAdvanceWhenAnEarlierRequirementChangesSpeed() {
        var child = new Child();
        var changing = external("aa_checkpoint", (current, stage) -> {
            if (stage != RecipeStage.TICK) { return RequirementTransaction.SUCCESS; }
            var previous = current.attributes().localSnapshot();
            current.attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, 100);
            return transaction(NO_OP, () -> current.attributes().restoreLocal(previous));
        });
        var process = process(101, changing, checkpoint("frozen", 2, child.requirement(), true));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(1, process.state().progress());
        assertTrue(child.acquisitions().isEmpty());
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(List.of(new RecipeProcess.Tick(1, 101, 1, 2)), child.ticks);
        assertEquals(101, process.state().progress());
    }

    @Test
    void everyBlockedChildStageStopsLaterStagesAndReleasesAllTokensBeforeRetry() {
        for (var rejecting : RecipeStage.values()) {
            var child = new Child();
            child.rejectionStage = rejecting;
            child.reject = true;
            var process = process(0, checkpoint("blocked", 0, child.requirement(), true));
            assertInstanceOf(ProcessResult.Blocked.class, process.tick());
            assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
            assertEquals(0, child.acquisitionEffects);
            assertEquals(0, child.businessEffects);
            assertEquals(20, child.input.availableAmount(MATERIAL));
            assertEquals(100, child.output.insert(MATERIAL, 100, Actionable.SIMULATE));
            assertEquals(rejecting.ordinal() + 1, child.acquisitions().size());
            child.reject = false;
            child.events.clear();
            assertSame(ProcessResult.SUCCESS, process.tick());
            assertEquals(15, child.input.availableAmount(MATERIAL));
            assertEquals(4, child.output.availableAmount(MATERIAL));
            assertEquals(3, child.businessEffects);
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        }
    }

    @Test
    void aBlockedPositiveCheckpointRetriesTheSameCrossingWithoutAdvancingTheParentClock() {
        var child = new Child();
        child.rejectionStage = RecipeStage.TICK;
        child.reject = true;
        var process = process(3, checkpoint("retry", 1, child.requirement(), true));
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(RecipeProcess.Lifecycle.RUNNING, process.state().lifecycle());
        assertEquals(0, process.state().progress());
        assertEquals(0, process.state().successfulTicks());
        child.reject = false;
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(child.ticks.getFirst(), child.ticks.getLast());
        assertEquals(1, process.state().progress());
        assertEquals(1, process.state().successfulTicks());
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(5, child.acquisitions().size());
    }

    @Test
    void childFailureStopsItsLifecycleAndLeavesTheParentTerminalWithoutLeakingReservations() {
        var child = new Child();
        child.rejectionStage = RecipeStage.TICK;
        child.reject = true;
        var failure = new ProcessResult.Failure("child_failure", List.of("detail"));
        child.rejection = failure;
        var process = process(0, checkpoint("failure", 0, child.requirement(), true));
        assertSame(failure, process.tick());
        assertSame(failure, process.tick());
        assertEquals(RecipeProcess.Lifecycle.FAILED, process.state().lifecycle());
        assertEquals(List.of("acquire:START", "acquire:TICK"), child.acquisitions());
        assertEquals(20, child.input.availableAmount(MATERIAL));
        assertEquals(0, child.acquisitionEffects);
        assertEquals(0, child.businessEffects);
    }

    @Test
    void childAcquisitionExceptionRetainsAllBusinessAndTokenCleanupFaults() {
        var child = new Child();
        var primary = new IllegalStateException("child END acquisition failed");
        var startCleanup = new IllegalStateException("START undo failed");
        var tickCleanup = new IllegalStateException("TICK undo failed");
        var tokenCleanup = new IllegalStateException("TICK token undo failed");
        child.acquisitionFailure = primary;
        child.rollbackFailures.put(RecipeStage.START, startCleanup);
        child.rollbackFailures.put(RecipeStage.TICK, tickCleanup);
        child.tokenRollbackFailures.put(RecipeStage.TICK, tokenCleanup);
        var process = process(0, checkpoint("exception", 0, child.requirement(), true));

        assertSame(primary, assertThrows(IllegalStateException.class, process::tick));
        assertArrayEquals(new Throwable[]{tickCleanup, startCleanup, tokenCleanup}, primary.getSuppressed());
        assertEquals(20, child.input.availableAmount(MATERIAL));
        assertEquals(0, child.acquisitionEffects);
        assertEquals(0, child.businessEffects);
        assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
        assertNoTick(process);
        assertTrue(child.events.indexOf("rollback:START") < child.events.indexOf("token:rollback:TICK"));
    }

    @Test
    void childCommitFaultCompensatesEarlierChildApplicationsWithoutCompletingTokens() {
        var child = new Child();
        var primary = new IllegalStateException("child TICK business failed");
        child.commitFailure = primary;
        var process = process(0, checkpoint("commit", 0, child.requirement(), true));
        assertSame(primary, assertThrows(IllegalStateException.class, process::tick));
        assertEquals(20, child.input.availableAmount(MATERIAL));
        assertEquals(0, child.output.amount(MATERIAL));
        assertEquals(0, child.acquisitionEffects);
        assertEquals(0, child.businessEffects);
        assertTrue(child.events.stream().noneMatch(event -> event.startsWith("complete:") || event.startsWith("token:complete:")));
        assertTrue(child.events.indexOf("rollback:START") < child.events.indexOf("token:rollback:END"));
        assertNoTick(process);
    }

    @Test
    void laterParentCommitFailureCanStillCompensateTheEntireChildLifecycle() {
        var child = new Child();
        var primary = new IllegalStateException("later parent business failed");
        var process = process(0, checkpoint("parent", 0, child.requirement(), true),
            external("zz_checkpoint", (_, stage) -> stage == RecipeStage.START
                ? transaction(() -> { throw primary; }, NO_OP) : RequirementTransaction.SUCCESS));
        assertSame(primary, assertThrows(IllegalStateException.class, process::tick));
        assertEquals(0, child.businessEffects);
        assertEquals(20, child.input.availableAmount(MATERIAL));
        assertEquals(0, child.output.amount(MATERIAL));
        assertTrue(child.events.stream().noneMatch(event -> event.startsWith("complete:") || event.startsWith("token:complete:")));
    }

    @Test
    void childCompletionWaitsUntilLaterParentBusinessHasCommitted() {
        var child = new Child();
        boolean[] committed = {false};
        child.verifyCompletion = () -> assertTrue(committed[0]);
        var process = process(0, checkpoint("completion", 0, child.requirement(), true),
            external("zz_checkpoint", (_, stage) -> stage == RecipeStage.START
                ? transaction(() -> committed[0] = true, () -> committed[0] = false) : RequirementTransaction.SUCCESS));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertTrue(child.events.indexOf("commit:END") < child.events.indexOf("complete:START"));
        assertTrue(child.events.indexOf("complete:END") < child.events.indexOf("token:complete:START"));
    }

    @Test
    void zeroWidthChildTickDoesNotTriggerAPositiveNestedCheckpoint() {
        var child = new Child();
        var inner = checkpoint("positive_inner", 1, child.requirement(), true);
        var process = process(0, checkpoint("zero_outer", 0, inner, true));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertTrue(child.acquisitions().isEmpty());
    }

    @Test
    void nestedZeroCheckpointRestoresTheRealParentTickDuringLaterBusinessCallbacks() {
        var child = new Child();
        var realTick = new RecipeProcess.Tick(0, 1, 0, 1);
        var inner = checkpoint("zero_inner", 0, child.requirement(), true);
        var process = process(2, checkpoint("positive_outer", 1, inner, true),
            external("zz_checkpoint", (current, stage) -> {
                if (stage != RecipeStage.TICK) { return RequirementTransaction.SUCCESS; }
                assertEquals(realTick, current.tickContext());
                return transaction(() -> assertEquals(realTick, current.tickContext()), NO_OP);
            }));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(List.of(new RecipeProcess.Tick(0, 0, 0, 0)), child.ticks);
        assertEquals(1, process.state().successfulTicks());
        assertEquals(1, process.state().progress());
        assertNoTick(process);
    }

    private static RecipeRequirement<CheckpointRequirement> checkpoint(String name, int time,
                                                                       RecipeRequirement<?> child, boolean scaled) {
        return RecipeRequirements.CHECKPOINT.requirement(id(name), new CheckpointRequirement(time, child, scaled));
    }

    private static RecipeRequirement<String> external(String namespace,
                                                       BiFunction<RecipeProcess, RecipeStage, RequirementTransaction> acquire) {
        var location = new ResourceLocation(namespace, "requirement");
        var system = new RecipeRequirementSystem<String>() {
            @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                return acquire.apply(process, RecipeStage.START);
            }
            @Override public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<String> requirement) {
                return acquire.apply(process, RecipeStage.TICK);
            }
            @Override public RequirementTransaction end(RecipeProcess process, RecipeRequirement<String> requirement) {
                return acquire.apply(process, RecipeStage.END);
            }
        };
        return new RecipeRequirementType<>(location, system).requirement(location, "immutable_payload");
    }

    private static RequirementTransaction transaction(Runnable commit, Runnable rollback) {
        return new RequirementTransaction() {
            @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
            @Override public void commit() { commit.run(); }
            @Override public void rollback() { rollback.run(); }
        };
    }

    private static RecipeProcess process(int duration, RecipeRequirement<?>... requirements) {
        return new RecipeProcess(new MachineInstance(),
            new MachineRecipe(id("recipe"), duration, ObjectArrayList.of(requirements)), 17);
    }

    private static void assertNoTick(RecipeProcess process) {
        assertThrows(NullPointerException.class, process::tickContext);
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("checkpoint_test", name);
    }

    private static SlottedResourceBuffer buffer() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(TYPE, 100);
        return new SlottedResourceBuffer(1, capacities, NO_OP);
    }

    private static final class Child {
        private final SlottedResourceBuffer input = buffer();
        private final SlottedResourceBuffer output = buffer();
        private final List<String> events = new ObjectArrayList<>();
        private final List<RecipeProcess.Tick> ticks = new ObjectArrayList<>();
        private final Object2ObjectOpenHashMap<RecipeStage, RuntimeException> rollbackFailures = new Object2ObjectOpenHashMap<>();
        private final Object2ObjectOpenHashMap<RecipeStage, RuntimeException> tokenRollbackFailures = new Object2ObjectOpenHashMap<>();
        private @Nullable RecipeStage rejectionStage;
        private boolean reject;
        private ProcessResult rejection = new ProcessResult.Blocked("child_blocked");
        private @Nullable RuntimeException acquisitionFailure;
        private @Nullable RuntimeException commitFailure;
        private Runnable verifyCompletion = NO_OP;
        private int acquisitionEffects;
        private int businessEffects;

        private Child() {
            input.insert(MATERIAL, 20, Actionable.MODULATE);
        }

        private List<String> acquisitions() {
            return events.stream().filter(event -> event.startsWith("acquire:")).toList();
        }

        private RecipeRequirement<String> requirement() {
            return external("child_checkpoint", this::acquire);
        }

        private RequirementTransaction acquire(RecipeProcess process, RecipeStage stage) {
            events.add("acquire:" + stage);
            var tick = stage == RecipeStage.TICK ? process.tickContext() : null;
            if (tick != null) { ticks.add(tick); }
            if (stage == RecipeStage.END && acquisitionFailure != null) {
                throw acquisitionFailure;
            }
            var result = stage == RecipeStage.END ? output.reserveInsert(output.snapshot(), MATERIAL, 4)
                : input.reserveExtract(input.snapshot(), MATERIAL, stage == RecipeStage.START ? 2 : 3);
            assertEquals(ReservationResult.Status.ACCEPTED, result.status());
            var token = new TracedToken(Objects.requireNonNull(result.reservation()), stage, events,
                verifyCompletion, tokenRollbackFailures.get(stage));
            acquisitionEffects++;
            return new RequirementTransaction() {
                private boolean committed;
                @Override public ProcessResult result() { return reject && stage == rejectionStage ? rejection : ProcessResult.SUCCESS; }
                @Override public List<? extends ResourceReservation> reservations() { return List.of(token); }
                @Override public void commit() {
                    checkContext();
                    events.add("commit:" + stage);
                    committed = true;
                    businessEffects++;
                    if (stage == RecipeStage.TICK && commitFailure != null) {
                        throw commitFailure;
                    }
                }
                @Override public void complete() {
                    checkContext();
                    verifyCompletion.run();
                    events.add("complete:" + stage);
                }
                @Override public void rollback() {
                    checkContext();
                    token.context().requireLock();
                    events.add("rollback:" + stage);
                    acquisitionEffects--;
                    if (committed) { businessEffects--; }
                    var failure = rollbackFailures.get(stage);
                    if (failure != null) { throw failure; }
                }
                private void checkContext() {
                    if (tick != null) { assertEquals(tick, process.tickContext()); }
                }
            };
        }
    }

    private record TracedToken(ResourceReservation delegate, RecipeStage stage, List<String> events,
                               Runnable verifyCompletion, @Nullable RuntimeException rollbackFailure) implements ResourceReservation {
        @Override public StorageContext context() { return delegate.context(); }
        @Override public boolean canApply() { return delegate.canApply(); }
        @Override public void apply() {
            events.add("token:apply:" + stage);
            delegate.apply();
        }
        @Override public void complete() {
            verifyCompletion.run();
            events.add("token:complete:" + stage);
            delegate.complete();
        }
        @Override public void rollback() {
            events.add("token:rollback:" + stage);
            delegate.rollback();
            if (rollbackFailure != null) { throw rollbackFailure; }
        }
    }
}
