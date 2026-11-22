package com.looker.droidify.work

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.looker.droidify.datastore.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutoBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _treeUri = MutableStateFlow(prefs.getString(KEY_TREE_URI, null))
    val treeUri: StateFlow<String?> = _treeUri.asStateFlow()

    private val json = Json {
        encodeDefaults = true
        prettyPrint = true
    }

    fun isEnabled(): Boolean = _enabled.value && !_treeUri.value.isNullOrBlank()

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
    }

    fun setTreeUri(uri: Uri?) {
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Failed to persist URI permission", e)
            }
        }
        val raw = uri?.toString()
        prefs.edit().putString(KEY_TREE_URI, raw).apply()
        _treeUri.value = raw
    }

    fun getFolderDisplayName(rawUri: String? = _treeUri.value): String? {
        if (rawUri.isNullOrBlank()) return null
        val segment = Uri.parse(rawUri).lastPathSegment ?: return rawUri
        return segment.substringAfter(':', segment)
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun performBackup(settings: Settings): Boolean = withContext(Dispatchers.IO) {
        val rawUri = _treeUri.value
        if (!_enabled.value || rawUri.isNullOrBlank()) return@withContext false

        val treeUri = Uri.parse(rawUri)
        val resolver = context.contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)

        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val todayFileName = "$BACKUP_PREFIX$dateStr$BACKUP_SUFFIX"

        try {
            val existingBackups = mutableListOf<Pair<String, String>>() // (displayName, docId)
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idIdx)
                    val name = cursor.getString(nameIdx) ?: continue
                    if (BACKUP_FILE_REGEX.matches(name)) {
                        existingBackups.add(name to docId)
                    }
                }
            }

            val existingTodayDocId = existingBackups.firstOrNull { it.first == todayFileName }?.second
            val targetDocUri = if (existingTodayDocId != null) {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, existingTodayDocId)
            } else {
                DocumentsContract.createDocument(resolver, treeDocUri, "application/json", todayFileName)
            } ?: return@withContext false

            resolver.openOutputStream(targetDocUri, "wt")?.use { out ->
                json.encodeToStream(settings, out)
            } ?: return@withContext false

            // Prune oldest backups beyond MAX_BACKUPS (keep today's file + newest N-1)
            existingBackups
                .filter { it.first != todayFileName }
                .sortedByDescending { it.first }
                .drop(MAX_BACKUPS - 1)
                .forEach { (_, docId) ->
                    runCatching {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        DocumentsContract.deleteDocument(resolver, docUri)
                    }.onFailure { e ->
                        Log.w(TAG, "Failed to prune old backup $docId", e)
                    }
                }

            Log.i(TAG, "Auto-backup written to $todayFileName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Auto-backup failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "AutoBackupManager"
        private const val PREFS_NAME = "auto_backup_prefs"
        private const val KEY_ENABLED = "auto_backup_enabled"
        private const val KEY_TREE_URI = "auto_backup_tree_uri"
        private const val BACKUP_PREFIX = "droidify-backup-"
        private const val BACKUP_SUFFIX = ".json"
        private const val MAX_BACKUPS = 7
        private val BACKUP_FILE_REGEX = Regex("""^droidify-backup-\d{4}-\d{2}-\d{2}\.json$""")
    }
}
