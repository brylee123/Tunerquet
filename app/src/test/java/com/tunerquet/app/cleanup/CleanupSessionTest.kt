package com.tunerquet.app.cleanup

import com.tunerquet.app.adb.*
import com.tunerquet.app.inventory.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.*

class CleanupSessionTest {
    private val name = "com.netflix.ninja"
    private val other = "com.hulu.plus"
    private val key = "a".repeat(64)
    private val device = PairedDevice("guid", "192.168.1.20", 1234, 1, key, 4321)
    private val target = ConnectionTarget.parse(device.host, "4321")
    private fun result(value: String) = ShellResult(value, "", 0)
    private fun details(pkg: String, state: Int, version: String = "1") =
        "Packages:\n  Package [$pkg] (123):\n    versionCode=$version minSdk=26\n    versionName=1.0\n    User 0: installed=true hidden=false suspended=false stopped=false enabled=$state\n"
    private inner class Store : PairedDeviceStore {
        var record: PairedDevice? = device
        override fun list() = listOfNotNull(record)
        override fun save(device: PairedDevice) { record = device }
        override fun forget(guid: String) { record = null }
    }
    private class Journal : CleanupJournal {
        val records = CopyOnWriteArrayList<ChangeRecord>()
        var failWrites = false
        override fun list() = records.toList()
        override fun put(record: ChangeRecord) {
            if (failWrites) throw IOException("Disk full")
            records.removeAll { it.id == record.id }; records.add(record)
        }
    }
    private inner class Client(val log: Journal) : CleanupClient {
        val states = linkedMapOf(name to 0, other to 1)
        val mutations = CopyOnWriteArrayList<Pair<String, PackageChange>>()
        var serverKey = key
        var currentUser = "0"
        var version = "1"
        var systemApp = false
        var malformed = false
        var refuse = false
        var breakAfterMutation = false
        var beforeChange: (() -> Unit)? = null
        var approved = false
        var closed = false
        override fun connect(target: ConnectionTarget, expectedServerKey: String?) = ConnectionInfo(serverKey, "device::")
        override fun approveServerKey() { approved = true }
        override fun close() { closed = true }
        override fun read(command: InventoryCommand): ShellResult {
            check(approved)
            return result(when (command) {
                InventoryCommand.PROPERTIES -> "[persist.adb.wifi.guid]: [guid]"
                InventoryCommand.PACKAGES -> "package:/system/app/Settings/base.apk=com.android.settings uid:1000\n" +
                    states.keys.mapIndexed { i, pkg -> "package:/data/app/$pkg/base.apk=$pkg uid:${10001 + i}" }.joinToString("\n")
                InventoryCommand.SYSTEM -> "package:com.android.settings"
                InventoryCommand.DISABLED -> states.filterValues { it >= 2 }.keys.joinToString("\n") { "package:$it" }
                else -> error("Unexpected inventory read")
            }.let { if (command == InventoryCommand.SYSTEM) "package:com.android.settings" + (if (systemApp) "\npackage:$name" else "") else it })
        }
        override fun inspect(read: CleanupRead) = result(when (read) {
            CleanupRead.CURRENT_USER -> currentUser
            CleanupRead.LAUNCHERS -> "com.android.launcher/.Home"
            CleanupRead.INPUT_METHODS -> "com.android.keyboard/.Ime"
        })
        override fun packageDetails(name: String): ShellResult {
            if (breakAfterMutation && mutations.isNotEmpty()) throw IOException("Lost connection")
            return result(if (malformed) "unknown output" else details(name, states.getValue(name), version))
        }
        override fun change(name: String, action: PackageChange): ShellResult {
            assertTrue(log.list().any { it.evidence.pkg.name == name && it.status in setOf(ChangeStatus.PENDING, ChangeStatus.RESTORING) })
            beforeChange?.invoke()
            mutations.add(name to action)
            if (!refuse) states[name] = when (action) {
                PackageChange.DISABLE -> 3
                PackageChange.RESTORE_DEFAULT -> 0
                PackageChange.RESTORE_ENABLED -> 1
            }
            return result("State requested")
        }
    }
    private fun waitFor(session: CleanupSession, phase: CleanupPhase) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (session.state.phase != phase && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(session.state.message, phase, session.state.phase)
    }
    private fun session(store: Store, journal: Journal, client: Client, deadline: Long = 300000) =
        CleanupSession({ client }, store, journal, Executor { it.run() }, {}, deadlineMillis = deadline)
    private fun prepared(s: CleanupSession) { s.prepare(device.guid, target, true); waitFor(s, CleanupPhase.REVIEW) }

