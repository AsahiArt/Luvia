package tech.asahiart.luvia

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tech.asahiart.luvia.internal.NdjsonFramer
import tech.asahiart.luvia.internal.UhpRequest
import tech.asahiart.luvia.internal.decodeUhpRequest
import tech.asahiart.luvia.internal.parseObject
import tech.asahiart.luvia.support.ScriptedFactory

class AgentFilesSearchTest {
    @Test
    fun encodesExactRequestParams() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openAgentFiles(backgroundScope) { seen += it }

        session.reportAgent(
            pane = "7",
            source = "fx/plugin",
            agent = "fx",
            status = AgentStatus.Blocked,
            message = "approval required",
            sessionId = "sess_abc",
            sequence = 12L,
            ttlSeconds = 300,
        )
        session.releaseAgent("7", "fx/plugin")
        session.waitForAgent("7", status = AgentStatus.Blocked, timeoutSeconds = 12)
        session.waitForAgent(
            "7",
            statuses = listOf(AgentStatus.Working, AgentStatus.Blocked),
            timeoutSeconds = 30,
        )
        session.nameAgent(pane = "7", name = "reviewer")
        session.nameAgent(pane = "7", clear = true)
        session.forkAgent("reviewer", name = "experiment", focus = false)
        session.resumeAgent("sess_abc")
        session.fileTree()
        session.openFile("/work/api-service/src/main.kt", FileOpenTarget.TAB)
        session.revealFile("/work/api-service/src/main.kt")
        session.refreshFiles()
        session.searchCapabilities()
        session.querySearch(
            query = "api auth",
            scope = SearchScope.ALL,
            caseSensitive = false,
            allSessions = true,
            limit = 50,
        )
        session.activateSearch(SearchKind.PANE, SearchTarget(pane = "7"))
        session.gitBranches(workspace = 0)
        session.gitOpen(workspace = 1)

        fun params(method: String): List<JsonObject> = seen.filter { it.method == method }.map { it.params }

        val report = params("agent.report").single()
        assertEquals(
            setOf("pane", "source", "agent", "status", "message", "session_id", "sequence", "ttl_s"),
            report.keys,
        )
        assertEquals("7", report.getValue("pane").jsonPrimitive.content)
        assertEquals("fx/plugin", report.getValue("source").jsonPrimitive.content)
        assertEquals("fx", report.getValue("agent").jsonPrimitive.content)
        assertEquals("blocked", report.getValue("status").jsonPrimitive.content)
        assertEquals("approval required", report.getValue("message").jsonPrimitive.content)
        assertEquals("sess_abc", report.getValue("session_id").jsonPrimitive.content)
        assertEquals("12", report.getValue("sequence").jsonPrimitive.content)
        assertEquals("300", report.getValue("ttl_s").jsonPrimitive.content)

        val release = params("agent.release").single()
        assertEquals(setOf("pane", "source"), release.keys)
        assertEquals("7", release.getValue("pane").jsonPrimitive.content)
        assertEquals("fx/plugin", release.getValue("source").jsonPrimitive.content)

