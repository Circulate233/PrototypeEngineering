# 资源仓有限窗口

2026-11-10。本阶段保留 PM 仓室纹理、尺寸、两侧独立滚动条、30 像素滚轮步进、缓动、裁剪、标题与 tooltip，继续直接使用 `AEBaseGui`、`AppEngSlot`、`GenericStackDisplayInventory` 和 AEKey 渲染。没有加入 ModularUI 或第二套 GUI。

最新完整验证为主代理 full-resumed：**1814 tests / 237 suites，零失败、零错误、零跳过**，无陈旧 XML、无格式变化。已独立解析 `build/staging/chat-goal-recovery-2026-11-10/full-resumed-results/summary.json` 与全部 237 份 XML 核实，其中资源窗口 13、滑块 4、能源表达式 2 项全通过。本阶段 13 个 owned Java 的最新本机 MCP 全级结果为零，完整读回与冻结磁盘一致。以下保留实现及先前验证历史，不以旧结果替代当前完整构建。

## 逻辑槽与原生槽

每个存在的资源侧仅建立 `columns * (visibleRows + 1)` 个 AppEngSlot；额外一行覆盖平滑滚动时上下两条残行。36 个玩家槽随其后，槽号终身不重建。当前皮肤最多 116 个原生槽（混合仓每侧 40 加玩家 36），逻辑仓槽数不再受原生窗口 signed-short 限制。实际存储仍以侧和 int 槽索引访问，没有新增 32731 等仓容量上限。

`ResourceHatchWindow` 持有两侧首槽、一个窗口 revision，以及各侧数量统计的存储 revision。打开容器和每次换行均分配进程全局单调 long token，不因原生 windowId 重用而重复；long 身份空间耗尽时失败而不回绕。`ResourceHatchWindowSnapshot` 只传可见窗口中的 AEKey/long 数量、每个资源的容量和全侧 occupied/distinct 总计。统计仅在该侧 storage revision 改变时重新扫描，既不截断为当前窗口，也不保留全仓 GUI 内容缓存。两侧槽数、容量和允许类型分别来自各自真实存储。

窗口缓存经现有 AE GuiSync `PacketWritable` 一次同步。原生 `SPacketSetSlot` / `SPacketWindowItems` 没有窗口 revision，因此 `HatchGuiInventory` 不接受这些包覆盖资源显示；玩家槽继续正常接受原生同步。服务端可见资源仍是实际数据的显示副本，不能直接写回仓库存储。

滚动条使用 long 像素偏移，行数用提升到 long 后的整除。逻辑末槽为 `Integer.MAX_VALUE - 1` 时仍可以拖到底部，不发生像素乘法溢出。空尾格和裁剪区外的槽不显示、不接受操作。

用户追加的禁用滑块：物理方向存在的侧始终显示原轨道与滑块，行数全部可见时显示压暗的禁用态；该侧不存在时才隐藏。方向存在性与可滚动范围分别控制绘制和输入，禁用态不响应滚轮、按下、拖动，也不进入悬停/按下皮肤。双侧各自保持独立状态，范围归零会复位偏移并停止拖动/动画。

已核对原 `PMVerticalScrollData` / `PMThinVerticalScrollData` 及相同 SHA 的 `states.png`：原素材仅 normal/hover/pressed 三态，没有可引用的第四禁用精灵。因此禁用态复用原 normal 图覆盖暗色，保留宽 12×15、细 7×12、原偏移与所有可滚动状态的原坐标，不替换皮肤或新增贴图。来源、首份本批 before 与三项新增行为测试记录在 `build/staging/hatch-resource-window/disabled-scrollbar/`。此次新增三个测试覆盖宽细禁用可见、无方向隐藏、混合双侧独立；该测试类由 1 项增至 4 项，已在第三轮联合专项真实全部通过，具体证据见下文。

## 操作与过期保护

普通资源点击复用 AE `registerClientAction` / `sendClientAction`，小型数组携带 revision、输入/输出侧、真实逻辑 int 槽、button、ClickType 与 bulk 模式。VIEW/CLICK 最大 JSON 长度分别为 96/128，低于 AE 原有上限。不存在新网络 channel。

AE 原有 InventoryActionPacket 用于中键、填充/清空容器与 space 区域转移；该包本就带 int 原生槽和 long extraId。本容器此前资源操作不读取 extraId，GUI 一直传 0。现在仅资源分支将正 extraId 解释为窗口 revision；玩家分支仍传给 AE 原实现。

