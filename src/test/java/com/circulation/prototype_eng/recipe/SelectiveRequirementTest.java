package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
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
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveContext;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveModifierDescriptor;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveState;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectiveRequirementTest {
    private static final KeyType TYPE = new KeyType("selective");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final ResourceLocation SCORE = id("score");
    private static final ResourceLocation OTHER = id("other");
    private static final Runnable NO_OP = () -> { };

    @Test
    void rejectedBlockedAndFailedCandidatesRollbackBeforeTheFirstSuccessAndRemainUnselected() {
        var env = new Environment();
        var blocked = env.candidate("blocked", 4, 0, 0);
        var failed = env.candidate("failed", 6, 0, 0);
        var chosen = env.candidate("chosen", 2, 0, 0);
        var unused = env.candidate("unused", 1, 0, 0);
        blocked.results.put(RecipeStage.START, new ProcessResult.Blocked("missing"));
        failed.results.put(RecipeStage.START, new ProcessResult.Failure("rejected"));
        var selection = selector("choice", List.of(blocked.requirement(), failed.requirement(), chosen.requirement(), unused.requirement()),
            List.of(new SelectiveModifierDescriptor(id("score_modifier"), context -> {
                assertEquals(2, context.candidateIndex());
                assertSame(context.process().owner(), context.machine());
                assertEquals(context.selectionPath(), context.process().requirementPath());
                assertEquals(context.selectionPath().toString(), context.selectionId());
                context.putProcessAttributeModifier(SCORE, add("bonus", 4));
            })));
        var process = process(2, selection);
        assertSame(ProcessResult.SUCCESS, process.tick());
        var decision = Objects.requireNonNull(state(process).selection(selection.path(null)));
        assertEquals(2, decision.index());
        assertTrue(decision.committed());
        assertFalse(decision.completed());
        assertEquals(18, env.input.availableAmount(MATERIAL));
        assertEquals(0, blocked.acquisitionEffects);
        assertEquals(0, failed.acquisitionEffects);
        assertEquals(0, blocked.businessEffects);
        assertEquals(0, failed.businessEffects);
        assertTrue(unused.paths.isEmpty());
        assertTrue(env.events.indexOf("blocked:rollback:START") < env.events.indexOf("failed:acquire:START"));
        assertTrue(env.events.indexOf("failed:rollback:START") < env.events.indexOf("chosen:acquire:START"));
        assertEquals(5, process.attributes().value(SCORE));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertTrue(decision.completed());
        assertEquals(1, process.attributes().value(SCORE));
        assertTrue(blocked.paths.size() == 1 && failed.paths.size() == 1);
    }

    @Test
    void noAvailableCandidateDisablesTheSelectionForTheCommittedProcess() {
        var env = new Environment();
        var first = env.candidate("first", 2, 0, 0);
        var second = env.candidate("second", 3, 0, 0);
        first.results.put(RecipeStage.START, new ProcessResult.Blocked("first"));
        second.results.put(RecipeStage.START, new ProcessResult.Failure("second"));
        boolean[] modified = {false};
        var selection = selector("disabled", List.of(first.requirement(), second.requirement()),
            List.of(new SelectiveModifierDescriptor(id("unused_modifier"), _ -> modified[0] = true)));
        var process = process(3, selection);
        assertSame(ProcessResult.SUCCESS, process.tick());
        var decision = Objects.requireNonNull(state(process).selection(selection.path(null)));
        assertEquals(-1, decision.index());
        assertTrue(decision.committed());
        first.results.clear();
        second.results.clear();
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertTrue(decision.completed());
        assertFalse(modified[0]);
        assertEquals(20, env.input.availableAmount(MATERIAL));
        assertEquals(1, first.paths.size());
        assertEquals(1, second.paths.size());
    }

    @Test
    void aDisabledProvisionalChoiceIsDiscardedWhenAnotherRequirementBlocksTheStage() {
        var env = new Environment();
        var candidate = env.candidate("candidate", 2, 0, 0);
        candidate.results.put(RecipeStage.START, new ProcessResult.Blocked("candidate"));
        boolean[] blocked = {true};
        var selection = selector("provisional_disabled", List.of(candidate.requirement()));
        var process = process(1, selection, external("zz_selective", (_, stage) ->
            stage == RecipeStage.START && blocked[0] ? new SimpleTransaction(new ProcessResult.Blocked("later"), NO_OP, NO_OP)
                : RequirementTransaction.SUCCESS));
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertNull(state(process).selection(selection.path(null)));
        candidate.results.clear();
        blocked[0] = false;
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(0, Objects.requireNonNull(state(process).selection(selection.path(null))).index());
        assertEquals(18, env.input.availableAmount(MATERIAL));
    }

    @Test
    void parentCommitFaultDiscardsTheChoiceAndRetryAcquiresItsInputsAgain() {
        var env = new Environment();
        var candidate = env.candidate("candidate", 2, 0, 0);
        boolean[] fail = {true};
        var primary = new IllegalStateException("later business failed");
        var selection = selector("retry", List.of(candidate.requirement()), List.of(boost("bonus", 4)));
        var earlier = external("aa_selective", (current, stage) -> stage == RecipeStage.START
            ? new SimpleTransaction(ProcessResult.SUCCESS,
                () -> current.attributes().setBase(OTHER, current.attributes().value(OTHER) + 5),
                () -> current.attributes().setBase(OTHER, current.attributes().value(OTHER) - 5))
            : RequirementTransaction.SUCCESS);
        var later = external("zz_selective", (_, stage) -> stage == RecipeStage.START
            ? new SimpleTransaction(ProcessResult.SUCCESS, () -> { if (fail[0]) { throw primary; } }, NO_OP)
            : RequirementTransaction.SUCCESS);
        var process = process(2, earlier, selection, later);
        process.attributes().setBase(OTHER, 10);
        process.attributes().addModifier(SCORE, add("foreign", 7));
        assertSame(primary, assertThrows(IllegalStateException.class, process::tick));
        assertEquals(10, process.attributes().value(OTHER));
        assertEquals(8, process.attributes().value(SCORE));
        assertEquals(20, env.input.availableAmount(MATERIAL));
        assertNull(state(process).selection(selection.path(null)));
        assertEquals(0, candidate.businessEffects);
        fail[0] = false;
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(18, env.input.availableAmount(MATERIAL));
        assertEquals(2, candidate.startAcquisitions);
        assertEquals(12, process.attributes().value(SCORE));
        assertEquals(15, process.attributes().value(OTHER));
    }

    @Test
    void resultAccessorFaultCleansReturnedChildBusinessAndTokensAndRetainsCleanupFaults() {
        var env = new Environment();
        var candidate = env.candidate("faulty", 2, 0, 0);
        var primary = new IllegalStateException("child result fault");
        var businessCleanup = new IllegalStateException("child rollback fault");
        var tokenCleanup = new IllegalStateException("token rollback fault");
        candidate.resultFault = primary;
        candidate.rollbackFault = businessCleanup;
        candidate.tokenRollbackFault = tokenCleanup;
        var selection = selector("accessor", List.of(candidate.requirement()));
        var process = process(1, selection);
        assertSame(primary, assertThrows(IllegalStateException.class, process::tick));
        assertArrayEquals(new Throwable[]{businessCleanup, tokenCleanup}, primary.getSuppressed());
        assertEquals(20, env.input.availableAmount(MATERIAL));
        assertEquals(0, candidate.acquisitionEffects);
        assertNull(state(process).selection(selection.path(null)));
    }

    @Test
    void partialExternalModifierApplicationRemovesOnlyOwnedEffectsAndStillRollsBackTheChild() {
        var env = new Environment();
        var candidate = env.candidate("candidate", 2, 0, 0);
        var primary = new IllegalStateException("second modifier failed");
        var childCleanup = new IllegalStateException("child rollback failed");
        candidate.rollbackFault = childCleanup;
        boolean[] laterApplied = {false};
        SelectiveContext[] saved = new SelectiveContext[1];
        var selection = selector("partial", List.of(candidate.requirement()), List.of(
            boost("first", 2),
            new SelectiveModifierDescriptor(id("second"), context -> {
                saved[0] = context;
                assertEquals(id("second"), context.modifierId());
                assertEquals(1, context.modifierOrdinal());
                context.putProcessAttributeModifier(OTHER, add("own", 3));
                throw primary;
            }),
            new SelectiveModifierDescriptor(id("third"), _ -> laterApplied[0] = true)));
        var process = process(1, selection);
        process.attributes().setBase(OTHER, 10);
        process.attributes().addModifier(SCORE, add("foreign", 7));
        assertSame(primary, assertThrows(IllegalStateException.class, process::tick));
        assertArrayEquals(new Throwable[]{childCleanup}, primary.getSuppressed());
        assertEquals(8, process.attributes().value(SCORE));
        assertEquals(10, process.attributes().value(OTHER));
        assertEquals(20, env.input.availableAmount(MATERIAL));
        assertEquals(0, candidate.businessEffects);
        assertEquals(0, candidate.acquisitionEffects);
        assertNull(state(process).selection(selection.path(null)));
        assertFalse(laterApplied[0]);
        var expired = Objects.requireNonNull(saved[0]);
        assertThrows(IllegalStateException.class, () -> expired.putProcessAttributeModifier(SCORE, add("late", 99)));
    }

    @Test
    void repeatedTrackedPutReplacesItsOwnModifierAndEndRemovesItOnlyOnce() {
        var env = new Environment();
        var candidate = env.candidate("candidate", 2, 0, 0);
        var selection = selector("replace", List.of(candidate.requirement()), List.of(
            new SelectiveModifierDescriptor(id("replace_modifier"), context -> {
                context.putProcessAttributeModifier(SCORE, add("same", 2));
                context.putProcessAttributeModifier(SCORE, add("same", 5));
            })));
        var process = process(2, selection);
        process.attributes().addModifier(SCORE, add("foreign", 7));
        assertSame(ProcessResult.SUCCESS, process.tick());
        var decision = Objects.requireNonNull(state(process).selection(selection.path(null)));
        assertEquals(1, decision.modifiers().size());
        assertEquals(13, process.attributes().value(SCORE));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertTrue(decision.modifiers().isEmpty());
        assertEquals(8, process.attributes().value(SCORE));
    }

    @Test
    void unknownExistingModifierIdIsRejectedWithoutDeletingTheForeignModifier() {
        var process = process(1);
        process.attributes().addModifier(SCORE, add("foreign", 7));
        var decision = SelectiveState.getOrCreate(process).begin(
            new RequirementPath(null, RecipeRequirements.SELECTIVE.id(), id("guard")), 0);
        assertThrows(IllegalStateException.class, () -> decision.putModifier(SCORE, add("foreign", 9)));
        decision.cleanupModifiers();
        assertEquals(8, process.attributes().value(SCORE));
        assertTrue(decision.modifiers().isEmpty());
    }

    @Test
    void blockedEndKeepsTheCommittedModifierAndRetriesOnlyTheChosenEnd() {
        var env = new Environment();
        var candidate = env.candidate("candidate", 2, 0, 4);
        candidate.results.put(RecipeStage.END, new ProcessResult.Blocked("output"));
        var selection = selector("blocked_end", List.of(candidate.requirement()), List.of(boost("bonus", 4)));
        var process = process(1, selection);
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(5, process.attributes().value(SCORE));
        assertEquals(18, env.input.availableAmount(MATERIAL));
        assertEquals(0, env.output.availableAmount(MATERIAL));
        assertEquals(1, process.state().successfulTicks());
        candidate.results.clear();
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(4, env.output.availableAmount(MATERIAL));
        assertEquals(18, env.input.availableAmount(MATERIAL));
        assertEquals(1, candidate.startAcquisitions);
        assertEquals(1, process.state().successfulTicks());
        assertEquals(1, process.attributes().value(SCORE));
    }

    @Test
    void lateEndCommitFaultRefundsOutputsWithoutRemovingTheCommittedSelectionModifier() {
        var env = new Environment();
        var candidate = env.candidate("candidate", 2, 0, 4);
        var primary = new IllegalStateException("later END failed");
        boolean[] fail = {true};
        var selection = selector("failed_end", List.of(candidate.requirement()), List.of(boost("bonus", 4)));
        var process = process(1, selection, external("zz_selective", (_, stage) -> stage == RecipeStage.END
            ? new SimpleTransaction(ProcessResult.SUCCESS, () -> { if (fail[0]) { throw primary; } }, NO_OP)
            : RequirementTransaction.SUCCESS));
        assertSame(primary, assertThrows(IllegalStateException.class, process::tick));
        assertEquals(5, process.attributes().value(SCORE));
        assertEquals(18, env.input.availableAmount(MATERIAL));
        assertEquals(0, env.output.availableAmount(MATERIAL));
        assertEquals(RecipeProcess.Lifecycle.RUNNING, process.state().lifecycle());
        fail[0] = false;
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(4, env.output.availableAmount(MATERIAL));
        assertEquals(1, process.attributes().value(SCORE));
    }

    @Test
    void childEndCompletionFaultStillCleansOwnedModifiersAndCannotReplayEnd() {
        var env = new Environment();
        var candidate = env.candidate("candidate", 2, 0, 0);
        var primary = new IllegalStateException("child completion notification failed");
        candidate.completeFault = primary;
        var selection = selector("terminal_cleanup", List.of(candidate.requirement()), List.of(boost("bonus", 4)));
        var process = process(1, selection);
        process.attributes().addModifier(SCORE, add("foreign", 7));
        process.attributes().setBase(OTHER, 10);
        assertSame(primary, assertThrows(IllegalStateException.class, process::tick));
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertEquals(8, process.attributes().value(SCORE));
        assertEquals(10, process.attributes().value(OTHER));
        assertTrue(Objects.requireNonNull(state(process).selection(selection.path(null))).completed());
        int visits = candidate.paths.size();
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(visits, candidate.paths.size());
        assertEquals(18, env.input.availableAmount(MATERIAL));
    }

    @Test
    void checkpointCanUseAProvisionalChoiceAndParentFailureForcesFreshInputAcquisition() {
        var env = new Environment();
        var candidate = env.candidate("candidate", 2, 3, 4);
        var selection = selector("inside", List.of(candidate.requirement()), List.of(boost("bonus", 4)));
        var checkpoint = RecipeRequirements.CHECKPOINT.requirement(id("checkpoint"), new CheckpointRequirement(0, selection));
        var primary = new IllegalStateException("parent START failed");
        boolean[] fail = {true};
        var process = process(0, checkpoint, external("zz_selective", (_, stage) -> stage == RecipeStage.START
            ? new SimpleTransaction(ProcessResult.SUCCESS, () -> { if (fail[0]) { throw primary; } }, NO_OP)
            : RequirementTransaction.SUCCESS));
        var path = selection.path(checkpoint.path(null));
        assertSame(primary, assertThrows(IllegalStateException.class, process::tick));
        assertEquals(List.of(false, false), candidate.committedAtAcquisition);
        assertNull(state(process).selection(path));
        assertEquals(20, env.input.availableAmount(MATERIAL));
        assertEquals(0, env.output.availableAmount(MATERIAL));
        assertEquals(1, process.attributes().value(SCORE));
        fail[0] = false;
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(2, candidate.startAcquisitions);
        assertEquals(15, env.input.availableAmount(MATERIAL));
        assertEquals(4, env.output.availableAmount(MATERIAL));
        assertEquals(0, process.state().successfulTicks());
        assertEquals(List.of(false, false, false, false), candidate.committedAtAcquisition);
        assertTrue(Objects.requireNonNull(state(process).selection(path)).completed());
        assertEquals(1, process.attributes().value(SCORE));
    }

    @Test
    void reusingTheSameInnerSelectorUnderDifferentWrappersKeepsStateAndModifierNamesSeparate() {
        var env = new Environment();
        var candidate = env.candidate("shared_leaf", 2, 0, 0);
        var inner = selector("inner", List.of(candidate.requirement()), List.of(boost("shared_modifier", 2)));
        var first = selector("first_outer", List.of(inner));
        var second = selector("second_outer", List.of(inner));
        var process = process(2, first, second);
        assertSame(ProcessResult.SUCCESS, process.tick());
        var firstPath = inner.path(first.path(null));
        var secondPath = inner.path(second.path(null));
        var firstDecision = Objects.requireNonNull(state(process).selection(firstPath));
        var secondDecision = Objects.requireNonNull(state(process).selection(secondPath));
        assertNotEquals(firstPath, secondPath);
        assertNotEquals(firstDecision.modifiers().getFirst().id(), secondDecision.modifiers().getFirst().id());
        assertEquals(2, candidate.startAcquisitions);
        assertEquals(16, env.input.availableAmount(MATERIAL));
        assertEquals(5, process.attributes().value(SCORE));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertTrue(firstDecision.completed() && secondDecision.completed());
        assertEquals(1, process.attributes().value(SCORE));
    }

    @Test
    void copiedDefinitionsRejectAmbiguousCandidateAndModifierIdentities() {
        var env = new Environment();
        var candidate = env.candidate("candidate", 2, 0, 0).requirement();
        var modifier = boost("modifier", 2);
        assertThrows(IllegalArgumentException.class, () -> new SelectiveRequirement(List.of(candidate, candidate)));
        assertThrows(IllegalArgumentException.class, () -> new SelectiveRequirement(List.of(candidate), List.of(modifier, modifier)));
        var candidates = new ObjectArrayList<RecipeRequirement<?>>(List.of(candidate));
        var modifiers = new ObjectArrayList<>(List.of(modifier));
        var payload = new SelectiveRequirement(candidates, modifiers);
        candidates.clear();
        modifiers.clear();
        var process = process(2, RecipeRequirements.SELECTIVE.requirement(id("copied"), payload));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(18, env.input.availableAmount(MATERIAL));
        assertEquals(3, process.attributes().value(SCORE));
    }

    private static SelectiveModifierDescriptor boost(String name, double amount) {
        return new SelectiveModifierDescriptor(id(name), context -> context.putProcessAttributeModifier(SCORE, add("bonus", amount)));
    }

    private static NumericAttributeModifier add(String name, double amount) {
        return new NumericAttributeModifier(name, amount, AttributeModifier.Operation.ADDITION);
    }

    private static RecipeRequirement<SelectiveRequirement> selector(String name, List<RecipeRequirement<?>> candidates,
                                                                   List<SelectiveModifierDescriptor> modifiers) {
        return RecipeRequirements.SELECTIVE.requirement(id(name), new SelectiveRequirement(candidates, modifiers));
    }

    private static RecipeRequirement<SelectiveRequirement> selector(String name, List<RecipeRequirement<?>> candidates) {
        return RecipeRequirements.SELECTIVE.requirement(id(name), new SelectiveRequirement(candidates));
    }

    private static RecipeRequirement<String> external(String namespace,
                                                       BiFunction<RecipeProcess, RecipeStage, RequirementTransaction> acquire) {
        var location = new ResourceLocation(namespace, "requirement");
        return binding(location, acquire);
    }

    private static RecipeRequirement<String> binding(ResourceLocation location,
                                                      BiFunction<RecipeProcess, RecipeStage, RequirementTransaction> acquire) {
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

    private static RecipeProcess process(int duration, RecipeRequirement<?>... requirements) {
        return new RecipeProcess(new MachineInstance(),
            new MachineRecipe(id("recipe"), duration, ObjectArrayList.of(requirements)), 17);
    }

    private static SelectiveState state(RecipeProcess process) {
        return Objects.requireNonNull(process.components().get(SelectiveState.TYPE));
    }

    private static ResourceLocation id(String name) { return new ResourceLocation("selective_test", name); }

    private static SlottedResourceBuffer buffer() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(TYPE, 100);
        return new SlottedResourceBuffer(1, capacities, NO_OP);
    }

    private record SimpleTransaction(ProcessResult result, Runnable onCommit, Runnable onRollback) implements RequirementTransaction {
        @Override public void commit() { onCommit.run(); }
        @Override public void rollback() { onRollback.run(); }
    }

    private static final class Environment {
        private final SlottedResourceBuffer input = buffer();
        private final SlottedResourceBuffer output = buffer();
        private final List<String> events = new ObjectArrayList<>();
        private Environment() { input.insert(MATERIAL, 20, Actionable.MODULATE); }
        private Candidate candidate(String name, long start, long tick, long end) {
            return new Candidate(this, name, start, tick, end);
        }
    }

    private static final class Candidate {
        private final Environment env;
        private final String name;
        private final long startAmount;
        private final long tickAmount;
        private final long endAmount;
        private final Object2ObjectOpenHashMap<RecipeStage, ProcessResult> results = new Object2ObjectOpenHashMap<>();
        private final List<RequirementPath> paths = new ObjectArrayList<>();
        private final List<Boolean> committedAtAcquisition = new ObjectArrayList<>();
        private @Nullable RuntimeException resultFault;
        private @Nullable RuntimeException rollbackFault;
        private @Nullable RuntimeException tokenRollbackFault;
        private @Nullable RuntimeException completeFault;
        private int acquisitionEffects;
        private int businessEffects;
        private int startAcquisitions;

        private Candidate(Environment env, String name, long start, long tick, long end) {
            this.env = env;
            this.name = name;
            startAmount = start;
            tickAmount = tick;
            endAmount = end;
        }

        private RecipeRequirement<String> requirement() { return binding(id(name), this::acquire); }

        private RequirementTransaction acquire(RecipeProcess process, RecipeStage stage) {
            env.events.add(name + ":acquire:" + stage);
            var path = process.requirementPath();
            paths.add(path);
            var tick = stage == RecipeStage.TICK ? process.tickContext() : null;
            if (stage == RecipeStage.START) { startAcquisitions++; }
            else {
                var decision = Objects.requireNonNull(state(process).selection(Objects.requireNonNull(path.parent())));
                committedAtAcquisition.add(decision.committed());
            }
            long amount = switch (stage) {
                case START -> startAmount;
                case TICK -> tickAmount;
                case END -> endAmount;
            };
            var tokens = new ObjectArrayList<ResourceReservation>();
            if (amount > 0) {
                var result = stage == RecipeStage.END ? env.output.reserveInsert(env.output.snapshot(), MATERIAL, amount)
                    : env.input.reserveExtract(env.input.snapshot(), MATERIAL, amount);
                assertEquals(ReservationResult.Status.ACCEPTED, result.status());
                tokens.add(new TracedToken(Objects.requireNonNull(result.reservation()), name + ":" + stage, env.events,
                    stage == RecipeStage.START ? tokenRollbackFault : null));
            }
            var result = results.getOrDefault(stage, ProcessResult.SUCCESS);
            acquisitionEffects++;
            return new RequirementTransaction() {
                private boolean committed;
                @Override public ProcessResult result() {
                    checkScope();
                    if (stage == RecipeStage.START && resultFault != null) { throw resultFault; }
                    return result;
                }
                @Override public List<? extends ResourceReservation> reservations() { checkScope(); return tokens; }
                @Override public void commit() {
                    checkScope();
                    env.events.add(name + ":commit:" + stage);
                    businessEffects++;
                    committed = true;
                }
                @Override public void complete() {
                    checkScope();
                    env.events.add(name + ":complete:" + stage);
                    if (stage == RecipeStage.END && completeFault != null) { throw completeFault; }
                }
                @Override public void rollback() {
                    checkScope();
                    for (var token : tokens) { token.context().requireLock(); }
                    env.events.add(name + ":rollback:" + stage);
                    acquisitionEffects--;
                    if (committed) { businessEffects--; }
                    if (stage == RecipeStage.START && rollbackFault != null) { throw rollbackFault; }
                }
                private void checkScope() {
                    assertEquals(path, process.requirementPath());
                    if (tick != null) { assertEquals(tick, process.tickContext()); }
                }
            };
        }
    }

    private record TracedToken(ResourceReservation delegate, String name, List<String> events,
                               @Nullable RuntimeException rollbackFault) implements ResourceReservation {
        @Override public StorageContext context() { return delegate.context(); }
        @Override public boolean canApply() { return delegate.canApply(); }
        @Override public void apply() { events.add(name + ":token:apply"); delegate.apply(); }
        @Override public void complete() { events.add(name + ":token:complete"); delegate.complete(); }
        @Override public void rollback() {
            events.add(name + ":token:rollback");
            delegate.rollback();
            if (rollbackFault != null) { throw rollbackFault; }
        }
    }
}
