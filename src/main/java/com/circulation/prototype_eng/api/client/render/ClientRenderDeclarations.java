package com.circulation.prototype_eng.api.client.render;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Resource-only startup declarations may be queued on either side; only the client delivers them.
 */
public final class ClientRenderDeclarations {
    private static final ObjectArrayList<Consumer<MachineRenderRegistrationEvent>> DECLARATIONS = new ObjectArrayList<>();
    private static boolean sealed;

    private ClientRenderDeclarations() {
    }

    public static synchronized void register(Consumer<MachineRenderRegistrationEvent> declaration) {
        if (sealed) throw new IllegalStateException("Client render declaration registration is closed");
        DECLARATIONS.add(Objects.requireNonNull(declaration));
    }

    /**
     * Closes before callbacks; reentry and script reload cannot replay startup declarations.
     */
    public static void dispatch(MachineRenderRegistrationEvent event) {
        Objects.requireNonNull(event);
        ObjectArrayList<Consumer<MachineRenderRegistrationEvent>> callbacks;
        synchronized (ClientRenderDeclarations.class) {
            if (sealed) throw new IllegalStateException("Client render declarations were already delivered");
            sealed = true;
            callbacks = new ObjectArrayList<>(DECLARATIONS);
            DECLARATIONS.clear();
        }
        for (var callback : callbacks) callback.accept(event);
    }
}
