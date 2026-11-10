# Luvia · Pencil 设计稿（定稿）

Luvia 定稿设计稿的 Pencil 版，按 Android（Material You）和 iOS（Liquid Glass）分开放，都是浅色帧，机器页另有深色帧。从原 HTML 稿逐元素重画：几何、颜色、字体、间距都取自 HTML/CSS 的计算值，HTML 是规格来源。字体：Android 用 Roboto，iOS 用 Inter 代替 SF Pro（原 HTML 在这台机器上就是渲染成 Inter），等宽用 JetBrains Mono，中文回落 Noto Sans SC，和原稿渲染一致。数值以 `docs/final-rev-HANDOFF.md` 为准，图和文冲突时按文。

## 文件

- `luvia.pen`：Pencil 源文件（pen.dev 2.22）。每个分区是一个顶层灰底 frame，里面每张屏一个 frame，frame 名 = 下表「frame」列。
- `png/<frame>.png`：Pencil 导出图，倍率与原稿 PNG 相同。
- `compare.png`：原稿 vs Pencil 对照总表；`_cmp/<frame>.png` 是单张「原稿 | Pencil | 差异」。
- `assets/`：位图资源（照片、截图类素材，以及少量无法用矢量表达的效果的栅格化，见「保真说明」）。
- `docs/`：原规格文档原样拷贝（未改字）。

## 总定稿（wow · Chat-first · 双端原生皮肤）


**图上看不出的 UX（原文摘录）**

> 摘自 `luvus-mobile/native-a/wow/CONCLUSION.md`

#### 定稿
- **结构**：方向 A（Chat-first）— 配对 → 会话列表 → 对话 → 顶栏进全屏终端
- **视觉**：`native-a/wow/` 惊艳版（Android Material You + iOS Liquid Glass），对照 `/workspace/designer/luvus-mobile/native-a/compare-now-vs-wow.png`
- **v1 新会话**：不做 Extended FAB，只在顶栏 `+`
- **签名交互**：决策卡全宽沉浸、压过普通气泡；工具轨迹可读/改/跑；配对仪式感；会话→终端有记忆点转场

#### 默认收口（定稿未另说）
- Android 决策卡：沿用 wow 稿 tertiary 蓝灰（与主色青绿分层）
- iOS 配对：沿用暗场 + 蓝点球体
- 转场：Android 350ms container transform；iOS ~0.42s spring
- 快捷回复：可从 B 借一点（会话列表上直接决策），已在 wow 稿体现

> 摘自 `luvia/wow-sprint-2026-10-31/CONCLUSION.md`

#### 定稿（Misaka · 项目需求对齐）
- **落地顺序**：对话 → 列表 → 终端 → 配对
- **底 tab**：本轮做「会话 / 机器 / 设置」（iOS 浮动玻璃 / Android M3 NavigationBar，对齐 wow）
- **结构不变**：A Chat-first；无 Extended FAB（新会话只顶栏 `+`）；双端原生皮肤

## Android · Material You（final-rev-2026-11-06，全面屏）

| frame | 平台 | 源 HTML | 原稿 PNG |
|---|---|---|---|
| `lv-and-01-pair` · Android · 01 配对 | Android | `luvia/final-rev-2026-11-06/android/01-pair.html` | `luvia/final-rev-2026-11-06/png/android-01-pair@2x.png` |
| `lv-and-02-home` · Android · 02 会话 · 等你决定层叠卡 | Android | `luvia/final-rev-2026-11-06/android/02-home.html` | `luvia/final-rev-2026-11-06/png/android-02-home@2x.png` |
| `lv-and-03-chat` · Android · 03 对话 · 需要决定 sheet | Android | `luvia/final-rev-2026-11-06/android/03-chat.html` | `luvia/final-rev-2026-11-06/png/android-03-chat@2x.png` |
| `lv-and-04-term-agent` · Android · 04 终端 · agent 在修 | Android | `luvia/final-rev-2026-11-06/android/04-terminal-agent.html` | `luvia/final-rev-2026-11-06/png/android-04-terminal-agent@2x.png` |
| `lv-and-04-term-shell` · Android · 04 终端 · 交给 claude 修 | Android | `luvia/final-rev-2026-11-06/android/04-terminal-shell.html` | `luvia/final-rev-2026-11-06/png/android-04-terminal-shell@2x.png` |
| `lv-and-05-transition` · Android · 05 会话→终端转场 | Android | `luvia/final-rev-2026-11-06/android/05-transition.html` | `luvia/final-rev-2026-11-06/png/android-05-transition@2x.png` |

**图上看不出的 UX（原文摘录）**

> 摘自 `luvia/final-rev-2026-11-06/CHANGES.md`

#### 改了什么

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

