package com.circulation.prototype_eng.structure.tool;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class BuilderDismantleRecoveryTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void onlyUndeliveredRemainderIsRetriedAfterSave() {
        var recovery = new BuilderDismantleRecovery("test");
        recovery.retain(new ItemStack(Items.COAL));
        var tagged = new ItemStack(Items.IRON_INGOT, 7);
        var tag = new NBTTagCompound();
        tag.setString("source", "dismantled storage");
        tagged.setTagCompound(tag);
        recovery.retain(tagged);
        recovery.retain(new ItemStack(Items.GOLD_INGOT));

        assertEquals(BuilderDismantleRecovery.Result.FULL, recovery.drain(() -> true, stack -> {
            if (stack.getItem() == Items.COAL) return ItemStack.EMPTY;
            assertSame(Items.IRON_INGOT, stack.getItem());
            stack.shrink(3);
            return stack;
        }));

        var loaded = new BuilderDismantleRecovery("test");
        loaded.readFromNBT(recovery.writeToNBT(new NBTTagCompound()));
        var received = new ArrayList<ItemStack>();
        assertEquals(BuilderDismantleRecovery.Result.EMPTY, loaded.drain(() -> true, stack -> {
            received.add(stack);
            return ItemStack.EMPTY;
        }));
        assertEquals(List.of(Items.IRON_INGOT, Items.GOLD_INGOT), received.stream().map(ItemStack::getItem).toList());
        assertEquals(4, received.getFirst().getCount());
        assertEquals(tag, received.getFirst().getTagCompound());
    }
}
