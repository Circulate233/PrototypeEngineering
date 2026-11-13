package com.circulation.prototype_eng.api.storage;

import ae2.api.stacks.AEKey2LongMap;
import ae2.api.util.AEColor;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Colors and explicit slot partitions stay separate while sharing unpartitioned uncolored storage.
 * Captures borrow published partition lists. All returned binding/group lists are read-only.
 */
public final class ResourceInputGroups {
    private ResourceInputGroups() {
    }

    public static Capture capture(MachineHatches root) {
        var bindings = new ObjectArrayList<Binding>();
        var expanded = new ObjectArrayList<Binding>();
        for (var hatch : root.resources()) {
            var partitions = hatch.inputPartitions();
            var provider = new Binding(hatch, hatch.inputGroup(), hatch.sharedInput(), partitions);
            bindings.add(provider);
            for (var partition : partitions) {
                var binding = partition == hatch ? provider
                    : new Binding(partition, partition.inputGroup(), partition.sharedInput(), null);
                expanded.add(binding);
                if (partition != hatch) bindings.add(binding);
            }
        }
        var groups = new Object2ObjectLinkedOpenHashMap<ResourceInputGroup, ObjectArrayList<ResourceHatch>>();
        for (var binding : expanded) {
            if (binding.shared()) continue;
            // Empty/temporarily absent input views must remain observed by the quantity capture.
            // Their later appearance is a binding change, not evidence that the whole group is empty.
            groups.computeIfAbsent(binding.group(), _ -> new ObjectArrayList<>());
        }
        if (groups.isEmpty()) groups.put(ResourceInputGroup.TRANSPARENT, new ObjectArrayList<>());
        var partitioned = new Object2ObjectAVLTreeMap<String, ObjectArrayList<ResourceHatch>>();
        groups.forEach((group, members) -> {
            if (!group.partition().isEmpty()) partitioned.put(group.partition(), members);
        });
        // Distribute in source order. Only shared prefixes visit multiple groups; distinct pattern
        // slots reach their exact group directly instead of rescanning all slots for every group.
        for (var binding : expanded) {
            var other = binding.group();
            var view = new GroupView(binding.hatch(), other);
            if (other.partition().isEmpty() && other.color() == AEColor.TRANSPARENT) {
                for (var members : groups.values()) members.add(view);
            } else if (binding.shared() && !other.partition().isEmpty()) {
                for (var entry : partitioned.tailMap(other.partition()).object2ObjectEntrySet()) {
                    if (!entry.getKey().startsWith(other.partition())) break;
                    entry.getValue().add(view);
                }
            } else {
                var members = groups.get(other);
                if (members != null) members.add(view);
            }
        }
        var captures = new ObjectArrayList<Group>();
        groups.forEach((key, members) -> captures.add(new Group(key,
            new MachineHatches(members, root.energyInputs(), root.energyOutputs()))));
        return new Capture(root, bindings, captures);
    }

    public record Group(ResourceInputGroup id, MachineHatches hatches) {
    }

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

    /**
     * Only providers own a partition list; their already expanded leaf views are not recursive.
     */
    public record Binding(ResourceHatch hatch, ResourceInputGroup group, boolean shared,
                          @Nullable List<? extends ResourceHatch> partitions) {
        boolean current() {
            if (!hatch.inputGroup().equals(group) || hatch.sharedInput() != shared) return false;
            if (partitions == null) return true;
            var current = hatch.inputPartitions();
            if (current.size() != partitions.size()) return false;
            for (int i = 0; i < current.size(); i++) if (current.get(i) != partitions.get(i)) return false;
            return true;
        }
    }

    private record GroupView(ResourceHatch source, ResourceInputGroup captured)
        implements ResourceHatch, ResourceRecipeObserver {
        @Override
        public Object observerIdentity() {
            return source instanceof ResourceRecipeObserver observer ? observer.observerIdentity() : this;
        }

        @Override
        public void attached(RecipeProcess process) {
            if (source instanceof ResourceRecipeObserver observer) observer.attached(process);
        }

        @Override
        public void detached(RecipeProcess process) {
            if (source instanceof ResourceRecipeObserver observer) observer.detached(process);
        }

        @Override
        public void inputsCommitted(RecipeProcess process, RecipeStage stage, AEKey2LongMap amounts) {
            if (source instanceof ResourceRecipeObserver observer)
                observer.inputsCommitted(process, stage, amounts);
        }

        @Override
        public void stageCommitted(RecipeProcess process, RecipeStage stage) {
            if (source instanceof ResourceRecipeObserver observer)
                observer.stageCommitted(process, stage);
        }

        @Override
        public @Nullable ResourceStorage input() {
            return source.input();
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
            return source.inputStorageIdentity();
        }

        @Override
        public @Nullable Object outputStorageIdentity() {
            return source.outputStorageIdentity();
        }
    }
}