> 摘自 `luvia/final-rev-2026-11-06/HANDOFF.md`

#### 2 · 「需要决定」非模态 sheet（双端，已定稿）

- 三档高度：
  - **低档**：iOS 196pt / Android 208dp。显示 grabber、头像、「〈agent〉 需要你决定 · i/n」、两行问题、推荐项按钮。
  - **中档**：约半屏（iOS `.medium`）。能看到全部选项和「继续」按钮。
  - **高档**：iOS `.large`，Android 展开到内容高度（最高到状态栏下方）。
- 出现时停在**低档**。
- 下拉最多回到低档，**不能关闭**。
- 后面的对话**不加遮罩、可以滚动**。sheet 停在低档或中档时，后面的对话都能点。
- 有决策时**隐藏消息输入框**，只保留 sheet 里的「补充说明」。
- 「补充说明」获得焦点时，自动升到高档，键盘顶在输入框下方。
- 退场：
  - 答完、本会话没有下一个决策时：sheet 下滑退场，250ms，然后消息输入框淡入，150ms。
  - 本会话还有下一个决策时：sheet 不退场，内容横向交叉过渡（150ms）到下一个，标题计数随之变化，高度回到低档。
- 多个决策排队：按到达顺序，sheet 里只排本会话的决策。其他会话的决策留在首页层叠卡里。
- 双端：
  - Android：`BottomSheetDefaults.DragHandle()`，32 × 4dp。
  - iOS：显示 grabber，36 × 5pt。
- iOS 实现：
  - `.sheet(item:)`
  - `.presentationDetents([.height(196), .medium, .large], selection:)`
  - `.presentationBackgroundInteraction(.enabled)`，让后面的对话可滚可点
  - `.interactiveDismissDisabled()`
  - `.presentationDragIndicator(.visible)`
  - `.presentationContentInteraction(.scrolls)`
  - 不要自定背景和圆角，交给系统 Liquid Glass。
- Android 实现：
  - `BottomSheetScaffold` + `rememberStandardBottomSheetState(PartiallyExpanded, skipHiddenState = true)`
  - `sheetPeekHeight = 208.dp`
  - `sheetShape = BottomSheetDefaults.ExpandedShape`，顶部圆角 28dp
  - `sheetContainerColor = surfaceContainerLow`
  - 内容加 `navigationBarsPadding()`
  - 删掉现在手动加的 22% scrim。
  - ⚠️ M3 标准 sheet 原生只有两个状态：PartiallyExpanded 和 Expanded，其中 Expanded 的高度是内容高度。所以 Android 的「中档」就是内容高度展开；输入时键盘顶起，等于高档。不要为了凑三档自己写拖拽。

---

#### 5 · Android 筛选控件（已定：保留 FilterChip，只修布局 · Misaka 2026-11-06）

- 继续用 `FilterChip`，不换成分段按钮，视觉不改。
- 选中项加 `leadingIcon = Check`，外层 `Modifier.selectableGroup()`，单选。
- 筛选行放进首页唯一的 `LazyColumn` 做 `stickyHeader`：吸顶前底色 surface，吸顶后换 `surfaceContainer`（#E9EFEC）。
- chip 高 32dp、间距 8dp、左右边距 16dp；放不下时整行横向滚动，左右各留 16dp 内边距。
- 验收：首页往下滚时筛选行吸顶并变色；chip 按下时水波纹不超出 chip 形状。
- 注：「状态筛选 + 范围筛选合并成一排」另有新稿在 `workspace/` 下，等 Misaka 选。选定之前按本节做。

## 首页「等你决定」层叠卡 + 翻卡动效（双端，已定稿）

| frame | 平台 | 源 HTML | 原稿 PNG |
|---|---|---|---|
| `lv-and-02b-flip` · Android · 02b 翻卡 8 帧 | Android / iOS | `luvia/final-rev-2026-11-06/android/02b-flip.html` | `luvia/final-rev-2026-11-06/png/android-02b-flip@2x.png` |
| `lv-ios-02-home` · iOS · 02 会话 · 等你决定层叠卡 | Android / iOS | `luvia/final-rev-2026-11-06/ios/02-home.html` | `luvia/final-rev-2026-11-06/png/ios-02-home@2x.png` |
| `lv-ios-02b-flip` · iOS · 02b 翻卡 8 帧 | Android / iOS | `luvia/final-rev-2026-11-06/ios/02b-flip.html` | `luvia/final-rev-2026-11-06/png/ios-02b-flip@2x.png` |

**图上看不出的 UX（原文摘录）**

> 摘自 `luvia/final-rev-2026-11-06/HANDOFF.md`

#### 1 · 首页「等你决定」层叠卡（已定稿）

