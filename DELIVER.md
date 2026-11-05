# ACP 历史

薄 bridge。手机只看到 `luvia.acp.session.list` 和扩过的 `luvia.acp.session.open`（`mode=new|resume`，`replay`）。不解析各家 jsonl，不加 Luvus hook。

## 做了什么

- Host：`session.list` 是短生命周期 spawn → `initialize`（要约 protocol 2）→ ACP `session/list` → 杀进程。
- `open` 仍接受旧的 `{agent,cwd}`（`mode` 默认 `new`）。`resume` + `replay=true`：v1 走 `session/load`，v2 走 `session/resume` + `replayFrom:{type:start}`。`replay=false` 只 `session/resume`。回放的 `session/update` 在 ack 之后仍压成 `acp.update`。
- 能力：有任一 ACP 命令在 PATH 上时宣告 `luvia.acp.session.list`（read）。某个 agent 的 initialize 没有 list / load / resume 时，该次调用返回 `acp_unsupported`，不发对应 ACP 方法。
- Catalog 钉：`claude`→`claude-code-acp`，`codex`→`codex-acp`，`agy`→`agy acp`，`pi`→`pi-acp`。保留 `gemini`。
- 会话列表一个入口。去重键是规范化 agent + cwd（去掉末尾 `/`）。pane 的 `claude-code` 与 ACP 的 `claude` 视为同一 agent。同键时 pane 留下，ACP 历史行丢掉（两边 session id 不作比较）。没有 pane 时，同一目录的多条 ACP 会话都留。已打开的 ACP session id 不重复。点历史行：`open(mode=resume, replay=true)`，开始时 `transcript=[]`。
- 主机没宣告 `luvia.acp.session.list`，或某个 agent 返回 `acp_unsupported`：不给那个来源插行（隐藏，不另做灰行）。

## 测了什么

- `cargo test -p luvia-host --lib`：131 passed。假 stdio 覆盖 list（v1/v2）、无 list → `acp_unsupported`、v1 `session/load` 回放、v2 `replayFrom.start`、无回放的 `session/resume`。
- `./gradlew :shared:jvmTest`：283 tests，0 failures。含 list 解码、resume 参数、`startingReplay()` 清 transcript、agent+cwd 去重。
- `./gradlew :androidApp:compileDebugKotlin`：通过。
- `cargo clippy -p luvia-host --all-targets -- -D warnings`：通过。`cargo fmt --all --check`：通过。

## 已知缺口

- 本机 `pi-acp` 0.0.34 有 list：`initialize` 回到 protocol 1，`sessionCapabilities.list` 是 `{}`（空对象也算有能力）。直接 `session/list` 能返回会话。`loadSession: true`，所以回放走 v1 `session/load`。
- 本机 `agy` 1.0.0（`~/.local/bin/agy`）还不会说 ACP。`agy help acp` 是 `unknown subcommand: acp`；直接跑 `agy acp` 去开 TTY，stdout 是 `bubbletea: could not open TTY`，没有 JSON-RPC。Catalog 仍只钉官方 `agy acp`，不改走社区 `agy-acp`，也不读磁盘历史。这条路径在 initialize 之前就结束，桥返回 `acp_error`，不是 `acp_unsupported`（后者要等 initialize 明确说没有 list）。
- 没实机跑 claude-code-acp / codex-acp。没有 list/load/resume 的适配器走已测的 `acp_unsupported`。
- `uhp.capabilities` 不逐个 spawn 去探 list；方法在「至少有一个 ACP 命令」时宣告，真正的 list 能力在调用时判定。
- 同一 agent+cwd 已有 pane 时，该目录下更早的 ACP 会话也会被 pane 挡住。这是定稿的去重，不是按 session id。
- list 分页在手机上最多跟 8 页 `next_cursor`。
