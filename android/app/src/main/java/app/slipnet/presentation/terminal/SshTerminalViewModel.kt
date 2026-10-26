package app.slipnet.presentation.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val output: String = "SlipNet Built-in SSH Terminal (Termius Mode)\nType commands or select quick actions below.\n",
    val currentCommand: String = "",
    val isAutoScroll: Boolean = true
)

@HiltViewModel
class SshTerminalViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(SshTerminalUiState())
    val uiState: StateFlow<SshTerminalUiState> = _uiState.asStateFlow()

    private var jschSession: Session? = null
    private var shellChannel: ChannelShell? = null
    private var shellInput: OutputStream? = null
    private var isReading = false

    fun updateHost(value: String) { _uiState.value = _uiState.value.copy(host = value) }
    fun updatePort(value: String) { _uiState.value = _uiState.value.copy(port = value) }
    fun updateUsername(value: String) { _uiState.value = _uiState.value.copy(username = value) }
    fun updatePassword(value: String) { _uiState.value = _uiState.value.copy(password = value) }
    fun updateCommand(value: String) { _uiState.value = _uiState.value.copy(currentCommand = value) }

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
                    session.connect(15000)

                    val channel = session.openChannel("shell") as ChannelShell
                    channel.setPtyType("xterm-256color")
                    
                    val inStream = channel.inputStream
                    val outStream = channel.outputStream

                    channel.connect(10000)

                    jschSession = session
                    shellChannel = channel
                    shellInput = outStream

                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            connectionState = TerminalConnectionState.Connected,
                            output = _uiState.value.output + "[Connected successfully! Ready for input.]\n\n"
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
        val buffer = ByteArray(4096)
        try {
            while (isReading && shellChannel?.isConnected == true) {
                val available = inStream.available()
                val readCount = inStream.read(buffer)
                if (readCount > 0) {
                    val rawText = String(buffer, 0, readCount, Charsets.UTF_8)
                    // Clean ANSI escape sequences for smooth rendering
                    val cleanText = cleanAnsi(rawText)
                    viewModelScope.launch(Dispatchers.Main) {
                        val current = _uiState.value.output
                        // Keep buffer manageable (max 100k chars)
                        val trimmed = if (current.length > 80000) current.takeLast(40000) else current
                        _uiState.value = _uiState.value.copy(output = trimmed + cleanText)
                    }
                } else if (readCount < 0) {
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            isReading = false
            disconnect()
        }
    }

    fun sendCommand(cmd: String = _uiState.value.currentCommand) {
        if (shellChannel?.isConnected != true || shellInput == null) return
        val commandToSend = cmd
        viewModelScope.launch(Dispatchers.IO) {
            try {
                shellInput?.write((commandToSend + "\n").toByteArray(Charsets.UTF_8))
                shellInput?.flush()
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(currentCommand = "")
                }
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

    fun sendEnter() {
        sendCommand("")
    }

    fun clearOutput() {
        _uiState.value = _uiState.value.copy(output = "")
    }

    fun disconnect() {
        isReading = false
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

    private fun cleanAnsi(text: String): String {
        return text.replace(Regex("\u001B\\[[;?0-9]*[a-zA-Z]"), "")
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