##### 1.1 静态
- 卡片：圆角 28dp/pt，iOS 用 `.continuous`；宽 = 屏宽 − 2 × 16dp/pt。
- 下面露出的卡边：第 1 层下移 6dp/pt、缩放 0.96、透明度 0.72；第 2 层下移 12dp/pt、缩放 0.92、透明度 0.45。缩放以卡片底边中点为原点。
- 卡边层数 = min(2, 当前卡后面还剩几张)。
- 计数显示在右上角，格式「i/n」。
  - Android：`labelMedium`，白色 55% 底，圆角 10dp。
  - iOS：`.caption.weight(.semibold).monospacedDigit()`，橙色 14% 底的胶囊。
- 分组标题写「等你决定 · n」。
- 一共 1 张时：不叠卡边、不显示计数。
- 一共 2 张时：在第 1 张露 1 层卡边、显示「1/2」；翻到第 2 张时不露卡边、显示「2/2」。
- 一共 0 张时：整个分组收起，高度动画 300ms。

##### 1.2 翻卡手势
- 任意方向都能甩：上、下、左、右、斜向，**都翻到下一张**。
- 在最后一张（n/n）再甩，回到 1/n 循环。
- 拖动中顶卡 1:1 跟手，位移就是拖动向量。
- 旋转 θ = 8° × clamp(dx / 卡宽, −1, 1)，最多 ±8°。纯竖向拖动不旋转。
- 松手判定，满足任一条就翻页：
  - 位移超过 1/3 卡宽（约 119dp/pt）或 1/3 卡高（约 48dp/pt）。按位移较大的那个轴取对应的值；卡高按实测高度算。
  - 松手速度超过 800 dp/s（pt/s）。
- 翻页过程：
  - 顶卡沿松手时的速度方向飞出屏幕，220ms，ease-out，飞出过程中透明度降到 0。速度太小时沿拖动方向飞出。
  - 下一张缩放 0.96 → 1，卡边整体上提一级。
  - 弹簧参数 response 0.35s、damping 0.85。
- 弹回：两条都不满足时，用同一组弹簧回到原位，旋转归零。
- 只剩 1 张：任意方向拖动的位移按 × 0.3 衰减（阻尼），不翻页，松手后弹回。
- 边缘安全区：屏幕左右各 20dp/pt 以内起手的手势不交给翻卡。
  - iOS 留给 NavigationStack 的侧滑返回。
  - Android 留给系统返回。卡片区域**不要**用 `systemGestureExclusion`。
- 和外层列表滚动的分工：**起手点在卡片内，手势全归卡片；起手点在卡片外，手势全归列表。**手势判定起点（slop）8dp/pt。
  - ⚠️ 需要真机调。如果竖向手感冲突，备选方案是：只有 |dy| < 2|dx|，或者列表已滚到顶时，竖向手势才归卡片。
- 卡片里的按钮照常点击。按钮上起手的拖动，超过 slop 就转给卡片，同时取消按钮的按下态。
- 实现参考：
  - iOS：`DragGesture(minimumDistance: 8)` + `.offset` + `.rotationEffect`。
  - Android：`pointerInput` + `detectDragGestures` + `VelocityTracker`；飞出用 `Animatable<Offset>`；旋转用 `graphicsLayer { rotationZ }`。

##### 1.3 队列
- 按决策到达时间排序，最早的在前。
- 翻卡只是换着看，不改变队列。
- 答完一张（点卡上的按钮，或者在对话里答）：
  - 这张卡缩放到 0.9、淡出，200ms，然后从队列里删掉。
  - 下一张原地放大上来。n 减 1，i 不变；如果 i 超过 n，取 n。
  - 分组标题和计数同步变化。
- 新决策到达：
  - 排到队尾，n 加 1。**顶卡不换**，不打断用户正在看的那张。
  - 如果它落在前 2 层卡边的位置，就从下方滑入（下移 +12 → 目标位置，透明度 0 → 目标值，250ms）。
  - 原来是 0 张时，整个分组展开（300ms），卡片淡入。
  - 手指正按在卡片上时，等松手之后再插入。

##### 1.4 无障碍 / 减弱动态
- VoiceOver / TalkBack：
  - 整张卡是一个元素，读作「〈agent〉 等你决定：〈问题〉，第 i 个，共 n 个」。卡上的按钮各自单独成为可聚焦元素。
  - 自定义 action：「下一张」，可加一个「上一张」。
  - iOS：`.accessibilityAction(named: "下一张")`。
  - Android：`semantics { customActions = listOf(CustomAccessibilityAction("下一张") { … }) }`。
  - 翻页后朗读新卡的内容。
