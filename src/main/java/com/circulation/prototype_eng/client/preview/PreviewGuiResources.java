package com.circulation.prototype_eng.client.preview;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.resources.IResourceManager;
import net.minecraftforge.client.resource.ISelectiveResourceReloadListener;
import net.minecraftforge.client.resource.IResourceType;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.function.Predicate;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Actual active viewer resources; JEI wrappers own and retire their own scenes. */
@ParametersAreNonnullByDefault
public final class PreviewGuiResources implements ISelectiveResourceReloadListener {
    public static final PreviewGuiResources INSTANCE = new PreviewGuiResources();
    private static final ObjectArrayList<StructurePreviewScene> SCENES = new ObjectArrayList<>();
    private PreviewGuiResources() {}
    static void add(StructurePreviewScene scene) { SCENES.add(scene); }
    static void remove(StructurePreviewScene scene) { SCENES.remove(scene); }
    @Override public void onResourceManagerReload(IResourceManager manager, Predicate<IResourceType> resources) { invalidate(); }
    @SubscribeEvent public void onUnload(WorldEvent.Unload event) { if (event.getWorld().isRemote) invalidate(); }
    private static void invalidate() {
        Throwable failure = null;
        for (var scene : new ObjectArrayList<>(SCENES)) try { scene.invalidate(); }
        catch (RuntimeException | Error caught) { failure = PreviewBlockModelCache.merge(failure, caught); }
        PreviewBlockModelCache.rethrow(failure);
    }
}
