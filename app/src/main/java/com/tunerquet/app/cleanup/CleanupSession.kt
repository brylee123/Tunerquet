package com.tunerquet.app.cleanup

import com.tunerquet.app.adb.*
import com.tunerquet.app.inventory.*
import java.util.UUID
import java.util.concurrent.*

enum class CleanupPhase { IDLE, CHECKING, REVIEW, APPLYING, COMPLETE, FAILED, CANCELLED }
data class CleanupState(
    val phase: CleanupPhase = CleanupPhase.IDLE,
    val message: String = "Prepare a fresh app check before selecting cleanup.",
    val candidates: List<CleanupCandidate> = emptyList(),
    val records: List<ChangeRecord> = emptyList()
)

/** One explicit connection/review. Cancellation leaves any in-flight journal record unresolved. */
class CleanupSession(
    private val clients: () -> CleanupClient,
    private val store: PairedDeviceStore,
    private val journal: CleanupJournal,
    private val callbacks: Executor,
    private val changed: (CleanupState) -> Unit,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
    private val deadlineMillis: Long = 300000
) : AutoCloseable {
    private val lock = Any()
    private var generation = 0L
    private var closed = false
    private var client: CleanupClient? = null
    private var device: PairedDevice? = null
    private var target: ConnectionTarget? = null
    private var timeout: ScheduledFuture<*>? = null
    @Volatile var state = CleanupState(); private set

    fun prepare(guid: String, endpoint: ConnectionTarget, approved: Boolean) {
        require(approved) { "Approve a fresh cleanup check for the selected TV." }
        synchronized(lock) {
            check(!closed && client == null && state.phase !in setOf(CleanupPhase.CHECKING, CleanupPhase.APPLYING))
            val saved = store.list().singleOrNull { it.guid == guid } ?: error("Pair with this TV first.")
            require(saved.serverKeySha256 != null) { "Read inventory and trust this TV's server key first." }
            journal.list() // An unreadable journal must block the session before connecting.
            val run = ++generation
            device = saved
            target = endpoint
            emit(CleanupState(CleanupPhase.CHECKING, "Authenticating and checking current app state..."))
            timeout = timer.schedule({ cancel("Cleanup review expired. Prepare a new check.", run) }, deadlineMillis, TimeUnit.MILLISECONDS)
            worker.execute {
                try {
                    val active = synchronized(lock) {
                        guard(run)
                        clients().also { client = it }
                    }
                    val info = active.connect(endpoint, saved.serverKeySha256)
                    check(info.serverKeySha256 == saved.serverKeySha256) { "Server key changed" }
                    synchronized(lock) { guard(run); active.approveServerKey() }
                    val candidates = collect(run, active)
                    synchronized(lock) {
                        guard(run)
                        val records = records()
                        emit(CleanupState(CleanupPhase.REVIEW,
                            "Fresh check complete. Select apps to disable, then review the batch. System and unrecognized apps stay locked.",
                            candidates.filterNot { candidate -> records.any { it.evidence.pkg.name == candidate.evidence.pkg.name && it.status != ChangeStatus.RESTORED } }, records))
                    }
                } catch (e: Exception) { fail(run, e) }
            }
        }
    }
    private fun collect(run: Long, active: CleanupClient): List<CleanupCandidate> {
        fun read(command: InventoryCommand): String {
            synchronized(lock) { guard(run) }
            return CleanupPolicy.checked(active.read(command))
        }
        fun inspect(command: CleanupRead): String {
            synchronized(lock) { guard(run) }
            return CleanupPolicy.checked(active.inspect(command))
        }
        check(inspect(CleanupRead.CURRENT_USER).trim() == "0") { "Only active user 0 is supported. No changes sent." }
        val props = InventoryParser.properties(read(InventoryCommand.PROPERTIES))
        val guid = props["persist.adb.wifi.guid"]
        check(guid == null || guid == device?.guid) { "Device GUID changed" }
        val raw = read(InventoryCommand.PACKAGES)
        val all = InventoryParser.packages(raw)
        check(all.isNotEmpty() && all.size == raw.lineSequence().count { it.isNotBlank() }) { "Incomplete package inventory" }
        val system = CleanupPolicy.names(read(InventoryCommand.SYSTEM), false)
        val disabled = CleanupPolicy.names(read(InventoryCommand.DISABLED), true)
        check((system + disabled).all { name -> all.any { it.name == name } }) { "Package lists changed" }
        val roles = CleanupPolicy.components(inspect(CleanupRead.LAUNCHERS)) + CleanupPolicy.components(inspect(CleanupRead.INPUT_METHODS))
        return all.filter { it.name in CleanupPolicy.optionalMedia && it.name !in system && it.name !in disabled }.mapNotNull { pkg ->
            synchronized(lock) { guard(run) }
            val detail = CleanupPolicy.checked(request(run) { active.packageDetails(pkg.name) })
            // A parse failure leaves this app locked instead of guessing an original state.
            val evidence = runCatching { CleanupPolicy.evidence(pkg.copy(systemApp = false, disabled = false), detail) }.getOrNull()
            evidence?.let { CleanupPolicy.candidate(it, all, roles) }
        }
    }
    fun apply(names: Set<String>, approved: Boolean) {
        require(approved && names.isNotEmpty()) { "Review and approve at least one selected app." }
        synchronized(lock) {
            check(state.phase == CleanupPhase.REVIEW && !closed)
            require(names.size <= 20) { "Select at most 20 apps." }
            val selected = state.candidates.filter { it.evidence.pkg.name in names }
            require(selected.size == names.size) { "Selection is not in the reviewed plan." }
            val run = generation
            val active = client ?: error("Disconnected")
            emit(state.copy(phase = CleanupPhase.APPLYING, message = "Rechecking the approved batch..."))
            worker.execute {
                try {
                    val fresh = collect(run, active)
                    check(selected.all { it in fresh }) { "App state changed since review. Prepare a new check." }
                    for ((index, candidate) in selected.withIndex()) {
                        val record = synchronized(lock) {
                            guard(run)
                            check(records().none { it.evidence.pkg.name == candidate.evidence.pkg.name && it.status != ChangeStatus.RESTORED }) { "Restore the earlier journal entry first." }
                            // Re-read exact original state immediately before the write-ahead record.
                            emit(state.copy(message = "Disabling ${index + 1}/${selected.size}: ${candidate.label}"))
                            ChangeRecord(UUID.randomUUID().toString(), device!!.guid, device!!.serverKeySha256!!,
                                target!!.address, candidate.evidence, System.currentTimeMillis(), ChangeStatus.PENDING)
                        }
                        synchronized(lock) { guard(run) }
                        check(CleanupPolicy.checked(request(run) { active.inspect(CleanupRead.CURRENT_USER) }).trim() == "0") { "Active user changed." }
                        val before = CleanupPolicy.evidence(candidate.evidence.pkg, CleanupPolicy.checked(request(run) { active.packageDetails(candidate.evidence.pkg.name) }))
                        check(before == candidate.evidence) { "App state changed before disabling." }
                        synchronized(lock) { guard(run); journal.put(record) }
                        synchronized(lock) { guard(run) }
                        val result = active.change(record.evidence.pkg.name, PackageChange.DISABLE)
                        val after = CleanupPolicy.evidence(record.evidence.pkg, CleanupPolicy.checked(request(run) { active.packageDetails(record.evidence.pkg.name) }))
                        check(result.exitCode == 0 && result.stderr.isBlank() && after.enabledState == 3 && after.pkg == record.evidence.pkg) { "Disable was not verified. Review the journal before retrying." }
                        synchronized(lock) { guard(run); journal.put(record.copy(status = ChangeStatus.DISABLED)) }
                    }
                    complete(run, "Selected apps are verified disabled for user 0. Connection closed. Their app data remains installed.")
                } catch (e: Exception) { fail(run, e) }
            }
        }
    }
    fun restore(id: String, approved: Boolean) {
        require(approved) { "Approve restoring the original app state." }
        synchronized(lock) {
            check(!closed && state.phase == CleanupPhase.REVIEW)
            val record = records().singleOrNull { it.id == id && it.status != ChangeStatus.RESTORED } ?: error("No restorable journal entry.")
            val run = generation
            require(record.serverKey == device?.serverKeySha256) { "Server key differs from this journal. Restore through TV Settings or the original trusted connection." }
            val active = client ?: error("Disconnected")
            emit(state.copy(phase = CleanupPhase.APPLYING, message = "Checking original state for ${record.evidence.pkg.name}..."))
            worker.execute {
                try {
                    synchronized(lock) { guard(run) }
                    check(CleanupPolicy.checked(request(run) { active.inspect(CleanupRead.CURRENT_USER) }).trim() == "0") { "Only active user 0 is supported." }
                    val properties = InventoryParser.properties(CleanupPolicy.checked(request(run) { active.read(InventoryCommand.PROPERTIES) }))
                    check(properties["persist.adb.wifi.guid"].let { it == null || it == record.guid }) { "Device GUID changed" }
                    val packages = InventoryParser.packages(CleanupPolicy.checked(request(run) { active.read(InventoryCommand.PACKAGES) }))
                    val pkg = packages.singleOrNull { it.name == record.evidence.pkg.name } ?: error("App is no longer installed.")
                    check(pkg.uid == record.evidence.pkg.uid && pkg.apkPath == record.evidence.pkg.apkPath) { "App identity changed; inspect manually." }
                    val current = CleanupPolicy.evidence(record.evidence.pkg, CleanupPolicy.checked(request(run) { active.packageDetails(pkg.name) }))
                    check(current.pkg == record.evidence.pkg) { "App version changed; inspect manually." }
                    if (current.enabledState != record.evidence.enabledState) {
                        check(current.enabledState == 3) { "App state changed outside this cleanup. No restore sent." }
                        synchronized(lock) { guard(run); journal.put(record.copy(status = ChangeStatus.RESTORING)) }
                        synchronized(lock) { guard(run) }
                        val result = active.change(pkg.name, if (record.evidence.enabledState == 0) PackageChange.RESTORE_DEFAULT else PackageChange.RESTORE_ENABLED)
                        val after = CleanupPolicy.evidence(record.evidence.pkg, CleanupPolicy.checked(request(run) { active.packageDetails(pkg.name) }))
                        check(result.exitCode == 0 && result.stderr.isBlank() && after.enabledState == record.evidence.enabledState && after.pkg == record.evidence.pkg) { "Restore could not be verified." }
                    }
                    synchronized(lock) { guard(run); journal.put(record.copy(status = ChangeStatus.RESTORED)) }
                    complete(run, "Original enabled state verified. Connection closed.")
                } catch (e: Exception) { fail(run, e) }
            }
        }
    }
    private fun <T> request(run: Long, action: () -> T): T {
        synchronized(lock) { guard(run) }
        return action()
    }
    private fun records(): List<ChangeRecord> = journal.list().filter { it.guid == device?.guid }
    private fun guard(run: Long) {
        check(!closed && run == generation) { "Cleanup cancelled" }
        check(store.list().singleOrNull { it.guid == device?.guid } == device) { "Pairing record changed. No further commands sent." }
    }
    private fun emit(next: CleanupState) {
        state = next
        callbacks.execute { synchronized(lock) { if (!closed && state === next) changed(next) } }
    }
    private fun finish(run: Long, phase: CleanupPhase, message: String) {
        val active = synchronized(lock) {
            if (run != generation || closed) return
            timeout?.cancel(false); timeout = null
            val active = client
            client = null
            val saved = runCatching { records() }.getOrDefault(emptyList())
            emit(CleanupState(phase, message, records = saved))
            active
        }
        runCatching { active?.close() }
    }
    private fun complete(run: Long, message: String) = finish(run, CleanupPhase.COMPLETE, message)
    private fun fail(run: Long, e: Exception) = finish(run, CleanupPhase.FAILED,
        "Stopped: ${e.message ?: "connection or journal failure"}. Pending/restoring entries may already have changed; prepare a fresh check to restore. No later apps were changed.")
    fun cancel(message: String = "Cleanup stopped. Any in-flight change may have completed; check the saved journal before retrying.", expectedRun: Long? = null) {
        val active = synchronized(lock) {
            if (closed || (expectedRun != null && expectedRun != generation) || state.phase !in setOf(CleanupPhase.CHECKING, CleanupPhase.REVIEW, CleanupPhase.APPLYING)) return
            generation++
            timeout?.cancel(false); timeout = null
            val active = client
            client = null
            emit(CleanupState(CleanupPhase.CANCELLED, message, records = runCatching { records() }.getOrDefault(emptyList())))
            active
        }
        runCatching { active?.close() }
    }
    fun reset() { cancel(); synchronized(lock) { if (!closed) emit(CleanupState()) } }
    override fun close() {
        cancel()
        synchronized(lock) { closed = true; generation++ }
        worker.shutdownNow(); timer.shutdownNow()
    }
}