- 减弱动态：
  - 开关来源：iOS 读 `accessibilityReduceMotion`；Android 读 `ValueAnimator.areAnimatorsEnabled()` 或动画缩放为 0。
  - 开启时：不飞出、不旋转；松手过阈值后直接交叉淡入淡出到下一张，150ms；拖动时卡片仍跟手，但不旋转。卡边层保持静止。

---

## iOS · Liquid Glass（wow 定稿：配对 / 对话 / 终端 / 转场）

| frame | 平台 | 源 HTML | 原稿 PNG |
|---|---|---|---|
| `lv-ios-01-pair` · iOS · 01 配对 | iOS | `luvus-mobile/native-a/wow/ios/01-pair.html` | `luvus-mobile/native-a/wow/png/ios-01-pair@2x.png` |
| `lv-ios-03-chat` · iOS · 03 对话 | iOS | `luvus-mobile/native-a/wow/ios/03-chat.html` | `luvus-mobile/native-a/wow/png/ios-03-chat@2x.png` |
| `lv-ios-04-term` · iOS · 04 终端 | iOS | `luvus-mobile/native-a/wow/ios/04-terminal.html` | `luvus-mobile/native-a/wow/png/ios-04-terminal@2x.png` |
| `lv-ios-05-transition` · iOS · 05 会话→终端转场 | iOS | `luvus-mobile/native-a/wow/ios/05-transition.html` | `luvus-mobile/native-a/wow/png/ios-05-transition@2x.png` |

**图上看不出的 UX（原文摘录）**

> 摘自 `luvia/final-rev-2026-11-06/HANDOFF.md`

#### 4 · 控件审计 P0（详见 `AUDIT-native-2026-11-06.md`）

验收一律在**真机 + 真会话**里做。双端各录一段屏，并说明是真会话还是夹具。

##### P0-1 · iOS 返回导航
- 现状：用 `stage` 字符串在 ZStack 里切页，返回按钮是自绘 chevron，所以没有侧滑返回。见 `ios-01-back-nav.png`、`ios-05-terminal-chrome.png`。
- 改法：
  - 改成 `NavigationStack(path:)` + `navigationDestination(for: Route)`；`Route` 有 chat、terminal、diff 三种。
  - 终端页用 `.navigationTransition(.zoom(sourceID:in:))`。
  - 禁止使用 `navigationBarBackButtonHidden` 和 `.toolbar(.hidden, for: .navigationBar)`。
- 验收：
  - 对话页、终端页、Diff 页都能从左缘侧滑返回，滑到一半松手会弹回原页。
  - 返回按钮是系统的玻璃圆钮，没有自绘的「‹ 对话」。

##### P0-2 · 决策 sheet（双端）
- 现状：决策是钉在底部的内嵌卡片，占屏约 60%，没有 grabber。Android 上还同时出现两个输入框。见 `ios-02-decision-sheet.png`、`android-01-decision-sheet.png`。
- 改法：按 §2 做。
- 验收：
  - 出现时停在低档。
  - 拖到中档、高档都能停住；往下拉只回到低档，关不掉。
  - 后面的对话能滚动、能点。
  - 屏幕上只有一个输入框。
  - 答完后 sheet 退场，消息输入框回来。

##### P0-3 · iOS 底栏
- 现状：底栏是自绘胶囊，靠 `ChromeHitShield` 命中盾挡点击漏到下面；列表行会从底栏下面露出来。见 `ios-03-tabbar.png`。
- 改法：
  - 用 `TabView` + `Tab(…)` × 3，再加 `Tab(role: .search)` + `.searchable`。
  - 加 `.tabBarMinimizeBehavior(.onScrollDown)`。
  - 删掉 `HomeChromeLayout` 和 `ChromeHitShield`。
- 验收：
  - 点「机器」「设置」从不误开下面的行。
  - 列表往下滚时底栏缩小，往上滚时恢复。
  - 搜索是系统自带的独立圆钮，点开后弹出系统搜索框。

##### P0-4 · 滚动边缘和裁切（双端）
- 现状：
  - iOS：首页上半部分固定不滚，行在底栏后面硬露出来；终端 pane 胶囊里的文字贴边被切。
  - Android：对话页顶栏是自绘的，首页滚动后颜色不变；筛选和卡片固定在列表外面。
  - 见 `ios-03`、`ios-05`、`android-02`、`android-03`。
- 改法：
  - iOS：
    - 首页整页做成一个 `List(.insetGrouped)`，行用 `NavigationLink`。
    - 加 `.scrollEdgeEffectStyle(.soft, for: [.top, .bottom])`。
    - pane 胶囊加 `.contentMargins(.horizontal, 6)` + `.clipShape(Capsule())`，两端各加 12pt 渐隐。
  - Android：
    - 用 `Scaffold` + `TopAppBar(scrollBehavior = pinnedScrollBehavior())`，滚动后底色 `scrolledContainerColor = surfaceContainer`（#E9EFEC）。
    - 首页只用一个 `LazyColumn`；筛选行用 `stickyHeader`。
    - 所有可点控件用 `Surface(onClick, shape)` 或 `clip(shape).clickable`。
