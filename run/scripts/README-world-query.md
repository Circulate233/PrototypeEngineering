# 真实 World 自定义 Query

`11_pe_run_core_world_preinit.zs` 注册真实 `core_world` 控制器；`21_pe_run_core_world.zs` 注册 Query、旋转 nested validator、结构与实际铁锭→金锭配方。独立 SDK 验证器只编译这两个文件，**没有执行 World 场景**。由用户完整重启客户端后在允许命令的新测试世界执行以下步骤。

SDK718 `IWorld.getBrightness(IBlockPos)` 返回 `int`，其实际实现是 `World.getLight`。这里使用实际返回值0..15，Query通过run助手记录位置和值并在服务器 owner线程与同点原生 `World.getLight` 比对。validator只读冻结的自定义 IData、dimension和坐标；光照没有额外 builtin LIGHT 查询，因此旧JSON proof的光照失效由实际 custom Query 的NBT内容变化证明。

在周围区块已加载且这10个目标位置全为空气的位置建第一台。以下坐标只是完整的可执行示例，可整组平移：

```text
/pe_examples build core_world 200 80 200 NORTH_UP
```

控制器在 `(200,80,200)`，输入/输出仓在 `(199,80,200)` / `(201,80,200)`。灯在 `(206,80,200)`；四侧与顶部五块石头封光，下面 `(206,79,200)` 的红石块供电。等待约1秒，让原生光照和结构扫描更新。

```text
/pe_run_tests world-query 200 80 200 MATCHED NORTH_UP
/pe_run_tests inspect 200 80 200
```

真实命令在控制器 joined boundary 后调用 `WorldStructureSource`。它必须真的执行脚本采样闭包，在单独worker上评价准确已注册的Java结构及实际 `fixtures/core_world.json`，断言 MATCHED、freshness、三处采样位置、当前光照、worker线程和controller-relative旋转。返回统计包含实际 owner/worker线程、采样值、位置及每点调用次数。队列已接受或标题信息不等于断言通过；查看实际 `PASS core_world_*` 或 `FAIL`/异常及详细统计。

保持所有结构方块，只关红石供电，并用石头维持底部封光。等待约1秒：

```text
/setblock 206 79 200 minecraft:stone
/pe_run_tests world-query 200 80 200 MISMATCH NORTH_UP
```

此时必须实际采样到低于8的灯位光照，Java与JSON两个custom validator都执行并失配。前一条命令的JSON proof必须不再current；旧snapshot在worker再次评价仍保留先前MATCHED。这些断言读取真实观察值，不用预置假光照。

接着验证光照门真的控制配方，而不仅是一个纯校验标题。关灯时向真实输入仓投一份铁锭，等约4秒，物料必须保持，输出仍0：

```text
/pe_examples stock 199 80 200 pe_run_tests:core_world_recipe 1
/pe_run_tests assert-resource 199 80 200 input minecraft:iron_ingot 1
/pe_run_tests assert-resource 201 80 200 output minecraft:gold_ingot 0
```

恢复供电，约1秒后先检查结构，再等约4秒检查40 tick的实际配方付款与产物：

```text
/setblock 206 79 200 minecraft:redstone_block
/pe_run_tests world-query 200 80 200 MATCHED NORTH_UP
/pe_run_tests assert-resource 199 80 200 input minecraft:iron_ingot 0
/pe_run_tests assert-resource 201 80 200 output minecraft:gold_ingot 1
```

再在另一处空区域建立实际旋转后的实体结构，不仅改传给匹配器的参数：

```text
/pe_examples build core_world 220 80 220 EAST_UP
/pe_run_tests world-query 220 80 220 MATCHED EAST_UP
```

旋转后实际灯位为 `(220,80,226)`、供电为 `(220,79,226)`，输入/输出仓为 `(220,80,219)` / `(220,80,221)`。Query必须报告controller位及世界Z方向的6/7格采样点。可按第一台的完整开关、投料和恢复流程复验；命令的可选orientation只核对当前真实控制器朝向，不用假的朝向替换世界。

这些观察本次尚未在游戏中执行。记录两台坐标、实际光照/线程统计、PASS/FAIL和 `run/logs/latest.log` 堆栈；保存、同步、重连等继续使用统一原生场景步骤，不能以本场景的snapshot/freshness代替存档验收。
