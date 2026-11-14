package com.circulation.prototype_eng.container;

import ae2.api.stacks.AEKeyTypes;
import ae2.api.stacks.GenericStack;
import ae2.container.guisync.DataSynchronization;
import ae2.container.guisync.GuiSync;
import com.circulation.prototype_eng.api.machine.ComputerController;
import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.testsupport.TestResources;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ComputerDetailsSyncTest {
    @Test
    void aeTransfersDefinitionsAndOnlyChangedRecipeStates() {
        var type = new TestResources.KeyType("computer_gui_inputs");
        AEKeyTypes.register(type);
        var item = new TestResources.Key(type, "component");
        var input = new GenericStack(new TestResources.Key(type, "water"), 250);
        var supply = Reference2DoubleMaps.singleton(ComputingResourceFixtures.COMPUTE, 20.5);
        var server = new Host();
        var client = new Host();
        var outgoing = new DataSynchronization(server);
        var incoming = new DataSynchronization(client);
        server.packet = new ComputerDetailsPacket(List.of(new ComputerDetailsPacket.Component(item, 2, 4, 8, supply, Reference2DoubleMaps.emptyMap())),
            List.of(new ComputerDetailsPacket.Recipe(new ResourceLocation("test", "water_recipe"), List.of(input), supply)),
            List.of(new ComputerDetailsPacket.RecipeState(0, ComputerController.RECIPE_INPUT)));
        int fullBytes = transfer(outgoing, incoming, true);
        var components = client.packet.components();
        var recipes = client.packet.recipes();
        assertNotNull(components);
        assertNotNull(recipes);
        assertEquals(item, components.getFirst().item());
        assertEquals(input, recipes.getFirst().inputs().getFirst());
        assertEquals(20.5, recipes.getFirst().provided().values().iterator().nextDouble());
        assertEquals(ComputerController.RECIPE_INPUT, client.packet.changed().getFirst().state());
        assertFalse(outgoing.hasChanges());
        server.packet = new ComputerDetailsPacket(null, null, List.of(new ComputerDetailsPacket.RecipeState(0, ComputerController.RECIPE_ACTIVE)));
        int updateBytes = transfer(outgoing, incoming, false);
        assertNull(client.packet.components());
        assertNull(client.packet.recipes());
        assertEquals(ComputerController.RECIPE_ACTIVE, client.packet.changed().getFirst().state());
        assertTrue(updateBytes < fullBytes);
        server.packet = new ComputerDetailsPacket(List.of(), null, List.of());
        transfer(outgoing, incoming, false);
        assertTrue(client.packet.components().isEmpty());
        assertNull(client.packet.recipes());
    }

    private static int transfer(DataSynchronization outgoing, DataSynchronization incoming, boolean full) {
        var data = Unpooled.buffer();
        try {
            if (full) outgoing.writeFull(data);
            else outgoing.writeUpdate(data);
            int bytes = data.readableBytes();
            incoming.readUpdate(data);
            return bytes;
        } finally {
            data.release();
        }
    }

    private static final class Host {
        @GuiSync(0)
        public ComputerDetailsPacket packet = ComputerDetailsPacket.EMPTY;
    }
}
