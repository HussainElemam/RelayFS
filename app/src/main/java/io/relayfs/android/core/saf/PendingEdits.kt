package io.relayfs.android.core.saf

import android.content.Context
import io.relayfs.android.core.database.AppDatabase
import io.relayfs.android.core.database.entity.PendingUploadEntity
import io.relayfs.android.core.database.entity.SafMetadataEntity
import io.relayfs.android.core.sync.io.AtomicFileWriter
import io.relayfs.android.core.work.CloudUploadWorker
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Files awaiting upload are persistent user edits, never disposable cache data. */
object PendingEdits {
    val openDescriptors: MutableSet<String> = ConcurrentHashMap.newKeySet()
    fun directory(context: Context): File = File(context.noBackupFilesDir, "pending_edits").apply { mkdirs() }

    suspend fun begin(context: Context, db: AppDatabase, entity: SafMetadataEntity, identity: String?, source: File?): PendingUploadEntity {
        val id = UUID.randomUUID().toString()
        val file = File(directory(context), "$id.edit")
        if (source != null && source.exists()) source.inputStream().use { AtomicFileWriter.write(file, it, source.length()) }
        else AtomicFileWriter.writeText(file, "")
        val row = PendingUploadEntity(id, entity.documentId, entity.accountId, identity, file.path,
            entity.parentDocumentId ?: "d0", entity.displayName, entity.sizeBytes, entity.lastModified, entity.contentHash)
        db.pendingUploadDao().upsert(row)
        return row
    }

    suspend fun closed(context: Context, db: AppDatabase, row: PendingUploadEntity, error: String? = null) {
        openDescriptors.remove(row.id)
        val file = File(row.localPath)
        if (file.exists()) java.io.RandomAccessFile(file, "rw").use { it.fd.sync() }
        db.pendingUploadDao().setState(row.id, if (error == null) "READY" else "ERROR", error)
        if (error == null) CloudUploadWorker.enqueuePending(context, row.id, row.accountId, row.documentId)
    }

    suspend fun recover(context: Context, db: AppDatabase) {
        for (row in db.pendingUploadDao().getAll()) {
            if (row.state == "READY") CloudUploadWorker.enqueuePending(context, row.id, row.accountId, row.documentId)
            else if (row.state == "OPEN" && row.id !in openDescriptors) {
                db.pendingUploadDao().setState(row.id, "ERROR", "An edit was interrupted. Export and review it, then retry.")
            }
        }
    }
}
