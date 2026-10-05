# 神之牛仔帽（HatMod）

三顶**牛仔帽**：黑 / 白 / 红。戴上之后会像手电筒一样，对着面前的敌人持续照射一道光柱，
附带各自专属的技能与附魔。

> 一个模组，两棵源码树 —— 分别对应两个模组加载器：
>
> | 目录 | 游戏版本 | 加载器 |
> | --- | --- | --- |
> | `hatmod-forge-1.20.1/` | Minecraft 1.20.1 | Forge 47.x（Java 17） |
> | `hatmod-neoforge-1.21.1/` | Minecraft 1.21.1 | NeoForge 21.1.x（Java 21） |
>
> 两棵树的玩法内容一致，只是适配不同的加载器。完整的设计文档在各自的 `README.md` 里。

## 下载

仓库根目录下已经有编译好的成品，直接丢进 `.minecraft/mods/` 即可（只装对应版本的那一个）：

- `hatmod-forge-1.20.1-1.0.0.jar` —— 给 **Minecraft 1.20.1 + Forge**
- `hatmod-neoforge-1.21.1-1.0.0.jar` —— 给 **Minecraft 1.21.1 + NeoForge**

## 玩法速览

- **和平主义**：戴上帽子后不能攻击被动生物与未被激怒的中立生物。
- **合法目标**（敌意生物 / 被激怒的中立生物 / 正在锁定你的生物 / 最近真的打过你的生物）
  进入 **16 格**就开始蓄力，蓄力结束后进入**持续照射**，自动锁定最近的敌人。
- 被照中的敌人每刻受到「按血量算」的伤害、被缓慢 255 定身，照完重新进入循环。
- 三顶帽子各有**专属效果**（黑：闪避/预知；白：速度与支援/神隐；红：输出/掌控/多道光柱）
  与**专属附魔**。

细节（数值、状态机、附魔表、可调参数）都在各树的 `README.md`。

## 兼容

- **Curios**（可选）：装了之后帽子也能放进饰品栏的 `head` 槽，效果和戴在头上完全一样。
  没装 Curios 也能正常跑。
- 本模组**不需要**任何前置模组。

## 自己编译

```bat
:: Forge 版（需要 JDK 17）
cd hatmod-forge-1.20.1
gradle build

:: NeoForge 版（需要 JDK 21）
cd hatmod-neoforge-1.21.1
gradle build
```

产物出现在各自的 `build/libs/` 下。两棵树都需要本机已装 **Gradle 8.x**（仓库里没有带 wrapper）。

> `*/libs/curios-*-api.jar` 是**编译期**用的 Curios API（`compileOnly`，不会打进成品），
> 放在仓库里是为了离线也能编译。

## 目录结构

```
hatmod-forge-1.20.1/          Forge 1.20.1 源码（含 README.md 完整文档）
hatmod-neoforge-1.21.1/       NeoForge 1.21.1 源码（含 README.md 完整文档）
hatmod-forge-1.20.1-1.0.0.jar       成品
hatmod-neoforge-1.21.1-1.0.0.jar    成品
```

## 许可

[MIT](LICENSE)。
