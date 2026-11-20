package nl.user.pockettest

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.IBinder
import android.os.Build
import android.os.PowerManager
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object TestState {
    @Volatile var onChange: (() -> Unit)? = null
    @Volatile var playbackSettings = PlaybackSettings()
    val sleepTimer = SentenceTimer()
    @Volatile var audible: AudibleSentence? = null
    @Volatile var activeFile = ""
    @Volatile var status = "Import the runtime and a voice profile."
        set(value) { field = value; onChange?.invoke() }
    @Volatile var busy = false
        set(value) { field = value; onChange?.invoke() }
    @Volatile var temperature = "Temperature monitoring starts with Play."
}

private class RunLog(context: Service, private val requestedNs: Long) {
    private val directory = File(context.getExternalFilesDir(null), "runs").apply { check(mkdirs() || isDirectory) }
    val file = File(directory, "run-${System.currentTimeMillis()}-${System.nanoTime()}.jsonl")
    @Synchronized fun event(name: String, vararg fields: Pair<String, Any>) {
        val value = JSONObject().put("event", name).put("from_play_ms", (System.nanoTime() - requestedNs) / 1e6)
        for ((key, item) in fields) value.put(key, item)
        file.appendText(value.toString() + "\n", Charsets.UTF_8)
        Log.i("PocketAudioTest", value.toString())
    }
}

private data class Block(val first: Long, val samples: FloatArray, val line: Int, val sentenceFirst: Long)

