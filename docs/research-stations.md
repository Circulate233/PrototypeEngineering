# 研究站材料与推进

研究站通过队伍共享研究服务推进队首。时间、普通资源剩余量和各项网络资源累计量归该队伍的研究目标 `(researchId, targetLevel)` 所有；切换、重排、移出队列和卸载站点均保留进度，再入队继续。完成时才移除该目标的未完成进度。

## 定义与工作速度

`ResearchLevelStage.Builder.durationTicks(long)` 必须给出正数时间，单位为单站基准 tick。`material(AEKey, long)` 添加普通资源总量；也可通过 `materialRequirement(...)` 传静态资源输入。必须使用固定总量输入，禁止 TICK 输入、输出、随机产物或动态需求。相同 AEKey 合并为一种材料，用 `AEKey2LongMap.addTo` 累加并预检溢出。

`ResearchResourceFormula.constant(type, total)` 和 `affine(type, base, slope)` 定义网络资源总量；`perTick(type, limit)` 和 `affinePerTick(type, base, slope)` 定义单站每 tick 上限。总量 R 与上限 u 按基准时间 T 换算：`R = u × T`、`u = R / T`，数量使用 double。等级增长使用阶段内等级偏移。资源键必须复用 `NetworkResourceType` 单例，表为 `Reference2DoubleMap`，累加使用 `addTo`。手动研究禁止网络资源需求。

`ResearchStation` 暴露 `researchLevel()`、`researchRates()`、`energyPerTick()`。默认可继承的 `ResearchStationController(type, level, rates, energyPerTick)` 无配方工位或独立业务 tick。必须在定义完成后只读交付 rates，缺项表示该资源速率为 0；网络设备事件可以修改当前 tick 的有效速率和耗能倍率。

每个服务器 tick，每台实际工作站至多贡献 1 tick 时间工作量。每项网络资源可得到的增量为 `min(普通设备结算后的余额, Σ min(研究单站 tick 上限, 站点该资源速率), 剩余总量, 已支付材料开放的剩余额度)`。各项独立累计，供给不足可部分推进，达标的资源停止占用。时间达到 T 后仍需等待所有材料和网络资源达标。

## 逐项支付与材料汇总

普通材料按资源种类等权开放进度：`开放比例 = Σ(已付量 / 总量) / 材料种类数`。没有普通材料时开放比例为 1。A 总需 2、B 总需 10、C 总需 100 时，扣 1 个 A 开放 1/6，扣完 A 开放 1/3；缺少 A 可以先扣 B 或 C。

本 tick 根据可推进的时间和各网络资源计算所需开放比例，再按可用库存支付完整原生单位。必须按实际支付量减少剩余需求，禁止要求每种材料一起支付。材料不足时，已支付比例以内的时间和落后网络资源仍可推进。即使本次付款尚不足以开放一个完整时间 tick，也由一台站占用 1 并发并支付工作 FE。

`ResearchStations` 读取同队已加载、成型、已获网络授权且等级不低于研究阶级的站，可跨维度共同工作。研究入队需要有效网络中心与等级合格的研究站，材料汇总和推进均不要求数据库；数据库的存在与阶级仅影响独立的下载、导入和设备研究要求。材料汇总包括尚未取得工作并发的合格站；共享输入存储按真实存储身份去重。GUI 的当前可用量为汇总数量与剩余需求的较小值。

## 调度与提交

计算机和活动数据库优先取得基础并发。普通设备完成当 tick 的工作后才运行研究；研究站不收回普通设备租约，只使用剩余并发和资源。每台实际贡献站占 1 并发，无贡献的候选站释放租约。

`ResearchStationWork.advance` 同步预留材料、实际参与站 FE 和网络资源，然后通过 `ResourceTransactions` 与队列进度、完成资格共同提交。提交前授权失效或任何预留失败时回滚本次预留，已经提交的历史材料不重复扣除。同队每服务器 tick 最多成功结算一次。

## 保存、界面与示例

世界、队列和手动会话保存目标、long 时间、材料剩余量、double 网络累计量及手动材料凭据，不写入或检查固定格式版本号。研究站不保存材料配方进程、物理站点恢复记录或永久破坏清理回调；当前推进只读取本次可用站点。禁止自动删除研究数据。

研究站正式 GUI 已按[获批布局](research-gui.md)接入，默认 `ResearchStation.DEVICE` 自动选择对应容器和界面。固定定义使用 AE `PacketWritable`，后续只发变化行索引和数值；队列管理调用服务器权限检查，切换和移出保留进度。

Java 示例见 `run/visual-examples/src/main/java/pe/visual/examples/ResearchStationExamples.java`，包含五项可排队研究、混合总量/tick 网络定义及不等数量材料。ZS 示例见 `run/scripts/30_pe_run_network.zs`。验证证据与尚待实机核对的事项见[实施进度](implementation-status.md)。
