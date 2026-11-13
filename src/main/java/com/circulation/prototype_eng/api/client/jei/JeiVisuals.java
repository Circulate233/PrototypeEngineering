package com.circulation.prototype_eng.api.client.jei;

import com.circulation.prototype_eng.localization.JeiText;
import mezz.jei.api.gui.IDrawable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import javax.annotation.ParametersAreNonnullByDefault;

import java.util.Locale;
import java.util.Objects;

/**
 * Native Minecraft drawing compositions. Resource slots share AEKey rendering and generic skins.
 */
@SideOnly(Side.CLIENT)
@ParametersAreNonnullByDefault
public final class JeiVisuals {
    private static final ItemSkin ITEM_SKIN = new ItemSkin(Plaid.ITEM);
    public static final JeiSlot.Skin AUTOMATIC = ITEM_SKIN;

    private JeiVisuals() {
    }

    public static ResourceLocation texture(String path) {
        return new ResourceLocation("prototype_eng", "textures/gui/jei_recipeicons/" + path + ".png");
    }

    /**
     * End coordinates and normalized UVs; zero area is skipped rather than submitted to GL.
     */
    public static void quad(double x0, double y0, double x1, double y1, double u0, double v0, double u1, double v1) {
        if (x1 <= x0 || y1 <= y0) return;
        var tessellator = Tessellator.getInstance();
        var buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        buffer.pos(x0, y1, 0).tex(u0, v1).endVertex();
        buffer.pos(x1, y1, 0).tex(u1, v1).endVertex();
        buffer.pos(x1, y0, 0).tex(u1, v0).endVertex();
        buffer.pos(x0, y0, 0).tex(u0, v0).endVertex();
        tessellator.draw();
    }

    private static void bind(Minecraft minecraft, ResourceLocation texture) {
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.color(1, 1, 1, 1);
        minecraft.getTextureManager().bindTexture(texture);
    }

