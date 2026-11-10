# Luvia 原生控件审计 · 2026-11-06

依据：Misaka 11-06 反馈（决策 sheet、tab/分段选择器、iOS 返回无手势、iOS 底栏、两端控件边缘没裁到形状、滚动时内容透出/叠压）。
证据：真机帧（全部是**真会话**，没有用夹具帧）+ main `9081ce7` 只读代码。标注图在 `png/audit/`，每张左上角绿标「真会话」，并写明来源路径和提交。

| 标注图 | 来源（真会话） |
|---|---|
| `png/audit/ios-01-back-nav.png` | `playtest/luvia-49-main-c9d8cc6-2026-11-06/ios/03-chat.png` |
| `png/audit/ios-02-decision-sheet.png` | 同上 |
| `png/audit/ios-03-tabbar.png` | `playtest/luvia-49-main-c9d8cc6-2026-11-06/ios/01-list.png` |
| `png/audit/ios-04-segmented.png` | 同上 |
| `png/audit/ios-05-terminal-chrome.png` | `playtest/luvia-real-main-82c37d2-2026-11-05/ios/14d-terminal.png` |
| `png/audit/android-01-decision-sheet.png` | `playtest/luvia-49-main-c9d8cc6-2026-11-06/android/05-chat.png` |
| `png/audit/android-02-topbar-scroll.png` | `playtest/luvia-real-main-82c37d2-2026-11-05/android/02b-chat-scroll.png` |
| `png/audit/android-03-list-chips.png` | `playtest/luvia-47-main-0202537-2026-11-05/android/02-list-scrolled-history.png` |

编号 I1–I8（iOS）和 A1–A9（Android）对应图里的红框。

---

## P0

### P0-1 · iOS 返回导航：没有侧滑返回（I1、I7）
- **现状**：`ContentView` 用 `@State stage = "sessions"/"chat"/"terminal"/"pair"` 在 ZStack 里切页面；`ChatView` 顶栏是自己画的 `HStack { Button(chevron.left) … }`，终端页是自绘「‹ 对话」pill。没有 push，所以没有系统的 interactivePopGesture，没有系统返回钮，大标题也不会过渡成 inline。
- **改法**：
  - 根视图改成 `NavigationStack(path: $path)`，加 `enum Route: Hashable { case chat(String), terminal(String), diff(String) }`，用 `.navigationDestination(for: Route.self)` 推页。`onBack` 和 stage 全部删掉。
  - ChatView：`.navigationTitle(thread.title)`，iOS 26 用 `.navigationSubtitle("pi · 等你决定")`，旧系统用 `ToolbarItem(.principal)` 放两行标题。「终端」放 `ToolbarItem(.topBarTrailing)`。
  - 被 push 的页面一律**不要用** `.navigationBarBackButtonHidden(true)` 或 `.toolbar(.hidden, for: .navigationBar)`，否则侧滑返回会失效。
  - 终端想保留「缩略图放大」的效果：给「终端」按钮加 `.matchedTransitionSource(id: "terminal", in: ns)`，终端页加 `.navigationTransition(.zoom(sourceID: "terminal", in: ns))`（iOS 18+）。这样左缘侧滑、下拉都能交互返回。
  - 配对页只在首次启动时用 `.fullScreenCover`。
- **数值**：返回钮、标题字号、栏高都用系统默认，不自定。

### P0-2 · 决策 sheet：不是 sheet（I2、I3 / A1–A4）
- **iOS 现状**：`ChatView.decision` 是钉在 VStack 底部的卡片（圆角 28、阴影 16），占屏约 60%。没有 grabber，没有 detent，拖不下去，上文只剩一小段。
- **iOS 改法**：
  ```swift
  .sheet(item: $pendingAsk) { ask in
      DecisionSheet(ask: ask, …)
          .presentationDetents([.height(196), .medium, .large], selection: $detent)
          .presentationDragIndicator(.visible)
          .presentationBackgroundInteraction(.enabled)        // 后面的对话可滚可点（与 HANDOFF 一致）
          .presentationContentInteraction(.scrolls)
          .interactiveDismissDisabled()   // 下拉只回到 196pt，不会关掉
  }
  ```
  - 196pt 档：grabber、头像 + 「pi 需要你决定 · 1/3」、两行问题、推荐项主按钮。往上拉才看到全部选项和补充说明。
  - 选项用 `List` 的 inset 分组行，选中打 `checkmark`；「继续」用 `.buttonStyle(.glassProminent)`（iOS 26，旧系统用 `.borderedProminent` + `.controlSize(.large)`），高 50。
  - **不设** `presentationBackground` / `presentationCornerRadius`，让 iOS 26 自己画内缩的玻璃 sheet。
