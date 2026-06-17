package com.bipolar.balance

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bipolar.balance.databinding.FragmentDriveBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Google Drive sync tab.
 *
 * Sign-in → Upload button saves a JSON backup to the user's Drive (appDataFolder is private
 * to this app, so no special permissions dialog). The Download button restores the latest
 * backup and merges it with local data.
 */
class BackupFragment : Fragment() {

    private var _b: FragmentDriveBinding? = null
    private val b get() = _b!!

    private val BACKUP_FILENAME = "barlevel_backup.json"

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("BackupFragment", "signInLauncher result: ${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                updateSignInUi(account)
                toast("Signed in as ${account.email}")
                // Automatically check for backup on sign-in
                checkExistingBackup(account)
            } catch (e: ApiException) {
                Log.e("BackupFragment", "Sign-in failed with status code: ${e.statusCode}", e)
                toast("Sign-in failed (Error ${e.statusCode})")
            }
        } else {
            Log.w("BackupFragment", "Sign-in cancelled or failed with code: ${result.resultCode}")
            toast("Sign-in failed/cancelled (Code ${result.resultCode})")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentDriveBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        b.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        b.btnSignIn.setOnClickListener { signIn() }
        b.btnSignOut.setOnClickListener { signOut() }
        b.btnUpload.setOnClickListener { upload() }
        b.btnDownload.setOnClickListener { download() }
        b.btnCopySha1.setOnClickListener { copySha1() }

        // Restore previous sign-in silently.
        val account = GoogleSignIn.getLastSignedInAccount(requireContext())
        updateSignInUi(account)
        refreshLastBackupLabel()
    }

    private fun signIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        val client = GoogleSignIn.getClient(requireActivity(), gso)
        signInLauncher.launch(client.signInIntent)
    }

    private fun signOut() {
        val client = GoogleSignIn.getClient(
            requireActivity(),
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
        )
        client.signOut().addOnCompleteListener {
            updateSignInUi(null)
            toast("Signed out")
        }
    }

    private fun checkExistingBackup(account: GoogleSignInAccount) {
        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val drive = buildDriveService(account)
                val list = drive.files().list()
                    .setSpaces("appDataFolder")
                    .setQ("name='$BACKUP_FILENAME'")
                    .execute()

                withContext(Dispatchers.Main) {
                    setLoading(false)
                    if (list.files.isNotEmpty()) {
                        showBackupOptionsDialog(account)
                    } else {
                        toast("No existing backup found. You can upload your current data.")
                    }
                }
            } catch (e: Exception) {
                Log.e("BackupFragment", "Failed to check backup", e)
                withContext(Dispatchers.Main) { setLoading(false) }
            }
        }
    }

    private fun showBackupOptionsDialog(account: GoogleSignInAccount) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Existing Backup Found")
            .setMessage("What would you like to do with the data on Google Drive?")
            .setPositiveButton("Merge (Recommended)") { _, _ -> download(account, DataRepository.ImportMode.MERGE) }
            .setNeutralButton("Discard Local & Use Backup") { _, _ -> download(account, DataRepository.ImportMode.OVERWRITE_LOCAL) }
            .setNegativeButton("Keep Local / Overwrite Drive") { _, _ -> upload() }
            .show()
    }

    private fun upload() {
        val account = GoogleSignIn.getLastSignedInAccount(requireContext())
        if (account == null) { toast("Please sign in first"); return }

        val jsonPayload = DataRepository.exportJson(requireContext())
        setLoading(true)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val drive = buildDriveService(account)
                val existing = drive.files().list()
                    .setSpaces("appDataFolder")
                    .setQ("name='$BACKUP_FILENAME'")
                    .execute()
                existing.files.forEach { drive.files().delete(it.id).execute() }

                val meta = File().setName(BACKUP_FILENAME)
                    .setParents(listOf("appDataFolder"))
                val content = ByteArrayContent(
                    "application/json",
                    jsonPayload.toByteArray(Charsets.UTF_8)
                )
                drive.files().create(meta, content).execute()

                withContext(Dispatchers.Main) {
                    val nowMs = System.currentTimeMillis()
                    DataRepository.saveLastBackupMs(requireContext(), nowMs)
                    setLoading(false)
                    toast("Backup uploaded to Drive")
                    refreshLastBackupLabel()
                }
            } catch (e: Exception) {
                Log.e("BackupFragment", "Upload failed", e)
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    toast("Upload failed: ${e.message}")
                }
            }
        }
    }

    private fun download(account: GoogleSignInAccount? = null, mode: DataRepository.ImportMode = DataRepository.ImportMode.MERGE) {
        val activeAccount = account ?: GoogleSignIn.getLastSignedInAccount(requireContext())
        if (activeAccount == null) { toast("Please sign in first"); return }

        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val drive = buildDriveService(activeAccount)
                val list = drive.files().list()
                    .setSpaces("appDataFolder")
                    .setQ("name='$BACKUP_FILENAME'")
                    .execute()

                if (list.files.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        setLoading(false)
                        toast("No backup found on Drive")
                    }
                    return@launch
                }

                val fileId = list.files.first().id
                val stream = drive.files().get(fileId).executeMediaAsInputStream()
                val json = stream.bufferedReader().readText()

                DataRepository.importJson(requireContext(), json, mode)

                withContext(Dispatchers.Main) {
                    val nowMs = System.currentTimeMillis()
                    DataRepository.saveLastBackupMs(requireContext(), nowMs)
                    setLoading(false)
                    toast(if (mode == DataRepository.ImportMode.MERGE) "Backup merged" else "Local data replaced")
                    refreshLastBackupLabel()
                }
            } catch (e: Exception) {
                Log.e("BackupFragment", "Download failed", e)
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    val msg = if (e.message?.contains("403") == true || e.toString().contains("403")) {
                        "Download forbidden (403). Ensure 'Google Drive API' is ENABLED in Cloud Console Library."
                    } else {
                        "Download failed: ${e.message}"
                    }
                    toast(msg)
                }
            }
        }
    }

    private fun buildDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            requireContext(),
            listOf(DriveScopes.DRIVE_APPDATA)
        ).also { it.selectedAccount = account.account }

        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName(getString(R.string.app_name)).build()
    }

    private fun updateSignInUi(account: GoogleSignInAccount?) {
        val signed = account != null
        b.btnSignIn.visibility   = if (signed) View.GONE  else View.VISIBLE
        b.btnSignOut.visibility  = if (signed) View.VISIBLE else View.GONE
        b.btnUpload.isEnabled    = signed
        b.btnDownload.isEnabled  = signed
        b.tvAccount.text = if (signed) account!!.email ?: "Signed in" else "Not signed in"
    }

    private fun refreshLastBackupLabel() {
        val ms = DataRepository.getLastBackupMs(requireContext())
        b.tvLastSync.text = if (ms == 0L) "No backup saved yet" else
            "Last backed up: " + java.text.SimpleDateFormat(
                "d MMM yyyy, HH:mm", java.util.Locale.getDefault()
            ).format(java.util.Date(ms))
    }

    private fun setLoading(loading: Boolean) {
        b.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        b.btnUpload.isEnabled    = !loading
        b.btnDownload.isEnabled  = !loading
    }

    private fun copySha1() {
        val ctx = requireContext()
        try {
            val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
            }
            
            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (signatures != null) {
                for (signature in signatures) {
                    val md = java.security.MessageDigest.getInstance("SHA-1")
                    val digest = md.digest(signature.toByteArray())
                    val hexString = digest.joinToString(":") { "%02X".format(it) }
                    
                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("SHA-1", hexString)
                    clipboard.setPrimaryClip(clip)
                    toast("SHA-1 copied to clipboard")
                    Log.d("BackupFragment", "Copied SHA-1: $hexString")
                    return
                }
            } else {
                toast("No signatures found")
            }
        } catch (e: Exception) {
            Log.e("BackupFragment", "Failed to get SHA-1", e)
            toast("Could not get SHA-1: ${e.message}")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
