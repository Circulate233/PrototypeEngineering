# PM 配方事件、条件及预设复审

2026-11-07，按用户要求由独立子代理重新从 PM 源码核对。PM 检查版本：`034541c593ee735312904e635c7ea17db1e376a1`。本次没有把 PE 既有迁移文档当作旧功能事实来源。

## 修正结论

PM 的配方扩展绝不只有机器 tick。它目前没有独立于 `requirements` 的顶层 `condition/events` 数组或通用 `RecipeEventHandler` 注册框架，但每配方运行时扩展实际通过需求三阶段、选择提交修改器、动态输入匹配器和进程组件系统实现。旧 PM 还提供选择修改器和动态匹配器的 CT 回调注册入口。

此前“PM 没有每配方 condition/runtime event 字段/DSL”的表述过于宽泛，遗漏了顶层字段之外的回调和脚本注册，现予以纠正。不能从 `MachineRecipe` 或 `ZenRecipeBuilder` 的字段列表推断整个项目没有运行时配方回调。

## 实际清单与新项目对应

| 能力 | PM 的定义与实际触发 | PE 当前对应 |
|---|---|---|
| 每配方 START/TICK/END | `RecipeRequirementSystem.kt:74,99`；`FactoryRecipeProcessorSystem.kt:245,256,260,266` 遍历当前配方需求并执行 | `RecipeRequirement.acquire`、`RecipeStageExecutor` 和 `RecipeProcess` 已执行对应阶段 |
| 配方进程组件 Pre/Normal/Post | `RecipeProcessComponentType.kt:17`、`RecipeProcessSystem.kt:11`；`FactoryRecipeProcessorSystem.kt:175,181–183` 实际派发。六种内置进程组件中五种无 system，唯一 SelectiveState system 的三个回调均为空；这是扩展钩子，不是额外的内置配方效果 | `ComponentType/ComponentSystem<RecipeProcess>`，`RecipeProcess.java:397,477` 实际开启和结束组件会话 |
| 属性效果 | `AttributeModifierRequirementComponent.kt:18` 配置属性、modifier、applyAtStart/removeAtEnd；对应 system `:24,60` 安装/撤销 | `AttributeRequirementSystem` 已实现，并保留回滚和最终阶段属性有效期 |
| 定时检查点 | `CheckpointRequirementComponent.kt:19` 的时间、子需求、速度缩放；system `:45,67,93,109,128` 跨阈值后执行子需求 START/TICK/END | `CheckpointRequirementSystem` 已执行；修正旧 time=0 不触发的问题 |
| **选择提交修改器** | `SelectiveRequirementComponent.kt:29` 的 candidates/modifierIds；system `:71,87` 在所选子 START 提交后查注册表，`:91` 真调用 `modifier.apply(ctx)` | `SelectiveRequirement` 绑定描述符；`SelectiveRequirementSystem.java:160,180` 已实际执行自定义回调，并清理登记的效果 |
| **选择器脚本和速度预设** | `ZenSelectiveModifiers.kt:32` 注册 ZenScript lambda，`:44` 提供 `registerProcessSpeedMultiplier`；`ZenSelectiveContext.kt:30,46` 提供速度加法/倍增 | Java `SelectiveModifier`、`SelectiveContext.putProcessAttributeModifier` 与 `NumericAttributeModifier` 已有；CT 回调桥和便捷入口尚未实现 |
| **动态输入条件和脚本** | `DynamicItemInputGroup.kt:16` 保存 matcherId/pattern/count；`ZenItemMatchers.kt:30` 注册脚本；`ZenRecipeRequirement.kt:186,238` 写入配方；`ItemRequirementSystem.kt:91,448` 实际查询并调用 matcher | `DynamicInputGroup`、`DynamicInputCandidates.java:94`、`ResourceInputResolver` 已执行匹配并锁定候选；CT 尚未接入 |
| 条件预设 | `ItemRequirementMatcherRegistry.kt:47,53`：item_only 比物品/meta、忽略 NBT；exact 也比较 NBT | `ResourceMatchers.EXACT/ITEM_ONLY` 已按 AEKey 实现 |
| 显式 overlay 修改器 | `RecipeOverlayModifierPipeline.kt:12,24` 按 id 调用 apply；旧主源码中没有自动调用 pipeline 的入口，只有显式入口及测试 | `RecipeOverlayPipeline.apply` 已提供真实修改和 undo；不能把它描述成旧 PM 自动触发的 recipe-start 事件 |