- **Android 现状**：`ChatScreen` 的 Column 里依次是 LazyColumn、手动 22% scrim（只盖列表）、`DecisionCard`（Surface，顶角 28dp，阴影 12dp）、`Composer`。顶栏没被 scrim 盖住（A1），没有 drag handle（A2），「补充说明」和「消息」两个输入框同时出现（A4）。
- **Android 改法**：用标准（非模态）底部 sheet，这样对话还能滚：
  ```kotlin
  val sheet = rememberStandardBottomSheetState(SheetValue.PartiallyExpanded, skipHiddenState = true)
  BottomSheetScaffold(
      scaffoldState = rememberBottomSheetScaffoldState(sheet),
      sheetPeekHeight = 208.dp,
      sheetDragHandle = { BottomSheetDefaults.DragHandle() },   // 32×4dp，上下各 22dp
      sheetShape = BottomSheetDefaults.ExpandedShape,             // 顶角 28dp
      sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
      topBar = { /* 见 P0-4 */ },
  ) { inner -> /* 对话 LazyColumn，contentPadding = inner */ }
  ```
  - 有 ask 时**不显示** `Composer`，只保留 sheet 里的「补充说明」，一套输入。手动 scrim 删掉。
  - sheet 内容加 `Modifier.navigationBarsPadding()`。选项行保持 `clip(RoundedCornerShape(16.dp))` → `clickable`，选中时描 2dp `tertiary` 边。
  - 如果 Misaka 想要「不答不能动」的阻塞式，换成 `ModalBottomSheet(sheetState = rememberModalBottomSheetState(confirmValueChange = { it != SheetValue.Hidden }), dragHandle = { BottomSheetDefaults.DragHandle() })`。我推荐非模态。

### P0-3 · iOS 底栏：自绘胶囊 + 命中盾（I4、I5）
- **现状**：`SessionListView.homeTabBar` 是 `HStack` 胶囊加 `luviaGlass`，再盖一层 UIKit `ChromeHitShield` 和 `highPriorityGesture`，用来防止点击漏到下面的行（代码注释里写了这个问题）。另外列表行会从底栏后面和下面露出来（I5），没有 edge effect。
- **改法**：换成系统 TabView。iOS 26 下它自动就是定稿里那样：浮动玻璃胶囊 + 独立的搜索圆钮，命中、边缘渐隐、滚动最小化都不用自己写。
  ```swift
  TabView(selection: $tab) {
      Tab("会话", systemImage: "bubble.left.and.bubble.right", value: .sessions) { NavigationStack { Sessions() } }
      Tab("机器", systemImage: "laptopcomputer", value: .machines) { NavigationStack { Machines() } }
      Tab("设置", systemImage: "gearshape", value: .settings) { NavigationStack { Settings() } }
      Tab(value: .search, role: .search) { NavigationStack { SearchResults() }.searchable(text: $query, prompt: "搜索会话") }
  }
  .tabBarMinimizeBehavior(.onScrollDown)   // iOS 26
  ```
  可以删掉的：`HomeChromeLayout`（shared）、`ChromeHitShield`、`applyChromeTap`、`claimChromeTap`。iOS 17/18 会退回标准 tab bar，可以接受。
- 视觉定稿不用改，iOS 26 的系统样式就是 `ios-02-home` 底部那样。

### P0-4 · 滚动边缘：内容和栏之间没有层次（I5 / A5–A9）
- **iOS 现状**：首页的标题、待决定、分段控件是固定在 ScrollView 上方的 VStack，只有行在滚。行从底栏后面露出来，也没有渐隐。
- **iOS 改法**：整页改成一个 `List`（`.listStyle(.insetGrouped)`）：
  - 「会话」用 `.navigationTitle` + `.large`。主机 chip 放 `ToolbarItem(.topBarLeading)`，「+」放 trailing。
  - 决策卡堆叠和分段控件做成前两个 section，加 `.listRowBackground(Color.clear)` 和 `.listRowInsets(.init(top: 8, leading: 16, bottom: 8, trailing: 16))`。
  - 行用 `NavigationLink(value: Route.chat(id))`，自带按下高亮和 chevron。现在的 `.onTapGesture` 按下时没有任何反馈。
  - iOS 26 加 `.scrollEdgeEffectStyle(.soft, for: [.top, .bottom])`，系统栏下自动渐隐。不要自己画渐变。