    @Test fun approvalAndPinnedPairingRequiredBeforeTransport() {
        val store = Store(); val journal = Journal(); val client = Client(journal)
        session(store, journal, client).use { s ->
            assertThrows(IllegalArgumentException::class.java) { s.prepare(device.guid, target, false) }
            store.record = device.copy(serverKeySha256 = null)
            assertThrows(IllegalArgumentException::class.java) { s.prepare(device.guid, target, true) }
            assertFalse(client.approved)
        }
    }
    @Test fun reviewDoesNotMutateAndCannotSelectUnreviewedPackages() {
        val store = Store(); val journal = Journal(); val client = Client(journal)
        session(store, journal, client).use { s ->
            prepared(s)
            assertEquals(2, s.state.candidates.size)
            assertTrue(client.mutations.isEmpty())
            assertThrows(IllegalArgumentException::class.java) { s.apply(setOf("com.android.settings"), true) }
            assertThrows(IllegalArgumentException::class.java) { s.apply(setOf(name), false) }
        }
    }
    @Test fun disableIsJournaledFirstAndVerifiedThenExactDefaultCanBeRestored() {
        val store = Store(); val journal = Journal(); val client = Client(journal)
        session(store, journal, client).use { s -> prepared(s); s.apply(setOf(name), true); waitFor(s, CleanupPhase.COMPLETE) }
        assertEquals(ChangeStatus.DISABLED, journal.list().single().status)
        assertEquals(3, client.states[name])
        val next = Client(journal).apply { states[name] = 3 }
        session(store, journal, next).use { s ->
            prepared(s); s.restore(journal.list().single().id, true); waitFor(s, CleanupPhase.COMPLETE)
        }
        assertEquals(listOf(name to PackageChange.RESTORE_DEFAULT), next.mutations.toList())
        assertEquals(ChangeStatus.RESTORED, journal.list().single().status)
    }
    @Test fun explicitEnabledStateRestoresWithEnableAndPreviouslyDisabledAppsNeverSelected() {
        val store = Store(); val journal = Journal(); val client = Client(journal).apply { states[name] = 3 }
        session(store, journal, client).use { s ->
            prepared(s); assertEquals(listOf(other), s.state.candidates.map { it.evidence.pkg.name })
            s.apply(setOf(other), true); waitFor(s, CleanupPhase.COMPLETE)
        }
        val next = Client(journal).apply { states[other] = 3 }
        session(store, journal, next).use { s -> prepared(s); s.restore(journal.list().single().id, true); waitFor(s, CleanupPhase.COMPLETE) }
        assertEquals(other to PackageChange.RESTORE_ENABLED, next.mutations.single())
        assertEquals(1, journal.list().single().evidence.enabledState)
    }
    @Test fun journalFailurePreventsAnyMutation() {
        val store = Store(); val journal = Journal(); val client = Client(journal)
        session(store, journal, client).use { s ->
            prepared(s); journal.failWrites = true
            s.apply(setOf(name), true); waitFor(s, CleanupPhase.FAILED)
        }
        assertTrue(client.mutations.isEmpty())
    }
    @Test fun changedVersionAndForgottenRecordInvalidateApproval() {
        for (forget in listOf(false, true)) {
            val store = Store(); val journal = Journal(); val client = Client(journal)
            session(store, journal, client).use { s ->
                prepared(s)
                if (forget) store.forget(device.guid) else client.version = "2"
                s.apply(setOf(name), true); waitFor(s, CleanupPhase.FAILED)
            }
            assertTrue(client.mutations.isEmpty())
        }
    }
    @Test fun failedVerificationStopsBatchAndRetainsUncertainJournal() {
        val store = Store(); val journal = Journal(); val client = Client(journal).apply { refuse = true }
        session(store, journal, client).use { s ->
            prepared(s); s.apply(setOf(name, other), true); waitFor(s, CleanupPhase.FAILED)
        }
        assertEquals(1, client.mutations.size)
        assertEquals(ChangeStatus.PENDING, journal.list().single().status)
    }
    @Test fun connectionLossAfterDisableCanBeRecoveredFromPendingRecord() {
        val store = Store(); val journal = Journal(); val client = Client(journal).apply { breakAfterMutation = true }
        session(store, journal, client).use { s -> prepared(s); s.apply(setOf(name), true); waitFor(s, CleanupPhase.FAILED) }
        assertEquals(ChangeStatus.PENDING, journal.list().single().status)
        val next = Client(journal).apply { states[name] = 3 }
        session(store, journal, next).use { s -> prepared(s); s.restore(journal.list().single().id, true); waitFor(s, CleanupPhase.COMPLETE) }
        assertEquals(ChangeStatus.RESTORED, journal.list().single().status)
    }
    @Test fun systemAppsMalformedStatesAndOtherUsersStayLocked() {
        val store = Store(); val journal = Journal()
        for (kind in 0..2) {
            val client = Client(journal).apply { if (kind == 0) systemApp = true; if (kind == 1) malformed = true; if (kind == 2) currentUser = "10" }
            session(store, journal, client).use { s ->
                s.prepare(device.guid, target, true)
                waitFor(s, if (kind == 2) CleanupPhase.FAILED else CleanupPhase.REVIEW)
                assertFalse(s.state.candidates.any { it.evidence.pkg.name == name })
            }
            assertTrue(client.mutations.isEmpty())
        }
    }
    @Test fun changedServerKeyPreventsAllChecksAndChanges() {
        val store = Store(); val journal = Journal(); val client = Client(journal).apply { serverKey = "b".repeat(64) }
        session(store, journal, client).use { s -> s.prepare(device.guid, target, true); waitFor(s, CleanupPhase.FAILED) }
        assertFalse(client.approved); assertTrue(client.mutations.isEmpty())
    }
    @Test fun cancellationBetweenCommandsKeepsPendingRecordAndStopsBatch() {
        val store = Store(); val journal = Journal(); val client = Client(journal)
        session(store, journal, client).use { s ->
            prepared(s)
            client.beforeChange = { s.cancel() }
            s.apply(setOf(name, other), true); waitFor(s, CleanupPhase.CANCELLED)
            Thread.sleep(50)
            assertEquals(1, client.mutations.size)
            assertEquals(ChangeStatus.PENDING, journal.list().single().status)
        }
    }
    @Test fun expiredReviewCannotExecute() {
        val store = Store(); val journal = Journal(); val client = Client(journal)
        session(store, journal, client, 150).use { s ->
            prepared(s); waitFor(s, CleanupPhase.CANCELLED)
            assertThrows(IllegalStateException::class.java) { s.apply(setOf(name), true) }
        }
        assertTrue(client.mutations.isEmpty())
    }
    @Test fun pendingEntryAlreadyAtOriginalStateNeedsNoEnableCommand() {
        val store = Store(); val journal = Journal(); val client = Client(journal).apply { refuse = true }
        session(store, journal, client).use { s -> prepared(s); s.apply(setOf(name), true); waitFor(s, CleanupPhase.FAILED) }
        val next = Client(journal)
        session(store, journal, next).use { s -> prepared(s); s.restore(journal.list().single().id, true); waitFor(s, CleanupPhase.COMPLETE) }
        assertTrue(next.mutations.isEmpty())
        assertEquals(ChangeStatus.RESTORED, journal.list().single().status)
    }
    @Test fun outsideStateChangeAndVersionChangeBlockRestore() {
        for (updated in listOf(false, true)) {
            val store = Store(); val journal = Journal(); val client = Client(journal)
            session(store, journal, client).use { s -> prepared(s); s.apply(setOf(name), true); waitFor(s, CleanupPhase.COMPLETE) }
            val next = Client(journal).apply { if (updated) { states[name] = 3; version = "2" } else states[name] = 2 }
            session(store, journal, next).use { s -> prepared(s); s.restore(journal.list().single().id, true); waitFor(s, CleanupPhase.FAILED) }
            assertTrue(next.mutations.isEmpty())
            assertEquals(ChangeStatus.DISABLED, journal.list().single().status)
        }
    }
    @Test fun newServerPinCannotRestoreOldJournal() {
        val store = Store(); val journal = Journal(); val client = Client(journal)
        session(store, journal, client).use { s -> prepared(s); s.apply(setOf(name), true); waitFor(s, CleanupPhase.COMPLETE) }
        store.record = device.copy(serverKeySha256 = "b".repeat(64))
        val next = Client(journal).apply { serverKey = "b".repeat(64); states[name] = 3 }
        session(store, journal, next).use { s ->
            prepared(s)
            assertEquals(1, s.state.records.size)
            assertThrows(IllegalArgumentException::class.java) { s.restore(journal.list().single().id, true) }
        }
        assertTrue(next.mutations.isEmpty())
    }
    @Test fun maliciousPackageStringsAreRejected() {
        for (bad in listOf("$name;reboot", "$name\nreboot", "--user", "$name'")) {
            assertThrows(IllegalArgumentException::class.java) { CleanupPolicy.validateName(bad) }
        }
    }
    @Test fun criticalRoleSharedUidAndSystemClassificationsOverrideCatalog() {
        val pkg = InstalledPackage(name, "/data/app/$name/base.apk", 10001, false, false, "1", "1")
        val evidence = PackageEvidence(pkg, 0)
        assertNotNull(CleanupPolicy.candidate(evidence, listOf(pkg), emptySet()))
        assertNull(CleanupPolicy.candidate(evidence, listOf(pkg), setOf(name)))
        assertNull(CleanupPolicy.candidate(evidence, listOf(pkg, pkg.copy(name = other)), emptySet()))
        assertNull(CleanupPolicy.candidate(evidence.copy(pkg = pkg.copy(systemApp = true)), listOf(pkg), emptySet()))
        assertNull(CleanupPolicy.candidate(evidence.copy(enabledState = 2), listOf(pkg), emptySet()))
        assertEquals(setOf("android", "com.android.settings"), CleanupPolicy.names("package:android\npackage:com.android.settings", false))
    }
}
