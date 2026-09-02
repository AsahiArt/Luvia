package tech.asahiart.luvia.support

internal object CopiedFixtures {
    val validRequests: List<String> =
        listOf(
            """{"id":"1","method":"uhp.capabilities","params":{}}""",
            """{"id":"2","method":"workspace.get","params":{"workspace":0}}""",
            """{"id":"15","method":"events.subscribe","params":{"after_sequence":41}}""",
            """{"id":"16","method":"workspace.get","auth":"luv_tok_example","params":{"workspace_id":"workspace_example"}}""",
        )

    val validResponses: List<String> =
        listOf(
            """{"id":"1","result":{"type":"uhp_capabilities","protocol":{"name":"luvus-uhp","major":1,"minor":0}}}""",
            """{"id":"2","result":{"type":"workspace","workspace":"0","name":"project"}}""",
            """{"id":"3","result":{"type":"pane_neighbor","pane":"7","neighbor":"9"}}""",
            """{"id":"4","error":{"code":"not_found","message":"pane not found"}}""",
        )

    val validEvents: List<String> =
        listOf(
            """{"event":"pane.focused","sequence":1,"data":{"pane":"7"}}""",
            """{"event":"layout.applied","sequence":2,"data":{"workspace":"0","tab":"1"}}""",
        )

    val invalidRequests: List<String> =
        listOf(
            """{"id":"1","method":"pane.neighbor","params":{},"extra":true}""",
            """{"id":"unicode-é","method":"uhp.capabilities","params":{}}""",
            """{"id":"","method":"uhp.capabilities","params":{}}""",
        )

    val validControlFrames: List<String> =
        listOf(
            """{"id":"input-1","action":"type_literal","params":{"text":"cargo test"}}""",
            """{"id":"input-2","action":"submit_text","params":{"text":"summarize this change"}}""",
            """{"id":"input-3","action":"send_key","params":{"key":"escape"}}""",
        )

    val invalidTerminalEvents: List<String> =
        listOf(
            """{"event":"terminal.frame","sequence":15,"data":{"server_generation":"11111111111111111111111111111111","terminal_id":"22222222222222222222222222222222","pane_id":"7","content_revision":5,"mode":"visible","ansi":true,"text":"ready","lines":1,"bytes":4,"truncated":false}}""",
            """{"event":"terminal.frame","sequence":15,"data":{"server_generation":"11111111111111111111111111111111","terminal_id":"22222222222222222222222222222222","pane_id":"7","content_revision":5,"mode":"visible","ansi":true,"text":"first\nsecond","lines":1,"bytes":12,"truncated":false}}""",
        )

    const val VALID_TERMINAL_FRAME: String =
        """{"event":"terminal.frame","sequence":15,"data":{"server_generation":"11111111111111111111111111111111","terminal_id":"22222222222222222222222222222222","pane_id":"7","content_revision":5,"mode":"visible","ansi":true,"text":"ready","lines":1,"bytes":5,"truncated":false}}"""

    /** dispatch.rs:2603 agent.read result */
    const val AGENT_READ_RESULT: String =
        """{"type":"agent_read","pane":"7","text":"hello from agent"}"""

    /** dispatch.rs:543-554 agent_prompt_response; evidence timeout still submitted */
    const val AGENT_PROMPT_TIMEOUT_RESULT: String =
        """{"type":"agent_prompt","pane":"7","submitted":true,"matched":false,"status":"working","baseline_revision":11,"content_revision":14,"evidence":"timeout"}"""

    /** dispatch.rs:2847 agent.sessions */
    const val AGENT_SESSIONS_RESULT: String =
        """{"type":"session_list","sessions":[{"agent":"pi","session_id":"sess_abc","cwd":"/tmp/work"}]}"""

