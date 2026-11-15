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

Tier 0 手动研究只支持原来的严格固定物品输入，不支持机器来源分配。定义校验、手动投料模型和手动材料显示都明确拒绝 SINGLE_SOURCE/CUSTOM，而不是忽略它们。正阶研究也只接受固定总量资源输入，跨同队研究站汇总付款，不执行材料配方来源策略。

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

`Context` 暴露阶段、投影序号、资源键（FE 为 null）、有效数量与不可变来源列表。每来源包含本次来源下标、首次快照 revision、当前投影剩余可提量、首次投影前的完整 AEKey 余额，以及独立 FE 的原余额与外部传输上限；FE 可分配量只取未预留电量，maxExtract 仅提供外部端口信息。完整资源表由 `beginQuantityCapture` 和后续预算化复制得到，不能通过回调修改；前面投影的预留不会改写首次快照，只影响后面投影的剩余量。来源下标仅用于本次分配返回；实际预留仍使用获取游标持有的 StorageContext 和快照。

数量表直接使用 AEKey2LongMap。`ImmutableResourceAmounts` 现仅为原生map构建帮助类，build 按契约移交已完成表，不再创建自定义防写map、逐条Entry副本或关闭builder；生产者和消费者都不得再修改交接后的表。`StorageSnapshot` 不在构造时再次复制已经捕获的数据，真实库存快照仍由存储在其读取边界构建。

2026-11-13 防御性编程审查删除了位置标签和整套绑定协议：TE 不保存 allocationLocation/loadedLocation，StorageContext 不再持有 sourceIdentity/sourceIdentityBound，也不因设置诊断标签修改 revision、使库存失效或重新挂载。Java/CT 的分配来源不再暴露 identity 文本；按来源下标和完整冻结库存等实际数据选择。混合仓两侧及同余额别名仍通过真实 context 区分和去重。

自定义策略的总可用量仅累计到本次需求，避免多仓各自合法的大库存相加溢出而拒绝小额扣料。回调返回数量、实际库存变动与预留仍保留必要的溢出检查。样板总成另有实际参与隔离组匹配的分区 ID，由该存储自身保存恢复，和已删除的诊断标签无关。

## 原执行协议

捕获、每来源/每投影选择、逐项分配验证、原生预留均沿原预算游标续办。custom 回调和其返回列表冻结是显式合作边界。已捕获的实际来源必须在预留前重新验证 context/revision；实际原生预留量必须等于分配量，否则原事务受阻并保留准确清理义务。

原生 SlottedResourceBuffer 的全仓提取模拟仅查询 totals−extractHolds。该 final 存储没有额外提取过滤或速率规则，索引与逐槽可提量等价，因此模拟是 O(1)；实际写入和指定单槽请求仍保留原逐槽检查。外部 legacy 存储的模拟仍是明确合作回调，不把它描述为原生预算化实现。

策略不持有原生存储、TileEntity、可变进程、锁、lease 或 reservation；这些都留在 owner 获取游标。得到的 tokens 仍交给原 RequirementTransaction / ResourceBatch，保留 source affinity、共同提交、逆序补偿、两次屏障、预算让出、取消、UNKNOWN 和真实退出边界。没有第二套 planned coordinator。

## 验证入口

`InputAllocationTest` 覆盖同步及真实 planned 单工作单位路径的拆分/单来源差异、首个足量来源、别名去重、独立同 key/long 投影、自定义比例与完整快照选择、非法分配结果、FE 限额与各阶段、overlay/最终并行量、概率保守预检、陈旧来源拒绝和跨宿主补偿。2026-11-13 删除旧标签协议及防写包装测试，旧标签重建夹具整体删除；真实存档恢复由仓室/能源测试覆盖。大库存案例改为正常小额分配成功，快照保留真实库存后续变化的验证，4096 槽稀疏原生仓的实际探针/planned执行也保留。本轮实际运行结果见 [防御性编程审查](defensive-programming-audit.md)。

2026-11-10 最终关联111 tests/11 suites及完整1744 tests/224 suites均零失败、错误、跳过；22个所属Java与本批其余文件本机全级检查全部零诊断、完整读回一致，MCP build成功。原失败和修复边界见 `build/staging/input-allocation-policy/test-runs/results.md`；统一构建证据见 `build/staging/resumed-goal-plan/unified/third-results/`。
