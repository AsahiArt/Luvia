package tech.asahiart.luvia

import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath

@OptIn(ExperimentalCoroutinesApi::class)
class CandidateRaceTest {
    @Test
    fun dedupesCandidatesInRaceOrder() {
        val profile =
            raceProfile(
                name = "Yui",
                lastGood = "192.168.1.5",
                hints =
                    listOf(
                        "192.168.1.5",
                        "10.0.0.8",
                        "100.64.0.2",
                        "Yui.local",
                        "yui.java-tiyanki.ts.net",
                        "fd7a:115c:a1e0::9",
                        "10.0.0.8",
                    ),
                fqdn = "yui.java-tiyanki.ts.net",
            )
        assertEquals(
            listOf(
                "192.168.1.5",
                "Yui.local",
                "10.0.0.8",
                "yui.java-tiyanki.ts.net",
                "100.64.0.2",
                "fd7a:115c:a1e0::9",
            ),
            candidateAddresses(profile),
        )
    }

    @Test
    fun fastLanFailureLetsTailnetWinAndCancelsTheRest() = runTest {
        val cancelled = ArrayList<String>()
        val lines = ArrayList<String>()
        val result =
            raceCandidates(
                candidateAddresses(
                    raceProfile(
                        hints = listOf("192.168.1.8", "100.89.1.2"),
                        fqdn = "yui.java-tiyanki.ts.net",
                    ),
                ),
                dial =
                    scriptDial(
                        tcp = { address ->
                            if (address == "192.168.1.8") {
                                fail(Failure.Transport("connect timeout"))
                            } else {
                                ok(Unit)
                            }
                        },
                        prove = { address ->
                            when (address) {
                                "yui.java-tiyanki.ts.net" -> {
                                    ok(address)
                                }
                                else -> hangUntilCancelled(address, cancelled)
                            }
                        },
                    ),
                log = { lines += it },
            )
        advanceUntilIdle()
        val win = result as Outcome.Ok
        assertEquals("yui.java-tiyanki.ts.net", win.value.first)
        assertTrue(cancelled.contains("Yui.local"), "loser must be cancelled, got $cancelled")
        assertTrue(lines.any { it == "winner yui.java-tiyanki.ts.net" })
        assertTrue(lines.any { it.contains("192.168.1.8") && it.contains("connect timeout") })
    }

    @Test
    fun startsCandidatesAtStaggerIntervals() = runTest {
        val starts = ArrayList<Pair<String, Long>>()
        val addresses = listOf("a.local", "192.168.1.1", "host.ts.net")
        val job =
            launch {
                raceCandidates(
                    addresses,
                    scriptDial(
                        dns = { address ->
                            starts += address to this@runTest.currentTime
                            hangUntilCancelled(address, ArrayList())
                        },
                    ),
                )
            }
        runCurrent()
        assertEquals(listOf("a.local" to 0L), starts)
        advanceTimeBy(CANDIDATE_STAGGER_MS)
        runCurrent()
        assertEquals(listOf("a.local" to 0L, "192.168.1.1" to 250L), starts)
        advanceTimeBy(CANDIDATE_STAGGER_MS)
        runCurrent()
        assertEquals(
            listOf("a.local" to 0L, "192.168.1.1" to 250L, "host.ts.net" to 500L),
            starts,
        )
        job.cancel()
    }

    @Test
    fun dnsTcpAndProveTimeoutsFireOnVirtualTime() = runTest {
        assertTimeout(
            DNS_TIMEOUT_MS,
            scriptDial(dns = { hangUntilCancelled(it, ArrayList()) }),
            "dns timeout",
        )
        assertTimeout(
            TCP_TIMEOUT_MS,
            scriptDial(tcp = { hangUntilCancelled(it, ArrayList()) }),
            "connect timeout",
        )
        assertTimeout(
            PROVE_BUDGET_MS,
            scriptDial(prove = { hangUntilCancelled(it, ArrayList()) }),
            "candidate timeout",
        )
    }

    @Test
    fun anyClosedBeforeBannerRefusesWhenEveryCandidateFails() = runTest {
        val result =
            raceCandidates(
                listOf("192.168.1.8", "yui.java-tiyanki.ts.net"),
                scriptDial(
                    prove = { address ->
                        if (address == "192.168.1.8") {
                            fail(Failure.ClosedBeforeBanner())
                        } else {
                            fail(Failure.Transport("connect timeout"))
                        }
                    },
                ),
            )
        advanceUntilIdle()
        val err = assertIs<Outcome.Err<Pair<String, String>>>(result)
        assertIs<Failure.ClosedBeforeBanner>(err.failure)
        assertEquals("The host refused the connection.", err.failure.connectMessage())
        assertEquals("主机拒绝了连接", err.failure.refusedOutcomeZh())
        assertFalse(err.failure.connectMessage().contains("No address reachable"))
        assertFalse(err.failure.refusedOutcomeZh().contains("没有地址可达"))
        assertFalse(err.failure.refusedOutcomeZh().contains("没有能连上的地址"))
    }

    @Test
    fun deletedGrantDoesNotWinTheRace() = runTest {
        val result =
            raceCandidates(
                listOf("192.168.1.9", "100.89.1.2"),
                scriptDial(
                    prove = { address ->
                        if (address == "192.168.1.9") {
                            fail(Failure.ProveRejected("unknown_device", "no pairing grant for host-1"))
                        } else {
                            ok(address)
                        }
                    },
                ),
            )
        advanceUntilIdle()
        val win = assertIs<Outcome.Ok<Pair<String, String>>>(result)
        assertEquals("100.89.1.2", win.value.first)
        assertFalse(win.value.first == "192.168.1.9")
    }

