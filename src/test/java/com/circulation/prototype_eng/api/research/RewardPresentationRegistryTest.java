package com.circulation.prototype_eng.api.research;

import ae2.api.stacks.AEKeyTypes;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.research.reward.CompositeRewardPresentation;
import com.circulation.prototype_eng.api.research.reward.ItemRewardPresentation;
import com.circulation.prototype_eng.api.research.reward.RewardChild;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import com.circulation.prototype_eng.api.research.reward.RewardPresentation;
import com.circulation.prototype_eng.api.research.reward.RewardPresentationRegistry;
import com.circulation.prototype_eng.api.research.reward.RewardRenderContext;
import com.circulation.prototype_eng.api.research.reward.TextRewardPresentation;
import com.circulation.prototype_eng.testsupport.TestResources;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewardPresentationRegistryTest {
    @Test
    void independentRegistriesDecodeCustomAndBuiltInChildrenAndRenderThemInOrder() {
        var key = key();
        AEKeyTypes.register(key.getType());
        var stack = new GenericStack(key, 8);
        var source = new CompositeRewardPresentation(List.of(new ItemRewardPresentation(stack), new TextRewardPresentation("解锁制造"), new Badge("工艺图鉴")));
        var bytes = Unpooled.buffer();
        try {
            var data = new PacketBuffer(bytes);
            registry().write(data, source);
            var decoded = assertInstanceOf(CompositeRewardPresentation.class, registry().read(data));
            assertEquals(source, decoded);

            var render = new RecordedRender();
            var height = decoded.height(render, 120);
            assertTrue(render.rows.isEmpty());
            decoded.render(render, 0, 0, 120);
            assertEquals(List.of(stack, "解锁制造", "工艺图鉴"), render.rows.stream().map(Row::value).toList());
            assertTrue(render.rows.get(0).y() < render.rows.get(1).y());
            assertTrue(render.rows.get(1).y() < render.rows.get(2).y());
            assertTrue(render.rows.getLast().y() < height);
        } finally {
            bytes.release();
        }
    }

    @Test
    void defaultPresentationKeepsTheResourceAmountAndDoesNotInvokeTheRewardEvent() {
        var key = key();
        var rewardEvent = id("complete_archive");
        var group = new RewardGroup(id("archive"), List.of(new RewardChild.Item(id("archive_item"), key, 9),
            new RewardChild.Event(rewardEvent, (_, _) -> { throw new AssertionError("Rendering invoked delivery"); })));
        var render = new RecordedRender();
        group.presentation().render(render, 0, 0, 120);
        assertEquals(List.of(new GenericStack(key, 9), rewardEvent.toString()), render.rows.stream().map(Row::value).toList());
    }

    @Test
    void decodingUnknownRewardTypeFailsWithoutPreventingRegisteredTypesFromDecoding() {
        var registry = new RewardPresentationRegistry();
        var bytes = Unpooled.buffer();
        try {
            var data = new PacketBuffer(bytes);
            registry().write(data, new Badge("未知配置"));
            assertThrows(IllegalArgumentException.class, () -> registry.read(data));
            data.clear();
            var plain = new TextRewardPresentation("后续奖励");
            registry.write(data, plain);
            assertEquals(plain, registry.read(data));
        } finally {
            bytes.release();
        }
    }

    private static RewardPresentationRegistry registry() {
        var registry = new RewardPresentationRegistry();
        registry.register(Badge.TYPE, Badge.class, (buffer, badge) -> buffer.writeString(badge.name()), buffer -> new Badge(buffer.readString(128)));
        registry.freeze();
        return registry;
    }

    private static TestResources.Key key() {
        return new TestResources.Key(new TestResources.KeyType("overview_reward_presentation"), "archive");
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test_reward", path);
    }

    private record Badge(String name) implements RewardPresentation {
        private static final ResourceLocation TYPE = id("badge");

        @Override
        public ResourceLocation type() {
            return TYPE;
        }

        @Override
        public double height(RewardRenderContext context, double width) {
            return 28;
        }

        @Override
        public void render(RewardRenderContext context, double x, double y, double width) {
            context.tooltip(x, y, width, height(context, width), List.of(name));
        }
    }

    private record Row(Object value, double y) {
    }

    private static final class RecordedRender implements RewardRenderContext {
        private final List<Row> rows = new ArrayList<>();

        @Override
        public void item(GenericStack stack, double x, double y, double size) {
            rows.add(new Row(stack, y));
        }

        @Override
        public void text(String text, double x, double y, double width) {
            rows.add(new Row(text, y));
        }

        @Override
        public void tooltip(double x, double y, double width, double height, List<String> lines) {
            rows.add(new Row(lines.getFirst(), y));
        }
    }
}
