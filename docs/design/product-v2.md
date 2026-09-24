# Luvia v2 产品设计

Status: accepted (2026-09-24)。取代 `docs/design/redesign.md` 的 IA 与视觉部分；不改信任模型（SSH pinned、`luvia-host` 持有凭据）、ADR 0001–0003、`CONTEXT.md` 的对象树。

---

## 0. 为什么现在乱

`082dc4a` 那版把 tab 从「UHP 方法族」改成了「对象」，但用户仍然同时面对**三套心智模型**：

| 来源 | 用户看到的 | 问题 |
|---|---|---|
| UHP | Agents / Workspace / More、Review 层、Tasks、Automations、Layout、Worktrees | 桌面 TUI 的全部能力被平铺到手机上 |
| Terminal | 对话里 `Transcript | Terminal` 两段；Transcript 本身又是等宽的屏幕文本 | 同一个 agent 有两种"原始"视图，没有一种是"对话" |
| ACP | 气泡、tool 行、plan、permission 卡 | 只有手机启动的 agent 才有好看的 UI，pane agent 没有 |

结果：同一个"agent"，因为来源不同长得完全不同；More 是杂物间；Workspace 靠 `workspace.focus` 定位，这是协议细节漏到了 IA。

## 1. 定位（一句话）

**Luvia 是装在口袋里的 agent 值班台：看一眼谁卡住了，替它做决定，审完它的改动。**

三个动词决定一切：**看（Glance）· 答（Answer）· 审（Review）**。
做不到这三件事之一的功能不上首屏；需要键盘和大屏的事情留给桌面 Luvus。

## 2. 三者各取所长

| 取自 | 长处 | 在 v2 中的角色 |
|---|---|---|
| UHP | 权威状态：agent 状态、Blocked、diff、notes、tasks、role | **数据层 / 真相来源**。UI 不暴露任何 UHP 名词 |
| ACP | 结构化对话：消息、思考、工具调用、plan、permission 选项 | **唯一的对话 UI 语言**。所有 agent 都用它的形状渲染 |
| Terminal | 精细控制：slash command、快捷键、TUI 菜单导航 | **控制层（Control）**：composer 内的命令面板 + 键盘条，以及一键全屏的终端控制模式；不是并列 tab |

核心决定：**pane agent 也渲染成 ACP 风格时间线**。数据来自 UHP（`agent.read` 转录 + 状态变化 + 我发出的 prompt/keys），由 shared 层组装成统一的 `TimelineItem`。ACP agent 天然就是这个形状。用户不再需要知道 "Pane" 和 "ACP" 的区别——只剩一个小类型角标。

## 3. 统一概念：Thread

```
Host ─┬─ Project（原 Workspace，按 workspace_id / cwd）
      │    ├─ Thread × N   ← pane agent 或 ACP session，同一种东西
      │    ├─ Changes      ← diff + review notes
      │    └─ Tasks
      └─ Host 设置 / 工具
```

**Thread 时间线项（shared `TimelineItem`）：**

| Item | pane agent 来源 | ACP 来源 |
|---|---|---|
| `Output`（agent 文本块） | `agent.read` 转录按新增后缀切块，ANSI 去色保留结构 | `Message` |
| `Mine`（我发的） | 本机发出的 Agent prompt / keys（本地记录） | user message |
| `Thought` | — | thought（默认折叠） |
| `Tool` | —（不从转录猜） | tool call |
| `Plan` | — | plan |
| `Ask`（需要我决定） | status = Blocked，附最后一段问题 | permission request |
| `Status` 分隔线 | Working → Idle / Done 等状态迁移 | turn 结束 / exited |
| `Unconfirmed` | 结果没回来的发送 | 同 |

原则：**不伪造结构**。pane agent 没有 tool/plan 就不显示，时间线只是更"朴素"，但形状、交互、Ask 卡片完全一致。

## 4. 信息架构

两个 tab，没有 More。

```
┌ Now ─────────────┐   ┌ Projects ────────┐
│ 跨 Host 的注意力流 │   │ Host → Project   │
└──────────────────┘   └──────────────────┘
         右上角头像 → Hosts & 设置（配对、Role、Push、断开、取消配对）
```

### 4.1 Now（首页）

回答"现在有什么要我管"。按紧急度排序，**按 Host 分组**（组头显示 Host 名 + 连接点），保留 Host 作为信任边界的可见性，但不强迫用户逐个点进 Host。

> 这推翻 `redesign.md` 的"Host 列表就是 inbox"。理由：用户的任务是"谁卡住了"，不是"哪台机器在线"；分组头已经保留了边界信息，Role 按行生效（Observer 行的 Ask 卡禁用）。