        val waits = params("agent.wait")
        assertEquals(2, waits.size)
        assertEquals(setOf("pane", "status", "timeout_s"), waits[0].keys)
        assertEquals("7", waits[0].getValue("pane").jsonPrimitive.content)
        assertEquals("blocked", waits[0].getValue("status").jsonPrimitive.content)
        assertEquals("12", waits[0].getValue("timeout_s").jsonPrimitive.content)
        assertFalse("statuses" in waits[0])
        assertEquals(setOf("pane", "statuses", "timeout_s"), waits[1].keys)
        assertEquals(
            listOf("working", "blocked"),
            waits[1].getValue("statuses").jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse("status" in waits[1])

        val names = params("agent.name")
        assertEquals(setOf("pane", "name"), names[0].keys)
        assertEquals("7", names[0].getValue("pane").jsonPrimitive.content)
        assertEquals("reviewer", names[0].getValue("name").jsonPrimitive.content)
        assertFalse("clear" in names[0])
        assertEquals(setOf("pane", "clear"), names[1].keys)
        assertEquals("true", names[1].getValue("clear").jsonPrimitive.content)
        assertFalse("name" in names[1])

        val fork = params("agent.fork").single()
        assertEquals(setOf("target", "name", "focus"), fork.keys)
        assertEquals("reviewer", fork.getValue("target").jsonPrimitive.content)
        assertEquals("experiment", fork.getValue("name").jsonPrimitive.content)
        assertEquals("false", fork.getValue("focus").jsonPrimitive.content)

        val resume = params("agent.resume").single()
        assertEquals(setOf("session_id"), resume.keys)
        assertEquals("sess_abc", resume.getValue("session_id").jsonPrimitive.content)

        assertTrue(params("files.tree").single().isEmpty())
        val open = params("files.open").single()
        assertEquals(setOf("path", "target"), open.keys)
        assertEquals("/work/api-service/src/main.kt", open.getValue("path").jsonPrimitive.content)
        assertEquals("tab", open.getValue("target").jsonPrimitive.content)
        val reveal = params("files.reveal").single()
        assertEquals(setOf("path"), reveal.keys)
        assertEquals("/work/api-service/src/main.kt", reveal.getValue("path").jsonPrimitive.content)
        assertTrue(params("files.refresh").single().isEmpty())

        assertTrue(params("search.capabilities").single().isEmpty())
        val query = params("search.query").single()
        assertEquals(setOf("query", "scope", "case_sensitive", "all_sessions", "limit"), query.keys)
        assertEquals("api auth", query.getValue("query").jsonPrimitive.content)
        assertEquals("all", query.getValue("scope").jsonPrimitive.content)
        assertEquals("false", query.getValue("case_sensitive").jsonPrimitive.content)
        assertEquals("true", query.getValue("all_sessions").jsonPrimitive.content)
        assertEquals("50", query.getValue("limit").jsonPrimitive.content)
        assertFalse("search" in seen.map { it.method }.toSet())

        val activate = params("search.activate").single()
        assertEquals(setOf("kind", "target"), activate.keys)
        assertEquals("pane", activate.getValue("kind").jsonPrimitive.content)
        assertEquals(
            setOf("pane"),
            activate.getValue("target").jsonObject.keys,
        )
        assertEquals("7", activate.getValue("target").jsonObject.getValue("pane").jsonPrimitive.content)

        val branches = params("git.branches").single()
        assertEquals(setOf("workspace"), branches.keys)
        assertEquals("0", branches.getValue("workspace").jsonPrimitive.content)
        val gitOpen = params("git.open").single()
        assertEquals(setOf("workspace"), gitOpen.keys)
        assertEquals("1", gitOpen.getValue("workspace").jsonPrimitive.content)

        seen.filter { it.method != "uhp.capabilities" }.forEach { request ->
            assertFalse("if_revision" in request.params, request.method)
        }

        session.close()
    }

    @Test
    fun omitsOptionalKeys() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openAgentFiles(backgroundScope) { seen += it }

        session.reportAgent("7", "fx/plugin", "fx", AgentStatus.Working)
        session.waitForAgent("7", status = AgentStatus.Idle)
        session.nameAgent(name = "reviewer")
        session.forkAgent("7")
        session.openFile("/tmp/a.kt")
        session.querySearch("auth")
        session.gitBranches()
        session.gitOpen()

        fun params(method: String): JsonObject = seen.single { it.method == method }.params

        assertEquals(setOf("pane", "source", "agent", "status"), params("agent.report").keys)
        assertEquals(setOf("pane", "status"), params("agent.wait").keys)
        assertEquals(setOf("name"), params("agent.name").keys)
        assertEquals(setOf("target"), params("agent.fork").keys)
        assertEquals(setOf("path"), params("files.open").keys)
        assertEquals(setOf("query"), params("search.query").keys)
        assertTrue(params("git.branches").isEmpty())
        assertTrue(params("git.open").isEmpty())

