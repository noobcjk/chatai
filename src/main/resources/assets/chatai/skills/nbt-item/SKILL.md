---
name: nbt-item
description: 用 nbt_item 工具发放、读取、修改带 NBT 的物品，或用 json 文件批量发放
---
# NBT 物品

发放、读取、修改带 NBT 的物品，统一用 `nbt_item` 工具。它走创造模式槽位包，
**只在创造模式有效，不需要 op 权限**；生存模式下服务端会忽略这类包，工具会直接报错。

## 操作

| op | 作用 | 需要的参数 |
| --- | --- | --- |
| `read` | 读某槽位物品的 id、数量、NBT | `slot`（不写就读手持） |
| `give` | 发放一件 | `id`，可选 `count`、`nbt`、`slot` |
| `set` | 把手持物品的 NBT 与 `nbt` **合并** | `nbt` |
| `load` | 读取工作区里的 json 文件批量发放 | `path` |

- `slot` 是背包索引：`0-8` 快捷栏，`9-35` 主背包；不写就自动找第一个空位，背包满了替换手持。
- `count` 默认 1，会被自动夹到该物品的堆叠上限。
- `set` 是合并语义：给出的键覆盖同名键，没提到的键保留。只改名字不会把附魔弄丢。

## NBT 写法（SNBT）

`nbt` 是 SNBT 字符串，也就是 1.20.1 `/give` 命令里大括号里的那一段。常用例子：

- 改名：`{display:{Name:'{"text":"神剑"}'}}`
- 附魔：`{Enchantments:[{id:"sharpness",lvl:5},{id:"unbreaking",lvl:3}]}`
- 介绍文字：`{display:{Lore:['{"text":"第一行","color":"gray"}']}}`
- 无法破坏：`{Unbreakable:1b}`
- 自定义模型：`{CustomModelData:1}`

要点：

- 名字 / 介绍这类显示文本本身是 JSON，用**单引号**包起来，里面的双引号就不用转义。
- 数字后缀要写对：`1b` 是 byte，`1` 是 int，`1.0d` 是 double。写错类型物品可能加载失败。
- 物品 id 用带命名空间的形式：`minecraft:diamond_sword`、`minecraft:player_head`。

## json 文件格式

`load` 读取工作区（默认 `/sdcard`，见设置里的工作区）里的 json。顶层可以是
`{"items":[...]}`，也可以直接是数组 `[...]`，每一项：

```json
{
  "items": [
    {
      "id": "minecraft:diamond_sword",
      "count": 1,
      "nbt": "{display:{Name:'{\"text\":\"神剑\"}'},Enchantments:[{id:\"sharpness\",lvl:5}]}",
      "slot": 0
    },
    { "id": "minecraft:golden_apple", "count": 16 },
    { "id": "minecraft:player_head", "count": 1, "nbt": "{SkullOwner:\"Notch\"}" }
  ]
}
```

- 每项的 `id` 必填，`count` / `nbt` / `slot` 可选；`slot` 不写就自动找空位。
- 因为 `nbt` 是 json 字符串，里面的双引号要写成 `\"`（SNBT 自己的显示文本再用单引号包）。
  所以 `{display:{Name:'{"text":"神剑"}'}}` 在 json 里写成
  `"{display:{Name:'{\"text\":\"神剑\"}'}}"`。
- 一次最多发放 64 件；某项 id 非法或 NBT 解析失败时只跳过该项，并在结果里说明原因。

## 推荐流程

1. 先确认在创造模式，否则 `give` / `set` / `load` 会报错。
2. 不确定物品当前 NBT 时，先用 `read` 看一眼，再决定 `set` 要合并哪些键。
3. 需要批量、可复用的物品定义，就写成 json 放工作区里，用 `load` 发放，
   这样不用每次把一大段 SNBT 塞进工具参数。
4. 发放 / 修改前会弹确认框，确认后才会真正生效；工具返回成功才代表服务端收下了。
