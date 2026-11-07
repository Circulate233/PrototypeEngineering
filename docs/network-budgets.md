# 每 tick 计算预算与工作凭据

本阶段将 PN 数值预留和机器 lease 接入真实 server tick 与 NetworkRuntime，机器组件可取得 owner 工作凭据。普通配方已由 NetworkRecipeWork 接入真实需求阶段；研究站与完整正式 planned 仍待接入；`allowsNetworkWork()` 继续只表达网络资格，不能作为已有数值额度的证据。

## tick 与发布

独立宿主先 `NetworkRuntime.beginTick(tick)`，在合法 owner 边界调用任意次数 refresh，所有已接受 worker 实际 join 后调用 endTick。tick 必须递增，未结束时不能再次 begin；结束/会话 close 撤销发布并关闭本 tick 账本。旧 receipt 仍可原代补偿，不能继续预留或提交。

内置 ServerTaskSchedulers 每个真实 server END 事件只递增一次会话内 long 序号；begin/end 包住完整 scheduler.tick。每次 MAIN action 前与 CONCURRENT 捕获前仍分别 refresh。序号不使用世界时间，不受改时间或多次捕获影响；新服务器会话创建新 runtime。

每队伍首次成功发布时，以该次完整供给创建 NetworkTeamBudget。同 tick 后续发布保留第一份容量、余额、lease 与操作键。捕获失败、revoke、队伍暂时消失后返回、中心供给改变都不补充额度；下一真实 tick 才采用新供给。新出现的队伍首次成功发布时建立自己的账本。

## lease、数值与操作身份

- 用户确认的消费规则：算力等数值资源随配方并行量缩放；并发资源（标准 THREAD）按机器只占 1，不随并行量、工位数或 Java 工作线程数重复占用。NetworkRecipeWorkSystem 已按实际进程并行量缩放；START/扫描只检查，工作 TICK 占用，零时长 START 占用一次，见[配方消费者](network-recipes.md)。
- `requestLease(machineId)` 使用 THREAD；同一机器本 tick 只消耗一份，其他工位为 ALREADY_GRANTED。没有工位结束即释放的操作，数值不足/取消也不退 lease，保持 PN 请求顺序。
- `requestLease(machineId, logicalType)` 支持已注册自定义 logical 维度，各维独立机器集合和 long 容量，不再只支持 THREAD 或强制 int。一次申请一维，不代表多维 lease 原子申请。
- WorkPermit 对活动中心免标准 THREAD lease，返回 NOT_REQUIRED；自定义 logical 仍实际申请。其他角色在普通数值预留前真实申请 THREAD，不给所有已加载设备预分配额度。
- `numericResources()` 为本 tick 初始不可变研究供给；普通预留扣另一份 availableResources。保留 PN 两份 numeric 预算、共享 lease；研究分配算法尚未接入。
- `reserve(Operation, required, authorized)` 先校验完整 numeric 向量和全部余额，再整组 addTo 扣减。不足返回 null，不部分扣减、不占操作键。空需求允许无数值成本的授权凭据。

Operation 包含 machine UUID、稳定 source ResourceLocation 和 sequence；source 表示工位/来源，sequence 区分同 tick 新一轮工作，不能拿 capture epoch 作工作序号。成功预留后本 tick 同键不可再次使用，包括已补偿的键；未接受的申请可以原键重试。账本只保存去重键，不反向保存 receipt，避免已结束操作滞留旧 Publication 与宿主。

## 当前代提交与原代补偿

owner 从 MachineNetworkDevice.workPermit 或准确 Registration 取 WorkPermit，网络拒绝时为 null；组件入口还检查实际绑定。凭据绑定准确 Registration、identity revision、Publication 和本 tick 队伍账本。worker 可读 current()，但它只检查发布/身份，不能据此直接消费。

requestLease、reserve 和 receipt commit 在 owner 上重验该 Publication 的全部 Capture.current，包括依赖中心与供给设备。任一失效或抛错撤销仍生效的原发布；若回调已生成新发布，不撤销新发布。proof 仍须遵守只读契约。

commit 只接受 RESERVED：授权或账本过期返回 false 并保留 RESERVED 供撤销；成功变 COMMITTED，重复/终态提交为调用错误。rollback 可撤 RESERVED 或补偿 COMMITTED，完整退款预检后仅向 issuing book addTo，重复调用幂等。它不要求当前授权，但仍限定 owner；旧 tick 退款不会进入新账本。

可变集合和 receipt 状态由 owner 管理，使用 fastutil；worker 只读不可变供给、操作身份和发布状态，不能直接操作余额、lease、receipt 状态。原 TaskScheduler worker、同步与亲和 lanes 保持。

## 验证与后续

新增 8 项行为测试覆盖多维事务、每机器/维度 lease、long 容量、不可变快照、操作键/轮次、过期提交与原代补偿、同 tick 重捕获/失败/队伍返回/供给改变、身份复制 ABA、依赖中心 proof 失效/异常，以及真实调度器两次 MAIN 和 CONCURRENT 准备。原复制存档集成测试增加实际组件 WorkPermit、旧 receipt 撤销和恢复后的新 receipt；没有 getter/常量/hash 专项测试。

当前每次 owner 许可操作会重验本发布的全部宿主证明，多次 MAIN 捕获和许可申请都有 owner 成本，尚无性能压测。正式 planned 必须在 prepare/resolve 两次 join 与批次决策边界整合校验，并按真实变更版本减少重复工作，不能凭未提供的变更通知省略证明。

普通配方数值与实际数据中心研究需求已接入，receipt 与本阶段材料/FE 一同提交补偿，并检查提交前后的许可。继续接研究分配、数据中心下载/同步的服务器入口、完整参与者当前代校验和完整 planned prepare。运行许可不从存档恢复。旧实现审计见[计算资源](network-resources.md)，证据见[实施进度](implementation-status.md)。
