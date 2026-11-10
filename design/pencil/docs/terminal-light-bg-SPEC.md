# 浅色终端底板（Misaka 2026-11-01）

浅色主题终端区不得再叠深底 `#101615`；跟 Material You mint 浅色面。

## Light（必改）

| token | 值 | 说明 |
| --- | --- | --- |
| `terminalBg` | `#EEF5F2` | = wow `surface-cl`；略低于 app `surface` `#F4FBF8`，像一块板 |
| `terminalFg`（无 ANSI 正文） | `#161D1C` | = `on-surface`；对比 ≈13:1 |
| 次要行 / dim | `#3F4947` | = `on-surface-v` |
| 失败行底 | `#FFDAD6` @ 55% 或实心 | = `error-container` |
| 失败行描边 / 字 | `#BA1A1A` | = `error`；字也可用 `#410002` |
| 失败摘要条 | 保持现有浅色 error 条 | 勿跟深色条 |

输入框墨色跟浅底：`prefersDarkInk` 开；pane 未选标签同 `on-surface-v`。

## Dark（不动）

| token | 值 |
| --- | --- |
| `terminalBg` | `#101615` |
| `terminalFg` | `#E4EEEB` |
| 失败描边 | 现有红描边 |

## 验收
浅/深各一张：浅色主题日志区肉眼为浅薄荷底 + 深字；深色仍深底浅字。
