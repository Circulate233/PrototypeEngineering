# 研究界面与服务器操作

研究界面使用具体 `GuiResearch extends AEBaseGui<ContainerResearch>` 和 `ContainerResearch extends AEBaseContainer`。复用 AE2 的 `GuiSync`、`PacketWritable` 和带 windowId 的窗口动作包，没有新增网络频道、GUI 基类或编辑器。库不注册用于打开界面的正式机器或物品。

## 公开 Java 入口

服务端物品、方块或命令在服务器线程调用 `ResearchGuiAccess.open(EntityPlayerMP)`。CommonProxy 的独立 GUI ID 1 创建研究容器，不依赖占位坐标的区块加载；ClientProxy 复用 common 创建的容器并附加客户端屏幕。入口及窗口校验实际在线玩家、原 World、存活状态和当前容器实例。

默认界面提供：

- 研究目录：文案、个人/队伍有效等级、目标等级与设备 tier，开始研究和维护黑名单。
- 队列：原队列顺序、目标等级、数值资源进度、手动材料进度；暂停的手动会话可以接管。状态、暂停原因与贡献者在条目提示中显示。
- 奖励：本人的逐研究等级资格、选择组、领取及继续未完成领取。选择一个组后点击领取；已经开始发放的组固定，不再允许更换。离队、研究等级被消费或遗忘不删除历史资格。
- 说明与详情切换：说明按可用宽度折行并滚动；进度和奖励组分别分页，每个选择组都可以访问。静态手动材料使用 AEKey 的显示名称，标准算力/量子位/并发使用本地化枚举。

目录中的目标仅表示前置、互斥等结构资格，不意味着外部 startCondition、设备或实时计算资源校验已通过。真正开始仍由服务器执行完整入队检查；显示刷新不执行开始条件。

## 只读视图与同步

`ResearchWorld.project` 从当前成员重新计算共享等级，分别应用个人、队伍、队列权限。只读查询不创建玩家/队伍来源、不保存世界、不重试材料回执，也不推进研究。权限回调期间禁止重入世界修改，结束时重验源、成员及调用者证明。已有工作未结算、来源被借出、窗口失效或世界正在关闭时返回 null，本轮不以空数据覆盖旧视图。

`ResearchPlayerView` 是脱离活动来源的公开只读数据。其他成员仅用于计算共享等级，不向当前玩家传输其他人的奖励、交付片段、物品 NBT、实体位置或材料回执。

`ResearchGuiSnapshot` 每次最多同步 6 个主条目和 6 个详情条目。目录按研究 ID 排序，奖励按 ID/等级排序，队列保留原顺序。主列表与详情分别有 total/offset；准确选择由研究 ID、目标等级、队列 entryId 组成。选择失效后落到当前页首行，详情回到第一页。数值进度只属于准确队头，手动材料按 requirementId/inputIndex 对应实际输入。

编码使用有界 body 长度，检查版本、枚举、布尔、计数、ID、非负数量、页面关系及多余 body 字节；不会吞掉同一 GuiSync 包的后续字段。文案和注册定义由客户端本地加载，不随周期刷新传输。AE2 默认 Gson 动作参数使用显式无参构造的普通数据类：现有 Gson 2.8.0 无法给 record 字段赋值；原机器分页参数也一并修正。这里没有自写反射适配器，二进制同步快照仍为不可变 record。

## 操作与关闭边界

服务器容器最多保留一个待执行操作和一个进行中的调度边界查询。刷新通过既有 MAIN 提交队列执行，动作结束后刷新；关闭窗口后停止新增工作，已经排队的操作仍以原容器实例证明检查有效性。动作参数标识当前显示行和目标意图，服务器验证原actor队伍；不能自报权限、库存或交付计划。客户端按钮绑定最后绘制时的准确身份和黑名单目标值；MC 同步先于输入、screen tick 的实际顺序不会使旧按钮越界或作用到重排后的另一项。

开始、黑名单和接管绑定服务器先前投影的原队伍；换队后拒绝旧目标。手动接管还绑定准确 entryId，不能接管同 ID/等级的新会话。角色及成员在执行和回调后继续重验。奖励仅针对实际玩家的永久领取资格，不绑定旧队伍。

