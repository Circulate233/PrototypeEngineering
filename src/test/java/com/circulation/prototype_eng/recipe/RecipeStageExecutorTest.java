package com.circulation.prototype_eng.recipe;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeStageExecutorTest {
    private static final ComponentType<RecipeProcess, Ledger> LEDGER =
        new ComponentType<>(id("ledger"), _ -> new Ledger(), ObjectLists.emptyList());

    private static ResourceLocation id(String name) {
        return new ResourceLocation("external_addon", name);
    }

    private static RecipeProcess process(ExternalSystem system) {
        var type = new RecipeRequirementType<>(id("charges"), system);
        var recipe = new MachineRecipe(id("recipe"), 1, ObjectArrayList.of(
            type.requirement(id("first"), new Charge(2)),
            type.requirement(id("second"), new Charge(3)),
            type.requirement(id("third"), new Charge(1))));
        return new RecipeProcess(new MachineInstance(), recipe, 1);
    }

    @Test
    void registeredExternalRequirementsRollbackWholeBlockedStageThenRetryWithoutLeakingState() {
        var system = new ExternalSystem();
        var later = new RecipeRequirementType<>(id("z_later"), system);
        var first = new RecipeRequirementType<>(id("a_first"), system);
        var registry = new RecipeRegistry();
        registry.registerPool(id("pool"), "external.pool");
        // Cross-type order is deliberately reversed. The two requirements inside a_first retain declaration order.
        registry.registerRecipe(id("pool"), new MachineRecipe(id("recipe"), 10, ObjectArrayList.of(
            later.requirement(id("later"), new Charge(1)),
            first.requirement(id("first"), new Charge(2)),
            first.requirement(id("second"), new Charge(3)))));
        registry.freeze();
        var pool = Objects.requireNonNull(registry.get(id("pool")));
        var process = new RecipeProcess(new MachineInstance(), pool.recipes().getFirst(), 7);
        var ledger = process.components().create(LEDGER, process);
        ledger.balance = 4;
        assertInstanceOf(ProcessResult.Blocked.class, RecipeStageExecutor.execute(process, RecipeStage.START));
        assertEquals(4, ledger.balance);
        assertEquals(List.of("acquire:START:first", "acquire:START:second", "rollback:second", "rollback:first"), ledger.events);

        ledger.balance = 12;
        ledger.events.clear();
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.execute(process, RecipeStage.START));
        assertEquals(6, ledger.balance);
        assertEquals(List.of("acquire:START:first", "acquire:START:second", "acquire:START:later",
            "commit:first", "commit:second", "commit:later",
            "complete:first", "complete:second", "complete:later"), ledger.events);
        ledger.events.clear();
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.execute(process, RecipeStage.TICK));
        assertEquals(0, ledger.balance);
        ledger.balance = 6;
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.execute(process, RecipeStage.END));
        assertEquals(0, ledger.balance);
        assertTrue(ledger.events.contains("acquire:TICK:first"));
        assertTrue(ledger.events.contains("acquire:END:first"));

        var other = new RecipeProcess(process.owner(), process.recipe(), 8);
        var otherLedger = other.components().create(LEDGER, other);
        otherLedger.balance = 2;
        assertInstanceOf(ProcessResult.Blocked.class, RecipeStageExecutor.execute(other, RecipeStage.START));
        assertEquals(2, otherLedger.balance);
        assertEquals(0, ledger.balance);
    }

    @Test
    void acquisitionFaultPropagatesAfterReverseCleanupIncludingCleanupFaults() {
        var primary = new IllegalStateException("acquisition failed");
        var cleanup = new IllegalStateException("rollback failed");
        var process = process(new ExternalSystem() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Charge> requirement) {
                if (requirement.id().equals(id("third"))) {
                    throw primary;
                }
                return super.start(process, requirement);
            }
        });
        var ledger = process.components().create(LEDGER, process);
        ledger.balance = 10;
        ledger.rollbackFault = cleanup;
        ledger.faultAt = "second";
        assertSame(primary, assertThrows(IllegalStateException.class, () -> RecipeStageExecutor.execute(process, RecipeStage.START)));
        assertArrayEquals(new Throwable[]{cleanup}, primary.getSuppressed());
        assertEquals(List.of("acquire:START:first", "acquire:START:second", "rollback:second", "rollback:first"), ledger.events);
        assertEquals(10, ledger.balance);
    }

    @Test
    void rollbackFaultIsFatalAndDoesNotSkipTheOtherAcquisitions() {
        var process = process(new ExternalSystem());
        var ledger = process.components().create(LEDGER, process);
        ledger.balance = 3;
        ledger.faultAt = "second";
        ledger.rollbackFault = new IllegalStateException("undo failure");
        assertSame(ledger.rollbackFault, assertThrows(IllegalStateException.class,
            () -> RecipeStageExecutor.execute(process, RecipeStage.START)));
        assertEquals(3, ledger.balance);
        assertEquals(List.of("acquire:START:first", "acquire:START:second", "rollback:second", "rollback:first"), ledger.events);
    }

    @Test
    void commitFaultReversesEarlierBusinessCommitsAndAllAcquisitions() {
        var process = process(new ExternalSystem());
        var ledger = process.components().create(LEDGER, process);
        ledger.balance = 10;
        ledger.faultAt = "second";
        ledger.commitFault = new IllegalStateException("commit failure");
        assertSame(ledger.commitFault, assertThrows(IllegalStateException.class,
            () -> RecipeStageExecutor.execute(process, RecipeStage.START)));
        assertEquals(10, ledger.balance);
        assertEquals(0, ledger.businessBalance);
        assertEquals(List.of("acquire:START:first", "acquire:START:second", "acquire:START:third",
            "commit:first", "commit:second", "rollback:third", "rollback:second", "rollback:first"), ledger.events);
    }

    @Test
    void expectedBusinessFailureRollsBackAndIsReturnedWithoutRunningLaterRequirements() {
        var process = process(new ExternalSystem() {
            @Override
            ProcessResult rejection() {
                return new ProcessResult.Failure("permission_revoked");
            }
        });
        var ledger = process.components().create(LEDGER, process);
        ledger.balance = 3;
        assertInstanceOf(ProcessResult.Failure.class, RecipeStageExecutor.execute(process, RecipeStage.START));
        assertEquals(3, ledger.balance);
        assertEquals(List.of("acquire:START:first", "acquire:START:second", "rollback:second", "rollback:first"), ledger.events);
    }

    private record Charge(int amount) {
    }

    private static final class Ledger {
        final List<String> events = new ObjectArrayList<>();
        int balance;
        int businessBalance;
        String faultAt;
        RuntimeException rollbackFault;
        RuntimeException commitFault;
    }

    /**
     * A consumer-defined requirement: no library resource/energy implementation is involved.
     */
    private static class ExternalSystem implements RecipeRequirementSystem<Charge> {
        @Override
        public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Charge> requirement) {
            return acquire(process, requirement, RecipeStage.START);
        }

        @Override
        public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<Charge> requirement) {
            return acquire(process, requirement, RecipeStage.TICK);
        }

        @Override
        public RequirementTransaction end(RecipeProcess process, RecipeRequirement<Charge> requirement) {
            return acquire(process, requirement, RecipeStage.END);
        }

        ProcessResult rejection() {
            return new ProcessResult.Blocked("waiting_for_budget");
        }

        private RequirementTransaction acquire(RecipeProcess process, RecipeRequirement<Charge> requirement, RecipeStage stage) {
            var ledger = Objects.requireNonNull(process.components().get(LEDGER));
            var name = requirement.id().getPath();
            ledger.events.add("acquire:" + stage + ":" + name);
            int received = Math.min(ledger.balance, requirement.payload().amount());
            ledger.balance -= received;
            ProcessResult result = received == requirement.payload().amount() ? ProcessResult.SUCCESS : rejection();
            return new RequirementTransaction() {
                private boolean applied;

                @Override
                public ProcessResult result() {
                    return result;
                }

                @Override
                public void commit() {
                    ledger.events.add("commit:" + name);
                    ledger.businessBalance += received;
                    applied = true;
                    if (name.equals(ledger.faultAt) && ledger.commitFault != null) {
                        throw ledger.commitFault;
                    }
                }

                @Override
                public void complete() {
                    ledger.events.add("complete:" + name);
                }

                @Override
                public void rollback() {
                    ledger.events.add("rollback:" + name);
                    if (applied) {
                        ledger.businessBalance -= received;
                    }
                    ledger.balance += received;
                    if (name.equals(ledger.faultAt) && ledger.rollbackFault != null) {
                        throw ledger.rollbackFault;
                    }
                }
            };
        }
    }
}
