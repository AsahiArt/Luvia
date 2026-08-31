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
}