- 验收：
  - iOS：列表滚到顶栏、底栏后面时是模糊渐隐的，看不到清晰的行文字；行按下时有系统高亮；pane 文字不贴边。
  - Android：内容一滚动，顶栏就变成 #E9EFEC；筛选行吸顶；开「显示点按操作」录屏，水波纹不超出控件形状（行、卡片、chip、选项、按钮逐个长按检查）。

---

## iOS · 整页 Diff（已定稿，3 帧）

| frame | 平台 | 源 HTML | 原稿 PNG |
|---|---|---|---|
| `lv-ios-06-diff-1` · iOS · 06 Diff 1 · 文件列表 | iOS | `luvia/final-rev-2026-11-06/ios/06-diff-1-files.html` | `luvia/final-rev-2026-11-06/png/_ios-06-diff-1.png` |
| `lv-ios-06-diff-2` · iOS · 06 Diff 2 · hunk | iOS | `luvia/final-rev-2026-11-06/ios/06-diff-2-hunk.html` | `luvia/final-rev-2026-11-06/png/_ios-06-diff-2.png` |
| `lv-ios-06-diff-3` · iOS · 06 Diff 3 · 批注 sheet | iOS | `luvia/final-rev-2026-11-06/ios/06-diff-3-note.html` | `luvia/final-rev-2026-11-06/png/_ios-06-diff-3.png` |

**图上看不出的 UX（原文摘录）**

> 摘自 `luvia/final-rev-2026-11-06/HANDOFF.md`

#### 3 · iOS 整页 Diff（已定稿，图见 `png/ios-06-diff@2x.png`）

- 入口：对话里 Diff 卡上的「看全部」→ `NavigationLink(value: Route.diff(threadID))` 推入。
- 返回：用系统返回按钮 + 左缘侧滑。不要隐藏返回按钮。
- 文件列表页：
  - 大标题「Diff」，往上滚后收成单行标题。
  - `List(.insetGrouped)` 分两段：「n 个文件」和「批注 · n」。
  - 点文件行，推入该文件的 hunk 页。点批注行，推入对应文件并定位到那一行。
- hunk 页：
  - 单行标题是文件名，副标题是「路径 · +a −b · n 批注」。
  - 右上 ↑↓：跳到上一个 / 下一个 hunk，`scrollTo(anchor: .top)`，300ms。在文件最后一个 hunk 按 ↓，跳到下一个文件的第一个 hunk。
  - 底部两个玻璃按钮：「‹ 上一文件」「下一文件 ›」，在本页切换文件，内容横向滑入，250ms，不压新的导航页。
  - 等宽字体 12pt，行高 21pt。新增行底色为绿色 14%，删除行为红色 11%。旧行号、新行号各占 28pt。
  - 长行横向滚动，不换行。
- 点一行加批注：
  - 被点的行加 2pt 蓝色描边。
  - 打开 `.sheet`，`.presentationDetents([.medium, .large])`，带 grabber；输入框获得焦点后升到 `.large`。
  - 左上 ✕：内容为空时直接关闭；有内容时先弹确认「放弃这条批注？」。
  - 右上 ✓：保存，sheet 关闭，批注气泡插在这一行下面，状态「未发送」。
  - 点已有的批注，打开同一个 sheet 编辑。
- 文件列表页底部的「按批注改」：把所有未发送的批注合并成一条输入，发给当前会话的 agent。发出后这些批注标为「已发给 〈agent〉」，然后自动返回对话页。

---

## iOS · 机器页 方向 A（浅 / 深 / 离线 / 空态）

| frame | 平台 | 源 HTML | 原稿 PNG |
|---|---|---|---|
| `lv-ios-mach-light` · iOS · 机器 A · 浅色 | iOS | `luvia/ios-machines-2026-11-09/html/ios-machines-A.html` | `luvia/ios-machines-2026-11-09/png/ios-machines-A@2x.png` |
| `lv-ios-mach-dark` · iOS · 机器 A · 深色 | iOS | `luvia/ios-machines-2026-11-09/html/ios-machines-A-dark.html` | `luvia/ios-machines-2026-11-09/png/ios-machines-A-dark@2x.png` |
| `lv-ios-mach-offline` · iOS · 机器 A · 离线 | iOS | `luvia/ios-machines-2026-11-09/html/ios-machines-A-offline.html` | `luvia/ios-machines-2026-11-09/png/ios-machines-A-offline@2x.png` |
| `lv-ios-mach-empty` · iOS · 机器 A · 空态 | iOS | `luvia/ios-machines-2026-11-09/html/ios-machines-A-empty.html` | `luvia/ios-machines-2026-11-09/png/ios-machines-A-empty@2x.png` |

