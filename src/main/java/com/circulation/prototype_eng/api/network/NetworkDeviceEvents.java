package com.circulation.prototype_eng.api.network;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;
import java.util.function.Consumer;

public final class NetworkDeviceEvents {
    private static final List<Consumer<NetworkDeviceEvent>> LISTENERS = new ObjectArrayList<>();

    private NetworkDeviceEvents() {
    }

    /**
     * Register during definition loading; do not change listeners while a world is running.
     */
    public static void register(Consumer<NetworkDeviceEvent> listener) {
        LISTENERS.add(listener);
    }

    public static void fire(NetworkDeviceEvent event) {
        for (int index = 0; index < LISTENERS.size(); index++) {
            var listener = LISTENERS.get(index);
            listener.accept(event);
        }
        event.machine().onNetworkEvent(event);
    }
}