- **终端 pane 胶囊（I8）**：「7 luvus」贴着左缘被切掉。横向 ScrollView 加 `.contentMargins(.horizontal, 6, for: .scrollContent)` 和 `.clipShape(Capsule())`；iOS 26 用 `.scrollEdgeEffectStyle(.soft, for: .horizontal)`，旧系统两端各加 12pt 的 `LinearGradient` mask。pane 不超过 4 个时直接用 `Picker(.segmented)`。「‹ 对话」改成系统返回（P0-1）。
- **Android 现状**：
  - 对话顶栏是自绘的 `Row`（64dp + `statusBarsPadding`），滚动时底色不变，气泡直接切在下沿（A5、A6）。
  - 首页 `LargeTopAppBar` 设了 `scrolledContainerColor = surface`，和不滚时一样，没有色阶（A9）。主机 chip 放在 navigationIcon，折叠后「会话」挤在它右边（A7）。
  - 待决定、chips 固定在列表外，只有行在滚（A8）。
- **Android 改法**：
  - 对话页：`Scaffold(topBar = { TopAppBar(title = { Column { 标题; 副标题 labelSmall } }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, …) } }, actions = { TerminalThumbnail(…) }, scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(), colors = TopAppBarDefaults.topAppBarColors(containerColor = surface, scrolledContainerColor = surfaceContainer)) })`，Scaffold 加 `Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)`。
  - 首页：改成 `TopAppBar`（pinned），navigationIcon 放主机 chip，title 留空。大字「会话」做成 LazyColumn 的第一个 item，跟着滚走，和定稿一致，也不会挤。`scrolledContainerColor = surfaceContainer`（token `#E9EFEC`）。
  - 首页只留一个 `LazyColumn`：`item { 统计行 }`、`item { 决策堆叠 }`、`stickyHeader { 筛选行，背景 surface，吸顶后换 surfaceContainer }`、`items { 行 }`，`contentPadding = innerPadding`。
  - `NavigationBar(windowInsets = WindowInsets.navigationBars)` 和 `enableEdgeToEdge()` 已经是对的，不用动。

---

## P1

### P1-1 · iOS 分段控件（I6）
- **现状**：`HStack` 里 4 个 plain Button，加 `Capsule` 白底、阴影 3、`segmentTrack` 底（`#787880` 12%）。按下没反馈，VoiceOver 也读不出是分段控件。
- **改法**：
  ```swift
  Picker("筛选", selection: $filter) {
      Text("全部 \(all)").tag(Filter.all)
      Text("等你 \(w)").tag(Filter.needsYou)
      …
  }
  .pickerStyle(.segmented)
  .padding(.horizontal, 16)
  .padding(.vertical, 8)
  ```
  高度用系统的。删掉 `segmentTrack` 和 `filterChip`。

### P1-2 · Android 筛选
`FilterChip` 本身就是 M3 原生的，用来筛列表也是规范写法，不用换控件。问题在布局（P0-4 的 A8）。顺手补两点：
- 选中项加 `leadingIcon = { Icon(Icons.Filled.Check) }`，和定稿一致。
- 单选语义：外层加 `Modifier.selectableGroup()`。

如果 Misaka 要和 iOS 完全同构，可以换成 `SingleChoiceSegmentedButtonRow` + `SegmentedButton(shape = SegmentedButtonDefaults.itemShape(i, 4))`，高 40dp，选中色 `secondaryContainer #CCE8E3`。终端 pane 已经是这个控件。这个需要她来定，我不擅自改定稿。

