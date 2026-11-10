package com.circulation.prototype_eng.client.preview;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Objects;

/** True next-tick client open queue: chat closes after executing its command. */
public final class PreviewClientOpenQueue {
    public static final PreviewClientOpenQueue INSTANCE = new PreviewClientOpenQueue();
    private final ObjectArrayList<Runnable> pending = new ObjectArrayList<>();

    private PreviewClientOpenQueue() {
    }

    public void enqueue(Runnable action) {
        pending.add(Objects.requireNonNull(action));
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pending.isEmpty()) return;
        var ready = new ObjectArrayList<>(pending);
        pending.clear();
        for (var action : ready) action.run();
    }
}
