# 网络资源与显示包

2026-11-13 用户修订：资源无需注册，库没有默认算力、量子位等资源。并发是独立整数限制，不放入资源表。数值资源、研究需求与速率使用double；物料身份和数量仍用AEKey / AEKey2LongMap。

## 资源接口

`NetworkResourceType` 由开发者创建并复用为单例，提供id、internal，并实现AE的 `PacketWritable`。资源表使用 `Reference2DoubleMap<NetworkResourceType>`，按对象身份处理，默认返回0，累加和扣减使用 `addTo`。这些是Javadoc契约，不执行单例、重复id、canonical或资源冻结检查。内部计算机资源仅在本机产生和占用，网络输出排除内部资源。

```java
static final NetworkResourceType COMPUTE = NetworkResourceType.network(
    new ResourceLocation("example", "compute"), Text.COMPUTE, "%.2f FLOP/t");
static final NetworkResourceType COOLING = NetworkResourceType.computer(
    new ResourceLocation("example", "cooling"), Text.COOLING, "%.1f CU/t");
```

默认 `FormattedNetworkResource` 固定包包含id、内部属性、翻译键和数值格式；客户端用 `LocalizationEnum.getLocal()` 获取名称，`format(double)` 返回数量字符串。自定义Java资源可自行实现客户端名称、格式化方法与 `writeToPacket(ByteBuf)`。

自定义包布局由 `packetType()` 标识，使用方在两侧mod初始化时调用 `NetworkResourcePackets.register(packetType, decoder)` 提供解码函数，例如自己的 `Resource(ByteBuf)` 构造器。这里只登记包布局的解码器，不登记资源实例或逐项资源id；默认格式包的解码器已内置。固定数据在单例生命周期内保持不变，解码器完整读取对应包。客户端重建对象仅用于展示，不替代服务端资源表的单例。

## 网络中心增量显示

容器直接遍历网络R2D供给表，隐藏产出为0的资源。首次显示/新增资源发送固定展示包和初始占用、上限；随后 `NetworkCenterResourcePacket implements PacketWritable` 只发送变化资源的索引、字段标记与对应double。只变占用不重复发送上限，只变上限不重复发送占用。资源归零时发送移除索引，新AE容器监听者取得完整基线；没有变化不创建更新包或行列表。

客户端保留展示对象与行顺序，增量更新原行；成行文本仅在数值变化或GUI重新初始化后生成，不经ITextComponent数组或每tick字符串传输。宽384、高162～242的显示屏无玩家背包，超过5行才显示滑块，滚动完全在客户端完成。占用读取在该tick工作join后、预算关闭前执行。

状态分别显示能源不足、未成型、所有者队伍无效等原因；尚未取得当前发布状态时显示状态未就绪。缺电中心保留INSUFFICIENT_POWER诊断，仍不获得工作许可；原选择与费用规则不变，悬停能源不足可查看供电说明。

## 供给与预算

`NetworkComputerComponent.Capture(concurrencyPerItem, resourcesPerItem, requiredResources, energyPerTick, current)` 分别定义整数并发、double供给、本机消耗、FE与版本证明。物料安装数量仍按long汇总；供给乘安装数量后用 `addTo` 合并。单件组件、计算机和研究站资源量没有固有上限，保留非负、有限数量和实际算术溢出预检。

默认计算机只从运算仓读取实现 `NetworkComputerComponent` 的物品，结构绑定与组成事件更新捕获，稳定库存复用结果。已有外部物品适配表仍可使用，其 `freeze()` 不再依赖资源表。全部组件消耗不足时组件供给全部停摆，能运行的供给配方独立继续，见[计算机](computers.md)。

普通配方余额与研究供给独立，但共享整机并发。账本按真实服务器tick管理，同tick容量变化保留已消费/预留量，补偿只回原代，见[预算](network-budgets.md)、[配方](network-recipes.md)与[研究站](research-stations.md)。研究队列保存资源id和double进度，恢复时从研究定义引用的单例解析，无全局资源注册表。

ZS入口为 `ComputingResource.network(id, translationKey, pattern)` / `.computer(...)`；Java自定义资源可通过 `ZenComputingResource` 包装器提供给脚本。数量用 `ComputingResources.create().add(type, amount)`，并发在控制器/组件/参与者中单独定义。同步、构建和静态检查证据见[实施进度](implementation-status.md)，画面与真实World行为由用户复验。
