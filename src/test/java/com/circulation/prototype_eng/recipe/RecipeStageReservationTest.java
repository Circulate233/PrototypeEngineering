package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecipeStageReservationTest {
    private static final KeyType TYPE = new KeyType("stage_resource");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final Runnable NO_OP = () -> {
    };

    private static SlottedResourceBuffer buffer(Runnable onChanged) {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(TYPE, 100);
        return new SlottedResourceBuffer(1, capacities, onChanged);
    }

    private static TrackedReservation reserve(SlottedResourceBuffer storage, boolean insert, String name, List<String> events) {
        var result = insert ? storage.reserveInsert(storage.snapshot(), MATERIAL, 30)
            : storage.reserveExtract(storage.snapshot(), MATERIAL, 30);
        assertEquals(ReservationResult.Status.ACCEPTED, result.status());
        return new TrackedReservation(Objects.requireNonNull(result.reservation()), name, events, null, null);
    }

    private static RecipeRequirement<String> requirement(String name, Supplier<RequirementTransaction> acquire) {
        var location = new ResourceLocation("external_stage", name);
        var system = new RecipeRequirementSystem<String>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                return acquire.get();
            }
        };
        return new RecipeRequirementType<>(location, system).requirement(location, "immutable_payload");
    }

    private static RecipeProcess process(RecipeRequirement<?>... requirements) {
        var recipe = new MachineRecipe(new ResourceLocation("external_stage", "recipe"), 1, ObjectArrayList.of(requirements));
        return new RecipeProcess(new MachineInstance(), recipe, 1);
    }

    @Test
    void laterRequirementCommitFaultCompensatesBusinessBeforeAllResourceTokens() {
        var input = buffer(NO_OP);
        var output = buffer(NO_OP);
        input.insert(MATERIAL, 80, Actionable.MODULATE);
        var events = new ObjectArrayList<String>();
        var primary = new IllegalStateException("later business commit failed");
        int[] business = {0};
        boolean[] applied = {false, false};
        var process = process(
            requirement("a_input", () -> new Transaction(ProcessResult.SUCCESS,
                List.of(reserve(input, false, "input", events)), () -> {
                events.add("business:first");
                business[0] = 2;
                applied[0] = true;
            }, NO_OP, () -> {
                input.context().requireLock();
                output.context().requireLock();
                events.add("undo:first");
                if (applied[0]) {
                    business[0] -= 2;
                }
            })),
            requirement("b_output", () -> new Transaction(ProcessResult.SUCCESS,
                List.of(reserve(output, true, "output", events)), () -> {
                assertEquals(50, input.amount(MATERIAL));
                assertEquals(30, output.amount(MATERIAL));
                assertEquals(2, business[0]);
                events.add("business:second");
                business[0] += 5;
                applied[1] = true;
                throw primary;
            }, NO_OP, () -> {
                input.context().requireLock();
                output.context().requireLock();
                assertEquals(50, input.amount(MATERIAL));
                assertEquals(30, output.amount(MATERIAL));
                events.add("undo:second");
                if (applied[1]) {
                    business[0] -= 5;
                }
            })));

        assertSame(primary, assertThrows(IllegalStateException.class,
            () -> RecipeStageExecutor.execute(process, RecipeStage.START)));
        assertEquals(0, business[0]);
        assertEquals(80, input.amount(MATERIAL));
        assertEquals(80, input.availableAmount(MATERIAL));
        assertEquals(0, output.amount(MATERIAL));
        assertEquals(100, output.insert(MATERIAL, 100, Actionable.SIMULATE));
        assertEquals(List.of("apply:input", "apply:output", "business:first", "business:second",
            "undo:second", "undo:first", "rollback:output", "rollback:input"), events);
    }

    @Test
    void blockedRequirementReleasesItsOwnTokensAndEarlierTokensBeforeFreshRetry() {
        var input = buffer(NO_OP);
        var output = buffer(NO_OP);
        input.insert(MATERIAL, 80, Actionable.MODULATE);
        var events = new ObjectArrayList<String>();
        boolean[] blocked = {true};
        var process = process(
            requirement("a_input", () -> new Transaction(ProcessResult.SUCCESS,
                List.of(reserve(input, false, "input", events)), () -> events.add("business:first"),
                () -> events.add("complete:first"), () -> events.add("undo:first"))),
            requirement("b_output", () -> new Transaction(blocked[0] ? new ProcessResult.Blocked("budget") : ProcessResult.SUCCESS,
                List.of(reserve(output, true, "output", events)), () -> events.add("business:second"),
                () -> events.add("complete:second"), () -> events.add("undo:second"))));

        assertInstanceOf(ProcessResult.Blocked.class, RecipeStageExecutor.execute(process, RecipeStage.START));
        assertEquals(80, input.availableAmount(MATERIAL));
        assertEquals(100, output.insert(MATERIAL, 100, Actionable.SIMULATE));
        assertEquals(List.of("undo:second", "undo:first", "rollback:output", "rollback:input"), events);
        blocked[0] = false;
        events.clear();
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.execute(process, RecipeStage.START));
        assertEquals(50, input.availableAmount(MATERIAL));
        assertEquals(30, output.availableAmount(MATERIAL));
        assertEquals(List.of("apply:input", "apply:output", "business:first", "business:second",
            "complete:first", "complete:second", "complete:input", "complete:output"), events);
    }

    @Test
    void acquisitionExceptionReleasesEveryPreviouslyAcquiredToken() {
        var input = buffer(NO_OP);
        var output = buffer(NO_OP);
        input.insert(MATERIAL, 80, Actionable.MODULATE);
        var events = new ObjectArrayList<String>();
        var primary = new IllegalStateException("cannot acquire later requirement");
        var process = process(
            requirement("a_input", () -> new Transaction(ProcessResult.SUCCESS,
                List.of(reserve(input, false, "input", events)), NO_OP, NO_OP, () -> events.add("undo:first"))),
            requirement("b_output", () -> new Transaction(ProcessResult.SUCCESS,
                List.of(reserve(output, true, "output", events)), NO_OP, NO_OP, () -> events.add("undo:second"))),
            requirement("c_failure", () -> {
                throw primary;
            }));

        assertSame(primary, assertThrows(IllegalStateException.class,
            () -> RecipeStageExecutor.execute(process, RecipeStage.START)));
        assertEquals(80, input.availableAmount(MATERIAL));
        assertEquals(100, output.insert(MATERIAL, 100, Actionable.SIMULATE));
        assertEquals(List.of("undo:second", "undo:first", "rollback:output", "rollback:input"), events);
    }

    @Test
    void invalidatedStorageReturnsLocalizedBlockedAndCompensatesAcquisitions() {
        var input = buffer(NO_OP);
        input.insert(MATERIAL, 80, Actionable.MODULATE);
        var events = new ObjectArrayList<String>();
        var process = process(
            requirement("a_input", () -> new Transaction(ProcessResult.SUCCESS,
                List.of(reserve(input, false, "input", events)), () -> events.add("business"),
                () -> events.add("complete"), () -> events.add("undo"))),
            requirement("b_invalidate", () -> {
                input.context().invalidate();
                return RequirementTransaction.SUCCESS;
            }));

        var result = assertInstanceOf(ProcessResult.Blocked.class, RecipeStageExecutor.execute(process, RecipeStage.START));
        assertEquals(RecipeText.StorageChanged.getTranslationKey(), result.reason());
        assertEquals(List.of("undo", "rollback:input"), events);
    }

    @Test
    void notificationFaultAfterCompletionDoesNotRollbackTerminalTokensOrBusiness() {
        var primary = new IllegalStateException("notification failed");
        boolean[] failNotification = {false};
        var input = buffer(() -> {
            if (failNotification[0]) {
                throw primary;
            }
        });
        input.insert(MATERIAL, 80, Actionable.MODULATE);
        var events = new ObjectArrayList<String>();
        var process = process(requirement("input", () -> new Transaction(ProcessResult.SUCCESS,
            List.of(reserve(input, false, "input", events)), () -> {
            events.add("business");
            failNotification[0] = true;
        }, () -> events.add("complete:business"), () -> events.add("undo"))));

        assertSame(primary, assertThrows(IllegalStateException.class,
            () -> RecipeStageExecutor.execute(process, RecipeStage.START)));
        assertEquals(50, input.availableAmount(MATERIAL));
        assertEquals(List.of("apply:input", "business", "complete:business", "complete:input"), events);
        failNotification[0] = false;
        ResourceTransactions.rollback(List.of(reserve(input, false, "retry", new ObjectArrayList<>())));
    }

    @Test
    void completionFaultDoesNotTryToCompensatePreviouslyCompletedTokens() {
        var input = buffer(NO_OP);
        var output = buffer(NO_OP);
        input.insert(MATERIAL, 80, Actionable.MODULATE);
        var events = new ObjectArrayList<String>();
        var primary = new IllegalStateException("completion contract violated");
        var process = process(
            requirement("a_input", () -> new Transaction(ProcessResult.SUCCESS,
                List.of(reserve(input, false, "input", events)), NO_OP, NO_OP, () -> events.add("undo:first"))),
            requirement("b_output", () -> {
                var token = reserve(output, true, "output", events);
                return new Transaction(ProcessResult.SUCCESS,
                    List.of(new TrackedReservation(token.delegate(), "output", events, null, primary)),
                    NO_OP, NO_OP, () -> events.add("undo:second"));
            }));

        assertSame(primary, assertThrows(IllegalStateException.class,
            () -> RecipeStageExecutor.execute(process, RecipeStage.START)));
        assertEquals(50, input.availableAmount(MATERIAL));
        assertEquals(30, output.availableAmount(MATERIAL));
        assertEquals(List.of("apply:input", "apply:output", "complete:input", "complete:output"), events);
    }

    @Test
    void businessAndEveryTokenCleanupFaultAreRetainedWithoutSkippingOtherCleanup() {
        var input = buffer(NO_OP);
        var output = buffer(NO_OP);
        input.insert(MATERIAL, 80, Actionable.MODULATE);
        var events = new ObjectArrayList<String>();
        var primary = new IllegalStateException("business failed");
        var businessCleanup = new IllegalStateException("business cleanup failed");
        var inputCleanup = new IllegalStateException("input cleanup failed");
        var outputCleanup = new IllegalStateException("output cleanup failed");
        var take = reserve(input, false, "input", events);
        var give = reserve(output, true, "output", events);
        var participants = List.of(
            new TrackedReservation(take.delegate(), "input", events, inputCleanup, null),
            new TrackedReservation(give.delegate(), "output", events, outputCleanup, null));

        assertSame(primary, assertThrows(IllegalStateException.class, () -> ResourceTransactions.commit(participants,
            () -> {
                throw primary;
            }, NO_OP, () -> {
                input.context().requireLock();
                output.context().requireLock();
                events.add("undo");
                throw businessCleanup;
            })));
        assertArrayEquals(new Throwable[]{businessCleanup, outputCleanup, inputCleanup}, primary.getSuppressed());
        assertEquals(80, input.availableAmount(MATERIAL));
        assertEquals(100, output.insert(MATERIAL, 100, Actionable.SIMULATE));
        assertEquals(List.of("apply:input", "apply:output", "undo", "rollback:output", "rollback:input"), events);
    }

    private record Transaction(ProcessResult result, List<? extends ResourceReservation> reservations,
                               Runnable onCommit, Runnable onComplete,
                               Runnable onRollback) implements RequirementTransaction {
        @Override
        public void commit() {
            onCommit.run();
        }

        @Override
        public void complete() {
            onComplete.run();
        }

        @Override
        public void rollback() {
            onRollback.run();
        }
    }

    private record TrackedReservation(ResourceReservation delegate, String name, List<String> events,
                                      @Nullable RuntimeException rollbackFailure,
                                      @Nullable RuntimeException completeFailure) implements ResourceReservation {
        @Override
        public StorageContext context() {
            return delegate.context();
        }

        @Override
        public boolean canApply() {
            return delegate.canApply();
        }

        @Override
        public void apply() {
            events.add("apply:" + name);
            delegate.apply();
        }

        @Override
        public void complete() {
            events.add("complete:" + name);
            delegate.complete();
            if (completeFailure != null) {
                throw completeFailure;
            }
        }

        @Override
        public void rollback() {
            events.add("rollback:" + name);
            delegate.rollback();
            if (rollbackFailure != null) {
                throw rollbackFailure;
            }
        }
    }
}
