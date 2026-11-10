package com.circulation.prototype_eng.api.recipe.process;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;

import java.util.List;
import java.util.Objects;

/**
 * Expected business results. Broken contracts throw; they are never translated into Blocked.
 */
public sealed interface ProcessResult {
    ProcessResult SUCCESS = Success.INSTANCE;

    enum Success implements ProcessResult {INSTANCE}

    record Blocked(String reason, List<String> arguments) implements ProcessResult {
        public Blocked {
            Objects.requireNonNull(reason);
            arguments = ObjectLists.unmodifiable(new ObjectArrayList<>(arguments));
        }

        public Blocked(String reason) {
            this(reason, ObjectLists.emptyList());
        }
    }

    record Failure(String reason, List<String> arguments) implements ProcessResult {
        public Failure {
            Objects.requireNonNull(reason);
            arguments = ObjectLists.unmodifiable(new ObjectArrayList<>(arguments));
        }

        public Failure(String reason) {
            this(reason, ObjectLists.emptyList());
        }
    }
}
