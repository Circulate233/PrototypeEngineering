package com.circulation.prototype_eng.client.research;

import com.circulation.prototype_eng.network.ResearchPayload;
import com.circulation.prototype_eng.research.content.ResearchOverviewLayout;
import com.circulation.prototype_eng.research.content.ResearchViewCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;

@SideOnly(Side.CLIENT)
public final class ClientResearchCatalog {
    private static final ResearchPayload.Assembly ASSEMBLY = new ResearchPayload.Assembly();
    private static @Nullable NetworkManager connection;
    private static ResearchOverviewLayout layout = ResearchOverviewLayout.EMPTY;
    private static long revision = -1;

    private ClientResearchCatalog() {
    }

    public static ResearchOverviewLayout layout() {
        var handler = Minecraft.getMinecraft().getConnection();
        return handler != null && handler.getNetworkManager() == connection ? layout : ResearchOverviewLayout.EMPTY;
    }

    public static long revision() {
        var handler = Minecraft.getMinecraft().getConnection();
        return handler != null && handler.getNetworkManager() == connection ? revision : -1;
    }

    public static void receive(NetworkManager manager, ResearchPayload.Part part) {
        if (connection != manager) {
            ASSEMBLY.clear();
            connection = manager;
            layout = ResearchOverviewLayout.EMPTY;
            revision = -1;
        }
        ASSEMBLY.accept(part, buffer -> {
            var data = new PacketBuffer(buffer);
            long version = data.readVarLong();
            var projection = ResearchViewCodec.readLayout(data);
            if (data.isReadable()) throw new IllegalArgumentException("Trailing bytes in research catalog");
            if (version >= revision) {
                revision = version;
                layout = projection;
            }
        });
    }

    public static void disconnect(NetworkManager manager) {
        if (connection != manager) return;
        ASSEMBLY.clear();
        connection = null;
        layout = ResearchOverviewLayout.EMPTY;
        revision = -1;
    }
}
