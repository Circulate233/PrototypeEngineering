package com.circulation.prototype_eng.network;

import ae2.core.network.ClientboundPacket;
import com.circulation.prototype_eng.api.client.jei.ClientRecipeDeclarations;
import com.circulation.prototype_eng.api.client.jei.RecipeJeiDisplays;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeDeclarationKey;
import com.circulation.prototype_eng.api.recipe.RecipeDefinitionManifest;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.client.recipe.ClientRecipeDefinitions;
import com.circulation.prototype_eng.network.clientbound.RecipeDefinitionManifestPacket;
import com.circulation.prototype_eng.recipe.RecipeDefinitionManifestCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientboundDeliveryTest {
    private static ResourceLocation id(String name) { return new ResourceLocation("ae_packet_delivery", name); }

    private static RecipeRegistry.Snapshot snapshot() {
        var recipe = new MachineRecipe(id("recipe"), 2, List.of());
        var registry = new RecipeRegistry();
        registry.register(new DefaultRecipePool(id("pool"), "pool", List.of(recipe)));
        registry.declareRevision(recipe, "behavior-v1");
        registry.freeze();
        return registry.snapshot();
    }

    private static RecipeDefinitionManifest manifest(UUID session, long generation, RecipeRegistry.Snapshot snapshot) {
        return new RecipeDefinitionManifest(session, generation, new RecipeDeclarationKey(id("declaration"), "v1"),
            RecipeDefinitionManifest.describe(snapshot));
    }

    private static RecipeDefinitionManifestPacket packet(RecipeDefinitionManifest manifest) {
        byte[] payload = RecipeDefinitionManifestCodec.encode(manifest);
        return RecipeDefinitionManifestPacket.part(manifest, payload, 0);
    }

    private static ManifestDelivery decode(ClientRecipeDefinitions target, RecipeDefinitionManifestPacket payload) {
        var bytes = Unpooled.buffer();
        try {
            ClientboundPacket outgoing = new ManifestDelivery(target, payload);
            outgoing.toBytes(bytes);
            var incoming = new ManifestDelivery(target, new RecipeDefinitionManifestPacket());
            incoming.fromBytes(bytes);
            return incoming;
        } finally {
            bytes.release();
        }
    }

    @Test
    void aeReadWriteAndCapturedReceiptDispatchCannotRetagOldDataAfterReconnect() {
        var local = snapshot();
        var server = snapshot();
        assertNotSame(local.catalog().recipes().getFirst(), server.catalog().recipes().getFirst());
        var declarations = new ClientRecipeDeclarations();
        var displays = new RecipeJeiDisplays();
        displays.freeze();
        declarations.register(new RecipeDeclarationKey(id("declaration"), "v1"), local, displays);
        var applies = new int[1];
        var target = new ClientRecipeDefinitions(declarations, new ClientRecipeDefinitions.Renderer() {
            @Override
            public ClientRecipeDefinitions.ApplyResult apply(ClientRecipeDeclarations.Declaration declaration) {
                applies[0]++;
                return new ClientRecipeDefinitions.ApplyResult(true, "Ready");
            }

            @Override
            public void withdraw(String reason) {}
        });
        var oldConnection = new Object();
        target.connected(oldConnection);
        var old = manifest(UUID.randomUUID(), 100, server);
        var delivery = decode(target, packet(old));
        delivery.receiveConnection(oldConnection);
        delivery.dispatchClient(oldConnection);
        assertEquals(old, target.applied());
        assertEquals(1, applies[0]);

        target.disconnected(oldConnection);
        var newConnection = new Object();
        target.connected(newConnection);
        var current = manifest(UUID.randomUUID(), 0, server);
        var currentDelivery = decode(target, packet(current));
        currentDelivery.receiveConnection(newConnection);
        currentDelivery.dispatchClient(newConnection);
        assertEquals(current, target.applied());
        delivery.dispatchClient(newConnection);
        assertEquals(current, target.applied());
        assertEquals(2, applies[0]);
        assertThrows(IllegalStateException.class, () -> delivery.receiveConnection(newConnection));
        delivery.dispatchClient(oldConnection);
        assertEquals(current, target.applied());
        assertEquals(2, applies[0]);
    }

    /** Exercise the common receipt gate with the real packet body without initializing Minecraft/FML. */
    private static final class ManifestDelivery extends ConnectionClientboundPacket {
        private final @Nullable ClientRecipeDefinitions target;
        private final RecipeDefinitionManifestPacket payload;

        public ManifestDelivery() {
            target = null;
            payload = new RecipeDefinitionManifestPacket();
        }

        private ManifestDelivery(@Nullable ClientRecipeDefinitions target, RecipeDefinitionManifestPacket payload) {
            this.target = target;
            this.payload = payload;
        }

        @Override
        protected void read(ByteBuf buffer) { payload.fromBytes(buffer); }

        @Override
        protected void write(ByteBuf buffer) { payload.toBytes(buffer); }

        @Override
        protected void handleClientConnection(Object receivedConnection) { Objects.requireNonNull(target).receive(receivedConnection, payload); }
    }
}
