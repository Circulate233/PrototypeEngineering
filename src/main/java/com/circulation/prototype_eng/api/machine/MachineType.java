package com.circulation.prototype_eng.api.machine;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.component.ComponentMap;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import it.unimi.dsi.fastutil.objects.Object2DoubleLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable machine declaration shared by registration, assembly and later controller bindings.
 * A structure ID permits declaration before Forge block resolution. Neither this definition nor
 * its resolved structure grants world, owner, scheduler or planned-execution authority.
 */
public final class MachineType {
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
    @Nullable
    private final ResourceLocation controllerModel;

    private MachineType(Builder builder) {
        id = builder.id;
        displayName = builder.displayName;
        structureId = builder.structureId;
        componentTypes = ObjectLists.unmodifiable(new ObjectArrayList<>(builder.components.values()));
        ComponentMap.validateTypes(componentTypes);
        recipePoolIds = ObjectLists.unmodifiable(new ObjectArrayList<>(builder.recipePools));
        recipeGroups = ObjectSets.unmodifiable(new ObjectLinkedOpenHashSet<>(builder.recipeGroups));
        workSlots = ObjectLists.unmodifiable(new ObjectArrayList<>(builder.workSlots.values()));
        defaultAttributeBases = Object2DoubleMaps.unmodifiable(new Object2DoubleLinkedOpenHashMap<>(builder.attributes));
        executionMode = builder.executionMode;
        explicitExecutionMode = builder.explicitExecutionMode;
        controllerModel = builder.controllerModel;
    }

    public static Builder builder(ResourceLocation id, LocalizationEnum displayName, ResourceLocation structureId) {
        return new Builder(id, displayName, structureId);
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

    /** Explicit declaration overrides the actual runtime controller's default scheduling choice. */
    public boolean hasExplicitExecutionMode() {
        return explicitExecutionMode;
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
        @Nullable
        private ResourceLocation controllerModel;

        private Builder(ResourceLocation id, LocalizationEnum displayName, ResourceLocation structureId) {
            this.id = Objects.requireNonNull(id, "machine ID");
            this.displayName = Objects.requireNonNull(displayName, "display name");
            this.structureId = Objects.requireNonNull(structureId, "structure ID");
        }

        public Builder component(ComponentType<MachineInstance, ?> type) {
            Objects.requireNonNull(type, "component type");
            if (components.putIfAbsent(type.id(), type) != null) {
                throw new IllegalArgumentException("Duplicate machine component ID: " + type.id());
            }
            return this;
        }

        /**
         * Declares this machine as a static user of the pool, including addon pools registered later.
         */
        public Builder recipePool(ResourceLocation poolId) {
            if (!recipePools.add(Objects.requireNonNull(poolId, "recipe pool"))) {
                throw new IllegalArgumentException("Duplicate machine recipe pool: " + poolId);
            }
            return this;
        }

        public Builder recipeGroup(ResourceLocation group) {
            if (!recipeGroups.add(Objects.requireNonNull(group, "recipe group"))) {
                throw new IllegalArgumentException("Duplicate machine recipe group: " + group);
            }
            return this;
        }

        public Builder workSlot(WorkSlotDefinition definition) {
            Objects.requireNonNull(definition, "work slot");
            if (workSlots.putIfAbsent(definition.name(), definition) != null) {
                throw new IllegalArgumentException("Duplicate machine work slot: " + definition.name());
            }
            return this;
        }

        public Builder attribute(ResourceLocation attribute, double base) {
            Objects.requireNonNull(attribute, "attribute ID");
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
            executionMode = Objects.requireNonNull(mode);
            explicitExecutionMode = true;
            return this;
        }

        @SuppressWarnings("UnusedReturnValue") // Scripts use the setter effect; Java addons may chain declarations.
        public Builder controllerModel(@Nullable ResourceLocation model) {
            controllerModel = model;
            return this;
        }

        public MachineType build() {
            return new MachineType(this);
        }
    }
}
