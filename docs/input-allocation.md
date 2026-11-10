# 配方输入来源分配

Java 定义通过 `ResourceRequirement.withInputAllocation` / `EnergyRequirement.withInputAllocation` 指定策略，既有构造调用仍使用 `InputAllocation.SPLITTABLE`。本轮实现源于 PM `ConsumableAllocationPolicy`、`DeterministicConsumablePlanner` 以及真实 planned/distributed 来源预留消费者，不迁入 PN 开发配方本身。

当前代码及验证交接记录位于 `build/staging/input-allocation-policy/`。独立复核提出的 entry 可写和原生模拟超预算问题已修正。统一构建后的测试反馈已处理，最终获准专项及直接关联验证为 111 tests / 11 suites，全部通过；日志、各轮失败 XML、预算诊断和最终 MCP 记录见 `test-runs/results.md`。完整全仓复跑由主代理继续，未启动游戏。

## 实际规则

| 策略 | 输入行为 |
|---|---|
| SPLITTABLE | 保持既有输入顺序和跨来源拆分行为。 |
| SINGLE_SOURCE | 每个独立输入投影从首个足量、可提取的来源完整获取；各投影可以使用不同来源。 |
| CUSTOM | 纯回调从冻结事实返回每来源数量，框架完整验证后才沿原来源预留。 |

投影是输入列表中的一项，不是整张配方或整台机器。相同 AEKey 的两项输入也分别处理；固定输入的非默认策略不会为一个不需要的合并总数制造 long 溢出。每项实际数量继续使用既有并行/概率缩放规则。概率保守预检与最终消耗使用相同来源策略；输出路由和 voidExcess 不受输入策略改变。FE 保持独立 long 数量，不引入 FE AEKey。

模糊/动态输入仍先沿原选择器确定并锁定资源键，分配策略随后处理这些已选投影。策略不会回溯或改写键选择。检查点、选择器以及 IO overlay 均使用相同需求系统；overlay 修改数量时保留原输入策略。

Tier 0 手动研究只支持原来的严格固定物品输入，不支持机器来源分配。定义校验、手动投料模型和手动材料显示都明确拒绝 SINGLE_SOURCE/CUSTOM，而不是忽略它们。正等级研究站的完整材料需求仍沿实际材料周期执行来源策略。

## 自定义策略

```java
var allocation = InputAllocation.custom(new ResourceLocation("addon", "prefer_marked_input"), context -> {
    var sources = new ObjectArrayList<>(context.sources());
    sources.sort(Comparator.comparingLong((InputAllocation.Source source) -> source.resources().getLong(markerKey)).reversed());
    var portions = new ObjectArrayList<InputAllocation.Portion>();
    long remaining = context.amount();
    for (var source : sources) {
        long amount = Math.min(remaining, source.available());
        if (amount > 0) portions.add(new InputAllocation.Portion(source.index(), amount));
        remaining -= amount;
        if (remaining == 0) break;
    }
    return portions;
});
var payload = new ResourceRequirement(inputs, outputs).withInputAllocation(allocation);
```

回调和其捕获状态必须在定义存续期间不可变；它是 owner 上的一次合作回调，不能访问世界、执行 START、预留或改变存储。扫描探针、概率预检及以后重新尝试均可能再次调用它。回调返回正数分配，总额必须准确等于本投影需求；空列表、部分结果、重复/不存在的来源、超出余额/需求或算术溢出会明确报错。普通总量不足在回调前返回既有受阻结果。

`Context` 暴露阶段、投影序号、资源键（FE 为 null）、有效数量与不可变来源列表。每来源包含身份文本、首次快照 revision、当前投影剩余可提量、首次投影前的完整 AEKey 余额，以及独立 FE 的原余额/操作上限。完整资源表由 `beginQuantityCapture` 和后续预算化复制得到，不能通过回调修改；前面投影的预留不会改写首次快照，只影响后面投影的剩余量。来源下标仅用于本次分配返回，不允许保存作后续授权。身份、revision 与余额也都不是运行授权。

