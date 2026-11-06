# 实施进度

需求依据：[开发文档](development-spec.md)。仅记录实际完成的工作与验证证据，不将设计目标标为已实现。

项目定位为纯库：机器、配方、研究内容和玩法数值由使用方定义；内置仓室为通用组件，独立示例不进入主产物。

## 当前状态

- 文档恢复入口：先读 [INDEX.md](INDEX.md)，再按任务定位章节；根 AGENTS.md 和 README 已链接该入口。索引维护当前检查点与下一步，本文件维护详细状态和证据。
- 已完成：工程标识、Java 25 模板、需求基线；固定 AE2S `v1.1.0:dev` 与 HEI `4.35.1`；移除模板示例 Mixin；开发包和生产重混淆构建。
- 已有待重构仓室后端：输入/输出独立多类型缓存、自动化方向路由、失效控制及独立 long FE 存储。用户明确要求独立存储契约，现有 MEStorage 返回签名及 GenericStackInv 后端不符合最终设计，须按[存储重构约束](storage-redesign.md)替换；公开扩展职责继续保留接口，外部宿主不依赖内置 TileEntity。
- P1 已接入：资源/能源各输入、输出、混合三方向十阶，共 60 个方块及物品注册；方块实体保存和能力暴露、挖掘保留内容、Java 容器和直接继承 AEBaseGui 的资源/能源界面。十阶默认值来自 PM ItemHatchConfig/ItemIOHatchConfig、FluidHatchConfig 与 EnergyHatchConfig，均可配置。资源输入/输出独立滚动，最多 5 行并随分辨率压缩，隐藏槽禁用且索引保持；能源 GUI 为 176×214，使用原 16×66 纵向条，支持速率/自动标志、Enter 提交和完整 long tooltip。实际 GUI/游戏交互未运行验证。
- 能源职责已接入：EnergyOutput 及公开发现能力、EnergyHatch 双职责、ForgeEnergyPort 三方向，每次操作限额同时约束机器与 Forge，并随设置动态更新；模式/等级/限额/自动标志写入 NBT。能源重载以当前配置容量为准并裁剪保存量，资源仓仍保留保存容量。PM 限额并非累计 tick 预算，自动标志也尚未发现邻居 tick 传输调用链；本项目保留设置/保存，不宣称邻居自动传输已实现。
- 美术已复制：PM item_hatch 与 energy_hatch 十阶三方向原模型 60 份、方块 PNG 144 张、CTM/Bloom 元数据 30 份（其中 6 份还带动画），另有 gui_energy_hatch 的 22 张 PNG，保留 credit 与 bloom 元素。原仓室走标准 Forge 模型与这些元数据，外部 CTM/BLOOM 层环境的实际效果未验证；机器自定义 Gecko/Bloom 管线仍待迁移。
- 本地化已接入：GuiText 实现 AE2S LocalizationEnum，客户端使用 getLocal，容器标题使用 text 组件；禁止 GUI 拼键和裸翻译键调用。最终本机 IDEA MCP 逐文件检查零错误、警告、弱警告，MCP 构建及 Gradle 完整构建均通过，证据见下。
- P2 已迁移组件/需求基础：软依赖拓扑、组件多系统、Pre/Normal/Post 调度与 tick 事件适配；配方按需求职责分组、外部类型绑定及 start/tick/end 整阶段事务分派。完整配方库仍未迁完，进程状态/属性、内置需求、扫描、多工位和并发集成等继续开发。
- 旧固定资源流程已改为内部 ResourceTransferPlan/Run 迁移材料，不再定义公开 MachineRecipe 或从公开池选择配方。其资源守恒测试保留，但不能作为新存储或事务实现的证明。未接入机器且无调用方的固定资源 ResourceTransferNbt 已移除；正式进程/组件持久化仍为必须迁移项。
- 尚未实现：机器/结构定义和运行接入、JEI 页面、研究与计算库能力、CraftTweaker。
- 暂缓：Applied-Flux 兼容；后续仍使用带 v 前缀的 `v1.0.0:dev` 坐标，当前不拉取该依赖。
- 必须继续迁移：研究/队伍、并行调度、结构/投影/渲染及其他未被明确取消的 PM/PN 功能效果。实现策略仍需按源码审查；正式机器与玩法数值不属于待开发内容。

