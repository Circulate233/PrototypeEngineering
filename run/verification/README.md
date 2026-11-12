# 不启动游戏的 SDK 验证

`ScriptVerifier.java` 是独立工具，不会打入开发 addon。`verify.ps1` 用 Java 25 编译工具后运行一个独立 JVM。类路径必须包含当前 PE 的命名开发 main/runtime、compile 依赖、真实 CraftTweaker 4.1.20.718 三个 binary SDK jar，以及最新 addon 的 classes。它不调用 Gradle、FML、Minecraft Bootstrap、客户端或服务器启动入口。

由项目根目录先执行 `run/prepare-tests.ps1`，再执行 `run/verification/verify.ps1` 即可使用默认当前类路径、最新addon classes及 `run/verification/results` 证据目录。需要指定其它真实导出路径时：

```powershell
.\run\verification\verify.ps1 `
    -ClasspathFile 'C:/path/to/main-runtime-plus-compile-classpath.txt' `
    -AddonClasses 'F:/new/PrototypeEngineering/run/visual-examples/build/compile-<id>/classes' `
    -EvidenceDirectory 'C:/path/to/verification-evidence'
```

验证器直接读取 `run/scripts` 中所有 `.zs` 文件，按 preinit/普通 loader、priority、文件名排序，用实际 SDK Parser/Compiler 编译。它不拷贝脚本片段、不返回 fake 物品、不初始化游戏注册表。原版 `<...>` 以实际 SDK 的 typed symbol 延后至真正 Forge lookup；这层只验证类型和字节码，无法确认对应物品在用户当前环境中确已注册。

当前包含13脚本；新增13/24的ME机器、结构、样板用配方和外部并行控制器均参与SDK编译。验证器注册了真实 `ZenParallelController` 类型，预初始化中的ZS扩展入口也经过实际编译。涉及AE/Forge注册表的新增脚本仍留给原生启动执行。

然后执行 `10_pe_run_core_preinit.zs` 和 `20_pe_run_core_checks.zs`，通过真实 DefinitionRegistrations 事件交付、freeze 与 runtime resolve，再运行 `CoreCases.runRegisteredChecks()` 的实际配方付款、source allocation、owner 扫描、codec 和冻结结构行为。SDK动态 member 使用生产 `ZenComponentDataMembers` 同一个 helper 作为独立 JVM 的 native type，实际游戏 Mixin 注入仍由用户重启验收。

其它 preinit/普通脚本与 `95` 的真实客户端 variant 涉及世界/物品/客户端注册，记录为“编译通过、未执行、待实机”。旧 Java 示例目录的全部原生注册也不在这个无引导 JVM中重放。`script-verifier-result.json` 逐文件记录这些状态；任何编译诊断或实际断言失败会保留原始日志并返回非零，不输出 suite PASS。即使此工具成功，也不能把网络、FTB、数据库、退出重进、GUI、HEI 或渲染视觉标为已验收。
