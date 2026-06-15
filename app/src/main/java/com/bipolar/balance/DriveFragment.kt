package com.bipolar.balance

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
        if (result.resultCode == Activity.RESULT_OK) {
            GoogleSignIn.getSignedInAccountFromIntent(result.data)
                .addOnSuccessListener { account ->
                    updateSignInUi(account)
                    toast("Signed in as ${account.email}")
                }
                .addOnFailureListener { e ->
                    toast("Sign-in failed: ${e.message}")
                }
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

        b.btnSignIn.setOnClickListener { signIn() }
        b.btnSignOut.setOnClickListener { signOut() }
        b.btnUpload.setOnClickListener { upload() }
        b.btnDownload.setOnClickListener { download() }

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

    private fun upload() {
        val account = GoogleSignIn.getLastSignedInAccount(requireContext())
        if (account == null) { toast("Please sign in first"); return }

        val jsonPayload = DataRepository.exportJson(requireContext())
        setLoading(true)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val drive = buildDriveService(account)
                // Check if the backup file already exists and delete it first.
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
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    toast("Upload failed: ${e.message}")
                }
            }
        }
    }

    private fun download() {
        val account = GoogleSignIn.getLastSignedInAccount(requireContext())
        if (account == null) { toast("Please sign in first"); return }

        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val drive = buildDriveService(account)
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

                DataRepository.importJson(requireContext(), json)

                withContext(Dispatchers.Main) {
                    val nowMs = System.currentTimeMillis()
                    DataRepository.saveLastBackupMs(requireContext(), nowMs)
                    setLoading(false)
                    toast("Backup restored and merged")
                    refreshLastBackupLabel()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    toast("Download failed: ${e.message}")
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

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
