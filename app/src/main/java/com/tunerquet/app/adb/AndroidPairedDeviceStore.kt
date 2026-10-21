package com.tunerquet.app.adb

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Atomic metadata in noBackupFilesDir. Codes and private keys are never serialized. */
class AndroidPairedDeviceStore(context: Context) : PairedDeviceStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "paired-devices.json"))
    private fun read(): JSONObject = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
        JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
    } else JSONObject()
    private fun write(json: JSONObject) {
        val stream = file.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    @Synchronized override fun save(device: PairedDevice) {
        val value = JSONObject().put("guid", device.guid).put("host", device.host)
            .put("pairingPort", device.pairingPort).put("pairedAt", device.pairedAt)
            .put("serverKeySha256", device.serverKeySha256).put("connectionPort", device.connectionPort)
        write(read().put(device.guid, value))
    }
    @Synchronized override fun list(): List<PairedDevice> {
        val all = read()
        return all.keys().asSequence().map { key ->
            val json = all.getJSONObject(key)
            PairedDevice(json.getString("guid"), json.getString("host"), json.getInt("pairingPort"), json.getLong("pairedAt"),
                if (json.has("serverKeySha256")) json.getString("serverKeySha256") else null,
                if (json.has("connectionPort")) json.getInt("connectionPort") else null)
        }.sortedByDescending { it.pairedAt }.toList()
    }
    @Synchronized override fun forget(guid: String) { write(read().apply { remove(guid) }) }
}
