package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.machine.ComputerController;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;

/**
 * 阶段的候选资源修改；仅在该阶段输入提交成功后生效，不得保留 values 或直接修改其他设备。
 */
public record ComputerRecipeEvent(ComputerController computer, RecipeProcess process, RecipeStage stage,
                                  NetworkData network, NetworkResourceState values) {
}
