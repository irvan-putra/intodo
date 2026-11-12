package com.instinct.widget.bot

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.FileOutputStream

/**
 * Drop-in "Import bot backup" flow for BotActivity (itdapk 0.4.5).
 *
 * Restores BOTH halves of the bot from one .dcmig bundle written by the
 * scratch app's export (button 5 there):
 *   - the Delta Chat account (core import_backup, SQLCipher passphrase)
 *   - the bot's own state (bot_state.json + ops-*.db stores, Fernet)
 * Both are verified (sha256 manifest) and decrypted BEFORE anything is
 * written; the account import runs on a fresh slot and is removed again
 * if it fails halfway; state files land last, atomically, before any
 * service start. The bundle file itself is never modified.
 *
 * Wiring (BotActivity):
 *   private val importer = BotImport(this) { result -> ... }
 *   importButton.setOnClickListener { importer.launch() }
 *   override fun onActivityResult(...) { importer.handleActivityResult(...) }
 * On result.ok, BotActivity may start BotService for the first time.
 */
class BotImport(
    private val activity: Activity,
    private val callback: (Result) -> Unit,
) {
    data class Result(val ok: Boolean, val addr: String?, val error: String?)

    private var bundleFile: File? = null

    fun launch() {
        AlertDialog.Builder(activity)
            .setTitle("Import bot backup")
            .setMessage(
                "Move the existing bot (its Delta Chat identity, owner and " +
                "chats) into itdapk from the .dcmig bundle exported by the " +
                "old app. You will also need the one-time transfer code " +
                "shown there.\n\nThe bot service stays stopped during the " +
                "import.")
            .setPositiveButton("Choose bundle file") { _, _ -> stopServiceThenPick() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun stopServiceThenPick() {
        // Same stop pattern as the scratch app: clear the persisted mode so
        // a sticky restart does not revive the service mid-import.
        activity.getSharedPreferences("bot", Activity.MODE_PRIVATE)
            .edit().putString("last_mode", "").apply()
        activity.stopService(Intent(activity, BotService::class.java))
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        activity.startActivityForResult(i, REQ_PICK_BUNDLE)
    }

    /** Call from BotActivity.onActivityResult. Returns true when consumed. */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ_PICK_BUNDLE) return false
        if (resultCode != Activity.RESULT_OK || data?.data == null) return true
        try {
            val tmp = File.createTempFile("import-", ".dcmig", activity.cacheDir)
            activity.contentResolver.openInputStream(data.data!!).use { input ->
                FileOutputStream(tmp).use { out -> input?.copyTo(out) }
            }
            bundleFile = tmp
            promptCode()
        } catch (e: Exception) {
            Toast.makeText(activity, "Could not read bundle: $e", Toast.LENGTH_LONG).show()
        }
        return true
    }

    private fun promptCode() {
        val input = EditText(activity).apply {
            // visible-password: no autocorrect, no dictionary learning, no
            // clipboard suggestions for the transfer code
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            hint = "one-time transfer code"
        }
        AlertDialog.Builder(activity)
            .setTitle("Transfer code")
            .setMessage("Shown once by the old app during export (44 characters).")
            .setView(input)
            .setPositiveButton("Import") { _, _ -> runImport(input.text.toString().trim()) }
            .setNegativeButton("Cancel") { _, _ -> cleanup() }
            .show()
    }

    private fun runImport(code: String) {
        val bundle = bundleFile ?: return
        Toast.makeText(activity, "Importing...", Toast.LENGTH_SHORT).show()
        Thread {
            val result = try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(activity))
                val addr = Python.getInstance()
                    .getModule("migration")
                    .callAttr(
                        "import_migration",
                        activity.applicationInfo.nativeLibraryDir,
                        activity.filesDir.absolutePath,
                        bundle.absolutePath,
                        code,
                    )
                    .toString()
                Result(true, addr, null)
            } catch (e: Exception) {
                Result(false, null, e.message ?: e.toString())
            }
            activity.runOnUiThread {
                cleanup()
                callback(result)
            }
        }.start()
    }

    private fun cleanup() {
        bundleFile?.delete()
        bundleFile = null
    }

    companion object {
        const val REQ_PICK_BUNDLE = 43
    }
}
