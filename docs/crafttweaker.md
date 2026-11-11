# CraftTweaker 使用与验证边界

CT2 4.1.20.718为可选脚本桥；公开Java API及Forge注册subscriber不链接CT类型。脚本声明共用机器/装配、配方池、结构、研究和网络的既有Java注册/校验/执行/codec。库不自带正式机器、配方或玩法数值；GUI DSL和独立Modern不恢复。

## 声明与运行

注册入口排入DefinitionRegistrations的对应event，按实际阶段dispatch；结构predicate先注册再freeze/解析JSON。名称解析得到具体不可变定义和闭包；旧进程使用准确定义、descriptor及历史codec，不能运行时按名称取最新回调。机器声明及装配配置各自安装同一组件/codec；服务器更新、仓室配置和客户端manifest沿既有准确session/owner边界，pending或拒绝不冒充成功。

## 完整结构校验

`mods.prototype_eng.structure.Predicate.custom(facts, queries, offsets, controllerOffsets, validator)`先声明所有可能读点。`Query.create(id, capture)`的capture在真实owner采样，worker validator只读冻结Facts；NBT入/出均复制。Facts包含当前base绝对坐标、实际controller与相对offset；`at(...)`使用会随结构旋转的local offset，`atWorld(...)`使用绝对坐标，biomeName保留名称。旧Java两参及budget hooks保持原职责；未可用事实和采样/回调错误仍区分。不要从未知matcher执行结果猜预览材料；需要时显式使用Java withDisplay或JSON display。

718把`any`作为类型保留字，脚本OR使用已验证的别名：

```zenscript
import mods.prototype_eng.structure.Predicate;
var gate = Predicate.anyOf([Predicate.notAir(), Predicate.hasTileEntity()]);
```

`gate.register("your_addon:gate")`进入同一个启动predicate registry，JSON按该ID及空参数引用。真实任意query、旋转nested base/controller及注册前不执行validator的Compiler行为入口为ZenCustomStructureBridgeTest。

## 高级配方与输入分配

Requirement共用完整weighted随机输出、chance、attribute三operation和原selective/overlay效果。CUSTOM source facts和资源数组隔离；Java planner继续预验source索引、重复份额、容量、总量与溢出。独立FE用`context.isEnergy`识别；`context.resource`在FE时为空，不用脚本nullable对象相等代替typed判别。匿名SAM返回显式原生数组，例如以下回调片段（调用方仍须提供满足需求的source）：

```zenscript
import mods.prototype_eng.recipe.AllocationPortion;
return [AllocationPortion.create(context.sources[0].index, context.amount)] as AllocationPortion[];
```

该片段放入`InputAllocation.create(id, function(context) { ... })`，经Requirement.customInputAllocation连接。partial resource/FE overlay共用tracked patch，未覆盖字段保持；外部需求/overlay按确切Java type、path和codec绑定。END效果到真实完成阶段才移除；替换定义不重绑旧codec/闭包。

## 研究、授权与原生网络

计算resource/component、研究metadata/stage、资格/材料/奖励和item-download声明沿原registry。ResearchServer.permissions回调收到服务器捕获的actor/team/目标/leader/moderator/operator事实，`standardDecision(action)`显式回落原默认策略。ResearchWorld与session冻结同一policy；PREPARED下载/磁盘恢复按当前准确owner/session和原来源重验，已支付义务沿原代继续补偿。奖励领取及manual takeover仍由原actor/持久回执授权协议负责。

NetworkConfiguration五角色为NETWORK_CENTER、COMPUTER、RESEARCH_STATION、DATA_CENTER、DEVICE；它安装MachineOwnership、真实device/work和对应计算槽/数据库组件。供给向量不是伪造owner或已提交槽内容的授权，DATA_CENTER使用原CODEC与持久化barrier，gate使用准确研究对象/priority；NETWORK_CENTER和RESEARCH_STATION级别须正数，DATA_CENTER容量须正数，其它role容量为0。

## 验证范围

本批真实SDK Parser/Compiler→SAM→canonical Java运行行为随正常build验证：1918 tests/261 suites全零，60Java本机MCP全级零/全文和普通bytes匹配，MCP build成功。测试fixture在首GenericRegistry载IData及SDK的ExpandAnyDict、ExpandDataStringDict、ExpandInt、ExpandBool、ExpandString五个实际扩展，保留SDK本身caster/equality/array机制，避免不完整环境先缓存primitive castRules。上述fixture环境不是完整FML/CT原生运行初始化。

第二阶段普通data INDEX/member/IData已有真实Compiler同helper验证；SDK718必要的单类Mixin注入仅核descriptor/profile及调用helper，用户仍需重启客户端验收实际注入。World sampler使用owner线程的合约已有行为证明，真实World/FTB/native数据库/网络、保存和客户端同步及GUI/HEI/视觉仍待游戏复验，由用户自行启动。总体Goal仍active；完整scope独立审计继续。

最新源码提交、准确manifest/原始XML/MCP/保护证据从[实施进度](implementation-status.md)及`C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261111/resumed-goal/ct-third-exact/`进入。源码阅读从`integration/crafttweaker/{structure,recipe,resource,research,network}`与对应`api`开始。
