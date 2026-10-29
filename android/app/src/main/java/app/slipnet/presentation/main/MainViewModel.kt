package app.slipnet.presentation.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.slipnet.data.export.ConfigExporter
import app.slipnet.data.export.ConfigImporter
import app.slipnet.data.export.ImportResult
import app.slipnet.data.local.datastore.PreferencesDataStore
import app.slipnet.domain.model.ChainValidation
import app.slipnet.domain.model.ConnectionState
import app.slipnet.domain.model.DnsResolver
import app.slipnet.domain.model.PingResult
import app.slipnet.domain.model.ProfileChain
import app.slipnet.domain.model.ServerProfile
import app.slipnet.domain.model.TrafficStats
import app.slipnet.domain.model.TunnelType
import app.slipnet.domain.model.isAvailable
import app.slipnet.domain.model.E2eTestResult
import app.slipnet.domain.repository.ChainRepository
import app.slipnet.domain.repository.ProfileRepository
import app.slipnet.domain.repository.ResolverScannerRepository
import app.slipnet.domain.usecase.ConnectVpnUseCase
import app.slipnet.domain.usecase.DeleteProfileUseCase
import app.slipnet.domain.usecase.DisconnectVpnUseCase
import app.slipnet.domain.usecase.GetActiveProfileUseCase
import app.slipnet.domain.usecase.GetProfilesUseCase
import app.slipnet.domain.usecase.SaveProfileUseCase
import app.slipnet.domain.usecase.SetActiveProfileUseCase
import app.slipnet.service.VpnConnectionManager
import app.slipnet.tunnel.SnowflakeBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

data class ImportPreview(
    val profiles: List<ServerProfile>,
    val warnings: List<String>
)

data class QrCodeData(
    val profileName: String,
    val configUri: String
)

