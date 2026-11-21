package com.example.radiosync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

@UnstableApi
class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: android.content.SharedPreferences
    private var processor: DelayAudioProcessor? = null
    private var player: ExoPlayer? = null
    private var relay: StreamRelay? = null

    @Volatile private var playing = false
    @Volatile private var measuring = false
    @Volatile private var measurementAborted = false
    private var measureThread: Thread? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvBuffered: TextView
    private lateinit var tvDelayReadout: TextView
    private lateinit var tvConfidence: TextView
    private lateinit var etUrl: EditText
    private lateinit var btnPlay: Button
    private lateinit var btnMeasure: Button
    private lateinit var btnMute: Button
    private lateinit var progressMeasure: ProgressBar
    private lateinit var tvCountdown: TextView
    private lateinit var tvBigDelay: TextView
    private lateinit var tvDetectedInfo: TextView
    private lateinit var seekDelay: SeekBar
    private lateinit var durationRow: LinearLayout
    private lateinit var savedRow: LinearLayout

    private val durationOptions = listOf(10, 15, 20, 30, 45, 60)
    private val durationButtons = mutableListOf<Button>()
    private var selectedDuration = 20

    private var pendingDelaySeconds: Double? = null
    private var pendingConfidence = 0
    private var pendingNegativeTvAhead: Double? = null

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateLiveUi()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        window.statusBarColor = Color.parseColor("#0B1120")
        window.navigationBarColor = Color.parseColor("#0B1120")
        prefs = getSharedPreferences("radio_sync", Context.MODE_PRIVATE)
        processor = DelayAudioProcessor()
        buildUi()
        loadPrefs()
        updateLiveUi()
        handler.post(updateRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateRunnable)
        stopPlaybackInternal(true)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun card(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(dp(16), dp(14), dp(16), dp(14))
        val g = GradientDrawable()
        g.cornerRadius = dp(18).toFloat()
        g.setColor(Color.parseColor("#131C31"))
        g.setStroke(dp(1), Color.parseColor("#1E293B"))
        l.background = g
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(12)
        l.layoutParams = lp
        return l
    }

    private fun label(text: String, size: Float = 14f, color: String = "#94A3B8", bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.parseColor(color))
        t.textSize = size
        if (bold) t.setTypeface(null, Typeface.BOLD)
        t.textDirection = View.TEXT_DIRECTION_RTL
        t.gravity = Gravity.RIGHT
        return t
    }

    private fun pillBg(color: String = "#1E293B"): GradientDrawable {
        val g = GradientDrawable()
        g.cornerRadius = dp(20).toFloat()
        g.setColor(Color.parseColor(color))
        return g
    }

    private fun button(text: String, bg: String = "#10B981", fg: String = "#04120C"): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 15f
        b.setTextColor(Color.parseColor(fg))
        b.setTypeface(null, Typeface.BOLD)
        b.isAllCaps = false
        val g = GradientDrawable()
        g.cornerRadius = dp(14).toFloat()
        g.setColor(Color.parseColor(bg))
        b.background = g
        b.stateListAnimator = null
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(dp(12), dp(10), dp(12), dp(10))
        return b
    }

    private fun weightedLp(weight: Float, margin: Int): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        lp.marginStart = margin
        lp.marginEnd = margin
        return lp
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#0B1120"))
        scroll.fillViewport = true

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(18), dp(16), dp(28))
        scroll.addView(root)
        setContentView(scroll)

        val title = label("مزامنة الراديو مع الصورة", 22f, "#F8FAFC", true)
        title.gravity = Gravity.CENTER
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val subtitle = label("تأخير حتى 120 ثانية • بث مباشر", 13f, "#64748B")
        subtitle.gravity = Gravity.CENTER
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.bottomMargin = dp(14)
        subtitle.layoutParams = slp
        root.addView(subtitle)

        // ===== Status Card =====
        val statusCard = card()
        statusCard.addView(label("حالة البث", 13f, "#94A3B8", true))
        tvStatus = label("جاهز للتشغيل", 16f, "#F8FAFC", true)
        tvStatus.setPadding(0, dp(4), 0, dp(6))
        statusCard.addView(tvStatus)
        tvBuffered = label("المخزون: 0 ث", 13f, "#94A3B8")
        statusCard.addView(tvBuffered)
        tvDelayReadout = label("الصورة — ث • الصوت 0.00 ث", 14f, "#E2E8F0", true)
        tvDelayReadout.setPadding(0, dp(6), 0, 0)
        statusCard.addView(tvDelayReadout)
        tvConfidence = label("", 12f, "#10B981", true)
        tvConfidence.visibility = View.GONE
        statusCard.addView(tvConfidence)
        root.addView(statusCard)

        // ===== Stream Bar =====
        val streamCard = card()
        streamCard.addView(label("رابط البث", 13f, "#94A3B8", true))
        etUrl = EditText(this)
        etUrl.hint = "http://example.com:8080/live"
        etUrl.setTextColor(Color.WHITE)
        etUrl.setHintTextColor(Color.parseColor("#475569"))
        etUrl.textSize = 14f
        etUrl.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        etUrl.textDirection = View.TEXT_DIRECTION_LTR
        etUrl.gravity = Gravity.LEFT
        etUrl.setSingleLine(true)
        val eg = GradientDrawable()
        eg.cornerRadius = dp(12).toFloat()
        eg.setColor(Color.parseColor("#0B1120"))
        eg.setStroke(dp(1), Color.parseColor("#334155"))
        etUrl.background = eg
        etUrl.setPadding(dp(12), dp(10), dp(12), dp(10))
        val elp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        elp.topMargin = dp(6)
        elp.bottomMargin = dp(8)
        etUrl.layoutParams = elp
        streamCard.addView(etUrl)

        btnPlay = button("تشغيل")
        btnPlay.setOnClickListener { togglePlay() }
        streamCard.addView(btnPlay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val hscroll = HorizontalScrollView(this)
        hscroll.isHorizontalScrollBarEnabled = false
        savedRow = LinearLayout(this)
        savedRow.orientation = LinearLayout.HORIZONTAL
        hscroll.addView(savedRow)
        val hlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hlp.topMargin = dp(8)
        streamCard.addView(hscroll, hlp)
        root.addView(streamCard)

        // ===== Measure Card =====
        val measureCard = card()
        measureCard.addView(label("فحص التأخير الصوتي", 13f, "#94A3B8", true))
        btnMeasure = button("فحص التأخير", "#10B981")
        btnMeasure.textSize = 18f
        btnMeasure.setOnClickListener { startMeasurement() }
        val mlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        mlp.topMargin = dp(8)
        mlp.bottomMargin = dp(10)
        measureCard.addView(btnMeasure, mlp)

        measureCard.addView(label("مدة الفحص", 12f, "#64748B"))
        durationRow = LinearLayout(this)
        durationRow.orientation = LinearLayout.HORIZONTAL
        durationRow.gravity = Gravity.CENTER
        measureCard.addView(durationRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        buildDurationChips()

        progressMeasure = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressMeasure.max = 100
        progressMeasure.progress = 0
        progressMeasure.progressTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
        progressMeasure.visibility = View.GONE
        val plp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6))
        plp.topMargin = dp(10)
        measureCard.addView(progressMeasure, plp)

        tvCountdown = label("", 13f, "#10B981", true)
        tvCountdown.visibility = View.GONE
        measureCard.addView(tvCountdown)
        root.addView(measureCard)

        // ===== 3 Action Buttons =====
        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.CENTER
        actions.weightSum = 3f
        val alp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        alp.bottomMargin = dp(12)
        actions.layoutParams = alp

        val btnSync = button("زامن الآن", "#10B981")
        btnSync.textSize = 13f
        btnSync.setOnClickListener {
            val pd = pendingDelaySeconds
            if (pendingNegativeTvAhead != null) {
                setDelay(0.0)
                setStatus("التلفاز متقدم عن الراديو — التأخير 0.00 ث", "#F59E0B")
            } else if (pd != null) {
                setDelay(pd)
                setStatus("تم تطبيق التأخير ${String.format(Locale.US, "%.2f", pd)} ث", "#10B981")
            } else {
                setStatus("قم بإجراء الفحص أولاً", "#F59E0B")
            }
        }

        btnMute = button("صوت القناة", "#1E293B", "#E2E8F0")
        btnMute.textSize = 13f
        btnMute.setOnClickListener {
            val p = processor ?: return@setOnClickListener
            p.muted = !p.muted
            btnMute.text = if (p.muted) "إلغاء الكتم" else "صوت القناة"
        }

        val btnReset = button("إيقاف المزامنة", "#7F1D1D", "#FEE2E2")
        btnReset.textSize = 13f
        btnReset.setOnClickListener {
            setDelay(0.0)
            pendingDelaySeconds = null
            pendingConfidence = 0
            pendingNegativeTvAhead = null
            setStatus("تم إيقاف المزامنة", "#94A3B8")
            updateLiveUi()
        }

        actions.addView(btnSync, weightedLp(1f, dp(4)))
        actions.addView(btnMute, weightedLp(1f, dp(4)))
        actions.addView(btnReset, weightedLp(1f, dp(4)))
        root.addView(actions)

        // ===== Manual Fine-Tuning =====
        val manual = card()
        manual.addView(label("الضبط اليدوي للتأخير", 13f, "#94A3B8", true))
        tvBigDelay = label("0.00 ث", 40f, "#10B981", true)
        tvBigDelay.gravity = Gravity.CENTER
        manual.addView(tvBigDelay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        tvDetectedInfo = label("لم يتم الفحص بعد", 12f, "#64748B")
        tvDetectedInfo.gravity = Gravity.CENTER
        manual.addView(tvDetectedInfo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        seekDelay = SeekBar(this)
        seekDelay.max = 480
        seekDelay.progress = 0
        seekDelay.progressTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
        seekDelay.thumbTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
        seekDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) setDelay(progress / 4.0)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        manual.addView(seekDelay, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val nudges = listOf(
            "-10 ث" to -10.0,
            "-1 ث" to -1.0,
            "-0.25 ث" to -0.25,
            "+0.25 ث" to 0.25,
            "+1 ث" to 1.0,
            "+10 ث" to 10.0
        )
        val nrow = LinearLayout(this)
        nrow.orientation = LinearLayout.HORIZONTAL
        nrow.gravity = Gravity.CENTER
        for ((txt, delta) in nudges) {
            val b = Button(this)
            b.text = txt
            b.textSize = 11f
            b.setTextColor(Color.parseColor("#E2E8F0"))
            b.isAllCaps = false
            b.minWidth = 0
            b.minimumWidth = 0
            b.setPadding(dp(4), dp(6), dp(4), dp(6))
            b.background = pillBg()
            b.setOnClickListener {
                val current = processor?.delaySeconds ?: 0.0
                setDelay(current + delta)
            }
            nrow.addView(b, weightedLp(1f, dp(2)))
        }
        manual.addView(nrow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(manual)
    }

    private fun buildDurationChips() {
        durationRow.removeAllViews()
        durationButtons.clear()
        for (d in durationOptions) {
            val b = Button(this)
            b.text = "$d ث"
            b.textSize = 12f
            b.isAllCaps = false
            b.minWidth = 0
            b.minimumWidth = 0
            b.setPadding(dp(6), dp(6), dp(6), dp(6))
            b.setOnClickListener {
                selectedDuration = d
                updateDurationChips()
            }
            durationButtons.add(b)
            durationRow.addView(b, weightedLp(1f, dp(2)))
        }
        updateDurationChips()
    }

    private fun updateDurationChips() {
        for ((i, d) in durationOptions.withIndex()) {
            val b = durationButtons.getOrNull(i) ?: continue
            if (d == selectedDuration) {
                b.setTextColor(Color.parseColor("#04120C"))
                b.background = pillBg("#10B981")
            } else {
                b.setTextColor(Color.parseColor("#94A3B8"))
                b.background = pillBg("#1E293B")
            }
        }
    }

    private fun loadPrefs() {
        val last = prefs.getString("last_url", "") ?: ""
        if (last.isNotEmpty()) etUrl.setText(last)
        renderSavedUrls()
    }

    private fun renderSavedUrls() {
        if (!::savedRow.isInitialized) return
        savedRow.removeAllViews()
        val set = prefs.getStringSet("saved_urls", emptySet()) ?: emptySet()
        for (u in set.take(8)) {
            val b = Button(this)
            b.text = hostOf(u)
            b.textSize = 11f
            b.isAllCaps = false
            b.minWidth = 0
            b.minimumWidth = 0
            b.setTextColor(Color.parseColor("#94A3B8"))
            b.background = pillBg("#0B1120")
            b.setPadding(dp(10), dp(6), dp(10), dp(6))
            b.setOnClickListener { etUrl.setText(u) }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(6)
            savedRow.addView(b, lp)
        }
    }

    private fun hostOf(url: String): String = try {
        URL(url).host ?: url
    } catch (_: Exception) {
        url
    }

    private fun setStatus(msg: String, color: String) {
        safeUi {
            tvStatus.text = msg
            tvStatus.setTextColor(Color.parseColor(color))
        }
    }

    private fun safeUi(action: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runOnUiThread {
            if (!isFinishing && !isDestroyed) action()
        }
    }

    private fun updateLiveUi() {
        if (!::tvBuffered.isInitialized) return
        val proc = processor ?: return
        val buffered = proc.bufferedSeconds
        tvBuffered.text = String.format(Locale.US, "المخزون: %.0f ث", buffered)
        val audioDelay = proc.delaySeconds
        val pd = pendingDelaySeconds
        val picText = if (pd == null) "—" else String.format(Locale.US, "%.1f", pd)
        tvDelayReadout.text = "الصورة $picText ث • الصوت ${String.format(Locale.US, "%.2f", audioDelay)} ث"

        if (pendingConfidence > 0) {
            tvConfidence.visibility = View.VISIBLE
            tvConfidence.text = "الثقة: $pendingConfidence%"
        } else {
            tvConfidence.visibility = View.GONE
        }

        if (!seekDelay.isPressed) {
            val prog = (audioDelay * 4).roundToInt().coerceIn(0, 480)
            if (seekDelay.progress != prog) seekDelay.progress = prog
        }
        tvBigDelay.text = String.format(Locale.US, "%.2f ث", audioDelay)

        val ahead = pendingNegativeTvAhead
        tvDetectedInfo.text = when {
            ahead != null -> "التلفاز متقدم عن الراديو بـ ${String.format(Locale.US, "%.1f", ahead)} ث — التأخير 0"
            pd != null -> "التأخير المكتشف: ${String.format(Locale.US, "%.2f", pd)} ث (الثقة $pendingConfidence%)"
            else -> "لم يتم الفحص بعد"
        }
        btnMute.text = if (proc.muted) "إلغاء الكتم" else "صوت القناة"
    }

    private fun setDelay(d: Double) {
        val clamped = d.coerceIn(0.0, 120.0)
        processor?.delaySeconds = clamped
        val progress = (clamped * 4).roundToInt().coerceIn(0, 480)
        if (seekDelay.progress != progress) seekDelay.progress = progress
        tvBigDelay.text = String.format(Locale.US, "%.2f ث", clamped)
        updateLiveUi()
    }

    // ---------------------------------------------------------------------------------------------
    // Playback
    // ---------------------------------------------------------------------------------------------

    private fun togglePlay() {
        if (playing) stopPlaybackInternal(true) else startPlayback()
    }

    private fun startPlayback() {
        val url = etUrl.text.toString().trim()
        if (url.isEmpty()) {
            setStatus("أدخل رابط البث أولاً", "#EF4444")
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            setStatus("الرابط يجب أن يبدأ بـ http:// أو https://", "#EF4444")
            return
        }

        prefs.edit().putString("last_url", url).apply()
        val set = (prefs.getStringSet("saved_urls", emptySet()) ?: emptySet()).toMutableSet()
        set.add(url)
        prefs.edit().putStringSet("saved_urls", set).apply()
        renderSavedUrls()

        stopPlaybackInternal(false)

        val isHls = url.contains(".m3u8", ignoreCase = true)
        val playUrl: String
        try {
            if (!isHls) {
                val r = StreamRelay(url) { msg ->
                    setStatus("ترحيل: $msg", "#F59E0B")
                }
                relay = r
                val port = r.start()
                playUrl = "http://127.0.0.1:$port/stream"
            } else {
                playUrl = url
            }
        } catch (e: Exception) {
            setStatus("فشل بدء الترحيل: ${e.message}", "#EF4444")
            return
        }

        val proc = processor ?: DelayAudioProcessor().also { processor = it }

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink? {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<AudioProcessor>(proc))
                    .build()
            }
        }

        val dsFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("VLC/3.0.18")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dsFactory)

        val p = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> setStatus("جارٍ التخزين المؤقت...", "#F59E0B")
                    Player.STATE_READY -> {
                        if (p.playWhenReady) setStatus("يعمل • البث المباشر", "#10B981")
                    }
                    Player.STATE_ENDED -> setStatus("انتهى البث", "#F59E0B")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val msg = buildString {
                    append(error.errorCodeName)
                    error.message?.let { append(": ").append(it) }
                    var cause = error.cause
                    var depth = 0
                    while (cause != null && depth < 3) {
                        append(" ← ").append(cause.javaClass.simpleName).append(": ").append(cause.message)
                        cause = cause.cause
                        depth++
                    }
                }
                setStatus("خطأ: $msg", "#EF4444")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                safeUi {
                    playing = isPlaying
                    if (isPlaying) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        btnPlay.text = "إيقاف"
                        btnPlay.background = pillBg("#7F1D1D")
                        btnPlay.setTextColor(Color.parseColor("#FEE2E2"))
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        })

        player = p
        p.setMediaItem(MediaItem.fromUri(playUrl))
        p.prepare()
        p.playWhenReady = true
        playing = true
        btnPlay.text = "إيقاف"
        btnPlay.background = pillBg("#7F1D1D")
        btnPlay.setTextColor(Color.parseColor("#FEE2E2"))
        setStatus("جارٍ الاتصال...", "#F59E0B")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun stopPlaybackInternal(updateUi: Boolean) {
        measurementAborted = true
        measuring = false
        measureThread?.interrupt()
        measureThread = null

        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null

        try { relay?.stop() } catch (_: Exception) {}
        relay = null

        playing = false
        try { processor?.reset() } catch (_: Exception) {}
        processor?.let {
            it.measurementMute = false
            it.muted = false
            it.delaySeconds = 0.0
        }

        pendingDelaySeconds = null
        pendingConfidence = 0
        pendingNegativeTvAhead = null

        if (updateUi) {
            safeUi {
                btnPlay.text = "تشغيل"
                btnPlay.background = pillBg("#10B981")
                btnPlay.setTextColor(Color.parseColor("#04120C"))
                setStatus("متوقف", "#94A3B8")
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                updateLiveUi()
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Measurement
    // ---------------------------------------------------------------------------------------------

    private fun startMeasurement() {
        if (measuring) return
        val proc = processor
        if (proc == null || !playing) {
            setStatus("شغّل البث أولاً قبل الفحص", "#EF4444")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            return
        }
        if (proc.envTotal < 250) {
            setStatus("انتظر بضع ثوانٍ حتى يمتلئ مخزون الراديو", "#F59E0B")
            return
        }

        measuring = true
        measurementAborted = false
        pendingDelaySeconds = null
        pendingNegativeTvAhead = null
        pendingConfidence = 0
        proc.measurementMute = true

        val duration = selectedDuration
        val totalSamples = duration * 50

        progressMeasure.visibility = View.VISIBLE
        progressMeasure.progress = 0
        tvCountdown.visibility = View.VISIBLE
        tvCountdown.text = "جارٍ الفحص... 0/$duration ث"
        btnMeasure.isEnabled = false
        btnMeasure.alpha = 0.5f

        val micEnv = FloatArray(totalSamples)

        val runnable = Runnable {
            var record: AudioRecord? = null
            var tStart = 0L
            try {
                val sampleRate = 16000
                val blockSize = sampleRate / 50
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AndroidAudioFormat.CHANNEL_IN_MONO,
                    AndroidAudioFormat.ENCODING_PCM_16BIT
                )
                val bufSize = max(minBuf, blockSize * 4)
                record = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AndroidAudioFormat.Builder()
                            .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AndroidAudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize)
                    .build()

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    throw IOException("تعذر تهيئة الميكروفون")
                }

                record.startRecording()
                tStart = proc.envTotal

                val block = ShortArray(blockSize)
                var filled = 0
                var idx = 0
                var prevRms = 0.0

                while (idx < totalSamples && measuring) {
                    val n = record.read(block, filled, blockSize - filled)
                    if (n < 0) break
                    if (n == 0) continue
                    filled += n

                    if (filled >= blockSize) {
                        var sum = 0.0
                        for (i in 0 until blockSize) {
                            val s = block[i] / 32768.0
                            sum += s * s
                        }
                        val rms = sqrt(sum / blockSize)
                        val onset = max(0.0, rms - prevRms)
                        prevRms = rms
                        micEnv[idx++] = onset.toFloat()
                        filled = 0

                        if (idx % 10 == 0 || idx == totalSamples) {
                            val progress = idx * 100 / totalSamples
                            val sec = idx / 50
                            safeUi {
                                progressMeasure.progress = progress
                                tvCountdown.text = "جارٍ الفحص... $sec/$duration ث"
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                setStatus("خطأ في الميكروفون: ${e.message}", "#EF4444")
            } finally {
                try { record?.stop() } catch (_: Exception) {}
                try { record?.release() } catch (_: Exception) {}
                proc.measurementMute = false

                safeUi {
                    measuring = false
                    btnMeasure.isEnabled = true
                    btnMeasure.alpha = 1f
                    progressMeasure.visibility = View.GONE
                    tvCountdown.visibility = View.GONE
                }

                if (!measurementAborted) {
                    try {
                        val result = computeDelay(micEnv, tStart, proc)
                        safeUi {
                            if (result != null) {
                                val lagSec = result.lagSamples / 50.0
                                pendingConfidence = result.confidence
                                if (lagSec < -0.1) {
                                    pendingNegativeTvAhead = abs(lagSec)
                                    pendingDelaySeconds = null
                                    setStatus(
                                        "التلفاز متقدم عن الراديو بـ ${String.format(Locale.US, "%.1f", abs(lagSec))} ث. اضغط زامن الآن",
                                        "#F59E0B"
                                    )
                                } else {
                                    pendingNegativeTvAhead = null
                                    pendingDelaySeconds = lagSec.coerceIn(0.0, 120.0)
                                    setStatus(
                                        "تم الفحص: تأخير الصورة ${String.format(Locale.US, "%.2f", pendingDelaySeconds)} ث (الثقة ${result.confidence}%)",
                                        "#10B981"
                                    )
                                }
                            } else {
                                setStatus("لم يتم العثور على تطابق واضح. جرّب مدة أطول أو تأكد من صوت التلفاز", "#F59E0B")
                            }
                            updateLiveUi()
                        }
                    } catch (e: Exception) {
                        setStatus("خطأ في الحساب: ${e.message}", "#EF4444")
                    }
                }
            }
        }

        measureThread = Thread(runnable).apply {
            isDaemon = true
            start()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startMeasurement()
            } else {
                setStatus("تم رفض إذن الميكروفون", "#EF4444")
            }
        }
    }

    private data class DelayResult(val lagSamples: Double, val confidence: Int)

    private fun computeDelay(mic: FloatArray, tStart: Long, proc: DelayAudioProcessor): DelayResult? {
        val m = mic.size
        if (m < 100) return null
        val maxLag = min(6000, tStart.toInt().coerceAtLeast(0))
        val minLag = -250
        if (maxLag < 0) return null

        val rangeStart = tStart - maxLag
        val count = (maxLag + m + 250)
        if (count <= 0) return null
        val radio = proc.getEnvRange(rangeStart, count)

        var micMean = 0.0
        for (v in mic) micMean += v
        micMean /= m
        var micVar = 0.0
        for (v in mic) {
            val d = v - micMean
            micVar += d * d
        }
        val micStd = sqrt(micVar)
        if (micStd < 1e-6) return null

        val lagCount = maxLag - minLag + 1
        val corrs = DoubleArray(lagCount) { -2.0 }
        var bestIdx = -1
        var bestCorr = -2.0

        for (lag in minLag..maxLag) {
            var sumR = 0.0
            var sumRR = 0.0
            var sumMR = 0.0
            var valid = 0

            for (k in 0 until m) {
                val ri = (maxLag + k - lag)
                if (ri < 0 || ri >= count) continue
                val r = radio[ri]
                if (r.isNaN()) continue
                sumR += r
                sumRR += r * r
                sumMR += (mic[k] - micMean) * r
                valid++
            }

            if (valid < m * 0.6) continue
            val rMean = sumR / valid
            val rVar = sumRR - valid * rMean * rMean
            if (rVar < 1e-9) continue

            val corr = sumMR / (micStd * sqrt(rVar))
            val idx = lag - minLag
            corrs[idx] = corr

            if (corr > bestCorr) {
                bestCorr = corr
                bestIdx = idx
            }
        }

        if (bestIdx < 0 || bestCorr <= 0.05) return null

        var refinedLag = (bestIdx + minLag).toDouble()
        if (bestIdx > 0 && bestIdx < corrs.size - 1) {
            val y1 = corrs[bestIdx - 1]
            val y2 = corrs[bestIdx]
            val y3 = corrs[bestIdx + 1]
            val denom = y1 - 2 * y2 + y3
            if (abs(denom) > 1e-9) {
                val delta = 0.5 * (y1 - y3) / denom
                if (abs(delta) < 1.0) refinedLag += delta
            }
        }
        refinedLag = refinedLag.coerceIn(minLag.toDouble(), maxLag.toDouble())

        val confidence = ((bestCorr * bestCorr) * 100).roundToInt().coerceIn(1, 99)
        return DelayResult(refinedLag, confidence)
    }
}

// =================================================================================================
// Delay Audio Processor — 125 s PCM ring buffer, 16-bit internal storage, mute, live envelope.
// =================================================================================================

@UnstableApi
class DelayAudioProcessor : BaseAudioProcessor() {

    companion object {
        private const val ENV_CAPACITY = 9000 // 180 s at 50 Hz
    }

    @Volatile var delaySeconds: Double = 0.0
    @Volatile var muted: Boolean = false
    @Volatile var measurementMute: Boolean = false

    @Volatile private var sampleRate: Int = 48000
    @Volatile private var channelCount: Int = 2
    @Volatile private var ringCapacityFrames: Int = 0
    @Volatile private var totalFrames: Long = 0L

    private var inputEncoding: Int = C.ENCODING_PCM_16BIT
    private var ring: ShortArray = ShortArray(0)

    private val envLock = Any()
    private val envHistory = FloatArray(ENV_CAPACITY)
    private var envWrite = 0
    private var envTotalCount = 0L
    private var envAccum = 0.0
    private var envCount = 0
    private var prevEnv = 0.0

    val envTotal: Long
        get() = synchronized(envLock) { envTotalCount }

    val bufferedSeconds: Double
        get() {
            val sr = sampleRate.coerceAtLeast(1)
            val cap = ringCapacityFrames.toLong()
            return min(totalFrames, cap).toDouble() / sr
        }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val sr = inputAudioFormat.sampleRate
        val ch = inputAudioFormat.channelCount
        if (sr <= 0 || ch <= 0) return AudioFormat.NOT_SET

        inputEncoding = inputAudioFormat.encoding
        sampleRate = sr
        channelCount = ch

        val framesNeeded = sr * 125
        val shortsNeeded = framesNeeded * ch
        if (ring.size != shortsNeeded) {
            ring = ShortArray(shortsNeeded)
            ringCapacityFrames = framesNeeded
            totalFrames = 0L
        }

        return AudioFormat(sr, ch, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (ringCapacityFrames <= 0 || ring.isEmpty()) return
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val ch = channelCount
        val bytesPerSample = if (inputEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val bytesPerFrame = ch * bytesPerSample
        val frames = remaining / bytesPerFrame
        if (frames <= 0) return

        val outSize = frames * ch * 2
        val out = replaceOutputBuffer(outSize)
        val delayFrames = (delaySeconds * sampleRate).toLong()
        val mute = muted || measurementMute

        val shorts = ShortArray(frames * ch)
        if (inputEncoding == C.ENCODING_PCM_FLOAT) {
            val fb = inputBuffer.asFloatBuffer()
            val n = minOf(fb.remaining(), shorts.size)
            for (i in 0 until n) {
                shorts[i] = (fb.get(i).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
        } else {
            val sb = inputBuffer.asShortBuffer()
            val n = minOf(sb.remaining(), shorts.size)
            for (i in 0 until n) shorts[i] = sb.get(i)
        }
        inputBuffer.position(inputBuffer.position() + remaining)

        val cap = ringCapacityFrames
        val baseTotal = totalFrames

        for (f in 0 until frames) {
            val wf = ((baseTotal + f) % cap).toInt()
            val base = wf * ch
            val ib = f * ch
            for (c in 0 until ch) ring[base + c] = shorts[ib + c]
        }

        processEnvelope(shorts, frames)

        val totalAfter = baseTotal + frames
        val oldestValid = max(0L, totalAfter - cap)

        for (f in 0 until frames) {
            val globalRead = baseTotal + f - delayFrames
            if (mute || globalRead < oldestValid || globalRead >= totalAfter) {
                for (c in 0 until ch) out.putShort(0)
            } else {
                val rf = (globalRead % cap).toInt()
                val base = rf * ch
                for (c in 0 until ch) out.putShort(ring[base + c])
            }
        }

        out.flip()
        totalFrames = totalAfter
    }

    override fun onFlush() {
        totalFrames = 0L
        envAccum = 0.0
        envCount = 0
        prevEnv = 0.0
        synchronized(envLock) {
            envTotalCount = 0L
            envWrite = 0
            java.util.Arrays.fill(envHistory, 0f)
        }
    }

    private fun processEnvelope(shorts: ShortArray, frames: Int) {
        val ch = channelCount
        val blockSize = max(1, sampleRate / 50)
        for (f in 0 until frames) {
            var sum = 0.0
            val base = f * ch
            for (c in 0 until ch) {
                val s = shorts[base + c] / 32768.0
                sum += s * s
            }
            envAccum += sum / ch
            envCount++
            if (envCount >= blockSize) {
                val rms = sqrt(envAccum / envCount)
                val onset = max(0.0, rms - prevEnv)
                prevEnv = rms
                synchronized(envLock) {
                    envHistory[envWrite] = onset.toFloat()
                    envWrite = (envWrite + 1) % ENV_CAPACITY
                    envTotalCount++
                }
                envAccum = 0.0
                envCount = 0
            }
        }
    }

    fun getEnvRange(startLogical: Long, count: Int): FloatArray {
        val out = FloatArray(count)
        synchronized(envLock) {
            val oldest = max(0L, envTotalCount - ENV_CAPACITY)
            for (i in 0 until count) {
                val idx = startLogical + i
                if (idx in oldest until envTotalCount) {
                    out[i] = envHistory[(idx % ENV_CAPACITY).toInt()]
                } else {
                    out[i] = Float.NaN
                }
            }
        }
        return out
    }
}

// =================================================================================================
// Local TCP relay — converts ICY/Shoutcast streams to standard HTTP for ExoPlayer.
// =================================================================================================

private class StreamRelay(
    private val remoteUrl: String,
    private val onLog: (String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    @Volatile private var running = false
    @Volatile private var currentRemote: RemoteStream? = null
    @Volatile private var currentClient: Socket? = null

    fun start(): Int {
        val ss = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        running = true
        thread = Thread {
            while (running) {
                try {
                    val client = ss.accept()
                    currentClient = client
                    Thread { handleClient(client) }.apply {
                        isDaemon = true
                        start()
                    }
                } catch (e: Exception) {
                    if (running) onLog("accept: ${e.message}")
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        return ss.localPort
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        try { currentRemote?.close() } catch (_: Exception) {}
        currentRemote = null
        try { currentClient?.close() } catch (_: Exception) {}
        currentClient = null
        thread?.interrupt()
        thread = null
    }

    private fun handleClient(client: Socket) {
        var remote: RemoteStream? = null
        try {
            client.soTimeout = 15000
            val inStream = BufferedInputStream(client.getInputStream())
            while (true) {
                val line = readLine(inStream) ?: break
                if (line.isEmpty()) break
            }

            remote = openRemote()
            if (remote == null) {
                try {
                    client.getOutputStream().write(
                        "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
                    )
                } catch (_: Exception) {}
                return
            }
            currentRemote = remote

            val out = client.getOutputStream()
            val ct = remote.contentType.ifBlank { "audio/mpeg" }
            val header = "HTTP/1.1 200 OK\r\nContent-Type: $ct\r\nConnection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.flush()

            pipe(remote.input, out, remote.metaInt)
        } catch (e: Exception) {
            if (running) onLog("client: ${e.message}")
        } finally {
            try { remote?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private sealed class RemoteOpenResult {
        class Success(val stream: RemoteStream) : RemoteOpenResult()
        class Redirect(val url: URL) : RemoteOpenResult()
        object Failure : RemoteOpenResult()
    }

    private fun openRemote(): RemoteStream? {
        var url = try {
            URL(remoteUrl)
        } catch (e: Exception) {
            onLog("bad url: ${e.message}")
            return null
        }

        for (i in 0 until 6) {
            val result = if (url.protocol.equals("https", true)) openHttps(url) else openHttpRaw(url)
            when (result) {
                is RemoteOpenResult.Redirect -> url = result.url
                is RemoteOpenResult.Success -> return result.stream
                is RemoteOpenResult.Failure -> return null
            }
        }
        return null
    }

    private fun openHttpRaw(url: URL): RemoteOpenResult {
        val port = if (url.port != -1) url.port else 80
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(url.host, port), 15000)
            socket.soTimeout = 15000
            val out = socket.getOutputStream()
            val path = if (url.file.isNullOrEmpty()) "/" else url.file
            val req = "GET $path HTTP/1.0\r\n" +
                    "Host: ${url.host}\r\n" +
                    "User-Agent: VLC/3.0.18\r\n" +
                    "Icy-MetaData: 0\r\n" +
                    "Accept: */*\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(req.toByteArray(Charsets.US_ASCII))
            out.flush()

            val input = BufferedInputStream(socket.getInputStream())
            val statusLine = readLine(input) ?: run {
                socket.close()
                return RemoteOpenResult.Failure
            }
            val parts = statusLine.split(" ", limit = 3)
            val code = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val headers = readHeaders(input)

            if (code in 300..399) {
                val loc = headers["location"] ?: run {
                    socket.close()
                    return RemoteOpenResult.Failure
                }
                socket.close()
                return RemoteOpenResult.Redirect(URL(url, loc))
            }

            if (code !in 200..299) {
                socket.close()
                onLog("HTTP $code")
                return RemoteOpenResult.Failure
            }

            val ct = headers["content-type"] ?: "audio/mpeg"
            val mi = headers["icy-metaint"]?.toIntOrNull()
            return RemoteOpenResult.Success(RemoteStream(input, ct, mi) { socket.close() })
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            onLog("http: ${e.message}")
            return RemoteOpenResult.Failure
        }
    }

    private fun openHttps(url: URL): RemoteOpenResult {
        try {
            val conn = url.openConnection() as HttpsURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "VLC/3.0.18")
            conn.setRequestProperty("Icy-MetaData", "0")
            conn.connect()

            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: run {
                    conn.disconnect()
                    return RemoteOpenResult.Failure
                }
                conn.disconnect()
                return RemoteOpenResult.Redirect(URL(url, loc))
            }

            if (code !in 200..299) {
                conn.disconnect()
                onLog("HTTPS $code")
                return RemoteOpenResult.Failure
            }

            val ct = conn.contentType ?: "audio/mpeg"
            val mi = conn.getHeaderField("icy-metaint")?.toIntOrNull()
            return RemoteOpenResult.Success(RemoteStream(conn.inputStream, ct, mi) { conn.disconnect() })
        } catch (e: Exception) {
            onLog("https: ${e.message}")
            return RemoteOpenResult.Failure
        }
    }

    private fun pipe(input: InputStream, output: OutputStream, metaInt: Int?) {
        val buf = ByteArray(8192)
        try {
            if (metaInt == null || metaInt <= 0) {
                while (running) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    output.flush()
                }
            } else {
                var remaining = metaInt
                while (running) {
                    if (remaining > 0) {
                        val n = input.read(buf, 0, minOf(remaining, buf.size))
                        if (n < 0) break
                        output.write(buf, 0, n)
                        output.flush()
                        remaining -= n
                    } else {
                        val lenByte = input.read()
                        if (lenByte < 0) break
                        var metaLen = lenByte * 16
                        while (metaLen > 0) {
                            val n = input.read(buf, 0, minOf(buf.size, metaLen))
                            if (n < 0) break
                            metaLen -= n
                        }
                        remaining = metaInt
                    }
                }
            }
        } catch (e: Exception) {
            if (running) onLog("pipe: ${e.message}")
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.endsWith("\r")) sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val map = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                map[line.substring(0, idx).trim().lowercase(Locale.US)] =
                    line.substring(idx + 1).trim()
            }
        }
        return map
    }

    private class RemoteStream(
        val input: InputStream,
        val contentType: String,
        val metaInt: Int?,
        private val closeAction: (() -> Unit)? = null
    ) {
        fun close() {
            try { input.close() } catch (_: Exception) {}
            try { closeAction?.invoke() } catch (_: Exception) {}
        }
    }
}
