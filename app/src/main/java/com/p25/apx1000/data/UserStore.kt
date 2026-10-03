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
        setSession(u, id)
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

    /** Learned side-PTT key code for this device (-1 = use defaults). */
    var pttKeyCode: Int
        get() = prefs.getInt(KEY_PTT, -1)
        set(v) = prefs.edit().putInt(KEY_PTT, v).apply()

    /** Layout override: 0 = auto, 1 = phone (touch PTT), 2 = PoC (keypad). */
    var layoutMode: Int
        get() = prefs.getInt(KEY_LAYOUT, 0)
        set(v) = prefs.edit().putInt(KEY_LAYOUT, v).apply()

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
            ?: return listOf(Channel(name = "P25", code = "P25-CH-1000", zone = 1))
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val alias = o.optString("a").ifEmpty { null }
            Channel(o.getString("n"), o.getString("c"), o.optInt("z", 1), alias)
        }.map {
            // one-time rebrand of the previous default name
            if (it.name.equals("APX-1000", ignoreCase = true)) it.copy(name = "P25") else it
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

    /** Number of zones the operator wants (1..9). */
    var zoneCount: Int
        get() = prefs.getInt(KEY_ZONECOUNT, 1).coerceIn(1, 9)
        set(v) = prefs.edit().putInt(KEY_ZONECOUNT, v.coerceIn(1, 9)).apply()

    /** Assign a channel to a zone (1-based). */
    fun setChannelZone(code: String, zone: Int) {
        val list = channels().map { if (it.code.equals(code, ignoreCase = true)) it.copy(zone = zone.coerceIn(1, zoneCount)) else it }
        persistChannels(list)
    }

    private fun persistChannels(list: List<Channel>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("n", it.name).put("c", it.code).put("z", it.zone).put("a", it.alias ?: ""))
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
        private const val KEY_PTT = "ptt_keycode"
        private const val KEY_LAYOUT = "layout_mode"
        private const val KEY_ZONECOUNT = "zone_count"
    }
}
