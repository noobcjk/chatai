---
name: nbt-item
description: 用 nbt_item 工具发放、读取、修改带数据组件的物品，或用 json 文件批量发放
---
# NBT / 组件物品

发放、读取、修改带数据的物品，统一用 `nbt_item` 工具。它走创造模式槽位包，
**只在创造模式有效，不需要 op 权限**；生存模式下服务端会忽略这类包，工具会直接报错。

> **注意版本差异**：1.21 起物品不再有 `tag` NBT，改成了「数据组件（data components）」。
> 工具参数仍然叫 `nbt`（沿用习惯），但里面要填**组件**的 SNBT，写法跟 1.20.1 的 NBT 不一样。

## 操作

| op | 作用 | 需要的参数 |
| --- | --- | --- |
| `read` | 读某槽位物品的 id、数量、组件 | `slot`（不写就读手持） |
| `give` | 发放一件 | `id`，可选 `count`、`nbt`、`slot` |
| `set` | 用给出的组件**替换**手持物品的组件 | `nbt` |
| `load` | 读取工作区里的 json 文件批量发放 | `path` |

- `slot` 是背包索引：`0-8` 快捷栏，`9-35` 主背包；不写就自动找第一个空位，背包满了替换手持。
- `count` 默认 1，会被自动夹到该物品的堆叠上限。
- `set` 是**替换**语义：用给出的组件重建物品，没提到的组件回到默认值。
  想保留原有组件（比如附魔），就要在 `nbt` 里把它们一并写全。要先用 `read` 看清现有组件。

## 组件写法（SNBT）

`nbt` 是组件映射的 SNBT 字符串。常用例子：

- 改名：`{custom_name:'{"text":"神剑"}'}`
- 介绍文字：`{lore:['{"text":"第一行","color":"gray"}']}`
- 附魔：`{enchantments:{sharpness:5,unbreaking:3}}`
- 无法破坏：`{unbreakable:{}}`（无内容的「单位组件」写成空复合 `{}`）
- 自定义模型：`{custom_model_data:1}`
- 修复成本：`{repair_cost:0}`

要点：

- 显示文本这类本身就是文本组件的字段，用**单引号**包一段 JSON 文本，里面的双引号就不用转义。
- 数字后缀：`1b` 是 byte，`1` 是 int，`1.0d` 是 double。写错类型物品会加载失败。
- 物品 id 用带命名空间的形式：`minecraft:diamond_sword`、`minecraft:player_head`。
- 组件名是下划线小写（`custom_name`、`custom_model_data`），和 1.20.1 的 `display`、`CustomModelData` 完全不同。

## json 文件格式

`load` 读取工作区（默认 `/sdcard`，见设置里的工作区）里的 json。顶层可以是
`{"items":[...]}`，也可以直接是数组 `[...]`，每一项：

```json
{
  "items": [
    {
      "id": "minecraft:diamond_sword",
      "count": 1,
      "nbt": "{custom_name:'{\"text\":\"神剑\"}',enchantments:{sharpness:5}}",
      "slot": 0
    },
    { "id": "minecraft:golden_apple", "count": 16 },
    { "id": "minecraft:diamond_pickaxe", "count": 1, "nbt": "{unbreakable:{}}" }
  ]
}
```

- 每项的 `id` 必填，`count` / `nbt` / `slot` 可选；`slot` 不写就自动找空位。
- 因为 `nbt` 是 json 字符串，里面的双引号要写成 `\"`（SNBT 自己的显示文本再用单引号包）。
  所以 `{custom_name:'{"text":"神剑"}'}` 在 json 里写成
  `"{custom_name:'{\"text\":\"神剑\"}'}"`。
- 一次最多发放 64 件；某项 id 非法或组件解析失败时只跳过该项，并在结果里说明原因。

## 推荐流程

1. 先确认在创造模式，否则 `give` / `set` / `load` 会报错。
2. 不确定物品当前组件时，先用 `read` 看一眼，再决定 `set` 要写哪些组件（替换语义，要写全）。
3. 需要批量、可复用的物品定义，就写成 json 放工作区里，用 `load` 发放，
   这样不用每次把一大段 SNBT 塞进工具参数。
4. 发放 / 修改前会弹确认框，确认后才会真正生效；工具返回成功才代表服务端收下了。
