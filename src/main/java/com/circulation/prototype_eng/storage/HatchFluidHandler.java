package com.circulation.prototype_eng.storage;

import ae2.helpers.externalstorage.GenericStackFluidStorage;
import com.circulation.prototype_eng.api.storage.FluidHatchSettings;
import com.circulation.prototype_eng.api.storage.SlottedResourceHatch;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Keeps the input fluid from masking a different, drainable output fluid.
 */
public final class HatchFluidHandler implements IFluidHandler {
    private final GenericStackFluidStorage input;
    private final GenericStackFluidStorage output;
    @Nullable
    private final SlottedResourceStorage inputStorage;
    @Nullable
    private final SlottedResourceStorage outputStorage;
    @Nullable
    private final FluidHatchSettings settings;

    public HatchFluidHandler(SlottedResourceHatch hatch) {
        this(hatch, null);
    }

    public HatchFluidHandler(SlottedResourceHatch hatch, @Nullable FluidHatchSettings settings) {
        this.settings = settings;
        inputStorage = hatch.input();
        outputStorage = hatch.output();
        boolean shared = inputStorage != null && inputStorage == outputStorage;
        input = new GenericStackFluidStorage(new HatchInventoryPort(hatch, true, shared));
        output = shared ? input : new GenericStackFluidStorage(new HatchInventoryPort(hatch, false, true));
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

    @Override
    public IFluidTankProperties[] getTankProperties() {
        var inputs = tankProperties(input, inputStorage);
        if (inputStorage != null && inputStorage == outputStorage) return inputs;
        var outputs = tankProperties(output, outputStorage);
        var result = Arrays.copyOf(inputs, inputs.length + outputs.length);
        System.arraycopy(outputs, 0, result, inputs.length, outputs.length);
        return result;
    }

    @Override
    public int fill(FluidStack resource, boolean doFill) {
        if (inputStorage == null || resource == null || resource.amount <= 0) {
            return 0;
        }
        inputStorage.context().lock();
        try {
            int amount = settings == null ? resource.amount : (int) settings.limitFluidInput(resource.amount);
            var limited = resource.copy();
            limited.amount = amount;
            return input.fill(limited, doFill);
        } finally {
            inputStorage.context().unlock();
        }
    }

    @Override
    @Nullable
    public FluidStack drain(FluidStack resource, boolean doDrain) {
        if (outputStorage == null || resource == null || resource.amount <= 0) {
            return null;
        }
        outputStorage.context().lock();
        try {
            var limited = resource.copy();
            limited.amount = settings == null ? resource.amount : (int) settings.limitFluidOutput(resource.amount);
            return output.drain(limited, doDrain);
        } finally {
            outputStorage.context().unlock();
        }
    }

    @Override
    @Nullable
    public FluidStack drain(int maxDrain, boolean doDrain) {
        if (outputStorage == null || maxDrain <= 0) {
            return null;
        }
        outputStorage.context().lock();
        try {
            int amount = settings == null ? maxDrain : (int) settings.limitFluidOutput(maxDrain);
            return output.drain(amount, doDrain);
        } finally {
            outputStorage.context().unlock();
        }
    }
}
