package com.circulation.prototype_eng.recipe;

import com.circulation.prototype_eng.api.client.jei.ClientRecipeDeclarations;
import com.circulation.prototype_eng.api.client.jei.RecipeJeiDisplays;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeDeclarationKey;
import com.circulation.prototype_eng.api.recipe.RecipeDeclarations;
import com.circulation.prototype_eng.api.recipe.RecipeDefinitionManifest;
import com.circulation.prototype_eng.api.recipe.RecipeDefinitionRevision;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.client.recipe.ClientRecipeDefinitions;
import com.circulation.prototype_eng.network.clientbound.RecipeDefinitionManifestPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeDefinitionRemoteSyncTest {
    private static ResourceLocation id(String name) {
        return new ResourceLocation("remote_definition", name);
    }

    private static RecipeDeclarationKey key(String revision) {
        return new RecipeDeclarationKey(id("declarations"), revision);
    }

    private static RecipeRegistry.Snapshot snapshot(String revision, boolean reverse) {
        var first = new MachineRecipe(id("z_first"), 2, List.of());
        var second = new MachineRecipe(id("a_second"), 3, List.of());
        var registry = new RecipeRegistry();
        registry.register(new DefaultRecipePool(new ResourceLocation("alpha.beta", "b_pool"), "pool.b", List.of(second)));
        registry.register(new DefaultRecipePool(new ResourceLocation("alpha", "a_pool"), "pool.a", reverse ? List.of(second, first) : List.of(first, second)));
        registry.declareRevision(first, revision);
        registry.declareRevision(second, revision);
        registry.freeze();
        return registry.snapshot();
    }

    private static RecipeJeiDisplays displays() {
        var displays = new RecipeJeiDisplays();
        displays.freeze();
        return displays;
    }

    private static RecipeDefinitionManifest manifest(UUID session, long generation, String declarationRevision, String memberRevision) {
        return new RecipeDefinitionManifest(session, generation, key(declarationRevision),
            RecipeDefinitionManifest.describe(snapshot(memberRevision, false)));
    }

    private static RecipeDefinitionManifestPacket wire(RecipeDefinitionManifestPacket outgoing) {
        var bytes = Unpooled.buffer();
        try {
            outgoing.toBytes(bytes);
            var incoming = new RecipeDefinitionManifestPacket();
            incoming.fromBytes(bytes);
            return incoming;
        } finally {
            bytes.release();
        }
    }

    private static boolean deliver(ClientRecipeDefinitions client, Object connection, RecipeDefinitionManifest manifest) {
        byte[] bytes = RecipeDefinitionManifestCodec.encode(manifest);
        boolean completed = false;
        int chunks = (bytes.length + RecipeDefinitionManifestPacket.CHUNK_BYTES - 1) / RecipeDefinitionManifestPacket.CHUNK_BYTES;
        for (int p = 0; p < chunks; p++)
            completed |= client.receive(connection, wire(RecipeDefinitionManifestPacket.part(manifest, bytes, p)));
        return completed;
    }

    @Test
    void realCodecResolvesIndependentLocalObjectsWithCanonicalPriorityAndVersionIdentity() {
        var server = snapshot("behavior-v2", false);
        var client = snapshot("behavior-v2", false);
        assertNotSame(server.catalog().recipes().getFirst(), client.catalog().recipes().getFirst());
        var outgoing = RecipeDefinitionManifest.capture(UUID.randomUUID(), key("display-v4"), server);
        var decoded = RecipeDefinitionManifestCodec.decode(RecipeDefinitionManifestCodec.encode(outgoing));
        assertEquals(outgoing, decoded);
        assertEquals(List.of(new ResourceLocation("alpha", "a_pool"), new ResourceLocation("alpha.beta", "b_pool")), decoded.pools().stream().map(RecipeDefinitionManifest.Pool::id).toList());
        assertEquals(List.of(id("z_first"), id("a_second")), decoded.pools().getFirst().members().stream().map(RecipeDefinitionRevision::recipeId).toList());
        assertTrue(decoded.matches(client));
        assertFalse(decoded.matches(snapshot("behavior-v3", false)));
        assertFalse(decoded.matches(snapshot("behavior-v2", true)));
        var shiftedGeneration = new RecipeDefinitionManifest(decoded.serverSession(), 53, decoded.declaration(), decoded.pools());
        assertTrue(shiftedGeneration.matches(client));
    }

    @Test
    void commonDeclarationsRequireExplicitVersionsAndCannotRebindEquivalentLookingObjects() {
        var declarations = new RecipeDeclarations();
        var registered = snapshot("v1", false);
        declarations.register(key("v1"), registered);
        declarations.register(key("v1"), registered);
        assertSame(registered, declarations.get(key("v1")));
        assertThrows(IllegalArgumentException.class, () -> declarations.register(key("v1"), snapshot("v1", false)));
        assertThrows(IllegalArgumentException.class, () -> declarations.register(key("alias"), registered));
        var staticRegistry = new RecipeRegistry();
        staticRegistry.register(new DefaultRecipePool(id("static_pool"), "pool", List.of(new MachineRecipe(id("unversioned"), 1, List.of()))));
        staticRegistry.freeze();
        assertThrows(IllegalArgumentException.class, () -> declarations.register(key("static"), staticRegistry.snapshot()));
        assertEquals(1, staticRegistry.catalog().recipes().size());
    }

    @Test
    void generationNoticeImmediatelyRevokesOldReadyAndAllowsOnlyTheSameGenerationReadyPhase() {
        var local = new ClientRecipeDeclarations();
        var renderer = new RecordingRenderer();
        var state = new ClientRecipeDefinitions(local, renderer);
        var connection = new Object();
        var session = UUID.randomUUID();
        state.connected(connection);
        local.register(key("old"), snapshot("old", false), displays());
        var old = manifest(session, 0, "old", "old");
        assertTrue(deliver(state, connection, old));
        assertEquals(old, state.applied());
        state.receive(connection, wire(RecipeDefinitionManifestPacket.notice(session, 1)));
        assertNull(state.applied());
        assertNull(state.desired());
        assertEquals(1, state.generation());
        assertFalse(deliver(state, connection, old));
        var next = manifest(session, 1, "late", "v2");
        deliver(state, connection, next);
        assertNull(state.applied());
        assertTrue(state.reason().contains("Missing local"));
        var exactLocal = snapshot("v2", false);
        local.register(key("late"), exactLocal, displays());
        assertEquals(next, state.applied());
        assertSame(exactLocal, renderer.last.snapshot());
        int withdrawals = renderer.withdrawals;
        state.receive(connection, wire(RecipeDefinitionManifestPacket.notice(session, 1)));
        assertEquals(withdrawals, renderer.withdrawals);
        assertEquals(next, state.applied());
    }

    @Test
    void latestDesiredWinsWhenDeclarationsAndRuntimeArriveLateAndReloadRevalidatesNativeState() {
        var local = new ClientRecipeDeclarations();
        var renderer = new RecordingRenderer();
        renderer.ready = false;
        var state = new ClientRecipeDefinitions(local, renderer);
        var connection = new Object();
        var session = UUID.randomUUID();
        state.connected(connection);
        deliver(state, connection, manifest(session, 2, "old", "old"));
        var latest = manifest(session, 3, "new", "new");
        deliver(state, connection, latest);
        local.register(key("old"), snapshot("old", false), displays());
        assertEquals(0, renderer.applies);
        local.register(key("new"), snapshot("new", false), displays());
        assertEquals(1, renderer.applies);
        assertNull(state.applied());
        assertEquals("Runtime unavailable", state.reason());
        renderer.ready = true;
        state.retry();
        assertEquals(latest, state.applied());
        state.runtimeUnavailable();
        assertNull(state.applied());
        assertEquals(latest, state.desired());
        assertEquals("HEI runtime is rebuilding", state.reason());
        state.runtimeReady();
        assertEquals(latest, state.applied());
        int beforeReload = renderer.applies;
        state.resourcesReloaded();
        assertEquals(beforeReload + 1, renderer.applies);
        assertEquals(latest, state.applied());
        renderer.ready = false;
        state.resourcesReloaded();
        assertNull(state.applied());
        assertEquals(latest, state.desired());
    }

    @Test
    void wrongLocalMemberVersionAndOrderRemainExplicitlyUnready() {
        var local = new ClientRecipeDeclarations();
        var renderer = new RecordingRenderer();
        var state = new ClientRecipeDefinitions(local, renderer);
        var connection = new Object();
        state.connected(connection);
        local.register(key("wrong-version"), snapshot("v0", false), displays());
        var session = UUID.randomUUID();
        deliver(state, connection, manifest(session, 0, "wrong-version", "v1"));
        assertEquals(0, renderer.applies);
        assertTrue(state.reason().contains("mismatch"));
        local.register(key("wrong-order"), snapshot("v1", true), displays());
        deliver(state, connection, manifest(session, 1, "wrong-order", "v1"));
        assertEquals(0, renderer.applies);
        assertNull(state.applied());
    }

    @Test
    void outOfOrderRepeatedChunksCompleteOnceAndConflictsPoisonOnlyTheirGeneration() {
        var local = new ClientRecipeDeclarations();
        var renderer = new RecordingRenderer();
        var state = new ClientRecipeDefinitions(local, renderer);
        var connection = new Object();
        state.connected(connection);
        String longRevision = "version-".repeat(5000);
        local.register(key(longRevision), snapshot("v1", false), displays());
        var session = UUID.randomUUID();
        var wanted = manifest(session, 2, longRevision, "v1");
        var payload = RecipeDefinitionManifestCodec.encode(wanted);
        int count = (payload.length + RecipeDefinitionManifestPacket.CHUNK_BYTES - 1) / RecipeDefinitionManifestPacket.CHUNK_BYTES;
        assertTrue(count > 1);
        var last = wire(RecipeDefinitionManifestPacket.part(wanted, payload, count - 1));
        assertFalse(state.receive(connection, last));
        assertFalse(state.receive(connection, last));
        for (int p = count - 2; p >= 0; p--)
            state.receive(connection, wire(RecipeDefinitionManifestPacket.part(wanted, payload, p)));
        assertEquals(wanted, state.applied());
        assertEquals(1, renderer.applies);
        assertFalse(state.receive(connection, last));
        var altered = last.bytes().clone();
        altered[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> state.receive(connection,
            wire(new RecipeDefinitionManifestPacket(session, 2, last.totalBytes(), last.index(), altered))));
        assertNull(state.applied());
        assertFalse(deliver(state, connection, wanted));
        state.retry();
        assertNull(state.applied());
        var newer = new RecipeDefinitionManifest(session, 3, wanted.declaration(), wanted.pools());
        assertTrue(deliver(state, connection, newer));
        assertEquals(newer, state.applied());
    }

    @Test
    void conflictingIncompleteFragmentsAndHeadersCannotRestartTheirPoisonedGeneration() {
        var state = new ClientRecipeDefinitions(new ClientRecipeDeclarations(), new RecordingRenderer());
        var connection = new Object();
        state.connected(connection);
        var session = UUID.randomUUID();
        var wanted = manifest(session, 0, "v".repeat(40000), "v1");
        var payload = RecipeDefinitionManifestCodec.encode(wanted);
        var first = wire(RecipeDefinitionManifestPacket.part(wanted, payload, 0));
        state.receive(connection, first);
        var changed = first.bytes().clone();
        changed[10] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> state.receive(connection,
            wire(new RecipeDefinitionManifestPacket(session, 0, first.totalBytes(), 0, changed))));
        assertFalse(deliver(state, connection, wanted));
        var next = new RecipeDefinitionManifest(session, 1, wanted.declaration(), wanted.pools());
        var nextPayload = RecipeDefinitionManifestCodec.encode(next);
        state.receive(connection, wire(RecipeDefinitionManifestPacket.part(next, nextPayload, 0)));
        assertThrows(IllegalArgumentException.class, () -> state.receive(connection,
            wire(new RecipeDefinitionManifestPacket(session, 1, nextPayload.length + 1, 0, first.bytes()))));
        assertNull(state.desired());
    }

    @Test
    void disconnectReconnectAndPinnedSessionRejectOldOwnerMessagesEvenWhenGenerationIsLarger() {
        var local = new ClientRecipeDeclarations();
        local.register(key("v1"), snapshot("v1", false), displays());
        var state = new ClientRecipeDefinitions(local, new RecordingRenderer());
        var oldConnection = new Object();
        var oldSession = UUID.randomUUID();
        state.connected(oldConnection);
        deliver(state, oldConnection, manifest(oldSession, 4, "v1", "v1"));
        state.disconnected(oldConnection);
        assertNull(state.applied());
        var nextConnection = new Object();
        var nextSession = UUID.randomUUID();
        state.connected(nextConnection);
        assertFalse(deliver(state, oldConnection, manifest(oldSession, 100, "v1", "v1")));
        var next = manifest(nextSession, 0, "v1", "v1");
        deliver(state, nextConnection, next);
        assertFalse(deliver(state, nextConnection, manifest(oldSession, 100, "v1", "v1")));
        state.disconnected(oldConnection);
        assertEquals(next, state.applied());
    }

    @Test
    void malformedBodyAndWrongEnvelopeSessionWithdrawWithoutFallbackToOldReady() {
        var local = new ClientRecipeDeclarations();
        local.register(key("v1"), snapshot("v1", false), displays());
        var state = new ClientRecipeDefinitions(local, new RecordingRenderer());
        var connection = new Object();
        var session = UUID.randomUUID();
        state.connected(connection);
        deliver(state, connection, manifest(session, 0, "v1", "v1"));
        var wrongBody = manifest(UUID.randomUUID(), 1, "v1", "v1");
        var bytes = RecipeDefinitionManifestCodec.encode(wrongBody);
        assertThrows(IllegalArgumentException.class, () -> state.receive(connection,
            wire(new RecipeDefinitionManifestPacket(session, 1, bytes.length, 0, bytes))));
        assertNull(state.applied());
        assertFalse(deliver(state, connection, manifest(session, 1, "v1", "v1")));
        var valid = RecipeDefinitionManifestCodec.encode(manifest(session, 2, "v1", "v1"));
        assertThrows(IllegalArgumentException.class, () -> RecipeDefinitionManifestCodec.decode(Arrays.copyOf(valid, valid.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> RecipeDefinitionManifestCodec.decode(Arrays.copyOf(valid, valid.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> new RecipeDefinitionManifestPacket(session, 2,
            RecipeDefinitionManifestCodec.MAX_BYTES + 1, 0, new byte[RecipeDefinitionManifestPacket.CHUNK_BYTES]));
    }

    @Test
    void localProviderCopyStaysSealedAndCannotRebindOrRegisterBeforeFreeze() {
        var declarations = new ClientRecipeDeclarations();
        var snapshot = snapshot("v1", false);
        var source = displays();
        declarations.register(key("v1"), snapshot, source);
        declarations.register(key("v1"), snapshot, source);
        var bound = Objects.requireNonNull(declarations.get(key("v1")));
        assertNotSame(source, bound.displays());
        source.beginRegistration();
        assertThrows(IllegalStateException.class, () -> declarations.register(key("v1"), snapshot, source));
        var customType = new RecipeRequirementType<>(id("custom_display"), new RecipeRequirementSystem<>() {
        });
        source.register(customType, (_, _, _) -> {
        });
        source.freeze();
        assertThrows(IllegalArgumentException.class, () -> declarations.register(key("v1"), snapshot, source));
        assertThrows(IllegalStateException.class, bound.displays()::beginRegistration);
        assertThrows(IllegalArgumentException.class, () -> declarations.register(key("v1"), snapshot("v1", false), source));
        assertThrows(IllegalStateException.class, () -> declarations.register(key("unfinished"), snapshot, new RecipeJeiDisplays()));
        assertNull(declarations.get(key("unfinished")));
    }

    @Test
    void reentrantNewGenerationDuringNativeApplyCannotPublishAnObsoleteSuccess() {
        var declarations = new ClientRecipeDeclarations();
        declarations.register(key("v1"), snapshot("v1", false), displays());
        var renderer = new RecordingRenderer();
        var state = new ClientRecipeDefinitions(declarations, renderer);
        var connection = new Object();
        var session = UUID.randomUUID();
        state.connected(connection);
        renderer.onApply = () -> state.receive(connection, wire(RecipeDefinitionManifestPacket.notice(session, 1)));
        deliver(state, connection, manifest(session, 0, "v1", "v1"));
        assertNull(state.applied());
        assertNull(state.desired());
        assertEquals(1, state.generation());
        renderer.onApply = null;
        var current = manifest(session, 1, "v1", "v1");
        deliver(state, connection, current);
        assertEquals(current, state.applied());
        renderer.onApply = state::runtimeUnavailable;
        state.retry();
        assertNull(state.applied());
        assertEquals(current, state.desired());
        renderer.onApply = null;
        state.runtimeReady();
        assertEquals(current, state.applied());
    }

    @Test
    void sameTupleReentryDoesNotSpinAndActualNewDesiredUsesNextOwnerTick() {
        var declarations = new ClientRecipeDeclarations();
        declarations.register(key("v1"), snapshot("v1", false), displays());
        var renderer = new RecordingRenderer();
        var state = new ClientRecipeDefinitions(declarations, renderer);
        var connection = new Object();
        var session = UUID.randomUUID();
        state.connected(connection);
        renderer.onApply = state::retry;
        deliver(state, connection, manifest(session, 0, "v1", "v1"));
        assertEquals(1, renderer.applies);
        state.tick();
        assertEquals(1, renderer.applies);
        var latest = manifest(session, 1, "v1", "v1");
        renderer.onApply = () -> deliver(state, connection, latest);
        state.retry();
        assertNull(state.applied());
        renderer.onApply = state::retry;
        state.tick();
        assertEquals(latest, state.applied());
        state.tick();
        assertEquals(3, renderer.applies);

        renderer.onApply = state::runtimeReady;
        state.retry();
        int beforeTick = renderer.applies;
        state.tick();
        assertEquals(beforeTick + 1, renderer.applies);
        assertNull(state.applied());
        renderer.onApply = null;
        state.tick();
        assertEquals(latest, state.applied());
    }

    @Test
    void failingDeclarationListenersDoNotPreventLaterCommittedDeclarationConsumers() {
        var failure = new IllegalStateException("first consumer");
        var laterFailure = new IllegalArgumentException("second consumer");
        var common = new RecipeDeclarations();
        var called = new boolean[1];
        common.onRegistered((_, _) -> {
            throw failure;
        });
        common.onRegistered((_, _) -> {
            called[0] = true;
            throw laterFailure;
        });
        var snapshot = snapshot("v1", false);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> common.register(key("v1"), snapshot)));
        assertTrue(called[0]);
        assertEquals(List.of(laterFailure), Arrays.asList(failure.getSuppressed()));
        assertSame(snapshot, common.get(key("v1")));

        var client = new ClientRecipeDeclarations();
        var clientCalled = new boolean[1];
        client.onRegistered(() -> {
            throw failure;
        });
        client.onRegistered(() -> clientCalled[0] = true);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> client.register(key("v1"), snapshot, displays())));
        assertTrue(clientCalled[0]);
        assertSame(snapshot, Objects.requireNonNull(client.get(key("v1"))).snapshot());
    }

    @Test
    void explicitStaticBaselineKeepsLegacyLocalDisplayUntilDeclaredUpgradeAndNeverClaimsRemoteReady() {
        var declarations = new ClientRecipeDeclarations();
        var renderer = new RecordingRenderer();
        renderer.staticReady = true;
        var state = new ClientRecipeDefinitions(declarations, renderer);
        state.runtimeReady();
        assertTrue(state.localStaticVisible());
        assertNull(state.applied());
        assertTrue(state.reason().contains("Local static"));
        var connection = new Object();
        var session = UUID.randomUUID();
        state.connected(connection);
        var staticNotice = wire(RecipeDefinitionManifestPacket.notice(session, 0, false));
        assertFalse(state.localStaticVisible());
        assertFalse(staticNotice.declarationRequired());
        state.receive(connection, staticNotice);
        assertTrue(state.localStaticVisible());
        assertNull(state.applied());

        renderer.onStatic = () -> state.receive(connection, wire(RecipeDefinitionManifestPacket.notice(session, 0, true)));
        state.runtimeReady();
        assertFalse(state.localStaticVisible());
        assertNull(state.applied());
        renderer.onStatic = null;
        int staticShows = renderer.staticShows;
        state.receive(connection, staticNotice);
        state.runtimeReady();
        state.resourcesReloaded();
        assertEquals(staticShows, renderer.staticShows);
        var declared = manifest(session, 0, "v1", "v1");
        deliver(state, connection, declared);
        assertNull(state.applied());
        declarations.register(key("v1"), snapshot("v1", false), displays());
        assertEquals(declared, state.applied());
        assertFalse(state.localStaticVisible());

        state.disconnected(connection);
        state.runtimeReady();
        assertTrue(state.localStaticVisible());
        assertNull(state.applied());
        var nextConnection = new Object();
        state.connected(nextConnection);
        var nextSession = UUID.randomUUID();
        state.receive(nextConnection, wire(RecipeDefinitionManifestPacket.notice(nextSession, 2, true)));
        assertFalse(state.localStaticVisible());
        assertThrows(IllegalArgumentException.class, () -> wire(RecipeDefinitionManifestPacket.notice(nextSession, 3, false)));
        state.runtimeReady();
        assertFalse(state.localStaticVisible());
        assertFalse(deliver(state, connection, declared));
    }

    @Test
    void firstReadyFragmentRevokesStaticBeforeTheCompleteBodyOrLocalDeclarationArrives() {
        var declarations = new ClientRecipeDeclarations();
        var renderer = new RecordingRenderer();
        renderer.staticReady = true;
        var state = new ClientRecipeDefinitions(declarations, renderer);
        var connection = new Object();
        var session = UUID.randomUUID();
        state.connected(connection);
        var staticNotice = wire(RecipeDefinitionManifestPacket.notice(session, 0, false));
        state.receive(connection, staticNotice);
        assertTrue(state.localStaticVisible());
        var declarationRevision = "declaration-".repeat(3000);
        var wanted = manifest(session, 0, declarationRevision, "v1");
        var payload = RecipeDefinitionManifestCodec.encode(wanted);
        state.receive(connection, wire(RecipeDefinitionManifestPacket.part(wanted, payload, 0)));
        assertFalse(state.localStaticVisible());
        assertNull(state.desired());
        assertNull(state.applied());
        int staticShows = renderer.staticShows;
        state.runtimeReady();
        state.receive(connection, staticNotice);
        assertEquals(staticShows, renderer.staticShows);
        int chunks = (payload.length + RecipeDefinitionManifestPacket.CHUNK_BYTES - 1) / RecipeDefinitionManifestPacket.CHUNK_BYTES;
        for (int p = 1; p < chunks; p++)
            state.receive(connection, wire(RecipeDefinitionManifestPacket.part(wanted, payload, p)));
        assertEquals(wanted, state.desired());
        assertNull(state.applied());
        declarations.register(key(declarationRevision), snapshot("v1", false), displays());
        assertEquals(wanted, state.applied());
        assertFalse(state.localStaticVisible());
    }

    private static final class RecordingRenderer implements ClientRecipeDefinitions.Renderer {
        private boolean ready = true;
        private int applies;
        private int withdrawals;
        private ClientRecipeDeclarations.Declaration last;
        private Runnable onApply;
        private Runnable onStatic;
        private boolean staticReady;
        private int staticShows;

        @Override
        public ClientRecipeDefinitions.ApplyResult apply(ClientRecipeDeclarations.Declaration declaration) {
            applies++;
            last = declaration;
            if (onApply != null) onApply.run();
            return new ClientRecipeDefinitions.ApplyResult(ready, ready ? "Ready" : "Runtime unavailable");
        }

        @Override
        public void withdraw(String reason) {
            withdrawals++;
        }

        @Override
        public ClientRecipeDefinitions.ApplyResult showLocalStatic() {
            staticShows++;
            if (onStatic != null) onStatic.run();
            return new ClientRecipeDefinitions.ApplyResult(staticReady, staticReady ? "Local static" : "Runtime unavailable");
        }
    }
}