    @Test
    fun hostKeyMismatchAbortsACandidateThatWouldSucceed() = runTest {
        val cancelled = ArrayList<String>()
        val result =
            raceCandidates(
                candidateAddresses(
                    raceProfile(
                        lastGood = "192.168.1.8",
                        hints = listOf("192.168.1.8"),
                        fqdn = "yui.java-tiyanki.ts.net",
                    ),
                ),
                scriptDial(
                    prove = { address ->
                        if (address == "192.168.1.8") {
                            delay(400)
                            fail(Failure.HostKeyMismatch("SHA256:expected", "SHA256:actual"))
                        } else {
                            hangUntilCancelled(address, cancelled)
                        }
                    },
                ),
            )
        advanceUntilIdle()
        val err = result as Outcome.Err
        val mismatch = assertIs<Failure.HostKeyMismatch>(err.failure)
        assertEquals("SHA256:expected", mismatch.expected)
        assertEquals("SHA256:actual", mismatch.actual)
        assertTrue(cancelled.contains("Yui.local"))
        assertTrue(!cancelled.contains("192.168.1.8"))
    }

    @Test
    fun winnerIsRememberedAndTriedFirstNextTime() = runTest {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "luvia-race-${Random.nextLong()}.json"
        val store = HostStore(path.toString(), backgroundScope)
        val profile =
            raceProfile(
                hints = listOf("192.168.1.8", "100.89.1.2"),
                fqdn = "yui.java-tiyanki.ts.net",
            )
        store.upsert(profile)
        val first =
            raceCandidates(
                candidateAddresses(profile),
                scriptDial(
                    tcp = { address ->
                        if (address.endsWith(".local") || address.startsWith("192.168.")) {
                            fail(Failure.Transport("no route"))
                        } else {
                            ok(Unit)
                        }
                    },
                    prove = { address ->
                        if (address == "yui.java-tiyanki.ts.net") ok(address) else hangUntilCancelled(address, ArrayList())
                    },
                ),
            )
        advanceUntilIdle()
        val winner = (first as Outcome.Ok).value.first
        store.setLastConnectedAddress(profile.id, winner)
        val stored = store.current().hosts.single()
        assertEquals(winner, stored.lastGood)
        assertEquals(winner, candidateAddresses(stored).first())

        val secondStarts = ArrayList<String>()
        val job =
            launch {
                raceCandidates(
                    candidateAddresses(stored),
                    scriptDial(
                        dns = { address ->
                            secondStarts += address
                            hangUntilCancelled(address, ArrayList())
                        },
                    ),
                )
            }
        runCurrent()
        assertEquals(winner, secondStarts.first())
        job.cancel()
    }
}

private class ScriptDial(
    private val dnsStep: suspend (String) -> Outcome<Unit> = { ok(Unit) },
    private val tcpStep: suspend (String) -> Outcome<Unit> = { ok(Unit) },
    private val proveStep: suspend (String) -> Outcome<String>,
) : CandidateDial<String> {
    override suspend fun dns(address: String): Outcome<Unit> = dnsStep(address)

    override suspend fun tcp(address: String): Outcome<Unit> = tcpStep(address)

    override suspend fun prove(address: String): Outcome<String> = proveStep(address)

    override suspend fun close(value: String) {}
}

private fun scriptDial(
    dns: suspend (String) -> Outcome<Unit> = { ok(Unit) },
    tcp: suspend (String) -> Outcome<Unit> = { ok(Unit) },
    prove: suspend (String) -> Outcome<String> = { ok(it) },
): ScriptDial = ScriptDial(dns, tcp, prove)

private suspend fun kotlinx.coroutines.test.TestScope.assertTimeout(
    limit: Long,
    dial: ScriptDial,
    reason: String,
) {
    val raced: Deferred<Outcome<Pair<String, String>>> =
        async { raceCandidates(listOf("slow.local"), dial) }
    runCurrent()
    advanceTimeBy(limit - 1)
    runCurrent()
    assertTrue(raced.isActive, reason)
    advanceTimeBy(1)
    runCurrent()
    val result = raced.await()
    assertEquals(reason, (result as Outcome.Err).failure.raceReason())
}

private suspend fun hangUntilCancelled(address: String, cancelled: MutableList<String>): Nothing {
    try {
        awaitCancellation()
    } catch (error: CancellationException) {
        cancelled += address
        throw error
    }
}

private fun raceProfile(
    name: String = "Yui",
    lastGood: String? = null,
    hints: List<String> = emptyList(),
    fqdn: String? = null,
): HostProfile =
    HostProfile(
        id = "host-1",
        hostId = "ab".repeat(16),
        role = HostRole.Controller,
        lastStatus = HostStatus.Unknown,
        lastUpdatedEpochMs = 1,
        topology = null,
        name = name,
        tailnetFqdn = fqdn,
        user = "misaka",
        port = 22,
        hostKeys = listOf("SHA256:ypeBEsobvcr6wjGzmiPcTaeG7/gUfE5yuYB3ha/uSLs"),
        hints = hints,
        lastGood = lastGood,
    )
