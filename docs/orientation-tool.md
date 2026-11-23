# 控制器方向工具

[ControllerOrientationTool](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/item/ControllerOrientationTool.java) 注册为 `prototype_eng:orientation_tool`，单件堆叠，使用原 PM 的开关扳手模型、武器/铁质复合工具效果及配置默认值。库不为它添加制作配方。原行为入口为 [ControllerOrientationToolItem.kt](F:/clone/PrototypeMachinery/src/main/kotlin/common/item/ControllerOrientationToolItem.kt)、[OrientationToolTuning.kt](F:/clone/PrototypeMachinery/src/main/kotlin/api/tuning/OrientationToolTuning.kt) 与 [TwistMath.kt](F:/clone/PrototypeMachinery/src/main/kotlin/common/util/TwistMath.kt)。

## 两种模式与方向操作

缺省关闭，开关保存在物品 NBT 的 `enabled` Boolean。潜行右键空气在服务端切换模式，发送本地化状态提示并播放原 UI_BUTTON_CLICK 音量/音调；不潜行时返回 PASS。新格式不解码 PM 的 `pm_orientation_tool_enabled`，无旧存档兼容承诺。

开启时，工具在目标方块被指向的面上绘制九宫格。普通和潜行右键都按点击位置选择目标方向，由 [OrientationToolOrientations](../src/main/java/com/circulation/prototype_eng/item/OrientationToolOrientations.java) 计算 AE2 `BlockOrientation`：

- 点击当前正面的中央，正面不变，绕正面顺时针转一档。
- 点击其他面的中央，正面转向点击面，顶部随方块一起转动。
- 点击上、下、左、右边格，正面转向相邻的面，顶部随方块一起转动。
- 点击角格时方向不变。

九宫格的上方在水平面和 `UP` 面分别为 `UP` 和 `NORTH`，在 `DOWN` 面为 `SOUTH`。四个边格以玩家从外侧看该面的方向为准。

只有开启模式时工具才接管控制器或已注册旋转回调的外部主方块的交互。服务端须通过 `world.isBlockModifiable` 和 `player.canPlayerEdit` 检查；控制器的相对变换继续在其任务队列执行时读取最新方向，接受旋转请求时消耗耐久。方向变化后的重配见[控制器接线](controller-integration.md#朝向与结构刷新)。

外部主方块的客户端九宫格与服务端点击使用同一方向限制。四向只接受水平朝向。四向和六向在已对准的正面中央不显示滚转箭头，不可用的点击区域不高亮。二十四向支持绕正面滚转。服务端从当前方块状态和可空的方块实体读取朝向，再调用注册的旋转回调；回调返回 `true` 才消耗耐久。回调可以拒绝已成型结构的旋转，主方块转向时不会移动周围的已放置方块。默认固定的外部主方块不显示旋转九宫格，右键继续交给方块。注册方法见[外部主方块注册](structure-tools.md#外部主方块注册)。

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

`OrientationToolOrientationsTest` 验证原版六个面上的四档滚转、点击边格、相对变换以及外部主方块方向限制。游戏内复验时分别用四向、六向和二十四向锚点点击中央与边格，检查无效点击不消耗耐久、有效旋转后构建器读取新朝向，以及固定锚点的原右键交互。

PM 的 [OrientationToolPreviewRenderHandler.kt](F:/clone/PrototypeMachinery/src/main/kotlin/client/impl/render/OrientationToolPreviewRenderHandler.kt) 自述为未默认注册的 UX 实验，源码检索也无注册调用；当前不把它列作已启用默认工具效果。正式结构投影、预览与复杂渲染仍属于后续迁移范围。
