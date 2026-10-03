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
                    service?.pttDown()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.pttPad.alpha = 1.0f
                    service?.pttUp()
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
        }
        AlertDialog.Builder(this)
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
            .show()
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
        binding.radioDisplay.softkeyHighlight = if (scanEnabled) 1 else -1
        updateFooter()
    }

    private fun cycleChannel() {
        if (channels.isEmpty()) return
        channelIndex = (channelIndex + 1) % channels.size
        applyChannel()
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

    // ---- Hardware keys --------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (PttService.isPttKey(keyCode)) {
            if (event != null) service?.handleKeyEvent(event)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            cycleChannel()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (PttService.isPttKey(keyCode)) {
            if (event != null) service?.handleKeyEvent(event)
            return true
        }
        return super.onKeyUp(keyCode, event)
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
    }
}
