# Prototype Engineering

A Minecraft 1.12.2 mod for Cleanroom, based on the MDG branch of
[CleanroomMC/CleanroomModTemplate](https://github.com/CleanroomMC/CleanroomModTemplate).

- Mod ID: `prototype_eng`
- Mod name: `Prototype Engineering`
- Base package: `com.circulation.prototype_eng`
- Entry point: `PrototypeEngineering`

## 开发需求

本项目是 PrototypeMachinery 与 PrototypeNetwork 合并后的重新开发版本，以 AE2 Supergiant 为基础依赖。
当前处于需求确认与模板初始化阶段，已确认决定、旧项目调查和待定范围见[开发文档 v0.2](docs/development-spec.md)。
对外 Java API 独立放在 `com.circulation.prototype_eng.api`，按契约职责组织；实现类按实际用途命名，CraftTweaker 在最后阶段接入该 API。

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
