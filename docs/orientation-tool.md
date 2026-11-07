# 控制器方向工具

[ControllerOrientationTool](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/item/ControllerOrientationTool.java) 注册为 `prototype_eng:orientation_tool`，单件堆叠，使用原 PM 的开关扳手模型、武器/铁质复合工具效果及配置默认值。库不为它添加制作配方。原行为入口为 [ControllerOrientationToolItem.kt](F:/clone/PrototypeMachinery/src/main/kotlin/common/item/ControllerOrientationToolItem.kt)、[OrientationToolTuning.kt](F:/clone/PrototypeMachinery/src/main/kotlin/api/tuning/OrientationToolTuning.kt) 与 [TwistMath.kt](F:/clone/PrototypeMachinery/src/main/kotlin/common/util/TwistMath.kt)。

## 两种模式与方向操作

缺省关闭，开关保存在物品 NBT 的 `enabled` Boolean。潜行右键空气在服务端切换模式，发送本地化状态提示并播放原 UI_BUTTON_CLICK 音量/音调；不潜行时返回 PASS。新格式不解码 PM 的 `pm_orientation_tool_enabled`，无旧存档兼容承诺。

开启时对控制器的操作保持 PM 规则，由独立 [OrientationToolOrientations](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/item/OrientationToolOrientations.java) 转换成 AE2 `BlockOrientation`：

| 操作 | 结果 |
|---|---|
| 普通点击当前 FRONT | front 不变，绕 front 顺时针旋转一档，四档循环 |
| 普通点击其他面 | front 设为点击面，top 重置为该 front 的 PM canonical top |
| 潜行点击 | 当前 front 的 twist 前进一档；回到 canonical 时转到下一 vanilla facing，遍历 24 向 |

facing 顺序为 DOWN → UP → NORTH → SOUTH → WEST → EAST；每个 front 有四个 twist。水平 front 的 canonical top 为 UP，UP 的 top 为 NORTH，DOWN 的 top 为 SOUTH。DOWN 明确保留 PM 约定，不直接套用 AE2 的缺省 canonical top。顺时针结果由原 PM 的 front/top 数学表核对，测试从每一个起始方向覆盖完整循环。

只有开启模式且目标是本项目控制器时物品接管交互；还要求 `world.isBlockModifiable` 和 `player.canPlayerEdit`。客户端返回交互结果，服务端提交纯相对变换至 TE 的 owner FIFO，执行到该边界才读取最新方向，因此连续点击按序复合。请求绑定当前 attachment epoch；TE 换代后旧请求失效。方向实际变化会失效旧结构/仓室、更新结构配置与完整 state，后续按既有刷新策略重配，详见[控制器接线](controller-integration.md#朝向与结构刷新)。

服务端接受工具旋转交互时扣配置的旋转耐久。入队不是立即写 World，也不代表晚到或已脱离的请求必然变更状态。当前检查属于 vanilla 编辑权限；PN owner/team 等权限仍须迁移。

## 武器、采掘、附魔与成就

两种模式都提供主手攻击伤害和攻击速度 modifier，并按模式扣攻击耐久。关闭模式取铁镐、铁斧、铁锹的最大挖掘速度，提供 pickaxe/axe/shovel 工具类及采集等级 2，并使用三种铁工具的可采集判定；开启模式挖掘速度为 1，无这三种采集工具类。破坏硬度非零的方块时按模式扣耐久。

附魔值可配置，附魔台接受 DIGGER、WEAPON、BREAKABLE 或 vanilla 基类允许的附魔。附魔书须非空且全部附魔均被接受。开启模式显示附魔闪光，关闭模式仍保留真实附魔闪光。客户端名称、模式 tooltip 和服务端提示均通过实现 `LocalizationEnum` 的 `OrientationToolText` 提供。

关闭模式由服务端玩家攻击实体时，授予 `prototype_eng:cherish_tool` 的 impossible criterion `trigger`；成就不存在或已完成时不重复授予。JSON 保留原 adventure/root、工具图标、task frame、toast、聊天公告与可见性效果，标题/说明使用新的枚举翻译键。

## 配置

[OrientationToolConfig](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/OrientationToolConfig.java) 在 PreInit、物品注册前读取 `config/prototype_eng/tools.cfg` 的 `tools` 分类。以下十项沿用 PM 默认值与范围；伤害和速度是 vanilla 属性 modifier 数值：

| 配置键 | 默认值 | 范围 |
|---|---:|---:|
| orientationToolMaxDurability | 1024 | 1..100000 |
| orientationToolEnchantability | 14 | 0..100 |
| orientationToolAttackDamageEnabled | 20.0 | 0..2048 |
| orientationToolAttackDamageDisabled | 5.0 | 0..2048 |
| orientationToolAttackSpeed | -2.4 | -8..8 |
| orientationToolDurabilityCostRotateEnabled | 5 | 0..1000 |
| orientationToolDurabilityCostAttackEnabled | 5 | 0..1000 |
| orientationToolDurabilityCostAttackDisabled | 1 | 0..1000 |
| orientationToolDurabilityCostBreakEnabled | 5 | 0..1000 |
| orientationToolDurabilityCostBreakDisabled | 1 | 0..1000 |

物品注册时再次应用配置的最大耐久；其余行为读取当前 settings。配置文件与物品 NBT 均不授予机器运行权限。

## 资源与验证边界

客户端 `OrientationToolModels` 在 `ModelRegistryEvent` 安装 `enabled` predicate，切换 `wrench_off` / `wrench_on`，专服物品构造不安装客户端 predicate。三份模型 JSON 与两张 PNG 的来源、署名见[控制器资产](controller-assets.md)。

`OrientationToolOrientationsTest` 以独立 PM top 表覆盖六 front 的四档旋转、每个起点的 24 向循环、其他面重置（包括垂直面）及相对操作复合。实际物品交互、World 方向写入、耐久/附魔/成就和模型渲染未进行游戏内验证；完整构建/MCP 证据由[实施进度](implementation-status.md)记录。

PM 的 [OrientationToolPreviewRenderHandler.kt](F:/clone/PrototypeMachinery/src/main/kotlin/client/impl/render/OrientationToolPreviewRenderHandler.kt) 自述为未默认注册的 UX 实验，源码检索也无注册调用；当前不把它列作已启用默认工具效果。正式结构投影、预览与复杂渲染仍属于后续迁移范围。
