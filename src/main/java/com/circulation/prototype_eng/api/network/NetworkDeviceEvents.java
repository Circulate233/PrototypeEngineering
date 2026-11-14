package com.circulation.prototype_eng.api.network;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;
import java.util.function.Consumer;

public final class NetworkDeviceEvents {
    private static final List<Consumer<NetworkDeviceEvent>> LISTENERS = new ObjectArrayList<>();

    private NetworkDeviceEvents() {
    }

    /**
     * 在定义注册阶段调用，世界运行期间不得更改监听列表。
     */
    public static void register(Consumer<NetworkDeviceEvent> listener) {
        LISTENERS.add(listener);
    }

    public static void fire(NetworkDeviceEvent event) {
        for (var listener : LISTENERS) listener.accept(event);
        event.machine().onNetworkEvent(event);
        event.values().validate();
    }
}
