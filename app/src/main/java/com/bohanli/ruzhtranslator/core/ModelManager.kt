package com.bohanli.ruzhtranslator.core

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File

object ModelManager {
    private const val TAG = "ModelManager"
    private const val MODELS_SUBDIR = "models"
    const val PUBLIC_FALLBACK_DIRNAME = "translator_models"

    /**
     * Returns the ready-to-use model directory, checking in order:
     *  0. Public shared storage  /sdcard/Download/translator_models/<name>/
     *     (requires MANAGE_EXTERNAL_STORAGE; used as fallback on ROMs whose
     *      scoped-storage FUSE hides adb-shell-written files from app UID,
     *      e.g. HyperOS for Pad where app is not in ext_data_rw group).
     *  1. Internal files dir (previously extracted)
     *  2. External scoped files dir (manually placed)
     *  3. APK assets → extract to internal storage
     *
     * Handles the "extra nesting" case where a zip was extracted as
     *   assets/vosk-model-ru-0.42/vosk-model-ru-0.42/   (one level too deep)
     * and transparently resolves to the correct inner directory.
     */
    fun getModelDir(context: Context, modelName: String): File? {
        // Priority 0: public shared storage fallback
        getPublicFallbackDir()?.let { publicBase ->
            resolveDir(File(publicBase, modelName))?.let { return it }
        }

        // Priority 1: internal storage (already extracted)
        val internalDir = File(context.filesDir, "$MODELS_SUBDIR/$modelName")
        resolveDir(internalDir)?.let { return it }

        // Priority 2: external scoped files dir
        context.getExternalFilesDir(MODELS_SUBDIR)?.let { base ->
            resolveDir(File(base, modelName))?.let { return it }
        }

        // Priority 3: extract from APK assets
        return try {
            val entries = context.assets.list(modelName)
            if (entries?.isNotEmpty() == true) {
                Log.i(TAG, "Extracting $modelName from assets...")
                internalDir.mkdirs()
                extractAssetDir(context, modelName, internalDir)
                Log.i(TAG, "Extraction complete: $modelName")
                resolveDir(internalDir) ?: internalDir
            } else {
                Log.w(TAG, "Model $modelName not found in assets")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract $modelName from assets: ${e.message}")
            null
        }
    }

    /**
     * If [dir] exists and is non-empty, return it.
     * If [dir] contains exactly one subdirectory with the same name (double-nesting from zip
     * extraction), return that inner directory instead.
     */
    private fun resolveDir(dir: File): File? {
        if (!dir.isDirectory) return null
        val children = dir.list() ?: return null
        if (children.isEmpty()) return null

        // Detect double-nesting: only one child, and it's a directory with the same name
        if (children.size == 1) {
            val inner = File(dir, children[0])
            if (inner.isDirectory) {
                Log.i(TAG, "Detected extra nesting in ${dir.name}, using inner: ${inner.name}")
                return inner
            }
        }

        Log.i(TAG, "Using model dir: ${dir.absolutePath}")
        return dir
    }

    /**
     * Public-storage fallback base directory. Returns null if the app does not
     * hold MANAGE_EXTERNAL_STORAGE on Android 11+, or if external storage is not
     * mounted. The directory itself need not exist yet — [getModelDir] will
     * probe [resolveDir] on the concrete model subdir.
     */
    fun getPublicFallbackDir(): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()) {
            return null
        }
        val downloads = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        if (!downloads.isDirectory) return null
        return File(downloads, PUBLIC_FALLBACK_DIRNAME)
    }

    fun getExternalModelDir(context: Context): String =
        context.getExternalFilesDir(MODELS_SUBDIR)?.absolutePath ?: "(external storage unavailable)"

    /** Absolute path of the public fallback base, even if permission not yet granted. */
    fun getPublicFallbackPath(): String {
        val downloads = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        return File(downloads, PUBLIC_FALLBACK_DIRNAME).absolutePath
    }

    private fun extractAssetDir(context: Context, assetPath: String, targetDir: File) {
        targetDir.mkdirs()
        val entries = context.assets.list(assetPath) ?: return
        for (entry in entries) {
            val entryPath = "$assetPath/$entry"
            val targetFile = File(targetDir, entry)
            val subEntries = try { context.assets.list(entryPath) } catch (_: Exception) { null }
            if (subEntries?.isNotEmpty() == true) {
                extractAssetDir(context, entryPath, targetFile)
            } else {
                context.assets.open(entryPath).use { input ->
                    targetFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
