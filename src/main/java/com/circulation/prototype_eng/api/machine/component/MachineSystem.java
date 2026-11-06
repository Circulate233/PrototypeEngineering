package com.circulation.prototype_eng.api.machine.component;

import com.circulation.prototype_eng.api.component.ComponentSystem;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.event.MachineEvent;
import com.circulation.prototype_eng.api.machine.event.MachineTickEvent;

/** PM's tick-to-event adapter. A concrete system may override phase methods directly. */
public interface MachineSystem<C> extends ComponentSystem<MachineInstance, C> {
    @Override
    default void onPreTick(MachineInstance machine, C component) { onEvent(machine, component, new MachineTickEvent.Pre(machine)); }

    @Override
    default void onTick(MachineInstance machine, C component) { onEvent(machine, component, new MachineTickEvent.Normal(machine)); }

    @Override
    default void onPostTick(MachineInstance machine, C component) { onEvent(machine, component, new MachineTickEvent.Post(machine)); }

    default void onEvent(MachineInstance machine, C component, MachineEvent event) {}
}
