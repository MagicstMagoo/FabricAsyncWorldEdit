# Fabric 26.3 开发与运行

## 版本与模块

本分支使用 Minecraft 26.3、Java 25、Fabric Loader 0.19.5、Fabric API
0.161.0+26.3、非重映射 Loom 1.18.2 和 Gradle 9.8.0。Minecraft 26.1 起提供未混淆的
类名，因此不再配置 Mojang/Yarn mappings，也不再执行 `remapShadowJar`。

Minecraft 26.3 的 WorldEdit 平台版本为 7.4.6-beta-02。本项目保留 FAWE 2.15.1
核心，将该平台层接入异步命令、编辑会话、剪贴板和历史系统：

* `worldedit-core`：FAWE 编辑算法、命令、历史与工作线程。
* `worldedit-core-mc`：Minecraft 原生世界、实体、物品、NBT、注册表和 mixin。
* `worldedit-fabric`：Fabric 启动入口、生命周期、权限、事件和 FAWE 调度桥接。
* `worldedit-libs`：打包与依赖模块。

1.20.1 分支实际编译 `worldedit-fabric/src/upstream`。本分支使用标准的 `src/main`，
共享的 Minecraft 实现位于 `worldedit-core-mc/src/main`；旧的 `src/upstream` 已移除。

## 构建与安装

安装 JDK 25 并设置 `JAVA_HOME`，在仓库根目录执行：

```powershell
.\gradlew.bat :worldedit-fabric:build :worldedit-core:test :worldedit-core-mc:test
```

Linux/macOS 使用 `./gradlew`。Gradle toolchain 可自动查找或下载 JDK，仓库不绑定本机
Java 安装目录。Fabric 构建无需编译 Bukkit 适配器。

将 `worldedit-fabric/build/libs/worldedit-fabric-mc26.3-2.15.1-SNAPSHOT-dist.jar`
放入 Fabric 26.3 服务端或客户端的 `mods` 目录。使用 `-dist.jar`，其中包括 FAWE 核心、
Minecraft 平台代码、CUI 协议、所需 Fabric API 子模块以及 FAWE 的额外运行依赖。
移除同目录中的原版 WorldEdit JAR，避免重复的 `worldedit` mod ID。

配置文件位于 `config/worldedit`。Fabric mod 元数据以 `7.4.6-beta-02` 声明
WorldEdit 兼容版本；FAWE 的实际构建版本保存在 `custom["fawe:version"]` 和 JAR manifest
中。这用于满足依赖 WorldEdit 的模组版本条件，并不保证它们使用的全部新 API 都兼容。

## 开发环境与验证

```powershell
.\gradlew.bat :worldedit-fabric:runClient
.\gradlew.bat :worldedit-fabric:runServer
.\gradlew.bat :worldedit-fabric:runTestmodClient -PquickPlayWorld=<已有存档目录名>
```

单人测试需要已有的启用作弊的存档；测试模组执行选区、放置、撤销和重做，检查客户端收到
的方块状态，将结果写入运行目录的 `sp-test-results.json` 后退出。

独立的无图形界面回归测试使用 `worldedit-fabric/run-smoke-26.3`。阅读并接受 Minecraft
EULA 后，在该目录放置自己的 `eula.txt`，再执行：

```powershell
.\gradlew.bat :worldedit-fabric:runSmokeTestServer
```

此测试首次运行会生成只监听本机的专用服务器配置，检查全部方块状态往返转换、
实际 Brigadier 命令、跨区块放置、撤销/重做、快速模式、复制粘贴、生物群系、
方块实体 NBT、无玩家的 API 编辑和维度隔离，将结果写入运行目录的
`server-test-results.json` 后停止服务器。验证时应检查 JSON 中的成功状态，服务器退出码
本身不能证明测试通过；Gradle 测试任务会读取 JSON，并在缺少结果或检查失败时报告失败。
普通开发运行不会自动启用服务端测试。已有测试配置应保持 `pause-when-empty-seconds=0`，
防止无玩家时暂停服务器 tick。

最近一次验证结果见 [Fabric 26.3 验证记录](FABRIC-26.3-VALIDATION.md)。

## 执行方式与限制

命令和编辑计算使用 FAWE 工作线程；访问原生世界时转交给对应服务器的主线程执行。
同步延迟/重复任务由服务器 tick 驱动，停止服务器时取消旧世界的一次性任务，重新进入
单人世界时更新主线程身份并恢复长期重复任务。

目前 Fabric 没有实现 FAWE 的原生 `IChunkGet` 区块批量写入。平台能力检查强制使用
有效的原生放置路径，包括 OP、`//fast` 和无玩家的 API 会话；这能保持编辑正确性，但
不能提供 Bukkit 原生区块写入的吞吐量。数据库历史、区域保护集成、假区块数据包和
区块预加载尚未适配；磁盘/内存历史与正常方块更新仍通过 FAWE 核心处理。

各维度使用独立且适合文件路径的名称保存 FAWE 历史。主世界保留存档名，下界/末地等
使用存档名加经过百分号编码的维度 ID；`//world` 接受 `id()` 给出的完整标识，例如
`world_minecraft:the_nether`。

本次适配覆盖 Minecraft 平台层。WorldEdit 7.4.6 新增的部分公开核心 API（例如
`WorldEdit.getAssetLoaders()`、`getSchematicsManager()` 和 `LocalSession.getPlacement()`）
尚未同步到 FAWE 核心，依赖这些 API 的第三方模组仍需单独适配。

上游参考：

* [Minecraft 版本清单](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)
* [WorldEdit 26.3 发布版本](https://modrinth.com/plugin/worldedit/versions?g=26.3&l=fabric)
* [EngineHub WorldEdit](https://github.com/EngineHub/WorldEdit)
* [Fabric Loader 26.3 元数据](https://meta.fabricmc.net/v2/versions/loader/26.3)
