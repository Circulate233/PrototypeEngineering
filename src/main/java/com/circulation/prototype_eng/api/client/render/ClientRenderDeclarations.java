package com.circulation.prototype_eng.api.client.render;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.function.Consumer;

/**
 * Resource-only startup declarations may be queued on either side; only the client delivers them.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public final class ClientRenderDeclarations {
    private static final ObjectArrayList<Consumer<MachineRenderRegistrationEvent>> DECLARATIONS = new ObjectArrayList<>();
    private static boolean sealed;

    private ClientRenderDeclarations() {
    }

    public static synchronized void register(Consumer<MachineRenderRegistrationEvent> declaration) {
        if (sealed) throw new IllegalStateException("Client render declaration registration is closed");
        DECLARATIONS.add(declaration);
    }

    /**
     * Closes before callbacks; reentry and script reload cannot replay startup declarations.
     */
    public static void dispatch(MachineRenderRegistrationEvent event) {
        ObjectArrayList<Consumer<MachineRenderRegistrationEvent>> callbacks;
        synchronized (ClientRenderDeclarations.class) {
            if (sealed) throw new IllegalStateException("Client render declarations were already delivered");
            sealed = true;
            callbacks = new ObjectArrayList<>(DECLARATIONS);
            DECLARATIONS.clear();
        }
        for (int index = 0; index < callbacks.size(); index++) {
            var callback = callbacks.get(index);
            callback.accept(event);
        }
    }
}
