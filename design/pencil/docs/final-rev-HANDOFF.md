# Luvia 交接 · 2026-11-06（设计师 → 工程师 / 其他 bot）

图上画不出来的交互，都写在这里。数值单位：Android 用 dp，iOS 用 pt。两边数值相同时写成「dp/pt」。时长用 ms。
数值以本文为准。图里的标注如果和本文冲突，按本文做。

## 0 · 文件索引（根目录 `/workspace/designer/luvia/final-rev-2026-11-06/`）

| 内容 | 路径 |
|---|---|
| 总览 | `png/overview.png` |
| 首页 · 层叠卡（静态） | `png/android-02-home@2x.png`、`png/ios-02-home@2x.png`、`png/android-sessions@2x.png`（D 稿） |
| 层叠卡 · 翻卡动效（8 帧） | `png/android-02b-flip@2x.png`、`png/ios-02b-flip@2x.png` |
| 对话 / 决策 | `png/android-03-chat@2x.png`（只画了视觉，三档高度见 §2） |
| 终端 agent / shell 两种状态 | `png/android-04-terminal-agent@2x.png`、`png/android-04-terminal-shell@2x.png` |
| 配对、转场 | `png/android-01-pair@2x.png`、`png/android-05-transition@2x.png` |
| iOS 整页 Diff（3 帧） | `png/ios-06-diff@2x.png`（单帧：`png/_ios-06-diff-1/2/3.png`） |
| Android Diff（参考，不在本目录） | `/workspace/designer/luvia/mux-concepts-2026-11-01/wow-d/android-diff-note.png` |
| 审计标注图（真会话帧） | `png/audit/ios-01…05-*.png`、`png/audit/android-01…03-*.png` |
| 审计全文 / 改动记录 | `AUDIT-native-2026-11-06.md`、`CHANGES.md` |

---

## 1 · 首页「等你决定」层叠卡（已定稿）

### 1.1 静态
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

### 1.2 翻卡手势
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

### 1.3 队列
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

### 1.4 无障碍 / 减弱动态
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

## 2 · 「需要决定」非模态 sheet（双端，已定稿）

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

## 3 · iOS 整页 Diff（已定稿，图见 `png/ios-06-diff@2x.png`）

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

## 4 · 控件审计 P0（详见 `AUDIT-native-2026-11-06.md`）

验收一律在**真机 + 真会话**里做。双端各录一段屏，并说明是真会话还是夹具。

### P0-1 · iOS 返回导航
- 现状：用 `stage` 字符串在 ZStack 里切页，返回按钮是自绘 chevron，所以没有侧滑返回。见 `ios-01-back-nav.png`、`ios-05-terminal-chrome.png`。
- 改法：
  - 改成 `NavigationStack(path:)` + `navigationDestination(for: Route)`；`Route` 有 chat、terminal、diff 三种。
  - 终端页用 `.navigationTransition(.zoom(sourceID:in:))`。
  - 禁止使用 `navigationBarBackButtonHidden` 和 `.toolbar(.hidden, for: .navigationBar)`。
- 验收：
  - 对话页、终端页、Diff 页都能从左缘侧滑返回，滑到一半松手会弹回原页。
  - 返回按钮是系统的玻璃圆钮，没有自绘的「‹ 对话」。

### P0-2 · 决策 sheet（双端）
- 现状：决策是钉在底部的内嵌卡片，占屏约 60%，没有 grabber。Android 上还同时出现两个输入框。见 `ios-02-decision-sheet.png`、`android-01-decision-sheet.png`。
- 改法：按 §2 做。
- 验收：
  - 出现时停在低档。
  - 拖到中档、高档都能停住；往下拉只回到低档，关不掉。
  - 后面的对话能滚动、能点。
  - 屏幕上只有一个输入框。
  - 答完后 sheet 退场，消息输入框回来。

### P0-3 · iOS 底栏
- 现状：底栏是自绘胶囊，靠 `ChromeHitShield` 命中盾挡点击漏到下面；列表行会从底栏下面露出来。见 `ios-03-tabbar.png`。
- 改法：
  - 用 `TabView` + `Tab(…)` × 3，再加 `Tab(role: .search)` + `.searchable`。
  - 加 `.tabBarMinimizeBehavior(.onScrollDown)`。
  - 删掉 `HomeChromeLayout` 和 `ChromeHitShield`。
- 验收：
  - 点「机器」「设置」从不误开下面的行。
  - 列表往下滚时底栏缩小，往上滚时恢复。
  - 搜索是系统自带的独立圆钮，点开后弹出系统搜索框。

### P0-4 · 滚动边缘和裁切（双端）
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

## 5 · Android 筛选控件（已定：保留 FilterChip，只修布局 · Misaka 2026-11-06）

- 继续用 `FilterChip`，不换成分段按钮，视觉不改。
- 选中项加 `leadingIcon = Check`，外层 `Modifier.selectableGroup()`，单选。
- 筛选行放进首页唯一的 `LazyColumn` 做 `stickyHeader`：吸顶前底色 surface，吸顶后换 `surfaceContainer`（#E9EFEC）。
- chip 高 32dp、间距 8dp、左右边距 16dp；放不下时整行横向滚动，左右各留 16dp 内边距。
- 验收：首页往下滚时筛选行吸顶并变色；chip 按下时水波纹不超出 chip 形状。
- 注：「状态筛选 + 范围筛选合并成一排」另有新稿在 `workspace/` 下，等 Misaka 选。选定之前按本节做。
