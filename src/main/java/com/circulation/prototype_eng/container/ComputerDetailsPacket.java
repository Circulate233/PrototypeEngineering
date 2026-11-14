package com.circulation.prototype_eng.container;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import ae2.container.guisync.PacketWritable;
import com.circulation.prototype_eng.api.network.resource.NetworkResourcePackets;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * 列表与资源定义在发送前必须完成。null 列表表示定义未变，配方状态按索引发送增量。
 */
public record ComputerDetailsPacket(@Nullable List<Component> components, @Nullable List<Recipe> recipes,
                                    List<RecipeState> changed) implements PacketWritable {
    public static final ComputerDetailsPacket EMPTY = new ComputerDetailsPacket(null, null, List.of());

    @SuppressWarnings("unused") // AE GuiSync 调用具体包类型的 ByteBuf 构造器。
    public ComputerDetailsPacket(ByteBuf data) {
        this(readComponents(new PacketBuffer(data)), readRecipes(new PacketBuffer(data)), readStates(new PacketBuffer(data)));
    }

    private static @Nullable List<Component> readComponents(PacketBuffer data) {
        if (!data.readBoolean()) return null;
        int count = data.readVarInt();
        var rows = new ObjectArrayList<Component>(count);
        for (int index = 0; index < count; index++)
            rows.add(new Component(Objects.requireNonNull(AEKey.readKey(data)), data.readVarLong(), data.readVarLong(),
                data.readVarLong(), readResources(data), readResources(data)));
        return rows;
    }

    private static @Nullable List<Recipe> readRecipes(PacketBuffer data) {
        if (!data.readBoolean()) return null;
        int count = data.readVarInt();
        var rows = new ObjectArrayList<Recipe>(count);
        for (int index = 0; index < count; index++) {
            var id = new ResourceLocation(data.readString(32767));
            int inputCount = data.readVarInt();
            var inputs = new ObjectArrayList<GenericStack>(inputCount);
            for (int input = 0; input < inputCount; input++)
                inputs.add(Objects.requireNonNull(GenericStack.readBuffer(data)));
            rows.add(new Recipe(id, inputs, readResources(data)));
        }
        return rows;
    }

    private static Reference2DoubleMap<NetworkResourceType> readResources(PacketBuffer data) {
        int count = data.readVarInt();
        var resources = new Reference2DoubleOpenHashMap<NetworkResourceType>(count);
        for (int index = 0; index < count; index++) resources.put(NetworkResourcePackets.read(data), data.readDouble());
        return resources;
    }

    private static List<RecipeState> readStates(PacketBuffer data) {
        int count = data.readVarInt();
        var states = new ObjectArrayList<RecipeState>(count);
        for (int index = 0; index < count; index++) states.add(new RecipeState(data.readVarInt(), data.readByte()));
        return states;
    }

    @Override
    public void writeToPacket(ByteBuf buffer) {
        var data = new PacketBuffer(buffer);
        data.writeBoolean(components != null);
        if (components != null) {
            data.writeVarInt(components.size());
            for (var row : components) {
                AEKey.writeKey(data, row.item);
                data.writeVarLong(row.count);
                data.writeVarLong(row.concurrency);
                data.writeVarLong(row.energy);
                writeResources(data, row.provided);
                writeResources(data, row.required);
            }
        }
        data.writeBoolean(recipes != null);
        if (recipes != null) {
            data.writeVarInt(recipes.size());
            for (var row : recipes) {
                data.writeString(row.id.toString());
                data.writeVarInt(row.inputs.size());
                for (var input : row.inputs) GenericStack.writeBuffer(input, data);
                writeResources(data, row.provided);
            }
        }
        data.writeVarInt(changed.size());
        for (var state : changed) {
            data.writeVarInt(state.index);
            data.writeByte(state.state);
        }
    }

    private static void writeResources(PacketBuffer data, Reference2DoubleMap<NetworkResourceType> resources) {
        data.writeVarInt(resources.size());
        for (var entry : resources.reference2DoubleEntrySet()) {
            NetworkResourcePackets.write(data, entry.getKey());
            data.writeDouble(entry.getDoubleValue());
        }
    }

    public record Component(AEKey item, long count, long concurrency, long energy,
                            Reference2DoubleMap<NetworkResourceType> provided,
                            Reference2DoubleMap<NetworkResourceType> required) {
    }

    public record Recipe(ResourceLocation id, List<GenericStack> inputs,
                         Reference2DoubleMap<NetworkResourceType> provided) {
    }

    public record RecipeState(int index, byte state) {
    }
}
