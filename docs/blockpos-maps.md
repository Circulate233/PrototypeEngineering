# BlockPos Map 审查（2026-11-13）

## 集合契约

`util/BlockPos2ObjectMap<V>` 实现 fastutil `Object2ObjectMap<BlockPos, V>`，内部默认且仅使用 `Long2ObjectOpenHashMap`。对象入口直接调用现有 `BlockPos.toLong()`；`toLong(x,y,z)` 按用户提供的原生位宽与掩码编码，不增加坐标验证或不同的编码规则。

`entrySet()`、`object2ObjectEntrySet()`、`keySet()` 与对象 `forEach` 抛出 `UnsupportedOperationException`。遍历直接使用实例的 `long2ObjectEntrySet()` / `longKeySet()`，值遍历使用 `values()`。没有动态识别普通 Map 的 `longEntries/longKeys` 适配器。结构、显示、单格选择及示例放置的相关字段、参数和返回值使用 `BlockPos2ObjectMap<V>`；普通外部 Map 仅在显式构造时转换，强类型复制直接处理内部 long Map。

发布后生产者和调用者遵守只读契约，不再提供 `unmodifiableView()` 或不可变标记。`emptyMap()` 复用共享原生空表。`long2ObjectEntrySet()` 直接返回底层视图；即时消费键/值时优先 `fastIterator()`，迭代器会复用当前 Entry，不得将其保留到下一次迭代。普通 Map 显式构造会实际复制条目，本轮已修复原构造器漏掉 `putAll` 的问题。

```java
var cells = new BlockPos2ObjectMap<StructurePredicate>();
cells.put(existingPosition, predicate);
cells.put(BlockPos2ObjectMap.toLong(x, y, z), predicate);
for (var iterator = cells.fastIterator(); iterator.hasNext();) {
    var entry = iterator.next();
    long position = entry.getLongKey();
    var value = entry.getValue();
}
```

## 来源审查与迁移

| 位置 | 结论 |
|---|---|
| StructurePattern、CT Pattern、JSON pattern、旋转结果 | 注册输入/临时坐标在外部释放后仍被 Map 独占，改为 long 存储。 |
| Pattern/Definition/CatalogView 的显示表、CatalogCodec 解码 | 输入或解码坐标无持续外部所有者，改为 long；序列化、变换和消费者直接遍历 long。 |
| Scanner pendingDisplays、BuilderState 单格选择、Settings 旋转选择、PreviewView 选择复制 | 临时扫描/旋转坐标或上游已为 long，改为 long；相关参数强类型贯通。GuiBuilder 的短期显示表随接口改为同一强类型。 |
| PreviewRayIndex.sections | 分区计算新建键没有外部所有者，直接用 xyz→long；射线遍历解码整数，不构造 BlockPos。 |
| StructurePickWorld.tiles | mutable pos 的独立 toImmutable 与 setTileEntity 输入不能保证由 Tile 持有同一实例，改为 long。 |
| 示例坐标布局、旋转布局及独立构建计划的输出表 | 临时布局/计划释放后输出表仍独占键，改为 long。忽略目录中的 run 源码也纳入检查。 |
| StructureMatching/Refresh 的模拟世界表、控制器更新及 CTM 邻域测试表 | 独立创建的坐标改为相同集合；现有测试跟随强类型签名更新。 |

保留普通对象 Map：`ClientMachineRenderSnapshots.owners` 的 `Owner.position`、`MachineModelHides.leases` 的 `Lease.controller`、`StructurePreviewView.cells` / `StructurePreviewExpansion.positions` / `ProjectionInteractions.indexed` 的 Cell.position、`PreviewBlockAccess.states` 对应的模型 Cell，以及 `StructureBuildPlan.cells` 的 Cell.position，均已有持续强引用。第三方 `MachineModelHides.entries()` 只返回外部集合。run `CoreWorldChecks.samples/counts` 的键由 Sample.position 持有。测试中用于模拟这些外部/共享索引的对象表及显式 JDK 接口验证保留。

不为示例增加有序集合：ExamplesCommand 将控制器保存在独立变量中，先预检全部位置，再放置普通成员，最后显式放置控制器。通用集合没有链表分支或有序工厂。

## 验证

本节下方为早期迁移检查记录；本次复审后的全仓格式化、测试、IDEA 结果以 [实施进度](implementation-status.md) 最新条目为准，证据在 `clean-spoless-review/`。全仓实际消费者已同步新的只读与 fastIterator 契约，独立 addon 也纳入格式化和编译。

证据目录：`C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261113/blockpos-map/`。

- 当前工作树 `compileJava compileTestJava` 成功；完整测试 1888 项，11 项失败，失败清单保留在 `build/test-results/test/`。涉及现有快照复制、配方、注册及恢复约束；本次集合专项通过。不能将该结果记为完整构建通过。
- `BlockPos2ObjectMapTest` 检查混合对象/long 操作、fastutil null/defaultReturnValue、多次更新/删除、MutableBlockPos 快照、原生掩码、对象视图禁用、直接 primitive 视图以及显式复制。
- 本机 IDEA 逐文件 `errorsOnly:false`：18 个正式 Java 全级零；21 个测试中，CTM 邻域测试因 IDE 未解析 CTM 依赖有既有诊断，StructureBuildPlanTest 有现有恒定参数警告，其余全级零。12 个 run Java 被 IDEA 排除，诊断未知，不冒充全级零。完整原始结果见 `idea-results.json`。
- 未启动 Minecraft 客户端/服务端，未推送。原有工作树与暂存内容单独保留。
