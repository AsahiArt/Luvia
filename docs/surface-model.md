# Surface model

Status: accepted (2026-09-18)

Phone IA follows UHP objects, not UHP namespaces. A Host tab per method
family is wrong. Terminal is not a Host tab.

## Object tree

```
Host (Luvus named session / luvia-host Grant)
  └── Workspace (`workspace_id`)
        └── Tab
              └── Pane
                    ├── Agent (identity overlay on that pane)
                    └── Terminal (`server_generation` + `terminal_id` + `pane_id`)
```

ACP sessions are Host-owned processes. They are not panes.

## Host chrome (one per paired Host)

Primary tabs:

| Surface | UHP | Why Host-level |
|---|---|---|
| Agents | `agent.list` | Fleet. Each row is a pane. Opening a row enters pane scope. |
| Automations | `automation.*` | Host ledger. `task.workspace_id` is a field of the job, not the screen. |

Also Host-owned, not tabs: connection, Role, Mission (`scope=all`), ACP launch, push, settings.

## Workspace scope (selected Agent's `workspace_id`)

These stay reachable from Host chrome so a project can be reviewed without
keeping Agent detail open. They must display and mutate that Agent's project,
not whichever workspace Luvus currently has focused.

| Surface | UHP | Locator |
|---|---|---|
| Review | `diff.*` `git.*` | Project checkout |
| Tasks | `task.*` | 0.14 ledger is project-owned; mutations send `workspace_id` |
| Files | `files.tree/open/reveal` | cwd of that workspace |
| Search | `search.query` | same |
| Worktrees | `worktree.*` | that repo |

`files.tree` still has no `workspace_id` on the wire. Review and Tasks follow
the selected project:

1. Tasks: `task.list` is a Host dump; the phone keeps rows whose
   `project.workspace_id` matches. Mutations already send `workspace_id`.
2. Review: `diff.list` reads the Host's active DIFF snapshot. Loading Review
   calls `workspace.focus` for that project's index, then `diff.refresh`.
   This is a locator, not a Layout surface. Observers skip the focus.
3. Files/Search stay labeled until `files.tree` grows a workspace param.

## Pane / Agent scope (Agent detail, never a Host tab)

| Surface | UHP |
|---|---|
| Transcript | `agent.read` `target=pane` |
| Prompt / Keys | `agent.prompt` / `agent.keys` |
| Terminal observe / control | `terminal.backend.observe/control` on that pane's identity |
| Resume / Fork / Name | `agent.resume/fork/name` |

A shell pane with no Agent is opened from Layout, still as pane detail.

## Not Host chrome

| Surface | Placement |
|---|---|
| Terminal | Agent detail (Transcript \| Terminal). Bound to that `paneId`. |
| Layout | More. TUI geometry; phone needs it only to pick a pane. |
| Files, Search, Worktrees | More. Workspace-scoped; not a fourth primary tab. |

## Tab bars

```
Host:    Agents | Review | Tasks | Automations
More:    Files, Search, Worktrees, Layout
Agent:   Transcript | Terminal
```

## Binding

1. Open Agent → stamp that project's `workspace_id`, `agent.read` that pane,
   observe its terminal.
2. Review / Tasks use: picker, else last opened Agent, else the only
   `workspace_id` on the Host. Multiple projects and no choice → empty
   ("Select a project"). Never TUI `focused`.
3. Agent detail project name jumps to Review with that workspace already set.
4. Closing Agent detail does not reset Host tabs; Terminal observe may keep
   the last pane. `HostSection.Terminal` is not a visible section.


## Out of scope

Herdr / tmux / zellij backends keep this object tree. They may omit Agent
identity; Terminal then is still pane detail, not a Host tab.
