# Auto Combo

一个 Minecraft Forge 客户端模组，为 [SlashBlade:Resharped](https://github.com/0999312/SlashBlade_Resharped) 的连段提供自动释放：按住一个键，模组按刀当前的真实连段状态逐段推进，直到把整条链打完。

不注入模拟输入，不修改游戏逻辑，只在正确的时刻调用游戏自己的 `useItem`。

## 环境要求

| 项 | 版本 |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.x（在 47.4.13 上测试） |
| SlashBlade:Resharped | **1.8.62**（编译时对照此版本，其他版本可能不兼容） |
| Java | 17 |

## 安装

把 jar 放进 `mods` 文件夹，启动游戏。

联机时**只有主机需要安装**。模组标记为 `clientSideOnly`，不会参与 Forge 的版本校验，其他人可以直接连进你的局域网房间。

## 使用

1. `选项 → 控制 → 按键绑定` → 底部「杂项」
2. 找到 **Auto Combo (hold)**，点它，然后按下你想用的键（鼠标侧键或键盘键都行）
3. 手持拔刀剑，按住这个键

默认绑定是鼠标键 4（GLFW button 3）。注意 Minecraft 界面里的编号是 1 起算的：显示的 `Mouse 4` 对应 GLFW 3。

按住期间模组会自动推进 A 段 → B 段 → 打完 b7。松手立刻停。

## 工作原理

连段状态机在拔刀剑的 capability 里，由**服务端**逐 tick 推进。这个模组也在服务端跑：

```java
ResourceLocation current = state.resolvCurrentComboState(player);  // 权威状态
ComboState combo = registry.getValue(current);
ResourceLocation next = combo.getNext(player);                     // 窗口开了吗
if (!next.equals(current)) click();
```

`ComboState.getNext()` 内部是 `TimeoutNext`：输入窗口没开时返回当前状态自己，开了才返回下一段。所以这一个比较就等价于「现在能不能点」，不需要任何毫秒数。

几个状态必须**故意不点**（代码里的 `SKIP` 集合）：

- `combo_a3`：它的正常分支通向 `combo_a4`，而 `combo_a4.next` 是 `none` —— 点了链条当场断掉。必须让它超时滑进 `combo_a3_end`，那里才是进入 B 段的唯一入口。
- `combo_a4` / `combo_a4_ex` / `combo_a5ex`：同样是死路或蓄力分支。
- `combo_b7`：B 段终点，成就「完成连击B」在进入它的瞬间授予，之后再点只会重新起手。

## 限制

- **只有主机生效**。逻辑跑在逻辑服务端，需要和客户端在同一个 JVM（单机 / 局域网开放）。进别人的房间不会起作用。
- **专用服务器不支持**。`clientSideOnly` 让模组不会加载到独立服务端；真要支持得改成网络包形式。
- **绑定拔刀剑版本**。编译时引用的是 1.8.62 的 API，换版本可能 `NoSuchMethodError`。
- **公开服务器慎用**。多数服务器禁止自动战斗类模组。行为上是自动操作，请只在单人 / 私人联机使用。

## 排错

日志里搜 `[autocombo]`：

```
[autocombo] constructed                 模组已实例化
[autocombo] state -> combo_b1           状态机当前处于哪一段
[autocombo] click from combo_b1 (tick 12345)
```

常见现象：

| 现象 | 原因 |
|---|---|
| 完全没有 `[autocombo]` 行 | 模组没加载，检查 mods 目录和 Forge 版本 |
| 按了没反应，也没有 `state ->` | 键没传到游戏，或手里不是拔刀剑 |
| 状态停在 `combo_a3` 不动 | 正常，在等它超时进 `a3_end` |
| 掉进 `combo_a2_end` / `combo_c` | 有额外的手动点击插进来，或者用了其他自动点击工具 |

## 从源码构建

```bash
# 需要 JDK 17
# 先把 SlashBlade:Resharped 的 jar 放到 libs/slashblade.jar（不随仓库分发）
./gradlew build
```

产物在 `build/libs/`。

`gradle/wrapper/gradle-wrapper.properties` 指向 Gradle 8.8。国内网络如果下载慢，可以改用本地已安装的 Gradle 8.x。

## 许可

MIT，见 [LICENSE](LICENSE)。

本项目不包含 SlashBlade:Resharped 的任何代码或资源，仅在编译期引用其 API。