```
Now                                   (◉)
──────────────────────────────────────────
需要你  2
  studio-mac ●
  ┃ claude · luvus          要不要运行迁移？
  ┃ [ 是 ]  [ 否 ]  [ 打开 ]            2m
  ┃ codex · luvia     允许写入 build.gradle.kts
  ┃ [ 允许一次 ] [ 拒绝 ]                 5m
进行中  3
  studio-mac ●   claude · web     正在测试…
  nas ◌ stale    gemini · infra   编译中…
刚完成  1
  studio-mac ●   codex · luvia  ✓ 12 files changed →审阅
```

- **Ask 卡片直接在 Now 上作答**：一次点击解决 Blocked，这是产品的高光时刻（haptic + 卡片收起）。
- "刚完成"带改动数，点进直接到该 Project 的 Changes。
- 全部安静时：一行衬线大字"一切顺利"，下方列出 Hosts 状态。
- 无 Host：配对引导（现有三步）。

### 4.2 Projects

```
Projects
  studio-mac ●  Controller
    luvus      3 threads · 12 changes
    luvia      1 thread  · 2 changes
  nas ◌ stale  Observer
    infra      1 thread
  [+ 新 Thread]
```

**Project 页**：顶部 `Threads | Changes | Tasks` 分段。
- Threads：该项目的 thread 列表 + 可恢复会话（折叠区）+ "新 Thread"（选 agent → ACP launch，cwd 预填项目路径）。
- Changes：原 Review（文件列表 → diff → 行内 note → 发送给某个 thread）。"发送 notes"时选择目标 thread，而不是隐式的当前 agent。
- Tasks：项目过滤后的任务。
- 右上角 `⋯`：Files、Search、Worktrees（项目级工具，低频）。

`workspace.focus` 作为 Changes 的定位器仍在 shared 层发生，UI 永远不出现"焦点工作区"概念。

### 4.3 Thread 页

```
‹ luvus        claude · Working ●      ⋯
──────────────────────────────────────────
  ▸ 计划 (3/5)
  ─ 14:02 开始 ─
  已读取 src/app/dispatch.rs，准备…
                         ┌──────────────┐
                         │ 先别动 CLI 部分 │   ← Mine
                         └──────────────┘
  ▸ 思考
  ⚙ edit src/app/dispatch.rs  +12 −3
  ─ Blocked ─
┌────────────────────────────────────────┐
│ 要不要运行 cargo test --locked？          │
│ [ 是 ]   [ 否 ]   Enter  Esc             │
└────────────────────────────────────────┘
[ Esc ][ Tab ][ ⇧Tab ][ ^C ][ ↑ ][ ↓ ][ ⏎ ]   ← 键盘条（聚焦 composer 时）
[ /com                                ⏎ ]
  ┌ /compact   压缩上下文                ┐  ← 命令面板
  │ /clear     清空会话                  │
  └ /model     切换模型   ↗ 需要终端      ┘
                                    [ ▦ ]  ← 终端控制
```

Terminal 的职责是**精细控制**，分两层，由轻到重：

1. **Composer 控制层（默认、无需开终端）**
   - **命令面板**：输入 `/` 弹出当前 agent 的 slash command 列表，可搜索。来源：ACP 用 agent 上报的 available commands；pane agent 用按 agent 类型内置的目录（claude / codex / gemini），允许手输任意 `/xxx`。选中后作为一次 Agent prompt 原子发送（pane）或 ACP prompt 发送。
   - **键盘条**：聚焦 composer 时浮在键盘上方，Esc、Tab、⇧Tab（切换模式）、^C、↑↓、⏎。点击即发 Agent keys，不进输入框；pane agent 专属，ACP thread 只保留"取消本轮"。
   - 目录中标注 `↗ 需要终端` 的命令（会弹出交互式菜单，如 `/model`、`/resume`）：发送后自动打开终端控制模式，避免在时间线里盲操作。
2. **终端控制模式（全屏）**
   - composer 旁 `▦` 打开：深色全屏终端，默认 observe；Controller 可一键 take control，得到完整键盘条（含方向键、^ 组合、Fn）与原生软键盘直输，用来走 TUI 菜单、选项列表、vim 式交互。
   - 顶部显示控制状态（Observing / Controlling / 被他人占用）；关闭即释放 control 并停止 observe（ADR 0001 不变）。
   - ACP thread 没有终端，交互式命令由 ACP 自身的 permission / 选项承担。
- Observer：命令面板可浏览但发送禁用；终端只能 observe。
- `⋯`：重命名、Fork、结束（ACP）、取消本轮（ACP）、复制 cwd。
- 顶部项目名可点 → 该 Project 的 Changes。

### 4.4 Hosts & 设置（头像入口）

Host 列表、配对、每个 Host 的 Role / Push / 编辑连接 / 断开 / 取消配对、Host 级工具（Automations、Layout）。Layout 只作为"打开无 agent 的 shell pane"的选择器存在。

### 4.5 删除 / 降级清单

