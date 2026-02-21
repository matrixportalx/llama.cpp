package com.example.llama

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.InferenceEngine
import com.arm.aichat.internal.InferenceEngineImpl
import com.example.llama.data.AppDatabase
import com.example.llama.data.Conversation
import com.example.llama.data.DbMessage
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import android.os.Environment
import android.net.Uri
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
// v4 - SAF backup + AES-256-GCM encryption

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private var selectedTemplate: Int = 0
    private lateinit var toolbar: Toolbar
    private lateinit var messagesRv: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var fab: FloatingActionButton
    private lateinit var conversationsRv: RecyclerView
    private lateinit var btnNewChat: Button

    private lateinit var messageAdapter: MessageAdapter
    private lateinit var conversationAdapter: ConversationAdapter
    private lateinit var db: AppDatabase
    private lateinit var engine: InferenceEngine

    private var currentConversationId: String = ""
    private var loadedModelPath: String? = null
    private var isGenerating = false
    private var generationJob: Job? = null

    // Ayarlar
    private var contextSize: Int = 2048
    private var systemPrompt: String = ""
    private var temperature: Float = 0.8f
    private var topP: Float = 0.95f
    private var topK: Int = 40

    private val currentMessages = mutableListOf<ChatMessage>()

    // SAF: yedek dosyasını kaydetmek için
    private val backupSaveLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                pendingBackupUri = uri
                pendingBackupCallback?.invoke(uri)
                pendingBackupCallback = null
            }
        }
    }

    // SAF: yedek dosyasını geri yüklemek için
    private val backupRestoreLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleRestoreFile(uri)
            }
        }
    }

    private var pendingBackupUri: Uri? = null
    private var pendingBackupCallback: ((Uri) -> Unit)? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Dosya kopyalanıyor...", Toast.LENGTH_SHORT).show()
                        }

                        val fileName = contentResolver.query(
                            uri, null, null, null, null
                        )?.use { cursor ->
                            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            cursor.moveToFirst()
                            cursor.getString(nameIndex)
                        } ?: "model.gguf"

                        // filesDir güncelleme sonrası silinmez (cacheDir silinir)
                        val modelsDir = java.io.File(filesDir, "models").also { it.mkdirs() }
                        val destFile = java.io.File(modelsDir, fileName)
                        contentResolver.openInputStream(uri)?.use { input ->
                            destFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }

                        val path = destFile.absolutePath
                        val prefs = getSharedPreferences("llama_prefs", MODE_PRIVATE)
                        val models = prefs.getStringSet("saved_models", mutableSetOf())!!.toMutableSet()
                        models.add(path)
                        prefs.edit().putStringSet("saved_models", models).apply()

                        withContext(Dispatchers.Main) {
                            showTemplatePickerDialog(path)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@MainActivity,
                                "Dosya kopyalanamadı: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = AppDatabase.getInstance(this)
        engine = InferenceEngineImpl.getInstance(this)

        loadSettings()
        cleanupMissingModels()
        bindViews()
        setupToolbar()
        setupDrawer()
        setupMessageList()
        setupConversationList()
        setupFab()
        setupInput()
        observeConversations()

        lifecycleScope.launch {
            ensureActiveConversation()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.destroy()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("llama_prefs", MODE_PRIVATE)
        contextSize   = prefs.getInt("context_size", 2048)
        systemPrompt  = prefs.getString("system_prompt", "") ?: ""
        temperature   = prefs.getFloat("temperature", 0.8f)
        topP          = prefs.getFloat("top_p", 0.95f)
        topK          = prefs.getInt("top_k", 40)
    }

    private fun saveSettings() {
        getSharedPreferences("llama_prefs", MODE_PRIVATE).edit()
            .putInt("context_size", contextSize)
            .putString("system_prompt", systemPrompt)
            .putFloat("temperature", temperature)
            .putFloat("top_p", topP)
            .putInt("top_k", topK)
            .apply()
    }

    private fun bindViews() {
        drawerLayout    = findViewById(R.id.drawer_layout)
        toolbar         = findViewById(R.id.toolbar)
        messagesRv      = findViewById(R.id.messages)
        messageInput    = findViewById(R.id.message)
        fab             = findViewById(R.id.send)
        conversationsRv = findViewById(R.id.conversations_list)
        btnNewChat      = findViewById(R.id.btn_new_chat)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun setupDrawer() {
        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.drawer_open, R.string.drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        btnNewChat.setOnClickListener {
            lifecycleScope.launch {
                createNewConversation()
                drawerLayout.closeDrawers()
            }
        }
    }

    private fun setupMessageList() {
        messageAdapter = MessageAdapter(
            onCopy = { msg ->
                val clip = ClipData.newPlainText("mesaj", msg)
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(clip)
                Toast.makeText(this, "Panoya kopyalandı", Toast.LENGTH_SHORT).show()
            },
            onEdit = { position, content ->
                showEditMessageDialog(position, content)
            },
            onRegenerate = { _ ->
                regenerateLastResponse()
            }
        )
        messagesRv.layoutManager = LinearLayoutManager(this).also { it.stackFromEnd = true }
        messagesRv.adapter = messageAdapter
    }

    private fun setupConversationList() {
        conversationAdapter = ConversationAdapter(
            onSelect = { conv ->
                lifecycleScope.launch {
                    switchConversation(conv.id)
                    drawerLayout.closeDrawers()
                }
            },
            onDelete = { conv -> confirmDeleteConversation(conv) }
        )
        conversationsRv.layoutManager = LinearLayoutManager(this)
        conversationsRv.adapter = conversationAdapter
    }

    private fun setupFab() {
        updateFabIcon()
        fab.setOnClickListener {
            when {
                isGenerating -> stopGeneration()
                loadedModelPath == null -> showModelPickerDialog()
                else -> sendMessage()
            }
        }
    }

    private fun updateFabIcon() {
        val icon = when {
            isGenerating -> android.R.drawable.ic_media_pause
            loadedModelPath == null -> android.R.drawable.ic_menu_add
            else -> android.R.drawable.ic_menu_send
        }
        fab.setImageResource(icon)
    }

    private fun setupInput() {
        messageInput.setOnEditorActionListener { _, _, _ ->
            if (!isGenerating && loadedModelPath != null) sendMessage()
            true
        }
    }

    private fun observeConversations() {
        lifecycleScope.launch {
            db.chatDao().getAllConversations().collectLatest { list ->
                conversationAdapter.activeId = currentConversationId
                conversationAdapter.submitList(list)
            }
        }
    }

    private suspend fun ensureActiveConversation() {
        val prefs = getSharedPreferences("llama_prefs", MODE_PRIVATE)
        val savedId = prefs.getString("active_conversation_id", null)
        currentConversationId = if (savedId != null && conversationExists(savedId)) {
            savedId
        } else {
            createNewConversation()
        }
        loadMessagesForCurrent()
    }

    private suspend fun conversationExists(id: String): Boolean = withContext(Dispatchers.IO) {
        try { db.chatDao().getMessages(id).isNotEmpty() } catch (e: Exception) { false }
    }

    private suspend fun createNewConversation(): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        db.chatDao().insertConversation(Conversation(id = id, title = "Yeni Sohbet"))
        withContext(Dispatchers.Main) {
            currentConversationId = id
            saveActiveId(id)
            currentMessages.clear()
            messageAdapter.submitList(emptyList())
            conversationAdapter.activeId = id
            conversationAdapter.notifyDataSetChanged()
            updateToolbarTitle("Yeni Sohbet")
        }
        id
    }

    private suspend fun switchConversation(id: String) {
        if (id == currentConversationId) return
        currentConversationId = id
        saveActiveId(id)
        loadMessagesForCurrent()
        withContext(Dispatchers.Main) {
            conversationAdapter.activeId = id
            conversationAdapter.notifyDataSetChanged()
        }
    }

    private suspend fun loadMessagesForCurrent() = withContext(Dispatchers.IO) {
        val dbMessages = db.chatDao().getMessages(currentConversationId)
        val chatMessages = dbMessages.map {
            ChatMessage(content = it.content, isUser = it.role == "user")
        }
        withContext(Dispatchers.Main) {
            currentMessages.clear()
            currentMessages.addAll(chatMessages)
            messageAdapter.submitList(currentMessages.toList())
            if (currentMessages.isNotEmpty())
                messagesRv.scrollToPosition(currentMessages.size - 1)
            val title = if (chatMessages.isNotEmpty())
                chatMessages.first().content.take(30) else "Yeni Sohbet"
            updateToolbarTitle(title)
        }
    }

    private fun saveActiveId(id: String) {
        getSharedPreferences("llama_prefs", MODE_PRIVATE)
            .edit().putString("active_conversation_id", id).apply()
    }

    private fun updateToolbarTitle(title: String) {
        supportActionBar?.title = title
    }

    private fun updateActiveModelSubtitle() {
        supportActionBar?.subtitle = loadedModelPath?.substringAfterLast("/") ?: "Model yüklü değil"
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return
        messageInput.text.clear()

        val userMsg = ChatMessage(content = text, isUser = true)
        currentMessages.add(userMsg)
        messageAdapter.submitList(currentMessages.toList())
        messagesRv.scrollToPosition(currentMessages.size - 1)

        val convId = currentConversationId
        lifecycleScope.launch(Dispatchers.IO) {
            db.chatDao().insertMessage(
                DbMessage(UUID.randomUUID().toString(), convId, "user", text)
            )
            if (currentMessages.size == 1) {
                db.chatDao().updateConversationTitle(convId, text.take(40), System.currentTimeMillis())
            } else {
                db.chatDao().touchConversation(convId, System.currentTimeMillis())
            }
        }

        sendMessageContent(text)
    }

    private fun stopGeneration() {
        generationJob?.cancel()
        isGenerating = false
        updateFabIcon()
    }

    private fun confirmDeleteConversation(conv: Conversation) {
        AlertDialog.Builder(this)
            .setTitle("Sohbeti Sil")
            .setMessage("\"${conv.title}\" silinsin mi?")
            .setPositiveButton("Sil") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        db.chatDao().deleteMessages(conv.id)
                        db.chatDao().deleteConversation(conv.id)
                    }
                    if (conv.id == currentConversationId) createNewConversation()
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // Model listesi: her modele tıklayınca "Yükle / Kaldır / İptal" sorar
    private fun showModelPickerDialog() {
        val prefs = getSharedPreferences("llama_prefs", MODE_PRIVATE)
        val savedModels = prefs.getStringSet("saved_models", mutableSetOf())!!.toMutableList()

        val options = savedModels.map { path ->
            val name = path.substringAfterLast("/")
            if (path == loadedModelPath) "✓ $name" else name
        }.toMutableList()
        options.add("+ Yeni model ekle")

        AlertDialog.Builder(this)
            .setTitle("Model Seç")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == options.size - 1) {
                    showAddModelDialog()
                } else {
                    showModelActionDialog(savedModels[which])
                }
            }
            .show()
    }

    // Modele tıklandığında: Yükle / Kaldır / İptal
    private fun showModelActionDialog(path: String) {
        val name = path.substringAfterLast("/")
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf("Yükle", "Kaldır")) { _, which ->
                when (which) {
                    0 -> showTemplatePickerDialog(path)
                    1 -> confirmRemoveModel(path)
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // Modeli listeden ve cache'den kaldır
    private fun confirmRemoveModel(path: String) {
        val name = path.substringAfterLast("/")
        AlertDialog.Builder(this)
            .setTitle("Modeli Kaldır")
            .setMessage("\"$name\" uygulamadan kaldırılsın mı? Dosya silinecek.")
            .setPositiveButton("Kaldır") { _, _ ->
                // Aktif model mi kontrol et
                if (loadedModelPath == path) {
                    Toast.makeText(this, "Önce başka bir model yükleyin veya modeli değiştirin.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val prefs = getSharedPreferences("llama_prefs", MODE_PRIVATE)
                val models = prefs.getStringSet("saved_models", mutableSetOf())!!.toMutableSet()
                models.remove(path)
                prefs.edit().putStringSet("saved_models", models).apply()

                // Cache dosyasıysa fiziksel olarak sil
                val file = java.io.File(path)
                if (file.exists() && file.absolutePath.startsWith(cacheDir.absolutePath)) {
                    file.delete()
                }
                Toast.makeText(this, "\"$name\" kaldırıldı", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showTemplatePickerDialog(path: String) {
        val templates = arrayOf(
            "Otomatik (GGUF'tan)",
            "Aya / Command-R",
            "ChatML",
            "Gemma",
            "Llama 3"
        )
        val prefs = getSharedPreferences("llama_prefs", MODE_PRIVATE)
        val modelKey = "template_${path.substringAfterLast("/")}"
        val savedTemplate = prefs.getInt(modelKey, 0)

        AlertDialog.Builder(this)
            .setTitle("Sohbet Şablonu Seçin")
            .setSingleChoiceItems(templates, savedTemplate) { dialog, which ->
                selectedTemplate = which
                prefs.edit().putInt(modelKey, which).apply()
                dialog.dismiss()
                loadModel(path)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showAddModelDialog() {
        AlertDialog.Builder(this)
            .setTitle("Model Ekle")
            .setItems(arrayOf("Dosya seçici", "Yol gir")) { _, which ->
                when (which) {
                    0 -> {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                        filePickerLauncher.launch(intent)
                    }
                    1 -> {
                        val input = EditText(this).apply {
                            hint = "/storage/emulated/0/models/model.gguf"
                        }
                        AlertDialog.Builder(this)
                            .setTitle("Model Yolu")
                            .setView(input)
                            .setPositiveButton("Yükle") { _, _ ->
                                val path = input.text.toString().trim()
                                if (path.isNotEmpty()) {
                                    val prefs = getSharedPreferences("llama_prefs", MODE_PRIVATE)
                                    val models = prefs.getStringSet("saved_models", mutableSetOf())!!.toMutableSet()
                                    models.add(path)
                                    prefs.edit().putStringSet("saved_models", models).apply()
                                    loadModel(path)
                                }
                            }
                            .setNegativeButton("İptal", null)
                            .show()
                    }
                }
            }
            .show()
    }

    // ─── AYARLAR DİALOGU ────────────────────────────────────────────────────────

    private fun showSettingsDialog() {
        val ctx = this
        val dp = resources.displayMetrics.density

        // Ana scroll + layout
        val scrollView = ScrollView(ctx)
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }
        scrollView.addView(layout)

        fun sectionTitle(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * dp).toInt(); bottomMargin = (4 * dp).toInt() }
            layoutParams = lp
        }

        // ── Context Window ──────────────────────────────────────────────────────
        layout.addView(sectionTitle("Context Window (token)"))

        val ctxGroup = RadioGroup(ctx).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val ctxOptions = listOf(2048, 4096, 8192)
        val ctxRadios = ctxOptions.map { size ->
            RadioButton(ctx).apply {
                text = size.toString()
                id = View.generateViewId()
                isChecked = (size == contextSize)
            }
        }
        ctxRadios.forEach { ctxGroup.addView(it) }
        layout.addView(ctxGroup)

        // ── Sistem Prompt ───────────────────────────────────────────────────────
        layout.addView(sectionTitle("Sistem Prompt"))

        val systemPromptInput = EditText(ctx).apply {
            hint = "Örn: Sen yardımcı bir asistansın."
            setText(systemPrompt)
            minLines = 3
            maxLines = 6
            gravity = android.view.Gravity.TOP
        }
        layout.addView(systemPromptInput)

        // ── Temperature ─────────────────────────────────────────────────────────
        layout.addView(sectionTitle("Temperature: %.2f".format(temperature)))
        val tempLabel = layout.getChildAt(layout.childCount - 1) as TextView

        val tempBar = SeekBar(ctx).apply {
            max = 200  // 0.00 – 2.00, adım 0.01
            progress = (temperature * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    tempLabel.text = "Temperature: %.2f".format(p / 100f)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        layout.addView(tempBar)

        // ── Top-P ───────────────────────────────────────────────────────────────
        layout.addView(sectionTitle("Top-P: %.2f".format(topP)))
        val topPLabel = layout.getChildAt(layout.childCount - 1) as TextView

        val topPBar = SeekBar(ctx).apply {
            max = 100  // 0.00 – 1.00
            progress = (topP * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    topPLabel.text = "Top-P: %.2f".format(p / 100f)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        layout.addView(topPBar)

        // ── Top-K ───────────────────────────────────────────────────────────────
        layout.addView(sectionTitle("Top-K: $topK"))
        val topKLabel = layout.getChildAt(layout.childCount - 1) as TextView

        val topKBar = SeekBar(ctx).apply {
            max = 200  // 1 – 200
            progress = topK
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    val v = maxOf(1, p)
                    topKLabel.text = "Top-K: $v"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        layout.addView(topKBar)

        // ── Dialog ──────────────────────────────────────────────────────────────
        AlertDialog.Builder(this)
            .setTitle("⚙️ Ayarlar")
            .setView(scrollView)
            .setPositiveButton("Kaydet") { _, _ ->
                // Seçilen context size
                val checkedId = ctxGroup.checkedRadioButtonId
                if (checkedId != -1) {
                    val idx = ctxRadios.indexOfFirst { it.id == checkedId }
                    if (idx >= 0) contextSize = ctxOptions[idx]
                }
                systemPrompt = systemPromptInput.text.toString().trim()
                temperature = tempBar.progress / 100f
                topP = topPBar.progress / 100f
                topK = maxOf(1, topKBar.progress)
                saveSettings()
                Toast.makeText(this, "Ayarlar kaydedildi", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ── Model yükleme ────────────────────────────────────────────────────────────

    /** Startup'ta dosyası olmayan kayıtlı modelleri listeden temizle */
    private fun cleanupMissingModels() {
        val prefs = getSharedPreferences("llama_prefs", MODE_PRIVATE)
        val models = prefs.getStringSet("saved_models", mutableSetOf())!!.toMutableSet()
        val valid = models.filter { java.io.File(it).exists() }.toMutableSet()
        if (valid.size != models.size) {
            prefs.edit().putStringSet("saved_models", valid).apply()
        }
    }

    private fun loadModel(path: String) {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@MainActivity, "Model yükleniyor...", Toast.LENGTH_SHORT).show()

                if (engine.state.value is InferenceEngine.State.ModelReady ||
                    engine.state.value is InferenceEngine.State.Error) {
                    engine.cleanUp()
                }

                var waited = 0
                while (engine.state.value !is InferenceEngine.State.Initialized && waited < 100) {
                    delay(100)
                    waited++
                }

                engine.loadModel(path)
                loadedModelPath = path
                updateFabIcon()
                updateActiveModelSubtitle()
                Toast.makeText(
                    this@MainActivity,
                    path.substringAfterLast("/") + " yüklendi",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Model yüklenemedi: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ── Menü ─────────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_change_model -> { showModelPickerDialog(); true }
            R.id.action_clear_chat  -> { clearCurrentChat(); true }
            R.id.action_settings    -> { showSettingsDialog(); true }
            R.id.action_backup      -> { backupChats(); true }
            R.id.action_restore     -> { showRestorePicker(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun clearCurrentChat() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.chatDao().deleteMessages(currentConversationId)
                db.chatDao().updateConversationTitle(
                    currentConversationId, "Yeni Sohbet", System.currentTimeMillis()
                )
            }
            currentMessages.clear()
            messageAdapter.submitList(emptyList())
            updateToolbarTitle("Yeni Sohbet")
        }
    }
    // ── Düzenleme / Yeniden Oluştur ──────────────────────────────────────────────

    private fun showEditMessageDialog(position: Int, currentContent: String) {
        if (isGenerating) {
            Toast.makeText(this, "Yanıt üretilirken düzenleme yapılamaz", Toast.LENGTH_SHORT).show()
            return
        }
        val input = android.widget.EditText(this).apply {
            setText(currentContent)
            setSelection(currentContent.length)
            setPadding(48, 24, 48, 24)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Mesajı Düzenle")
            .setView(input)
            .setPositiveButton("Gönder") { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isNotEmpty() && newText != currentContent) {
                    editAndResend(position, newText)
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun editAndResend(position: Int, newContent: String) {
        val convId = currentConversationId

        // Listeden bu mesajdan sonraki tüm mesajları kaldır
        while (currentMessages.size > position) {
            currentMessages.removeAt(currentMessages.size - 1)
        }
        // Düzenlenmiş mesajı yerleştir
        currentMessages.add(ChatMessage(content = newContent, isUser = true))
        messageAdapter.submitList(currentMessages.toList())

        // DB'de bu sohbetin mesajlarını temizleyip yeniden yaz
        lifecycleScope.launch(Dispatchers.IO) {
            db.chatDao().deleteMessages(convId)
            currentMessages.forEachIndexed { idx, msg ->
                db.chatDao().insertMessage(
                    com.example.llama.data.DbMessage(
                        id = java.util.UUID.randomUUID().toString(),
                        conversationId = convId,
                        role = if (msg.isUser) "user" else "assistant",
                        content = msg.content,
                        timestamp = System.currentTimeMillis() + idx
                    )
                )
            }
        }

        // Modele yeniden gönder
        sendMessageContent(newContent)
    }

    private fun regenerateLastResponse() {
        if (isGenerating) {
            Toast.makeText(this, "Yanıt üretilirken yeniden oluşturulamaz", Toast.LENGTH_SHORT).show()
            return
        }
        if (loadedModelPath == null) {
            Toast.makeText(this, "Önce bir model yükleyin", Toast.LENGTH_SHORT).show()
            return
        }

        // Son asistan mesajını bul ve kaldır
        if (currentMessages.isNotEmpty() && !currentMessages.last().isUser) {
            currentMessages.removeAt(currentMessages.size - 1)
        }

        // Son kullanıcı mesajını bul
        val lastUserMsg = currentMessages.lastOrNull { it.isUser }?.content ?: return

        messageAdapter.submitList(currentMessages.toList())

        // DB'den son asistan mesajını sil
        val convId = currentConversationId
        lifecycleScope.launch(Dispatchers.IO) {
            val dbMessages = db.chatDao().getMessages(convId)
            val lastAssistant = dbMessages.lastOrNull { it.role == "assistant" }
            lastAssistant?.let { msg ->
                db.chatDao().deleteMessages(convId)
                dbMessages.filter { it.id != msg.id }.forEach {
                    db.chatDao().insertMessage(it)
                }
            }
        }

        sendMessageContent(lastUserMsg)
    }

    /** sendMessage'ın içeriği dışarıdan da çağrılabilsin diye ayrıldı */
    private fun sendMessageContent(text: String) {
        if (loadedModelPath == null) {
            Toast.makeText(this, "Önce bir model yükleyin", Toast.LENGTH_SHORT).show()
            return
        }

        val convId = currentConversationId
        isGenerating = true
        updateFabIcon()
        var fullResponse = ""

        generationJob = lifecycleScope.launch {
            try {
                engine.sendUserPrompt(text)
                    .collect { token ->
                        val cleaned = if (selectedTemplate == 1) {
                            token
                                .replace("<|START_RESPONSE|>", "")
                                .replace("<|END_RESPONSE|>", "")
                                .replace("<|END_OF_TURN_TOKEN|>", "")
                                .replace("<|START_OF_TURN_TOKEN|>", "")
                                .replace("<|CHATBOT_TOKEN|>", "")
                        } else token
                        fullResponse += cleaned
                        val newIndex = messageAdapter.updateLastAssistantMessage(fullResponse)
                        messagesRv.scrollToPosition(newIndex)
                    }
            } catch (e: Exception) {
                messageAdapter.updateLastAssistantMessage(
                    if (fullResponse.isEmpty()) "[Hata: ${e.message}]" else fullResponse
                )
            } finally {
                if (currentMessages.isNotEmpty() && !currentMessages.last().isUser) {
                    currentMessages[currentMessages.size - 1] = ChatMessage(fullResponse, false)
                } else {
                    currentMessages.add(ChatMessage(fullResponse, false))
                }
                if (fullResponse.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.chatDao().insertMessage(
                            com.example.llama.data.DbMessage(
                                java.util.UUID.randomUUID().toString(), convId, "assistant", fullResponse,
                                System.currentTimeMillis()
                            )
                        )
                        db.chatDao().touchConversation(convId, System.currentTimeMillis())
                    }
                }
                isGenerating = false
                updateFabIcon()
            }
        }
    }

    // ── Yedekleme / Geri Yükleme ─────────────────────────────────────────────────

    /** JSON verisini oluştur */
    private suspend fun buildBackupJson(): String {
        val conversations = db.chatDao().getAllConversationsList()
        val allMessages   = db.chatDao().getAllMessages()
        val root = JSONObject()
        root.put("version", 2)
        root.put("exportedAt", System.currentTimeMillis())
        val convsArray = JSONArray()
        for (conv in conversations) {
            val convObj = JSONObject()
            convObj.put("id", conv.id)
            convObj.put("title", conv.title)
            convObj.put("updatedAt", conv.updatedAt)
            val msgsArray = JSONArray()
            allMessages.filter { it.conversationId == conv.id }.forEach { msg ->
                val msgObj = JSONObject()
                msgObj.put("id", msg.id)
                msgObj.put("role", msg.role)
                msgObj.put("content", msg.content)
                msgObj.put("timestamp", msg.timestamp)
                msgsArray.put(msgObj)
            }
            convObj.put("messages", msgsArray)
            convsArray.put(convObj)
        }
        root.put("conversations", convsArray)
        return root.toString(2)
    }

    /**
     * AES-256-GCM şifreleme
     * Format: "KOVA" (4 byte) + salt (16 byte) + iv (12 byte) + şifreli veri
     */
    private fun encryptBackup(jsonText: String, password: String): ByteArray {
        val rng  = SecureRandom()
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        val iv   = ByteArray(12).also { rng.nextBytes(it) }

        // PBKDF2 ile anahtar türet (310000 iterasyon — OWASP önerisi)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec    = PBEKeySpec(password.toCharArray(), salt, 310_000, 256)
        val keyBytes = factory.generateSecret(spec).encoded
        val key = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(jsonText.toByteArray(Charsets.UTF_8))

        // Başlık: KOVA magic + salt + iv + şifreli veri
        val magic = "KOVA".toByteArray(Charsets.UTF_8)
        return magic + salt + iv + encrypted
    }

    /**
     * AES-256-GCM çözme
     */
    private fun decryptBackup(data: ByteArray, password: String): String {
        require(data.size > 32) { "Geçersiz yedek dosyası" }
        val magic = data.slice(0..3).toByteArray()
        require(String(magic) == "KOVA") { "Bu dosya Kova yedek dosyası değil" }

        val salt      = data.slice(4..19).toByteArray()
        val iv        = data.slice(20..31).toByteArray()
        val encrypted = data.slice(32 until data.size).toByteArray()

        val factory  = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec     = PBEKeySpec(password.toCharArray(), salt, 310_000, 256)
        val keyBytes = factory.generateSecret(spec).encoded
        val key      = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    /** Dosyanın şifreli Kova yedeği olup olmadığını kontrol et */
    private fun isEncryptedBackup(data: ByteArray): Boolean {
        if (data.size < 4) return false
        return String(data.slice(0..3).toByteArray()) == "KOVA"
    }

    /** Yedekleme: şifre sor → JSON oluştur → SAF ile kaydet */
    private fun backupChats() {
        // Şifre dialog
        val passwordInput = android.widget.EditText(this).apply {
            hint = "Şifre (boş bırakılırsa şifresiz)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("💾 Yedekleme")
            .setMessage("İsteğe bağlı şifre girin. Boş bırakırsanız şifresiz kaydedilir.")
            .setView(passwordInput)
            .setPositiveButton("Devam") { _, _ ->
                val password = passwordInput.text.toString()
                val isEncrypted = password.isNotEmpty()
                val ext = if (isEncrypted) "kova" else "json"
                val fileName = "kova_yedek_${System.currentTimeMillis()}.$ext"

                // SAF ile kayıt konumu seç
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (isEncrypted) "application/octet-stream" else "application/json"
                    putExtra(Intent.EXTRA_TITLE, fileName)
                }
                pendingBackupCallback = { uri ->
                    performBackupToUri(uri, password, isEncrypted)
                }
                backupSaveLauncher.launch(intent)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun performBackupToUri(uri: Uri, password: String, encrypt: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val jsonText = buildBackupJson()
                val conversations = db.chatDao().getAllConversationsList()

                contentResolver.openOutputStream(uri)?.use { out ->
                    if (encrypt) {
                        out.write(encryptBackup(jsonText, password))
                    } else {
                        out.write(jsonText.toByteArray(Charsets.UTF_8))
                    }
                } ?: throw Exception("Dosya yazılamadı")

                withContext(Dispatchers.Main) {
                    val encMsg = if (encrypt) " (AES-256 şifreli)" else ""
                    Toast.makeText(
                        this@MainActivity,
                        "${conversations.size} sohbet yedeklendi$encMsg",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Yedekleme hatası: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Geri yükleme: dosya seç */
    private fun showRestorePicker() {
        AlertDialog.Builder(this)
            .setTitle("📂 Geri Yükle")
            .setMessage("Mevcut tüm sohbetler silinecek ve yedekten geri yüklenecek. Devam edilsin mi?")
            .setPositiveButton("Devam") { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                backupRestoreLauncher.launch(intent)
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    /** Seçilen dosyayı işle: şifreli mi değil mi? */
    private fun handleRestoreFile(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = contentResolver.openInputStream(uri)?.readBytes()
                    ?: throw Exception("Dosya okunamadı")

                if (isEncryptedBackup(bytes)) {
                    // Şifreli — şifre sor
                    withContext(Dispatchers.Main) {
                        val passInput = android.widget.EditText(this@MainActivity).apply {
                            hint = "Yedekleme şifresi"
                            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                            setPadding(48, 24, 48, 24)
                        }
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("🔐 Şifreli Yedek")
                            .setMessage("Bu yedek şifrelenmiş. Şifreyi girin:")
                            .setView(passInput)
                            .setPositiveButton("Çöz ve Yükle") { _, _ ->
                                val pass = passInput.text.toString()
                                if (pass.isEmpty()) {
                                    Toast.makeText(this@MainActivity, "Şifre boş olamaz", Toast.LENGTH_SHORT).show()
                                    return@setPositiveButton
                                }
                                lifecycleScope.launch(Dispatchers.IO) {
                                    try {
                                        val jsonText = decryptBackup(bytes, pass)
                                        importJsonBackup(jsonText)
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(
                                                this@MainActivity,
                                                "Şifre çözme hatası. Şifre yanlış olabilir.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                            }
                            .setNegativeButton("İptal", null)
                            .show()
                    }
                } else {
                    // Şifresiz JSON
                    val jsonText = bytes.toString(Charsets.UTF_8)
                    importJsonBackup(jsonText)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Dosya okuma hatası: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** JSON yedek verisini DB'ye aktar */
    private suspend fun importJsonBackup(jsonText: String) {
        try {
            val root = JSONObject(jsonText)
            val convsArray = root.getJSONArray("conversations")

            db.chatDao().deleteAllMessages()
            db.chatDao().deleteAllConversations()

            var convCount = 0
            var msgCount  = 0

            for (i in 0 until convsArray.length()) {
                val convObj = convsArray.getJSONObject(i)
                val conv = com.example.llama.data.Conversation(
                    id        = convObj.getString("id"),
                    title     = convObj.getString("title"),
                    updatedAt = convObj.getLong("updatedAt")
                )
                db.chatDao().insertConversation(conv)
                convCount++

                val msgsArray = convObj.getJSONArray("messages")
                for (j in 0 until msgsArray.length()) {
                    val msgObj = msgsArray.getJSONObject(j)
                    db.chatDao().insertMessage(
                        com.example.llama.data.DbMessage(
                            id             = msgObj.getString("id"),
                            conversationId = conv.id,
                            role           = msgObj.getString("role"),
                            content        = msgObj.getString("content"),
                            timestamp      = msgObj.getLong("timestamp")
                        )
                    )
                    msgCount++
                }
            }

            withContext(Dispatchers.Main) {
                currentMessages.clear()
                messageAdapter.submitList(emptyList())
                lifecycleScope.launch { ensureActiveConversation() }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("✅ Geri Yükleme Tamamlandı")
                    .setMessage("$convCount sohbet, $msgCount mesaj geri yüklendi.")
                    .setPositiveButton("Tamam", null)
                    .show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Geri yükleme hatası: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

}