所有依赖资源格身份的动作进入同一个 `ResourceHatchWindow.interact` 门：当前服务端线程、准确 openContainer、原 TE/存储有效性、revision、存在的方向、当前窗口覆盖范围及逻辑末槽均须成立。无版本的原生资源 `slotClick`、`decrStackSize`、AE display swap 不修改存储。玩家 shift 插入本身以全输入侧为目的地，不使用资源显示格映射，继续经准确容器/存储引用检查后调用整侧 `insert`。

客户端只保留一个在途换行请求，并拒绝已接收映射 token 的降代快照。`ResourceHatchPresentation` 分开保存布局开始时的代与已经完成整个绘制帧的代：资源按键、鼠标、Shift press、中键、space、双击都只能使用最后完成绘制的 token；收新映射包之后、下一帧完成之前拒绝资源输入。按下与释放保存独立的 revision/逻辑槽，滚动或重配置后的旧手势不能操作复用格。数字键专门避开 AE 针对非 64 槽限发出的无版本 `SwapSlotsPacket`。

| 操作 | 实际行为 |
|---|---|
| 普通左/右键、数字键、丢弃 | 原真实数量 `HatchSlotActions` / `takeItems` 路径，先验证准确视图 |
| 创造中键 | 对真正 AEItemKey 生成正常最大堆叠；超过 64 的 GenericStack 显示不会被 AE 默认 wrapped 检查吞掉 |
| 物品 shift | 玩家到全输入侧；资源到玩家 36 槽；有限原生窗口不截断目标范围 |
| AE shift 双击 | 提取选中同类资源的整个逻辑侧；第一击清空显示格后仍保留准确的旧 key/槽/revision 以完成第二击 |
| space 区域移动 | 遍历所选整个逻辑侧，实际移动正常物品；从不移动虚拟显示物品 |
| PM 同 button 双击，间隔不超过 250ms | 光标非空且为输入侧：光标、36 主背包槽、offhand 的同类物品依序存入全侧；光标为空且所选资源为物品：全侧同类资源填充背包及 offhand，部分容量不足的余额原位保留 |
| 非物品资源 | 普通及双击仍使用 AE ContainerItemStrategies 与现有填充/清空 held-item 实现；整容器操作使用 FILL_ENTIRE_ITEM / EMPTY_ENTIRE_ITEM，不制造可提取的虚拟 ItemStack |
| 原生玩家拖放 | 保留；资源不参与 vanilla drag 分配，滚动条拖动照常 |

同类转移通过每个 storage context 的锁把 expectedKey 判断、首次 quick move 的 key 记录、实际提取与未转移数量的准确补回绑定在一起。并发写入不同 key 时，该槽不参与原同类转移；不会读取一次 A 后按当前 B 提取。混合两侧不同时持锁。双击填充非物品资源时，只有真实 `ContainerItemContext` 模拟接受所选资源才优先执行整容器填充；普通物品光标不能灌装时继续 PM 同类整侧插入。

tooltip 已通过当前有限快照接线：`ResourceHatchWindow.capture` 在该侧 context 锁内从每个显示资源的真实 key 调用 `storage.getMaxAmount(stack.what())`，将当前 long 数量与对应容量写入同一 Entry；`ResourceHatchContainer.capacity` 校验该 native cell 的 key 仍相同后读回 Entry.capacity，`ResourceHatchGui` 用这个 key 的 FULL 数量格式显示 `ResourceStored`。占用槽/总槽同样来自各自 Side 快照。没有新增用户说明控件或替换原 GUI 文案。

资源区 PM 双击与 vanilla PICKUP_ALL 的方向存在真实冲突：前者会把光标和背包同类物品全部存回输入仓，后者会将同类资源补入光标。按本轮裁定资源区使用 PM 同 button 双击；玩家背包区保留 vanilla 双击。不新增替代手势。原资源 collect 函数仍以完整逻辑双侧为范围，不会因窗口化误变为仅可见格。

## 原实现依据

- PM `client/gui/widget/ResourceSlotWidget.kt` 继承普通 Widget，配套 `ResourceSlotSyncHandler` 继承 SyncHandler。资源格没有注册为原生 Container Slot；原资源槽索引和同步 count 均走 VarInt。
- `ResourceSlotWidget.kt:232` 为 press，`:314` 为同 button 双 tap 检测，`:339` 为 insertAllSame / extractAllSame 触发条件；`ResourceSlotSyncHandler.kt:529` 和 `:587` 分别处理 cursor/main/offhand 插入和全同类提取。
- 原资源 Widget 没有 onMouseDrag、不实现 IVanillaSlot。MUI Interactable 默认 drag 为空；真正 ItemSlot 才调用 ClientScreenHandler.dragSlot。PM SyncHandler action 表也没有拖放分配协议，故资源不参加原生拖放是原实现边界。shift-wheel 的服务端 `handleScrollServer` 是空 stub，普通 wheel 回父 ScrollArea。
- AE `AEBaseContainer` 的 GuiSync 在原生 `detectAndSendChanges` 前发送，但单靠顺序不能给原生 slot 数据加 revision，故资源快照必须作为一份带版本数据发布。`AEBaseGui.checkHotbarKeys` 的非 64 容量分支与 wrapped CLONE 提前返回已单独处理。
- 完整来源审查：`build/staging/hatch-reconfiguration/large-slot-review.md`。

