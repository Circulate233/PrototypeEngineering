package pe.visual.examples.run;

import com.circulation.prototype_eng.integration.crafttweaker.resource.ZenComputingResource;
import crafttweaker.annotations.ZenRegister;
import pe.visual.examples.ExampleResources;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

/**
 * ZS access to the example's shared Java resource singletons.
 */
@ZenRegister
@ZenClass("mods.pe_run_tests.Resources")
public final class RunResources {
    private RunResources() {
    }

    @ZenMethod
    public static ZenComputingResource compute() {
        return new ZenComputingResource(ExampleResources.COMPUTE);
    }

    @ZenMethod
    public static ZenComputingResource qubit() {
        return new ZenComputingResource(ExampleResources.QUBIT);
    }

    @ZenMethod
    public static ZenComputingResource netCompute() {
        return new ZenComputingResource(ExampleResources.NET_COMPUTE);
    }

    @ZenMethod
    public static ZenComputingResource cooling() {
        return new ZenComputingResource(ExampleResources.COOLING);
    }

    @ZenMethod
    public static ZenComputingResource memory() {
        return new ZenComputingResource(ExampleResources.MEMORY);
    }

    @ZenMethod
    public static ZenComputingResource bandwidth() {
        return new ZenComputingResource(ExampleResources.BANDWIDTH);
    }

    @ZenMethod
    public static ZenComputingResource storage() {
        return new ZenComputingResource(ExampleResources.STORAGE);
    }

    @ZenMethod
    public static ZenComputingResource signal() {
        return new ZenComputingResource(ExampleResources.SIGNAL);
    }
}