## 验证记录

- 2026-11-07：改用本机 IDEA MCP 完成检查。已启用服务的 `mcpServer.xml` 端口为 64343，通过 SSE `http://127.0.0.1:64343/sse` 连接并在 `tools/list` 发现 `get_file_problems` 与 `build_project`。对 57 个 src Java 文件及 1 个生成 Tags 文件逐个使用 `errorsOnly:false`，全部 `errors:[]`，无错误、警告、弱警告及超时；最终报告为 [final.json](../build/reports/idea/final.json)。后续检查沿用 MCP，不操作桌面或修改 IDE 设置。
- 2026-11-07：MCP `build_project` 返回 `isSuccess:true`、`problems:[]`；最终 `gradlew.bat build` 成功，含生产重混淆与 25 项逻辑用例，其中 7 项能源覆盖三方向、动态机器/Forge 限额、重载裁剪及旧引用失效等行为。未启动 Minecraft 客户端或服务端，GUI/游戏验收未列为通过。
- 2026-11-07：静态核对 128 份模型 JSON 的模型/纹理引用，无缺失；18 个 GuiText 枚举项的中英语言键完整，格式占位一致。源码未发现裸 `TextComponentTranslation` / `I18n` 调用；未建立资源哈希、常量或 getter 测试。
- 2026-11-07：早先错误选择桌面 IDEA 检查，被用户 Escape 中止且报告未导出；用户纠正后改用上述 MCP，修复诊断并取得最终零问题结果。早先中断不再作为当前状态。
- 2026-11-06：`gradlew.bat --version` 确认 Gradle 9.8.1 使用 Java 25；系统 PATH 中的 Java 8 未被 Gradle 启动器采用。
- 2026-11-06：早期构建从 4 项逻辑用例扩展至 P1 的 7 项，再至固定资源原型的 11 项，验证双缓存方向、鼠标转移、同源去重、部分收取/付款与检查点守恒；这些证据不代表独立存储、完整需求迁移或 NBT/游戏验收通过。
- 2026-11-06：组件与需求基础迁移通过 `gradlew.bat spotlessApply build --console=plain`，含生产重混淆及 20 项逻辑测试。新增 9 项覆盖软依赖/替换/重装、外部系统事件顺序和失败终止，以及外部需求的整阶段回滚/重试、状态隔离、三个阶段及异常清理。没有宣称真实调度器、NBT 或游戏运行已经验证。
- 早期依赖排查曾启动临时服务端并正常关闭，发现普通 implementation 导致 AE2 插件类加载冲突，改用 MDG 的 modImplementation 后完成基础加载。该记录发生在仓室核心接入之前，不是当前业务功能的游戏验证。
- NBT 往返、当前能力注册、GUI 与游戏交互尚未取得运行证据；当前不继续自行启动客户端或服务端。
- 直接测试 Forge FluidStack 需要 Bootstrap/FML 注册流程；普通 JUnit 引导还遇到 Gson 运行库冲突。该游戏依赖用例未纳入当前通过集，没有用反射、替身注册表或大批附属依赖绕过它。混合仓流体方向目前依据 AE2 适配器源码和本项目分离视图核对，实际流体交互仍待运行验证。

## 提交约定

阶段完成后本地提交，不推送。测试限于静态推断无法充分确认的行为；测试证据、构建结果与游戏验证分别记录。

集合实现优先使用 fastutil；本轮容量表采用原生 long 映射，能力适配缓存采用引用身份映射。没有自行实现反射扫描或通过捕获异常维持常规资源流程。

当前实施先完成源码、构建及必要逻辑测试，不继续自行启动客户端或服务端。此前的两个临时服务端已经正常停止。