### P1-3 · 水波纹和按下态的边界（Misaka 说的「Android 水波纹溢出」）
- **没有帧能证明**：现有帧都是静态截图，水波纹只出现一瞬，所以没有一张拍到溢出。
- **代码查了也没找到**：`9081ce7` 里 `ChatScreen`、`SessionListScreen`、`TerminalScreen` 的每个 `.clickable` 前面都有 `.clip(shape)`，要么是整宽矩形行，要么是 `Surface(onClick, shape)`。所以我**定位不到具体是哪一个**，不编。
- **规则**（工程师按这个查）：可点控件一律用 `Surface(onClick = …, shape = …)`，或者 `Modifier.clip(shape).clickable(…)`。不能出现没 clip 的 `.background(color, shape).clickable`。圆角容器里的整行点击，先 clip 容器。
- **需要试玩员补拍**（Android 打开「显示点按操作」，60fps 录屏）：逐个长按列表行、决策卡正文和两个按钮、每个 FilterChip、主机 chip、终端缩略图、pane 分段、NavigationBar 三项、决策选项行、「选定并继续」。iOS 录一段聊天页和终端页的**左缘侧滑**。上次 agent-device 的 swipe 命令报 INVALID_ARGS，没录到，见 `luvia-real-main-82c37d2-2026-11-05/ios/21-swipe.txt`。另外双端各拍一张**对话滚到中间、决策 sheet 在**的帧。

### P1-4 · 首页「等你决定」层叠翻卡（本轮新定稿，交给实现的要点）
- **视觉**：见 `png/android-02-home@2x.png`、`png/ios-02-home@2x.png`、`png/*-02b-flip@2x.png`。
  - 卡片圆角 28（iOS 用 `.continuous`）。
  - 下面两层边：下移 6 / 12，scale 0.96 / 0.92（以底边为原点），透明度 0.72 / 0.45。
  - 后面剩 1 张时只露 1 层；只有 1 张时不叠、不显示计数。
  - 右上角计数：Android 写成 `labelMedium`，白 55% 底，圆角 10dp；iOS 写成 `.caption.weight(.semibold).monospacedDigit()`，橙 14% 底的胶囊。
- **手势**：上、下、左、右、斜向都可以甩，都翻到下一张。顶卡沿拖动方向飞出，下一张在原位浮出。
  - 旋转：θ = 8° × clamp(dx / 卡宽, −1, 1)，最多 ±8°。
  - 阈值：松手时位移超过 1/3 就翻页，横向按卡宽算（约 119dp），竖向按卡高算（约 48dp），斜向按主方向算；或者速度超过 800 dp/s 也翻页。两条都不满足就弹回原位、旋转归零。弹簧参数 response 0.35 / damping 0.85。
  - 边缘安全区：起手点在屏幕左右 20pt/dp 以内的手势，不交给翻卡。iOS 留给 NavigationStack 的侧滑返回；Android 留给系统返回（gesture nav 的边缘返回）。卡片区域不要调用 `Modifier.systemGestureExclusion`，否则会抢掉系统返回。
  - 只剩 1 张时，任意方向拖动都只有阻尼（× 0.3）。
  - 和外层列表竖向滚动的冲突：起手落在卡片上才接管手势，需要真机试手感。
  - iOS：`DragGesture(minimumDistance: 8)`，`.rotationEffect` 加 `.offset`；无障碍用 `.accessibilityAction(named: "下一张")`。
  - Android：`pointerInput` + `detectDragGestures` + `VelocityTracker`，飞出用 `Animatable<Offset>`，旋转用 `graphicsLayer { rotationZ }`。
- **裁切**：卡片和每层边都要 `clip` / `clipShape` 到 28 圆角，按下态限制在卡内。

---

## 能确认的 / 不能确认的
- **能确认**（帧 + 代码一致）：
  - iOS 用 stage 切页，没有 NavigationStack；
  - iOS 底栏和分段控件是自绘的；
  - 双端决策区是内嵌卡片，不是 sheet；
  - Android 对话顶栏是自绘 Row，首页滚动后颜色不变；
  - Android 决策时有两个输入框；
  - iOS 终端 pane 文字贴边被切。
- **不能从帧确认**：
  - 水波纹溢出（瞬时，没拍到，代码里也没找到）；
  - iOS 侧滑失败本身（没有录屏。代码层面一定失败，因为没有 push）。
- 两张 Android 帧左缘中段都有一条灰竖条（`05-chat`、`02-list-scrolled-history`），代码里没有对应的视图，判断是系统（HyperOS）叠加层，不算 App 的问题。
