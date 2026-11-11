package com.circulation.prototype_eng.api.registration;

import com.circulation.prototype_eng.api.machine.MachineRuntimeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistrationEvent;
import com.circulation.prototype_eng.api.recipe.RecipeDeclarationRegistrationEvent;
import com.circulation.prototype_eng.api.recipe.RecipeRegistrationEvent;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Deferred declarations share the existing synchronous Java registration events and their validation.
 * Each phase closes before delivering callbacks. Startup definitions cannot be replayed as a reload.
 */
public final class DefinitionRegistrations {
    private static final Phase<MachineTypeRegistrationEvent> MACHINES = new Phase<>("machine types (#loader preinit)");
    private static final Phase<RecipeRegistrationEvent> RECIPES = new Phase<>("recipes");
    private static final Phase<RecipeDeclarationRegistrationEvent> DECLARATIONS = new Phase<>("recipe declaration manifest");
    private static final Phase<MachineRuntimeRegistrationEvent> RUNTIME = new Phase<>("machine runtime");

    private DefinitionRegistrations() {
    }

    public static void machines(Consumer<MachineTypeRegistrationEvent> declaration) {
        MACHINES.add(declaration);
    }

    public static void recipes(Consumer<RecipeRegistrationEvent> declaration) {
        RECIPES.add(declaration);
    }

    public static void runtime(Consumer<MachineRuntimeRegistrationEvent> declaration) {
        RUNTIME.add(declaration);
    }

    public static void declarations(Consumer<RecipeDeclarationRegistrationEvent> declaration) {
        DECLARATIONS.add(declaration);
    }

    public static void dispatch(MachineTypeRegistrationEvent event) {
        MACHINES.deliver(event);
    }

    public static void dispatch(RecipeRegistrationEvent event) {
        RECIPES.deliver(event);
    }

    public static void dispatch(MachineRuntimeRegistrationEvent event) {
        RUNTIME.deliver(event);
    }

    public static void dispatch(RecipeDeclarationRegistrationEvent event) {
        DECLARATIONS.deliver(event);
    }

    static final class Phase<E> {
        private final String name;
        private final ObjectArrayList<Consumer<E>> declarations = new ObjectArrayList<>();
        private boolean sealed;

        Phase(String name) {
            this.name = name;
        }

        synchronized void add(Consumer<E> declaration) {
            if (sealed) throw new IllegalStateException("Definition registration is closed for " + name);
            declarations.add(Objects.requireNonNull(declaration));
        }

        void deliver(E event) {
            Objects.requireNonNull(event);
            ObjectArrayList<Consumer<E>> callbacks;
            synchronized (this) {
                if (sealed) throw new IllegalStateException("Definition registration was already delivered for " + name);
                sealed = true;
                callbacks = new ObjectArrayList<>(declarations);
                declarations.clear();
            }
            for (var callback : callbacks) callback.accept(event);
        }
    }
}
