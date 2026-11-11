# CraftTweaker 使用与验证边界

CT2 4.1.20.718为可选脚本桥；公开Java API及Forge注册subscriber不链接CT类型。脚本声明共用机器/装配、配方池、结构、研究和网络的既有Java注册/校验/执行/codec。库不自带正式机器、配方或玩法数值；GUI DSL和独立Modern不恢复。

## 声明与运行

注册入口排入DefinitionRegistrations的对应event，按实际阶段dispatch；结构predicate先注册再freeze/解析JSON。名称解析得到具体不可变定义和闭包；旧进程使用准确定义、descriptor及历史codec，不能运行时按名称取最新回调。机器声明及装配配置各自安装同一组件/codec；服务器更新、仓室配置和客户端manifest沿既有准确session/owner边界，pending或拒绝不冒充成功。

控制器创建声明可用`.executionPolicy("FULL_ASYNC")`（新逻辑控制器默认），或`ASYNC_SCAN`、`ASYNC_WORK`、`SYNCHRONOUS`显式控制发现/工作位置；内部owner-only需求按声明回退。`Pool.combine(id, titleKey, [simplePool, generalPool])`组合混合provider，保原准确recipe/codec/revision，不隐式注册child，也不把它们改成普通池。两接口均有真实718 Compiler行为回归；完整线程语义、组合声明时点和例子见[配方执行策略](recipe-execution-policy.md)。

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

## 控制器工厂、简单池与最终并行选择

`MachineType.processing(names, candidates, probes)`默认创建真正的MultithreadController，扫描器和work host由实际owner提供；`factory(MachineFactory.crossRecipe())`选择CrossRecipeController，Java addon可提供自己的MachineFactory与MachineComponent句柄。装配新建与恢复共用同一factory、组件descriptor和codec；默认PLANNED_PARALLEL及显式executionMode保持。下列为声明片段，`names`、实际需求、结构与翻译由调用方提供：

```zenscript
import mods.prototype_eng.MachineType;
import mods.prototype_eng.MachineFactory;
import mods.prototype_eng.recipe.Recipe;
import mods.prototype_eng.recipe.Requirement;
import mods.prototype_eng.recipe.Resource;
import mods.prototype_eng.recipe.ParallelismSelection;
import mods.prototype_eng.recipe.Pool;
import mods.prototype_eng.jei.Options;
var recipe = Recipe.withSelection("your_addon:recipe", "v1", 10,
    [Requirement.resources("your_addon:io", [Resource.item(<minecraft:cobblestone>, 2)],
        [Resource.item(<minecraft:gravel>, 1), Resource.item(<minecraft:sand>, 1)])], [], 4,
    ParallelismSelection.create(function(suggested as int) { return suggested + 1; }));
var pool = Pool.simple("your_addon:pool", "your_addon.pool.title", [recipe])
    .jeiOptions(Options.create(true, "expanded"));
pool.register();
MachineType.create("your_addon:machine", "your_addon.machine.title", "your_addon:shape")
    .factory(MachineFactory.crossRecipe()).recipePool("your_addon:pool")
    .processing(names, 8, 8).register("v1");
```

`ParallelismSelection.fixed(n)`提供固定值；int SAM在candidate配置后、完整资源准入前选择最终数量，可能超过suggested，仍须支付最终数量的START/TICK/END物料、FE和网络费用。回调只能作纯计算；unknown输入改写不凭此获得固定输入预筛保证。`Pool.simple`使用实际owner输入策略及原SimpleRecipePool索引，未知输入改写按底层契约回退；Java `ZenRecipePool.of`保留准确addon pool/recipe/codec。

`Pool.simple`的“单输入”指一个固定START资源条目，不限制其long数量；同一resources需求可声明任意数量固定END输出（包括空列表）。默认JEI自动选一个常驻输入槽的专用页，输出超过24组分页。TICK、概率、动态输入及额外需求仍使用通用 `Pool.create`；多输出不改变原输入索引策略。

## 渲染注册与普通data属性

```zenscript
import mods.prototype_eng.render.RenderBindings;
var model = RenderBindings.gecko().geo("your_addon:geo/model.json")
    .texture("your_addon:textures/model.png").animation("your_addon:animations/model.json")
    .defaultAnimation("idle").animationLayer("base")
    .animationStateKey("animation").animationLayersStateKey("animation_layers")
    .modelOffset(0.0, 0.0, 0.0).yOffset(0.0).pass("default");
RenderBindings.bindGeckoToMachineType("your_addon:machine", model);
RenderBindings.bindGeckoToStructure("your_addon:machine", "your_addon:shape",
    model.sliceMode("per_slice"));
```

