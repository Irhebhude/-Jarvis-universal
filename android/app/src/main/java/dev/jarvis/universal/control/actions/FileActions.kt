package dev.jarvis.universal.control.actions

import android.content.ContentUris
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import dev.jarvis.universal.control.ActionRegistry
import dev.jarvis.universal.control.ActionResult
import dev.jarvis.universal.control.ActionSpec
import dev.jarvis.universal.control.ArgSpec
import dev.jarvis.universal.control.ArgType
import dev.jarvis.universal.control.Args
import dev.jarvis.universal.control.Danger
import dev.jarvis.universal.util.Logx
import java.io.ByteArrayOutputStream

/**
 * Authorized file access.
 *
 * Read-only discovery uses MediaStore. Writing requires an explicit Storage Access Framework
 * folder chosen by the owner in setup; JARVIS never deletes files, never overwrites an existing
 * file, and refuses content URIs it was not granted.
 */
object FileActions {

    private const val TAG = "FileActions"
    private const val MAX_READ_BYTES = 1_500_000
    private const val MAX_EXCERPT = 4000

    fun register(registry: ActionRegistry, deps: ActionDeps) {

        registry.register(
            ActionSpec(
                id = "files_search",
                description = "Search document files the system already indexes (text, PDF, office documents).",
                args = listOf(
                    ArgSpec("query", ArgType.STRING, required = true, maxLength = 80),
                    ArgSpec("limit", ArgType.INTEGER, required = false, min = 1.0, max = 25.0)
                )
            ) { args -> search(deps, args) }
        )

        registry.register(
            ActionSpec(
                id = "files_read_text",
                description = "Read a text document by its content URI (from files_search or a folder the owner granted).",
                args = listOf(
                    ArgSpec("uri", ArgType.STRING, required = true, maxLength = 1000)
                ),
                danger = Danger.SENSITIVE
            ) { args -> readText(deps, args) }
        )

        registry.register(
            ActionSpec(
                id = "files_write_note",
                description = "Create a new text note inside the folder the owner selected in setup. Never overwrites.",
                args = listOf(
                    ArgSpec("name", ArgType.STRING, required = true, maxLength = 60),
                    ArgSpec("body", ArgType.STRING, required = true, maxLength = 20000)
                ),
                danger = Danger.SENSITIVE
            ) { args -> writeNote(deps, args) }
        )

        registry.register(
            ActionSpec(
                id = "files_storage_status",
                description = "Report which storage capabilities are currently available."
            ) { storageStatus(deps) }
        )
    }

    private fun search(deps: ActionDeps, args: Args): ActionResult {
        val query = args.string("query")?.trim().orEmpty()
        if (query.isEmpty()) return ActionResult.fail("missing_argument", "What should I search for?")
        val limit = args.int("limit") ?: 10
        val resolver = deps.app.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE
        )
        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
        val results = mutableListOf<Map<String, Any?>>()

