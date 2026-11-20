package nl.user.pockettest

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.method.ScrollingMovementMethod
import android.provider.OpenableColumns
import android.util.Log
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var temperature: TextView
    private lateinit var selector: Spinner
    private lateinit var favoriteButton: ImageButton
    private lateinit var text: EditText
    private var voices = emptyList<Voice>()
    private val controls = mutableListOf<View>()
    private var importing = false
        set(value) { field = value; requestUpdate() }
    private var bookFile: String? = null
    private var startLine = 0
    private lateinit var percentage: EditText
    private lateinit var title: TextView
    private lateinit var previewLabel: TextView
    private lateinit var playButton: View
    private lateinit var goButton: Button
    private var selectedVoice = ""
    private val actions = linkedMapOf<String, Pair<Boolean, () -> Unit>>()
    private lateinit var timerButton: Button
    private lateinit var chapterLabel: TextView
    private lateinit var timeLabel: TextView
    private var timerDefault = 0L
    private var chapterFile: String? = null
    private var chapterList = emptyList<BookImport.Chapter>()
    private lateinit var searchButton: Button
    private lateinit var speedButton: Button
    private lateinit var pitchButton: Button
    private var document: ReaderDocument? = null
    private var documentName = ""
    private var requestedSentence: AudibleSentence? = null
    private var typedDraft: String? = null
    private var displayingPlayback = false
    private var displayFirstLine = 0
    private var displayFile: String? = null
    private var selectionPreview = false
    private var selectedTypedText: String? = null
    private var followAfterNs = 0L
    private lateinit var readerScroller: ReaderScroller
    private var observedSentence: AudibleSentence? = TestState.audible
    private var pendingSentence: AudibleSentence? = null
    private lateinit var rewindButton: Button
    private lateinit var forwardButton: Button
    private val update: Runnable = Runnable {
        if (!isDestroyed) workSchedule.update(TestState.busy, TestState.busy || importing)
    }
    private val idleClose: Runnable = Runnable {
        if (!isDestroyed) workSchedule.update(TestState.busy, TestState.busy || importing)
    }
    private val workSchedule: ActivityWorkSchedule = ActivityWorkSchedule(
        render = { renderState() },
        scheduleRefresh = { handler.postDelayed(update, 200) },
        cancelRefresh = { handler.removeCallbacks(update) },
        scheduleClose = { handler.postDelayed(idleClose, ActivityWorkSchedule.IDLE_MILLIS) },
        cancelClose = { handler.removeCallbacks(idleClose) },
        now = { android.os.SystemClock.elapsedRealtime() },
        close = {
            saveSelection()
            stopService(Intent(this, PlaybackService::class.java))
            finishAndRemoveTask()
        },
    )
    private val stateChanged: () -> Unit = { requestUpdate() }

    private fun requestUpdate() {
        handler.removeCallbacks(update)
        handler.post(update)
    }

    private fun renderState() {
            val compactStatus = TestState.status.substringBefore(" Log:")
            if (status.text.toString() != compactStatus) status.text = compactStatus
            if (temperature.text.toString() != TestState.temperature) temperature.text = TestState.temperature
            controls.filter { it !== playButton }.forEach { it.isEnabled = !TestState.busy && !importing }
            selector.isEnabled = !TestState.busy && !importing
            favoriteButton.isEnabled = !TestState.busy && !importing && voices.isNotEmpty()
            percentage.isEnabled = bookFile != null && !TestState.busy && !importing
            playButton.isEnabled = !importing && voices.isNotEmpty()
            playButton.alpha = if (playButton.isEnabled) 1f else 0.4f
            val playLabel = if (TestState.busy) "Pause" else "Play"
            if (playButton.contentDescription != playLabel) {
                (playButton as ImageButton).setImageResource(if (TestState.busy) android.R.drawable.ic_media_pause else R.drawable.ic_play)
                playButton.contentDescription = playLabel
            }
            rewindButton.isEnabled = !importing && document != null
            forwardButton.isEnabled = rewindButton.isEnabled
            goButton.isEnabled = percentage.isEnabled
            text.isEnabled = bookFile != null || displayingPlayback || (!TestState.busy && !importing)
            searchButton.isEnabled = bookFile != null && !TestState.busy && !importing
            updatePlaybackLabels()
            val timer = TestState.sleepTimer
            val remaining = timer.remaining(System.nanoTime() / 1_000_000)
            val timerLabel = when {
                timer.expired -> getString(R.string.finishing_sentence)
                remaining == null && timerDefault > 0 -> getString(R.string.timer_default, timerDefault)
                remaining == null -> getString(R.string.timer_off)
                else -> String.format(java.util.Locale.US, "%d:%02d", (remaining + 999) / 60000, ((remaining + 999) / 1000) % 60)
            }
            if (timerButton.text.toString() != timerLabel) timerButton.text = timerLabel
            val audible = TestState.audible
            if (!TestState.busy || audible == pendingSentence) pendingSentence = null
            if (audible != null && pendingSentence == null && !importing && audible != observedSentence && audible.file == displayFile) {
                observedSentence = audible
                startLine = audible.line
                percentage.setText(String.format(java.util.Locale.US, "%.2f", document?.percent(startLine) ?: 0.0))
            }
            updatePositionMarker()
            updateChapterLabel()
            if (audible != null && pendingSentence == null && TestState.busy && !importing && System.nanoTime() >= followAfterNs && getPreferences(MODE_PRIVATE).getInt("follow_mode", 0) != 2 && audible != requestedSentence) {
                followSentence(audible)
            }
            if (!TestState.busy && displayingPlayback && bookFile == null && !selectionPreview && !importing) {
                text.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                text.setText(typedDraft.orEmpty())
                displayingPlayback = false
                displayFile = null
                displayFirstLine = 0
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TestState.playbackSettings = SavedPlaybackSettings.load(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
            setBackgroundColor(getColor(R.color.player_background))
        }
        setContentView(layout)
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        title = TextView(this).apply {
            text = getString(R.string.player_title)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            ellipsize = TextUtils.TruncateAt.END
        }
        heading.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        val menu = Button(this).apply {
            text = getString(R.string.menu)
            isAllCaps = false
            contentDescription = getString(R.string.document_menu)
            setOnClickListener { showMenu(this) }
        }
        heading.addView(menu, LinearLayout.LayoutParams(dp(72), dp(48)))
        layout.addView(heading)
        timerDefault = SavedPlaybackSettings.loadTimerMinutes(this)
        chapterLabel = TextView(this).apply {
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(getColor(R.color.player_accent))
            visibility = View.GONE
        }
        timeLabel = TextView(this).apply {
            textSize = 14f
            maxLines = 1
            contentDescription = getString(R.string.estimated_time)
            visibility = View.GONE
        }
        val chapterRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        chapterRow.addView(chapterLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        chapterRow.addView(timeLabel)
        layout.addView(chapterRow)
        timerButton = Button(this).apply {
            text = getString(R.string.timer_off)
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(getColor(R.color.player_accent))
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_timer, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            contentDescription = getString(R.string.sleep_timer)
            setOnClickListener { chooseTimer() }
        }
        val utilityRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        utilityRow.addView(timerButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        searchButton = Button(this).apply {
            text = getString(R.string.search)
            isAllCaps = false
            setOnClickListener { searchBook() }
        }
        utilityRow.addView(searchButton, LinearLayout.LayoutParams(dp(96), dp(48)))
        layout.addView(utilityRow)
        val positionRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val playerRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun button(label: String, controlled: Boolean = true, action: () -> Unit) {
            actions[label] = controlled to action
            if (label !in setOf("Play", "Stop", "Go to % position")) return
            val button: View = if (label == "Go to % position") {
                Button(this).apply {
                    text = getString(R.string.go)
                    isAllCaps = false
                    goButton = this
                    positionRow.addView(this, LinearLayout.LayoutParams(dp(64), dp(48)))
                }
            } else {
                ImageButton(this).apply {
                    contentDescription = label
                    setImageResource(if (label == "Play") R.drawable.ic_play else R.drawable.ic_stop)
                    background = getDrawable(if (label == "Play") R.drawable.player_play else R.drawable.player_stop)
                    if (label == "Play") playButton = this
                    playerRow.addView(this, LinearLayout.LayoutParams(dp(64), dp(64)).apply {
                        marginStart = dp(4); marginEnd = dp(4); topMargin = dp(8); bottomMargin = dp(8)
                    })
                }
            }
            button.setOnClickListener { action() }
            if (controlled) controls.add(button)
        }
        button("Import runtime ZIP") { choose(10) }
        button("Import voice profiles") { choose(11) }
        selector = Spinner(this)
        selector.contentDescription = getString(R.string.voice)
        val voiceRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        voiceRow.addView(TextView(this).apply { text = getString(R.string.voice); textSize = 14f })
        voiceRow.addView(selector, LinearLayout.LayoutParams(0, dp(48), 1f))
        favoriteButton = ImageButton(this).apply {
            setOnClickListener {
                if (!TestState.busy && !importing) {
                    voices.getOrNull(selector.selectedItemPosition)?.let { voice ->
                        val changed = VoiceFavorites.toggle(favoriteIds(), voice.directory.name)
                        getSharedPreferences("voice_favorites", MODE_PRIVATE).edit().putStringSet("ids", changed).apply()
                        refreshVoices()
                        saveSelection()
                    }
                }
            }
        }
        voiceRow.addView(favoriteButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateFavoriteButton()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) { updateFavoriteButton() }
        }
        layout.addView(voiceRow)
        val playbackRow = LinearLayout(this)
        speedButton = Button(this).apply {
            isAllCaps = false
            contentDescription = getString(R.string.reading_speed)
            setOnClickListener { choosePlaybackSetting(false) }
        }
        pitchButton = Button(this).apply {
            isAllCaps = false
            contentDescription = getString(R.string.voice_pitch)
            setOnClickListener { choosePlaybackSetting(true) }
        }
        playbackRow.addView(speedButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        playbackRow.addView(pitchButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        layout.addView(playbackRow)
        updatePlaybackLabels()
        previewLabel = TextView(this).apply {
            text = getString(R.string.typed_text)
            textSize = 11f
            setTextColor(getColor(R.color.player_muted))
            setOnClickListener {
                TestState.audible?.takeIf { TestState.busy && it.file == displayFile }?.let {
                    followAfterNs = 0
                    followSentence(it)
                }
            }
        }
        layout.addView(previewLabel)
        button("Open document") { choose(12) }
        button("Chapters") { chooseChapter() }
        button("Use typed text") {
            bookFile = null
            document = null
            requestedSentence = null
            typedDraft = null
            displayingPlayback = false
            selectionPreview = false
            selectedTypedText = null
            displayFile = null
            displayFirstLine = 0
            startLine = 0
            text.isEnabled = true
            text.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            text.isFocusableInTouchMode = true
            text.setText("")
            title.setText(R.string.player_title)
            previewLabel.setText(R.string.typed_text)
            TestState.status = "Enter test text, then press Play."
        }
        text = EditText(this).apply {
            textSize = getPreferences(MODE_PRIVATE).getInt("reading_font", 16).toFloat()
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(getColor(R.color.player_text))
            setBackgroundColor(getColor(R.color.player_background))
            isSaveEnabled = false
            setText("The library closes at six tonight.\nPlease return the books before the doors close.")
        }
        layout.addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        installSentenceGesture()
        positionRow.addView(TextView(this).apply {
            text = getString(R.string.start_position)
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        percentage = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setText("0")
            textSize = 14f
            contentDescription = getString(R.string.start_position)
            setSelectAllOnFocus(true)
        }
        positionRow.addView(percentage, LinearLayout.LayoutParams(dp(64), dp(48)))
        positionRow.addView(TextView(this).apply { text = "%"; textSize = 14f })
        button("Go to % position") {
            if (importing || TestState.busy) return@button
            val selectedBook = bookFile
            val value = percentage.text.toString().replace(',', '.').toDoubleOrNull()
            if (selectedBook == null) TestState.status = "Open a document first."
            else if (value == null || !value.isFinite() || value !in 0.0..100.0) TestState.status = "Enter a percentage from 0 to 100."
            else {
                importing = true
                io.execute {
                    try {
                        val position = PlaybackText.position(filesDir, selectedBook, value)
                        val indexed = ReaderDocument(File(filesDir, selectedBook))
                        runOnUiThread {
                            startLine = position.line
                            displayFirstLine = position.line
                            displayFile = selectedBook
                            requestedSentence = null
                            document = indexed; documentName = selectedBook
                            showPosition(indexed, startLine)
                            saveBookPosition(indexed, startLine)
                            TestState.status = if (position.preview.isEmpty()) "End of file. Choose a lower percentage."
                                else "Position set to ${String.format(java.util.Locale.US, "%.2f", position.percent)}%, at the start of a sentence. Press Play."
                        }
                    } catch (error: Exception) { TestState.status = "ERROR: ${error.message}" }
                    finally { runOnUiThread { importing = false } }
                }
            }
        }
        button("Play") {
            if (TestState.busy) {
                startService(Intent(this, PlaybackService::class.java).setAction("stop"))
                return@button
            }
            val voice = voices.getOrNull(selector.selectedItemPosition)
            if (voice == null) TestState.status = "Import a voice profile first."
            else if (bookFile == null && text.text.isBlank()) TestState.status = "Enter test text first."
            else {
                if (importing || TestState.busy) return@button
                val input = if (displayingPlayback) typedDraft.orEmpty() else text.text.toString()
                typedDraft = input
                val selectedBook = bookFile
                val selectedLine = if (selectedBook != null || input == selectedTypedText) startLine else 0
                selectedTypedText = input
                selectionPreview = false
                val requested = System.nanoTime()
                importing = true
                TestState.status = "Preparing playback text"
                io.execute {
                    try {
                        val name = selectedBook ?: PlaybackText.write(filesDir, input)
                        document = ReaderDocument(File(filesDir, name))
                        documentName = name
                        runOnUiThread {
                            try {
                                requestedSentence = null
                                startForegroundService(Intent(this, PlaybackService::class.java).setAction("play")
                                    .putExtra("profile", voice.directory.absolutePath).putExtra("text_file", name)
                                    .putExtra("start_line", selectedLine)
                                    .putExtra("requested_ns", requested))
                            } catch (error: Exception) { TestState.status = "ERROR: ${error.message}" }
                            finally { importing = false }
                        }
                    } catch (error: Exception) {
                        runOnUiThread { TestState.status = "ERROR: ${error.message}"; importing = false }
                    }
                }
            }
        }
        button("Stop", false) { startService(Intent(this, PlaybackService::class.java).setAction("stop")) }
        rewindButton = Button(this).apply {
            text = "−1 min"
            isAllCaps = false
            contentDescription = "Rewind approximately 1 minute"
            setOnClickListener { seekSeconds(-60) }
        }
        forwardButton = Button(this).apply {
            text = "+1 min"
            isAllCaps = false
            contentDescription = "Forward approximately 1 minute"
            setOnClickListener { seekSeconds(60) }
        }
        playerRow.addView(rewindButton, 0, LinearLayout.LayoutParams(0, dp(56), 1f))
        playerRow.addView(forwardButton, LinearLayout.LayoutParams(0, dp(56), 1f))
        button("Run interface checks") {
            importing = true
            io.execute {
                try { TestState.status = InterfaceChecks.run() }
                catch (error: Exception) { TestState.status = "FAIL: ${error.message}" }
                finally { runOnUiThread { importing = false } }
            }
        }
        button("Unload model") {
            stopService(Intent(this, PlaybackService::class.java))
            TestState.status = "Model unload requested. Wait before the next test."
        }
        button("Test heat alert", false) {
            startService(Intent(this, PlaybackService::class.java).setAction("test_heat_alert"))
        }
        status = TextView(this).apply { setTextIsSelectable(true) }
        status.textSize = 12f
        status.maxLines = 1
        status.ellipsize = TextUtils.TruncateAt.END
        status.setOnClickListener { showDetails(getString(R.string.status), TestState.status) }
        temperature = TextView(this).apply {
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(getColor(R.color.player_muted))
            setOnClickListener { showDetails(getString(R.string.temperature), TestState.temperature) }
        }
        layout.addView(positionRow)
        layout.addView(playerRow)
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            minimumHeight = dp(56)
        }
        footer.addView(status)
        footer.addView(temperature)
        layout.addView(footer)
        text.post {
            Log.i("PocketUiLayout", JSONObject()
                .put("text_height_px", text.height)
                .put("line_height_px", text.lineHeight)
                .put("visible_line_capacity", (text.height - text.compoundPaddingTop - text.compoundPaddingBottom) / text.lineHeight)
                .put("font_scale", resources.configuration.fontScale).toString())
        }
        restoreSelection(savedInstanceState)
        refreshVoices()
        TestState.onChange = stateChanged
        installBundledContent()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 20)
    }

    private fun refreshVoices() {
        try {
            val retained = voices.getOrNull(selector.selectedItemPosition)?.directory?.name ?: selectedVoice
            val favorites = favoriteIds()
            voices = VoiceFavorites.sorted(LocalData(this).voices(), favorites, { it.directory.name }, { it.name })
            selector.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, voices.map { it.name }) {
                private fun decorate(view: View, position: Int): View = view.apply {
                    val voice = voices[position]
                    val favorite = voice.directory.name in favorites
                    (this as TextView).setCompoundDrawablesWithIntrinsicBounds(
                        if (favorite) android.R.drawable.btn_star_big_on else 0, 0, 0, 0)
                    compoundDrawablePadding = dp(4)
                    contentDescription = if (favorite) getString(R.string.favorite_voice_name, voice.name) else voice.name
                }
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    decorate(super.getView(position, convertView, parent), position)
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    decorate(super.getDropDownView(position, convertView, parent), position)
            }
            val index = voices.indexOfFirst { it.directory.name == retained }
            if (index >= 0) selector.setSelection(index)
            updateFavoriteButton()
            if (voices.isNotEmpty() && TestState.status == "Import the runtime and a voice profile.")
                TestState.status = getString(R.string.ready)
        } catch (error: Exception) { TestState.status = "ERROR: ${error.message}" }
    }

    private fun favoriteIds(): Set<String> =
        getSharedPreferences("voice_favorites", MODE_PRIVATE).getStringSet("ids", emptySet()).orEmpty().toSet()

    private fun updateFavoriteButton() {
        val voice = voices.getOrNull(selector.selectedItemPosition)
        val favorite = voice != null && voice.directory.name in favoriteIds()
        favoriteButton.setImageResource(if (favorite) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off)
        favoriteButton.contentDescription = getString(if (favorite) R.string.remove_favorite else R.string.add_favorite)
        favoriteButton.isEnabled = voice != null && !TestState.busy && !importing
    }

    @Suppress("DEPRECATION")
    private fun choose(request: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            if (request == 12) putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/epub+zip", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/octet-stream", "application/zip"))
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, request == 11)
        }, request)
    }

    @Deprecated("The framework Activity result callback is required without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || requestCode !in 10..12) return
        val selected = data?.clipData
        val uris = if (requestCode == 11 && selected != null) {
            (0 until selected.itemCount).map { selected.getItemAt(it).uri }.distinct()
        } else listOfNotNull(data?.data)
        if (uris.isEmpty()) return
        val uri = uris.first()
        importing = true
        TestState.status = "Importing local files"
        io.execute {
            try {
                if (requestCode == 12) {
                    val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: error("Cannot determine the document file name")
                    val loaded = contentResolver.openInputStream(uri)?.use { BookImport.import(filesDir, it, displayName) }
                        ?: error("Cannot open document")
                    val indexed = ReaderDocument(File(filesDir, loaded.name))
                    val savedLine = (BookPositions.load(filesDir, indexed.positionKey) ?: 0).coerceIn(0, maxOf(0, indexed.lineCount - 1))
                    runOnUiThread {
                        bookFile = loaded.name
                        document = indexed; documentName = loaded.name
                        requestedSentence = null
                        typedDraft = null
                        displayingPlayback = false
                        selectionPreview = false
                        selectedTypedText = null
                        title.text = displayName
                        previewLabel.setText(R.string.text_preview)
                        startLine = savedLine
                        displayFirstLine = 0
                        displayFile = loaded.name
                        showPosition(indexed, savedLine)
                        text.isFocusable = false
                        text.isFocusableInTouchMode = false
                        text.setTextIsSelectable(false)
                        text.keyListener = null
                        text.movementMethod = ScrollingMovementMethod.getInstance()
                        TestState.status = "Document loaded: ${loaded.characters} characters. Select a voice and press Play."
                        saveSelection()
                    }
                    return@execute
                }
                val store = LocalData(this)
                if (requestCode == 10) TestState.status = store.importRuntime(uri)
                else {
                    val imported = JSONArray()
                    for ((index, selectedUri) in uris.withIndex()) {
                        val result = store.importVoice(selectedUri)
                        imported.put(result)
                        TestState.status = "Imported ${index + 1}/${uris.size} voice profiles"
                    }
                    val verified = JSONArray()
                    for (voice in store.voices()) {
                        store.validateVoice(voice.directory)
                        verified.put(JSONObject().put("name", voice.name)
                            .put("speaker_sha256", LocalData.hash(voice.speaker)))
                    }
                    val receipt = JSONObject().put("result", "PASS").put("imported", imported)
                        .put("voices", verified)
                    File(requireNotNull(getExternalFilesDir(null)), "voice-import.json")
                        .writeText(receipt.toString(2), Charsets.UTF_8)
                    TestState.status = "Imported ${uris.size} profiles. Verified ${verified.length()} available voices."
                }
            } catch (error: Exception) { TestState.status = "ERROR: ${error.message}" }
            finally { runOnUiThread { importing = false; if (requestCode != 12) refreshVoices() } }
        }
    }

    /** Installs a model and voices bundled in the APK on first start (github flavour only). */
    private fun installBundledContent() {
        importing = true
        io.execute {
            try { BundledContent.install(this)?.let { TestState.status = it } }
            catch (error: Exception) { TestState.status = "ERROR: ${error.message}" }
            finally { runOnUiThread { importing = false; refreshVoices() } }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun showDetails(label: String, message: String) {
        dialogBuilder().setTitle(label).setMessage(message)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            for (label in listOf("Open document", "Chapters", "Use typed text")) {
                menu.add(label).isEnabled = !TestState.busy && !importing
            }
            menu.add(R.string.setup)
            menu.add(R.string.text_size)
            menu.add(R.string.follow_text)
            menu.add(R.string.model_speed)
            menu.add(R.string.diagnostics)
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    getString(R.string.setup) -> showActions(getString(R.string.setup), listOf("Import runtime ZIP", "Import voice profiles", getString(R.string.remove_voice)))
                    getString(R.string.text_size) -> {
                        val sizes = listOf(14, 16, 18, 20, 22, 24)
                        dialogBuilder().setTitle(R.string.text_size)
                            .setSingleChoiceItems(sizes.map { "$it sp" }.toTypedArray(), sizes.indexOf(getPreferences(MODE_PRIVATE).getInt("reading_font", 16))) { dialog, index ->
                                getPreferences(MODE_PRIVATE).edit().putInt("reading_font", sizes[index]).apply()
                                text.textSize = sizes[index].toFloat()
                                dialog.dismiss()
                            }.setNegativeButton(android.R.string.cancel, null).show()
                    }
                    getString(R.string.follow_text) -> {
                        val modes = arrayOf(getString(R.string.highlight_sentence), getString(R.string.follow_only), getString(R.string.follow_off))
                        dialogBuilder().setTitle(R.string.follow_text)
                            .setSingleChoiceItems(modes, getPreferences(MODE_PRIVATE).getInt("follow_mode", 0)) { dialog, index ->
                                getPreferences(MODE_PRIVATE).edit().putInt("follow_mode", index).apply()
                                requestedSentence = null
                                if (index != 0) text.setText(text.text.toString())
                                dialog.dismiss()
                            }.setNegativeButton(android.R.string.cancel, null).show()
                    }
                    getString(R.string.model_speed) -> {
                        val modes = arrayOf(getString(R.string.model_float32), getString(R.string.model_int8))
                        // A message hides single-choice items in AlertDialog, so the description goes in the title area.
                        val heading = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(24), dp(20), dp(24), dp(4))
                            addView(TextView(this@MainActivity).apply {
                                setText(R.string.model_speed); textSize = 20f; setTypeface(typeface, Typeface.BOLD)
                            })
                            addView(TextView(this@MainActivity).apply {
                                setText(R.string.model_speed_description); textSize = 14f; setPadding(0, dp(8), 0, 0)
                            })
                        }
                        dialogBuilder().setCustomTitle(heading)
                            .setSingleChoiceItems(modes, if (SavedPlaybackSettings.loadInt8(this@MainActivity)) 1 else 0) { dialog, index ->
                                SavedPlaybackSettings.saveInt8(this@MainActivity, index == 1)
                                TestState.status = getString(R.string.model_speed_saved)
                                dialog.dismiss()
                            }.setNegativeButton(android.R.string.cancel, null).show()
                    }
                    getString(R.string.diagnostics) -> showActions(getString(R.string.diagnostics), listOf("Run interface checks", "Unload model", "Test heat alert", getString(R.string.status), getString(R.string.temperature), getString(R.string.profile_details)))
                    else -> runAction(item.title.toString())
                }
                true
            }
            show()
        }
    }

    private fun showActions(label: String, choices: List<String>) {
        dialogBuilder().setTitle(label).setItems(choices.toTypedArray()) { _, index ->
            when (val selected = choices[index]) {
                getString(R.string.remove_voice) -> showVoiceRemoval()
                getString(R.string.status) -> showDetails(selected, TestState.status)
                getString(R.string.temperature) -> showDetails(selected, TestState.temperature)
                getString(R.string.profile_details) -> {
                    val voice = voices.getOrNull(selector.selectedItemPosition)
                    if (voice != null) {
                        importing = true
                        io.execute {
                            try {
                                val details = voice.name + "\nSHA-256: " + LocalData.hash(voice.speaker)
                                runOnUiThread { if (!isDestroyed) showDetails(selected, details) }
                            } catch (error: Exception) { TestState.status = "ERROR: ${error.message}" }
                            finally { runOnUiThread { importing = false } }
                        }
                    } else TestState.status = "Import a voice profile first."
                }
                else -> runAction(selected)
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun runAction(label: String) {
        val (controlled, action) = actions.getValue(label)
        if (!controlled || (!TestState.busy && !importing)) action()
        else TestState.status = getString(R.string.stop_before_change)
    }

    private fun showVoiceRemoval() {
        if (TestState.busy || importing) {
            TestState.status = getString(R.string.stop_before_change)
            return
        }
        val available = voices.toList()
        if (available.isEmpty()) {
            TestState.status = "No voice profiles to remove."
            return
        }
        dialogBuilder().setTitle(R.string.remove_voice)
            .setItems(available.map { it.name }.toTypedArray()) { _, index ->
                val voice = available[index]
                dialogBuilder().setTitle(R.string.remove_voice)
                    .setMessage("Remove ${voice.name}? The profile will move to the app's Trash.")
                    .setPositiveButton(R.string.remove_voice) { _, _ ->
                        if (TestState.busy || importing) {
                            TestState.status = getString(R.string.stop_before_change)
                        } else {
                            importing = true
                            io.execute {
                                try {
                                    LocalData(this).removeVoice(voice)
                                    runOnUiThread {
                                        refreshVoices()
                                        saveSelection()
                                        TestState.status = "Removed ${voice.name}."
                                    }
                                } catch (error: Exception) {
                                    TestState.status = "ERROR: ${error.message}"
                                } finally { runOnUiThread { importing = false } }
                            }
                        }
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun installSentenceGesture() {
        readerScroller = ReaderScroller(text,
            readOnly = { displayFile != null && (bookFile != null || displayingPlayback) },
            browsing = { active ->
                followAfterNs = if (active) Long.MAX_VALUE else System.nanoTime() + 400_000_000L
                if (active) requestedSentence = TestState.audible
            },
            selected = { offset, pixels -> selectSentence(offset, viewportPixels = pixels) },
            doubleTap = { offset -> if (!importing) selectSentence(offset) })
        readerScroller.install()
    }

    private fun updatePositionMarker() {
        val indexed = document ?: return
        if (displayFile != indexed.file.name) return
        val audible = TestState.audible?.takeIf { pendingSentence == null && TestState.busy && it.file == displayFile }
        val line = audible?.line ?: startLine
        val label = String.format(java.util.Locale.US, "%s %.2f%% · sentence %d",
            if (audible != null) "Playing" else "Start", indexed.percent(line), line + 1)
        if (previewLabel.text.toString() != label) previewLabel.text = label
    }

    private fun showPosition(indexed: ReaderDocument, line: Int) {
        readerScroller.cancel()
        val window = indexed.browseWindow(line)
        displayFirstLine = window.firstLine
        displayFile = indexed.file.name
        val styled = SpannableString(window.text)
        if (window.activeEnd > window.activeStart) {
            styled.setSpan(BackgroundColorSpan(getColor(R.color.player_highlight)), window.activeStart, window.activeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.setText(styled, TextView.BufferType.SPANNABLE)
        text.post {
            val layout = text.layout ?: return@post
            text.scrollTo(0, layout.getLineTop(layout.getLineForOffset(window.activeStart.coerceAtMost(text.length()))))
        }
        percentage.setText(String.format(java.util.Locale.US, "%.2f", indexed.percent(line)))
        updatePositionMarker()
    }

    private fun saveBookPosition(indexed: ReaderDocument, line: Int) {
        io.execute {
            try { BookPositions.save(filesDir, indexed.positionKey, indexed.file.name, line) }
            catch (error: Exception) { TestState.status = "ERROR: Cannot save book position: ${error.message}" }
        }
    }

    private fun seekSeconds(seconds: Int) {
        if (importing) return
        val indexed = document ?: return
        val line = pendingSentence?.line ?: TestState.audible?.takeIf { TestState.busy && it.file == indexed.file.name }?.line ?: startLine
        selectSentence(0, indexed.seekSeconds(line, seconds, TestState.playbackSettings.speed))
    }

    private fun selectSentence(offset: Int, targetLine: Int? = null, viewportPixels: Int? = null) {
        if (viewportPixels == null) readerScroller.cancel()
        val scrollRevision = readerScroller.revision
        if (viewportPixels != null) followAfterNs = Long.MAX_VALUE
        val rendered = text.text.toString()
        val withinSentence = if (viewportPixels != null) SentenceSelection.offsetInSentence(rendered, offset) else 0
        val firstLine = displayFirstLine
        val name = bookFile ?: if (displayingPlayback) displayFile else null
        val original = if (bookFile == null && displayingPlayback) typedDraft.orEmpty() else rendered
        val resume = TestState.busy
        val voice = voices.getOrNull(selector.selectedItemPosition)
        if (resume && voice == null) return
        importing = true
        requestedSentence = null
        io.execute {
            try {
                val file = name ?: PlaybackText.write(filesDir, original)
                val indexed = if (documentName == file && name != null) document ?: ReaderDocument(File(filesDir, file))
                    else ReaderDocument(File(filesDir, file))
                val line = targetLine ?: if (name != null) SentenceSelection.lineAt(rendered, firstLine, offset)
                    else indexed.lineForContentOffset(SentenceSelection.contentOffset(original, offset))
                val window = indexed.browseWindow(line)
                check(window.text.isNotEmpty()) { "The selected sentence is empty" }
                runOnUiThread {
                    if (isDestroyed || readerScroller.revision != scrollRevision) return@runOnUiThread
                    document = indexed; documentName = file
                    startLine = line
                    requestedSentence = TestState.audible
                    observedSentence = TestState.audible
                    pendingSentence = if (resume) AudibleSentence(file, line) else null
                    displayFirstLine = window.firstLine; displayFile = file
                    if (bookFile == null) { typedDraft = original; selectedTypedText = original }
                    selectionPreview = !resume
                    displayingPlayback = true
                    val styled = SpannableString(window.text)
                    styled.setSpan(BackgroundColorSpan(getColor(R.color.player_highlight)), window.activeStart, window.activeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text.setText(styled, TextView.BufferType.SPANNABLE)
                    text.keyListener = null
                    text.setTextIsSelectable(false)
                    text.movementMethod = ScrollingMovementMethod.getInstance()
                    text.post {
                        if (readerScroller.revision != scrollRevision) return@post
                        val anchor = (window.activeStart + withinSentence).coerceAtMost(window.activeEnd)
                        val top = text.layout?.getLineForOffset(anchor)?.let { text.layout.getLineTop(it) } ?: 0
                        text.scrollTo(0, top + (viewportPixels ?: 0))
                    }
                    percentage.setText(String.format(java.util.Locale.US, "%.2f", indexed.percent(line)))
                    previewLabel.setText(R.string.text_preview)
                    TestState.status = getString(R.string.sentence_selected)
                    Log.i("PocketSeek", JSONObject().put("event", "sentence_selected").put("source_line", line)
                        .put("resuming", resume).put("display_first_line", firstLine).put("text_offset", offset).toString())
                    if (resume) startForegroundService(Intent(this, PlaybackService::class.java).setAction("seek")
                        .putExtra("profile", requireNotNull(voice).directory.absolutePath).putExtra("text_file", file)
                        .putExtra("start_line", line).putExtra("requested_ns", System.nanoTime()))
                    else saveBookPosition(indexed, line)
                    saveSelection()
                }
            } catch (error: Exception) { TestState.status = "ERROR: Sentence selection failed: ${error.message}" }
            finally { runOnUiThread {
                importing = false
                if (readerScroller.revision == scrollRevision) followAfterNs = System.nanoTime() + 400_000_000L
            } }
        }
    }

    private fun followSentence(sentence: AudibleSentence) {
        requestedSentence = sentence
        io.execute {
            try {
                val source = if (documentName == sentence.file) document else null
                val indexed = source ?: ReaderDocument(File(filesDir, sentence.file)).also {
                    document = it; documentName = sentence.file
                }
                val window = indexed.browseWindow(sentence.line)
                runOnUiThread {
                    if (isDestroyed || requestedSentence != sentence || !TestState.busy || importing || System.nanoTime() < followAfterNs) return@runOnUiThread
                    val begin = System.nanoTime()
                    val mode = getPreferences(MODE_PRIVATE).getInt("follow_mode", 0)
                    val styled = SpannableString(window.text)
                    if (mode == 0 && window.activeEnd > window.activeStart) {
                        styled.setSpan(BackgroundColorSpan(getColor(R.color.player_highlight)), window.activeStart, window.activeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        styled.setSpan(ForegroundColorSpan(getColor(R.color.player_text)), window.activeStart, window.activeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    text.setText(styled, TextView.BufferType.SPANNABLE)
                    displayFirstLine = window.firstLine
                    displayFile = sentence.file
                    text.keyListener = null
                    text.setTextIsSelectable(false)
                    text.movementMethod = ScrollingMovementMethod.getInstance()
                    text.isEnabled = true
                    displayingPlayback = true
                    previewLabel.setText(R.string.current_sentence)
                    text.post {
                        val line = text.layout?.getLineForOffset(window.activeStart) ?: 0
                        val top = text.layout?.getLineTop(line) ?: 0
                        text.scrollTo(0, top)
                        Log.i("PocketUiFollow", JSONObject().put("source_line", sentence.line).put("mode", mode)
                            .put("ui_update_ms", (System.nanoTime() - begin) / 1e6).toString())
                    }
                }
            } catch (error: Exception) { TestState.status = "ERROR: Text following failed: ${error.message}" }
        }
    }

    private fun updatePlaybackLabels() {
        val settings = TestState.playbackSettings
        val speed = getString(R.string.speed_value, settings.speed)
        val pitch = getString(R.string.pitch_value, settings.pitchSemitones)
        if (speedButton.text.toString() != speed) speedButton.text = speed
        if (pitchButton.text.toString() != pitch) pitchButton.text = pitch
    }

    private fun choosePlaybackSetting(pitch: Boolean) {
        val settings = TestState.playbackSettings
        val values = if (pitch) (-4..4).toList() else (75..150 step 5).toList()
        val labels = values.map { value ->
            if (pitch) getString(R.string.pitch_option, value)
            else String.format(java.util.Locale.US, "%.2fx", value / 100f)
        }.toTypedArray()
        val selected = values.indexOf(if (pitch) settings.pitchSemitones else settings.speedPercent)
        dialogBuilder().setTitle(if (pitch) R.string.voice_pitch else R.string.reading_speed)
            .setSingleChoiceItems(labels, selected) { dialog, index ->
                val current = TestState.playbackSettings
                val changed = if (pitch) current.copy(pitchSemitones = values[index])
                    else current.copy(speedPercent = values[index])
                SavedPlaybackSettings.save(this, changed)
                updatePlaybackLabels()
                dialog.dismiss()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun chooseTimer() {
        val labels = arrayOf("Off", "1 min", "5 min", "15 min", "30 min", "45 min", "60 min", "Custom minutes")
        val minutes = longArrayOf(0, 1, 5, 15, 30, 45, 60)
        fun setTimer(value: Long) {
            TestState.sleepTimer.set(value * 60, System.nanoTime() / 1_000_000)
            SavedPlaybackSettings.saveTimerMinutes(this, value)
            timerDefault = value
            requestUpdate()
        }
        dialogBuilder().setTitle(R.string.sleep_timer).setItems(labels) { _, index ->
            if (index < minutes.size) setTimer(minutes[index])
            else {
                val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "1–180" }
                val dialog = dialogBuilder().setTitle(R.string.sleep_timer).setView(input)
                    .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val value = input.text.toString().toLongOrNull()
                        if (value == null || value !in 1..180) input.error = getString(R.string.timer_range)
                        else { setTimer(value); dialog.dismiss() }
                    }
                }
                dialog.show()
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun searchBook() {
        val name = bookFile ?: return
        if (TestState.busy || importing) return
        val input = EditText(this).apply { hint = getString(R.string.search_hint); setSingleLine(true) }
        val dialog = dialogBuilder().setTitle(R.string.search).setView(input)
            .setMessage(R.string.search_description)
            .setPositiveButton(R.string.search, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val query = input.text.toString()
                if (query.isBlank() || query.length > 500) { input.error = getString(R.string.search_range); return@setOnClickListener }
                dialog.dismiss()
                importing = true
                TestState.status = getString(R.string.searching)
                io.execute {
                    try {
                        val matches = PlaybackText.open(filesDir, name).use { PhraseSearch.find(it.lineSequence(), query) }
                        val indexed = ReaderDocument(File(filesDir, name))
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            TestState.status = getString(R.string.search_matches, matches.size)
                            if (matches.isEmpty()) showDetails(getString(R.string.search), getString(R.string.no_matches))
                            else dialogBuilder().setTitle(getString(R.string.search_matches, matches.size))
                                .setItems(matches.map { "${String.format(java.util.Locale.US, "%.2f", indexed.percent(it.line))}% · ${it.preview}" }.toTypedArray()) { _, index ->
                                    if (TestState.busy) return@setItems
                                    startLine = matches[index].line
                                    requestedSentence = null
                                    document = indexed; documentName = name
                                    showPosition(indexed, startLine)
                                    saveBookPosition(indexed, startLine)
                                    TestState.status = getString(R.string.match_selected)
                                }.setNegativeButton(android.R.string.cancel, null).show()
                        }
                    } catch (error: Exception) { TestState.status = "ERROR: Search failed: ${error.message}" }
                    finally { runOnUiThread { importing = false } }
                }
            }
        }
        dialog.show()
    }

    /** Show the chapter of the current reading line. Load chapter entries once per book. */
    private fun updateChapterLabel() {
        val name = bookFile
        if (name != chapterFile) {
            chapterFile = name
            chapterList = emptyList()
            if (name != null) io.execute {
                val loaded = try { BookImport.chapters(filesDir, name) } catch (_: Exception) { emptyList() }
                runOnUiThread { if (!isDestroyed && chapterFile == name) { chapterList = loaded; requestUpdate() } }
            }
        }
        val indexed = document?.takeIf { name != null && documentName == name }
        val speed = TestState.playbackSettings.speed
        val time = indexed?.let { "~${ReaderDocument.formatEstimate(it.elapsedSeconds(startLine, speed))} / ${ReaderDocument.formatEstimate(it.totalSeconds(speed))}" }
        if (time == null) { if (timeLabel.visibility != View.GONE) timeLabel.visibility = View.GONE }
        else {
            if (timeLabel.text.toString() != time) timeLabel.text = time
            if (timeLabel.visibility != View.VISIBLE) timeLabel.visibility = View.VISIBLE
        }
        val label = if (name == null) null else BookImport.chapterAt(chapterList, startLine)?.title
        if (label == null) { if (chapterLabel.visibility != View.GONE) chapterLabel.visibility = View.GONE; return }
        if (chapterLabel.text.toString() != label) chapterLabel.text = label
        if (chapterLabel.visibility != View.VISIBLE) chapterLabel.visibility = View.VISIBLE
    }

    private fun chooseChapter() {
        val name = bookFile
        if (TestState.busy || importing) return
        if (name == null) { TestState.status = "Open a document first."; return }
        importing = true
        io.execute {
            try {
                val chapters = BookImport.chapters(filesDir, name)
                val indexed = ReaderDocument(File(filesDir, name))
                runOnUiThread {
                    if (isDestroyed || bookFile != name) return@runOnUiThread
                    if (chapters.isEmpty()) showDetails("Chapters", "This document has no chapter entries.")
                    else dialogBuilder().setTitle("Chapters")
                        .setItems(chapters.map { it.title }.toTypedArray()) { _, index ->
                            if (TestState.busy || importing || bookFile != name) return@setItems
                            startLine = chapters[index].line
                            document = indexed; documentName = name
                            requestedSentence = null
                            displayingPlayback = false
                            selectionPreview = false
                            showPosition(indexed, startLine)
                            saveBookPosition(indexed, startLine)
                            previewLabel.setText(R.string.text_preview)
                            TestState.status = "Chapter selected. Press Play to read from here."
                            saveSelection()
                        }.setNegativeButton(android.R.string.cancel, null).show()
                }
            } catch (error: Exception) { TestState.status = "ERROR: Cannot open chapters: ${error.message}" }
            finally { runOnUiThread { importing = false } }
        }
    }

    private fun saveSelection() {
        getPreferences(MODE_PRIVATE).edit()
            .putString("voice", voices.getOrNull(selector.selectedItemPosition)?.directory?.name ?: selectedVoice)
            .putString("book", bookFile).putInt("start_line", startLine)
            .putInt("display_first_line", if (bookFile != null) displayFirstLine else 0)
            .putString("text", if (bookFile == null && displayingPlayback) typedDraft.orEmpty() else text.text.toString()).putString("percent", percentage.text.toString())
            .putString("title", title.text.toString()).apply()
    }

    private fun restoreSelection(state: Bundle?) {
        val saved = getPreferences(MODE_PRIVATE)
        selectedVoice = state?.getString("voice") ?: saved.getString("voice", "").orEmpty()
        bookFile = state?.getString("book") ?: saved.getString("book", null)
        startLine = state?.getInt("start_line") ?: saved.getInt("start_line", 0)
        displayFirstLine = state?.getInt("display_first_line") ?: saved.getInt("display_first_line", startLine)
        displayFile = bookFile
        if (bookFile == null) startLine = 0
        val restored = state?.getString("text") ?: saved.getString("text", null)
        if (restored != null) text.setText(restored)
        percentage.setText(state?.getString("percent") ?: saved.getString("percent", "0"))
        title.text = state?.getString("title") ?: saved.getString("title", getString(R.string.player_title))
        if (bookFile != null) {
            previewLabel.setText(R.string.text_preview)
            text.isFocusable = false
            text.isFocusableInTouchMode = false
            text.setTextIsSelectable(false)
            text.keyListener = null
            text.movementMethod = ScrollingMovementMethod.getInstance()
            // Older versions saved preview text without its document offset.
            // Restore both from the same source instead of trusting that preview.
            val name = requireNotNull(bookFile)
            val line = startLine
            importing = true
            io.execute {
                try {
                    val indexed = ReaderDocument(File(filesDir, name))
                    val savedLine = (BookPositions.load(filesDir, indexed.positionKey) ?: line).coerceIn(0, maxOf(0, indexed.lineCount - 1))
                    runOnUiThread {
                        if (isDestroyed || bookFile != name) return@runOnUiThread
                        document = indexed; documentName = name
                        startLine = savedLine
                        showPosition(indexed, savedLine)
                        requestedSentence = null
                    }
                } catch (error: Exception) { TestState.status = "ERROR: Cannot restore book position: ${error.message}" }
                finally { runOnUiThread { importing = false } }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("voice", voices.getOrNull(selector.selectedItemPosition)?.directory?.name ?: selectedVoice)
        outState.putString("book", bookFile)
        outState.putInt("start_line", startLine)
        outState.putInt("display_first_line", if (bookFile != null) displayFirstLine else 0)
        outState.putString("text", if (bookFile == null && displayingPlayback) typedDraft.orEmpty() else text.text.toString())
        outState.putString("percent", percentage.text.toString())
        outState.putString("title", title.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun dialogBuilder(): AlertDialog.Builder = object : AlertDialog.Builder(this) {
        override fun create(): AlertDialog = super.create().also { dialog ->
            val window = requireNotNull(dialog.window)
            val original = window.callback
            window.callback = object : android.view.Window.Callback by original {
                override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                    onUserInteraction()
                    return original.dispatchTouchEvent(event)
                }
                override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                    onUserInteraction()
                    return original.dispatchKeyEvent(event)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        workSchedule.resume()
        workSchedule.update(TestState.busy, TestState.busy || importing, interaction = true)
    }
    override fun onUserInteraction() {
        super.onUserInteraction()
        workSchedule.update(TestState.busy, TestState.busy || importing, interaction = true)
        requestUpdate()
    }
    override fun onPause() { readerScroller.cancel(); saveSelection(); workSchedule.pause(); super.onPause() }
    override fun onDestroy() {
        if (TestState.onChange === stateChanged) TestState.onChange = null
        workSchedule.destroy()
        io.shutdown()
        super.onDestroy()
    }
}
