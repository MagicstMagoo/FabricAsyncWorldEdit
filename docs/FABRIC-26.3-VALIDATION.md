# Fabric 26.3 验证记录

验证日期：2026-10-13。分支：`fabric-26.3-port`，从 `fabric-1.20.1` 的
`93a21e7670b3f95e17027f4e3f748c0b5b4c5e5a` 创建。本记录保留原始验证构建的信息；
该 JAR 在源码提交前生成，其中的 Git revision 因此仍是该基线提交。

环境：Windows 11、Azul Java 25.0.1、Minecraft 26.3、Fabric Loader 0.19.5。

## 构建与回归测试

以下命令成功完成：

```powershell
.\gradlew.bat :worldedit-fabric:build :worldedit-core:test :worldedit-core-mc:test :worldedit-fabric:testmodJar :worldedit-fabric:runSmokeTestServer
```

| 模块 | 测试数 | 失败/错误 |
| --- | ---: | ---: |
| worldedit-core | 176 | 0 |
| worldedit-core-mc | 9 | 0 |
| worldedit-fabric | 11 | 0 |
| 合计 | 196 | 0 |

覆盖原生放置能力选择、标准模式的 vector/biome 写入、历史保留、世界 ID、再生失败退出、
原生访问的线程归属/停服/中断，以及调度器在停止、重启、取消和异步清理时的行为。

## 实际服务器

开发环境及独立发行 JAR 各运行一次最终的 12 项检查，均为 `passed: true`、`failures: 0`：

1. 全部 35,723 个原生方块状态在 Minecraft → WorldEdit → Minecraft 转换后完全一致。
2. 实际 Brigadier 命令选区并放置跨四个区块的 125 个方块。
3. `//undo` 恢复所有原始状态。
4. `//redo` 恢复整个编辑。
5. `//fast` 仍使用有效放置路径。
6. `//copy`、`//paste` 完整复制 125 个方块。
7. `//setbiome` 改变选区内的八个精确 quart 生物群系单元。
8. 无 actor 的 API 编辑保留方块属性。
9. 独立三方块限制在第四次写入时生效。
10. 木桶 NBT 保留名称和七颗钻石；职业点随木桶写入和删除保持一致。
11. 主世界、下界、末地具有独立标识和历史名称；主世界/下界同坐标编辑及撤销互不干扰。
12. 重叠 `//move 1 east` 保留不同方块的顺序，撤销恢复原始状态。

发行包测试采用独立 Fabric 服务器和新测试世界，并同时加载 Lite2Edit 0.92、
Fabric Language Kotlin 1.14.1+kotlin.2.4.20。服务器正常停止，进程退出码为 0。
此结果验证共同加载及上述编辑行为，未验证 Lite2Edit 的所有文件转换功能。

本地完整证据：

* `build/port-26.3-final-build.log`
* `build/port-26.3-packaged-smoke.log`
* `worldedit-fabric/run-smoke-26.3/server-test-results.json`
* `worldedit-fabric/run-packaged-26.3/server-test-results.json`
* 各模块 `build/reports/tests/test/index.html`

## 发行产物与限制

产物：`worldedit-fabric/build/libs/worldedit-fabric-mc26.3-2.15.1-SNAPSHOT-dist.jar`

SHA-256：`6B340C8839648998B5A42A1655CE25280C0784B8138BD7987E6E79DBB9AA2292`

提交前重新构建通过全部 196 项测试，发行 JAR 与原始测试版本的 SHA-256 完全一致；
测试模组 JAR 也完全一致，确认提交的是该验证版本的源码。

当前使用原生逐块放置，FAWE 区块批量写入尚未实现；部分 WorldEdit 7.4.6 新核心 API
尚未同步。未实测图形客户端、真实单人世界切换、大规模编辑吞吐量及所有第三方模组。
单人重启的调度行为已有单元测试，客户端测试入口已保留，详见 [运行指南](FABRIC-26.3.md)。
