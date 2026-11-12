#loader crafttweaker
import mods.prototype_eng.structure.Structure;
import mods.prototype_eng.structure.Pattern;
import mods.prototype_eng.structure.Predicate;

var cap = Structure.template("example:smelter_cap", "NORTH_UP", 0, 1, 0, false,
    Pattern.create().put(0, 0, 0, Predicate.block("minecraft:gold_block")), [], [])
    .translated("example.structure.smelter_cap");
var column = Structure.slice("example:smelter_column", "NORTH_UP", 0, 1, 0, false,
    Pattern.create().put(0, 0, 0, Predicate.block("minecraft:iron_block")),
    1, 4, 0, 1, 0, [], [cap]).translated("example.structure.smelter_column");

var smelter = Structure.template("example:smelter_shape", "NORTH_UP", 0, 0, 0, false,
    Pattern.create()
        .put(-1, 0, 0, Predicate.block("prototype_eng:input_hatch_1"))
        .put(1, 0, 0, Predicate.block("prototype_eng:output_hatch_1"))
        .put(0, 0, 1, Predicate.block("prototype_eng:energy_input_hatch_1")), [], [column])
    .translated("example.structure.smelter");
cap.register();
column.register();
smelter.register();
