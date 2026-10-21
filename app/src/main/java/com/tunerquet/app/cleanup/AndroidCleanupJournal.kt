package com.tunerquet.app.cleanup

import android.content.Context
import android.util.AtomicFile
import com.tunerquet.app.inventory.InstalledPackage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Write-ahead log, retained across crashes and excluded from Android backups. Failed writes block commands. */
class AndroidCleanupJournal(context: Context) : CleanupJournal {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "cleanup-journal.json"))
    @Synchronized override fun list(): List<ChangeRecord> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val array = JSONArray(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        return (0 until array.length()).map { index ->
            val j = array.getJSONObject(index)
            val name = j.getString("name").also(CleanupPolicy::validateName)
            check(name in CleanupPolicy.optionalMedia) { "Unsupported journal package" }
            val original = j.getInt("enabled")
            check(original in 0..1) { "Invalid original enabled state" }
            ChangeRecord(j.getString("id"), j.getString("guid"), j.getString("key"), j.getString("address"),
                PackageEvidence(InstalledPackage(name, j.getString("path"), j.getInt("uid"), false, false,
                    j.getString("versionName"), j.getString("versionCode")), original),
                j.getLong("createdAt"), ChangeStatus.valueOf(j.getString("status")))
        }
    }
    @Synchronized override fun put(record: ChangeRecord) {
        val records = list().filterNot { it.id == record.id } + record
        val array = JSONArray()
        records.forEach { r ->
            val p = r.evidence.pkg
            array.put(JSONObject().put("id", r.id).put("guid", r.guid).put("key", r.serverKey).put("address", r.address)
                .put("name", p.name).put("path", p.apkPath).put("uid", p.uid).put("versionName", p.versionName)
                .put("versionCode", p.versionCode).put("enabled", r.evidence.enabledState)
                .put("createdAt", r.createdAt).put("status", r.status.name))
        }
        val stream = file.startWrite()
        try { stream.write(array.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
