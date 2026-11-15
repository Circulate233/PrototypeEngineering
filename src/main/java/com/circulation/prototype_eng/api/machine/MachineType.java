package com.circulation.prototype_eng.api.machine;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.scheduler.ControllerExecutionPolicy;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import it.unimi.dsi.fastutil.objects.Object2DoubleLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.function.Consumer;

import com.circulation.prototype_eng.api.network.NetworkDeviceEvent;
import com.circulation.prototype_eng.api.network.resource.ComputerInputRecipe;
import com.circulation.prototype_eng.api.network.ComputerInputWork;

/**
 * Published collections are read-only by contract; consumers must not modify them.
 * Immutable machine declaration shared by registration, assembly and later controller bindings.
 * A structure ID permits declaration before Forge block resolution. Neither this definition nor
 * its resolved structure grants world, owner, scheduler or planned-execution authority.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public final class MachineType {
    private final List<Consumer<NetworkDeviceEvent>> networkEvents;
    private final List<ComputerInputRecipe> computerInputRecipes;
    private final ResourceLocation id;
    private final LocalizationEnum displayName;
    private final ResourceLocation structureId;
    private final List<ComponentType<MachineInstance, ?>> componentTypes;
    private final Set<ResourceLocation> recipeGroups;
    private final List<ResourceLocation> recipePoolIds;
    private final List<WorkSlotDefinition> workSlots;
    private final Object2DoubleMap<ResourceLocation> defaultAttributeBases;
    private final ExecutionMode executionMode;
    private final boolean explicitExecutionMode;
    private final int requiresNetwork;
    @Nullable
    private final ControllerExecutionPolicy executionPolicy;
    @Nullable
    private final ResourceLocation controllerModel;
    private final int controllerInventorySlots;
    @Nullable
    private final ToIntFunction<MachineInstance> controllerInventorySize;

    private MachineType(Builder builder) {
        networkEvents = builder.networkEvents;
        computerInputRecipes = builder.computerInputRecipes;
        id = builder.id;
        displayName = builder.displayName;
        structureId = builder.structureId;
        componentTypes = new ObjectArrayList<>(builder.components.values());
        ComponentMap.validateTypes(componentTypes);
        recipePoolIds = new ObjectArrayList<>(builder.recipePools);
        recipeGroups = builder.recipeGroups;
        workSlots = new ObjectArrayList<>(builder.workSlots.values());
        defaultAttributeBases = builder.attributes;
        executionMode = builder.executionMode;
        explicitExecutionMode = builder.explicitExecutionMode;
        requiresNetwork = builder.requiresNetwork;
        executionPolicy = builder.executionPolicy;
        controllerModel = builder.controllerModel;
        controllerInventorySlots = builder.controllerInventorySlots;
        controllerInventorySize = builder.controllerInventorySize;
    }

    public static Builder builder(ResourceLocation id, LocalizationEnum displayName, ResourceLocation structureId) {
        return new Builder(id, displayName, structureId);
    }

    public List<Consumer<NetworkDeviceEvent>> networkEvents() {
        return networkEvents;
    }

    public List<ComputerInputRecipe> computerInputRecipes() {
        return computerInputRecipes;
    }

    /**
     * Pure owner-thread callback; the returned count must fit the default sixteen-slot grid.
     */
    public int controllerInventorySlots(@Nullable MachineInstance machine) {
        return machine == null || controllerInventorySize == null ? controllerInventorySlots
            : ControllerInventory.requireSlotCount(controllerInventorySize.applyAsInt(machine));
    }

    public ResourceLocation id() {
        return id;
    }

    /**
     * Server containers use text(); clients use getLocal() on the same localization contract.
     */
    @SuppressWarnings("unused") // Public display binding for downstream controllers and GUI factories.
    public LocalizationEnum displayName() {
        return displayName;
    }

    public ResourceLocation structureId() {
        return structureId;
    }

    public List<ComponentType<MachineInstance, ?>> componentTypes() {
        return componentTypes;
    }

    /**
     * Groups for the implicit main slot; empty groups add no filter within the selected pool scope.
     */
    public Set<ResourceLocation> recipeGroups() {
        return recipeGroups;
    }

    /**
     * Static pool references resolved together after all recipe registration; order does not reorder recipes.
     */
    public List<ResourceLocation> recipePoolIds() {
        return recipePoolIds;
    }

    public List<WorkSlotDefinition> workSlots() {
        return workSlots;
    }

    @SuppressWarnings("unused") // Assembly applies these public defaults after restoring persisted attributes.
    public Object2DoubleMap<ResourceLocation> defaultAttributeBases() {
        return defaultAttributeBases;
    }

    public ExecutionMode executionMode() {
        return executionMode;
    }

    /**
     * Explicit declaration overrides the actual runtime controller's default scheduling choice.
     */
    public boolean hasExplicitExecutionMode() {
        return explicitExecutionMode;
    }

    /**
     * Returns -1 for the runtime role/server default, 0 for offline operation, or 1 for required networking.
     */
    public int networkRequirement() {
        return requiresNetwork;
    }

    /**
     * Null retains the existing execution-mode selection and the runtime controller's default.
     */
    @Nullable
    public ControllerExecutionPolicy executionPolicy() {
        return executionPolicy;
    }

    @SuppressWarnings("unused") // Client model registration binds separately without loading client classes here.
    @Nullable
    public ResourceLocation controllerModel() {
        return controllerModel;
    }

    /**
     * No component factory runs here, and no work-slot or recipe-scanning component is implicit.
     */
    public static final class Builder {
        private final List<Consumer<NetworkDeviceEvent>> networkEvents = new ObjectArrayList<>();
        private final List<ComputerInputRecipe> computerInputRecipes = new ObjectArrayList<>();

        public Builder networkEvent(Consumer<NetworkDeviceEvent> listener) {
            networkEvents.add(listener);
            return this;
        }

        @SuppressWarnings("UnusedReturnValue")
        public Builder computerInputRecipe(ComputerInputRecipe recipe) {
            computerInputRecipes.add(recipe);
            if (!components.containsKey(ComputerInputWork.TYPE.id())) component(ComputerInputWork.TYPE);
            return this;
        }

        private final ResourceLocation id;
        private final LocalizationEnum displayName;
        private final ResourceLocation structureId;
        private final Object2ObjectLinkedOpenHashMap<ResourceLocation, ComponentType<MachineInstance, ?>> components = new Object2ObjectLinkedOpenHashMap<>();
        private final ObjectLinkedOpenHashSet<ResourceLocation> recipePools = new ObjectLinkedOpenHashSet<>();
        private final ObjectLinkedOpenHashSet<ResourceLocation> recipeGroups = new ObjectLinkedOpenHashSet<>();
        private final Object2ObjectLinkedOpenHashMap<String, WorkSlotDefinition> workSlots = new Object2ObjectLinkedOpenHashMap<>();
        private final Object2DoubleLinkedOpenHashMap<ResourceLocation> attributes = new Object2DoubleLinkedOpenHashMap<>();
        private ExecutionMode executionMode = ExecutionMode.CONCURRENT;
        private boolean explicitExecutionMode;
        private int requiresNetwork = -1;
        @Nullable
        private ControllerExecutionPolicy executionPolicy;
        @Nullable
        private ResourceLocation controllerModel;
        private int controllerInventorySlots;
        @Nullable
        private ToIntFunction<MachineInstance> controllerInventorySize;

        private Builder(ResourceLocation id, LocalizationEnum displayName, ResourceLocation structureId) {
            this.id = id;
            this.displayName = displayName;
            this.structureId = structureId;
        }

        public Builder component(ComponentType<MachineInstance, ?> type) {
            if (components.putIfAbsent(type.id(), type) != null) {
                throw new IllegalArgumentException("Duplicate machine component ID: " + type.id());
            }
            return this;
        }

        /**
         * Declares this machine as a static user of the pool, including addon pools registered later.
         */
        public Builder recipePool(ResourceLocation poolId) {
            if (!recipePools.add(poolId)) {
                throw new IllegalArgumentException("Duplicate machine recipe pool: " + poolId);
            }
            return this;
        }

        public Builder recipeGroup(ResourceLocation group) {
            if (!recipeGroups.add(group)) {
                throw new IllegalArgumentException("Duplicate machine recipe group: " + group);
            }
            return this;
        }

        public Builder workSlot(WorkSlotDefinition definition) {
            if (workSlots.putIfAbsent(definition.name(), definition) != null) {
                throw new IllegalArgumentException("Duplicate machine work slot: " + definition.name());
            }
            return this;
        }

        public Builder attribute(ResourceLocation attribute, double base) {
            if (!Double.isFinite(base)) {
                throw new IllegalArgumentException("Default attribute base must be finite");
            }
            if (attributes.containsKey(attribute)) {
                throw new IllegalArgumentException("Duplicate default attribute: " + attribute);
            }
            attributes.put(attribute, base);
            return this;
        }

        public Builder executionMode(ExecutionMode mode) {
            executionMode = mode;
            explicitExecutionMode = true;
            return this;
        }

        public Builder requiresNetwork(boolean required) {
            requiresNetwork = required ? 1 : 0;
            return this;
        }

        public Builder executionPolicy(ControllerExecutionPolicy policy) {
            executionPolicy = policy;
            return this;
        }

        @SuppressWarnings("UnusedReturnValue") // Scripts use the setter effect; Java addons may chain declarations.
        public Builder controllerModel(@Nullable ResourceLocation model) {
            controllerModel = model;
            return this;
        }

        /**
         * Sets the fixed fallback without removing an installed dynamic callback. Default is zero.
         */
        @SuppressWarnings("UnusedReturnValue") // Java declarations can chain the same setter used by scripts.
        public Builder controllerInventorySlots(int count) {
            controllerInventorySlots = ControllerInventory.requireSlotCount(count);
            return this;
        }

        /**
         * Null removes the dynamic callback and restores use of the configured fixed count.
         */
        @SuppressWarnings("UnusedReturnValue") // Java addons can chain the dynamic setter used by scripts.
        public Builder controllerInventorySlots(@Nullable ToIntFunction<MachineInstance> count) {
            controllerInventorySize = count;
            return this;
        }

        /**
         * Transfers declaration collections; do not modify this builder after building.
         */
        public MachineType build() {
            return new MachineType(this);
        }
    }
}
