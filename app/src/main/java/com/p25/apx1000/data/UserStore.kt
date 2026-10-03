package com.p25.apx1000.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Simple local persistence for accounts, Unit IDs and talkgroup channels.
 *
 * The Unit ID (e.g. "1001", "ALPHA") is the P25 radio call identity and must
 * be unique per account. Passwords are stored as salted SHA-256 digests.
 */
class UserStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Account(val username: String, val passwordHash: String, val unitId: String)

    sealed class Result {
        data class Ok(val unitId: String) : Result()
        data class Error(val message: String) : Result()
    }

    // ---- Authentication -------------------------------------------------

    fun signUp(username: String, password: String, unitId: String): Result {
        val u = username.trim()
        val id = unitId.trim().uppercase()
        if (u.isEmpty() || password.isEmpty() || id.isEmpty()) {
            return Result.Error("Username, password and Unit ID are required")
        }
        val accounts = accounts().toMutableList()
        if (accounts.any { it.username.equals(u, ignoreCase = true) }) {
            return Result.Error("Username already registered")
        }
        if (accounts.any { it.unitId.equals(id, ignoreCase = true) }) {
            return Result.Error("Unit ID $id is already in use")
        }
        accounts.add(Account(u, hash(password, id), id))
        saveAccounts(accounts)
        return Result.Ok(id)
    }

    fun signIn(username: String, password: String, unitId: String): Result {
        val u = username.trim()
        val id = unitId.trim().uppercase()
        val account = accounts().firstOrNull { it.username.equals(u, ignoreCase = true) }
            ?: return Result.Error("Unknown user")
        if (!account.unitId.equals(id, ignoreCase = true)) {
            return Result.Error("Unit ID does not match this account")
        }
        if (account.passwordHash != hash(password, account.unitId)) {
            return Result.Error("Incorrect password")
        }
        setSession(account.username, account.unitId)
        return Result.Ok(account.unitId)
    }

    private fun setSession(username: String, unitId: String) {
        prefs.edit().putString(KEY_SESSION_USER, username).putString(KEY_SESSION_UID, unitId).apply()
    }

    val currentUsername: String? get() = prefs.getString(KEY_SESSION_USER, null)
    val currentUnitId: String? get() = prefs.getString(KEY_SESSION_UID, null)

    fun signOut() = prefs.edit().remove(KEY_SESSION_USER).remove(KEY_SESSION_UID).apply()

    private fun accounts(): List<Account> {
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Account(o.getString("u"), o.getString("h"), o.getString("id"))
        }
    }

    private fun saveAccounts(list: List<Account>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("u", it.username).put("h", it.passwordHash).put("id", it.unitId))
        }
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    // ---- Channels -------------------------------------------------------

    fun channels(): List<Channel> {
        val raw = prefs.getString(KEY_CHANNELS, null)
            ?: return listOf(Channel(name = "APX-1000", code = "P25-CH-1000"))
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Channel(o.getString("n"), o.getString("c"), o.optString("z", "ZONE 1"))
        }
    }

    /** Add a channel by name or unique code. Returns the new channel. */
    fun addChannel(nameOrCode: String): Channel {
        val channel = Channel.fromNameOrCode(nameOrCode)
        val list = channels().toMutableList()
        if (list.none { it.code.equals(channel.code, ignoreCase = true) }) {
            list.add(channel)
            persistChannels(list)
        }
        return channel
    }

    private fun persistChannels(list: List<Channel>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("n", it.name).put("c", it.code).put("z", it.zone))
        }
        prefs.edit().putString(KEY_CHANNELS, arr.toString()).apply()
    }

    private fun hash(password: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest((salt + ":" + password).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREFS = "p25_apx1000"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_CHANNELS = "channels"
        private const val KEY_SESSION_USER = "session_user"
        private const val KEY_SESSION_UID = "session_uid"
    }
}