    private static void solid(double x0, double y0, double x1, double y1, int color) {
        if (x1 <= x0 || y1 <= y0) return;
        GlStateManager.disableTexture2D();
        GlStateManager.color((color >>> 16 & 255) / 255f, (color >>> 8 & 255) / 255f, (color & 255) / 255f, (color >>> 24) / 255f);
        var tessellator = Tessellator.getInstance();
        var buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        buffer.pos(x0, y1, 0).endVertex();
        buffer.pos(x1, y1, 0).endVertex();
        buffer.pos(x1, y0, 0).endVertex();
        buffer.pos(x0, y0, 0).endVertex();
        tessellator.draw();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1, 1, 1, 1);
    }

    public enum Direction {RIGHT, LEFT, UP, DOWN, CIRCULAR_CW}

    public enum Module {COMPRESS, CUT, MERGE, SPLIT, HEAT, COOL, ROLLING, RIGHT, LEFT, UP, DOWN}

    public enum Shape {ARROW, CYCLE}

    public enum Alignment {
        TOP_LEFT(0, 0), TOP_CENTER(.5, 0), TOP_RIGHT(1, 0), LEFT(0, .5), CENTER(.5, .5), RIGHT(1, .5),
        BOTTOM_LEFT(0, 1), BOTTOM_CENTER(.5, 1), BOTTOM_RIGHT(1, 1);
        final double x, y;

        Alignment(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    public enum Plaid {
        ITEM("item_module/plaid_base", "0000"), TOP_LEFT("plaid_module/base_top_0111", "1000"),
        TOP("plaid_module/base_top_0110", "1001"), TOP_RIGHT("plaid_module/base_top_0101", "1010"),
        LEFT("plaid_module/base_mid_1101", "0001"), CENTER("plaid_module/base_mid_1111", null),
        RIGHT("plaid_module/base_mid_1110", "0010"), BOTTOM_LEFT("plaid_module/base_down_1001", "0110"),
        BOTTOM("plaid_module/base_down_1011", "0100"), BOTTOM_RIGHT("plaid_module/base_down_1010", "0101");
        final Texture base;
        @Nullable
        final Texture top;

        Plaid(String base, @Nullable String top) {
            this.base = new Texture(texture(base), 18, 18);
            this.top = top == null ? null : new Texture(texture("plaid_module/" + top), 54, 54);
        }
    }

    public enum Energy {
        DEFAULT("default_in", 22, 72), ONE_1("1_1", 18, 18), ONE_2("1_2", 18, 36), ONE_3("1_3", 18, 54), ONE_4("1_4", 18, 72),
        TWO_1("2_1", 36, 18), TWO_2("2_2", 36, 36), TWO_3("2_3", 36, 54), TWO_4("2_4", 36, 72);
        final Texture empty, full;

        Energy(String key, int width, int height) {
            empty = new Texture(texture("energy_module/" + key + "_empty"), width, height);
            full = new Texture(texture("energy_module/" + key + "_full"), width, height);
        }

        /**
         * Stable role demonstration, never a live machine capacity or a JEI ingredient.
         */
        public void draw(Minecraft minecraft, int x, int y, int width, int height, @Nullable JeiRecipeDisplay.Role role, int ledYOffset) {
            empty.draw(minecraft, x, y, width, height);
            double ratio = role == JeiRecipeDisplay.Role.INPUT ? .65 : role == JeiRecipeDisplay.Role.OUTPUT ? .35 : .5;
            double insetX = 3.0 * width / empty.width, insetY = 3.0 * height / empty.height;
            double top = height - insetY - Math.floor((height - insetY * 2) * ratio);
            bind(minecraft, full.resource);
            quad(x + insetX, y + top, x + width - insetX, y + height - insetY,
                insetX / width, top / height, 1 - insetX / width, 1 - insetY / height);
            String led = role == JeiRecipeDisplay.Role.INPUT ? "input" : role == JeiRecipeDisplay.Role.OUTPUT ? "output" : "io";
            bind(minecraft, texture("energy_module/" + led + "_led"));
            double ledW = 10.0 * width / empty.width, ledH = height / (double) empty.height;
            quad(x + (width - ledW) / 2, y + ledYOffset, x + (width + ledW) / 2, y + ledYOffset + ledH, 0, 0, 1, 1);
        }
    }

    public record Texture(ResourceLocation resource, int width, int height) implements IDrawable {
        public Texture {
            Objects.requireNonNull(resource);
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid texture dimensions");
        }

        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public void draw(Minecraft minecraft, int x, int y) {
            draw(minecraft, x, y, width, height);
        }

        public void draw(Minecraft minecraft, int x, int y, int targetWidth, int targetHeight) {
            bind(minecraft, resource);
            quad(x, y, x + (double) targetWidth, y + (double) targetHeight, 0, 0, 1, 1);
        }
    }

    /**
     * Explicit source size avoids metadata caching and automatically uses resource-pack replacements.
     */
    public record NineSlice(Texture source, int width, int height, int border, boolean fillCenter,
                            int centerColor) implements IDrawable {
        public NineSlice {
            Objects.requireNonNull(source);
            if (width <= 0 || height <= 0 || border < 0 || border * 2L > Math.min(source.width, source.height)) {
                throw new IllegalArgumentException("Invalid nine-slice bounds");
            }
        }

        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public void draw(Minecraft minecraft, int x, int y) {
            bind(minecraft, source.resource);
            int dx = Math.min(border, width / 2), dy = Math.min(border, height / 2);
            for (int row = 0; row < 3; row++) {
                double y0 = row == 0 ? 0 : row == 1 ? dy : height - dy;
                double y1 = row == 0 ? dy : row == 1 ? height - dy : height;
                double v0 = row == 0 ? 0 : row == 1 ? border / (double) source.height : 1 - border / (double) source.height;
                double v1 = row == 0 ? border / (double) source.height : row == 1 ? 1 - border / (double) source.height : 1;
                for (int column = 0; column < 3; column++) {
                    double x0 = column == 0 ? 0 : column == 1 ? dx : width - dx;
                    double x1 = column == 0 ? dx : column == 1 ? width - dx : width;
                    if (column == 1 && row == 1 && fillCenter) {
                        solid(x + x0, y + y0, x + x1, y + y1, centerColor);
                        bind(minecraft, source.resource);
                    } else {
                        double u0 = column == 0 ? 0 : column == 1 ? border / (double) source.width : 1 - border / (double) source.width;
                        double u1 = column == 0 ? border / (double) source.width : column == 1 ? 1 - border / (double) source.width : 1;
                        quad(x + x0, y + y0, x + x1, y + y1, u0, v0, u1, v1);
                    }
                }
            }
        }
    }

    /**
     * Either a PM module texture or native arrow/cycle geometry, with independent fill direction.
     */
    public record Progress(@Nullable Module module, Shape shape, Direction direction, int width, int height,
                           long cycleTicks, boolean smooth, int emptyColor,
                           int fullColor) implements JeiRecipeDecorator {
        public Progress {
            Objects.requireNonNull(shape);
            Objects.requireNonNull(direction);
            if (width <= 0 || height <= 0 || cycleTicks < 0)
                throw new IllegalArgumentException("Invalid progress configuration");
        }

        public static Progress module(Module module, Direction direction) {
            return new Progress(Objects.requireNonNull(module), Shape.ARROW, direction, 18, 18, 0, true, 0xffffffff, 0xffffffff);
        }

        @Override
        public void draw(Minecraft minecraft, RecipePoolJeiRecipe recipe, int x, int y) {
            long period = cycleTicks == 0 ? Math.max(1, recipe.recipe().durationTicks()) : cycleTicks;
            double progress = minecraft.world == null ? 0 : Math.floorMod(minecraft.world.getTotalWorldTime(), period) / (double) period;
            drawProgress(minecraft, x, y, progress);
        }

        public void drawProgress(Minecraft minecraft, int x, int y, double progress) {
            double p = Double.isFinite(progress) ? Math.clamp(progress, 0, 1) : 0;
            layer(minecraft, x, y, 0, 0, width, height, false);
            if (direction == Direction.CIRCULAR_CW) {
                double halfW = width / 2.0, halfH = height / 2.0;
                double q = quantize(p * 4);
                layer(minecraft, x, y, 0, height - q * halfH, halfW, height, true);
                q = quantize(p * 4 - 1);
                layer(minecraft, x, y, 0, 0, q * halfW, halfH, true);
                q = quantize(p * 4 - 2);
                layer(minecraft, x, y, halfW, 0, width, q * halfH, true);
                q = quantize(p * 4 - 3);
                layer(minecraft, x, y, width - q * halfW, halfH, width, height, true);
            } else {
                double q = quantize(p);
                switch (direction) {
                    case RIGHT -> layer(minecraft, x, y, 0, 0, width * q, height, true);
                    case LEFT -> layer(minecraft, x, y, width * (1 - q), 0, width, height, true);
                    case UP -> layer(minecraft, x, y, 0, height * (1 - q), width, height, true);
                    case DOWN -> layer(minecraft, x, y, 0, 0, width, height * q, true);
                    case CIRCULAR_CW -> throw new AssertionError("Circular fill handled above");
                }
            }
            GlStateManager.color(1, 1, 1, 1);
            GlStateManager.enableBlend();
        }

        private double quantize(double value) {
            double q = Math.clamp(value, 0, 1);
            int steps = direction == Direction.UP || direction == Direction.DOWN ? height : width;
            return smooth ? q : Math.floor(q * steps) / steps;
        }

        private void layer(Minecraft minecraft, int x, int y, double x0, double y0, double x1, double y1, boolean full) {
            if (x1 <= x0 || y1 <= y0) return;
            if (module != null) {
                String name = module.name().toLowerCase(Locale.ROOT);
                String file = module == Module.COOL ? full ? "heat_base" : "heat_run_0"
                    : module == Module.HEAT ? full ? "heat_run_1" : "heat_base" : name + (full ? "_run" : "_base");
                bind(minecraft, texture("progress_module/" + file));
                int tint = full ? fullColor : emptyColor;
                GlStateManager.color((tint >>> 16 & 255) / 255f, (tint >>> 8 & 255) / 255f, (tint & 255) / 255f, (tint >>> 24) / 255f);
                quad(x + x0, y + y0, x + x1, y + y1, x0 / width, y0 / height, x1 / width, y1 / height);
            } else {
                GlStateManager.enableBlend();
                GlStateManager.disableDepth();
                GlStateManager.disableLighting();
                // Twenty native pixel rows define the arrow/ring; clipping follows the original four quadrant order.
                for (int row = 0; row < 20; row++) {
                    double top = Math.max(y0, row * height / 20.0), bottom = Math.min(y1, (row + 1) * height / 20.0);
                    if (bottom <= top) continue;
                    int run = -1;
                    for (int column = 0; column <= 20; column++) {
                        boolean pixel = column < 20 && pixel(column, row);
                        if (pixel && run < 0) run = column;
                        if (!pixel && run >= 0) {
                            solid(x + Math.max(x0, run * width / 20.0), y + top,
                                x + Math.min(x1, column * width / 20.0), y + bottom, full ? fullColor : emptyColor);
                            run = -1;
                        }
                    }
                }
            }
        }

        private boolean pixel(int x, int y) {
            if (shape == Shape.ARROW)
                return x >= 2 && x < 11 && y >= 7 && y < 13 || x >= 11 && x <= 18 - Math.abs(y - 10);
            double radius = (x - 9.5) * (x - 9.5) + (y - 9.5) * (y - 9.5);
            return radius >= 30 && radius <= 81;
        }
    }

    /**
     * Null template uses LocalizationEnum; explicit templates replace {ticks} and {seconds}.
     */
    public record Duration(@Nullable String template, int width, int height, Alignment alignment,
                           float scale, int color, boolean shadow) implements JeiRecipeDecorator {
        public Duration {
            Objects.requireNonNull(alignment);
            if (width <= 0 || height <= 0 || !Float.isFinite(scale) || scale <= 0)
                throw new IllegalArgumentException("Invalid duration bounds");
        }

        @Override
        public void draw(Minecraft minecraft, RecipePoolJeiRecipe recipe, int x, int y) {
            long ticks = recipe.recipe().durationTicks();
            String seconds = String.format(Locale.ROOT, "%.2f", ticks / 20.0);
            String text = template == null ? JeiText.Duration.getLocal(ticks, seconds)
                : template.replace("{ticks}", Long.toString(ticks)).replace("{seconds}", seconds);
            double px = x + (width - minecraft.fontRenderer.getStringWidth(text) * scale) * alignment.x;
            double py = y + (height - minecraft.fontRenderer.FONT_HEIGHT * scale) * alignment.y;
            GlStateManager.pushMatrix();
            try {
                GlStateManager.translate(px, py, 0);
                GlStateManager.scale(scale, scale, 1);
                minecraft.fontRenderer.drawString(text, 0, 0, color, shadow);
            } finally {
                GlStateManager.popMatrix();
                GlStateManager.enableBlend();
            }
        }
    }

    public record ItemSkin(Plaid plaid) implements JeiSlot.Skin {
        public ItemSkin {
            Objects.requireNonNull(plaid);
        }

        @Override
        public void background(Minecraft minecraft, JeiSlot slot, JeiRecipeDisplay.Resource resource) {
            plaid.base.draw(minecraft, slot.x(), slot.y(), slot.width(), slot.height());
        }

        @Override
        public void foreground(Minecraft minecraft, JeiSlot slot, JeiRecipeDisplay.Resource resource) {
            if (plaid.top != null)
                plaid.top.draw(minecraft, slot.x() - slot.width(), slot.y() - slot.height(), slot.width() * 3, slot.height() * 3);
        }
    }
}
