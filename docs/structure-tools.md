# PM 结构工具

## 2026-11-10 无法打开及原 GUI 补漏

用户实机日志 `run/logs/latest.log` 反复记录 `Client action structure_tool max payload length must be between 0 and 16384`，在 StructureToolContainer 构造阶段失败，因此两个 GUI 均无法打开。原声明写了32768；现改用AEBaseContainer的默认动作注册重载，由AE自身应用合法上限。原始日志保存在 `build/staging/pm-tool-gui-parity/runtime-before.log`；以前仅有源码/构建证据不能证明已经能打开。

逐项对照 PM 的 ScannerInstrumentItem、ScannerInstrumentUi、ScannerInstrumentClientUi、ScannerSelectionRenderHandler、BuildInstrumentUi、BuildInstrumentClientUi、TreeDiagramWidget 与 BuildInstrumentProgressBarWidget：

- 两个384×228面板、原底图裁切、选择器7个输入框/5个开关/滑条/方向转向按钮、构建器预览(4,2)/树(191,8)/进度条/状态按钮沿原布局。124张工具纹理再次逐字节核对原文件；没有引入MUI依赖。
- 选择器补回主/副手世界选区轮廓：原点青色、角点黄色、范围绿色，16³以内显示非空气方块轮廓，原颜色/透明度/2px线宽；恢复重新选点、选区尺寸/体积、更新角点和大选区提示。
- 预览指南针对回原34px背景、半径11、4px内距、阴影文字和中央加号。重设原点同步关闭自动原点；重设朝向按PM仅恢复北向/自动标志，在下次打开或导出时自动识别，不立即覆盖用户刚看到的重设结果。
- 构建器树恢复根视图初始化与平移边界、连接线及交点覆盖、按数量降序的材料列表、原文字颜色、按钮完整命中矩形及滑块手柄悬停。节点选择只更新对应结构高亮，不擅自打开左侧材料选择；材料按钮才写回候选，已选按钮不重复执行。高亮只在选择/结构数据改变时重算。
- 未绑定或缺少结构数据时显示PM原“No structure data”；撤去原PM未配置的任务按钮/进度条/树整行额外tooltip，任务按钮按原normal/hover分支绘制。左侧原预览控件提示保留。
- 大选区按PM规则：不超过32768显示全部；超过时保留完整外表面。之前的稀疏采样会漏可见方块，现用惰性六面遍历并每帧读取4096坐标，完整结果就绪后发布，名称修改不重建预览、相机视图保留。新增逻辑用例验证完整表面、不重复、退化平面及int边界上的有界读取。

完整1694 tests/221 suites全零；末次迭代器检查清理后的5项专项/build通过，10 Java及两语言本机全级零并全文读回与磁盘一致，MCP build成功。完整日志、原始失败、本机报告与精确本地提交在 `build/staging/pm-tool-gui-parity/`。未启动Minecraft/服务端/Bootstrap/FML；用户重新运行客户端后的视觉和实际建造仍需复验，不将这些结果称为视觉验收。


本阶段恢复 PM 的范围结构选择器与机械构建器。入口为 `StructureToolItems`、`StructureToolItem`、`StructureToolContainer`、`structure/tool` 和两个对应的 AEBaseGui。它们属于通用库工具，主产物未新增示例机器或配方。

## 范围结构选择器

注册名 `prototype_eng:scanner_instrument`，保留原 `pm_scanner` NBT。右键方块选择两角，潜行右键重选，跨维度重新开始；对空气右键打开界面。界面保留 PM 的原点、方向、转向、名称/ID、NBT 与其他原开关、滑条以及清空/初始化/导出按钮。

自动原点/朝向读取选区内控制器，通过宿主稳定边界获取方向；导出等待该回调，陈旧选区或宿主不再有效时不继续。手动原点与 24 朝向同样支持。扫描预览使用独立本地结构目录，不覆盖服务器同步目录；32768格以内完整显示，超过时完整保留外表面、按帧预算读取，修改名称不重置相机。

导出使用本项目现有 JSON schema，写入 `config/prototype_eng/structures/scanned`，每次容器更新最多处理 4096 个坐标。坐标和朝向转为模板空间，略过空气及原点；可选 NBT 排除世界 x/y/z。重复导出使用 `_2` 等后缀，同时修改文件内 id，已有文件不覆盖；完成后清空选区。当前 loader 读回测试已通过。

PM 原源码的 expanded、substructure、allowMirror 等字段没有对应的完整导出算法。本次保留原 UI/NBT，不将这些开关描述为已经实现的新导出能力。

## 机械构建器

2026-11-10 结构显示改为从同一匹配声明派生后，构建计划直接消费准确Node的intrinsic displays，显式Entry覆盖继续优先。材料键独立编码完整properties和浅NBT，放置按meta恢复properties后再旋转；未决complete=false、未知方块和无效properties跳过并提示未知要求，不能把partial代表当成确定可建造块。全部新候选均未决时保持IDLE；已知部分仍沿原任务处理。原有显式完整NBT展示继续只放blockstate，不新增TileNBT写入或把任务完成当成结构匹配成功。

注册名 `prototype_eng:build_instrument`，右键控制器绑定，对空气右键打开。保留原绑定 NBT、状态、进度和材料选择；未成型时建造，已成型时拆卸，按钮支持暂停/恢复。服务端每玩家一项任务，每 tick 最多八次尝试；普通方块先于流体/重力方块，拆卸倒序且保留控制器。

结构计划从当前服务端展示图展开，默认切片取 minCount；树的切片覆盖用于预览。材料候选选择同步到服务端，并使用含长度编码、排序后 NBT 的完整键。材料来自玩家库存及一层嵌套 ItemHandler；流体在副本上准备足量 1000mB 后写回，可写嵌套 handler 才可修改。放置/拆除沿权限及 Forge 事件，放置失败或事件取消归还材料。登出、卸载、换工具、重新绑定或朝向改变使旧任务失效。

构建器左侧预览锚定绑定控制器、锁定方向，沿真实机器类型显示控制器；扫描与剩余材料接线，左侧替换候选和右侧树都写回材料选择。树保留展开/折叠、切片滑条、拖动、滚轮、Ctrl 缩放、Shift 横移及材料/节点高亮。

## 原资源与布局

两个界面均为 384×228，直接裁切原 PM PNG。选择器预览 `(10,12,171,203)`；构建器预览按实际源码为 `(4,2)`，结构树 `(191,8,185,192)`，进度条 `(192,207,160,9)`，按钮 `(357,205,20,14)`。原字段/按钮纹理、布局与提示通过 AE/native 绘制接入，无 MUI 依赖。

两份物品模型仅迁移命名空间；PM 两模型本来都引用 `construction_instrument.png`。本次 124 张工具/状态 PNG 与 PM 源文件逐字节一致，源路径和 SHA256 保存在 `build/staging/pm-tools-and-lighting/original-assets.json`。

## 验证边界

状态、真实结构展开、坐标溢出、候选 NBT 和导出读回等七项工具测试通过；与旋转模型合计十一项专项通过。后续完整构建为 1693 tests / 221 suites，失败、错误、跳过均为零。本阶段 33 Java、两语言及 dependencies.gradle 的本机 IDEA 全级检查均零，MCP build 成功。能量条目最后注解/record 修正后两项专项和 build 再次通过。

证据入口 `build/staging/pm-tools-and-lighting/`。没有启动 Minecraft、服务端或 Bootstrap/FML；GUI 视觉、世界实际放置/拆卸、流体容器与玩家权限交互仍待用户实机复验，不能由编译或纯逻辑测试代替。
