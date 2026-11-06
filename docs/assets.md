# 仓室美术来源

当前资源来源为 `F:/clone/PrototypeMachinery/src/main/resources/assets/prototypemachinery`，使用 PM 原模型与依赖纹理：

- 资源输入、输出、混合仓：`models/block/item_hatch/{input,output,io}/lv1.json`–`lv10.json`，不是 `mix_hatch` 资源。
- 能源输入、输出、混合仓：`models/block/energy_hatch/{input,output,io}/lv1.json`–`lv10.json`。
- 依赖纹理：`textures/blocks/item_hatch`、`energy_hatch`，以及 `textures/blocks/bloom/item_fluid_gas_hatch`、`bloom/energy_hatch`；保留所用 `.png.mcmeta` 的 CTM/Bloom 声明与动画数据。
- 能源 GUI：`textures/gui/gui_energy_hatch/*.png` 共 22 张原样复制；176×214 界面已接入原 16×66 纵向能量条，实际显示待游戏内验证。

本轮共复制 60 个方块模型、144 张方块 PNG 及 30 份 CTM/Bloom 元数据（其中 6 份还包含动画），另外复制 22 张能源 GUI PNG，并为 60 个注册项建立 blockstate 与物品模型。模型的资源命名空间调整为 `prototype_eng`，原几何、`credit` 与 `bloom_*` 元素保留，没有把输入/输出标记与底图重新合成为静态纹理。

原模型美术署名为 **WI_8614_ice**，X：`@wi8614ice`、`@wi8614art`；模型中的原始 credit 字段继续保留。十阶模型加载、动画与实际渲染仍待游戏内验证。

普通仓室使用标准 Forge 方块模型，不走 PM 的机器 Gecko/TESR 管线。发光路径位于纹理 `.mcmeta`：`ctm.layer=BLOOM`、`gregtech=true`、`extra.light=15`；这些声明已经保留。原 PM 的 `gradle/scripts/dependencies.gradle:71–72` 使用 Lumenized 编译依赖与 CTM 运行依赖。实际后处理仍需兼容的 CTM 与 BLOOM 层提供者（如原环境的 GregTech/Lumenized）；本项目当前未声明这些附加依赖，也未验证外部组合，不另造仓室渲染器。机器自定义 Gecko/Bloom 管线则仍是后续迁移项。

本轮静态核对 128 份模型 JSON 的模型/纹理引用，无缺失引用；未将资源哈希检查包装为自动化测试。

PM 仓库附带的 [许可证](licenses/PrototypeMachinery-LICENSE) 同时收入产物的 `META-INF/licenses/PrototypeMachinery-LICENSE`。当前复用基于用户允许使用旧 PM/PN 资源的任务授权，未将美术标记为本项目重新创作。
