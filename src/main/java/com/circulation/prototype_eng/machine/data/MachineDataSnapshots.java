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

import java.util.Objects;

/**
 * Called only by the joined machine owner; process identity survives a slot transfer, never replacement.
 */
public final class MachineDataSnapshots {
    private final Reference2LongOpenHashMap<MachineWorkSlots> hosts = new Reference2LongOpenHashMap<>();
    private final Reference2LongOpenHashMap<RecipeProcess> processes = new Reference2LongOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<ComponentData, ComponentData.Snapshot> dataFacts = new Reference2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<RecipeProcess, ComponentData> processData = new Reference2ObjectOpenHashMap<>();
    private final ReferenceOpenHashSet<MachineWorkSlots> liveHosts = new ReferenceOpenHashSet<>();
    private final ReferenceOpenHashSet<RecipeProcess> liveProcesses = new ReferenceOpenHashSet<>();
    private final ReferenceOpenHashSet<ComponentData> liveData = new ReferenceOpenHashSet<>();
    private ComponentData machineData;
    private long rosterRevision;
    private long nextIdentity;
    private long revision;
    @Nullable
    private MachineInstance owner;
    @Nullable
    private MachineDataSnapshot previous;

    public MachineDataSnapshot observe(@Nullable MachineInstance machine, long generation) {
        if (machine != null) machine.requirePersistenceBoundary();
        var component = machine == null ? null : machine.components().get(DataComponents.MACHINE);
        long roster = machine == null ? 0 : machine.recipeRosterRevision();
        if (machine == owner && previous != null && previous.generation() == generation && rosterRevision == roster
            && component == machineData && unchanged(component)) {
            boolean current = true;
            for (var process : processes.keySet()) {
                var data = process.components().get(DataComponents.PROCESS);
                if (data != processData.get(process) || !unchanged(data)) {
                    current = false;
                    break;
                }
            }
            if (current) return previous;
        }
        if (machine != owner) {
            owner = machine;
            hosts.clear();
            processes.clear();
            dataFacts.clear();
            processData.clear();
        }
        liveHosts.clear();
        liveProcesses.clear();
        liveData.clear();
        var captured = new ObjectArrayList<MachineDataSnapshot.Host>();
        if (machine != null) {
            var elements1 = machine.recipeHosts();
            for (int index1 = 0; index1 < elements1.size(); index1++) {
                var host = elements1.get(index1);
                liveHosts.add(host);
                long hostId = hosts.getLong(host);
                if (hostId == 0) {
                    hostId = nextIdentity = Math.incrementExact(nextIdentity);
                    hosts.put(host, hostId);
                }
                var slots = new ObjectArrayList<MachineDataSnapshot.Slot>(host.slots().size());

                var elements = host.slots();
                for (int index = 0; index < elements.size(); index++) {
                    var slot = elements.get(index);
                    var process = slot.process();
                    MachineDataSnapshot.Process fact = null;
                    if (process != null) {
                        liveProcesses.add(process);
                        long processId = processes.getLong(process);
                        if (processId == 0) {
                            processId = nextIdentity = Math.incrementExact(nextIdentity);
                            processes.put(process, processId);
                        }
                        var data = process.components().get(DataComponents.PROCESS);
                        processData.put(process, data);
                        fact = new MachineDataSnapshot.Process(processId, process.recipe().id(), observeData(data, liveData));
                    }
                    slots.add(new MachineDataSnapshot.Slot(slot.name(), fact));
                }

                captured.add(new MachineDataSnapshot.Host(hostId, slots));
            }
        }
        hosts.keySet().retainAll(liveHosts);
        processes.keySet().retainAll(liveProcesses);
        processData.keySet().retainAll(liveProcesses);
        var data = observeData(component, liveData);
        dataFacts.keySet().retainAll(liveData);
        machineData = component;
        rosterRevision = roster;
        if (previous != null && previous.generation() == generation && Objects.equals(previous.machine(), data) && previous.hosts().equals(captured))
            return previous;
        revision = Math.incrementExact(revision);
        previous = new MachineDataSnapshot(generation, revision, data, captured);
        return previous;
    }

    private boolean unchanged(@Nullable ComponentData data) {
        if (data == null) return true;
        var fact = dataFacts.get(data);
        return fact != null && fact.revision() == data.revision();
    }

    /**
     * A reused fact already owns a consistent revision/NBT pair; only a changed component is copied.
     */
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
        processData.clear();
        liveHosts.clear();
        liveProcesses.clear();
        liveData.clear();
        machineData = null;
        owner = null;
        previous = null;
    }
}
