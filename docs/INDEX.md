# 开发索引

恢复上下文时先读本文件及 `git status --short`，再按当前任务读取指定章节或源码。不要一次加载全部文档。本文件只保留当前事实与下一步，历史验证写入实施进度；阶段提交时同步维护。

## 当前检查点

- 最近实现提交：`2960bcf`，组件 tick 事件、软依赖图及可扩展配方需求阶段分派。
- 最近验证：`2960bcf` 提交内容通过 `gradlew.bat spotlessApply build --console=plain`，20 项逻辑测试；无游戏运行验证。继续开发前检查工作区增量，不能将这份证据套用于之后的未验证改动。
- 当前任务：独立存储 API/后端重构。现有 `ResourceHatch → MEStorage` 与 `Buffer extends GenericStackInv` 均需要替换。
- 下一步：核对 PM 存储预留/补偿完整调用链，设计本库的资源存储、快照、预留和提交边界，再替换仓室后端及 AE/Forge/GUI 适配；不要先把旧 MEStorage 操作接进新事务系统。已发现的部分提取/账本补偿风险见 storage-redesign 的“待修正的源码风险”。
- 未完成：完整配方进程/属性/overlay、内置需求、多工位扫描、机器生命周期、结构、真实多线程调度、研究/计算、JEI。都是保留范围，不能按“未实现”裁掉。

## 必须记住的约束

- 纯库，`prototype_eng` / `com.circulation.prototype_eng`；不添加正式机器、配方或玩法数值。
- 保留 PM/PN 功能效果和多线程性能能力。先核对旧实现与目的，修正架构缺陷，不盲目重造。
- Java 25，Kotlin 重写；fastutil 优先；必要的字段原子操作优先共享 VarHandle；无非必要反射，无异常控制流。
- 资源身份复用 AEKey/AEKeyType；核心存储独立设计。三方向仓室，每侧多类型，混合仓输入/输出独立；FE 独立。
- GUI 直接基于 AEBaseGui，无编辑器/第二套基类；JEI 每配方池一个默认或自定义接口实例；CT 最后。
- Applied-Flux 暂缓，未来坐标保留 `v1.0.0:dev` 的 v。
- 不启动 Minecraft 客户端/服务端。本地阶段提交，不推送。只测试静态推断不足的行为，不测常量/哈希/getter。

## 按需阅读

| 要解决的问题 | 阅读入口 |
|---|---|
| 当前完成度、验证证据 | [implementation-status.md](implementation-status.md) 的“当前状态”和相应日期记录 |
| 存储重构 | [storage-redesign.md](storage-redesign.md)，再读相关后端/适配器 |
| 组件/配方 API、事务语义 | [recipes.md](recipes.md) 的相应章节 |
| 原 PM 事件/需求具体在哪里 | [migration-audit.md](migration-audit.md) 的源码映射表 |
| 现有仓室及 GUI 的行为 | [hatches.md](hatches.md)，注意这是待替换后端的记录 |
| 需求发生分歧 | [development-spec.md](development-spec.md) 的 R 编号或相关章节；先 rg 定位，不全文读取 |
| 资源来源/许可证 | [assets.md](assets.md) |

## 本地源码定位

- 新项目：`F:/new/PrototypeEngineering`。
- PM：`F:/clone/PrototypeMachinery`；存储看 `src/main/kotlin/{api,impl}/storage`，预留看 `impl/scheduler/planned`。
- PN：`F:/new/PrototypeNetwork`；合并时保留 owner/epoch、工作资源预算和恢复保证，去除跨模组冗余接线。
- AE2：`F:/clone/Applied-Energistics-2`；MMCE：`F:/clone/ModularMachinery-Community-Edition`。

索引不替代源码审查。只写实际已完成与已验证的状态；新增约束、检查点和下一步发生变化时同步更新。
