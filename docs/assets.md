# 仓室美术来源

当前资源来源为 `F:/clone/PrototypeMachinery/src/main/resources/assets/prototypemachinery`，使用 PM 原模型与依赖纹理：

- 资源输入、输出、混合仓：`models/block/item_hatch/{input,output,io}/lv1.json`–`lv10.json`，不是 `mix_hatch` 资源。
- 能源输入、输出、混合仓：`models/block/energy_hatch/{input,output,io}/lv1.json`–`lv10.json`。
- 依赖纹理：`textures/blocks/item_hatch`、`energy_hatch`，以及 `textures/blocks/bloom/item_fluid_gas_hatch`、`bloom/energy_hatch`；保留所用 `.png.mcmeta` 的 CTM/Bloom 声明与动画数据。
- 能源 GUI：`textures/gui/gui_energy_hatch/*.png` 共 22 张原样复制；176×214 界面已接入原 16×66 纵向能量条，实际显示待游戏内验证。

本轮共复制 60 个方块模型、144 张方块 PNG 及 30 份 CTM/Bloom 元数据（其中 6 份还包含动画），另外复制 22 张能源 GUI PNG，并为 60 个注册项建立 blockstate 与物品模型。模型的资源命名空间调整为 `prototype_eng`，原几何、`credit` 与 `bloom_*` 元素保留，没有把输入/输出标记与底图重新合成为静态纹理。

原模型美术署名为 **WI_8614_ice**，X：`@wi8614ice`、`@wi8614art`；模型中的原始 credit 字段继续保留。十阶模型加载、动画与实际渲染仍待游戏内验证。

普通仓室继续使用标准 Forge 方块模型。共有 44 个模型的 52 个 `bloom_*` 元素明确 `shade=false`，普通外壳及 AO 保持；外壳先绘制、稀疏彩色发光层后绘制。共享 `HatchBlock` 使用 CUTOUT 通道，使无 CTM 时透明像素也不会覆盖底图，实体的 opaque/fullCube 和存储行为不变。

30 份 CTM 元数据保留原 `layer=BLOOM`、`gregtech=true`、`extra.light=15` 和动画，新增布尔 `prototype_eng_bloom` 标记。客户端 optional Mixin 只处理带该标记的元数据：实际枚举存在 BLOOM 时原样交给 CTM/Lumenized；不存在时深拷贝并仅把 layer 转为 CUTOUT，保持局部满亮参数。未标记资源不变，不修改全局枚举或其他模组纹理。没有 CTM 时仍正常显示颜色与透明覆盖；局部满亮与 Bloom 后处理分别由 CTM 和相应提供者完成。

用户反馈中的白灰区域已有现场日志证据：未加载 Lumenized 的启动中，30 份纹理因未知 BLOOM 层解析失败，CTM 回退普通无元数据纹理，失去 light/专用层；物品原发光面仍参加方向漫反射，造成压暗。上述修复保持同一方块/物品模型父链、UV、几何、credit 及全部 163 个现存方块 PNG，6 份动画参数不变。新证据位于 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261110/hatch-model-parity/`；真实 GL/可选 Bloom 组合仍由用户重启客户端复验。

本轮静态核对 128 份模型 JSON 的模型/纹理引用，无缺失引用；未将资源哈希检查包装为自动化测试。

PM 仓库附带的 [许可证](licenses/PrototypeMachinery-LICENSE) 同时收入产物的 `META-INF/licenses/PrototypeMachinery-LICENSE`。当前复用基于用户允许使用旧 PM/PN 资源的任务授权，未将美术标记为本项目重新创作。
