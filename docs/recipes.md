# 配方池与标准执行核心

本模块属于库能力，不注册内置机器或玩法配方。配方内容、加工时长和 FE 参数由使用方提供。公开定义与注册入口在 `api.recipe`；执行状态及 NBT 实现在 `recipe`。

## 旧实现审查

- PM `MachineRecipe` / `SimpleMachineRecipe` 将需求按自有 requirement type 分组，支持配方组与阶段系统。当前标准资源需求直接使用 AE2 key 和数量，不再为物品/流体分别重造类型体系。
- PM `FactoryRecipeProcessorSystem` 的核心要求是：开始资源成功后才能推进；每次成功工作才推进进度；完成输出受阻时仍需保留进度。这些行为保留为标准执行器的依据。
- PM `PlannedDistributedTransactionExecutorImpl` 和 PN `NetworkMachineRecipePlannedOperationFactory` 还包含跨后端预留、epoch、owner、补偿和异常路径。当前单线程资源执行不复制这些协议；研究预算与并发能力实施时再选择所需的公共职责。
- PN `PrototypeMachineryRecipeRegistrationImpl` 的跨模组注册桥不再需要。使用方直接通过本库的公开注册事件提供配方池与配方。

## 使用方注册

`RecipeRegistrationEvent` 在 Forge 事件总线上、模组 post-init 阶段发出。监听者通过事件中的 `RecipeRegistry`：

```java
registry.registerPool(poolId, poolTitleTranslationKey);
registry.registerRecipe(poolId, new MachineRecipe(
        recipeId, inputStacks, outputStacks, durationTicks, fePerTick));
```

参数全部来自使用方。也可以直接 `register(new RecipePool(...))` 提供完整定义。向其他扩展声明的池追加配方时，必须在声明之后注册；扩展可利用 Forge 事件优先级组织该顺序。事件结束后由库冻结目录；运行期间不能再修改已发布定义。查询使用 `RecipeRegistry.INSTANCE.get(poolId)` / `pools()`。

同池配方 ID 不得重复；不同池可以复用同一份定义。池列表按 ID 排序，池内配方的声明顺序就是当前候选优先顺序。重复、未知池和错误注册时机立即报错，不按加载顺序覆盖已有定义。

`MachineRecipe` 使用不可变列表保存 AE2 `GenericStack`。每侧重复 key 合并为一个实际需求，保留首次出现顺序，数量相加溢出立即失败。数量为正 long，时长为正 int，FE/t 为非负 long。配方池定义不引用客户端、JEI 或内部执行类；后续客户端按池 ID 绑定页面。

## 当前标准执行语义

`RecipeRun` 是正在接入的内部标准执行器：开始时收取所有输入，每个成功工作 tick 消耗 FE，完成后交付输出。它实现基础精确 key 配方；复杂阶段、替代材料、条件、并行和自定义执行扩展仍需后续按库能力补齐，当前模型不代表这些需求已经取消。

执行只发生在宿主服务端线程。候选扫描使用 SIMULATE，但模拟成功不视为扣料成功。实际收到的材料逐项记入运行状态；只有输入全部到齐才推进。外部仓室部分扣取或失效时保留已有收取量，后续只取缺额。

FE 同样按实际收取量记录；不足一个工作 tick 时保存这笔未消费余额。重试先补足差额，推进一次再清零该 tick 的余额。能源入口按底层 `storageIdentity()` 去重，资源入口按输入/输出身份分别去重。混合仓的两侧不会被合并为一份库存。

加工完成后按实际插入量减少待交付产物。输出满载或失效时保留剩余产物，后续不再扣输入、不再耗 FE、不再推进。完成状态重复 tick 不会重复生成产物。执行器只使用公开 ResourceHatch、EnergyInput 和 AE2 MEStorage 契约，不调用内置仓室私有恢复操作。

资源不足和满载返回状态；外部实现返回负数或超过请求量属于契约损坏，立即失败。没有用捕获异常继续常规资源操作。

## 状态保存与接入边界

检查点包含完整配方定义、已收输入、剩余产物、进度和未消费 FE。恢复以当时的配方定义为准，后续修改配方目录不会改变已经收取材料的那次工作。数组在检查点边界复制，运行状态与调用方互不共享可变数组。

`RecipeRunNbt` 提供完整检查点的 AE2/NBT 编解码。未知资源、非法数量或不一致状态立即失败；不丢弃无法识别的余额并伪装恢复成功。宿主仍需保存整个工作状态，在结构失效、卸载或停用期间停止调用 tick，并在挖掘等生命周期路径保存/处理现有状态。

机器/结构宿主接入、可取消工作及实际世界持久化尚未完成。本实现没有跨多个 TileEntity 的磁盘事务，不把普通保存窗口宣称为断电原子提交。

## 验证

四项新增逻辑测试使用真实 AEKey/MEStorage 契约与独立外部仓室实现，覆盖：按已注册池选择配方并避免同源重复预算；多类型部分输入跨检查点与宿主失效恢复；共用 FE 的多个视图和部分付款恢复；部分产物交付后重试不重复扣料耗能。

这些是执行状态的逻辑恢复测试，不是 NBT 或游戏重启验收。NBT 适配、Forge 事件运行时序和机器生命周期仍待后续验证；当前不启动客户端或服务端。