数量表统一使用拥有数据的 `ImmutableResourceAmounts`。它沿用 ManualMaterials 中 BasicEntry 快照的防写模式，同时覆盖 primitive 和 boxed entry 视图；迭代器、数组和 stream 不会泄露底层可写 entry。公开 `StorageSnapshot` 和原生 `QuantityCapture` 也共用它。已有不可变表可 O(1) 复用；预算复制用 builder 逐项 addTo，build 只转移所有权，不额外整表遍历；entry 迭代时每项产生一个不可写条目。

原生仓在 world 已实际持有该 TileEntity 的最终位置之后、能力返回前，以仓室种类、维度、坐标和输入/输出侧绑定稳定身份。混合仓的两侧不同；同一余额的别名仍共享 context 和身份。`StorageContext.bindSourceIdentity` 只允许首次绑定，推进 revision，使之前捕获的事实失效；不同身份不能重绑，存在活跃预留或 fence 时也不能首次绑定。身份标签绝不替换实际 context 身份、锁顺序或授权证明。

NBT 重载、初始变体替换、卸载/重新挂载继续使用原来的旧 context 失效与新 context 构建路径，在同一物理位置重新生成相同标签。已绑定位置或 world 变化会先 invalidate；移动后的标准 validate/重挂载重新建立存储与标签，旧能力引用不能复活。标签不存作运行权限。纯内存来源可在使用前显式绑定自己的稳定名称；未绑定来源使用明确的 `lifetime:` 标签，只承诺本 context 存续期稳定。

## 原执行协议

捕获、每来源/每投影选择、逐项分配验证、原生预留均沿原预算游标续办。custom 回调和其返回列表冻结是显式合作边界。已捕获的实际来源必须在预留前重新验证 context/revision；实际原生预留量必须等于分配量，否则原事务受阻并保留准确清理义务。

原生 SlottedResourceBuffer 的全仓提取模拟仅查询 totals−extractHolds。该 final 存储没有额外提取过滤或速率规则，索引与逐槽可提量等价，因此模拟是 O(1)；实际写入和指定单槽请求仍保留原逐槽检查。外部 legacy 存储的模拟仍是明确合作回调，不把它描述为原生预算化实现。

策略不持有原生存储、TileEntity、可变进程、锁、lease 或 reservation；这些都留在 owner 获取游标。得到的 tokens 仍交给原 RequirementTransaction / ResourceBatch，保留 source affinity、共同提交、逆序补偿、两次屏障、预算让出、取消、UNKNOWN 和真实退出边界。没有第二套 planned coordinator。

## 验证入口

`InputAllocationTest` 包含 23 项行为测试：同步及真实 planned 单工作单位路径的拆分/单来源差异、首个足量来源、别名去重、独立同 key/long 投影、自定义比例与完整快照选择、无效结果/溢出、FE 限额与各阶段、overlay/最终并行量、概率保守预检、陈旧来源拒绝、跨宿主补偿，以及身份绑定/重复身份/重排/重建。复核补充覆盖两种 entry.setValue 被拒后后续投影不变、外部包装表的拥有式复制、两个原生快照出口不可写，以及 4096 槽稀疏原生仓在两种策略下的单单位真实探针/planned 执行。每步实际空槽访问 ≤1；总步数分别容纳原获取、提交和清理历史的真实计费，不使用 hash/getter 或时间阈值判断。能源实体通过位置事实覆写执行 headless 生命周期；资源实体完整配置依赖 AEConfig，纯测试改由既有可配置 BufferedResourceHatch 的保存/加载/退休及标签绑定边界验证，不把它声称为资源实体或实机验收。

2026-11-10 最终关联111 tests/11 suites及完整1744 tests/224 suites均零失败、错误、跳过；22个所属Java与本批其余文件本机全级检查全部零诊断、完整读回一致，MCP build成功。原失败和修复边界见 `build/staging/input-allocation-policy/test-runs/results.md`；统一构建证据见 `build/staging/resumed-goal-plan/unified/third-results/`。
