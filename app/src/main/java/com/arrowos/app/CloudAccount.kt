package com.arrowos.app

import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.concurrent.TimeUnit

/**
 * The user's Arrow Os account (Firebase): email sign-in, plus the parts of their space
 * kept in the cloud. Firestore layout:
 *
 *   users/<uid>                 { email, homeSetup, updatedAt }
 *   users/<uid>/files/<fileId>  one record per Arrow Files file
 *
 * Firestore keeps writes made offline and sends them when the phone is back online.
 */
class CloudAccount {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    val uid: String? get() = auth.currentUser?.uid
    val email: String? get() = auth.currentUser?.email

    fun signIn(email: String, password: String, done: (error: String?) -> Unit) {
        auth.signInWithEmailAndPassword(AccountRules.cleanEmail(email), password)
            .addOnSuccessListener { done(null) }
            .addOnFailureListener { done(messageFor(it)) }
    }

    fun createAccount(email: String, password: String, done: (error: String?) -> Unit) {
        auth.createUserWithEmailAndPassword(AccountRules.cleanEmail(email), password)
            .addOnSuccessListener { done(null) }
            .addOnFailureListener { done(messageFor(it)) }
    }

    fun sendPasswordReset(email: String, done: (error: String?) -> Unit) {
        auth.sendPasswordResetEmail(AccountRules.cleanEmail(email))
            .addOnSuccessListener { done(null) }
            .addOnFailureListener { done(messageFor(it)) }
    }

    fun signOut() = auth.signOut()

    /** The current sign-in token, for the cloud file store. Blocks: call off the main thread. */
    fun idTokenBlocking(): String? = try {
        auth.currentUser?.let { Tasks.await(it.getIdToken(false), 30, TimeUnit.SECONDS).token }
    } catch (e: Exception) {
        null
    }

    private fun userDoc(uid: String) = db.collection("users").document(uid)

    /** The home screen the user built on another phone, or null. */
    fun fetchHomeSetup(uid: String, done: (String?) -> Unit) {
        userDoc(uid).get()
            .addOnSuccessListener { done(it.getString("homeSetup")) }
            .addOnFailureListener { done(null) }
    }

    fun saveHomeSetup(uid: String, encoded: String) {
        userDoc(uid).set(
            mapOf("homeSetup" to encoded, "email" to email, "updatedAt" to FieldValue.serverTimestamp()),
            SetOptions.merge(),
        )
    }

    /** Saves (or replaces) the cloud record of one Arrow Files file. */
    fun saveFile(uid: String, f: ArrowFile) {
        userDoc(uid).collection("files").document(f.id).set(
            mapOf(
                "name" to f.name,
                "source" to f.source,
                "kind" to f.kind.name,
                "mimeType" to f.mimeType,
                "size" to f.size,
                "savedAt" to f.savedAt,
                "originUrl" to f.originUrl,
                "path" to f.relPath,
                "status" to f.status.name,
                "trashedAt" to f.trashedAt,
                "inCloud" to f.inCloud,
            ),
        )
    }

    /** Every file record kept in the account, for showing them on a new phone. */
    fun fetchFiles(uid: String, done: (List<ArrowFile>) -> Unit) {
        userDoc(uid).collection("files").get()
            .addOnSuccessListener { snap ->
                done(
                    snap.documents.mapNotNull { d ->
                        try {
                            ArrowFile(
                                id = d.id,
                                name = d.getString("name") ?: return@mapNotNull null,
                                source = d.getString("source") ?: "other",
                                kind = FileKind.valueOf(d.getString("kind") ?: "Other"),
                                mimeType = d.getString("mimeType") ?: "application/octet-stream",
                                size = d.getLong("size") ?: 0,
                                savedAt = d.getLong("savedAt") ?: 0,
                                originUrl = d.getString("originUrl").orEmpty(),
                                relPath = d.getString("path") ?: return@mapNotNull null,
                                status = FileStatus.valueOf(d.getString("status") ?: "Failed"),
                                trashedAt = d.getLong("trashedAt"),
                                inCloud = d.getBoolean("inCloud") == true,
                            )
                        } catch (e: IllegalArgumentException) {
                            null
                        }
                    },
                )
            }
            .addOnFailureListener { done(emptyList()) }
    }

    fun deleteFile(uid: String, fileId: String) {
        userDoc(uid).collection("files").document(fileId).delete()
    }

    private fun messageFor(e: Exception): String = when (e) {
        is FirebaseAuthWeakPasswordException -> "That password is too easy to guess. Try a longer one."
        is FirebaseAuthUserCollisionException -> "There is already an account with this email. Tap Sign in instead."
        is FirebaseAuthInvalidUserException -> "No account with this email. Tap Create account first."
        is FirebaseAuthInvalidCredentialsException -> "The email or password is wrong."
        is FirebaseNetworkException -> "No internet connection. Connect and try again."
        is FirebaseTooManyRequestsException -> "Too many tries. Wait a few minutes and try again."
        else -> e.message ?: "Something went wrong. Try again."
    }
}
