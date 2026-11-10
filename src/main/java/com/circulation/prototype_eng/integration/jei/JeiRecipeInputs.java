package com.circulation.prototype_eng.integration.jei;

import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRecipe;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.lwjgl.input.Mouse;

/** Only the bounds actually drawn during the current screen frame can consume a wheel event. */
@Mod.EventBusSubscriber(modid = "prototype_eng", value = Side.CLIENT)
public final class JeiRecipeInputs {
    private static final ObjectArrayList<Drawn> DRAWN = new ObjectArrayList<>();

    private JeiRecipeInputs() {
    }

    /** A new native JEI runtime invalidates layouts even if the current screen did not change. */
    public static void reset() {
        DRAWN.clear();
    }

    public static void drawn(Minecraft minecraft, RecipePoolJeiRecipe recipe, int width, int height, int mouseX, int mouseY) {
        if (minecraft.currentScreen == null || !recipe.isCurrent()) return;
        var scaled = new ScaledResolution(minecraft);
        int globalX = Mouse.getX() * scaled.getScaledWidth() / minecraft.displayWidth;
        int globalY = scaled.getScaledHeight() - Mouse.getY() * scaled.getScaledHeight() / minecraft.displayHeight - 1;
        observe(minecraft.currentScreen, (x, y, delta) -> recipe.isCurrent() && recipe.handleMouseScroll(x, y, delta),
            globalX - mouseX, globalY - mouseY, width, height);
    }

    static void observe(GuiScreen screen, Scroll scroll, int x, int y, int width, int height) {
        DRAWN.add(new Drawn(screen, scroll, x, y, width, height));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeDraw(GuiScreenEvent.DrawScreenEvent.Pre event) {
        DRAWN.clear();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void opened(GuiOpenEvent event) {
        DRAWN.clear();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void initialized(GuiScreenEvent.InitGuiEvent.Pre event) {
        DRAWN.clear();
    }

    // RecipesGui updates category/page layouts in place. A native key or button can replace
    // the visible roster without GuiOpen/InitGui, so revoke the previous draw before dispatch.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void keyboard(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        DRAWN.clear();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void mouse(GuiScreenEvent.MouseInputEvent.Pre event) {
        if (Mouse.getEventButton() >= 0) {
            DRAWN.clear();
            return;
        }
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        var minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen != event.getGui()) {
            DRAWN.clear();
            return;
        }
        var scaled = new ScaledResolution(minecraft);
        int x = Mouse.getEventX() * scaled.getScaledWidth() / minecraft.displayWidth;
        int y = scaled.getScaledHeight() - Mouse.getEventY() * scaled.getScaledHeight() / minecraft.displayHeight - 1;
        if (scroll(event.getGui(), x, y, wheel)) event.setCanceled(true);
    }

    static boolean scroll(GuiScreen screen, int x, int y, int wheel) {
        for (int i = DRAWN.size() - 1; i >= 0; i--) {
            var drawn = DRAWN.get(i);
            int localX = x - drawn.x, localY = y - drawn.y;
            if (drawn.screen != screen || localX < 0 || localY < 0 || localX >= drawn.width || localY >= drawn.height)
                continue;
            if (drawn.scroll.handle(localX, localY, wheel)) {
                return true;
            }
            // An unhandled native wheel can replace the current layout before the next buffered event.
            DRAWN.clear();
            return false;
        }
        DRAWN.clear();
        return false;
    }

    @FunctionalInterface
    interface Scroll {
        boolean handle(int x, int y, int delta);
    }

    private record Drawn(GuiScreen screen, Scroll scroll, int x, int y, int width, int height) {
    }
}