class PlaybackService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var pendingPlay: Intent? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val stopped = AtomicBoolean(false)
    private val sleepFinished = AtomicBoolean(false)
    private val seeking = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private val handleLock = Any()
    private val trackLock = Any()
    private var handle = 0L
    private var modelKey = ""
    private var positionKey = ""
    private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null
    private var wake: PowerManager.WakeLock? = null
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TestState.playbackSettings = SavedPlaybackSettings.load(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("playback", "Speech playback", NotificationManager.IMPORTANCE_LOW))
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("temperature", "Phone heat alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "test_heat_alert") {
            val posted = temperatureAlert("Test notification only. Phone temperature has not been changed. Use Stop to cancel playback.", isTest = true)
            TestState.status = if (posted) "Heat-alert test posted. Check the phone notification." else "Heat-alert test blocked by notification settings."
            Log.i("PocketAudioTest", JSONObject().put("event", "heat_alert_test")
                .put("notification_posted", posted).put("sensor_reading", false).toString())
            return START_NOT_STICKY
        }
        if (intent?.action == "stop") {
            pendingPlay = null
            cancelPlayback()
            return START_NOT_STICKY
        }
        if (intent?.action == "seek" && TestState.busy) {
            seeking.set(true)
            pendingPlay = Intent(intent).setAction("play").putExtra("from_seek", true)
            Log.i("PocketSeek", JSONObject().put("event", "seek_requested")
                .put("source_line", intent.getIntExtra("start_line", 0))
                .put("timer_deadline_ms", TestState.sleepTimer.deadline ?: JSONObject.NULL).toString())
            cancelPlayback()
            return START_NOT_STICKY
        }
        if (intent == null || intent.action !in setOf("play", "seek")) { stopSelf(startId); return START_NOT_STICKY }
        return beginPlayback(intent)
    }

    private fun beginPlayback(intent: Intent): Int {
        if (TestState.busy) return START_NOT_STICKY
        TestState.busy = true
        stopped.set(false)
        sleepFinished.set(false)
        seeking.set(false)
        if (!intent.getBooleanExtra("from_seek", false))
            TestState.sleepTimer.startDefault(SavedPlaybackSettings.loadTimerMinutes(this), System.nanoTime() / 1_000_000)
        TestState.audible = null
        startForeground(1, notification())
        val textFile = intent.getStringExtra("text_file").orEmpty()
        TestState.activeFile = textFile
        val startLine = intent.getIntExtra("start_line", 0)
        val profile = intent.getStringExtra("profile").orEmpty()
        val requested = intent.getLongExtra("requested_ns", System.nanoTime())
        val manager = getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
                if (change != AudioManager.AUDIOFOCUS_GAIN) cancelPlayback()
            }.build()
        focus = request
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            TestState.status = "ERROR: Audio focus was not granted"
            TestState.busy = false
            focus = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            return START_NOT_STICKY
        }
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketAudioTest:playback").also { it.acquire(2 * 60 * 60 * 1000L) }
        worker.execute {
            try {
                positionKey = BookPositions.key(File(filesDir, textFile))
                PlaybackText.openAt(filesDir, textFile, startLine).use { reader ->
                    val lines = reader.lineSequence().iterator()
                    if (lines.hasNext()) play(lines.asSequence(), File(profile), requested, startLine)
                    else TestState.status = "End of file. Choose a lower percentage."
                }
            }
            catch (error: Exception) {
                TestState.status = "ERROR: ${error.message}"
                Log.e("PocketAudioTest", "Playback failed", error)
            } finally {
                manager.abandonAudioFocusRequest(request)
                if (wake?.isHeld == true) wake?.release()
                wake = null
                focus = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                main.post {
                    if (!destroyed.get()) {
                        TestState.busy = false
                        val next = pendingPlay
                        pendingPlay = null
                        if (next != null) {
                            TestState.sleepTimer.retarget()
                            beginPlayback(next)
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, PlaybackService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "playback").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Pocket audio test").setContentText("Speech playback is active")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }

    private fun cancelPlayback() {
        stopped.set(true)
        synchronized(handleLock) { if (handle != 0L) PocketNative.stop(handle) }
        synchronized(trackLock) { track?.pause(); track?.flush() }
        TestState.status = "Stopping playback"
    }

    private fun temperatureAlert(message: String, isTest: Boolean = false): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val notifications = getSystemService(NotificationManager::class.java)
        if (!notifications.areNotificationsEnabled() || notifications.getNotificationChannel("temperature").importance == NotificationManager.IMPORTANCE_NONE) return false
        val stop = PendingIntent.getService(this, 1, Intent(this, PlaybackService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "temperature")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(if (isTest) "Test: Phone heat alert" else "Phone heat alert")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        notifications.notify(2, notification)
        return true
    }

    private fun play(lines: Sequence<String>, profile: File, requested: Long, startLine: Int) {
        val log = RunLog(this, requested)
        val notifications = getSystemService(NotificationManager::class.java)
        var heatAlertActive = false
        val temperature = TemperatureMonitor(this, onReading = { reading ->
            TestState.temperature = (if (reading.shouldWarn) "HEAT ALERT: " else "Last reading: ") + reading.description
            log.event("temperature", "battery_celsius" to (reading.batteryCelsius ?: JSONObject.NULL),
                "thermal_status" to (reading.thermalStatus ?: JSONObject.NULL),
                "thermal_status_name" to reading.thermalLabel, "battery_overheated" to (reading.batteryOverheated ?: JSONObject.NULL),
                "heat_alert" to reading.shouldWarn)
            if (reading.shouldWarn && !heatAlertActive) {
                temperatureAlert(reading.description + ". Stop playback and let the phone cool.")
                log.event("heat_alert", "message" to reading.description,
                    "notifications_enabled" to notifications.areNotificationsEnabled(),
                    "channel_importance" to notifications.getNotificationChannel("temperature").importance)
            } else if (!reading.shouldWarn && heatAlertActive) {
                notifications.cancel(2)
            }
            heatAlertActive = reading.shouldWarn
        }, onError = { error ->
            TestState.temperature = "ERROR: Temperature monitoring failed: ${error.message}"
            Log.e("PocketAudioTest", "Temperature monitoring failed", error)
            cancelPlayback()
            log.event("temperature_error", "message" to (error.message ?: error.javaClass.name))
        })
        try {
            log.event("start", "first_audible_speech" to "not verified", "profile_directory" to profile.name,
                "source_start_line" to startLine,
                "network_permission" to false, "queue_blocks" to 8, "max_block_samples" to 4096)
            notifications.cancel(2)
            TestState.temperature = "Waiting for the first temperature reading."
            temperature.start()
            val data = LocalData(this)
            val voice = data.validateVoice(profile)
            val config = File(data.runtime, data.configName)
            val int8 = SavedPlaybackSettings.loadInt8(this)
            val settings = JSONObject(voice.settings).put("quantize", int8).toString()
            val key = config.absolutePath + "\n" + settings
            val reused = synchronized(handleLock) { handle != 0L && key == modelKey }
            if (!reused) {
                TestState.status = "Checking runtime files"
                data.verifyRuntime()
                if (stopped.get()) return
                synchronized(handleLock) {
                    if (handle != 0L) { PocketNative.close(handle); handle = 0 }
                }
                TestState.status = "Loading model"
                val start = System.nanoTime()
                val loaded = PocketNative.create(config.absolutePath.encodeToByteArray(), settings.encodeToByteArray())
                synchronized(handleLock) { handle = loaded; modelKey = key }
                log.event("model_initialized", "model_initialization_ms" to (System.nanoTime() - start) / 1e6)
            }
            log.event("model_ready", "model_reused" to reused, "speaker_sha256" to voice.manifest.getJSONObject("speaker").getString("sha256"), "settings" to voice.manifest.getJSONObject("settings"), "int8" to int8)
            if (stopped.get()) return
            PocketNative.selectVoice(handle, voice.speaker.absolutePath.encodeToByteArray())
            val rate = PocketNative.sampleRate(handle)
            stream(lines, rate, log, requested, startLine)
            TestState.status = when {
                stopped.get() -> "Playback stopped. Log: ${log.file.name}"
                sleepFinished.get() -> "Sleep timer finished. Log: ${log.file.name}"
                else -> "Playback complete. Log: ${log.file.name}"
            }
        } catch (error: Exception) {
            log.event("error", "message" to (error.message ?: error.javaClass.name))
            throw error
        } finally {
            temperature.close()
            log.event("end", "cancelled" to stopped.get(), "sleep_timer_finished" to sleepFinished.get())
            if (sleepFinished.get() && !seeking.get()) TestState.sleepTimer.set(0, System.nanoTime() / 1_000_000)
        }
    }

    private fun stream(lines: Sequence<String>, rate: Int, log: RunLog, requested: Long, startLine: Int) {
        val queue = ArrayBlockingQueue<Block>(8)
        val timeline = SentenceTimeline()
        val finished = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val generationStart = System.nanoTime()
        val consumer = Thread({
            try { consume(queue, finished, rate, log, requested, timeline) }
            catch (error: Exception) {
                failure.set(error)
                stopped.set(true)
                synchronized(handleLock) { if (handle != 0L) PocketNative.stop(handle) }
            }
        }, "pocket-playback")
        consumer.start()
        var total = 0L
        var first = true
        try {
            for ((index, line) in lines.withIndex()) {
                if (stopped.get() || sleepFinished.get()) break
                val order = AudioOrder()
                val base = total
                val sourceLine = startLine + index
                timeline.start(sourceLine, base)
                log.event("line_start", "line" to index, "first_sample" to base)
                val result = PocketNative.generate(handle, line.encodeToByteArray(), AudioSink { samples, offset ->
                    if (stopped.get() || sleepFinished.get()) 1
                    else {
                        order.accept(offset, samples.size)
                        if (first) { first = false; log.event("first_generated_audio", "first_audio_ms" to (System.nanoTime() - requested) / 1e6) }
                        val block = Block(base + offset, samples, sourceLine, base)
                        var accepted = false
                        while (!stopped.get() && !sleepFinished.get() && !accepted) accepted = queue.offer(block, 50, TimeUnit.MILLISECONDS)
                        if (accepted) 0 else 1
                    }
                })
                total += order.nextSample
                timeline.finish(sourceLine, total)
                log.event("line_end", "line" to index, "end_sample" to total, "native_status" to result)
                if (result == 1) break
            }
        } catch (error: Exception) {
            stopped.set(true)
            synchronized(handleLock) { if (handle != 0L) PocketNative.stop(handle) }
            throw error
        } finally {
            val generationWallMs = (System.nanoTime() - generationStart) / 1e6
            finished.set(true)
            consumer.join()
            log.event("generation_finished", "generation_wall_ms_including_backpressure" to generationWallMs,
                "generated_samples" to total, "generated_audio_ms" to total * 1000.0 / rate)
            log.event("generation_end", "generated_samples" to total, "sample_rate" to rate,
                "generation_and_drain_wall_ms" to (System.nanoTime() - generationStart) / 1e6)
        }
        failure.get()?.let { throw IllegalStateException("Audio output failed", it) }
    }

    private fun consume(queue: ArrayBlockingQueue<Block>, finished: AtomicBoolean, rate: Int, log: RunLog, requested: Long, timeline: SentenceTimeline) {
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        require(minimum > 0) { "AudioTrack does not support this format" }
        val output = AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum * 2, rate * 4 * 2)).build()
        if (output.state != AudioTrack.STATE_INITIALIZED) {
            output.release()
            error("AudioTrack initialization failed")
        }
        synchronized(trackLock) { track = output }
        var written = 0L
        var playing = false
        var appliedSettings: PlaybackSettings? = null
        var presentationSettingsChanged = false
        fun applySettings() {
            val settings = TestState.playbackSettings
            if (settings == appliedSettings) return
            output.playbackParams = PlaybackParams().allowDefaults()
                .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL)
                .setSpeed(settings.speed).setPitch(settings.pitch)
            val actual = output.playbackParams
            check(kotlin.math.abs(actual.speed - settings.speed) < 0.001f &&
                kotlin.math.abs(actual.pitch - settings.pitch) < 0.001f) { "Playback settings were not applied" }
            appliedSettings = settings
            if (settings != PlaybackSettings()) presentationSettingsChanged = true
            log.event("playback_settings", "speed" to actual.speed, "pitch" to actual.pitch,
                "pitch_semitones" to settings.pitchSemitones,
                "played_samples" to (output.playbackHeadPosition.toLong() and 0xffffffffL))
        }
        var presented = false
        var previousUnderruns = output.underrunCount
        var maxUnderruns = 0
        var blockPending = false
        val observations = mutableMapOf<String, Int>()
        val position = PlayedPosition()
        var lastReport = 0L
        var lastProgressNs = System.nanoTime()
        var lastHead = 0L
        var lastSentence = -1
        var timerGuards = 0
        var guardActive = false
        fun finishForTimer() {
            sleepFinished.set(true)
            queue.clear()
            synchronized(handleLock) { if (handle != 0L) PocketNative.stop(handle) }
        }
        fun report(): Long {
            synchronized(trackLock) { if (playing && !stopped.get()) applySettings() }
            val now = System.nanoTime()
            // Flush can reset the position. Retain the last sample after cancellation.
            val (head, cancelled, underruns) = synchronized(trackLock) {
                val cancelled = stopped.get()
                Triple(if (cancelled) lastHead else position.update(output.playbackHeadPosition),
                    cancelled, output.underrunCount)
            }
            if (head > lastHead) { lastProgressNs = now; lastHead = head }
            val audible = timeline.audible(head)
            if (audible != null && audible.line != lastSentence) {
                lastSentence = audible.line
                TestState.audible = AudibleSentence(TestState.activeFile, audible.line)
                BookPositions.save(filesDir, positionKey, TestState.activeFile, audible.line)
                log.event("audible_sentence", "source_line" to audible.line, "first_sample" to audible.first,
                    "played_samples" to head)
            }
            if (TestState.sleepTimer.observe(now / 1_000_000, audible?.line)) {
                log.event("sleep_timer_expired", "source_line" to (audible?.line ?: -1),
                    "sentence_end_sample" to (audible?.end ?: JSONObject.NULL),
                    "played_samples" to head, "written_samples" to written)
                if (audible?.end != null) check(written <= requireNotNull(audible.end)) { "Sleep timer has later sentence audio in AudioTrack" }
            }
            if (playing && !stopped.get() && written > head && now - lastProgressNs > 30_000_000_000L) error("Audio playback position did not advance for 30 seconds")
            if (underruns > previousUnderruns) {
                val delta = underruns - previousUnderruns
                maxUnderruns += delta
                val generationFinished = finished.get()
                val queueEmpty = queue.isEmpty()
                val observation = underrunObservation(cancelled, generationFinished, queueEmpty,
                    blockPending, head, written)
                observations[observation] = observations.getOrDefault(observation, 0) + delta
                log.event("underrun", "count" to maxUnderruns, "raw_count" to underruns,
                    "previous_raw_count" to previousUnderruns, "delta" to delta,
                    "observation" to observation, "event_timing" to "not verified",
                    "audible_interruption" to "not verified", "cancelled" to cancelled,
                    "generation_finished" to generationFinished, "queue_empty" to queueEmpty,
                    "block_pending" to blockPending, "played_samples" to head, "written_samples" to written)
            }
            previousUnderruns = underruns
            if (!presented && head > 0) {
                presented = true
                val timestamp = AudioTimestamp()
                if (!presentationSettingsChanged && output.getTimestamp(timestamp)) {
                    val estimate = timestamp.nanoTime - (timestamp.framePosition * 1e9 / rate).toLong()
                    log.event("first_presentation_estimate", "play_to_first_presentation_estimate_ms" to (estimate - requested) / 1e6, "first_audible_speech" to "not verified")
                } else log.event("first_played_position", "played_samples" to head, "first_audible_speech" to "not verified")
            }
            if (now - lastReport >= 1_000_000_000) {
                lastReport = now
                log.event("position", "played_samples" to head, "written_samples" to written, "queue_blocks" to queue.size,
                    "java_heap_bytes" to Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(), "process_pss_kib" to Debug.getPss())
            }
            return head
        }
        try {
            TestState.status = "Generating speech"
            playback@ while (!stopped.get()) {
                report()
                if (TestState.sleepTimer.expired && TestState.sleepTimer.targetLine == -1) {
                    finishForTimer()
                    break
                }
                val block = queue.poll(50, TimeUnit.MILLISECONDS)
                if (block == null) {
                    report()
                    if (finished.get() && queue.isEmpty()) break
                    continue
                }
                require(block.first == written) { "Playback queue is out of order" }
                blockPending = true
                var offset = 0
                while (offset < block.samples.size && !stopped.get()) {
                    val head = report()
                    if (TestState.sleepTimer.expired && block.line > (TestState.sleepTimer.targetLine ?: -1)) {
                        finishForTimer()
                        break@playback
                    }
                    // Use the slowest speed to protect the timer boundary during live changes.
                    if (TestState.sleepTimer.holdFuture(System.nanoTime() / 1_000_000, head, block.sentenceFirst,
                            (rate * PlaybackSettings.MIN_SPEED).toInt())) {
                        if (!guardActive) {
                            timerGuards++
                            log.event("sleep_timer_boundary_wait", "next_source_line" to block.line)
                        }
                        guardActive = true
                        Thread.sleep(5)
                        continue
                    }
                    guardActive = false
                    val count = synchronized(trackLock) {
                        if (stopped.get()) 0 else output.write(block.samples, offset, block.samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                    }
                    check(count >= 0) { "AudioTrack write failed: $count" }
                    offset += count
                    written += count
                    if (!playing && count > 0) {
                        synchronized(trackLock) { if (!stopped.get()) { applySettings(); output.play() } }
                        playing = true
                        lastProgressNs = System.nanoTime()
                        TestState.status = "Playing speech"
                    }
                    report()
                    if (count == 0) Thread.sleep(10)
                }
                blockPending = false
            }
            var tailSilence = false
            while (!stopped.get() && report() < written) {
                // Some phones start a stream only when its buffer is full. Silence fills a stalled tail.
                // The silence is not counted in written, so sentence and timer positions stay exact.
                if (!tailSilence && System.nanoTime() - lastProgressNs > 300_000_000L) {
                    tailSilence = true
                    val silence = FloatArray(output.bufferSizeInFrames)
                    val count = synchronized(trackLock) {
                        if (stopped.get()) 0 else output.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
                    }
                    log.event("tail_silence", "samples" to count, "played_samples" to lastHead, "written_samples" to written)
                }
                Thread.sleep(20)
            }
            if (TestState.sleepTimer.expired) sleepFinished.set(true)
            log.event("audio_end", "played_samples" to report(), "written_samples" to written,
                "underruns" to maxUnderruns, "raw_underruns" to previousUnderruns,
                "underrun_observations" to JSONObject(observations.toMap()),
                "played_samples_exact" to !stopped.get(), "sleep_timer_boundary_waits" to timerGuards)
        } finally {
            synchronized(trackLock) {
                track = null
                output.pause()
                output.flush()
                output.release()
            }
        }
    }

    override fun onDestroy() {
        pendingPlay = null
        destroyed.set(true)
        TestState.busy = true
        cancelPlayback()
        worker.execute {
            try {
                synchronized(handleLock) { if (handle != 0L) { PocketNative.close(handle); handle = 0 } }
                TestState.status = "Model unloaded"
            } finally { TestState.busy = false }
        }
        worker.shutdown()
        super.onDestroy()
    }
}
