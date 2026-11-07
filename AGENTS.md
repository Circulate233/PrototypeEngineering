# 项目工作入口

- 恢复上下文后先读 `docs/INDEX.md` 并检查 Git 状态；根据索引按需读取章节和源码，不整批加载文档。
- 实现状态和验证证据记在 `docs/implementation-status.md`，当前检查点、下一步与阅读入口同步维护到 `docs/INDEX.md`。
- IDEA 代码检查必须使用本机 MCP，不操作桌面界面。工具未挂载时连接已经启用的本地 MCP（当前 `http://127.0.0.1:64343/sse`），通过 `tools/list` 查找工具，不修改 IDE 设置；逐文件 `get_file_problems` 使用 `errorsOnly:false`，记录错误、警告及弱警告结果。
- 需求以用户当前指示和 `docs/development-spec.md` 为准；阶段完成本地提交，不推送，不自行启动 Minecraft 客户端或服务端。
- AEKey 数量表使用 AEKey2LongMap，累加/扣减优先 addTo；非 AEKey 累加也优先 fastutil addTo，保留必要的溢出与事务预检。
