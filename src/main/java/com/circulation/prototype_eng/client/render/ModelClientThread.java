package com.circulation.prototype_eng.client.render;

import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The only bridge used by CPU writers for map/finish; GL operations execute on the captured client owner.
 */
public final class ModelClientThread {
    private final Thread owner;
    private final Consumer<Runnable> dispatch;
    private final ConcurrentLinkedQueue<Runnable> cleanup = new ConcurrentLinkedQueue<>();

    public ModelClientThread(Thread owner, Consumer<Runnable> dispatch) {
        this.owner = owner;
        this.dispatch = dispatch;
    }

    public static ModelClientThread minecraft() {
        var minecraft = Minecraft.getMinecraft();
        if (!minecraft.isCallingFromMinecraftThread())
            throw new IllegalStateException("Model service requires client owner");
        return new ModelClientThread(Thread.currentThread(), minecraft::addScheduledTask);
    }

    public void requireOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Model GL operation requires client owner");
    }

    public void execute(Runnable action) {
        if (Thread.currentThread() == owner) action.run();
        else {
            // Claim the cleanup before scheduling: a rejected wakeup cannot erase a native release obligation.
            cleanup.add(action);
            dispatch.accept(this::drain);
        }
    }

    public void drain() {
        requireOwner();
        Throwable failure = null;
        for (var action = cleanup.poll(); action != null; action = cleanup.poll())
            try {
                action.run();
            } catch (RuntimeException | Error exception) {
                failure = BuiltModelBuffers.append(failure, exception);
            }
        BuiltModelBuffers.rethrow(failure);
    }

    public <T> T call(Supplier<T> action) {
        if (Thread.currentThread() == owner) return action.get();
        var future = new CompletableFuture<T>();
        dispatch.accept(() -> {
            try {
                future.complete(action.get());
            } catch (RuntimeException | Error failure) {
                future.completeExceptionally(failure);
            }
        });
        boolean interrupted = false;
        try {
            for (; ; ) {
                try {
                    return future.get();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                } catch (ExecutionException failure) {
                    var cause = failure.getCause();
                    if (cause instanceof Error error) throw error;
                    if (cause instanceof RuntimeException exception) throw exception;
                    throw new IllegalStateException(cause);
                }
            }
        } finally {
            // Never abandon a scheduled map reply: it may already own a native mapping needing finish/abort.
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
