package com.circulation.prototype_eng.storage;

import ae2.helpers.externalstorage.GenericStackFluidStorage;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/** Keeps the input fluid from masking a different, drainable output fluid. */
public final class HatchFluidHandler implements IFluidHandler {
    private final GenericStackFluidStorage input;
    private final GenericStackFluidStorage output;
    @Nullable private final SlottedResourceStorage inputStorage;
    @Nullable private final SlottedResourceStorage outputStorage;

    public HatchFluidHandler(BufferedResourceHatch hatch) {
        inputStorage = hatch.input();
        outputStorage = hatch.output();
        input = new GenericStackFluidStorage(new HatchInventoryPort(hatch, true, false));
        output = new GenericStackFluidStorage(new HatchInventoryPort(hatch, false, true));
    }

    @Override
    public IFluidTankProperties[] getTankProperties() {
        var inputs = tankProperties(input, inputStorage);
        var outputs = tankProperties(output, outputStorage);
        var result = Arrays.copyOf(inputs, inputs.length + outputs.length);
        System.arraycopy(outputs, 0, result, inputs.length, outputs.length);
        return result;
    }

    @Override
    public int fill(FluidStack resource, boolean doFill) {
        if (inputStorage == null) {
            return 0;
        }
        inputStorage.context().lock();
        try {
            return input.fill(resource, doFill);
        } finally {
            inputStorage.context().unlock();
        }
    }

    @Override
    @Nullable
    public FluidStack drain(FluidStack resource, boolean doDrain) {
        if (outputStorage == null) {
            return null;
        }
        outputStorage.context().lock();
        try {
            return output.drain(resource, doDrain);
        } finally {
            outputStorage.context().unlock();
        }
    }

    @Override
    @Nullable
    public FluidStack drain(int maxDrain, boolean doDrain) {
        if (outputStorage == null) {
            return null;
        }
        outputStorage.context().lock();
        try {
            return output.drain(maxDrain, doDrain);
        } finally {
            outputStorage.context().unlock();
        }
    }

    private static IFluidTankProperties[] tankProperties(GenericStackFluidStorage adapter, @Nullable SlottedResourceStorage storage) {
        if (storage == null) {
            return new IFluidTankProperties[0];
        }
        storage.context().lock();
        try {
            return adapter.getTankProperties();
        } finally {
            storage.context().unlock();
        }
    }
}
