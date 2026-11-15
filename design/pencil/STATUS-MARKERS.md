# Luvia Pencil 源稿状态标记

更新日期：2026-11-15（Asia/Shanghai）  
本文件用于和 `design/pencil/luvia.pen` 一起入库，保留「已定稿 / 待验收」边界。上传或合入 **不代表设计验收通过**。

## 当前 `luvia.pen`（若已替换为本文件随附修订）

- 本地来源：2026-11-13 01:12（Asia/Shanghai）原生化修订
- sha256：`d4744771c549a8c34f368d40399f571326f125efac232a9e740d21373b352d14`
- 大小：2050190 bytes
- 内容：在已定稿帧集合上，把剩余位图改为原生图层；源稿内不再含 assets 位图。
- 状态：**待设计验收**。曾以静态对照（左旧 / 中新 / 右差异）给 Misaka 看过，尚未最终通过。
- Misaka 2026-11-15 21:34 授权将其上传到现有 `design/pencil`，并要求保留本标记；她同时明确这不代表设计验收通过。

## 已定稿实现基准（不要丢掉）

实现与视觉核对仍以这些为准，直到原生化修订通过验收并另有经确认的新导出：

1. HTML 原稿及其 PNG（见仓库外设计师目录 / 本交接包 `png-orig-spec/`）。
2. 已入库 UX 文档：`docs/final-rev-HANDOFF.md`、`docs/final-rev-CHANGES.md`、`docs/wow-CONCLUSION.md`、`docs/wow-sprint-CONCLUSION.md`、`docs/ios-machines-README.md`、`docs/terminal-light-bg-SPEC.md`、`docs/PI-LOGO-SPEC.md`、`README.md` 中的 UX 摘录。图文冲突按文。
3. 已入库 Pencil 导出 PNG（`png/`，2026-11-10 随 `10cd76d` 入库，sha 见 git）：对应 `.pen` sha256 `ba9bd6910bb2933bdaed94f8543629a38ade95bc9bfe69b56d9fa822edb9a1b2`（blob `fda8735d28a24d81a22063b8b928d4e6bacc74ea`）。这些 PNG **不是** d474 的导出。

旧源稿可在 git 历史中取回：`10cd76d` / main 树 `a1375f93a1fe0614aeaa89362b5efe307b631af7` 的 `design/pencil/luvia.pen`。

## 待验收、不能当作新实现基准的改画

2026-11-13 静态对照（`_cmp-2026-11-13/luvia`）中 before≠after 的项目：

- `lv-and-05-transition` 全帧
- `lv-ios-05-transition` 全帧
- `lv-ios-01-pair` 全帧
- `sec-lv-and-01-pair`、`sec-lv-ios-01-pair` 局部

其余帧没有经确认的 d474 全帧新导出；不得用旧 PNG 冒称新导出。动效亦未做验收。

## 仍未定稿、不在本源稿实现集

- mux-concepts D 整包（含 Android Diff 等）
- iOS 机器页 B/C
- `final-rev/workspace/` 筛选合并方向（未选之前，Android 筛选按 HANDOFF §5 FilterChip）