        session.close()
    }

    @Test
    fun waitForAgentRejectsConflictingStatusFieldsClientSide() = runTest {
        val seen = mutableListOf<UhpRequest>()
        val session = openAgentFiles(backgroundScope) { seen += it }
        val both =
            session.waitForAgent(
                "7",
                status = AgentStatus.Idle,
                statuses = listOf(AgentStatus.Blocked),
            )
        assertIs<Failure.InvalidRequest>((both as Outcome.Err).failure)
        val missing = session.waitForAgent("7")
        assertIs<Failure.InvalidRequest>((missing as Outcome.Err).failure)
        val empty = session.waitForAgent("7", statuses = emptyList())
        assertIs<Failure.InvalidRequest>((empty as Outcome.Err).failure)
        assertTrue(seen.none { it.method == "agent.wait" })
        session.close()
    }

    @Test
    fun mapsResultFixtures() = runTest {
        val session = openAgentFiles(backgroundScope) { }

        val report = (session.reportAgent("7", "fx/plugin", "fx", AgentStatus.Blocked) as Outcome.Ok).value
        assertEquals("7", report.pane)
        assertEquals("fx", report.agent)
        assertEquals(AgentStatus.Blocked, report.status)
        assertEquals("fx/plugin", report.source)
        assertEquals(12L, report.sequence)
        assertEquals(300L, report.ttlSeconds)

        val release = (session.releaseAgent("7", "fx/plugin") as Outcome.Ok).value
        assertEquals("7", release.pane)

        val wait = (session.waitForAgent("7", status = AgentStatus.Blocked) as Outcome.Ok).value
        assertTrue(wait.matched)
        assertEquals("7", wait.pane)
        assertEquals(AgentStatus.Blocked, wait.status)

        val named = (session.nameAgent(pane = "7", name = "reviewer") as Outcome.Ok).value
        assertEquals("7", named.pane)
        assertEquals("reviewer", named.name)

        val forked = (session.forkAgent("reviewer") as Outcome.Ok).value
        assertEquals("7", forked.from)
        assertEquals("8", forked.pane)
        assertEquals("pi", forked.agent)
        assertEquals("experiment", forked.name)
        assertEquals("0", forked.workspace)
        assertEquals("1", forked.tab)
        assertFalse(forked.focused)

        assertIs<Outcome.Ok<Unit>>(session.resumeAgent("sess_abc"))

        val tree = (session.fileTree() as Outcome.Ok).value
        assertEquals("/work/api-service", tree.root)
        assertEquals("src", tree.rows.single().name)
        assertEquals(0, tree.rows.single().depth)
        assertTrue(tree.rows.single().dir)
        assertTrue(tree.rows.single().expanded)

        assertIs<Outcome.Ok<Unit>>(session.openFile("/tmp/a.kt"))
        assertIs<Outcome.Ok<Unit>>(session.revealFile("/tmp/a.kt"))
        assertIs<Outcome.Ok<Unit>>(session.refreshFiles())

        val caps = (session.searchCapabilities() as Outcome.Ok).value
        assertEquals(1L, caps.version)
        assertEquals(listOf("search.query", "search.activate"), caps.methods)
        assertEquals(listOf("all", "navigate", "files", "output"), caps.scopes)
        assertEquals(200L, caps.maxResults)
        assertEquals(1048576L, caps.maxResponseBytes)

        val query = (session.querySearch("api auth") as Outcome.Ok).value
        assertEquals("api auth", query.query)
        assertEquals("all", query.scope)
        assertEquals(3L, query.total)
        assertEquals(1L, query.shown)
        assertFalse(query.partial)
        val match = query.matches.single()
        assertEquals("workspace:0", match.id)
        assertEquals("folder", match.kind)
        assertEquals("api-service", match.label)
        assertEquals(4120L, match.score)
        assertEquals(0, match.target.workspace)
        assertNull(match.target.pane)

        val activated = (session.activateSearch(SearchKind.PANE, SearchTarget(pane = "7")) as Outcome.Ok).value
        assertTrue(activated.activated)

        val branches = (session.gitBranches() as Outcome.Ok).value
        assertEquals("main", branches.single().name)
        assertTrue(branches.single().head)
        assertEquals(0L, branches.single().ahead)
        assertEquals("wire uhp", branches.single().subject)

        val git = (session.gitOpen() as Outcome.Ok).value
        assertTrue(git.git)

        session.close()
    }

    @Test
    fun supportsAdvertisedMethods() = runTest {
        val session = openAgentFiles(backgroundScope) { }
        for (method in AGENT_FILES_METHODS) {
            assertTrue(session.supports(method), method)
        }
        assertFalse(session.supports("search"))
        assertFalse(session.supports("agent.send"))
        session.close()
    }
}

private suspend fun openAgentFiles(
    scope: kotlinx.coroutines.CoroutineScope,
    onRequest: (UhpRequest) -> Unit,
): LuviaSession {
    val factory =
        ScriptedFactory(scope) { framer ->
            dispatchAgentFiles(framer, onRequest)
        }
    return (LuviaClient(factory).open("default") as Outcome.Ok).value
}

