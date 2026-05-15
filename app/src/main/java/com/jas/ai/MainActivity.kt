package com.jas.ai

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jas.ai.ui.theme.AppTheme
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

enum class ModelState {
    CHECKING,
    NEEDS_MODEL,
    COPYING,
    READY,
    ERROR
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isFromUser: Boolean,
    val isLoading: Boolean = false
)

class ChatViewModel : ViewModel() {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _modelState = MutableStateFlow(ModelState.CHECKING)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _errorMessage = MutableStateFlow("")
    val errorMessage: StateFlow<String> = _errorMessage.asStateFlow()

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    fun checkExistingModel(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val modelFile = File(context.filesDir, "model.litertlm")
            if (modelFile.exists() && modelFile.length() > 0) {
                initializeEngine(modelFile.absolutePath)
            } else {
                _modelState.value = ModelState.NEEDS_MODEL
            }
        }
    }

    fun copyModelFromUriAndInit(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _modelState.value = ModelState.COPYING
            val modelFile = File(context.filesDir, "model.litertlm")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
                initializeEngine(modelFile.absolutePath)
            } catch (e: Exception) {
                if (modelFile.exists()) modelFile.delete()
                _errorMessage.value = e.message ?: "Failed to copy model file"
                _modelState.value = ModelState.ERROR
            }
        }
    }

    private fun initializeEngine(path: String) {
        try {
            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
            val engineConfig = EngineConfig(modelPath = path)
            engine = Engine(engineConfig)
            engine?.initialize()
            conversation = engine?.createConversation()
            
            _messages.value = listOf(
                ChatMessage(
                    text = "System ready. Local LiteRT AI loaded from internal storage.",
                    isFromUser = false
                )
            )
            _modelState.value = ModelState.READY
        } catch (e: Exception) {
            _errorMessage.value = e.message ?: "Failed to initialize LiteRT Engine"
            _modelState.value = ModelState.ERROR
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val userMessage = ChatMessage(text = text, isFromUser = true)
        val placeholderBotMessage = ChatMessage(text = "", isFromUser = false, isLoading = true)
        
        _messages.value = _messages.value + userMessage + placeholderBotMessage

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentConv = conversation ?: throw IllegalStateException("Conversation not ready")
                var fullResponse = ""
                
                currentConv.sendMessageAsync(text).collect { token ->
                    fullResponse += token
                    updateLastBotMessage(fullResponse, isLoading = true)
                }
                updateLastBotMessage(fullResponse, isLoading = false)
            } catch (e: Exception) {
                updateLastBotMessage("Error generating response: ${e.message}", isLoading = false)
            }
        }
    }

    fun resetModel(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            conversation?.close()
            engine?.close()
            val modelFile = File(context.filesDir, "model.litertlm")
            if (modelFile.exists()) {
                modelFile.delete()
            }
            _messages.value = emptyList()
            _modelState.value = ModelState.NEEDS_MODEL
        }
    }

    private fun updateLastBotMessage(text: String, isLoading: Boolean) {
        val currentList = _messages.value.toMutableList()
        val lastIndex = currentList.indexOfLast { !it.isFromUser }
        if (lastIndex != -1) {
            currentList[lastIndex] = currentList[lastIndex].copy(text = text, isLoading = isLoading)
            _messages.value = currentList
        }
    }

    override fun onCleared() {
        super.onCleared()
        conversation?.close()
        engine?.close()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: ChatViewModel = viewModel()) {
    val modelState by viewModel.modelState.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.checkExistingModel(context)
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.copyModelFromUriAndInit(context, it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KotlinAI Local", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    if (modelState == ModelState.READY || modelState == ModelState.ERROR) {
                        Button(onClick = { viewModel.resetModel(context) }) {
                            Text("Reset Model")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (modelState == ModelState.READY) {
                ChatInputBar(
                    onSendMessage = { viewModel.sendMessage(it) }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            when (modelState) {
                ModelState.CHECKING -> {
                    LoadingScreen("Checking storage for model...")
                }
                ModelState.NEEDS_MODEL -> {
                    ActionScreen(
                        message = "No local model found. Please select a valid LiteRT/TFLite model file to begin.",
                        buttonText = "Select Model File",
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) }
                    )
                }
                ModelState.COPYING -> {
                    LoadingScreen("Copying model to secure internal storage...\nThis might take a moment depending on file size.")
                }
                ModelState.ERROR -> {
                    ActionScreen(
                        message = "An error occurred:\n$errorMessage",
                        buttonText = "Try Again",
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) }
                    )
                }
                ModelState.READY -> {
                    ChatList(messages)
                }
            }
        }
    }
}

@Composable
fun LoadingScreen(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(64.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = message,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
fun ActionScreen(message: String, buttonText: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(buttonText, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
fun ChatList(messages: List<ChatMessage>) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        items(messages) { message ->
            ChatBubble(message)
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
fun ChatInputBar(onSendMessage: (String) -> Unit) {
    var inputText by remember { mutableStateOf("") }

    BottomAppBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        tonalElevation = 8.dp
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ask your local AI...") },
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            maxLines = 3
        )
        Spacer(modifier = Modifier.width(12.dp))
        FloatingActionButton(
            onClick = {
                if (inputText.isNotBlank()) {
                    onSendMessage(inputText)
                    inputText = ""
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(imageVector = Icons.Default.Send, contentDescription = null)
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val alignment = if (message.isFromUser) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleColor = if (message.isFromUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (message.isFromUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val shape = if (message.isFromUser) {
        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Column(
            horizontalAlignment = if (message.isFromUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = shape,
                color = bubbleColor,
                shadowElevation = 2.dp,
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(14.dp),
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            if (message.isLoading && !message.isFromUser) {
                Text(
                    text = "Generating...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 4.dp, start = 8.dp)
                )
            }
        }
    }
}