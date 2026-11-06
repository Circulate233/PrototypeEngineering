package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompletionFailureTest {
    private static final KeyType TYPE = new KeyType("completion_failure");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final Runnable NO_OP = () -> { };

    @Test
    void businessCompletionAndTokenFaultsStillFinishEveryTokenAndPublishEveryDomain() {
        var events = new ObjectArrayList<String>();
        var primary = new IllegalStateException("business completion failed");
        var firstFault = new AssertionError("first token completion failed");
        var secondFault = new IllegalStateException("second token completion failed");
        var notificationFault = new IllegalStateException("first notification failed");
        boolean[] armed = {false};
        var first = buffer(() -> {
            if (armed[0]) {
                events.add("notify:first");
                throw notificationFault;
            }
        });
        var second = buffer(() -> { if (armed[0]) { events.add("notify:second"); } });
        var third = buffer(() -> { if (armed[0]) { events.add("notify:third"); } });
        for (var storage : List.of(first, second, third)) {
            storage.insert(MATERIAL, 80, Actionable.MODULATE);
        }
        var tokens = List.of(reserve(first, false, 10, "first", events, () -> { throw firstFault; }),
            reserve(second, false, 10, "second", events, () -> { throw secondFault; }),
            reserve(third, false, 10, "third", events, NO_OP));
        armed[0] = true;

        assertSame(primary, assertThrows(IllegalStateException.class, () -> ResourceTransactions.commit(tokens,
            () -> events.add("business:commit"), () -> {
                events.add("business:complete");
                throw primary;
            }, () -> events.add("business:rollback"))));
        assertArrayEquals(new Throwable[]{firstFault, secondFault, notificationFault}, primary.getSuppressed());
        assertEquals(List.of("apply:first", "apply:second", "apply:third", "business:commit", "business:complete",
            "complete:first", "complete:second", "complete:third", "notify:first", "notify:second", "notify:third"), events);
        for (var storage : List.of(first, second, third)) {
            assertEquals(70, storage.availableAmount(MATERIAL));
            assertEquals(30, storage.insert(MATERIAL, 30, Actionable.SIMULATE));
        }
    }

    @Test
    void endCompletionFaultsFinishLaterCallbacksAndCannotReplayACompletedProcess() {
        var events = new ObjectArrayList<String>();
        var input = buffer(NO_OP);
        var output = buffer(NO_OP);
        input.insert(MATERIAL, 80, Actionable.MODULATE);
        var primary = new IllegalStateException("first callback failed");
        var secondFault = new AssertionError("second callback failed");
        var firstId = id("first");
        var secondId = id("second");
        var thirdId = id("third");
        var process = process(0,
            requirement(firstId, (current, stage) -> stage != RecipeStage.END ? RequirementTransaction.SUCCESS
                : transaction(List.of(reserve(input, false, 5, "input", events, NO_OP)), NO_OP, () -> {
                    assertEquals(new RequirementPath(null, firstId, firstId), current.requirementPath());
                    events.add("complete:first");
                    throw primary;
                })),
            requirement(secondId, (current, stage) -> stage != RecipeStage.END ? RequirementTransaction.SUCCESS
                : transaction(List.of(reserve(output, true, 7, "output", events, NO_OP)), NO_OP, () -> {
                    assertEquals(new RequirementPath(null, secondId, secondId), current.requirementPath());
                    events.add("complete:second");
                    throw secondFault;
                })),
            requirement(thirdId, (current, stage) -> stage != RecipeStage.END ? RequirementTransaction.SUCCESS
                : transaction(List.of(), NO_OP, () -> {
                    assertEquals(new RequirementPath(null, thirdId, thirdId), current.requirementPath());
                    events.add("complete:third");
                })));

        assertSame(primary, assertThrows(IllegalStateException.class, process::tick));
        assertArrayEquals(new Throwable[]{secondFault}, primary.getSuppressed());
        assertEquals(List.of("apply:input", "apply:output", "complete:first", "complete:second", "complete:third",
            "complete:input", "complete:output"), events);
        assertEquals(75, input.availableAmount(MATERIAL));
        assertEquals(25, input.insert(MATERIAL, 25, Actionable.SIMULATE));
        assertEquals(7, output.availableAmount(MATERIAL));
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertThrows(NullPointerException.class, process::requirementPath);
        var afterFailure = List.copyOf(events);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(afterFailure, events);
    }

    @Test
    void checkpointCompletionFaultsFinishAllChildStagesAndRestoreTheirScopesWithoutReplaying() {
        var events = new ObjectArrayList<String>();
        var input = buffer(NO_OP);
        input.insert(MATERIAL, 20, Actionable.MODULATE);
        var primary = new IllegalStateException("child START completion failed");
        var tickFault = new AssertionError("child TICK completion failed");
        var checkpointId = id("checkpoint");
        var childId = id("child");
        var checkpointPath = new RequirementPath(null, RecipeRequirements.CHECKPOINT.id(), checkpointId);
        var childPath = new RequirementPath(checkpointPath, childId, childId);
        var child = requirement(childId, (current, stage) -> {
            assertChildScope(current, childPath, stage);
            events.add("acquire:" + stage);
            return transaction(List.of(reserve(input, false, 1, stage.toString(), events, NO_OP)),
                () -> assertChildScope(current, childPath, stage), () -> {
                    assertChildScope(current, childPath, stage);
                    events.add("complete:child:" + stage);
                    if (stage == RecipeStage.START) { throw primary; }
                    if (stage == RecipeStage.TICK) { throw tickFault; }
                });
        });
        var laterId = new ResourceLocation("zz_completion", "after_checkpoint");
        var later = requirement(laterId, (current, stage) -> stage != RecipeStage.START ? RequirementTransaction.SUCCESS
            : transaction(List.of(), NO_OP, () -> {
                assertEquals(new RequirementPath(null, laterId, laterId), current.requirementPath());
                assertThrows(NullPointerException.class, current::tickContext);
                events.add("complete:later");
            }));
        var process = process(1, RecipeRequirements.CHECKPOINT.requirement(checkpointId, new CheckpointRequirement(0, child)), later);

        assertSame(primary, assertThrows(IllegalStateException.class, process::tick));
        assertArrayEquals(new Throwable[]{tickFault}, primary.getSuppressed());
        assertEquals(List.of("acquire:START", "acquire:TICK", "acquire:END", "apply:START", "apply:TICK", "apply:END",
            "complete:child:START", "complete:child:TICK", "complete:child:END", "complete:later",
            "complete:START", "complete:TICK", "complete:END"), events);
        assertEquals(17, input.availableAmount(MATERIAL));
        assertEquals(83, input.insert(MATERIAL, 83, Actionable.SIMULATE));
        assertEquals(RecipeProcess.Lifecycle.RUNNING, process.state().lifecycle());
        assertThrows(NullPointerException.class, process::requirementPath);
        assertThrows(NullPointerException.class, process::tickContext);
        var afterFailure = List.copyOf(events);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertEquals(afterFailure, events);
        assertEquals(17, input.availableAmount(MATERIAL));
    }

    private static void assertChildScope(RecipeProcess process, RequirementPath expected, RecipeStage stage) {
        assertEquals(expected, process.requirementPath());
        if (stage == RecipeStage.TICK) {
            assertEquals(new RecipeProcess.Tick(0, 0, 0, 0), process.tickContext());
        } else {
            assertThrows(NullPointerException.class, process::tickContext);
        }
    }

    private static RequirementTransaction transaction(List<? extends ResourceReservation> tokens, Runnable commit, Runnable complete) {
        return new RequirementTransaction() {
            @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
            @Override public List<? extends ResourceReservation> reservations() { return tokens; }
            @Override public void commit() { commit.run(); }
            @Override public void complete() { complete.run(); }
            @Override public void rollback() { throw new AssertionError("A terminal transaction cannot roll back"); }
        };
    }

    private static RecipeRequirement<String> requirement(ResourceLocation id,
        BiFunction<RecipeProcess, RecipeStage, RequirementTransaction> acquisition) {
        var system = new RecipeRequirementSystem<String>() {
            @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                return acquisition.apply(process, RecipeStage.START);
            }
            @Override public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<String> requirement) {
                return acquisition.apply(process, RecipeStage.TICK);
            }
            @Override public RequirementTransaction end(RecipeProcess process, RecipeRequirement<String> requirement) {
                return acquisition.apply(process, RecipeStage.END);
            }
        };
        return new RecipeRequirementType<>(id, system).requirement(id, "payload");
    }

    private static RecipeProcess process(int duration, RecipeRequirement<?>... requirements) {
        return new RecipeProcess(new MachineInstance(), new MachineRecipe(id("recipe"), duration, ObjectArrayList.of(requirements)), 1);
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("completion_test", name);
    }

    private static SlottedResourceBuffer buffer(Runnable notification) {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(TYPE, 100);
        return new SlottedResourceBuffer(1, capacities, notification);
    }

    private static ResourceReservation reserve(SlottedResourceBuffer storage, boolean insert, long amount, String name,
                                               List<String> events, Runnable afterComplete) {
        var reservation = insert ? storage.reserveInsert(storage.snapshot(), MATERIAL, amount)
            : storage.reserveExtract(storage.snapshot(), MATERIAL, amount);
        assertEquals(ReservationResult.Status.ACCEPTED, reservation.status());
        return new TrackedReservation(Objects.requireNonNull(reservation.reservation()), name, events, afterComplete);
    }

    private record TrackedReservation(ResourceReservation delegate, String name, List<String> events,
                                      Runnable afterComplete) implements ResourceReservation {
        @Override public StorageContext context() { return delegate.context(); }
        @Override public boolean canApply() { return delegate.canApply(); }
        @Override public void apply() { events.add("apply:" + name); delegate.apply(); }
        @Override public void complete() { events.add("complete:" + name); delegate.complete(); afterComplete.run(); }
        @Override public void rollback() { events.add("rollback:" + name); delegate.rollback(); }
    }
}