**图上看不出的 UX（原文摘录）**

> 摘自 `luvia/ios-machines-2026-11-09/README.md`

#### 方向 A · 当前主机为主角
- 顶部是一张 Liquid Glass 主卡（`.glassEffect(.regular, in: .rect(cornerRadius: 28))`）：48pt 主色主机标、20pt 主机名、在线点 + 连接方式 + 延迟。
- 三个计数块的顺序是**等你**（橙）/**运行中**（蓝）/**会话**（中性），作为第二层信息。
- 路径直接列在卡里：文件夹 + 等宽路径 + 会话的 agent 官方标堆叠（最多 3 个，多的显示 +N）+「N 等你」胶囊 + ›。会话数由标堆叠表示，不再写「N 个会话」。
- 其他主机收进「其他电脑」，每台一张紧凑卡：60pt 主机行 + 44pt 路径行。离线主机降到 55% 不透明。
- 当前主机离线时，主卡缩成主机行 + 一个「重新连接」按钮，按钮保持满不透明度。

#### 完整 UX 说明（A 为准；B/C 的不同处单独标出）

##### 结构
- `NavigationStack` 根页，大标题「机器」（`.navigationBarTitleDisplayMode(.large)`），滚动时收成行内标题。
- 右上 `ToolbarItem(placement: .topBarTrailing)`：Liquid Glass「+」按钮，点开 `Menu`，两项是「扫码配对」和「输入配对码」，都 push 进现有配对页。
- 底部 Liquid Glass `TabView`：会话 / 机器 / 设置 + 独立搜索按钮，「机器」高亮。

##### 点按 / push / 侧滑返回
- **主机行**（A 主卡头、其他电脑卡头、B 主机行、C 组头）：`NavigationLink` push 主机详情，系统侧滑可返回。详情页（本稿不画）放连接信息、mux 类型、断开、重新连接、移除。
- **路径行**：**切到「会话」tab**，不在机器页里 push。同时把会话页的当前主机切到该路径所在主机，**范围下拉**设为该路径，**状态分段**重置为「全部」（方案 ②）。这次设置写进该主机的「记忆筛选」，不是临时状态。会话页的范围下拉和状态分段同时闪一次 0.6 s 高亮（开启「减弱动态效果」时不闪）。
- 该路径下只有 1 个会话时也进列表，不直接打开对话。
- 主机离线时也照常切过去，显示离线空态。
- 回到机器页：点底栏「机器」，滚动位置保持。
- **A 的计数块**也是按钮，都会切到会话 tab，范围设为当前主机：「等你」→ 状态=等你，「运行中」→ 状态=运行中，「会话」→ 状态=全部。C 的仪表规则相同，范围设为「全部主机」。B 没有这个入口。
- **A 离线卡的「重新连接」**：按钮内显示 `ProgressView`，成功后主卡用 0.35 s spring 展开回完整状态，失败时用卡内一行红字说明原因，不弹 alert。

##### swipeActions（全部用 `.swipeActions`，不自绘）
- 路径行左滑：「在此新建会话」（主色，`.tint(.accentColor)`）。新建后切到会话 tab 并打开这个会话。
- 主机行左滑：「断开」（`.tint(.gray)`）；离线主机行左滑：「移除」（`role: .destructive`，需要二次确认 `confirmationDialog`）。
- A 的主卡不是 List 行，不支持侧滑；断开、移除放在主机详情和主卡的长按 `contextMenu` 里。

##### 离线
- 离线主机：整行 55% 不透明，在线点变灰，副标题显示「离线 · 上次 X 前」。**不列路径**，因为路径来自在线时的 mux 会话列表，离线时不可信。
- 当前主机离线（A）：主卡隐藏计数和路径，只保留主机行和「重新连接」。仍然排在最上面，因为它还是当前主机。
- 主机在线但没有打开的路径：显示一行灰字「没有打开的路径」，行尾是主色「新建会话」（见 A-offline 帧里的 build-box）。

##### 空态
- 没有配对任何主机：隐藏右上「+」，页面中间显示 96pt 主色浅底图标、「还没有配对的电脑」、一行等宽命令 `luvus-companion pair`、主按钮「配对新电脑」（50pt 高，`.buttonStyle(.borderedProminent)` + `.controlSize(.large)`）。

##### 排序
- 当前主机永远在最上面（A 里就是主卡）；其余在线主机按最近使用排序；离线主机放最后，再按最后在线时间排序。
- 同一主机下的路径按最近活动排序，跟会话页范围下拉的选项顺序一致。有「等你」的路径不额外置顶，保证两边顺序完全一致。
- 列表顺序在页面可见时不重排，避免用户正要点的时候行跳走。新数据在下次进入页面或下拉刷新时生效。

##### 计数含义
- **等你**：该范围内状态为「等你决定」的会话数，跟会话页「等你」分段的数字一致。0 时隐藏胶囊；A 的计数块显示 0，不隐藏。
- **运行中**：agent 正在执行的会话数。
- **会话**（A）/ 标堆叠 / 灰色数字（B）：该范围内打开的会话总数，包括空闲。
- **在线 2/3**（C）：在线主机数 / 已配对主机数。
- agent 标堆叠：A、C 是每个会话一个标，排序为等你 → 运行 → 空闲，最多 3 个，超出部分显示「+N」；B 的主机行是每个 agent 一个标（去重）。

##### 动效
- 行按下：系统 List 行高亮（`rgba(120,120,128,.16)` 级别），松手后 0.25 s 淡出。A 主卡里的路径行用同样的自定义高亮，计数块按下缩放到 0.97，用 0.2 s easeOut。
- 路径行 → 会话 tab：用 TabView 原生切换，不加自定义转场。会话页筛选控件的高亮闪烁时长 0.6 s。
- 主机行 push：系统默认 push，可交互侧滑返回。
- 计数变化：`contentTransition(.numericText())`，0.3 s。主机上下线时，行的不透明度用 0.3 s easeInOut 过渡。
- 开启「减弱动态效果」时：去掉缩放和数字滚动，只保留淡入淡出。

##### VoiceOver
- A 主卡头：「misaka-mbp，当前电脑，已连接，Tailscale，延迟 12 毫秒，按钮，打开主机详情」。
- 计数块：「3 个会话等你决定，按钮，打开会话并筛选等你」。
- 路径行（A/B/C 统一）：「~/luvia，4 个会话，3 个等你，按钮，在会话中查看」。agent 标堆叠合并读成「pi、Claude Code、Codex 等 4 个 agent」，可放进 `accessibilityValue`。C 额外读最近一步摘要和时间。
- 离线主机：「home-nas，离线，上次在线 2 天前，按钮」。
- 自定义操作（`accessibilityActions`）跟 swipeActions 一一对应：「在此新建会话」「断开」「移除」。
- 路径正常朗读，不逐字母拼读；「~」读作「主目录」（在 `accessibilityLabel` 里预先替换）。

##### Dynamic Type（到 AX5）
- 所有文字用文本样式：主机名 `.title3.weight(.semibold)`（A 主卡）/ `.headline`；副标题 `.footnote`；路径 `.body.monospaced()`；计数数字 `.title2`。
- 到 `.accessibility1` 及以上时：
  - 路径行改成两行：路径单独占一行，允许折到第 2 行，中间截断（`.truncationMode(.middle)`）；标堆叠、等你胶囊、› 移到第二行左对齐。
  - A 的三个计数块从横排改成竖排（`ViewThatFits` 或 `dynamicTypeSize` 判断），每块一行，数字和标签左右排。
  - 主机副标题允许换行，在线点保持跟第一行对齐（`.firstTextBaseline`）。
  - C 的摘要行最多 2 行。
- 主机名过长时尾部截断，路径过长时中间截断（保留末级目录名）。
- 不压缩行高：行高随内容增长，最小 44pt。

##### 深色
- 背景纯黑，卡片 `secondarySystemGroupedBackground`（#1C1C1E），主色 #0A84FF，等你文字 #FFB340，在不透明背景上用 20% 橙底。A 主卡在深色下仍用 Liquid Glass，边缘高光由系统材质提供。

## Token · 终端浅色底板 + pi 官方 Logo

| frame | 平台 | 源 HTML | 原稿 PNG |
|---|---|---|---|
| `lv-tok-terminal` · 终端底板 token（terminal-light-bg/SPEC.md） | 通用 | `luvia/terminal-light-bg/SPEC.md（本目录 src/terminal-tokens.html 排版）` | `（无单独 PNG，对照用源 HTML 重渲染）` |
| `lv-brand-pi` · pi 官方 Logo（brand/logos） | 通用 | `luvia/brand/logos/*.svg（本目录 src/brand.html 排版）` | `（无单独 PNG，对照用源 HTML 重渲染）` |

**图上看不出的 UX（原文摘录）**

> 摘自 `luvia/terminal-light-bg/SPEC.md`

#### Light（必改）

| token | 值 | 说明 |
| --- | --- | --- |
| `terminalBg` | `#EEF5F2` | = wow `surface-cl`；略低于 app `surface` `#F4FBF8`，像一块板 |
| `terminalFg`（无 ANSI 正文） | `#161D1C` | = `on-surface`；对比 ≈13:1 |
| 次要行 / dim | `#3F4947` | = `on-surface-v` |
| 失败行底 | `#FFDAD6` @ 55% 或实心 | = `error-container` |
| 失败行描边 / 字 | `#BA1A1A` | = `error`；字也可用 `#410002` |
| 失败摘要条 | 保持现有浅色 error 条 | 勿跟深色条 |

输入框墨色跟浅底：`prefersDarkInk` 开；pane 未选标签同 `on-surface-v`。

#### Dark（不动）

| token | 值 |
| --- | --- |
| `terminalBg` | `#101615` |
| `terminalFg` | `#E4EEEB` |
| 失败描边 | 现有红描边 |

#### 验收
浅/深各一张：浅色主题日志区肉眼为浅薄荷底 + 深字；深色仍深底浅字。

> 摘自 `luvia/brand/PI-LOGO-SPEC.md`

#### 用法（对齐 #46）

1. **kind / agent = `pi`**（及明确是 Pi coding agent 的会话）→ 官方 mark，**禁止**紫底「P」字母圆。
2. **列表行头像、对话决策卡头像、工具轨旁头像**：优先彩色 `pi-logo.svg`，圆裁、留 ~12–15% 内边距，**不要**再套品牌底色圆（三色块本身是标）。
3. 只能单色的槽（通知小标、模板 mono）→ `pi-badge.svg`，浅底黑标 / 深底白标。
4. 未知 agent 仍可用字母圆；**不要**把 pi 当未知。
5. `skills` / `quote0` 等会话名 ≠ kind 映射：若底层 agent 是 pi，头像跟 **agent kind** 用 pi Logo；会话名首字母规则另定，不挡本项。

这两张原来没有单独的稿，是按 SPEC 表格和 `brand/logos/*.svg` 排出来的 token 板（源 HTML 在 `src/`），对照图是这份 HTML 在 Chrome 里的渲染。终端 token 也写进了 `.pen` 的 variables：`terminalBg` 和 `terminalFg` 分浅色和深色两套主题，`terminalDim`、`terminalFailBg`、`terminalFailStroke` 只有浅色值。深色失败描边在 SPEC 里只写了「现有红描边」，没有给色值，所以没有编值。

## 不在本文件（非定稿或未定）

- mux-concepts D（方向 D 整包，含 `final-rev/html/android-sessions.html` 的「D 会话」帧、`wow-d/` 各面、Android Diff 批注）：还没有定稿，按交代跳过，也没有单独出 `pending/`。
- iOS 机器页 B / C（`ios-machines-B/C.html`）：被否。
- `final-rev/workspace/`（会话筛选合并 ①②③ + iOS 机器页旧稿）：README 写的是「待 Misaka 选，不进 HANDOFF」。Android 筛选按 HANDOFF §5 保留 FilterChip，已经体现在 02-home 帧里。
- `ios-06-diff.html`（#w）：是三帧拼成的总图，内容和 diff-1/2/3 重复，没有单独画。
- wow 原版 Android 01–05：已经被 final-rev 的全面屏修订替换，用的是 final-rev 版。wow 原版 iOS 02-home 也被 final-rev 的层叠卡替换。
- `png/audit/*`、`reviews/*`：真会话截图标注和评审对照，不是设计稿。
- `luvus-mobile/` 的早期方向（native-a 以外、wow 以前的稿）。

## 保真说明
- 对照方法：同一份 HTML 在 Chrome 里截原稿，Pencil 导出同倍率 PNG，逐张并排目检，再算像素差（`_build.json` 里的 mean，0–255）。各帧 mean 在 1–8 之间。偏高的是 iOS 帧：原稿 PNG 圆角外露出了机身灰底，Pencil 导出是透明底，再加上深色底上的文字抗锯齿。
- 栅格化的部分：iOS 转场帧里加了 `filter: blur/brightness/saturate` 的背景层，带 mask 的终端渐隐区，以及含 mask / filter / pattern 的 SVG。Pencil 没有对应属性，这些改成位图填充（在 `assets/`）。其他都是可编辑图层：agent 官方标、pi 标、图标都转成了 path。
- 已知差异：iOS 的 SF Pro 在 Pencil 里没有，用 Inter 代替，和原 HTML 在这台机器上的渲染一致，但和真机 SF 有字宽差别。另外「 · 」两侧的空格比原稿略宽 1–2px。
- Liquid Glass 的背景模糊用 Pencil 的 `background_blur` 表达；`inset 0 0 0 Npx` 这类细线改成内描边；元素本来就有描边的，叠一个描边矩形。
