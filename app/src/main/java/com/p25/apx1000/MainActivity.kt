package com.p25.apx1000

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.p25.apx1000.audio.Codec2
import com.p25.apx1000.audio.PttEngine
import com.p25.apx1000.data.Channel
import com.p25.apx1000.data.UserStore
import com.p25.apx1000.databinding.ActivityMainBinding
import com.p25.apx1000.service.PttService

class MainActivity : AppCompatActivity(), PttService.UiListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: UserStore

    private var service: PttService? = null
    private var bound = false

    private var channels: List<Channel> = emptyList()
    private var channelIndex = 0
    private var scanEnabled = false
    private var busy = false
    private var online = false
    private var softkeyIndex = 0

    private var codecMode = Codec2.MODE_1600

    private val handler = Handler(Looper.getMainLooper())
    private val signalPoll = object : Runnable {
        override fun run() {
            updateSignalAndNetwork()
            handler.postDelayed(this, 2500)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra("level", 0) ?: 0
            val scale = intent?.getIntExtra("scale", 100) ?: 100
            binding.radioDisplay.batteryPct = if (scale > 0) level * 100 / scale else 0
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? PttService.LocalBinder ?: return
            service = local.service()
            service?.addUiListener(this@MainActivity)
            service?.setCodecMode(codecMode)
            pushChannelToService()
            updateFooter()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = UserStore(this)
        channels = store.channels()

        setupLogin()
        setupRadioControls()
        ensurePermissions()

        if (!store.currentUnitId.isNullOrEmpty()) {
            showRadio()
        } else {
            showLogin()
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, PttService::class.java), connection, Context.BIND_AUTO_CREATE)
        bound = true
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onResume() {
        super.onResume()
        service?.setVisible(true)
        handler.post(signalPoll)
    }

    override fun onPause() {
        super.onPause()
        service?.setVisible(false)
        handler.removeCallbacks(signalPoll)
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            service?.removeUiListener(this)
            unbindService(connection)
            bound = false
        }
        runCatching { unregisterReceiver(batteryReceiver) }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---- Authentication UI ---------------------------------------------

    private fun setupLogin() {
        binding.btnSignIn.setOnClickListener {
            val result = store.signIn(
                binding.inputUsername.text.toString(),
                binding.inputPassword.text.toString(),
                binding.inputUnitId.text.toString()
            )
            handleAuthResult(result, autoSignedUp = false)
        }
        binding.btnSignUp.setOnClickListener {
            val result = store.signUp(
                binding.inputUsername.text.toString(),
                binding.inputPassword.text.toString(),
                binding.inputUnitId.text.toString()
            )
            handleAuthResult(result, autoSignedUp = true)
        }
    }

    private fun handleAuthResult(result: UserStore.Result, autoSignedUp: Boolean) {
        when (result) {
            is UserStore.Result.Ok -> {
                val msg = if (autoSignedUp) "Account created · Unit ID ${result.unitId}"
                else "Signed in · Unit ID ${result.unitId}"
                binding.loginStatus.text = msg
                channels = store.channels()
                channelIndex = 0
                showRadio()
            }
            is UserStore.Result.Error -> binding.loginStatus.text = result.message
        }
    }

    private fun showLogin() {
        binding.loginContainer.visibility = View.VISIBLE
        binding.radioContainer.visibility = View.GONE
    }

    private fun showRadio() {
        binding.loginContainer.visibility = View.GONE
        binding.radioContainer.visibility = View.VISIBLE
        binding.radioDisplay.unitId = store.currentUnitId ?: "----"
        applyChannel()
        applyResponsiveLayout()
    }

    /** HT (small) gets a full-screen skin; phones get skin + large touch PTT. */
    private fun applyResponsiveLayout() {
        val dm = resources.displayMetrics
        val widthInches = dm.widthPixels / dm.xdpi
        val small = widthInches < 3.2f
        binding.pttPadWrap.visibility = if (small) View.GONE else View.VISIBLE
    }

    // ---- Radio controls -------------------------------------------------

    private fun setupRadioControls() {
        binding.pttPad.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    binding.pttPad.alpha = 0.7f
                    service?.requestPtt()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.pttPad.alpha = 1.0f
                    service?.releasePtt()
                    true
                }
                else -> false
            }
        }

        binding.btnAddChannel.setOnClickListener { showAddChannelDialog() }
        binding.btnRx.setOnClickListener { service?.replayRx(REMOTE_ID) }
        binding.btnBusy.setOnClickListener {
            busy = !busy
            service?.setChannelBusy(busy)
        }
        binding.btnMode.setOnClickListener { showModeDialog() }
    }

    private fun showAddChannelDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.add_channel_hint)
            setPadding(48, 32, 48, 32)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            isSingleLine = true
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.add_channel_title)
            .setView(input)
            .setPositiveButton(R.string.add_channel_ok) { _, _ ->
                val value = input.text.toString().trim()
                if (value.isEmpty()) return@setPositiveButton
                val channel = store.addChannel(value)
                channels = store.channels()
                channelIndex = channels.indexOfFirst { it.code == channel.code }.coerceAtLeast(0)
                applyChannel()
                Toast.makeText(this, "Added ${channel.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        // Keypad-only devices: type straight into the field, D-pad reaches OK/Cancel.
        dialog.setOnShowListener { input.requestFocus() }
        dialog.show()
    }

    private fun showModeDialog() {
        val modes = arrayOf(Codec2.MODE_700C, Codec2.MODE_1600, Codec2.MODE_3200, Codec2.MODE_1300)
        val labels = arrayOf("700C · most robotic", "1600 · balanced", "3200 · clearest", "1300 · narrow")
        val checked = modes.indexOf(codecMode).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.mode_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                codecMode = modes[which]
                service?.setCodecMode(codecMode)
                dialog.dismiss()
                updateFooter()
            }
            .show()
    }

    private fun applyChannel() {
        val channel = channels.getOrNull(channelIndex) ?: return
        binding.radioDisplay.zone = channel.zone
        binding.radioDisplay.channel = channel.name
        binding.radioDisplay.softkeyHighlight = softkeyIndex
        pushChannelToService()
        updateFooter()
    }

    private fun pushChannelToService() {
        val channel = channels.getOrNull(channelIndex) ?: return
        val unitId = store.currentUnitId ?: return
        service?.setChannel(unitId, channel.code)
    }

    private fun cycleChannel(delta: Int = 1) {
        if (channels.isEmpty()) return
        channelIndex = ((channelIndex + delta) % channels.size + channels.size) % channels.size
        applyChannel()
    }

    /** Move the soft-key highlight with LEFT/RIGHT (keypad only devices). */
    private fun moveSoftkey(delta: Int) {
        softkeyIndex = ((softkeyIndex + delta) % SOFTKEY_COUNT + SOFTKEY_COUNT) % SOFTKEY_COUNT
        binding.radioDisplay.softkeyHighlight = softkeyIndex
        updateFooter()
    }

    /** Activate the highlighted soft key (CENTER/ENTER or the left/right soft keys). */
    private fun activateSoftkey(index: Int) {
        when (index) {
            0 -> cycleChannel(1) // Chan: next channel
            1 -> {               // Scan: toggle scan
                scanEnabled = !scanEnabled
                Toast.makeText(this, if (scanEnabled) "Scan ON" else "Scan OFF", Toast.LENGTH_SHORT).show()
                updateFooter()
            }
            2 -> showAddChannelDialog() // Cnts: add channel / contacts
        }
    }

    private fun updateFooter() {
        val ch = channels.getOrNull(channelIndex)
        val modeName = service?.codecModeName() ?: when (codecMode) {
            Codec2.MODE_700C -> "700C"
            Codec2.MODE_3200 -> "3200"
            Codec2.MODE_1300 -> "1300"
            else -> "1600"
        }
        binding.radioFooter.text = buildString {
            append("Unit ").append(store.currentUnitId ?: "----")
            append(" · ").append(ch?.code ?: "-")
            append(" · Codec2 ").append(modeName)
            append(if (online) " · ONLINE" else " · OFFLINE")
            append(if (busy) " · BUSY" else " · IDLE")
        }
    }

    // ---- PttService.UiListener -----------------------------------------

    override fun onLight(light: PttEngine.Light) {
        binding.radioDisplay.light = when (light) {
            PttEngine.Light.RX -> com.p25.apx1000.ui.Apx1000View.Light.RX
            PttEngine.Light.TX -> com.p25.apx1000.ui.Apx1000View.Light.TX
            PttEngine.Light.INHIBIT -> com.p25.apx1000.ui.Apx1000View.Light.INHIBIT
            PttEngine.Light.IDLE -> com.p25.apx1000.ui.Apx1000View.Light.IDLE
        }
        updateFooter()
    }

    override fun onSpeaker(unitId: String?) {
        binding.radioDisplay.speakerId = unitId
        if (unitId != null) {
            Toast.makeText(this, "ID : $unitId", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBusy(busy: Boolean) {
        this.busy = busy
        binding.radioDisplay.statusText = if (busy) "BUSY" else networkLabel
        updateFooter()
    }

    override fun onError(message: String) {
        binding.radioDisplay.statusText = "ERR"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onConnection(connected: Boolean, detail: String) {
        online = connected
        binding.radioDisplay.statusText = if (connected) detail else "OFFLINE"
        updateFooter()
    }

    // ---- Hardware keys (works with no touch screen) ---------------------

    /**
     * Global key handling so arrow keys drive the radio on keypad-only HT
     * devices (e.g. Hytera PNC380 / PoC radios) regardless of focus.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (PttService.isPttKey(event.keyCode)) {
            service?.handleKeyEvent(event)
            return true
        }
        if (binding.radioContainer.visibility == View.VISIBLE && handleRadioKey(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleRadioKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) cycleChannel(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) cycleChannel(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) moveSoftkey(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) moveSoftkey(1)
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> if (down) activateSoftkey(softkeyIndex)
            KeyEvent.KEYCODE_SOFT_LEFT -> if (down) activateSoftkey(0)
            KeyEvent.KEYCODE_SOFT_RIGHT -> if (down) activateSoftkey(2)
            KeyEvent.KEYCODE_MENU -> if (down) showOptionsDialog()
            else -> return false
        }
        return true
    }

    private fun showOptionsDialog() {
        val items = arrayOf(
            "Codec 2 bitrate",
            "Toggle channel busy",
            "Replay last RX",
            "Add channel",
            "Sign out"
        )
        AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showModeDialog()
                    1 -> {
                        busy = !busy
                        service?.setChannelBusy(busy)
                    }
                    2 -> service?.replayRx(REMOTE_ID)
                    3 -> showAddChannelDialog()
                    4 -> {
                        store.signOut()
                        showLogin()
                    }
                }
            }
            .show()
    }

    // ---- Signal / battery ----------------------------------------------

    private var networkLabel = "LTE"

    private fun updateSignalAndNetwork() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifi?.connectionInfo
            val connected = info != null && info.networkId != -1
            val level = if (connected) {
                WifiManager.calculateSignalLevel(info!!.rssi, 5)
            } else {
                cellularLevel()
            }
            binding.radioDisplay.signalLevel = level
            networkLabel = if (connected) "WIFI" else "LTE"
            if (!busy) binding.radioDisplay.statusText = networkLabel
        } catch (_: Throwable) {
            binding.radioDisplay.signalLevel = 2
        }
    }

    private fun cellularLevel(): Int {
        return try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            when {
                tm == null -> 0
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED -> 2
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                    val ss = tm.signalStrength
                    if (ss != null) (WifiManager.calculateSignalLevel(ss.level, 5)).coerceIn(0, 4) else 2
                }
                else -> 2
            }
        } catch (_: Throwable) {
            2
        }
    }

    private fun ensurePermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        needed.add(Manifest.permission.READ_PHONE_STATE)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        val audioGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMISSIONS)
        }
        if (audioGranted) startRadioService()
    }

    private fun startRadioService() {
        try {
            PttService.start(this)
        } catch (t: Throwable) {
            Toast.makeText(this, "Could not start PTT service: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            val audioGranted = permissions.indices.any {
                permissions[it] == Manifest.permission.RECORD_AUDIO &&
                    grantResults[it] == PackageManager.PERMISSION_GRANTED
            }
            if (!audioGranted) {
                Toast.makeText(this, "Microphone permission is required for PTT", Toast.LENGTH_LONG).show()
            } else {
                startRadioService()
                service?.setCodecMode(codecMode)
            }
        }
    }

    companion object {
        private const val REQ_PERMISSIONS = 1001
        private const val REMOTE_ID = "1002"
        private const val SOFTKEY_COUNT = 3
    }
}