    /** src/app/mission.rs:93-106 mission_snapshot_value */
    const val MISSION_SNAPSHOT_RESULT: String =
        """{"type":"mission_snapshot","scope":"all","workspace":"0","workspace_id":"workspace_example","refreshing":false,"summary":{"agents":1,"tokens":42,"cost_usd":0.25,"burn_usd_per_hour":1.5},"rows":[{"kind":"live","pane":"7","agent":"pi","state":"working","workspace":"0","workspace_id":"workspace_example","workspace_name":"work","tab":"1","location":"/tmp/work","usage":{"model":"pi","tokens_in":10,"tokens_out":32,"cache_tokens":0,"total_tokens":42,"context":0.2,"cost_usd":0.25}}]}"""

    /** dispatch.rs:3420-3429 diff.list */
    const val DIFF_LIST_RESULT: String =
        """{"type":"diff_list","repo":"/tmp/repo","branch":"main","generation":3,"fingerprint":"fp1","omitted":0,"refreshing":false,"files":[{"path":"file.txt","path_raw_hex":null,"old_path":null,"old_path_raw_hex":null,"layer":"worktree","status":"M","additions":1,"deletions":1,"binary":false,"notes":0,"viewed":false,"modified_since_review":false,"fingerprint":"ff"}]}"""

    /** dispatch.rs:3524-3533 + model.rs:634-647 diff.get hunk/line shape */
    const val DIFF_GET_RESULT: String =
        """{"type":"diff","file":{"path":"file.txt","layer":"worktree","status":"M","additions":1,"deletions":1,"binary":false,"notes":0,"viewed":false,"modified_since_review":false,"fingerprint":"ff"},"additions":1,"deletions":1,"binary":false,"truncated":false,"omitted_lines":0,"hunks":[{"id":"h1","old_start":1,"new_start":1,"header":"@@ -1,1 +1,1 @@","lines":[{"kind":"deletion","old_line":1,"new_line":null,"text":"old"},{"kind":"addition","old_line":null,"new_line":1,"text":"new"}]}]}"""

    /** dispatch.rs:5713-5730 note_json */
    const val DIFF_NOTE_RESULT: String =
        """{"type":"diff_note","note":{"id":"note1","review":"rev1","author":"external","kind":"issue","body":"check this","state":"open","path":"file.txt","layer":"worktree","side":"new","start_line":1,"end_line":1,"revision":1,"deliveries":[],"created_at_ms":1,"updated_at_ms":1}}"""

    /** dispatch.rs:3583 diff.note.list */
    const val DIFF_NOTE_LIST_RESULT: String =
        """{"type":"diff_notes","notes":[{"id":"note1","review":"rev1","author":"external","kind":"issue","body":"check this","state":"open","path":"file.txt","layer":"worktree","side":"new","start_line":1,"end_line":1,"revision":1,"deliveries":[],"created_at_ms":1,"updated_at_ms":1}]}"""

    /** dispatch.rs:3805-3806 diff.note.send */
    const val DIFF_NOTE_SEND_RESULT: String =
        """{"type":"diff_note_send","pane":"7","target":"pi","count":1}"""

    /** dispatch.rs:3818-3823 git.status */
    const val GIT_STATUS_RESULT: String =
        """{"type":"git_status","branch":"main","upstream":"origin/main","ahead":1,"behind":0,"staged":[{"code":"M","path":"a.kt"}],"unstaged":[{"code":"M","path":"b.kt"}],"untracked":["c.kt"],"stashes":["WIP"]}"""

    /** dispatch.rs:3842 git.log */
    const val GIT_LOG_RESULT: String =
        """{"type":"git_log","commits":[{"sha":"abc1234","subject":"wire uhp","author":"misaka","when":"2 hours ago","refs":"HEAD -> main"}]}"""

    /** orch/mod.rs:99-137 task with mode + workspace_worker */
    const val TASK_WITH_WORKER: String =
        """{"id":"t1","title":"wire","status":"merging","assignee":13,"deps":[],"paths":[],"gate":null,"outputs":[],"notes":[],"worktree":null,"branch":null,"mode":"workspace","workspace_worker":{"workspace_id":"workspace-a","tab_id":"1","root":"/tmp/work"},"context":null,"created":1,"updated":2}"""
}