        try {
            resolver.query(collection, projection, selection, arrayOf("%$query%"), null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(projection[0])
                val nameIdx = cursor.getColumnIndex(projection[1])
                val mimeIdx = cursor.getColumnIndex(projection[2])
                val sizeIdx = cursor.getColumnIndex(projection[3])
                while (cursor.moveToNext() && results.size < limit) {
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx) ?: continue
                    val mime = cursor.getString(mimeIdx) ?: "application/octet-stream"
                    if (!isReadableMime(mime)) continue
                    val uri = ContentUris.withAppendedId(collection, id)
                    results.add(
                        mapOf(
                            "name" to name,
                            "mime" to mime,
                            "size_bytes" to cursor.getLong(sizeIdx),
                            "uri" to uri.toString()
                        )
                    )
                }
            }
        } catch (se: SecurityException) {
            Logx.w(TAG, "MediaStore query denied")
            return ActionResult.fail(
                "storage_permission",
                "I do not have permission to search your files. Grant storage access or pick a folder in setup."
            )
        } catch (t: Throwable) {
            Logx.e(TAG, "file search failed", t)
            return ActionResult.fail("search_failed", "The file search failed on this device.")
        }

        if (results.isEmpty()) {
            return ActionResult.ok(
                "I found no readable document matching $query. " +
                    "Only indexed text and PDF files are searchable here; use a granted folder for others.",
                mapOf("count" to 0)
            )
        }
        return ActionResult.ok(
            "I found ${results.size} matching file${if (results.size == 1) "" else "s"}: " +
                results.joinToString(", ") { it["name"].toString() } + ".",
            mapOf("count" to results.size, "files" to results)
        )
    }

    private fun isReadableMime(mime: String): Boolean {
        val m = mime.lowercase()
        return m.startsWith("text/") ||
            m == "application/pdf" ||
            m == "application/json" ||
            m == "application/xml" ||
            m.contains("wordprocessing") ||
            m.contains("spreadsheet") ||
            m == "application/rtf"
    }

    private fun readText(deps: ActionDeps, args: Args): ActionResult {
        val raw = args.string("uri")?.trim().orEmpty()
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
            ?: return ActionResult.fail("invalid_uri", "That file reference is not valid.")
        if (uri.scheme != "content") {
            return ActionResult.fail(
                "unsupported_uri",
                "I can only read documents that Android grants me through a content URI."
            )
        }
        return try {
            val resolver = deps.app.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            if (!isReadableMime(mime)) {
                return ActionResult.fail("unsupported_type", "I cannot read the document type $mime.")
            }
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_READ_BYTES) {
                        return ActionResult.fail(
                            "file_too_large",
                            "That document is larger than the ${MAX_READ_BYTES / 1000} kilobyte limit I read."
                        )
                    }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            if (bytes == null) {
                return ActionResult.fail("open_failed", "Android refused to open that document.")
            }
            val text = String(bytes, Charsets.UTF_8)
            val excerpt = if (text.length > MAX_EXCERPT) text.take(MAX_EXCERPT) else text
            ActionResult.ok(
                spoken = "I read ${text.length} characters from the document. " +
                    excerpt.take(220).replace('\n', ' '),
                data = mapOf(
                    "characters" to text.length,
                    "excerpt" to excerpt,
                    "truncated" to (text.length > MAX_EXCERPT)
                )
            )
        } catch (se: SecurityException) {
            ActionResult.fail("read_denied", "I do not have permission to read that document.")
        } catch (t: Throwable) {
            Logx.e(TAG, "read failed", t)
            ActionResult.fail("read_failed", "Reading that document failed.")
        }
    }

    private fun writeNote(deps: ActionDeps, args: Args): ActionResult {
        val treeUriString = deps.prefs.safTreeUri
        if (treeUriString.isNullOrBlank()) {
            return ActionResult.fail(
                "no_folder_granted",
                "Pick a notes folder in setup first, then I can write files."
            )
        }
        val name = sanitizeName(args.string("name") ?: "jarvis-note")
        val body = args.string("body").orEmpty()
        val resolver = deps.app.contentResolver
        return try {
            val tree = Uri.parse(treeUriString)
            val parentId = DocumentsContract.getTreeDocumentId(tree)
            val parentDoc = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            // Unique name, never overwrite an existing file.
            val stamp = System.currentTimeMillis()
            val fileName = "$name-${stamp}.txt"
            val created = DocumentsContract.createDocument(
                resolver,
                parentDoc,
                "text/plain",
                fileName
            ) ?: return ActionResult.fail("create_failed", "Android refused to create the note.")
            resolver.openOutputStream(created)?.use { it.write(body.toByteArray()) }
                ?: return ActionResult.fail("write_failed", "Android refused to write to the note.")
            ActionResult.ok(
                "I created $fileName in your notes folder.",
                mapOf("uri" to created.toString(), "name" to fileName, "bytes" to body.toByteArray().size)
            )
        } catch (se: SecurityException) {
            ActionResult.fail("write_denied", "The folder permission was revoked. Pick the folder again in setup.")
        } catch (t: Throwable) {
            Logx.e(TAG, "write note failed", t)
            ActionResult.fail("write_failed", "Writing the note failed.")
        }
    }

    private fun sanitizeName(raw: String): String =
        raw.trim()
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .take(60)
            .ifBlank { "jarvis-note" }

    private fun storageStatus(deps: ActionDeps): ActionResult {
        val saf = !deps.prefs.safTreeUri.isNullOrBlank()
        val mediaStore = try {
            deps.app.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns._ID),
                null, null, null
            )?.use { true } ?: false
        } catch (_: Throwable) {
            false
        }
        val spoken = buildString {
            append(if (mediaStore) "I can search indexed documents. " else "I cannot search indexed documents. ")
            append(if (saf) "I have a granted folder for creating notes." else "No notes folder is granted yet.")
        }
        return ActionResult.ok(spoken, mapOf("mediastore" to mediaStore, "saf_folder" to saf))
    }
}