data class MainUiState(
    // Connection state
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val activeProfile: ServerProfile? = null,
    val activeChain: ProfileChain? = null,
    val proxyOnlyMode: Boolean = false,
    val debugLogging: Boolean = false,
    val snowflakeBootstrapProgress: Int = -1,
    // Profile list state
    val profiles: List<ServerProfile> = emptyList(),
    val chains: List<ProfileChain> = emptyList(),
    val connectedProfileId: Long? = null,
    val connectedChainId: Long? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val exportedJson: String? = null,
    val importPreview: ImportPreview? = null,
    /** Input awaiting a password to decrypt (user pasted a slipnet-bundle-enc:// URI). */
    val pendingEncryptedImport: String? = null,
    val qrCodeData: QrCodeData? = null,
    val showFirstLaunchAbout: Boolean = false,
    val trafficStats: TrafficStats = TrafficStats.EMPTY,
    val uploadSpeed: Long = 0,
    val downloadSpeed: Long = 0,
    // Session totals shown after disconnect
    val sessionTotalUpload: Long = 0,
    val sessionTotalDownload: Long = 0,
    // Ping results per profile ID
    val pingResults: Map<Long, PingResult> = emptyMap(),
    val isPingRunning: Boolean = false,
    /**
     * Resolvers used by the *current* on-screen ping results when the Global
     * DNS override was active. Set when a ping starts; cleared by
     * [MainViewModel.clearPingResults]. Empty otherwise — the indicator only
     * appears alongside actual ping activity, not whenever Global DNS is on.
     */
    val pingingViaGlobalDns: List<DnsResolver> = emptyList(),
    val sleepTimerRemainingSeconds: Int = 0,
    // Update checker
    val availableUpdate: app.slipnet.util.AppUpdate? = null,
    // DNS warning
    val dnsWarning: String? = null,
    // DNS pool scan progress (per-profile pool feature)
    val dnsPoolScan: app.slipnet.tunnel.DnsPoolScanState = app.slipnet.tunnel.DnsPoolScanState()
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val connectionManager: VpnConnectionManager,
    private val getProfilesUseCase: GetProfilesUseCase,
    private val getActiveProfileUseCase: GetActiveProfileUseCase,
    private val connectVpnUseCase: ConnectVpnUseCase,
    private val disconnectVpnUseCase: DisconnectVpnUseCase,
    private val setActiveProfileUseCase: SetActiveProfileUseCase,
    private val deleteProfileUseCase: DeleteProfileUseCase,
    private val saveProfileUseCase: SaveProfileUseCase,
    private val profileRepository: ProfileRepository,
    private val chainRepository: ChainRepository,
    private val resolverScannerRepository: ResolverScannerRepository,
    private val configExporter: ConfigExporter,
    private val configImporter: ConfigImporter,
    private val preferencesDataStore: PreferencesDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var bootstrapPollingJob: Job? = null
    private var trafficPollingJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var pingJob: Job? = null

    init {
        observeConnectionState()
        observeDnsWarning()
        observeDnsPoolScan()
        observeProfiles()
        observeChains()
        observeProxyOnlyMode()
        observeDebugLogging()
        checkFirstLaunch()
        checkForUpdate()
    }

    // ── Connection ──────────────────────────────────────────────────────

    private fun observeConnectionState() {
        viewModelScope.launch {
            connectionManager.connectionState.collect { state ->
                val connectedId = when (state) {
                    is ConnectionState.Connected -> state.profile.id
                    else -> null
                }
                val chainId = when (state) {
                    is ConnectionState.Connected -> if (state.chainId > 0) state.chainId else null
                    else -> null
                }
                _uiState.value = _uiState.value.copy(
                    connectionState = state,
                    connectedProfileId = if (chainId != null) null else connectedId,
                    connectedChainId = chainId,
                    error = if (state is ConnectionState.Error) state.message else _uiState.value.error
                )
                if (state is ConnectionState.Connecting) {
                    startBootstrapPolling()
                } else {
                    stopBootstrapPolling()
                }
                if (state is ConnectionState.Connected) {
                    startTrafficPolling()
                    startSleepTimer()
                } else {
                    stopTrafficPolling()
                    cancelSleepTimer()
                }
            }
        }
    }

    private fun observeDnsWarning() {
        viewModelScope.launch {
            connectionManager.dnsWarning.collect { warning ->
                _uiState.value = _uiState.value.copy(dnsWarning = warning)
            }
        }
    }

    private fun observeDnsPoolScan() {
        viewModelScope.launch {
            connectionManager.dnsPoolScanState.collect { state ->
                _uiState.value = _uiState.value.copy(dnsPoolScan = state)
            }
        }
    }

    private fun startBootstrapPolling() {
        bootstrapPollingJob?.cancel()
        bootstrapPollingJob = viewModelScope.launch {
            while (true) {
                val progress = SnowflakeBridge.torBootstrapProgress
                _uiState.value = _uiState.value.copy(
                    snowflakeBootstrapProgress = if (progress > 0) progress else -1
                )
                delay(500)
            }
        }
    }

    private fun stopBootstrapPolling() {
        bootstrapPollingJob?.cancel()
        bootstrapPollingJob = null
        _uiState.value = _uiState.value.copy(snowflakeBootstrapProgress = -1)
    }

    private fun startTrafficPolling() {
        trafficPollingJob?.cancel()
        // Clear previous session totals when a new connection starts
        _uiState.value = _uiState.value.copy(sessionTotalUpload = 0, sessionTotalDownload = 0)
        trafficPollingJob = viewModelScope.launch {
            // Observe the stats that the VPN service's notification poller already
            // refreshes — no need to call refreshTrafficStats() a second time.
            connectionManager.trafficStats.collect { current ->
                _uiState.value = _uiState.value.copy(
                    trafficStats = current,
                    uploadSpeed = current.uploadSpeed,
                    downloadSpeed = current.downloadSpeed
                )
            }
        }
    }

    private fun stopTrafficPolling() {
        trafficPollingJob?.cancel()
        trafficPollingJob = null
        // Save session totals before clearing, but only if there are actual stats.
        // State transitions Connected→Disconnecting→Disconnected call this twice;
        // the second call must not overwrite saved totals with zeros.
        val lastStats = _uiState.value.trafficStats
        val hasStats = lastStats.bytesSent > 0 || lastStats.bytesReceived > 0
        _uiState.value = _uiState.value.copy(
            trafficStats = TrafficStats.EMPTY,
            uploadSpeed = 0,
            downloadSpeed = 0,
            sessionTotalUpload = if (hasStats) lastStats.bytesSent else _uiState.value.sessionTotalUpload,
            sessionTotalDownload = if (hasStats) lastStats.bytesReceived else _uiState.value.sessionTotalDownload
        )
    }

    private fun startSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = viewModelScope.launch {
            val minutes = preferencesDataStore.sleepTimerMinutes.first()
            if (minutes <= 0) return@launch
            var remaining = minutes * 60
            _uiState.value = _uiState.value.copy(sleepTimerRemainingSeconds = remaining)
            while (remaining > 0) {
                delay(1000)
                remaining--
                _uiState.value = _uiState.value.copy(sleepTimerRemainingSeconds = remaining)
            }
            disconnect()
        }
    }

    private fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _uiState.value = _uiState.value.copy(sleepTimerRemainingSeconds = 0)
    }

    fun userCancelSleepTimer() {
        cancelSleepTimer()
    }

    private fun observeProxyOnlyMode() {
        viewModelScope.launch {
            preferencesDataStore.proxyOnlyMode.collect { enabled ->
                _uiState.value = _uiState.value.copy(proxyOnlyMode = enabled)
            }
        }
    }

    private fun observeDebugLogging() {
        viewModelScope.launch {
            preferencesDataStore.debugLogging.collect { enabled ->
                _uiState.value = _uiState.value.copy(debugLogging = enabled)
            }
        }
    }


    private fun checkFirstLaunch() {
        viewModelScope.launch {
            val done = preferencesDataStore.firstLaunchDone.first()
            if (!done) {
                _uiState.value = _uiState.value.copy(showFirstLaunchAbout = true)
            }
        }
    }

    fun dismissFirstLaunchAbout() {
        _uiState.value = _uiState.value.copy(showFirstLaunchAbout = false)
        viewModelScope.launch {
            preferencesDataStore.setFirstLaunchDone()
        }
    }

    // ── Update Checker ──────────────────────────────────────────────────

    private fun checkForUpdate() {
        viewModelScope.launch {
            // Throttle: skip if checked recently
            val lastCheck = preferencesDataStore.lastUpdateCheckTime.first()
            val now = System.currentTimeMillis()
            if (now - lastCheck < app.slipnet.util.UpdateChecker.CHECK_INTERVAL_MS) return@launch
            preferencesDataStore.setLastUpdateCheckTime(now)

            val skipped = preferencesDataStore.skippedUpdateVersion.first()
            val current = app.slipnet.BuildConfig.VERSION_NAME

            val update = app.slipnet.util.UpdateChecker.check(current) ?: return@launch

            // Don't show if user already skipped this version
            if (update.versionName == skipped) return@launch

            _uiState.value = _uiState.value.copy(availableUpdate = update)
        }
    }

    fun dismissUpdate() {
        _uiState.value = _uiState.value.copy(availableUpdate = null)
    }

    fun manualCheckForUpdate(onResult: (app.slipnet.util.AppUpdate?) -> Unit) {
        viewModelScope.launch {
            val current = app.slipnet.BuildConfig.VERSION_NAME
            val update = app.slipnet.util.UpdateChecker.check(current)
            if (update != null) {
                _uiState.value = _uiState.value.copy(availableUpdate = update)
            }
            onResult(update)
        }
    }

    fun skipUpdate() {
        val version = _uiState.value.availableUpdate?.versionName ?: return
        _uiState.value = _uiState.value.copy(availableUpdate = null)
        viewModelScope.launch {
            preferencesDataStore.setSkippedUpdateVersion(version)
        }
    }

    private fun observeProfiles() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            combine(
                getProfilesUseCase(),
                getActiveProfileUseCase()
            ) { profiles, activeProfile ->
                Pair(profiles, activeProfile)
            }.collect { (profiles, activeProfile) ->
                _uiState.value = _uiState.value.copy(
                    profiles = profiles,
                    activeProfile = activeProfile,
                    isLoading = false
                )
            }
        }
    }

    fun connect(profile: ServerProfile? = null) {
        val targetProfile = profile ?: _uiState.value.activeProfile ?: _uiState.value.profiles.firstOrNull()
        if (targetProfile == null) {
            _uiState.value = _uiState.value.copy(error = "No profile available to connect")
            return
        }
        if (targetProfile.isExpired) {
            _uiState.value = _uiState.value.copy(error = "This profile has expired")
            return
        }
        if (!targetProfile.tunnelType.isAvailable()) {
            _uiState.value = _uiState.value.copy(
                error = "${targetProfile.tunnelType.displayName} is not available in this edition"
            )
            return
        }
        connectionManager.connect(targetProfile)
    }

    fun disconnect() {
        connectionManager.disconnect()
    }

    fun resetConnection() {
        connectionManager.reset()
        _uiState.value = _uiState.value.copy(connectionState = ConnectionState.Disconnected, error = null)
    }

    fun toggleConnection() {
        when (_uiState.value.connectionState) {
            is ConnectionState.Connected,
            is ConnectionState.Connecting -> disconnect()
            is ConnectionState.Error -> {
                resetConnection()
                connect()
            }
            is ConnectionState.Disconnecting -> { /* ignore — already disconnecting */ }
            else -> connect()
        }
    }

    private fun observeChains() {
        viewModelScope.launch {
            combine(
                chainRepository.getAllChains(),
                chainRepository.getActiveChain()
            ) { chains, activeChain ->
                Pair(chains, activeChain)
            }.collect { (chains, activeChain) ->
                _uiState.value = _uiState.value.copy(
                    chains = chains,
                    activeChain = activeChain
                )
            }
        }
    }

    fun setActiveChain(chain: ProfileChain) {
        viewModelScope.launch {
            chainRepository.setActiveChain(chain.id)
        }
    }

    fun connectChain(chain: ProfileChain) {
        viewModelScope.launch {
            val profiles = chain.profileIds.mapNotNull { profileRepository.getProfileById(it) }
            if (profiles.size != chain.profileIds.size) {
                _uiState.value = _uiState.value.copy(error = "Some profiles in chain were deleted")
                return@launch
            }
            val validationError = ChainValidation.validate(profiles)
            if (validationError != null) {
                _uiState.value = _uiState.value.copy(error = validationError)
                return@launch
            }
            connectionManager.connectChain(chain, profiles.first())
        }
    }

    fun moveChain(fromIndex: Int, toIndex: Int) {
        val currentList = _uiState.value.chains.toMutableList()
        if (fromIndex < 0 || fromIndex >= currentList.size ||
            toIndex < 0 || toIndex >= currentList.size) return

        val item = currentList.removeAt(fromIndex)
        currentList.add(toIndex, item)
        _uiState.value = _uiState.value.copy(chains = currentList)

        viewModelScope.launch {
            chainRepository.updateChainOrder(currentList.map { it.id })
        }
    }

    fun deleteChain(chain: ProfileChain) {
        viewModelScope.launch {
            chainRepository.deleteChain(chain.id)
        }
    }

    fun setActiveProfile(profile: ServerProfile) {
        val state = _uiState.value.connectionState
        val isConnectedOrConnecting = state is ConnectionState.Connected ||
                state is ConnectionState.Connecting

        viewModelScope.launch {
            setActiveProfileUseCase(profile.id)
        }

        if (isConnectedOrConnecting && _uiState.value.connectedProfileId != profile.id) {
            _uiState.value = _uiState.value.copy(connectionState = ConnectionState.Connecting)
            connectionManager.reconnect(profile)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // ── Profile Management ──────────────────────────────────────────────

    fun moveProfile(fromIndex: Int, toIndex: Int) {
        val currentList = _uiState.value.profiles.toMutableList()
        if (fromIndex < 0 || fromIndex >= currentList.size ||
            toIndex < 0 || toIndex >= currentList.size) return

        val item = currentList.removeAt(fromIndex)
        currentList.add(toIndex, item)
        _uiState.value = _uiState.value.copy(profiles = currentList)

        viewModelScope.launch {
            profileRepository.updateProfileOrder(currentList.map { it.id })
        }
    }

    fun togglePinProfile(profile: ServerProfile) {
        viewModelScope.launch {
            profileRepository.togglePinned(profile.id)
        }
    }

    fun deleteProfile(profile: ServerProfile) {
        viewModelScope.launch {
            val result = deleteProfileUseCase(profile.id)
            if (result.isFailure) {
                _uiState.value = _uiState.value.copy(
                    error = result.exceptionOrNull()?.message ?: "Failed to delete profile"
                )
            }
        }
    }

    // KeyFingerprint computes a short, non-reversible identifier for a key.
    // Consistent with server-side logging in cmd/server/main.go, which logs
    // KeyFingerprint(keyInfo.Key) instead of the raw key to prevent leaking
    // the tunnel's only secret into journald, syslog, and bug reports.
    private fun computeKeyFingerprint(key: String): String {
        if (key.isEmpty()) return "empty"
        try {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.substring(0, 8)
        } catch (e: Exception) {
            return "err"
        }
    }

    fun deleteDuplicateProfiles() {
        viewModelScope.launch {
            val profiles = _uiState.value.profiles
            val connectedId = (_uiState.value.connectionState as? ConnectionState.Connected)
                ?.profile?.id

            // Group by connection-relevant fields; keep the first (oldest by sortOrder) in each group
            val seen = mutableSetOf<String>()
            val duplicateIds = mutableListOf<Long>()
            for (profile in profiles) {
                val key = profile.duplicateKey()
                if (!seen.add(key)) {
                    // Skip the currently connected profile
                    if (profile.id != connectedId) {
                        duplicateIds.add(profile.id)
                    }
                }
            }
            for (id in duplicateIds) {
                deleteProfileUseCase(id)
            }
        }
    }

    private fun ServerProfile.duplicateKey(): String = listOf(
        tunnelType.value, domain, computeKeyFingerprint(dnsttPublicKey), dohUrl,
        sshHost, sshPort, sshUsername, sshPassword, sshAuthType.name, sshPrivateKey,
        resolvers.map { "${it.host}:${it.port}" }.sorted().joinToString(","),
        naivePort, naiveUsername, naivePassword,
        socksUsername.orEmpty(), socksPassword.orEmpty(),
        torBridgeLines
    ).joinToString("|")

    fun deleteAllProfiles() {
        viewModelScope.launch {
            // Disconnect first so no profile is protected from deletion
            if (_uiState.value.connectionState is ConnectionState.Connected ||
                _uiState.value.connectionState is ConnectionState.Connecting) {
                connectionManager.disconnect()
                // Give VPN service time to clean up so connectedProfile is cleared
                kotlinx.coroutines.delay(500)
            }
            for (profile in _uiState.value.profiles) {
                deleteProfileUseCase(profile.id)
            }
        }
    }

    // ── Import / Export ─────────────────────────────────────────────────

    fun exportProfile(profile: ServerProfile, hideResolvers: Boolean = false) {
        if (profile.isLocked) {
            _uiState.value = _uiState.value.copy(error = "Cannot export a locked profile")
            return
        }
        val json = configExporter.exportSingleProfile(profile, hideResolvers)
        _uiState.value = _uiState.value.copy(exportedJson = json)
    }

    fun exportStandardUri(profile: ServerProfile) {
        if (profile.isLocked) {
            _uiState.value = _uiState.value.copy(error = "Cannot export a locked profile")
            return
        }
        val uri = configExporter.exportStandardUri(profile)
        _uiState.value = _uiState.value.copy(exportedJson = uri)
    }

    fun exportProfileLocked(
        profile: ServerProfile,
        password: String,
        expirationDate: Long = 0,
        allowSharing: Boolean = false,
        boundDeviceId: String = "",
        hideResolvers: Boolean = false
    ) {
        val json = configExporter.exportSingleProfileLocked(
            profile, password, expirationDate, allowSharing, boundDeviceId, hideResolvers
        )
        _uiState.value = _uiState.value.copy(exportedJson = json)
    }

    fun exportAllProfiles() {
        val profiles = _uiState.value.profiles.filter { !it.isLocked }
        if (profiles.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "No exportable profiles")
            return
        }
        val json = configExporter.exportAllProfiles(profiles)
        _uiState.value = _uiState.value.copy(exportedJson = json)
    }

    fun exportAllProfilesEncrypted(
        bundlePassword: String,
        profilePassword: String = "",
        expirationDate: Long = 0,
        allowSharing: Boolean = false,
        boundDeviceId: String = "",
        hideResolvers: Boolean = false
    ) {
        val profiles = _uiState.value.profiles.filter { !it.isLocked }
        if (profiles.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "No exportable profiles")
            return
        }
        try {
            val encrypted = configExporter.exportAllProfilesEncrypted(
                profiles,
                bundlePassword,
                profilePassword,
                expirationDate,
                allowSharing,
                boundDeviceId,
                hideResolvers
            )
            _uiState.value = _uiState.value.copy(exportedJson = encrypted)
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(error = "Failed to encrypt: ${e.message}")
        }
    }

    fun clearExportedJson() {
        _uiState.value = _uiState.value.copy(exportedJson = null)
    }

    fun parseImportConfig(json: String, bundlePassword: String? = null) {
        val result = configImporter.parseAndImport(
            json, connectionManager.getDeviceId(), bundlePassword
        )
        when (result) {
            is ImportResult.Success -> {
                _uiState.value = _uiState.value.copy(
                    importPreview = ImportPreview(result.profiles, result.warnings),
                    pendingEncryptedImport = null
                )
            }
            is ImportResult.Error -> {
                _uiState.value = _uiState.value.copy(error = result.message)
            }
            ImportResult.NeedsPassword -> {
                _uiState.value = _uiState.value.copy(pendingEncryptedImport = json)
            }
        }
    }

    fun parseAndDirectImport(json: String, bundlePassword: String? = null, onResult: (Boolean, String) -> Unit) {
        val result = configImporter.parseAndImport(
            json, connectionManager.getDeviceId(), bundlePassword
        )
        when (result) {
            is ImportResult.Success -> {
                viewModelScope.launch {
                    try {
                        for (profile in result.profiles.reversed()) {
                            saveProfileUseCase(profile)
                        }
                        _uiState.value = _uiState.value.copy(importPreview = null, error = null)
                        val names = result.profiles.joinToString(", ") { it.name }
                        onResult(true, "Imported ${result.profiles.size} profile(s): $names")
                    } catch (e: Exception) {
                        onResult(false, "Failed to save imported profiles: ${e.message}")
                    }
                }
            }
            is ImportResult.Error -> {
                _uiState.value = _uiState.value.copy(error = result.message)
                onResult(false, result.message)
            }
            ImportResult.NeedsPassword -> {
                _uiState.value = _uiState.value.copy(pendingEncryptedImport = json)
                onResult(false, "Encrypted bundle requires password")
            }
        }
    }

    private fun decodeSubscriptionPayload(raw: String): String {
        val trimmed = raw.trim()
        val clean = trimmed.replace("\n", "").replace("\r", "").replace(" ", "")
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        for (flags in listOf(android.util.Base64.DEFAULT, android.util.Base64.URL_SAFE, android.util.Base64.NO_WRAP)) {
            try {
                val bytes = android.util.Base64.decode(padded, flags)
                val str = String(bytes, Charsets.UTF_8)
                if (str.contains("://") || str.contains("\"outbounds\"") || str.contains("vmess") || str.contains("vless") || str.contains("ss://")) {
                    return str
                }
            } catch (_: Exception) {}
        }
        return trimmed
    }

    fun updateSubscriptionGroup(groupName: String, url: String, onDone: (Int, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val conn = java.net.URL(url.trim()).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "v2rayNG/1.8.5")
                conn.instanceFollowRedirects = true
                val responseCode = conn.responseCode
                if (responseCode !in 200..299) {
                    withContext(Dispatchers.Main) { onDone(0, "HTTP $responseCode from subscription server") }
                    return@launch
                }
                val raw = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                val decoded = decodeSubscriptionPayload(raw)
                val result = configImporter.parseAndImport(decoded, connectionManager.getDeviceId())
                if (result is ImportResult.Success && result.profiles.isNotEmpty()) {
                    val group = groupName.ifBlank { "Subscription" }
                    // Synchronously delete all existing profiles for this group first to prevent duplicates / stale servers
                    profileRepository.deleteProfilesByGroup(group)
                    for (p in result.profiles) {
                        saveProfileUseCase(p.copy(subscriptionId = group))
                    }
                    withContext(Dispatchers.Main) { onDone(result.profiles.size, null) }
                } else if (result is ImportResult.Error) {
                    withContext(Dispatchers.Main) { onDone(0, result.message) }
                } else {
                    withContext(Dispatchers.Main) { onDone(0, "No profiles found in subscription") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onDone(0, e.message ?: "Failed to fetch subscription") }
            }
        }
    }

    fun saveSubscription(group: String, url: String, onDone: ((Int, String?) -> Unit)? = null) {
        viewModelScope.launch {
            preferencesDataStore.saveSubscriptionUrl(group, url)
            if (url.isNotBlank()) {
                updateSubscriptionGroup(group, url) { count, err ->
                    onDone?.invoke(count, err)
                }
            } else {
                onDone?.invoke(0, null)
            }
        }
    }

    fun updateSubscriptionGroup(groupName: String, onDone: (Int, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val map = preferencesDataStore.subscriptionUrls.first()
            val url = map[groupName]
            if (url.isNullOrBlank()) {
                withContext(Dispatchers.Main) { onDone(0, "No URL configured for group $groupName") }
            } else {
                updateSubscriptionGroup(groupName, url, onDone)
            }
        }
    }

    fun updateAllSubscriptions(onDone: (Int, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val map = preferencesDataStore.subscriptionUrls.first()
            if (map.isEmpty()) {
                withContext(Dispatchers.Main) { onDone(0, "No saved subscriptions found") }
                return@launch
            }
            var totalCount = 0
            var errorMsg: String? = null
            for ((group, url) in map) {
                if (url.isNotBlank()) {
                    try {
                        val conn = java.net.URL(url.trim()).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 15000
                        conn.readTimeout = 15000
                        conn.setRequestProperty("User-Agent", "v2rayNG/1.8.5")
                        conn.instanceFollowRedirects = true
                        if (conn.responseCode in 200..299) {
                            val raw = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                            val decoded = decodeSubscriptionPayload(raw)
                            val result = configImporter.parseAndImport(decoded, connectionManager.getDeviceId())
                            if (result is ImportResult.Success && result.profiles.isNotEmpty()) {
                                profileRepository.deleteProfilesByGroup(group)
                                for (p in result.profiles) {
                                    saveProfileUseCase(p.copy(subscriptionId = group))
                                }
                                totalCount += result.profiles.size
                            }
                        } else {
                            errorMsg = "HTTP ${conn.responseCode} on $group"
                        }
                    } catch (e: Exception) {
                        errorMsg = e.message
                    }
                }
            }
            withContext(Dispatchers.Main) { onDone(totalCount, errorMsg) }
        }
    }

    fun downloadGeoAssetFiles(filesDir: java.io.File, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val sources = listOf(
                "geoip.dat" to "https://raw.githubusercontent.com/Chocolate4U/Iran-v2ray-rules/release/geoip.dat",
                "geosite.dat" to "https://raw.githubusercontent.com/Chocolate4U/Iran-v2ray-rules/release/geosite.dat"
            )
            val fallbackSources = listOf(
                "geoip.dat" to "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat",
                "geosite.dat" to "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat"
            )
            var downloadedCount = 0
            val totalBytes = java.util.concurrent.atomic.AtomicLong(0)
            for (i in sources.indices) {
                val (filename, primaryUrl) = sources[i]
                val targetFile = java.io.File(filesDir, filename)
                val tempFile = java.io.File(filesDir, "${filename}.tmp")
                for (urlStr in listOf(primaryUrl, fallbackSources[i].second)) {
                    try {
                        val conn = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 15000
                        conn.readTimeout = 20000
                        conn.instanceFollowRedirects = true
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 AryanaNG")
                        if (conn.responseCode in 200..299) {
                            conn.inputStream.use { input ->
                                tempFile.outputStream().use { output ->
                                    val copied = input.copyTo(output)
                                    totalBytes.addAndGet(copied)
                                }
                            }
                            if (tempFile.length() > 1024) {
                                tempFile.renameTo(targetFile)
                                downloadedCount++
                                break
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            withContext(Dispatchers.Main) {
                if (downloadedCount > 0) {
                    val mb = String.format(java.util.Locale.US, "%.2f", totalBytes.get() / (1024.0 * 1024.0))
                    onDone(true, "Successfully updated $downloadedCount assets ($mb MB)")
                } else {
                    onDone(false, "Download failed. Please check internet connection.")
                }
            }
        }
    }

    fun applyRoutingSettings(
        routingMode: String,
        bypassLan: Boolean,
        bypassIran: Boolean,
        blockAds: Boolean
    ) {
        viewModelScope.launch {
            preferencesDataStore.setDomainRoutingEnabled(true)
            preferencesDataStore.setGeoBypassEnabled(bypassIran)
            preferencesDataStore.setGeoBypassCountry("IR")
            when (routingMode) {
                "Global" -> preferencesDataStore.setDomainRoutingMode(app.slipnet.data.local.datastore.DomainRoutingMode.BYPASS)
                "Direct" -> preferencesDataStore.setDomainRoutingMode(app.slipnet.data.local.datastore.DomainRoutingMode.ONLY_VPN)
                else -> preferencesDataStore.setDomainRoutingMode(app.slipnet.data.local.datastore.DomainRoutingMode.BYPASS)
            }
        }
    }

    fun cancelEncryptedImport() {
        _uiState.value = _uiState.value.copy(pendingEncryptedImport = null)
    }

    fun confirmImport() {
        val preview = _uiState.value.importPreview ?: return
        viewModelScope.launch {
            try {
                for (profile in preview.profiles.reversed()) {
                    saveProfileUseCase(profile)
                }
                _uiState.value = _uiState.value.copy(
                    importPreview = null,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to import profiles: ${e.message}",
                    importPreview = null
                )
            }
        }
    }

    fun cancelImport() {
        _uiState.value = _uiState.value.copy(importPreview = null)
    }

    fun showQrCode(profile: ServerProfile, hideResolvers: Boolean = false) {
        if (profile.isLocked) {
            _uiState.value = _uiState.value.copy(error = "Cannot export a locked profile")
            return
        }
        val configUri = configExporter.exportSingleProfile(profile, hideResolvers)
        _uiState.value = _uiState.value.copy(
            qrCodeData = QrCodeData(profile.name, configUri)
        )
    }

    fun clearQrCode() {
        _uiState.value = _uiState.value.copy(qrCodeData = null)
    }

    fun showQrCodeLocked(
        profile: ServerProfile,
        password: String,
        expirationDate: Long = 0,
        allowSharing: Boolean = false,
        boundDeviceId: String = "",
        hideResolvers: Boolean = false
    ) {
        val configUri = configExporter.exportSingleProfileLocked(
            profile, password, expirationDate, allowSharing, boundDeviceId, hideResolvers
        )
        _uiState.value = _uiState.value.copy(
            qrCodeData = QrCodeData(profile.name, configUri)
        )
    }

    fun reExportLockedProfile(profile: ServerProfile) {
        try {
            val json = configExporter.reExportLockedProfile(profile)
            _uiState.value = _uiState.value.copy(exportedJson = json)
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(error = e.message ?: "Re-export failed")
        }
    }

    fun showQrCodeLockedReExport(profile: ServerProfile) {
        try {
            val configUri = configExporter.reExportLockedProfile(profile)
            _uiState.value = _uiState.value.copy(
                qrCodeData = QrCodeData(profile.name, configUri)
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(error = e.message ?: "Re-export failed")
        }
    }

    fun getDeviceId(): String = connectionManager.getDeviceId()

    // ── Real Ping (full E2E tunnel handshake) ───────────────────────────

    companion object {
        /** Tunnel types that use DNS tunneling and support E2E testing */
        private val E2E_TUNNEL_TYPES = setOf(
            TunnelType.DNSTT, TunnelType.DNSTT_SSH,
            TunnelType.NOIZDNS, TunnelType.NOIZDNS_SSH,
            TunnelType.VAYDNS, TunnelType.VAYDNS_SSH,
            TunnelType.SLIPSTREAM, TunnelType.SLIPSTREAM_SSH
        )
        private const val E2E_TEST_URL = "http://www.gstatic.com/generate_204"
        /** Per-resolver E2E test timeout. With HTTP verification removed and
         *  the warmup SOCKS5 CONNECT dropped, the only significant cost is
         *  the Noise/QUIC handshake (~2–5s typical). 8s leaves ~3s headroom
         *  for cold/loaded resolvers without dragging on dead ones. */
        private const val E2E_TIMEOUT_MS = 8000L
        /** Total budget across all resolvers for a single profile. A profile
         *  with many dead resolvers won't block the whole ping job — after
         *  this budget, remaining resolvers are skipped and the last error
         *  is surfaced. */
        private const val E2E_TOTAL_BUDGET_MS = 15000L
    }

    fun pingAllProfiles() {
        if (_uiState.value.isPingRunning) {
            cancelPing()
            return
        }

        val profiles = _uiState.value.profiles
        if (profiles.isEmpty()) return

        val skipped = setOf(TunnelType.SNOWFLAKE)
        val initial = profiles.associate { profile ->
            profile.id to if (profile.tunnelType in skipped) {
                PingResult.Skipped
            } else {
                PingResult.Pending
            }
        }
        _uiState.value = _uiState.value.copy(pingResults = initial, isPingRunning = true)

        pingJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                pingingViaGlobalDns = preferencesDataStore.parsedGlobalResolvers()
            )
            try {
                val tcpProfiles = profiles.filter {
                    it.tunnelType !in skipped && it.tunnelType !in E2E_TUNNEL_TYPES
                }
                val e2eProfiles = profiles.filter {
                    it.tunnelType in E2E_TUNNEL_TYPES
                }

                // Launch all TCP pings in parallel
                val tcpJobs = tcpProfiles.map { profile ->
                    launch {
                        val result = pingProfileTcp(profile)
                        _uiState.value = _uiState.value.copy(
                            pingResults = _uiState.value.pingResults + (profile.id to result)
                        )
                    }
                }

                // E2E tests run sequentially: Slipstream still uses the singleton
                // native lib; DNSTT/NoizDNS/Vaydns now use the isolated variant
                // and *could* run in parallel, but a mixed list is simplest to
                // schedule one-at-a-time.
                val e2eJob = launch {
                    for (profile in e2eProfiles) {
                        val result = testProfileE2e(profile)
                        _uiState.value = _uiState.value.copy(
                            pingResults = _uiState.value.pingResults + (profile.id to result)
                        )
                    }
                }

                tcpJobs.forEach { it.join() }
                e2eJob.join()
            } finally {
                _uiState.value = _uiState.value.copy(isPingRunning = false)
            }
        }
    }

    fun pingAllProfilesSimple() {
        if (_uiState.value.isPingRunning) {
            cancelPing()
            return
        }

        val profiles = _uiState.value.profiles
        if (profiles.isEmpty()) return

        val skipped = setOf(TunnelType.SNOWFLAKE)
        val initial = profiles.associate { profile ->
            profile.id to if (profile.tunnelType in skipped) {
                PingResult.Skipped
            } else {
                PingResult.Pending
            }
        }
        _uiState.value = _uiState.value.copy(pingResults = initial, isPingRunning = true)

        pingJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                pingingViaGlobalDns = preferencesDataStore.parsedGlobalResolvers()
            )
            try {
                val jobs = profiles.filter { it.tunnelType !in skipped }.map { profile ->
                    launch {
                        val target = getSimplePingTarget(profile)
                        val result = if (target != null) {
                            pingTcp(target.first, target.second)
                        } else {
                            PingResult.Error("No target")
                        }
                        _uiState.value = _uiState.value.copy(
                            pingResults = _uiState.value.pingResults + (profile.id to result)
                        )
                    }
                }
                jobs.forEach { it.join() }
            } finally {
                _uiState.value = _uiState.value.copy(isPingRunning = false)
            }
        }
    }

    private suspend fun getSimplePingTarget(profile: ServerProfile): Pair<String, Int>? {
        // Try direct TCP target first
        getTcpTarget(profile)?.let { return it }
        // For DNS-tunneled profiles, ping the first resolver — honoring the
        // global DNS override so the test mirrors what Connect actually uses.
        val resolver = effectiveResolvers(profile).firstOrNull() ?: return null
        return resolver.host to resolver.port
    }

    /**
     * Resolvers that Connect would actually use for [profile]: the user's
     * Global DNS override list when enabled and non-empty, otherwise the
     * profile's own resolvers.
     */
    private suspend fun effectiveResolvers(profile: ServerProfile): List<DnsResolver> {
        return preferencesDataStore.parsedGlobalResolvers().ifEmpty { profile.resolvers }
    }

    private suspend fun pingTcp(host: String, port: Int): PingResult {
        return withContext(Dispatchers.IO) {
            try {
                val socket = Socket()
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(host, port), 5000)
                val elapsed = (System.nanoTime() - start) / 1_000_000
                socket.close()
                PingResult.Success(elapsed)
            } catch (e: Exception) {
                val msg = when (e) {
                    is java.net.SocketTimeoutException -> "Timeout"
                    is java.net.ConnectException -> "Refused"
                    is java.net.UnknownHostException -> "DNS failed"
                    else -> e.message?.take(20) ?: "Failed"
                }
                PingResult.Error(msg)
            }
        }
    }

    fun pingSingleProfile(profile: ServerProfile) {
        if (profile.tunnelType == TunnelType.SNOWFLAKE) return

        _uiState.value = _uiState.value.copy(
            pingResults = _uiState.value.pingResults + (profile.id to PingResult.Pending)
        )

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                pingingViaGlobalDns = preferencesDataStore.parsedGlobalResolvers()
            )
            val result = if (profile.tunnelType in E2E_TUNNEL_TYPES) {
                testProfileE2e(profile)
            } else {
                pingProfileTcp(profile)
            }
            _uiState.value = _uiState.value.copy(
                pingResults = _uiState.value.pingResults + (profile.id to result)
            )
        }
    }

    fun cancelPing() {
        pingJob?.cancel()
        pingJob = null
        _uiState.value = _uiState.value.copy(isPingRunning = false)
    }

    fun clearPingResults() {
        _uiState.value = _uiState.value.copy(pingResults = emptyMap(), pingingViaGlobalDns = emptyList())
    }

    /**
     * Persistently reorder the profile list ascending by last-measured ping
     * latency. Profiles without a successful ping keep their relative order
     * and sit at the bottom. Mirrors [moveProfile] by updating both in-memory
     * state and DB so the new order survives restarts.
     */
    fun sortProfilesByPing() {
        val current = _uiState.value.profiles
        val pings = _uiState.value.pingResults
        val sorted = current.sortedBy { profile ->
            (pings[profile.id] as? PingResult.Success)?.latencyMs ?: Long.MAX_VALUE
        }
        if (sorted == current) return
        _uiState.value = _uiState.value.copy(profiles = sorted)
        viewModelScope.launch {
            profileRepository.updateProfileOrder(sorted.map { it.id })
        }
    }

    fun sortProfilesByProtocol() {
        val current = _uiState.value.profiles
        val sorted = current.sortedBy { it.tunnelType.displayName }
        if (sorted == current) return
        _uiState.value = _uiState.value.copy(profiles = sorted)
        viewModelScope.launch {
            profileRepository.updateProfileOrder(sorted.map { it.id })
        }
    }

    fun sortProfilesByProtocolOrder(priorityList: List<app.slipnet.domain.model.TunnelType>) {
        val current = _uiState.value.profiles
        val priorityMap = priorityList.withIndex().associate { it.value to it.index }
        val sorted = current.sortedWith(
            compareBy<ServerProfile> { priorityMap[it.tunnelType] ?: Int.MAX_VALUE }
                .thenBy { it.name.lowercase() }
        )
        if (sorted == current) return
        _uiState.value = _uiState.value.copy(profiles = sorted)
        viewModelScope.launch {
            profileRepository.updateProfileOrder(sorted.map { it.id })
        }
    }

    fun sortProfilesByName() {
        val current = _uiState.value.profiles
        val sorted = current.sortedBy { it.name.lowercase() }
        if (sorted == current) return
        _uiState.value = _uiState.value.copy(profiles = sorted)
        viewModelScope.launch {
            profileRepository.updateProfileOrder(sorted.map { it.id })
        }
    }

    fun deleteProfilesMatching(predicate: (ServerProfile) -> Boolean) {
        viewModelScope.launch {
            val connectedId = (_uiState.value.connectionState as? ConnectionState.Connected)?.profile?.id
            val targets = _uiState.value.profiles.filter(predicate)
            for (p in targets) {
                if (p.id != connectedId) {
                    deleteProfileUseCase(p.id)
                }
            }
        }
    }

    fun exportProfilesToClipboard(predicate: (ServerProfile) -> Boolean): String {
        val targets = _uiState.value.profiles.filter(predicate)
        return targets.joinToString("\n") { configExporter.exportStandardUri(it) }
    }

    fun restartService() {
        viewModelScope.launch {
            val active = _uiState.value.activeProfile
            if (active != null) {
                connectionManager.disconnect()
                kotlinx.coroutines.delay(600)
                connect(active)
            }
        }
    }

    /**
     * E2E test for DNS-tunneled profiles: starts a real tunnel with the first resolver,
     * performs a handshake and HTTP/SSH verification, then reports total latency.
     */
    private suspend fun testProfileE2e(profile: ServerProfile): PingResult {
        // Honor Global DNS override so the test mirrors what Connect uses.
        val resolvers = effectiveResolvers(profile)
        if (resolvers.isEmpty()) return PingResult.Error("No resolver")

        // Try resolvers in order — if the first is geo-blocked / rate-limited
        // right now, the profile can still work through a later one, so we
        // shouldn't mark the whole profile as failed on the first resolver's
        // error. Stop at the first success; otherwise surface the last error.
        // Hard-bound total time via E2E_TOTAL_BUDGET_MS so a profile with many
        // dead resolvers doesn't stall the ping job: remaining resolvers are
        // skipped once the budget is exhausted.
        val budgetStart = System.currentTimeMillis()
        var lastError = "Failed"
        for ((index, resolver) in resolvers.withIndex()) {
            val elapsed = System.currentTimeMillis() - budgetStart
            val remainingBudget = E2E_TOTAL_BUDGET_MS - elapsed
            if (remainingBudget <= 1000L) {
                lastError = "Timeout (${index} of ${resolvers.size} resolvers tried)"
                break
            }
            val perResolverTimeout = minOf(E2E_TIMEOUT_MS, remainingBudget)
            try {
                // Use the *isolated* variant: spins up an ephemeral DNSTT/NoizDNS/
                // Vaydns client on a unique port instead of reusing the singleton
                // DnsttBridge / DnsttSocksBridge that the live VPN session owns.
                // Fixes the flaky "Bridge start failed" / port-collision errors
                // seen when testing multiple DNS-tunnel profiles sequentially.
                val result: E2eTestResult = resolverScannerRepository.testResolverE2eIsolated(
                    resolverHost = resolver.host,
                    resolverPort = resolver.port,
                    profile = profile,
                    testUrl = E2E_TEST_URL,
                    timeoutMs = perResolverTimeout,
                    // fullVerification=false: stop after the tunnel warmup
                    // handshake (Noise + KCP + smux + remote Dante SOCKS5
                    // CONNECT for non-SSH, SSH banner read for SSH variants).
                    // That already requires bidirectional data flow through
                    // the entire tunnel stack — strong enough proof of
                    // "reachability" without the false negatives caused by
                    // fetching gstatic.com over a 12s budget.
                    fullVerification = false
                ) { phase ->
                    val prefix = if (resolvers.size > 1) "[${index + 1}/${resolvers.size}] " else ""
                    _uiState.value = _uiState.value.copy(
                        pingResults = _uiState.value.pingResults + (profile.id to PingResult.Testing(prefix + phase))
                    )
                }

                if (result.success) {
                    return PingResult.Success(result.totalMs)
                }
                lastError = result.errorMessage?.take(25) ?: "Failed"
            } catch (e: Exception) {
                lastError = e.message?.take(25) ?: "Failed"
            }
        }
        return PingResult.Error(lastError)
    }

    /**
     * Simple TCP ping for non-tunnel profiles (SSH, Naive, DOH).
     */
    private suspend fun pingProfileTcp(profile: ServerProfile): PingResult {
        val target = getTcpTarget(profile) ?: return PingResult.Error("No target")

        val isDnsProfile = profile.tunnelType in listOf(
            TunnelType.MASTERDNS, TunnelType.MASTERDNS_SSH,
            TunnelType.STORMDNS, TunnelType.STORMDNS_SSH,
            TunnelType.COTTENDNS, TunnelType.COTTENDNS_SSH,
            TunnelType.SOAR, TunnelType.SOAR_SSH,
            TunnelType.MELI, TunnelType.MELI_SSH,
            TunnelType.QWDTT, TunnelType.QWDTT_SSH,
            TunnelType.NOOSHDAROO, TunnelType.NOOSHDAROO_SSH
        )
        if (isDnsProfile) {
            return pingDnsUdp(target.first, target.second)
        }

        return withContext(Dispatchers.IO) {
            try {
                val socket = Socket()
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(target.first, target.second), 5000)
                val elapsed = (System.nanoTime() - start) / 1_000_000
                socket.close()
                PingResult.Success(elapsed)
            } catch (e: Exception) {
                val msg = when (e) {
                    is java.net.SocketTimeoutException -> "Timeout"
                    is java.net.ConnectException -> "Refused"
                    is java.net.UnknownHostException -> "DNS failed"
                    else -> e.message?.take(20) ?: "Failed"
                }
                PingResult.Error(msg)
            }
        }
    }

    private suspend fun pingDnsUdp(host: String, port: Int = 53): PingResult = withContext(Dispatchers.IO) {
        var socket: java.net.DatagramSocket? = null
        try {
            val start = System.nanoTime()
            socket = java.net.DatagramSocket()
            socket.soTimeout = 2500
            val query = byteArrayOf(
                0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x01, 'm'.code.toByte(),
                0x0c, 'd'.code.toByte(), 'h'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(),
                'h'.code.toByte(), 'd'.code.toByte(), 't'.code.toByte(), 'j'.code.toByte(),
                'd'.code.toByte(), 't'.code.toByte(), 'i'.code.toByte(),
                0x04, 's'.code.toByte(), 'i'.code.toByte(), 't'.code.toByte(), 'e'.code.toByte(),
                0x00, 0x00, 0x01, 0x00, 0x01
            )
            val ip = java.net.InetAddress.getByName(host)
            val packet = java.net.DatagramPacket(query, query.size, ip, port)
            socket.send(packet)
            val recvBuf = ByteArray(512)
            val recvPacket = java.net.DatagramPacket(recvBuf, recvBuf.size)
            socket.receive(recvPacket)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            PingResult.Success(elapsed)
        } catch (e: Exception) {
            val msg = when (e) {
                is java.net.SocketTimeoutException -> "Timeout"
                else -> e.message?.take(20) ?: "Failed"
            }
            PingResult.Error(msg)
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun getTcpTarget(profile: ServerProfile): Pair<String, Int>? {
        return when (profile.tunnelType) {
            TunnelType.SSH -> profile.domain to profile.sshPort
            TunnelType.NAIVE_SSH -> profile.domain to profile.naivePort
            TunnelType.NAIVE -> profile.domain to profile.naivePort
            TunnelType.DOH -> {
                val host = try {
                    java.net.URL(profile.dohUrl).host
                } catch (_: Exception) { return null }
                host to 443
            }
            TunnelType.SOCKS5 -> profile.domain to profile.socks5ServerPort
            TunnelType.VLESS -> {
                val host = profile.cdnIp.takeIf { it.isNotBlank() } ?: profile.domain
                val port = if (profile.cdnPort > 0) profile.cdnPort else 443
                if (host.isBlank()) null else host to port
            }
            TunnelType.MASTERDNS, TunnelType.MASTERDNS_SSH,
            TunnelType.STORMDNS, TunnelType.STORMDNS_SSH,
            TunnelType.COTTENDNS, TunnelType.COTTENDNS_SSH,
            TunnelType.SOAR, TunnelType.SOAR_SSH,
            TunnelType.MELI, TunnelType.MELI_SSH,
            TunnelType.QWDTT, TunnelType.QWDTT_SSH,
            TunnelType.NOOSHDAROO, TunnelType.NOOSHDAROO_SSH -> {
                if (profile.resolvers.isNotEmpty()) {
                    profile.resolvers.first().let { it.host to it.port }
                } else {
                    "45.38.249.16" to 53
                }
            }
            TunnelType.WIREGUARD, TunnelType.OPENVPN,
            TunnelType.HYSTERIA2, TunnelType.TUIC,
            TunnelType.TROJAN, TunnelType.SHADOWSOCKS, TunnelType.SHADOWSOCKSR,
            TunnelType.VMESS, TunnelType.SNELL, TunnelType.VLESS_REALITY -> {
                val host = profile.domain.takeIf { it.isNotBlank() } ?: "45.38.249.16"
                val port = if (profile.cdnPort > 0) profile.cdnPort else 443
                host to port
            }
            else -> {
                if (profile.domain.isNotBlank()) {
                    profile.domain to (if (profile.cdnPort > 0) profile.cdnPort else 53)
                } else {
                    "45.38.249.16" to 53
                }
            }
        }
    }
}
