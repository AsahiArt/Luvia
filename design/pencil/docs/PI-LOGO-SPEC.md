# Pi agent 官方 Logo · 接入规格 · 2026-11-05

Misaka 定：pi 有官方 Logo，列表/对话等处集成，勿再用字母圆。对照 #46（claude/codex/grok）。

## 资产（官方 press kit）

来源：[pi.dev/press-kit](https://pi.dev/press-kit)

| 文件 | 用途 |
|---|---|
| `/workspace/designer/luvia/brand/logos/pi-logo.svg` | **首选**：彩色 primary mark（珊瑚 `#F09082` / 蓝 `#4D9ABF` / 黄 `#F1BE58`） |
| `pi-badge.svg` | 单色 square badge（浅色界面用黑 `#111`，深色用浅 `#f6f6f6`） |
| `pi-logo-auto.svg` | 同 primary，主题自适应 |

## 用法（对齐 #46）

1. **kind / agent = `pi`**（及明确是 Pi coding agent 的会话）→ 官方 mark，**禁止**紫底「P」字母圆。
2. **列表行头像、对话决策卡头像、工具轨旁头像**：优先彩色 `pi-logo.svg`，圆裁、留 ~12–15% 内边距，**不要**再套品牌底色圆（三色块本身是标）。
3. 只能单色的槽（通知小标、模板 mono）→ `pi-badge.svg`，浅底黑标 / 深底白标。
4. 未知 agent 仍可用字母圆；**不要**把 pi 当未知。
5. `skills` / `quote0` 等会话名 ≠ kind 映射：若底层 agent 是 pi，头像跟 **agent kind** 用 pi Logo；会话名首字母规则另定，不挡本项。

## 验收

- 真会话列表至少一行 `pi` / `logos-pi` 显示彩色官方标（非 P）
- 决策卡若出现 pi，头像同标
- 双端（本波可跟 Android wow；iOS 一并补）

## 对照

- #46 REVIEW：`/workspace/designer/luvia/reviews/pr46/REVIEW.md`
- wow-d logos：`/workspace/designer/luvia/mux-concepts-2026-11-01/wow-d/src/logos/`
