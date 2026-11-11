#loader crafttweaker
import mods.prototype_eng.recipe.Bindings;
import mods.pe_run_tests.NetworkRender;
import mods.pe_run_tests.CoreCases;

var names = Bindings.scope("pe_run_tests:bindings");
CoreCases.registerAll();
names.declareManifest("pe_run_tests:directory", "v1");
names.declareClientVariant("pe_run_tests:directory", "v2", NetworkRender.clientV2Recipes());
