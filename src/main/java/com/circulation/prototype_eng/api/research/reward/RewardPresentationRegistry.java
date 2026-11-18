package com.circulation.prototype_eng.api.research.reward;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Register matching display codecs on both physical sides during mod initialization. */
public final class RewardPresentationRegistry {
    public static final RewardPresentationRegistry INSTANCE = new RewardPresentationRegistry();
    private static final int MAX_CHILDREN = 65_536;
    private static final int MAX_DEPTH = 32;
    private final Object2ObjectOpenHashMap<ResourceLocation, Codec<?>> codecs = new Object2ObjectOpenHashMap<>();
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
    private boolean frozen;

    public RewardPresentationRegistry() {
        register(ItemRewardPresentation.TYPE, ItemRewardPresentation.class, (buffer, value) -> {
            AEKey.writeKey(buffer, value.stack().what());
            buffer.writeVarLong(value.stack().amount());
        }, buffer -> {
            var key = AEKey.readKey(buffer);
            if (key == null) throw new IllegalArgumentException("Invalid reward display resource");
            return new ItemRewardPresentation(new GenericStack(key, buffer.readVarLong()));
        });
        register(TextRewardPresentation.TYPE, TextRewardPresentation.class,
            (buffer, value) -> buffer.writeString(value.text()), buffer -> new TextRewardPresentation(buffer.readString(32767)));
        register(CompositeRewardPresentation.TYPE, CompositeRewardPresentation.class, (buffer, value) -> {
            if (value.children().size() > MAX_CHILDREN) throw new IllegalArgumentException("Too many reward presentations");
            buffer.writeVarInt(value.children().size());
            for (var child : value.children()) write(buffer, child);
        }, buffer -> {
            int count = buffer.readVarInt();
            if (count < 0 || count > MAX_CHILDREN || count > buffer.readableBytes())
                throw new IllegalArgumentException("Invalid reward presentation count");
            var children = new ObjectArrayList<RewardPresentation>(count);
            for (int index = 0; index < count; index++) children.add(read(buffer));
            return new CompositeRewardPresentation(children);
        });
    }

    public <T extends RewardPresentation> void register(ResourceLocation id, Class<T> type,
                                                        BiConsumer<PacketBuffer, T> encoder, Function<PacketBuffer, T> decoder) {
        if (frozen) throw new IllegalStateException("Reward presentation registration has ended");
        Objects.requireNonNull(id);
        if (codecs.putIfAbsent(id, new Codec<>(type, encoder, decoder)) != null)
            throw new IllegalArgumentException("Duplicate reward presentation type: " + id);
    }

    public void freeze() {
        frozen = true;
    }

    public void write(PacketBuffer buffer, RewardPresentation presentation) {
        int entered = enter();
        try {
            var id = presentation.type();
            var codec = require(id);
            buffer.writeResourceLocation(id);
            codec.write(buffer, presentation);
        } finally {
            exit(entered);
        }
    }

    public RewardPresentation read(PacketBuffer buffer) {
        int entered = enter();
        try {
            var id = buffer.readResourceLocation();
            return require(id).read(buffer, id);
        } finally {
            exit(entered);
        }
    }

    private Codec<?> require(ResourceLocation id) {
        var codec = codecs.get(id);
        if (codec == null) throw new IllegalArgumentException("Unknown reward presentation type: " + id);
        return codec;
    }

    private int enter() {
        int entered = depth.get();
        if (entered >= MAX_DEPTH) throw new IllegalArgumentException("Reward presentations are nested too deeply");
        depth.set(entered + 1);
        return entered;
    }

    private void exit(int entered) {
        if (entered == 0) depth.remove();
        else depth.set(entered);
    }

    private record Codec<T extends RewardPresentation>(Class<T> type, BiConsumer<PacketBuffer, T> encoder,
                                                       Function<PacketBuffer, T> decoder) {
        Codec {
            Objects.requireNonNull(type);
            Objects.requireNonNull(encoder);
            Objects.requireNonNull(decoder);
        }

        void write(PacketBuffer buffer, RewardPresentation value) {
            encoder.accept(buffer, type.cast(value));
        }

        T read(PacketBuffer buffer, ResourceLocation id) {
            var value = decoder.apply(buffer);
            if (!type.isInstance(value) || !id.equals(value.type())) throw new IllegalArgumentException("Invalid reward presentation for " + id);
            return value;
        }
    }
}