也可用`bindGeckoToComponentType`绑定装配组件。每次bind冻结当前builder值；启动ClientProxy.postInit的MachineRenderRegistrationEvent消费原生renderSnapshots/renderer共用的同一个MachineRenderBindings，注册在回调前关闭，资源重载不重放启动声明。geo与texture必须提供；pass支持default、transparent、bloom、bloom_transparent，sliceMode支持per_slice或structure_only。modelOffset随front/top旋转，yOffset沿世界Y，forceGlobalRenderer(true)使用原生全局renderer入口。

普通data的动画属性读取同步快照中的字符串，animation_layers为逗号分隔的有序名称。优先序为typed MachineAnimationState、data层名称、data单名称、声明layers/default；机器须安装componentData并由实际服务器owner写入普通data。Basic renderer只取准确attachment、完整装配和匹配display generation的数据；自定义原生renderer通过受保护的animationData提供同样验证过的ComponentData.Snapshot。数据身份改变撤销旧draw/hide接受结果；worker只收到冻结动画名称、坐标、朝向与epoch，不调用World/TE或脚本回调。

## JEI默认设置与独立页面

`Options.defaults(chanceOverlay, "alternatives")`设置全局默认；每池`jeiOptions(Options.create(chanceOverlay, "expanded"))`覆盖概率标记及候选轮播/展开方式，实际默认原生页和tooltip继续使用原row的long数量。池构造方法返回新句柄，配置后调用register。`existingJeiPage(uid)`对有效外部UID继续仅注册机器入口。

独立页面使用`customJeiPage(Page.named("your_addon:page"))`。客户端Java addon先以`ScriptJeiEvents.page(id, actualPage)`登记从准确canonical pool创建的RecipePoolJeiPage实例，client event把该实例交给原RecipePoolJeiRegistry；页面继续承担自己的layout/draw/update职责，可读取公共options。缺少已命名实例会明确拒绝；此入口不提供GUI/layout DSL。

## 验证范围

最新完整build含Spotless成功50s，1921 tests/264 suites全零；真实SDK Parser/Compiler→SAM→canonical Java行为包含在该build中。官方735main＋271test＋generated Tags共1007Java本机MCP errorsOnly:false全级零，38修改源码及Tags完整读回和当前bytes匹配，原生MCP build成功。G1–G5及已确认代码scope已闭合，独立审计未发现其它确证代码缺口。测试fixture在首GenericRegistry载IData及SDK的ExpandAnyDict、ExpandDataStringDict、ExpandInt、ExpandBool、ExpandString五个实际扩展，保留SDK本身caster/equality/array机制，避免不完整环境先缓存primitive castRules。上述fixture环境不是完整FML/CT原生运行初始化。

第二阶段普通data INDEX/member/IData已有真实Compiler同helper验证；SDK718必要的单类Mixin注入仅核descriptor/profile及调用helper，用户仍需重启客户端验收实际注入。World sampler使用owner线程的合约已有行为证明，真实World/FTB/native数据库/网络、保存和客户端同步及GUI/HEI/视觉仍待游戏复验，由用户自行启动。整体Goal仍active，等待用户原生验收。

最新源码提交为`8c5ba388`/`f4b749e5`/`58fc925f`；准确manifest/原始XML/MCP/保护证据从[实施进度](implementation-status.md)及`C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261111/resumed-goal/ct-fourth-exact/`、同级`full-idea-current/final/`进入。源码阅读从`integration/crafttweaker/{render,jei,structure,recipe,resource,research,network}`与对应`api`开始。


## 用户原生复验入口

以下均未报告游戏通过；由用户完整重启客户端，实际结果记入后续实施状态。旧[可视示例步骤](../run/visual-examples/README.md)继续适用，忽略示例目录不在官方IDE检查scope。

完整套件现已安装到run：先读 [run统一入口](../run/README.md) 与 [覆盖矩阵](../run/COVERAGE.md)。现有9脚本和18Java已通过编译，核心97断言已执行通过；真实游戏加载与下面所有原生结果仍待用户测试。完整场景按核心→World Query→原生仓室/保存→网络/研究/渲染→原有结构工具顺序。总体Goal保持暂停，等待用户结果。

以下最小片段可独立排查SDK注入；已使用完整套件时直接检查20脚本的 `PASS core_sdk_*`，无需再手工部署重复片段。

