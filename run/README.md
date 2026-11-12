# PE 开发测试入口

示例、实际加载的 CT 脚本、SDK 验证器和操作预期均在本目录。它们只用于开发，不加入 PE 正式库内容。原 13 台机器、106 条配方保留；新增机器与测试助手在同一个 `pe_visual_examples` addon。

从项目根目录执行：

```powershell
# 编译当前主类和全部 21 个 addon Java，安装开发 addon；不启动游戏。
.\run\prepare-tests.ps1

# 用真实 SDK718 编译全部 13 份脚本，执行核心脚本与 97 项行为断言；不启动游戏。
.\run\verification\verify.ps1

# 以下由用户执行，完整启动开发客户端；新增机器不能仅 /ct reload。
.\run\start-tests.ps1
# 原生 Bloom 场景改用：.\run\start-tests.ps1 -WithBloom
```

验证报告默认在 `verification/results/script-verifier-result.json`，原始日志也在该目录。`prepare-tests.ps1` 导出当前真实类路径，已不依赖旧 `build/staging`。准备失败或 addon 被占用时先解决错误再启动。

本批新增[ME总成与连接纹理](scripts/README-me-assemblies.md)。在已加载且没有建筑的空地执行下列命令，即可生成覆盖全部正式PE注册方块的检验结构；每种方块有相接的3×3地面、后墙与侧墙，共27格：

```text
/pe_examples build ctm_gallery 0 80 0
/pe_me_examples gallery-check 0 80 0
/pe_me_examples gallery-list 0 80 0
```

`gallery-check`核对真实方块覆盖，连接纹理画面由用户目视检验。六台新增ME场景可用 `/pe_examples kit me` 取材料，再 `/pe_examples build me_assemblies 0 80 70` 等命令搭建；完整坐标、原生加工样板、ME网络、颜色隔离和七种并行组件说明见上述专题。

按以下顺序验收；每一项的命令、投料、坐标、准确数量与拒绝条件在对应文件：

1. [核心脚本](scripts/README-core.md)：SDK member/INDEX/IData、START/TICK/END 付款、分配、并行、factory 与准确 codec；进入世界可执行 `/pe_run_tests registered` 再核对。
2. [真实 World Query](scripts/README-world-query.md)：光照采样、旋转、失配、冻结快照及线程。
3. [原生仓室、数据与保存](visual-examples/NATIVE-CASES.md)：十阶三方向、自动 IO、重配置、数据同步、退出重进、取消、掉落及独立外部过滤仓。
4. [网络、研究及客户端](visual-examples/NETWORK-RENDER-TESTS.md)：五角色、FTB/权限、研究与真实编码下载、渲染、独立/外部 JEI 页、运行中旧配方跨版本恢复。
5. [子结构与可拓展结构](scripts/README-composite.md)：共享pod与marker的嵌套复用、1–4段塔、移动末端盖、准确BOM、真实World断言和显式段数预览。
6. [原有可视示例](visual-examples/README.md)：13/106 场景、长数量/分页、完整 169 材料、终端编码和工具交互。
7. [ME总成与连接纹理](scripts/README-me-assemblies.md)：6台实际机器/9配方、Java与ZS并行扩展、全PE方块27格连接纹理展台、真实样板和ME操作。

[覆盖矩阵](COVERAGE.md)区分脚本编译、独立 JVM 行为、原生运行与视觉证据。2026-11-12 已通过全部21个addon Java编译、13脚本SDK编译及97核心断言，并安装addon。Minecraft、FTB、世界保存、ME网络/PAT、GUI/HEI与连接纹理画面仍待用户实际操作。IDEA本机MCP对run Java返回排除目录，错误/警告/弱警告均未取得分析结果。
