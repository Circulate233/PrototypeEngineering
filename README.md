# Prototype Engineering

A Minecraft 1.12.2 mod for Cleanroom, based on the MDG branch of
[CleanroomMC/CleanroomModTemplate](https://github.com/CleanroomMC/CleanroomModTemplate).

- Mod ID: `prototype_eng`
- Mod name: `Prototype Engineering`
- Base package: `com.circulation.prototype_eng`
- Entry point: `PrototypeEngineering`

## 开发需求

本项目是 PrototypeMachinery 与 PrototypeNetwork 合并后的重新开发版本，以 AE2 Supergiant 为基础依赖。
已开始分阶段实施，需求、旧项目调查和待定范围见[开发文档 v0.6](docs/development-spec.md)，实际完成情况见[实施进度](docs/implementation-status.md)。
对外 Java API 独立放在 `com.circulation.prototype_eng.api`，按契约职责组织；实现类按实际用途命名，CraftTweaker 在最后阶段接入该 API。
旧实现按功能审查后决定复用、重构或重写，重新处理合并后的职责与 Java 25 线程模型；确需字段级原子更新时优先采用 VarHandle。
混合仓保留独立输入、输出缓存，每侧支持 AE2 多类型资源；仓室通过公开接口接入机器，允许外部实现特殊仓室。
后续可选兼容 [Applied-Flux](https://github.com/FormlessDragon/Applied-Flux)（当前暂缓）：输入仓及混合仓输入侧的 FE Key 可承担能源输入仓职责；独立能源仓继续保留。
具体 GUI 直接基于 `AEBaseGui` 重实现，不增加本项目的通用 GUI 基类。每个配方池关联一个 JEI 页面接口实例：默认参考 MMCE 自动生成，也可由开发者提供独立的自定义实现。

使用 Java 25 执行 `gradlew.bat build` 构建并运行逻辑测试。当前仓室核心接口及调用约束见[仓室开发说明](docs/hatches.md)。

# Differences from kappa Unimined branch

The fork keeps the upstream `net.neoforged.moddev.*` plugin ids but is published to Maven Central as
`io.github.ruixuqi:moddev-gradle` rather than to the Gradle Plugin Portal, so `settings.gradle` points those ids at it.
May have issues; report them [here](https://github.com/RuiXuqi/ModDevGradle).

Use `mixinProvider` for jars that are meant to supply the Mixin runtime (should be unnecessary on Cleanroom), and
plain `implementation` for libraries that must not be remapped.

You **MUST** write AT entries in SRG name (eg: `field_130070_K # fileResourcepacks`), unlike Unimined. No remapping.

For IntelliJ users, it is recommended to switch
to [RuiXuqi/MinecraftDev](https://github.com/RuiXuqi/MinecraftDev/releases) for AT completion. Also RFG and Unimined
support have been merged there.

## Template conventions

The build generates `Tags` under `build/generated/sources/tags` from the Git tag and
short commit hash. Use `Tags.MOD_ID`, `Tags.MOD_NAME`, and `Tags.VERSION` in the mod
entry point; no checked-in `Reference` class is needed.

Resource metadata is kept in `src/main/resources` and expands the `gradle.properties`
values during `processResources`. Access transformer entries use SRG names for the
ModDevGradle Cleanroom toolchain.

## GitHub Actions

The repository workflows are thin callers for the matching `*-mdg.yml` workflows in
`FormlessDragon/.github`. The shared MDG workflows keep the build, release, and
runtime-test sequence used by the template while using the ModDevGradle staging
tasks. The release workflow builds the JitPack repository and publishes to CurseForge
when `CURSEFORGE_TOKEN` and `curseforge_project_id` are configured.