PM 活跃内置需求系统共六类：item、fluid、energy、attribute_modifier、checkpoint、selective。PE 把 item/fluid 合为 AEKey 通用资源需求；FE 独立。概率、概率属性倍率、模糊/动态输入、随机输出及数量缩放也都在实际需求系统执行，不能只列出 START/TICK/END 三个方法名就认为已经说明这些效果。

旧 Item 的 TICK 当前为空操作；Fluid 和 Energy 有逐 TICK IO。旧 parallelism 三个需求文件已清空，部分 `.zs` 示例仍调用不存在的 `parallelism()`，示例不是功能存在的证据。旧 CT 已有选择修改器注册，但当前 `ZenRecipeRequirement` 缺 selective/checkpoint/attribute 构造工厂，不能夸大成完整的 CT 配方构造链。

## 关键源码入口

- PM [选择器配置](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/component/SelectiveRequirementComponent.kt:29)、[提交回调](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/component/system/SelectiveRequirementSystem.kt:71)、[脚本与速度预设](F:/clone/PrototypeMachinery/src/main/kotlin/integration/crafttweaker/zenclass/ZenSelectiveModifiers.kt:32)。
- PM [动态匹配脚本](F:/clone/PrototypeMachinery/src/main/kotlin/integration/crafttweaker/zenclass/recipe/ZenItemMatchers.kt:30)、[实际匹配调用](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/system/ItemRequirementSystem.kt:448)。
- PE [选择提交回调](../src/main/java/com/circulation/prototype_eng/recipe/requirement/SelectiveRequirementSystem.java)、[动态匹配执行](../src/main/java/com/circulation/prototype_eng/recipe/requirement/DynamicInputCandidates.java)、[检查点](../src/main/java/com/circulation/prototype_eng/recipe/requirement/CheckpointRequirementSystem.java)、[属性效果](../src/main/java/com/circulation/prototype_eng/recipe/requirement/AttributeRequirementSystem.java)。

机器 `MachineTickEvent` 和 Loaded/Unloaded/FormedStateChanged/ControllerBroken 属于另一层生命周期，不应拿来代替本表的配方事件。

PM 当前也没有独立的配方取消/失败回调：`FactoryRecipeProcessorSystem.kt:57,81,118,141` 的 Failure 分支直接清空工位进程，`:149–150` 仍进入进程组件 POST。阶段事务 rollback 是补偿，不是 `onRecipeCancel/onRecipeFailure` 事件。PE 的 `RecipeRequirementSystem.cancel` 是新增的终止清理边界，不计作旧事件迁移。进程空回调可见 [SelectiveStateProcessComponentType.kt](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/process/component/SelectiveStateProcessComponentType.kt:14)。

## 范围与限度

独立检查了全仓 676 份 Java/Kotlin（src 658、modern-backend 18），以及 `.zs`、JSON、Web 编辑器和隐藏/忽略目录内源码。没有子模块、嵌套 Git 或未跟踪 Java/Kotlin；未跟踪 HTML 也纳入检索。结论基于当前本地 PM 源码，不声称不存在其他历史版本的设计。

以上是源码调用链核对。CT 仍按用户要求最后迁移，PN 的逐配方研究/计算扩展、完整研究队列与下载等缺口另见[功能核对](recipe-feature-parity.md)。没有运行 Minecraft、服务端或桌面操作。
