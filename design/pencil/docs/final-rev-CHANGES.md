# Luvia 最终图修订 · 2026-11-06

相对源：`luvus-mobile/native-a/wow`（wow 最终集）+ `luvia/mux-concepts-2026-11-01/wow-d`（方向 D）。**未覆盖**原文件；本目录是新副本。

## 改了什么

1. **Android 全面屏（所有 Android 帧）**
   - 状态栏、手势指示条改为透明叠加；列表/终端背景延伸到它们下面。
   - NavigationBar / 决策 sheet / 终端按键条贴在可见细手势柄上方，不留黑边或多余空白。
   - Token / 字体 / 圆角沿用 wow Material You seed `#006A62`。

2. **「等你决定」轮播（android-02-home、android-sessions、ios-02-home）**
   - 标题 `等你决定 · 3`。
   - 当前卡接近全宽，右侧露出下一张约 24dp；下方页点（第 1 个加长为当前）。
   - 三张 mock：pi（配对握手超时）、claude-code（TokenStore / 离线灰显）、grok（清理 token）；头像用官方标（pi 彩色几何、claude 星芒、grok）。

3. **终端失败摘要两态（android-04-terminal-*）**
   - **agent**：选中 `3 claude` → 无按钮；右侧状态 chip = claude 官方标 +「claude 在修…」+ 脉冲点。
   - **shell**：选中 `2 gradle` → 按钮「交给 claude 修」带会话 agent 官方标 + ▾（可换 agent）。
   - 保留：等宽 pane 分段、错误色实心摘要卡 + 细进度条、FAILED 行洗底、ctrl 激活 / ^C 区分、回到对话数字角标。

## 产出

| 文件 | 说明 |
|---|---|
| `png/android-01-pair@2x.png` | 配对 · 全面屏 |
| `png/android-02-home@2x.png` | 会话列表 · 轮播 |
| `png/android-03-chat@2x.png` | 对话 · sheet |
| `png/android-04-terminal-agent@2x.png` | 终端 · agent 在修 |
| `png/android-04-terminal-shell@2x.png` | 终端 · 交给修 |
| `png/android-05-transition@2x.png` | 转场 |
| `png/android-sessions@2x.png` | D 会话 · 轮播 |
| `png/ios-02-home@2x.png` | iOS 会话 · 轮播 |
| `png/overview.png` | 对照总览 |

重画：`cd src && python3 android.py && python3 gen_android_d.py && python3 gen_ios_home.py && python3 shot.py && python3 overview.py`

## 自检备注

- 轮播右露下一张仅约 24dp，主要露出下一张的 logo 边缘（符合需求）。
- shell 态按钮文案较长，已压到单行（`127/128 · 1 failed · 41s` 不换行）。
- iOS 只改了 home（便宜项）；iOS 终端/配对未动。
- 完整 DIFF 面仍属 D 定稿范围，本波未重画 `android-diff-note`。

---

## 第二轮 · 2026-11-06（Misaka 反馈：层叠卡 / iOS Diff / 原生控件）

1. **「等你决定」回到原 wow 层叠卡，改成竖向翻卡**（`android-02-home`、`android-sessions`、`ios-02-home`）
   - 方向跟随手势：上拖、下拖都能翻（详见下方「第二轮补」）。
   - 下面露两层卡边：下移 +6 / +12，scale .96 / .92，透明度 .72 / .45。后面只剩 1 张时只露 1 层；只有 1 张时不叠、不显示计数。
   - 右上角显示「1/3」计数。去掉横滑页点。官方标和全面屏保留。
   - 翻卡动效条：`png/android-02b-flip@2x.png`、`png/ios-02b-flip@2x.png`，已改为 6 帧（见下方「第二轮补」）。
   - 列表里 grok 行改成「等你 · 清理配对 token」，和「3 等你」对上。
2. **iOS 整页 Diff**（`png/ios-06-diff@2x.png`，3 帧：文件列表 → hunk → 批注 sheet）
   - NavigationStack push：大标题收成 inline，用系统返回加侧滑。
   - 文件列表用 inset grouped List；hunk 用等宽字体，+/− 行有底色。
   - 批注用 `.sheet`，detents 为 `.medium` / `.large`，带 grabber。
   - 顶栏和底栏用 Liquid Glass，有 scroll edge effect。
3. **原生控件审计**：`AUDIT-native-2026-11-06.md`，标注图在 `png/audit/`（8 张，全部是真会话帧）。
4. `png/overview.png` 重出：加了层叠首页、翻卡动效、iOS Diff；终端 agent / shell 两态保留。

新增脚本：`src/stack.py`、`src/gen_flip.py`、`src/gen_ios_diff.py`、`src/audit_crops.py`。`shot.py` 支持按元素截图。

重画命令：`cd src && python3 android.py && python3 gen_android_d.py && python3 gen_ios_home.py && python3 gen_flip.py && python3 gen_ios_diff.py && python3 shot.py && python3 overview.py && python3 audit_crops.py`

### 第二轮补 · 翻卡方向（Misaka 定稿 2026-11-06 09:34）
- 方向改成跟随手势：上拖、下拖都能翻，顶卡朝拖动方向移出，翻到下一张。
- 阈值：松手时位移超过卡高 1/3（本稿约 48dp），或速度超过约 800 dp/s，就翻页；否则弹回原位。弹簧参数 response .35 / damping .85。
- 只剩 1 张时，拖动只有橡皮筋阻尼，松手回原位。
- `png/android-02b-flip@2x.png`、`png/ios-02b-flip@2x.png` 改成 6 帧：① 静止 → ② 上拖跟手（过阈值）→ ③ 下拖跟手（过阈值）→ ④ 弹回（未过阈值，虚线框是松手位置）→ ⑤ 落定 2/3 → ⑥ 单张。帧里用虚线标出 ±1/3 卡高。
- 首页静态帧结构没改。`overview.png` 重出，说明文字已同步。
- 作废：上一版写的「下滑 = 上一张」和「阈值 30%」。

### 第二轮补 2 · 任意方向甩出（Misaka 改口径 2026-11-06 09:37，取代上一节的「只能上下」）
- 上、下、左、右、斜向都可以甩，都翻到下一张。顶卡沿手指拖动的方向飞出，下一张在原位浮出。
- 旋转：θ = 8° × clamp(水平位移 / 卡宽, −1, 1)，最多 ±8°，纯竖向拖动不旋转。
- 阈值：
  - 松手时位移超过 1/3 就翻页。横向按卡宽算（约 119dp），竖向按卡高算（约 48dp），斜向按主方向算。
  - 或者松手速度超过 800 dp/s，也翻页。
  - 两条都不满足就弹回原位，旋转归零。弹簧参数 response .35 / damping .85。
- 边缘安全区：屏幕左右 20dp/pt 以内起手的手势不交给翻卡，留给 iOS 侧滑返回和 Android 系统返回。
- 只剩 1 张时，任意方向拖动都只有阻尼（位移 × 0.3），松手回原位。
- `png/android-02b-flip@2x.png`、`png/ios-02b-flip@2x.png` 改成 8 帧：
  - ① 静止 + 边缘 20 安全区（每帧左右都标了红色斜纹）
  - ② 上拖
  - ③ 下拖
  - ④ 斜向甩出（由速度触发，+3.4°）
  - ⑤ 向左甩出（由位移触发，−6.7°）
  - ⑥ 弹回
  - ⑦ 落定 2/3
  - ⑧ 单张（只有阻尼）
- `overview.png` 重出；`AUDIT-native-2026-11-06.md` 里翻卡手势那一条同步改了。首页静态帧没改。
