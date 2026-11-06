# 开发索引

恢复上下文时先读本文件及 `git status --short`，再按当前任务读取指定章节或源码。不要一次加载全部文档。本文件只保留当前事实与下一步，历史验证写入实施进度；阶段提交时同步维护。

## 当前检查点

- 当前阶段：独立资源存储重构完成并通过验证；本阶段代码与文档本地提交，不推送，具体提交以 Git 日志为准。此前 `c8fa7f5` 的十阶三方向仓室、原模型/纹理、本地化枚举及 GUI 行为保留；原工作区其他格式/注解整理继续保留。
- 最近验证（2026-11-07）：本机 IDEA MCP 对 67 个 src Java 文件及生成 Tags 共 68 文件逐个 `errorsOnly:false` 检查，错误、警告、弱警告均为零且无超时；报告 `build/reports/idea/storage-final.json`。MCP `build_project` 成功且无问题，`gradlew.bat spotlessApply build` 通过 37 项逻辑用例。双侧恢复测试从解码后数据开始，NBT 编解码、GUI/游戏尚未运行验证。
- 当前实现：ResourceHatch 返回 ResourceStorage；SlottedResourceBuffer 独立持有余额、输入数量/输出容量预留及补偿状态，所有适配共享 context。按实际 context 顺序锁定整批提交/补偿，混合仓两侧先校验再恢复。MEStorage/GenericInternalInventory/GUI 只作适配，核心/API 无 GenericStackInv。FE 仍独立且尚未并发集成。
- 既有仓室：资源两侧独立 ScrollBar；能源 GUI 原纵向条与速率/自动设置；能源重载使用当前配置容量并裁剪保存量，资源保留保存容量。原 CTM/Bloom 元数据已保留，外部渲染组合未验证；机器 Gecko/Bloom 管线仍待迁移。
- 下一步：将资源预留接入真正需求阶段并加入独立 FE 结算，再继续进程/扫描和 planned 调度迁移。遵守 PM 两次 join、稳定 affinity lane 与 PN owner/epoch/预算边界；当前存储批结算不替代这些调度能力。旧 ledger 成功 finalize 缺口、PN 提交后生命周期误判等风险见 storage-redesign。
- 未完成：完整配方进程/属性/overlay、内置需求、多工位扫描、机器生命周期、结构、真实多线程调度、研究/计算、JEI。都是保留范围，不能按“未实现”裁掉。

## 必须记住的约束

- 纯库，`prototype_eng` / `com.circulation.prototype_eng`；不添加正式机器、配方或玩法数值。
- 保留 PM/PN 功能效果与真实多线程性能能力；优先复用适合的旧实现，再审查并修正架构缺陷，不盲目重造。
- Java 25，Kotlin 重写；fastutil 优先；必要的字段原子操作优先共享 VarHandle；无非必要反射，无异常控制流。
- 资源身份复用 AEKey/AEKeyType；核心存储独立设计。资源与能源均保留十阶、三方向；资源混合仓输入/输出独立，每侧多类型；FE 独立。公开扩展职责保留接口，外部实现不依赖内置 TileEntity。
- GUI 直接基于 AEBaseGui，无编辑器/第二套基类；本项目翻译枚举实现 AE2S LocalizationEnum，客户端使用 getLocal、容器标题使用 text 组件，禁止 GUI 拼键或直接传裸翻译键。JEI 每配方池一个默认或自定义接口实例；CT 最后。
- 每个类必须通过本机 IDEA MCP `get_file_problems`（`errorsOnly:false`）取得无错误、警告及弱警告的证据；编译和人工审查不能替代。不操作桌面界面；工具未挂载时连接已启用的 `http://127.0.0.1:64343/sse`，通过 `tools/list` 查找工具，不改 IDE 设置。
- Applied-Flux 暂缓，未来坐标保留 `v1.0.0:dev` 的 v。
- 不启动 Minecraft 客户端/服务端。本地阶段提交，不推送。只测试静态推断不足的行为，不测常量/哈希/getter。

## 按需阅读

| 要解决的问题 | 阅读入口 |
|---|---|
| 当前完成度、验证证据 | [implementation-status.md](implementation-status.md) 的“当前状态”和相应日期记录 |
| 存储重构 | [storage-redesign.md](storage-redesign.md)，再读相关后端/适配器 |
| 组件/配方 API、事务语义 | [recipes.md](recipes.md) 的相应章节 |
| 原 PM 事件/需求具体在哪里 | [migration-audit.md](migration-audit.md) 的源码映射表 |
| 现有仓室及 GUI 的行为 | [hatches.md](hatches.md)，核心与 AE2/Forge/GUI 适配边界 |
| 需求发生分歧 | [development-spec.md](development-spec.md) 的 R 编号或相关章节；先 rg 定位，不全文读取 |
| 资源来源/许可证 | [assets.md](assets.md) |

## 本地源码定位

- 新项目：`F:/new/PrototypeEngineering`。
- PM：`F:/clone/PrototypeMachinery`；存储看 `src/main/kotlin/{api,impl}/storage`，预留看 `impl/scheduler/planned`。
- PN：`F:/new/PrototypeNetwork`；合并时保留 owner/epoch、工作资源预算和恢复保证，去除跨模组冗余接线。
- AE2：`F:/clone/Applied-Energistics-2`；MMCE：`F:/clone/ModularMachinery-Community-Edition`。

索引不替代源码审查。只写实际已完成与已验证的状态；新增约束、检查点和下一步发生变化时同步更新。
