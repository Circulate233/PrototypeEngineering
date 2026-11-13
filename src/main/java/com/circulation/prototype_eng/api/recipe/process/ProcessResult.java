package com.circulation.prototype_eng.api.recipe.process;

import it.unimi.dsi.fastutil.objects.ObjectLists;

import java.util.List;
import java.util.Objects;

/**
 * Expected business results. Argument lists are borrowed immutable values for the result's lifetime;
 * callers must not modify them after construction. Broken contracts are never translated into Blocked.
 */
public sealed interface ProcessResult {
    ProcessResult SUCCESS = Success.INSTANCE;

    enum Success implements ProcessResult {INSTANCE}

    record Blocked(String reason, List<String> arguments) implements ProcessResult {
        public Blocked {
            Objects.requireNonNull(reason);
        }

        public Blocked(String reason) {
            this(reason, ObjectLists.emptyList());
        }
    }

    record Failure(String reason, List<String> arguments) implements ProcessResult {
        public Failure {
            Objects.requireNonNull(reason);
        }

        public Failure(String reason) {
            this(reason, ObjectLists.emptyList());
        }
    }
}
