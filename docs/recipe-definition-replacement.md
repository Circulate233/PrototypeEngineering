# 局部配方定义替换

本专题记录同 ID 配方定义替换的 Java 合同。执行入口沿原配方目录、扫描器、工位持久化和调度 owner 边界；旧进程继续持有自己的配方、需求图和回执。普通注册在 freeze 后仍关闭，不增加机器、研究树或 Forge 对象的通用重载。

## 版本与目录

`RecipeRegistry.snapshot()` 返回不可变目录快照，包含 canonical 配方池、目录、递增 generation 和显式版本历史。`isCurrent(snapshot)` 检查准确对象身份，不能用另一个 registry 的相同 generation 代替。

加载时可调用 `declareRevision(recipe, revision)` 声明当前 canonical 配方的行为版本；不需要内置持久化的独立配方仍可以使用这个入口。`declareHistoricalRevision` 明确声明已注册配方 ID 的历史对象，不把它加入扫描目录。所有显式版本都满足同一对象唯一 revision、同一 ID/revision 唯一对象；历史约束由 registry 自己保存，不依赖是否存在 codec。未声明版本的旧静态注册方式继续成立，但不能据此声称已有完整远端 manifest。

`prepareReplacement(expected, definitions, codecs)` 私下准备本次替换。每个 `Definition` 指定已有 ID 的新配方对象及 revision；同一对象替换其所有原归属池，保持池内 priority 和静态机器反查。受影响池调用自己的 `freeze` 并检查 ID、标题、配方和机器对象顺序，未变池保留原对象。未知 ID、重复 ID、复用历史 revision、错误 codec 或池冻结失败不发布本次目录。外部池冻结回调在发布锁外运行，结束时再次检查 expected。

`Prepared` 由 registry 封闭构造并只可消费一次。`expected()`、`next()` 和 `replacements()` 供服务端准备准确依赖；它本身不授予扫描准入、资源或调度权限。

服务器接管后，registry 保留具体 `Publication` 句柄；普通 `install` 不能绕过它，关闭后也不会恢复独立发布模式。准备结果同时捕获句柄身份，停止/重开即使目录快照未变也使旧准备失效。最终 `Publication.install` 调用原 `TaskScheduler.requireOwnerBoundary()`，要求真实 owner、BOUNDARY 状态和已退出的工作边界；仅在 owner 的 IDLE 或 MAIN 阶段不够。关闭句柄只要求原 owner，因此可在 scheduler 已关闭后清理。转交另一 scheduler 前还必须由旧 owner 的 close 记录原 scheduler 已真正 CLOSED；同一 scheduler 可在原 join 边界下重开，close 可在停止后再次更新退出证明。构造组合更新入口在 registry→PN 短锁内预验双方可接管及全部历史依赖，失败不半途接管一个独立 registry。

## 原工位的准确版本解析

`RecipeProcessNbt.revision()` 暴露现有保存格式中的明确 recipe revision；layout/executor revision 保持独立。

`RecipeProcessCodecs` 是稳定 resolver，内部发布保留历史的不可变快照。保存用配方对象身份查 codec，恢复用 NBT 的 `(Recipe, Revision)` 查 codec；没有按 ID 取最新的后备分支。同一个 codec 的重复声明可幂等，另一 codec 实例不能冒充相同版本。构造与准备冲突发生在私有表中，不修改当前快照。

`MachineWorkSlotsNbt(layoutRevision, resolver)` 捕获这个稳定 resolver；每次完整 encode/restore 开始只读一次快照，全部工位用同一份绑定。原 collection 构造委托固定 resolver，也允许明确提供同 ID 的不同历史版本。工位布局和 NBT 格式版本不因配方替换而改变。

`bindProcessCodecs` 在 registry freeze 前一次显式绑定 resolver。它不要求整个目录每条配方都有内置 codec；本次替换的旧对象已经有 codec 时，必须同时提供准确的新 codec。提供的任何 codec 都必须对应本次 `Definition` 的同一对象及 revision。绑定后 resolver 的独立 install 被拒绝，只有 registry 私持的发布能力可以安装，以免版本目录和 codec 表被分别修改。

`MachineAssembly.workSlots` 的真实组件 validator 只检查同 host 的 scanner。固定目录 scanner 保持原合同；持久跟随 scanner 必须在绑定时选择与 registry、实际 work-slot codec 相同的 resolver，校验不能暗中转换模式。B 批 scanner 在实际准入前要求候选对象的准确 codec，因此局部缺失不会拖到保存时才暴露。非持久独立跟随模式不能装进该标准持久工位。

历史版本覆盖已卸载区块内的 NBT；冷启动声明者必须再次提供需要的旧对象、codec 和闭包。缺少历史 revision 明确恢复失败；已有 `MachineController.save()` 在未发布实例时保留原始 pending NBT，不能清空、重开 START 或拼成当前版本。

## 发布共同边界

组合发布者使用原服务器 scheduler owner 边界，并固定按 registry、resolver（若有）、PN 绑定的顺序持有监视器。持锁后重验所有 expected，随后依次安装已准备的 PN 并集、codec 并集和目录；最终安装区不调用外部回调、不构造池、不分配依赖表。`RecipeRegistry.install` 自身也持 registry 与 resolver 锁，独立 writer 不能插进 codec/目录之间。