private suspend fun dispatchAgentFiles(
    framer: NdjsonFramer,
    onRequest: (UhpRequest) -> Unit,
) {
    val prelude = parseObject(framer.readFrame())
    when (prelude["operation"]?.let { (it as JsonPrimitive).content }) {
        "discover" ->
            framer.writeFrame(
                """{"version":1,"sessions":[{"name":"default","default":true,"running":true,"transport":"unix_socket"}]}""",
            )
        "open" -> {
            framer.writeFrame("""{"version":1,"status":"ready","session":"default"}""")
            val request = decodeUhpRequest(framer.readFrame())
            onRequest(request)
            respondAgentFiles(framer, request)
        }
    }
}

private suspend fun respondAgentFiles(framer: NdjsonFramer, request: UhpRequest) {
    val result: JsonObject =
        when (request.method) {
            "uhp.capabilities" ->
                buildJsonObject {
                    put("type", "uhp_capabilities")
                    put(
                        "protocol",
                        buildJsonObject {
                            put("name", "luvus-uhp")
                            put("major", 1)
                            put("minor", 0)
                        },
                    )
                    put("methods", stringArray(AGENT_FILES_METHODS))
                    put("event_sequence", 10)
                }
            "agent.report" -> parseObject(AGENT_REPORT_RESULT)
            "agent.release" -> parseObject(AGENT_RELEASE_RESULT)
            "agent.wait" -> parseObject(AGENT_WAIT_RESULT)
            "agent.name" -> parseObject(AGENT_NAME_RESULT)
            "agent.fork" -> parseObject(AGENT_FORK_RESULT)
            "files.tree" -> parseObject(FILES_TREE_RESULT)
            "search.capabilities" -> parseObject(SEARCH_CAPABILITIES_RESULT)
            "search.query" -> parseObject(SEARCH_QUERY_RESULT)
            "search.activate" -> parseObject(SEARCH_ACTIVATE_RESULT)
            "git.branches" -> parseObject(GIT_BRANCHES_RESULT)
            "git.open" -> parseObject(GIT_OPEN_RESULT)
            else -> buildJsonObject { put("type", "ok") }
        }
    framer.writeFrame(
        tech.asahiart.luvia.internal.compactJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("id", request.id)
                put("result", result)
            },
        ),
    )
}

private fun stringArray(values: List<String>): JsonArray =
    buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

private val AGENT_FILES_METHODS =
    listOf(
        "uhp.capabilities",
        "agent.report",
        "agent.release",
        "agent.wait",
        "agent.name",
        "agent.fork",
        "agent.resume",
        "files.tree",
        "files.open",
        "files.reveal",
        "files.refresh",
        "search.capabilities",
        "search.query",
        "search.activate",
        "git.branches",
        "git.open",
    )

private const val AGENT_REPORT_RESULT: String =
    """{"type":"agent_report","pane":"7","agent":"fx","status":"blocked","source":"fx/plugin","sequence":12,"ttl_s":300}"""

private const val AGENT_RELEASE_RESULT: String =
    """{"type":"agent_release","pane":"7"}"""

private const val AGENT_WAIT_RESULT: String =
    """{"type":"agent_wait","matched":true,"pane":"7","status":"blocked"}"""

private const val AGENT_NAME_RESULT: String =
    """{"type":"agent_name","pane":"7","name":"reviewer"}"""

private const val AGENT_FORK_RESULT: String =
    """{"type":"agent_fork","from":"7","pane":"8","agent":"pi","name":"experiment","workspace":"0","tab":"1","focused":false}"""

private const val FILES_TREE_RESULT: String =
    """{"type":"file_tree","root":"/work/api-service","rows":[{"path":"/work/api-service/src","name":"src","depth":0,"dir":true,"expanded":true}]}"""

private const val SEARCH_CAPABILITIES_RESULT: String =
    """{"type":"search_capabilities","version":1,"methods":["search.query","search.activate"],"scopes":["all","navigate","files","output"],"max_results":200,"max_response_bytes":1048576}"""

private const val SEARCH_QUERY_RESULT: String =
    """{"type":"search_query","query":"api auth","scope":"all","total":3,"shown":1,"partial":false,"matches":[{"id":"workspace:0","kind":"folder","label":"api-service","detail":"default › /work/api-service","score":4120,"target":{"workspace":0}}]}"""

private const val SEARCH_ACTIVATE_RESULT: String =
    """{"type":"search_activation","activated":true}"""

private const val GIT_BRANCHES_RESULT: String =
    """{"type":"git_branches","branches":[{"name":"main","head":true,"ahead":0,"behind":0,"subject":"wire uhp"}]}"""

private const val GIT_OPEN_RESULT: String =
    """{"type":"ok","git":true}"""