1. 用户在 `run/scripts/pe_native_data_check.zs` 保存下列完整脚本（目录不存在时自行创建）。使用普通 `crafttweaker` loader，无需机器、世界坐标、测试Harness或额外注册声明；保存后完整退出并重启开发客户端。`/ct syntax` 只检查语法，不能替代此次实际执行。查看这次启动的 `run/crafttweaker.log` 中 `PE_NATIVE_DATA_BEGIN` 和 `PE_NATIVE_DATA_PASS`；若缺少PASS或出现FAIL，保留包含脚本文件名、错误与堆栈的日志。无输出时先确认脚本确已被当前启动加载。

```zenscript
#loader crafttweaker
import mods.prototype_eng.data.ComponentData;
import crafttweaker.data.IData;

print("PE_NATIVE_DATA_BEGIN");
var data = ComponentData.from({initial: 1});
data["count"] = 2;
data.flag = true;
var snapshot as IData = data.asIData();
data["count"] = 7;
data.flag = false;
var currentCount as int = data["count"] as int;
var currentFlag as bool = data.flag.asBool();
var saved = snapshot.asMap();
if (currentCount == 7 && !currentFlag && data.getInt("absent", 11) == 11
    && saved["count"].asInt() == 2 && saved["flag"].asBool()
    && saved["initial"].asInt() == 1) {
    print("PE_NATIVE_DATA_PASS current=7,false snapshot=2,true initial=1 default=11");
} else {
    print("PE_NATIVE_DATA_FAIL");
}
```

这里的动态member赋值走SDK718 `ZenTypeNative.getMember` 的真实Mixin，INDEX和IData沿同一个普通data适配器。boolean内容用 `asBool()`，snapshot通过 `asMap()`/`asInt()`读取；成功只证明本次原生脚本加载、成员/INDEX赋值、内容转换和独立snapshot。这个局部对象每次启动重新创建，没有安装到机器owner，不能用重启后再次PASS证明机器保存、客户端同步或动画已通过。本片段按现有Compiler用例及SDK718 API源码核对，本次未执行游戏脚本。

2. 进入创造测试世界，按[既有示例README](../run/visual-examples/README.md)的“7×5×7实体结构与预览”执行 `build boundary_structure`、真实充电与 `stock structure_glass`，确认成型和输出玻璃×4。接着按“结构JEI样板导入”完整五步核对169材料、三片层仍169、玻璃候选整种替换和带结构后缀的控制器×1，实际编码样板；再对照构造器单格108＋1、整种109及原绑定/30秒投影/Shift滚轮交互。README已经包含准确命令、坐标和预期数量，不必重新编译未变化的示例。
3. 按 [原生用例](../run/visual-examples/NATIVE-CASES.md) 的native_data_fixed/cross执行普通componentData、四工位、末tick nonce、客户端full-sync、checkpoint和真实退出重进；再观察卸载、拆除补回、仓室掉落与重新放置。FTB/研究/数据库/五网络角色、真实编码下载及复杂渲染/JEI绑定已在 [网络与渲染用例](../run/visual-examples/NETWORK-RENDER-TESTS.md) 提供实际机器/脚本/命令与准确预期。每项独立记录原生结果，不能因核心SDK通过就把下表整行标为通过。

反馈保留这次启动时间、场景/命令/坐标、预期与实际结果，以及相关CT日志、`run/logs/latest.log`错误/堆栈和截图。当前 `latest.log` 最后更新时间仍为2026-11-10 21:17，早于本轮代码提交，不是新的验收证据。

| 范围 | 待观察的结果 |
|---|---|
| SDK与普通data | 实际CT脚本member/INDEX/IData赋值注入成功，保存重进后值保持；动画单属性与CSV层变化只接受当前owner数据 |
| World/保存与同步 | 成型/重附/卸载、退出世界与重连后数据和render代正确；末tick新data保存，旧片和过期session无效 |
| FTB/研究/数据库/五网络角色 | 实际actor/team权限、研究站阶段投料、下载/奖励/原生盘与数据库重启；最终并行物料/FE/网络费用及旧代补偿 |
| GUI/工具/HEI/AE样板 | 既有构造器绑定/单格与整种替换/30秒及Shift滚轮；结构JEI完整169用料/候选/“原名-结构”输出并实际编码；默认选项与独立页重建 |
| 复杂渲染 | machine/component/structure资源绑定、四pass、每slice/整structure锚点、front/top旋转与Y偏移、普通data动画及Gecko/Bloom视觉 |
