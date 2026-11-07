# 组件与结构端口扩展迁移

本专题记录 2026-11-07 恢复的两个公开库扩展职责。源码以当前 Java API 为准；旧 Kotlin 实现只提供行为依据。组件类型仍使用已有 `ComponentType`，不增加镜像 Impl、反射查询或另一套类型注册表。

## 外部组件的调度亲和来源

PM 的 [AffinityKeyProvider.kt](F:/clone/PrototypeMachinery/src/main/kotlin/api/machine/component/AffinityKeyProvider.kt) 允许组件声明共享资源来源；[MachineInstanceImpl.kt](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt) 遍历运行组件，scheduler 按相等的键并组。此前 PE 只从 MachineHatches 的资源/FE storageIdentity 捕获来源，外部设备和缓存无法声明同一来源。

现在 `api.machine.component.AffinityKeyProvider` 只有 `Set<?> affinityKeys()`，需要该职责的组件才实现它。MachineInstance 先按现有 hatch 顺序收集键，再按 `components().assemblyBindings()` 的稳定装配顺序调用 provider，并立即复制到同一个 fastutil 有序集合、去重后返回不可变集合。不会按组件图 revision 缓存 provider 结果。结构端口集合也聚合其端口实现的同一接口。

捕获由现有 TaskScheduler owner 完成，机器控制器继续委托 MachineInstance。相等键仍由现有 union-find 分组，任务按注册顺序进入同一 lane；没有另建锁、executor 或调度机制。集合和键不能为 null，空集合合法；捕获必须无副作用，键的 equals/hashCode 及其实际资源指向从捕获到全部 worker join 期间稳定。来源变化只能发生在已 join 的 owner 边界，下一轮重新捕获立即生效。provider 异常沿现有 scheduler 故障与 join 收束路径传播。

## 任意轻量结构端口

PM 的 [StructureComponentProvider.kt](F:/clone/PrototypeMachinery/src/main/kotlin/api/machine/component/StructureComponentProvider.kt) 在 owner apply 阶段为实际成型位置创建任意轻量端口；旧 Map 的 `getByInstanceOf` 被 PM 材料需求和 PN 计算物品消费者使用。此前 PE 的 StructureAssembly 只有匹配树和三类 hatch 绑定，无法公开任意外部端口。

现在 `api.structure.StructureComponentProvider` 提供 `createStructureComponents(Context)`。Context 保存机器、不可变 providerPos 和正数 formationGeneration；Entry 保存已有 canonical `ComponentType<MachineInstance,C>`、非空白的 provider-local String portId 和非 null 的 C。端口无需实现空的 marker 接口，也不会执行类型 factory/system。

`MachineStructureHost.structureComponentsAt(position)` 是可选发现职责。固定世界的 ControllerLifecycleHost 在已加载位置取得当前有效 TileEntity 的公开 provider；原 hatch capability 查询链继续工作。StructureRefresh 在当前匹配实际位置中排除控制器，按 x/y/z 稳定排序，收集全部 hatch 和 provider。每个 provider 返回的集合立即复制，最终重新验证匹配事实、hatch 合同及 provider 身份（包括此前缺席）；失稳整批重试，异常使当前机器故障，均不能安装部分端口。

`StructureAssembly` 同时携带匹配树、MachineHatches 和不可变 StructureComponents。生命周期在原 exclusive gate 内预检完整绑定的 owner 和 generation，再安装全部集合；真正 formed 变化的 listener 能看到完整新批。重复位置/type/portId、重复端口实例及同 ID 的非 canonical 类型被拒绝。

查询入口是 `machine.structureComponents()`，可取得完整 `bindings()`、按 canonical 类型的 `get(type)`（类型索引随完整候选预建）、或准确 `find(type, position, portId)`。每个 Binding 保留完整 Context 和 Entry，类型查询只用 descriptor 身份保证泛型安全，不按 Class 反射。外部消费者可保存绑定上下文用于准确端口身份判断；旧快照只是旧引用，不能赋予刷新后的权限。

## 刷新、移除与存档边界

每次接受的 refresh 都重新创建整个端口集合，即使朝向和 formed 没变化。旧快照保持不可变，同一轮新集合按稳定顺序发布。provider 缺席会从新集合移除；当前完整失配、显式 invalidate 以及卸载均清除运行绑定。普通 tick 组件、工位进度和已有事务义务沿原生命周期处理。

formationGeneration 只在 false→true 的成功成型时递增；同一成型内的刷新沿用原代号。候选被拒绝或创建失败不消耗代号。递增使用 Math.incrementExact，并在修改集合之前预检。MachineAssembly 的 Version 2 存档要求 exact-long、非负 `FormationGeneration`；恢复只能在 unpublished assembly 边界进行。StructureComponents 和 MachineHatches 作为派生组件均不写入存档，也不能声明为持久运行组件。恢复形成次数不会恢复 formed、结构配置或 worker 授权，新实体仍须重新匹配和创建端口。

provider 创建必须为可丢弃的观察性操作，不可注册外部状态或修改世界/机器。这里恢复的是轻量端口发现与消费职责，不引入额外的 tick、注册/卸载回调。端口自身的资源操作仍必须服从已有线程、事务和权限合同。

## 验证

本轮新增 8 项行为测试已通过；连同机器装配、生命周期、真实调度及协作仓室回归共 80 tests / 12 suites，失败、错误及跳过均为 0。构建前后核验 50 份关键 Java SHA256 一致，证据在 `build/staging/component-extension/targeted-test-result.json`、`targeted-test-results/` 与 `pre-test-sha256.json`。

最终检查与完整构建已完成。原 IDEA 写操作超时来自 Git 新文件确认对话框阻塞 write action；用户处理后已将全部关键 Document 与磁盘核对一致，迟到的测试写入按已验证文本修复。本轮 40 个文件的受限格式规范化只改变空白/换行，Java token 与 import 集未变：11 个扩展新 API、29 个历史下载阶段文件，后者均与 HEAD 相同（忽略换行），没有背景语义修改。40 份格式改动重新取得 MCP 全级零诊断，另 39 份以原始 SHA 未变复用此前完整零诊断，合并为 79 份 `errorsOnly:false` 零错误/警告/弱警告。

最终完整 `gradlew.bat build --console=plain` 为 1179 tests / 153 suites，失败、错误及跳过均为 0；assemble、Spotless、test、check 全通过。MCP build_project 成功、problems 为空；79 份 Java 的构建前后及 MCP 后 SHA 无漂移。证据在 `build/staging/component-extension/full-build-final-result.json`、`full-build-final-test-results/` 和 `build/reports/idea/component-extension-inspections-79-final.json`、`component-extension-document-formatted-final.json`、`component-extension-build-final.json`。准确 index 候选以已验证不可变快照和 HEAD/原用户基线合成，保留背景改动；扩展阶段已本地提交。

未启动 Minecraft 客户端或服务端；逻辑 fixture 不代表真实 World/TileEntity 运行验证。
