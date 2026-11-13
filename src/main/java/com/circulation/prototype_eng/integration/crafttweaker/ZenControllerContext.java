package com.circulation.prototype_eng.integration.crafttweaker;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.data.DataComponents;
import com.circulation.prototype_eng.api.machine.ControllerInventory;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.minecraft.CraftTweakerMC;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.List;
import java.util.Objects;

/**
 * One reusable script view per attached machine; inventory access requires the server owner.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.ControllerContext")
@SuppressWarnings("unused") // Script methods are resolved through ZenScript annotations.
public final class ZenControllerContext {
    public static final ComponentType<MachineInstance, ZenControllerContext> TYPE = new ComponentType<>(
        new ResourceLocation("prototype_eng", "controller_inventory_context"), ZenControllerContext::new, List.of());
    private final MachineInstance machine;
    @Nullable
    private ZenComponentData data;

    private ZenControllerContext(MachineInstance machine) {
        this.machine = Objects.requireNonNull(machine);
    }

    @ZenGetter("data")
    public ZenComponentData data() {
        if (data == null) {
            var component = machine.components().get(DataComponents.MACHINE);
            if (component == null) throw new IllegalStateException("Machine data component was not declared");
            data = new ZenComponentData(component);
        }
        return data;
    }

    @ZenGetter("inventorySize")
    public int inventorySize() {
        var inventory = machine.controllerInventory();
        return inventory instanceof ControllerInventory nativeInventory ? nativeInventory.availableSlots() : inventory.size();
    }

    @ZenMethod
    public void forEachInventory(ZenControllerSlotVisitor visitor) {
        Objects.requireNonNull(visitor);
        int count = inventorySize();
        for (int slot = 0; slot < count; slot++) visitor.visit(slot, inventoryStack(slot));
    }

    @ZenMethod
    public IItemStack inventoryStack(int slot) {
        return CraftTweakerMC.getIItemStack(machine.controllerInventory().getStackInSlot(slot).copy());
    }
}
