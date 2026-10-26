package app.slipnet.presentation.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

sealed class TerminalConnectionState {
    data object Disconnected : TerminalConnectionState()
    data object Connecting : TerminalConnectionState()
    data object Connected : TerminalConnectionState()
    data class Error(val message: String) : TerminalConnectionState()
}

data class SshTerminalUiState(
    val host: String = "45.38.249.16",
    val port: String = "22",
    val username: String = "yasin",
    val password: String = "yasin",
    val privateKey: String = "",
    val connectionState: TerminalConnectionState = TerminalConnectionState.Disconnected,
    val output: String = "Aryana Core Built-in SSH Terminal (Termius Mode)\nType commands or select quick actions below.\n",
    val currentCommand: String = "",
    val isAutoScroll: Boolean = true,
    val history: List<String> = emptyList(),
    val historyIndex: Int = -1
)

@HiltViewModel
class SshTerminalViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(SshTerminalUiState())
    val uiState: StateFlow<SshTerminalUiState> = _uiState.asStateFlow()

    private var jschSession: Session? = null
    private var shellChannel: ChannelShell? = null
    private var shellInput: OutputStream? = null
    private var readJob: Job? = null
    @Volatile
    private var isReading = false

    fun updateHost(value: String) { _uiState.value = _uiState.value.copy(host = value) }
    fun updatePort(value: String) { _uiState.value = _uiState.value.copy(port = value) }
    fun updateUsername(value: String) { _uiState.value = _uiState.value.copy(username = value) }
    fun updatePassword(value: String) { _uiState.value = _uiState.value.copy(password = value) }
    fun updateCommand(value: String) { _uiState.value = _uiState.value.copy(currentCommand = value) }
    fun toggleAutoScroll() { _uiState.value = _uiState.value.copy(isAutoScroll = !_uiState.value.isAutoScroll) }

    fun historyUp() {
        val state = _uiState.value
        if (state.history.isEmpty()) return
        val newIndex = if (state.historyIndex < 0) {
            state.history.size - 1
        } else {
            (state.historyIndex - 1).coerceAtLeast(0)
        }
        val cmd = state.history.getOrNull(newIndex) ?: ""
        _uiState.value = state.copy(historyIndex = newIndex, currentCommand = cmd)
    }

    fun historyDown() {
        val state = _uiState.value
        if (state.history.isEmpty() || state.historyIndex < 0) return
        val newIndex = state.historyIndex + 1
        if (newIndex >= state.history.size) {
            _uiState.value = state.copy(historyIndex = -1, currentCommand = "")
        } else {
            val cmd = state.history[newIndex]
            _uiState.value = state.copy(historyIndex = newIndex, currentCommand = cmd)
        }
    }

    fun connect() {
        val state = _uiState.value
        if (state.connectionState is TerminalConnectionState.Connected ||
            state.connectionState is TerminalConnectionState.Connecting) return

        _uiState.value = state.copy(
            connectionState = TerminalConnectionState.Connecting,
            output = state.output + "\n[Connecting to ${state.username}@${state.host}:${state.port}...]\n"
        )

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val jsch = JSch()
                    if (state.privateKey.isNotBlank()) {
                        jsch.addIdentity("custom_key", state.privateKey.toByteArray(Charsets.UTF_8), null, null)
                    }

                    val portNum = state.port.toIntOrNull() ?: 22
                    val session = jsch.getSession(state.username.trim(), state.host.trim(), portNum)
                    if (state.password.isNotBlank()) {
                        session.setPassword(state.password)
                    }
                    session.setConfig("StrictHostKeyChecking", "no")
                    session.setConfig("PreferredAuthentications", "password,publickey,keyboard-interactive")
                    session.setServerAliveInterval(15)
                    session.setServerAliveCountMax(4)
                    session.connect(15000)

                    val channel = session.openChannel("shell") as ChannelShell
                    channel.setPtyType("xterm-256color")
                    channel.setPtySize(100, 30, 800, 600)

                    val inStream = channel.inputStream
                    val outStream = channel.outputStream

                    channel.connect(10000)

                    jschSession = session
                    shellChannel = channel
                    shellInput = outStream

                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            connectionState = TerminalConnectionState.Connected,
                            output = _uiState.value.output + "[Connected to Aryana Core VPS! Ready.]\n\n"
                        )
                    }

                    startReadingOutput(inStream)

                } catch (e: Exception) {
                    disconnect()
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            connectionState = TerminalConnectionState.Error(e.message ?: "Connection failed"),
                            output = _uiState.value.output + "\n[Connection Failed: ${e.message}]\n"
                        )
                    }
                }
            }
        }
    }

    private fun startReadingOutput(inStream: InputStream) {
        isReading = true
        readJob?.cancel()
        readJob = viewModelScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(4096)
            try {
                while (isReading && isActive && shellChannel?.isConnected == true) {
                    val readCount = inStream.read(buffer)
                    if (readCount > 0) {
                        val rawText = String(buffer, 0, readCount, Charsets.UTF_8)
                        val cleanText = sanitizeAnsi(rawText)
                        if (cleanText.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                val current = _uiState.value.output
                                val trimmed = if (current.length > 80000) current.takeLast(40000) else current
                                _uiState.value = _uiState.value.copy(output = trimmed + cleanText)
                            }
                        }
                    } else if (readCount < 0) {
                        break
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (isReading) {
                    withContext(Dispatchers.Main) {
                        disconnect()
                    }
                }
            }
        }
    }

    fun sendCommand(cmd: String = _uiState.value.currentCommand) {
        if (shellChannel?.isConnected != true || shellInput == null) return
        val commandToSend = cmd
        if (commandToSend.isNotBlank()) {
            val updatedHistory = (_uiState.value.history + commandToSend).takeLast(50)
            _uiState.value = _uiState.value.copy(
                history = updatedHistory,
                historyIndex = -1,
                currentCommand = ""
            )
        } else {
            _uiState.value = _uiState.value.copy(currentCommand = "")
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                shellInput?.write((commandToSend + "\n").toByteArray(Charsets.UTF_8))
                shellInput?.flush()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        output = _uiState.value.output + "\n[Send Error: ${e.message}]\n"
                    )
                }
            }
        }
    }

    fun sendCtrlC() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                shellInput?.write(byteArrayOf(3)) // ASCII ETX (Ctrl+C)
                shellInput?.flush()
            } catch (_: Exception) {}
        }
    }

    fun sendCtrlZ() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                shellInput?.write(byteArrayOf(26)) // ASCII SUB (Ctrl+Z)
                shellInput?.flush()
            } catch (_: Exception) {}
        }
    }

    fun sendTab() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                shellInput?.write(byteArrayOf(9)) // ASCII TAB
                shellInput?.flush()
            } catch (_: Exception) {}
        }
    }

    fun sendEnter() {
        sendCommand("")
    }

    fun clearOutput() {
        _uiState.value = _uiState.value.copy(output = "")
    }

    fun disconnect() {
        isReading = false
        readJob?.cancel()
        readJob = null
        try {
            shellInput?.close()
        } catch (_: Exception) {}
        try {
            shellChannel?.disconnect()
        } catch (_: Exception) {}
        try {
            jschSession?.disconnect()
        } catch (_: Exception) {}
        shellInput = null
        shellChannel = null
        jschSession = null

        _uiState.value = _uiState.value.copy(
            connectionState = TerminalConnectionState.Disconnected
        )
    }

    /**
     * Sanitizes terminal ANSI codes, OSC window titles, VT100 control codes,
     * and normalizes carriage returns for clean Jetpack Compose rendering.
     */
    private fun sanitizeAnsi(text: String): String {
        var s = text
        // 1. Strip OSC window title sequences (e.g. \033]0;title\007 or \033]0;title\033\)
        s = s.replace(Regex("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)"), "")
        // 2. Strip standard CSI escape sequences (colors, cursor pos, etc.)
        s = s.replace(Regex("\u001B\\[[0-9;?]*[a-zA-Z~]"), "")
        // 3. Strip charset escapes like \033(B
        s = s.replace(Regex("\u001B\\([a-zA-Z0-9]"), "")
        // 4. Normalize CRLF
        s = s.replace("\r\n", "\n")
        // 5. Replace lone CR
        s = s.replace("\r", "")
        return s
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
