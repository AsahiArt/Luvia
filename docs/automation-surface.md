# Luvus Automation surface (Luvia client contract)

Status: implementing. Scope: expose the full Luvus 0.14 `automation.*` surface
in the shared Kotlin `HostUhp` board and both native UIs. Everything below the
board (session extension functions, JSON mapping) already exists in
`shared/src/commonMain/kotlin/tech/asahiart/luvia/OrchSurface.kt`; nothing on
the wire changes.

## Wire (Luvus 0.14.2, `scope = orchestration`)

| Method | Access | Params | Result |
|---|---|---|---|
| `automation.list` | read | `{}` | `{automations:[Automation], revision}` |
| `automation.get` | read | `{id}` | `{automation}` |
| `automation.create` | write | `{name, enabled?, trigger, target?, task, policy?, idempotency_key?}` | `{automation}` |
| `automation.update` | write | `{id, name, enabled?, trigger, target?, task, policy?}` (full replace) | `{automation}` |
| `automation.enable` / `.disable` | write | `{id}` | `{automation}` |
| `automation.rebind` | write | `{id, pane, terminal_id?}` | `{automation}` |
| `automation.delete` | write | `{id}` | `{automation}` |
| `automation.run` | write | `{id, idempotency_key?}` | `{run}` |
| `automation.history` | read | `{id?, limit?}` | `{runs:[AutomationRun]}` |
| `automation.preview` | read | `{trigger, from_utc?}` | `{occurrences_utc:[u64]}` |
| `automation.health` | read | `{}` | `{summary, automations:[view]}` |

Trigger is tagged by `kind`: `once{at_utc}`, `interval{every_seconds,anchor_utc}`,
`daily{timezone,second_of_day}`, `weekly{timezone,weekdays[1..7],second_of_day}`.
Target: `new_worker` (default) or `active_agent{pane_id,terminal_id,if_busy: wait|skip}`.
Task: `{title,prompt,agent_id,workspace_id,mode?,access?,paths?,gate?}`.
Policy: `{misfire: run_latest|skip|run_all, overlap: skip|queue, misfire_grace_seconds}`.
Run status: `pending|starting|running|review|delivered|succeeded|failed|skipped|cancelled`.
Target state (active_agent only): `bound|restoring|needs_rebind` -> rebind CTA.

Events (`bus.subscribe`): `automation.created|updated|enabled|disabled|rebound|deleted`
and `automation.run_queued|run_started|run_materialized|run_updated|run_finished|run_failed`.
Any of them -> `AutomationBoard.refresh()` while the Automations section is showing; run_*
also refreshes the open history list if it is for that automation.

## Shared Kotlin (`HostUhp` / `HostUhpState`)

Session-level calls (exist): `createAutomation`, `updateAutomation`, `deleteAutomation`,
`rebindAutomation`, `listAutomationHistory`, `previewAutomation`, `getAutomation`.

Board additions (`internal/uhp/OrchLayoutBoards.kt`, `AutomationBoard`):

```kotlin
// HostUhpState.automations: AutomationsState gains
public val history: Map<String, List<AutomationRun>> = emptyMap(),   // automationId -> runs
public val historyLoading: String? = null,
public val preview: List<Long>? = null,          // occurrences_utc for the draft in the editor
public val previewLoading: Boolean = false,
public val editorError: String? = null,

// HostUhp
public fun createAutomation(draft: AutomationDraft)
public fun updateAutomation(id: String, draft: AutomationDraft)
public fun deleteAutomation(id: String)
public fun rebindAutomation(id: String, pane: String, terminalId: String? = null)
public fun loadAutomationHistory(id: String, limit: Long = 20)
public fun previewAutomation(trigger: AutomationTrigger)
public fun clearAutomationPreview()

public data class AutomationDraft(
    val name: String,
    val enabled: Boolean = true,
    val trigger: AutomationTrigger,
    val target: AutomationTarget = AutomationTarget.NewWorker,
    val task: AutomationTaskSpec,
    val policy: AutomationPolicySpec? = null,
)
```

`createAutomation` passes a fresh `idempotencyKey` (uuid, <=128 bytes). Mutations honour
`canMutate` and `supports(method)` exactly like `enable/disable/run`. `workspace_id` in
the task spec defaults to `activeWorkspaceId` when the draft leaves it blank.

## UI (both platforms; aesthetic bar = existing Tasks / ACP surfaces)

- Automations list: card per automation with name, trigger summary ("Daily 09:00 Asia/Tokyo",
  "Every 30 min", "Weekly Mon/Wed 18:30", "Once Sep 18 10:00"), next run relative time,
  enabled toggle, target chip (`New worker` / `Agent pane N`, red `Needs rebind` chip with
  a Rebind action when `targetState == needs_rebind`), last run status pill.
- Swipe / context actions: Run now, Edit, History, Delete (confirm).
- Editor sheet (create + edit): name, trigger picker (segmented: Once / Interval / Daily /
  Weekly with the matching fields; weekday chips; time picker; timezone default = device),
  target picker (New worker / pick an active agent pane from `agents`), task section
  (title, prompt multi-line, agent picker from `agentCatalog`, workspace picker, mode,
  access, gate), advanced disclosure for policy. A live "Next occurrences" strip driven by
  `previewAutomation` (debounced 300 ms on trigger change).
- History sheet: runs newest first with status pill, scheduled/started/finished relative
  times, attempt, error text, and a link to the task when `taskId != null`.
- Rebind sheet: list of active agent panes; tap to rebind.
- Read-only hosts (`!canMutate`): all mutating controls hidden, not disabled.