| 现状 | v2 |
|---|---|
| More tab | 删除；工具进入 Project `⋯` 或 Host 设置 |
| `Transcript | Terminal` 分段 | 删除；时间线 + composer 控制层 + 终端控制模式 |
| 终端内 key chips 行 | 键盘条上移到 composer；终端模式内为完整版 |
| Agents tab 顶部 Mission 卡 | 并入 Now 分组计数 |
| Workspace tab 的项目选择器 | 删除；项目是导航层级而非过滤器 |
| Automations 作为一级入口 | 降为 Host 设置里的一项 |
| Pane / ACP 两种对话 UI | 一种 |

## 5. 视觉语言

保留 Warm Minimal 的色值，改掉"杂"的来源：衬线、Material 组件、等宽、raw 颜色混用。

**规则**

1. **一个强调色**：`accent` 只用于"需要你"（Ask 卡、Blocked 标记、主按钮）。Working 用中性动效（呼吸点），不再用蓝色抢注意力。
2. **状态 = 形状 + 颜色**：`●` 活跃、`◌` stale、`○` 离线、`┃` 左侧色条表示需要你。色盲可辨。
3. **字体三档**：衬线只在 Now 空状态与配对标题；UI 全部系统无衬线；等宽只在终端控制模式、slash command、diff hunk、路径、命令。**时间线里的 agent 文本用无衬线正文**，代码块才等宽——这是和"终端味"决裂的关键。
4. **层级靠留白不靠卡片**：列表去掉每行卡片底，用 24pt 分组间距 + 细分隔线；只有 Ask 卡片和 Mine 气泡有底色。
5. **一种圆角**：卡片 16，按钮/chip 全胶囊。
6. **深色只属于终端**：终端控制模式与代码块用 `terminalBg`，其余跟随系统明暗。
7. **动效**：Ask 卡作答后 200ms 收起 + 成功 haptic；新 Output 块淡入；不做其它装饰动效。
8. 平台：iOS Liquid Glass 只用于 tab bar、composer、键盘条；Android Material 3 组件，但颜色角色一律来自 `LuviaTheme.extended`（`agentBlocked`/`link*`/`diff*`），不跟壁纸。

**组件表（两端同名）**：`AttentionCard`(Ask)、`ThreadRow`、`TimelineItemView`、`HostGroupHeader`、`StatusGlyph`、`Composer`、`CommandPalette`、`KeyBar`、`TerminalControl`、`ProjectRow`、`EmptyState`、`UnconfirmedBanner`。删除 `MissionStrip`、`AttentionBanner`、各处私有 `StatusPill`。

## 6. shared 层改动

- `Thread`（替代 `redesign.md` 的 `AgentEntry`）：`id`、`kind: Pane|Acp`、`hostId`、`projectKey`、`title`、`status`、`ask: Ask?`、`summary`、`updatedEpochMs`。
- `TimelineItem` sealed class（见 §3），每个 thread 一个有界环形缓冲（如 500 项）。pane agent 的 `Output` 由转录新增后缀切块，`Mine` 由本地发送记录插入；不从转录推断 tool/plan。
- `NowState`：跨 `HostManager` 所有 Host 聚合 `needsYou / inProgress / recentlyDone`；只读已缓存快照，不为 Now 额外发 `agent.read`（Blocked 问题文本来自已打开过的转录缓存，没有就显示"等待你的决定"）。
- `Ask` 统一：`options: List<AskOption>`，pane 映射为 是/否(prompt `y`/`n`)+Enter/Esc(keys)，ACP 映射 permission options。发送一律走现有不重试路径，失败进 `Unconfirmed`。
- `SlashCommand(name, description, needsTerminal)`：`Thread.commands()` 返回 ACP available commands 或按 agent 类型的内置目录；目录是 shared 的静态数据，两端共用。
- `KeyBarKey` 枚举映射到 Agent keys 序列（Esc、Tab、⇧Tab、^C、↑↓、⏎），pane 与终端控制模式共用。
- `HostSection` 保留作为加载单元；UI 不再 1:1 映射。

## 7. 落地顺序

1. **shared**：`Thread`、`TimelineItem`、`NowState`、`Ask` + 单测（pane 转录切块、ACP 映射、Unconfirmed）。
2. **Thread 页**两端统一（最大体验收益；ACP 屏幕改造成通用时间线，pane 复用）。
3. **Now** 替代 Hosts 首页；Hosts 移入头像。
4. **Projects** 替代 Workspace + More。
5. 视觉清扫：删除私有组件与 raw 颜色，按 §5 规则走一遍所有屏。

每步可独立发版；第 2 步之后旧 IA 仍可用。

## 8. 已定决策

- Now 的"刚完成"：本机查看过或超过 24h 即移出。
- Now 只给选项作答；自由文本与 slash command 一律进 Thread，降低误发。
- 多 Host 同名项目不合并展示，Host 是信任边界。
