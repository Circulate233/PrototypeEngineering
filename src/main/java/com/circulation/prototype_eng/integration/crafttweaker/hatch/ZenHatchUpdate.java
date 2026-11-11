package com.circulation.prototype_eng.integration.crafttweaker.hatch;

import com.circulation.prototype_eng.api.storage.HatchConfigurationUpdates;
import crafttweaker.annotations.ZenRegister;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@ZenRegister
@ZenClass("mods.prototype_eng.hatch.Update")
@SuppressWarnings("unused") // ZenScript observes the actual asynchronous Java report.
public record ZenHatchUpdate(CompletableFuture<HatchConfigurationUpdates.Report> value) {
    public ZenHatchUpdate {
        Objects.requireNonNull(value);
    }

    @ZenGetter("done")
    public boolean done() {
        return value.isDone();
    }

    /** Pending remains explicit; completed Java failures propagate instead of becoming success. */
    @ZenGetter("result")
    @Nullable
    public ZenHatchReport result() {
        return value.isDone() ? new ZenHatchReport(value.join()) : null;
    }
}