`RecipeDefinitionUpdates` 是普通 Java 和后继 CT 共用的窄入口。`replace(expectedSession, expectedSnapshot, definitions, codecs)` 在原 owner 边界执行纯目录与 PN 准备；`submit(expectedSession, prepared)` 接受已完成目录准备的准确请求。两者返回带具体状态的 future：过期目录/会话拒绝、准备失败、已发布、通知失败分别报告，正常拒绝不 fault 原 scheduler。PN 回调前和最终三锁区内都会重验；通知在释放锁之后执行，通知失败报告 `published=true` 并保留原失败，不能回滚目录或重跑配置。

`NetworkRecipeExtensions` 初次发布保留原按 priority/sequence 排序的回调配置，以及验证用的冻结 research/resource registry。真实 postInit 接线使用 `RecipeRegistry.Snapshot`，按 reference 合并当前和 `historicalDefinitions()`，为冷启动旧 codec 对象准备准确门限；原 RecipeCatalog 入口仍可独立使用。后续仅对明确的新对象执行配置，已发布对象保留原绑定。一张私有 reference 缓存保存每个新对象的成功结果或原失败，配置回调在锁外运行，builder 始终 seal，同对象重入明确拒绝。缓存不属于 published 表，失败/过期请求不能让 `require` 提前看见门限。绑定 PN 表同样只通过组合发布者私持能力安装。

`ServerRecipeDefinitions` 在 `CommonProxy.serverAboutToStart` 使用实际 server、独立 UUID 和 `ServerTaskSchedulers.forServer` 接管；进入 stopping 后拒绝新请求，启动失败也先停接收，scheduler 证明退出后关闭原 Publication。登录动作沿准确 player、connection 和 session 检查排进 owner 边界。启动、成功替换与登录发出 `RecipeDefinitionPublicationEvent`，携带准确 session/previous/current 快照及可选登录接收者；此事件是 E 批声明解析与发送的入口，不代表已发 packet 或客户端 JEI 已应用。

`RecipeDeclarationKey(ResourceLocation, String)` 是完整本地声明集的明确身份；`RecipeDefinitionRevision` 是配方行为版本。两者都属于 common Java API，不携带 client 或 Zen 类型。后继 manifest 必须保留池内配方 priority，服务器 generation 不是闭包等价证明。

## 客户端声明与显示

服务器发送 bounded manifest，内容只有明确声明 key、server session/generation、池与原成员顺序及 revision；不序列化闭包或用摘要推断等价。`ClientRecipeDeclarations` 保存客户端独立 snapshot/provider 声明。客户端按准确连接、session、最高 generation 与分片冲突规则处理 notice/Ready，缺失声明只进入未就绪；晚注册、runtime 到达及重入重试在 ClientTick END 单项消费。收到 DECLARED notice 或首 Ready 片后，同 session 的静态 fallback 永久撤销；只有未连接本地或明确 STATIC notice 可使用首个本地 snapshot/provider 基线。

JEI 使用原 category/page 实例准备更新。先撤销旧 token，再添加/隐藏真实 native wrapper 并核 category/focus/recipe-ID 映射，确认后安装 page 数据并激活新 token；callback、runtime、source 或 epoch 换代均直接拒绝，不能重启已撤销的显示。默认页支持更新；没有明确更新能力的自定义页仅保留初次静态合同，动态代明确未就绪。真实 HEI Registry 初始化会触发 Minecraft Bootstrap，因此本轮没有执行该 fixture，不把协议用例当成原生运行通过。

已有外部页面由 `RecipePool.hasExistingJeiPage()` / `existingJeiPageId()` 声明。全部 HEI 插件注册完成后解析有效 UID，只绑定本池机器 catalyst；不创建 own page/wrapper/transfer handler。空或未知 UID 回退原页面，明确禁用项保持禁用。freeze/替换保留 metadata 与机器，运行期 category shape 变化直接拒绝。

## AE 网络接线

三个真正客户端包位于 `network/clientbound`，复用 AE2S `ClientboundPacket` 的 final from/toBytes → protected read/write。`ConnectionClientboundPacket` 只补接收连接身份，集中 `ClientboundPayloadHandler` 捕获 Netty ctx handler 后委托 AE 原 handler 唯一排队，最终 handleClient 再核 current handler 身份并进入原 proxy/session/manager 门。`InitNetwork` 统一注册和发送，保留原 SimpleNetworkWrapper 与 0/1/2 顺序；未建立无职责的 serverbound 镜像。

## 当前证据边界

2026-11-10 最终工作区专项 67 tests/12 suites、完整 build（含 Spotless）1868 tests/247 suites，0 failures/errors/skipped；67 Java 当前完整文本匹配本机 MCP 全级零记录，MCP build 成功。新结果及完整 XML 位于 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261110/unified-final/`，逐文件记录按 `mcp-accepted-current.json` 进入。网络七文件格式仅 LF→CRLF，修后另检；新增三项外部页面流程测试和一项真实 AE 字节桥/连接 gate 测试。完整工作区测试包含保留的原有未跟踪测试，不将其列成本批新增。

用户已确认手动清理 build/.idea。原 A/B/C stage 的 before/XML/原响应文件丢失，历史 106/14 一夹具失败与后续 132/18 全零只能从会话追溯；recovery 的新现树快照不冒充最早 before。D 的新写前文件和网络重构实际 before 保留独立出处；E 部分仅有语义增量支持。当前提交基线是用户 `b2dee38 spotless`，已包含此前大量实现及 Gradle 导入修复；只加入现树可归属剩余变化。没有启动 Minecraft、Bootstrap/FML、推送或执行远程脚本。
