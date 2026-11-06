package com.circulation.prototype_eng.storage;

import ae2.helpers.externalstorage.GenericStackFluidStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/** Keeps the input fluid from masking a different, drainable output fluid. */
public final class HatchFluidHandler implements IFluidHandler {
    private final GenericStackFluidStorage input;
    private final GenericStackFluidStorage output;

    public HatchFluidHandler(BufferedResourceHatch hatch) {
        input = new GenericStackFluidStorage(new HatchInventoryPort(hatch, true, false));
        output = new GenericStackFluidStorage(new HatchInventoryPort(hatch, false, true));
    }

    @Override
    public IFluidTankProperties[] getTankProperties() {
        var inputs = input.getTankProperties();
        var outputs = output.getTankProperties();
        var result = Arrays.copyOf(inputs, inputs.length + outputs.length);
        System.arraycopy(outputs, 0, result, inputs.length, outputs.length);
        return result;
    }

    @Override
    public int fill(FluidStack resource, boolean doFill) { return input.fill(resource, doFill); }
    @Override
    @Nullable
    public FluidStack drain(FluidStack resource, boolean doDrain) { return output.drain(resource, doDrain); }
    @Override
    @Nullable
    public FluidStack drain(int maxDrain, boolean doDrain) { return output.drain(maxDrain, doDrain); }
}
