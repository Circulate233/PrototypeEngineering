# 本地化与集合审查（2026-11-11）

本轮按用户最新要求检查正式 main 735 Java、run addon 19 Java 和 11 份实际 CT 脚本；测试调用点跟随严格 API 一起迁移。此前界面反馈和子结构/可拓展结构示例已分别提交，本轮继续修正其本地化与集合契约。没有启动子代理、Minecraft、服务端、FML 或 Bootstrap，没有推送。

## 本地化

用户指出的主要根因确实是测试 addon 缺少 `pack.mcmeta`。现已补 `pack_format:3`，保留小写 `en_us.lang`、`zh_cn.lang`；构建脚本缺少这些资源或格式错误时拒绝打包。最终已安装 Jar 根目录存在该 metadata，两语言各 94 条，源/目标 SHA256 一致。机器/结构显示还需要用户完整启动客户端复验。

独立修复以下问题：核心、World、网络和渲染结构声明补准确 `.translated(...)`；机器/配方池不再拿英文显示文本作 key；native/external 示例枚举的后缀与资源统一小写。结构声明有标题时优先使用声明，无标题但存在明确机器上下文时使用机器的 `displayName()`，裸结构仍不臆造机器。构造器根节点和预览标题一致。两件结构工具的客户端名称使用 `getLocal()`。

固定 key 统一归入 `LocalizationEnum`：扩展 `ExampleText` 并以真实 ZenClass/ZenProperty/ZenGetter 暴露给脚本，native/external 子枚举也纳入核对。13 个正式枚举及三个示例枚举共 527 项，EN/ZH 精确 key 缺失 0；四份语言表无重复 key，main/addon/脚本扫描没有枚举外的固定资源 key 字面量。循环生成的渲染机器 key 和外部动态结构/池声明继续使用生成/动态契约，不能任意修改大小写。

## 严格集合契约

带原始类型的对外接口与内部存储一起迁移，不保留装箱 JDK 重载：

| 用途 | 现在的契约 |
|---|---|
| AEKeyType 容量、换算比例及仓室构造参数 | `Reference2LongMap<AEKeyType>`，按注册身份查询 |
| 结构重复次数与候选索引 | `Object2IntMap<...>` |
| 目录子边、渲染 occurrence 路径 | `IntList`，自有不可变快照 |
| 候选索引、路径临时工作区、示例 pose | `IntArrayList` / `int[]` / `FloatList` |
| 树展开状态 | `Object2BooleanOpenHashMap` |
| 普通队列、DFS 栈、排序/保序对象表及 identity 表 | 相应 fastutil FIFO、list、tree、linked 或 reference 实现 |

保留 AEKey 数量专用 `AEKey2LongMap`、`addTo` 累加和必要溢出/事务预检。容量默认值继续拒绝不支持的类型。排序后的材料身份编码、BFS/DFS 顺序、缓冲池淘汰次序及清理 owner 的 FIFO/对象身份保持。

完整测试发现 fastutil 的 `unmodifiable(map)` 仍可能经 entry 的 `setValue` 写入，因此容量/比例和预览选择快照使用独立条目再包装，primitive 与继承的 boxed entry 路径均拒绝写入。后者只是验证 fastutil 自带的继承桥，不新增业务装箱 API；既有 AEKey 快照对同一桥的安全覆盖保留。

最终 main/addon 直接装箱 primitive 集合声明及包装数组扫描均为 0。其余 JDK 用途逐项核对：

另外扫描全部 271 个测试 Java，普通整型、时限、布尔结果记录及返回值也改为 primitive fastutil。仅保留 InputAllocationTest 的三处和 ManualMaterialsTest 的一处 JDK Map 静态视图，专门验证继承的 boxed entry 不能突破不可变性；这些断言不创建 JDK 存储，也不构成业务 API。

- `SlottedResourceBuffer` 两个 `BitSet`：密集位操作、范围与下一设置位查找。
- `ModelClientThread` 的 `ConcurrentLinkedQueue`：跨线程清理发布。
- Gecko 的 `LinkedHashMap<String, AnimationController>`：纯对象值，第三方覆盖方法要求返回具体 `HashMap`，并保留后层覆盖次序；没有原始类型集合 ABI 例外。
- 自定义 `AbstractList` / `AbstractSet`：依赖原 owner 的投影视图或私有完成计算的 O(1) 所有权转移，不是额外的普通 JDK 存储。纯对象的不可变包装/快照与允许的 JDK 公开接口保留。

`ModelBufferPool` oversize 和配方清理队列也改为 fastutil reference linked set，保留独立 Entry/FlatCandidate 身份、迭代和 FIFO 删除；没有仅为双端操作保留普通 ArrayDeque。`Fact<Boolean>`、任务 `Result<Boolean>` 是带读取/失败或任务结果语义的对象封套，不是装箱数量集合。nullable `Integer` 未指定选择是标量，不是集合。

## 验证与边界

- 最终完整 `spotlessApply build` 成功，1923 tests / 264 suites，失败、错误、跳过均 0。既有容量不可变断言发现并验证了条目写入修复，新增一个必要的结构标题行为回归；不为静态 getter/集合换名另造测试。
- 19 addon Java 重新编译、打包、安装；实际 Jar metadata/语言/源目标 SHA256 已核验。
- 真实 CraftTweaker 4.1.20.718 对全部 11 份脚本编译通过；10/20 脚本实际执行，97 项核心断言通过，`completed:true`。其余脚本只编译，不伪称 World 注册或渲染已运行。
- 28 main + 47 test Java 逐文件本机 IDEA MCP `errorsOnly:false` 最终错误/警告/弱警告全 0，pack metadata 与两语言也全级零；MCP build 成功。11 个修改的 run Java 被 IDE 排除，三类诊断未知；其 javac/SDK 证据不能替代 IDE 诊断。
- 原生机器/结构中文、材料槽/书签/左右同步/面板关闭/JEI 转移/分层方向，以及嵌套26用料、拓展1–4段22/31/40/49用料、World成型/支付/保存/退出重进仍由用户启动后复验。此前 UI 修复不能因本轮构建通过就标为已实机验收。

原始证据：`C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261111/localization-collections-audit/`，包括最终 build、264 份 XML、`sdk-final/`、527 key 审计、`final-source-audit.json`、逐文件 IDEA 原始结果、安装 Jar 核验、精确提交及背景保护记录。原暂存测试、依赖脚本、正式语言文案、Row 可见性与 INDEX 备份均保留；总体 Goal 保持暂停。
