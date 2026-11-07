package com.circulation.prototype_eng.api.machine.lifecycle;

/**
 * Implement on a machine component or register globally. Components run in dependency order, then
 * global listeners in registration order. Both lists are captured before the first callback.
 * Load/formed faults abort publication; unload/break cleanup attempts every captured listener.
 * Unload can follow a partially failed load: cleanup must tolerate absent or partially owned state.
 */
@SuppressWarnings("unused") // Callback parameters are consumed by external listener implementations.
public interface MachineLifecycleListener {
    default void onLoaded(MachineLifecycleEvent.Loaded event) {}
    default void onUnloaded(MachineLifecycleEvent.Unloaded event) {}
    default void onFormedChanged(MachineLifecycleEvent.FormedChanged event) {}
    default void onControllerBroken(MachineLifecycleEvent.ControllerBroken event) {}

    /** Saved controller destroyed before assembly; distinct from a loaded machine's callbacks. */
    default void onUnassembledControllerBroken(MachineLifecycleEvent.UnassembledControllerBroken event) {
    }
}