## 验证与交接

本阶段拥有 8 个生产 Java、2 个测试 Java 和本文件；首次 before/absence 清单在 `build/staging/hatch-resource-window/`。独立复核修复前文本另保存在 `review-repair/before/`，未覆盖首份 before。未修改仓室核心、共享 INDEX/status/spec，未提交或推送。

首版 9 个 Java 本机 MCP 全级零且完整读回一致；独立复核发现 4 项 P2 后已补修与 6 个回归，修复版共 10 个 Java 经主代理格式化后再次逐文件 `read_file(limit=5000)`、`get_file_problems(errorsOnly=false)`，错误、警告、弱警告均为 0，读回文本与磁盘逐字符一致。最终原始输出与 SHA-256 记录在 `review-repair/final-mcp-readback.json`、`final-mcp-problems.json`、`final-mcp-verification.json`；父目录 mcp 文件也同步为本次最终结果，修复前与格式化前证据另保留。

8 个专项测试覆盖 40001/40003 末逻辑槽实际点击、旧 revision 拒绝、空尾格、双侧不对称 long 数量、全侧目标插入、无版本原生更新不能覆盖快照、旧存储退休、恶意窗口 count 有界拒绝、PM bulk 部分成功与 offhand 守恒，以及 int 最大末槽的滚动条拖动。主代理联合专项实际执行 10 个任务，169 tests / 23 suites 全零；其中 `ResourceHatchWindowTest` 7 项和 `HatchScrollbarTest` 1 项全部通过。日志及 XML 在 `build/staging/continued-java-plan/focused-first.log`、`focused-first-results/`。

首版独立代码复核 `code-review.md` 的四项 P2 是上述通过测试未覆盖的触发序列：跨 open/move 重放、收包至渲染之间输入、同类转移并发换 key、非物品格普通光标灌装失败。修复新增 6 项回归，窗口专项共 14 项；新增测试直接覆盖复用窗口身份拒绝、presentation 输入门/降代快照、锁前换 key 拒绝/锁内并发阻挡、普通光标回退至第 40000 逻辑槽和真实容器策略填充优先。

主代理随后完整 `build` 实际执行成功：1785 tests / 231 suites，failures/errors/skipped 全 0；其中 `ResourceHatchWindowTest` 13 项、`HatchScrollbarTest` 1 项均零失败。计数已按 `build/staging/continued-java-plan/full-first-results/summary.json` 和该目录 231 份 XML 核实；日志为同目录上级 `full-first.log`。窗口格式化后 Java 的 SHA/读回再次一致，没有在完整构建后修改源码。

`build/staging/hatch-resource-window/code-review-second.md` 二次独立复核确认四项 P2 均关闭，未发现新增 P1/P2；原冻结 Java、首份 before 和修复前 before 均一致。复核要求更正的交接计数已按上述实际汇总修正。

上述 1785/231 及二审结论针对禁用滑块追加前的修复版。禁用滑块后来新增三个行为测试，当前窗口共 17 项，已在 `build/staging/chat-goal-recovery-2026-11-10/focused-resumed-repair2-results/` 的真实 XML 中核实全部通过（资源窗口 13、滑块 4）；同轮表达式 2 项也通过。该轮整体 203 tests / 37 suites 存在 1 个独立自动 IO fixture 失败，不能宣称整体通过。恢复后全部 10 个窗口 Java 已重新逐文件通过本机 MCP 全级检查，完整读回与当前磁盘一致，新证据在 `build/staging/hatch-resource-window/disabled-scrollbar/resumed-verification/`。旧 disabled-scrollbar/mcp-readback.json 含旧文本，不作为本批通过证据。

同一窗口阶段另补能源输入链：AE 表达式允许 -1、容器 server action 拒绝低于 -1 的值，既有 GUI 与 GuiSync 保留 -1 原值。物理 tier 上限由能源核心处理；具体范围、最早 before 与检查证据见 `build/staging/hatch-resource-window/energy-rate-unlimited/handoff.md`。当前阶段合计 13 Java 和本专题文档，新增的三个既有文件已加入最早 before/owned。

测试不引导 Bootstrap/FML，不启动游戏。完整构建已按顶部证据通过；最终 MCP build 与精确候选/提交由主代理继续组织，实机显示与鼠标手势仍须由用户游戏复验。