保存待确认时界面禁用动作。明确的业务拒绝转为本地化结果；外部回调、保存和不变量异常仍原样传播，不伪装成正常拒绝。奖励继续遵守既有交付凭据：未知交付不因重复点击而重新发放。

## 个人遗忘与管理员等级

目录右侧“操作”页提供独立遗忘阈值、管理员 PLAYER/TEAM 目标和绝对等级输入。个人遗忘只作用本人当前队伍的普通 DATABASE_AND_ITEM；阈值 k 把实际个人等级 before 改为 min(before,k−1)。管理员使用真实服务器操作员权限：DATABASE_AND_ITEM 对应 PLAYER，ITEM_ONLY 对应 TEAM，specialOnly拒绝；绝对等级0删除来源记录。PLAYER可指定离线UUID，TEAM可指定显式空/历史稳定ID；输入先严格检查再标准转换，没有异常驱动解析回退。

客户端绑定最后绘制的可见选中行、实际个人before、原actor team与window，operationLevel独立于目录目标等级；UUID/team/level仅作为意图。服务器容器核对原投影，再由原MAIN重新捕获实际会话、FTB角色/成员及operator。界面不显示他人私有等级或领取记录。管理输入页隐藏原底部开始/黑名单等动作，避免小窗口重叠触发；说明/详情和普通动作仍可访问。

V2快照追加遗忘/管理许可，兼容Java构造默认拒绝新增权限；body/envelope/16KiB与后续GuiSync字段保护保留。ACT新增nullable Integer字段，普通旧动作保持默认Gson省略null的原JSON。所有动作在入队、异步与反馈失败时通过原窗口finalized清理flags，原错误与distinct suppressed清理错误保留。遗忘/管理不收费、不派发奖励或开始事件，历史领取资格完整保留。

## 文案和奖励组名称

沿用旧 PN 的文案格式：

`config/prototype_eng/research_content/<language>/<namespace>/<path>.json`

字段为 title、可省略的 summary、非空 description 字符串数组，以及 icon `{kind,id,meta?}`。title 和每条 description trim 后必须非空，summary 保留原文本；未知字段拒绝。请求语言文件缺失才查 en_us；两份均缺失显示研究 ID 和明确缺文案状态。存在但损坏的文件记录错误并抛出，不偷偷使用英文文件。

icon 的 item 类型使用实际物品 ID 和可选非负 meta；icon 类型通过客户端 `ResearchIconRegistry.register(id, texture)` 提供映射，整张纹理绘制到 16×16 区域。缺少注册物品或命名图标是配置错误。缓存仅存在于当前屏幕，最多 128 条文案，语言变化清理；选中图标只在选项更新时重建。

`RewardGroup(id, title, description, children)` 的 title/description 是可选 `LocalizationEnum`，GUI 直接调用 getLocal；未提供名称则显示组 ID。原两参数构造保留。显示元数据不改变选择身份、子奖励顺序或存档交付协议。默认界面全部固定标签与操作结果使用 `ResearchGuiText`，中英文均已提供。

## 迁移来源和边界

旧 PN `PrototypeNetworkChannelImpl.ClientResponseHandler` 只记录响应日志，没有研究 GUI 或客户端响应缓存；旧 content loader 也没有界面消费者。本阶段复用已有定义、资格、队列、完成、领取和权限服务，为其增加真实客户端入口。

数据库页、普通下载和自动同步已接，见[数据中心](data-centers.md)。普通研究动作现在也携带最后绘制的队伍并由服务端核对，避免同步换队后将旧点击补绑到新队伍；领取奖励继续只绑定本人资格。个人遗忘与管理员等级Java/MAIN/原容器及默认GUI本阶段已接；普通物品转换与接口式用户盘共同导入此前已接，见[数据中心](data-centers.md)。没有启动游戏或服务端，实际交互、渲染和 FTB 游戏生命周期仍缺实机验收。

验证记录见 [实施进度](implementation-status.md)。只读投影、分页/编码、真实材料数量映射、文案文件解析与 Gson 动作编解码用行为测试验证；GUI/代理和窗口 guard 通过源代码及 IDEA MCP 检查，不通过伪造 Minecraft 环境代替游戏验收。
