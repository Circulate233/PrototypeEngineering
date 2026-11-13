package pe.visual.examples;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.NetworkResourcePackets;
import ae2.core.localization.LocalizationEnum;
import io.netty.buffer.ByteBuf;
import net.minecraft.util.ResourceLocation;

/**
 * Shared example-owned singleton keys; Java and ZS use these same instances without registration.
 */
public enum ExampleResources implements NetworkResourceType {
    COMPUTE("compute", false, "FLOP/t"),
    QUBIT("qubit", false, "Q/t"),
    NET_COMPUTE("net_compute", false, "FLOP/t"),
    COOLING("cooling", true, "CU/t"),
    MEMORY("memory", false, "MiB"),
    BANDWIDTH("bandwidth", false, "MB/t"),
    STORAGE("storage", false, "IOPS"),
    SIGNAL("signal", false, "sig/t");

    private static final ResourceLocation PACKET_TYPE = new ResourceLocation("pe_run_tests", "example_resource");
    private static final ExampleResources[] PACKET_VALUES = values();

    static {
        NetworkResourcePackets.register(PACKET_TYPE, data -> PACKET_VALUES[data.readUnsignedByte()]);
    }

    private final ResourceLocation id;
    private final boolean internal;
    private final String unit;

    ExampleResources(String name, boolean internal, String unit) {
        id = new ResourceLocation("pe_run_tests", name);
        this.internal = internal;
        this.unit = unit;
    }

    @Override
    public ResourceLocation id() {
        return id;
    }

    @Override
    public boolean internal() {
        return internal;
    }

    @Override
    public String displayName() {
        LocalizationEnum name = () -> "pe_run_tests.resource." + id.getPath();
        return name.getLocal();
    }

    @Override
    public String format(double amount) {
        return Double.toString(amount) + " " + unit;
    }

    @Override
    public ResourceLocation packetType() {
        return PACKET_TYPE;
    }

    @Override
    public void writeToPacket(ByteBuf data) {
        data.writeByte(ordinal());
    }
}
