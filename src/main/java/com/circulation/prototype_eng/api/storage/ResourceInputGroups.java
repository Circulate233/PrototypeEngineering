package com.circulation.prototype_eng.api.storage;

import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** Immutable input groups keep colors and pattern partitions apart through admission and work. */
public final class ResourceInputGroups {
    private ResourceInputGroups() {}

    public static Capture capture(MachineHatches root) {
        var bindings = new ObjectArrayList<Binding>();
        var expanded = new ObjectArrayList<ResourceHatch>();
        for (var hatch : root.resources()) {
            var partitions = List.copyOf(hatch.inputPartitions());
            bindings.add(new Binding(hatch, hatch.inputGroup(), hatch.sharedInput(), partitions));
            for (var partition : partitions) {
                expanded.add(Objects.requireNonNull(partition));
                if (partition != hatch)
                    bindings.add(new Binding(partition, partition.inputGroup(), partition.sharedInput(), List.of(partition)));
            }
        }
        var groups = new Object2ObjectLinkedOpenHashMap<ResourceInputGroup, ObjectArrayList<ResourceHatch>>();
        for (var hatch : expanded) {
            if (hatch.sharedInput()) continue;
            var group = hatch.inputGroup();
            // Empty/temporarily absent input views must remain observed by the quantity capture.
            // Their later appearance is a binding change, not evidence that the whole group is empty.
            groups.computeIfAbsent(group, _ -> new ObjectArrayList<>());
        }
        if (groups.isEmpty()) groups.put(ResourceInputGroup.RED, new ObjectArrayList<>());
        groups.forEach((group, members) -> {
            for (var hatch : expanded) {
                var other = hatch.inputGroup();
                boolean input = other.equals(group) || hatch.sharedInput() && other.color() == group.color()
                    && group.partition().startsWith(other.partition());
                boolean output = other.color() == group.color() && hatch.output() != null;
                if (input || output) members.add(new GroupView(hatch, other, input));
            }
        });
        var captures = new ObjectArrayList<Group>();
        groups.forEach((key, members) -> captures.add(new Group(key,
            new MachineHatches(members, root.energyInputs(), root.energyOutputs()))));
        return new Capture(root, ObjectLists.unmodifiable(bindings), ObjectLists.unmodifiable(captures));
    }

    public record Group(ResourceInputGroup id, MachineHatches hatches) {}

    public record Capture(MachineHatches root, List<Binding> bindings, List<Group> groups) {
        public boolean current(MachineHatches actual) {
            if (actual != root) return false;
            for (var binding : bindings) if (!binding.current()) return false;
            return true;
        }

        public @Nullable Group find(ResourceInputGroup id) {
            for (var group : groups) if (group.id.equals(id)) return group;
            return null;
        }
    }

    public record Binding(ResourceHatch hatch, ResourceInputGroup group, boolean shared, List<? extends ResourceHatch> partitions) {
        boolean current() {
            if (!hatch.inputGroup().equals(group) || hatch.sharedInput() != shared) return false;
            var current = hatch.inputPartitions();
            if (current.size() != partitions.size()) return false;
            for (int i = 0; i < current.size(); i++) if (current.get(i) != partitions.get(i)) return false;
            return true;
        }
    }

    private record GroupView(ResourceHatch source, ResourceInputGroup captured, boolean includeInput)
        implements ResourceHatch, ResourceRecipeObserver {
        @Override
        public Object observerIdentity() {
            return includeInput && source instanceof ResourceRecipeObserver observer ? observer.observerIdentity() : this;
        }

        @Override
        public void attached(RecipeProcess process) {
            if (includeInput && source instanceof ResourceRecipeObserver observer) observer.attached(process);
        }

        @Override
        public void detached(RecipeProcess process) {
            if (includeInput && source instanceof ResourceRecipeObserver observer) observer.detached(process);
        }

        @Override
        public void inputsCommitted(RecipeProcess process, RecipeStage stage, AEKey2LongMap amounts) {
            if (includeInput && source instanceof ResourceRecipeObserver observer) observer.inputsCommitted(process, stage, amounts);
        }

        @Override
        public void stageCommitted(RecipeProcess process, RecipeStage stage) {
            if (includeInput && source instanceof ResourceRecipeObserver observer) observer.stageCommitted(process, stage);
        }
        @Override
        public @Nullable ResourceStorage input() {
            return includeInput ? source.input() : null;
        }

        @Override
        public @Nullable ResourceStorage output() {
            return source.output();
        }

        @Override
        public ResourceInputGroup inputGroup() {
            return captured;
        }

        @Override
        public boolean isValid() {
            return source.isValid() && captured.equals(source.inputGroup());
        }

        @Override
        public @Nullable Object inputStorageIdentity() {
            return includeInput ? source.inputStorageIdentity() : null;
        }

        @Override
        public @Nullable Object outputStorageIdentity() {
            return source.outputStorageIdentity();
        }
    }
}
