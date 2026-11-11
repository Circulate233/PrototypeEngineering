package com.circulation.prototype_eng.machine.data;

import com.circulation.prototype_eng.api.data.ComponentData;
import com.circulation.prototype_eng.api.data.DataComponents;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** Called only by the joined machine owner; process identity survives a slot transfer, never replacement. */
public final class MachineDataSnapshots {
    private final Reference2LongOpenHashMap<MachineWorkSlots> hosts = new Reference2LongOpenHashMap<>();
    private final Reference2LongOpenHashMap<RecipeProcess> processes = new Reference2LongOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<ComponentData, ComponentData.Snapshot> dataFacts = new Reference2ObjectOpenHashMap<>();
    private long nextIdentity;
    private long revision;
    @Nullable
    private MachineInstance owner;
    @Nullable
    private MachineDataSnapshot previous;

    public MachineDataSnapshot observe(@Nullable MachineInstance machine, long generation) {
        if (machine != null) machine.requirePersistenceBoundary();
        if (machine != owner) {
            owner = machine;
            hosts.clear();
            processes.clear();
            dataFacts.clear();
        }
        var liveHosts = new ReferenceOpenHashSet<MachineWorkSlots>();
        var liveProcesses = new ReferenceOpenHashSet<RecipeProcess>();
        var liveData = new ReferenceOpenHashSet<ComponentData>();
        var captured = new ObjectArrayList<MachineDataSnapshot.Host>();
        if (machine != null) {
            for (var host : machine.recipeHosts()) {
                if (host.owner() != machine) throw new IllegalStateException("Foreign machine data host");
                liveHosts.add(host);
                long hostId = hosts.getLong(host);
                if (hostId == 0) {
                    hostId = nextIdentity = Math.incrementExact(nextIdentity);
                    hosts.put(host, hostId);
                }
                var slots = new ObjectArrayList<MachineDataSnapshot.Slot>();
                for (var slot : host.slots()) {
                    var process = slot.process();
                    MachineDataSnapshot.Process fact = null;
                    if (process != null) {
                        if (process.owner() != machine || process.host() != host || !liveProcesses.add(process))
                            throw new IllegalStateException("Machine data process lost exact slot ownership");
                        long processId = processes.getLong(process);
                        if (processId == 0) {
                            processId = nextIdentity = Math.incrementExact(nextIdentity);
                            processes.put(process, processId);
                        }
                        var data = process.components().get(DataComponents.PROCESS);
                        fact = new MachineDataSnapshot.Process(processId, process.recipe().id(), observeData(data, liveData));
                    }
                    slots.add(new MachineDataSnapshot.Slot(slot.name(), fact));
                }
                captured.add(new MachineDataSnapshot.Host(hostId, slots));
            }
        }
        hosts.keySet().retainAll(liveHosts);
        processes.keySet().retainAll(liveProcesses);
        var component = machine == null ? null : machine.components().get(DataComponents.MACHINE);
        var data = observeData(component, liveData);
        dataFacts.keySet().retainAll(liveData);
        var frozen = List.copyOf(captured);
        if (previous != null && previous.generation() == generation && Objects.equals(previous.machine(), data) && previous.hosts().equals(frozen))
            return previous;
        revision = Math.incrementExact(revision);
        previous = new MachineDataSnapshot(generation, revision, data, frozen);
        return previous;
    }

    /** A reused fact already owns a consistent revision/NBT pair; only a changed component is copied. */
    private @Nullable ComponentData.Snapshot observeData(@Nullable ComponentData component, ReferenceOpenHashSet<ComponentData> live) {
        if (component == null) return null;
        live.add(component);
        var fact = dataFacts.get(component);
        if (fact != null && fact.revision() == component.revision()) return fact;
        fact = component.snapshot();
        dataFacts.put(component, fact);
        return fact;
    }

    public void clear() {
        hosts.clear();
        processes.clear();
        dataFacts.clear();
        owner = null;
        previous = null;
    }
}
