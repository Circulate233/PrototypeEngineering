package pe.visual.examples.run;

import crafttweaker.annotations.ZenRegister;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

/**
 * Assertions log their actual result; a failed startup assertion aborts that script.
 */
@ZenRegister
@ZenClass("mods.pe_run_tests.NativeChecks")
public final class NativeChecks {
    private static final Logger LOG = LogManager.getLogger("pe_run_tests");

    private NativeChecks() {
    }

    @ZenMethod
    public static void check(String id, boolean condition) {
        if (!condition) {
            LOG.error("FAIL {}", id);
            throw new IllegalStateException("pe_run_tests assertion failed: " + id);
        }
        LOG.info("PASS {}", id);
    }
}
