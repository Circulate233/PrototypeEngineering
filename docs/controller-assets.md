# 默认控制器美术与模型映射

资源来自 `F:/clone/PrototypeMachinery/src/main/resources/assets/prototypemachinery`，对应 PM 提交 `034541c593ee735312904e635c7ea17db1e376a1`；注册行为参考 `src/main/kotlin/client/registry/ModelRegisterer.kt`。

新增资源链为 `blockstates/default_controller.json` → `models/block/block_controller.json`，物品由 `models/item/default_controller.json` 引用同一方块模型。复制的资源共 3 份模型/blockstate JSON、7 张 PNG 与 3 份外壳 CTM 元数据：

- `textures/blocks/block_controller.png`。
- `textures/blocks/block_casing_{down,side,top}.png`。
- `textures/blocks/block_casing_{down,side,top}-ctm.png`。
- `textures/blocks/block_casing_{down,side,top}.png.mcmeta`。

PNG 原样复制，JSON 仅将资源命名空间改为 `prototype_eng`，默认 blockstate 将原 `twist` 属性名改为 AE2S 的 `spin`。几何、UV、模型组、原始 `credit` 与 CTM 声明均保留。原模型美术署名为 **WI_8614_ice**，X：`@wi8614ice`、`@wi8614art`；原 credit 为 `Made with WI_8614_ice X:@wi8614ice @wi8614art QQ:1463599076`。PM [许可证](licenses/PrototypeMachinery-LICENSE) 已随本项目资料与产物保留。

`MachineControllerModels` 在客户端 Forge `ModelRegistryEvent` 遍历 `MachineControllerBlocks.all()`，每类型的世界状态与物品 `inventory` 共用 `MachineType.controllerModel()`，未提供时使用 `prototype_eng:default_controller`。世界模型保留完整 `facing/formed/spin` 属性字符串，下游自定义模型可以区分全部状态。

默认 blockstate 保留 PM 原 48 个世界变体与 1 个 inventory 变体。水平朝向分别使用原 north 0°、south 180°、east 90°、west 270°映射，各 spin 共用同一旋转；上下朝向保留原 x/y 旋转表，formed 的两种取值均使用原模型。这里保持原默认视觉效果，未为水平 spin 增加新几何旋转。

外壳元数据的 `connect_to` 随命名空间迁移为 `prototype_eng:block_casing`。当前没有注册外壳方块；该连接目标留待原外壳功能迁移。CTM 连接效果及各环境的实际模型加载仍待游戏内验证，本阶段只静态检查资源引用和复制内容，不启动游戏或添加外壳玩法。
