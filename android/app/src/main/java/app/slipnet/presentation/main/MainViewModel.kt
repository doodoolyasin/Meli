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
import app.slipnet.util.DnsProbe
import app.slipnet.tunnel.SnowflakeBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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

    /** Saved subscription links: remark (group name) -> URL. Drives the Subscription page. */
    val subscriptionUrls: StateFlow<Map<String, String>> = preferencesDataStore.subscriptionUrls
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private var bootstrapPollingJob: Job? = null
    private var trafficPollingJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var pingJob: Job? = null

    init {
        observeConnectionState()
        observeDnsWarning()
        observeDnsPoolScan()
        observeProfiles()
        runDueSubscriptionUpdates()
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
                _uiState.update { it.copy(
                    connectionState = state,
                    connectedProfileId = if (chainId != null) null else connectedId,
                    connectedChainId = chainId,
                    error = if (state is ConnectionState.Error) state.message else null
                ) }
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
                _uiState.update { it.copy(dnsWarning = warning) }
            }
        }
    }

    private fun observeDnsPoolScan() {
        viewModelScope.launch {
            connectionManager.dnsPoolScanState.collect { state ->
                _uiState.update { it.copy(dnsPoolScan = state) }
            }
        }
    }

    private fun startBootstrapPolling() {
        bootstrapPollingJob?.cancel()
        bootstrapPollingJob = viewModelScope.launch {
            while (true) {
                val progress = SnowflakeBridge.torBootstrapProgress
                _uiState.update { it.copy(
                    snowflakeBootstrapProgress = if (progress > 0) progress else -1
                ) }
                delay(500)
            }
        }
    }

    private fun stopBootstrapPolling() {
        bootstrapPollingJob?.cancel()
        bootstrapPollingJob = null
        _uiState.update { it.copy(snowflakeBootstrapProgress = -1) }
    }

    private fun startTrafficPolling() {
        trafficPollingJob?.cancel()
        // Clear previous session totals when a new connection starts
        _uiState.update { it.copy(sessionTotalUpload = 0, sessionTotalDownload = 0) }
        trafficPollingJob = viewModelScope.launch {
            // Observe the stats that the VPN service's notification poller already
            // refreshes — no need to call refreshTrafficStats() a second time.
            connectionManager.trafficStats.collect { current ->
                _uiState.update { it.copy(
                    trafficStats = current,
                    uploadSpeed = current.uploadSpeed,
                    downloadSpeed = current.downloadSpeed
                ) }
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
        _uiState.update { it.copy(
            trafficStats = TrafficStats.EMPTY,
            uploadSpeed = 0,
            downloadSpeed = 0,
            sessionTotalUpload = if (hasStats) lastStats.bytesSent else _uiState.value.sessionTotalUpload,
            sessionTotalDownload = if (hasStats) lastStats.bytesReceived else _uiState.value.sessionTotalDownload
        ) }
    }

    private fun startSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = viewModelScope.launch {
            val minutes = preferencesDataStore.sleepTimerMinutes.first()
            if (minutes <= 0) return@launch
            var remaining = minutes * 60
            _uiState.update { it.copy(sleepTimerRemainingSeconds = remaining) }
            while (remaining > 0) {
                delay(1000)
                remaining--
                _uiState.update { it.copy(sleepTimerRemainingSeconds = remaining) }
            }
            disconnect()
        }
    }

    private fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _uiState.update { it.copy(sleepTimerRemainingSeconds = 0) }
    }

    fun userCancelSleepTimer() {
        cancelSleepTimer()
    }

    private fun observeProxyOnlyMode() {
        viewModelScope.launch {
            preferencesDataStore.proxyOnlyMode.collect { enabled ->
                _uiState.update { it.copy(proxyOnlyMode = enabled) }
            }
        }
    }

    private fun observeDebugLogging() {
        viewModelScope.launch {
            preferencesDataStore.debugLogging.collect { enabled ->
                _uiState.update { it.copy(debugLogging = enabled) }
            }
        }
    }


    private fun checkFirstLaunch() {
        viewModelScope.launch {
            val done = preferencesDataStore.firstLaunchDone.first()
            if (!done) {
                _uiState.update { it.copy(showFirstLaunchAbout = true) }
            }
        }
    }

    fun dismissFirstLaunchAbout() {
        _uiState.update { it.copy(showFirstLaunchAbout = false) }
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

            _uiState.update { it.copy(availableUpdate = update) }
        }
    }

    fun dismissUpdate() {
        _uiState.update { it.copy(availableUpdate = null) }
    }

    fun manualCheckForUpdate(onResult: (app.slipnet.util.AppUpdate?) -> Unit) {
        viewModelScope.launch {
            val current = app.slipnet.BuildConfig.VERSION_NAME
            val update = app.slipnet.util.UpdateChecker.check(current)
            if (update != null) {
                _uiState.update { it.copy(availableUpdate = update) }
            }
            onResult(update)
        }
    }

    fun skipUpdate() {
        val version = _uiState.value.availableUpdate?.versionName ?: return
        _uiState.update { it.copy(availableUpdate = null) }
        viewModelScope.launch {
            preferencesDataStore.setSkippedUpdateVersion(version)
        }
    }

    private fun observeProfiles() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            combine(
                getProfilesUseCase(),
                getActiveProfileUseCase()
            ) { profiles, activeProfile ->
                Pair(profiles, activeProfile)
            }.collect { (profiles, activeProfile) ->
                _uiState.update { it.copy(
                    profiles = profiles,
                    activeProfile = activeProfile,
                    isLoading = false
                ) }
            }
        }
    }

    fun connect(profile: ServerProfile? = null) {
        val targetProfile = profile ?: _uiState.value.activeProfile ?: _uiState.value.profiles.firstOrNull()
        if (targetProfile == null) {
            _uiState.update { it.copy(error = "No profile available to connect") }
            return
        }
        if (targetProfile.isExpired) {
            _uiState.update { it.copy(error = "This profile has expired") }
            return
        }
        if (!targetProfile.tunnelType.isAvailable()) {
            _uiState.update { it.copy(
                error = "${targetProfile.tunnelType.displayName} is not available in this edition"
            ) }
            return
        }
        connectionManager.connect(targetProfile)
    }

    fun disconnect() {
        connectionManager.disconnect()
    }

    fun resetConnection() {
        connectionManager.reset()
        _uiState.update { it.copy(connectionState = ConnectionState.Disconnected, error = null) }
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
                _uiState.update { it.copy(
                    chains = chains,
                    activeChain = activeChain
                ) }
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
                _uiState.update { it.copy(error = "Some profiles in chain were deleted") }
                return@launch
            }
            val validationError = ChainValidation.validate(profiles)
            if (validationError != null) {
                _uiState.update { it.copy(error = validationError) }
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
        _uiState.update { it.copy(chains = currentList) }

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
            _uiState.update { it.copy(connectionState = ConnectionState.Connecting) }
            connectionManager.reconnect(profile)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // ── Profile Management ──────────────────────────────────────────────

    fun moveProfile(fromIndex: Int, toIndex: Int) {
        val currentList = _uiState.value.profiles.toMutableList()
        if (fromIndex < 0 || fromIndex >= currentList.size ||
            toIndex < 0 || toIndex >= currentList.size) return

        val item = currentList.removeAt(fromIndex)
        currentList.add(toIndex, item)
        _uiState.update { it.copy(profiles = currentList) }

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
                _uiState.update { it.copy(
                    error = result.exceptionOrNull()?.message ?: "Failed to delete profile"
                ) }
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
        torBridgeLines,
        // Imported Custom/VLESS/etc. profiles share constant domains; these fields tell them apart.
        xrayConfigJson, vlessUuid, cdnIp, cdnPort, vlessSni, vlessTransport, vlessWsPath, socks5ServerPort
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
            _uiState.update { it.copy(error = "Cannot export a locked profile") }
            return
        }
        val json = configExporter.exportSingleProfile(profile, hideResolvers)
        _uiState.update { it.copy(exportedJson = json) }
    }

    fun exportStandardUri(profile: ServerProfile) {
        if (profile.isLocked) {
            _uiState.update { it.copy(error = "Cannot export a locked profile") }
            return
        }
        val uri = configExporter.exportStandardUri(profile)
        _uiState.update { it.copy(exportedJson = uri) }
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
        _uiState.update { it.copy(exportedJson = json) }
    }

    fun exportAllProfiles() {
        val profiles = _uiState.value.profiles.filter { !it.isLocked }
        if (profiles.isEmpty()) {
            _uiState.update { it.copy(error = "No exportable profiles") }
            return
        }
        val json = configExporter.exportAllProfiles(profiles)
        _uiState.update { it.copy(exportedJson = json) }
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
            _uiState.update { it.copy(error = "No exportable profiles") }
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
            _uiState.update { it.copy(exportedJson = encrypted) }
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "Failed to encrypt: ${e.message}") }
        }
    }

    fun clearExportedJson() {
        _uiState.update { it.copy(exportedJson = null) }
    }

    fun parseImportConfig(json: String, bundlePassword: String? = null) {
        val result = configImporter.parseAndImport(
            json, connectionManager.getDeviceId(), bundlePassword
        )
        when (result) {
            is ImportResult.Success -> {
                _uiState.update { it.copy(
                    importPreview = ImportPreview(result.profiles, result.warnings),
                    pendingEncryptedImport = null
                ) }
            }
            is ImportResult.Error -> {
                _uiState.update { it.copy(error = result.message) }
            }
            ImportResult.NeedsPassword -> {
                _uiState.update { it.copy(pendingEncryptedImport = json) }
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
                        _uiState.update { it.copy(importPreview = null, error = null) }
                        val names = result.profiles.joinToString(", ") { it.name }
                        onResult(true, "Imported ${result.profiles.size} profile(s): $names")
                    } catch (e: Exception) {
                        onResult(false, "Failed to save imported profiles: ${e.message}")
                    }
                }
            }
            is ImportResult.Error -> {
                _uiState.update { it.copy(error = result.message) }
                onResult(false, result.message)
            }
            ImportResult.NeedsPassword -> {
                _uiState.update { it.copy(pendingEncryptedImport = json) }
                onResult(false, "Encrypted bundle requires password")
            }
        }
    }

    /** HttpURLConnection never follows http<->https redirects; resolve them by hand (max 5 hops). */
    private fun resolveRedirects(start: String): String {
        var current = start
        repeat(5) {
            val c = java.net.URL(current).openConnection() as java.net.HttpURLConnection
            try {
                c.connectTimeout = 15000
                c.readTimeout = 15000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "v2rayNG/1.8.5")
                val code = c.responseCode
                if (code !in 300..399) return current
                val loc = c.getHeaderField("Location") ?: return current
                current = java.net.URL(java.net.URL(current), loc).toString()
            } catch (_: Exception) {
                return current
            } finally {
                c.disconnect()
            }
        }
        return current
    }

    private fun decodeSubscriptionPayload(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            return trimmed
        }
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

    val uiPrefs: StateFlow<app.slipnet.util.UiPrefs> = combine(
        preferencesDataStore.uiDeleteConfirm, preferencesDataStore.uiTwoColumn,
        preferencesDataStore.uiShowAllTab, preferencesDataStore.uiDynamicColor
    ) { d, t, a, c -> app.slipnet.util.UiPrefs(d, t, a, c) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, app.slipnet.util.UiPrefs())

    val subscriptionMeta: StateFlow<Map<String, app.slipnet.util.SubMeta>> = preferencesDataStore.subscriptionMeta
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val hotspotShareEnabled: StateFlow<Boolean> = preferencesDataStore.hotspotShareEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val trafficAuditStats: StateFlow<app.slipnet.util.TrafficAuditStats> = preferencesDataStore.trafficAuditStats
        .stateIn(viewModelScope, SharingStarted.Eagerly, app.slipnet.util.TrafficAuditStats())

    fun toggleHotspotShare(onStatus: ((Boolean, String) -> Unit)? = null) {
        viewModelScope.launch {
            val current = hotspotShareEnabled.value
            val target = !current
            preferencesDataStore.setHotspotShareEnabled(target)
            withContext(Dispatchers.IO) {
                val port = preferencesDataStore.httpProxyPort.first()
                if (target) {
                    val rooted = app.slipnet.util.HotspotManager.enableRootTransparentTether(port)
                    val ip = app.slipnet.util.HotspotManager.detectShareableIp()?.first ?: "192.168.43.1"
                    withContext(Dispatchers.Main) {
                        onStatus?.invoke(true, if (rooted) "اشتراک‌گذاری خودکار فعال شد (Root Transparent)" else "پروکسی هات‌اسپات روی $ip:$port فعال شد")
                    }
                } else {
                    app.slipnet.util.HotspotManager.disableRootTransparentTether(port)
                    withContext(Dispatchers.Main) {
                        onStatus?.invoke(false, "اشتراک‌گذاری هات‌اسپات غیرفعال شد")
                    }
                }
            }
        }
    }

    /** Download, decode, filter and import one subscription. Returns (count, error). */
    private suspend fun fetchSubscription(group: String, url: String): Pair<Int, String?> = withContext(Dispatchers.IO) {
        val meta = preferencesDataStore.subscriptionMeta.first()[group] ?: app.slipnet.util.SubMeta()
        val u = url.trim()
        if (!meta.enabled) return@withContext 0 to "Subscription is disabled"
        if (u.startsWith("http://", ignoreCase = true) && !meta.allowInsecureHttp) {
            return@withContext 0 to "Plain HTTP link blocked (enable \"Allow insecure HTTP\")"
        }
        val headers = meta.parseHeaders() ?: return@withContext 0 to "Request headers are not valid JSON"
        val filter = if (meta.remarksFilter.isBlank()) null else try { Regex(meta.remarksFilter) } catch (_: Exception) {
            return@withContext 0 to "Remarks filter is not a valid regex"
        }
        try {
            val final = resolveRedirects(u)
            // Block HTTPS→HTTP downgrade via redirect
            if (final.startsWith("http://", ignoreCase = true) && !meta.allowInsecureHttp) {
                return@withContext 0 to "Redirect to plain HTTP blocked (enable \"Allow insecure HTTP\")"
            }
            val conn = java.net.URL(final).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", meta.userAgent.ifBlank { "v2rayNG/1.8.5" })
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code !in 200..299) return@withContext 0 to "HTTP $code from subscription server"
            val userInfo = conn.getHeaderField("subscription-userinfo").orEmpty()
            fun ui(k: String): Long? = Regex("(?:^|;)\\s*$k\\s*=\\s*(\\d+)").find(userInfo)?.groupValues?.get(1)?.toLongOrNull()
            val usedB = if (userInfo.isBlank()) -1L else (ui("upload") ?: 0L) + (ui("download") ?: 0L)
            val totalB = ui("total") ?: -1L
            val expireS = ui("expire") ?: 0L
            val raw = conn.inputStream.bufferedReader().use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                var total = 0
                val limit = 4 * 1024 * 1024  // 4 MB cap
                var n: Int
                while (reader.read(buf).also { n = it } >= 0) {
                    total += n
                    if (total > limit) return@withContext 0 to "Subscription too large (>4 MB)"
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }.trim()
            val decoded = decodeSubscriptionPayload(raw)
            val result = configImporter.parseAndImport(decoded, connectionManager.getDeviceId())
            when {
                result is ImportResult.Success && result.profiles.isNotEmpty() -> {
                    val kept = if (filter == null) result.profiles else result.profiles.filter { filter.containsMatchIn(it.name) }
                    if (kept.isEmpty()) return@withContext 0 to "Remarks filter matched no configs"
                    val total = mergeSubscriptionProfiles(group, kept)
                    preferencesDataStore.saveSubscriptionMeta(group, meta.copy(lastUpdated = System.currentTimeMillis(), lastCount = total, usedBytes = usedB, totalBytes = totalB, expireEpochSec = expireS))
                    total to null
                }
                result is ImportResult.Error -> 0 to result.message
                else -> 0 to "No profiles found in subscription"
            }
        } catch (e: Exception) {
            0 to (e.message ?: "Failed to fetch subscription")
        }
    }

    /**
     * Merge a fetched subscription into the existing group instead of wiping it:
     * unchanged profiles keep their id, pin/lock state, edits and ordering; profiles the
     * server dropped are deleted (never the connected one); new ones are added in file order.
     * Returns the number of profiles now belonging to the group.
     */
    private suspend fun mergeSubscriptionProfiles(group: String, incoming: List<ServerProfile>): Int {
        val existing = profileRepository.getAllProfiles().first().filter { it.subscriptionGroup == group }
        val connectedId = (_uiState.value.connectionState as? ConnectionState.Connected)?.profile?.id
        val existingByKey = LinkedHashMap<String, ServerProfile>()
        for (p in existing) existingByKey.putIfAbsent(p.duplicateKey(), p)
        val incomingKeys = HashSet<String>()
        val fresh = ArrayList<ServerProfile>()
        for (p in incoming) {
            val k = p.duplicateKey()
            if (!incomingKeys.add(k)) continue          // duplicate inside the feed itself
            if (!existingByKey.containsKey(k)) fresh.add(p)
        }
        for (p in existing) {
            val keep = p.duplicateKey() in incomingKeys && existingByKey[p.duplicateKey()]?.id == p.id
            if (!keep && p.id != connectedId) deleteProfileUseCase(p.id)
        }
        // New profiles are inserted at the top one by one, so go in reverse to keep file order.
        for (p in fresh.reversed()) saveProfileUseCase(p.copy(subscriptionId = group))
        return incomingKeys.size
    }

    private suspend fun runPostUpdate(groups: Collection<String>, flags: app.slipnet.util.UpdateFlags) {
        if (!flags.autoTest) return
        withContext(Dispatchers.Main) { startPingAll(PingMode.DELAY) }
        pingJob?.join()
        if (flags.deleteInvalid) {
            val st = _uiState.value
            val bad = st.profiles.filter { it.subscriptionGroup in groups && st.pingResults[it.id] is PingResult.Error }.map { it.id }.toSet()
            if (bad.isNotEmpty()) deleteProfilesMatching { it.id in bad }
        }
        if (flags.autoSort) sortProfilesByPing()
    }

    fun updateSubscriptionGroup(groupName: String, url: String, onDone: (Int, String?) -> Unit) =
        updateSubscriptionGroup(groupName, url, app.slipnet.util.UpdateFlags(), onDone)

    fun updateSubscriptionGroup(groupName: String, url: String, flags: app.slipnet.util.UpdateFlags, onDone: (Int, String?) -> Unit) {
        viewModelScope.launch {
            val group = groupName.ifBlank { "Subscription" }
            val (n, err) = fetchSubscription(group, url)
            if (err == null) runPostUpdate(listOf(group), flags)
            onDone(n, err)
        }
    }

    fun saveSubscriptionMeta(group: String, meta: app.slipnet.util.SubMeta) {
        viewModelScope.launch { preferencesDataStore.saveSubscriptionMeta(group, meta) }
    }

    fun setSubscriptionEnabled(group: String, enabled: Boolean) {
        viewModelScope.launch {
            val cur = preferencesDataStore.subscriptionMeta.first()[group] ?: app.slipnet.util.SubMeta()
            preferencesDataStore.saveSubscriptionMeta(group, cur.copy(enabled = enabled))
        }
    }

    /** Re-fetch subscriptions whose automatic-update interval has elapsed. */
    fun runDueSubscriptionUpdates() {
        viewModelScope.launch(Dispatchers.IO) {
            val urls = preferencesDataStore.subscriptionUrls.first()
            val metas = preferencesDataStore.subscriptionMeta.first()
            val now = System.currentTimeMillis()
            for ((g, u) in urls) {
                val m = metas[g] ?: continue
                if (m.enabled && m.autoUpdate && u.isNotBlank() && now - m.lastUpdated >= m.intervalMinutes * 60_000L) {
                    fetchSubscription(g, u)
                }
            }
        }
    }

    /**
     * Create or edit a subscription (Remark + URL). When [oldRemark] differs from the new
     * remark, its configs are re-tagged and the old saved link removed first, so a rename
     * never leaves duplicates behind. The link is then fetched under the new remark.
     */
    fun saveSubscriptionEdit(
        oldRemark: String?,
        newRemark: String,
        url: String,
        meta: app.slipnet.util.SubMeta? = null,
        flags: app.slipnet.util.UpdateFlags = app.slipnet.util.UpdateFlags(),
        onDone: (Int, String?) -> Unit
    ) {
        viewModelScope.launch {
            val remark = newRemark.trim().ifBlank { "Subscription" }
            val prevMeta = preferencesDataStore.subscriptionMeta.first()
            val carried = meta ?: oldRemark?.let { prevMeta[it] }
            if (carried != null) preferencesDataStore.saveSubscriptionMeta(remark, carried)
            val cleanUrl = url.trim()
            if (!oldRemark.isNullOrBlank() && oldRemark != remark) {
                withContext(Dispatchers.IO) {
                    _uiState.value.profiles
                        .filter { it.subscriptionGroup == oldRemark }
                        .forEach { saveProfileUseCase(it.copy(subscriptionId = remark)) }
                    preferencesDataStore.deleteSubscriptionUrl(oldRemark)
                    preferencesDataStore.deleteSubscriptionMeta(oldRemark)
                }
            }
            preferencesDataStore.saveSubscriptionUrl(remark, cleanUrl)
            if (cleanUrl.isBlank()) { onDone(0, null); return@launch }
            updateSubscriptionGroup(remark, cleanUrl, flags, onDone)
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

    fun updateSubscriptionGroup(groupName: String, onDone: (Int, String?) -> Unit) =
        updateSubscriptionGroup(groupName, app.slipnet.util.UpdateFlags(), onDone)

    fun updateSubscriptionGroup(groupName: String, flags: app.slipnet.util.UpdateFlags, onDone: (Int, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val url = preferencesDataStore.subscriptionUrls.first()[groupName]
            if (url.isNullOrBlank()) {
                withContext(Dispatchers.Main) { onDone(0, "No URL configured for group $groupName") }
            } else {
                updateSubscriptionGroup(groupName, url, flags, onDone)
            }
        }
    }

    fun updateAllSubscriptions(onDone: (Int, String?) -> Unit) =
        updateAllSubscriptions(app.slipnet.util.UpdateFlags(), onDone)

    fun updateAllSubscriptions(flags: app.slipnet.util.UpdateFlags, onDone: (Int, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val map = preferencesDataStore.subscriptionUrls.first()
            if (map.isEmpty()) {
                withContext(Dispatchers.Main) { onDone(0, "No saved subscriptions found") }
                return@launch
            }
            var total = 0
            var errorMsg: String? = null
            val done = mutableListOf<String>()
            for ((group, url) in map) {
                if (url.isBlank()) continue
                val (n, err) = fetchSubscription(group, url)
                if (err == null) { total += n; done += group } else errorMsg = "$group: $err"
            }
            runPostUpdate(done, flags)
            withContext(Dispatchers.Main) { onDone(total, errorMsg) }
        }
    }

    fun updateDnsSubscriptions(onDone: (Int, String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val map = preferencesDataStore.subscriptionUrls.first()
            val dnsEntries = map.filter { (k, _) ->
                k.contains("dns", ignoreCase = true) ||
                k.contains("master", ignoreCase = true) ||
                k.contains("meli", ignoreCase = true) ||
                k.contains("storm", ignoreCase = true)
            }
            if (dnsEntries.isEmpty()) {
                withContext(Dispatchers.Main) { onDone(0, "No DNS subscriptions configured") }
                return@launch
            }
            var totalCount = 0
            var errorMsg: String? = null
            for ((group, url) in dnsEntries) {
                if (url.isNotBlank()) {
                    try {
                        val conn = java.net.URL(resolveRedirects(url.trim())).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 15000
                        conn.readTimeout = 15000
                        conn.setRequestProperty("User-Agent", "v2rayNG/1.8.5")
                        conn.instanceFollowRedirects = true
                        try {
                        if (conn.responseCode in 200..299) {
                            val raw = conn.inputStream.bufferedReader().use { reader ->
                                val sb = StringBuilder()
                                val buf = CharArray(8192)
                                var total = 0
                                val limit = 4 * 1024 * 1024
                                var n: Int
                                while (reader.read(buf).also { n = it } >= 0) {
                                    total += n
                                    if (total > limit) break
                                    sb.append(buf, 0, n)
                                }
                                sb.toString()
                            }.trim()
                            val decoded = decodeSubscriptionPayload(raw)
                            val result = configImporter.parseAndImport(decoded, connectionManager.getDeviceId())
                            if (result is ImportResult.Success && result.profiles.isNotEmpty()) {
                                totalCount += mergeSubscriptionProfiles(group, result.profiles)
                            }
                        } else {
                            errorMsg = "HTTP ${conn.responseCode} on $group"
                        }
                        } finally { conn.disconnect() }
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

    data class RoutingState(
        val enabled: Boolean, val onlyVpn: Boolean, val geoEnabled: Boolean,
        val geoCountry: String, val domains: Set<String>
    )

    suspend fun loadRoutingState(): RoutingState = RoutingState(
        enabled = preferencesDataStore.domainRoutingEnabled.first(),
        onlyVpn = preferencesDataStore.domainRoutingMode.first() == app.slipnet.data.local.datastore.DomainRoutingMode.ONLY_VPN,
        geoEnabled = preferencesDataStore.geoBypassEnabled.first(),
        geoCountry = preferencesDataStore.geoBypassCountry.first(),
        domains = preferencesDataStore.domainRoutingDomains.first()
    )

    fun saveRoutingState(enabled: Boolean, onlyVpn: Boolean, geoEnabled: Boolean, geoCountry: String, domains: Set<String>) {
        viewModelScope.launch {
            preferencesDataStore.setDomainRoutingEnabled(enabled)
            preferencesDataStore.setDomainRoutingMode(
                if (onlyVpn) app.slipnet.data.local.datastore.DomainRoutingMode.ONLY_VPN
                else app.slipnet.data.local.datastore.DomainRoutingMode.BYPASS
            )
            preferencesDataStore.setGeoBypassEnabled(geoEnabled)
            preferencesDataStore.setGeoBypassCountry(geoCountry)
            preferencesDataStore.setDomainRoutingDomains(domains)
        }
    }

    fun downloadRuleFile(filesDir: java.io.File, name: String, url: String, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val valid = Regex("^[a-z]{2}(_[a-z0-9_]+)?\\.(cidr|domains)$").matches(name)
            var ok = false
            var message = "Invalid name. Use <country>.cidr or <country>.domains"
            if (valid) {
                val tmp = java.io.File(filesDir, "$name.tmp")
                try {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 AryanaNG")
                    if (conn.responseCode in 200..299) {
                        conn.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                        if (tmp.length() > 0 && tmp.renameTo(java.io.File(filesDir, name))) {
                            ok = true; message = "Saved $name"
                        } else message = "Empty file"
                    } else message = "HTTP ${conn.responseCode}"
                } catch (e: Exception) {
                    message = e.message?.take(60) ?: "Download failed"
                } finally { tmp.delete() }
            }
            withContext(Dispatchers.Main) { onDone(ok, message) }
        }
    }

    fun cancelEncryptedImport() {
        _uiState.update { it.copy(pendingEncryptedImport = null) }
    }

    fun confirmImport() {
        val preview = _uiState.value.importPreview ?: return
        viewModelScope.launch {
            try {
                for (profile in preview.profiles.reversed()) {
                    saveProfileUseCase(profile)
                }
                _uiState.update { it.copy(
                    importPreview = null,
                    error = null
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    error = "Failed to import profiles: ${e.message}",
                    importPreview = null
                ) }
            }
        }
    }

    fun cancelImport() {
        _uiState.update { it.copy(importPreview = null) }
    }

    fun showQrCode(profile: ServerProfile, hideResolvers: Boolean = false) {
        if (profile.isLocked) {
            _uiState.update { it.copy(error = "Cannot export a locked profile") }
            return
        }
        val configUri = configExporter.exportSingleProfile(profile, hideResolvers)
        _uiState.update { it.copy(
            qrCodeData = QrCodeData(profile.name, configUri)
        ) }
    }

    fun clearQrCode() {
        _uiState.update { it.copy(qrCodeData = null) }
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
        _uiState.update { it.copy(
            qrCodeData = QrCodeData(profile.name, configUri)
        ) }
    }

    fun reExportLockedProfile(profile: ServerProfile) {
        try {
            val json = configExporter.reExportLockedProfile(profile)
            _uiState.update { it.copy(exportedJson = json) }
        } catch (e: Exception) {
            _uiState.update { it.copy(error = e.message ?: "Re-export failed") }
        }
    }

    fun showQrCodeLockedReExport(profile: ServerProfile) {
        try {
            val configUri = configExporter.reExportLockedProfile(profile)
            _uiState.update { it.copy(
                qrCodeData = QrCodeData(profile.name, configUri)
            ) }
        } catch (e: Exception) {
            _uiState.update { it.copy(error = e.message ?: "Re-export failed") }
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
        /** Profiles pinged at the same time. Probes are tiny (one datagram / one SYN). */
        private const val PING_CONCURRENCY = 10
        private const val TCP_PING_TIMEOUT_MS = 4000
    }

    // ── Ping ────────────────────────────────────────────────────────────
    //
    // DELAY    : default. DNS tunnels are probed through their resolvers for the profile's
    //            own domain (see DnsProbe); TCP-based profiles get a TCP connect (best of 2).
    // RESOLVER : client -> resolver round trip only (no tunnel involved).
    // FULL     : starts a real tunnel client per resolver (slow; sequential for E2E types).

    private enum class PingMode { DELAY, RESOLVER, FULL }

    fun pingAllProfiles() = startPingAll(PingMode.DELAY)

    fun pingAllProfilesSimple() = startPingAll(PingMode.RESOLVER)

    fun pingAllProfilesFull() = startPingAll(PingMode.FULL)

    private fun startPingAll(mode: PingMode) {
        if (_uiState.value.isPingRunning) {
            cancelPing()
            return
        }

        val profiles = _uiState.value.profiles
        if (profiles.isEmpty()) return

        val initial = profiles.associate { profile ->
            profile.id to if (profile.tunnelType == TunnelType.SNOWFLAKE) PingResult.Skipped else PingResult.Pending
        }
        _uiState.update { it.copy(pingResults = initial, isPingRunning = true) }

        pingJob = viewModelScope.launch {
            _uiState.update { it.copy(pingingViaGlobalDns = preferencesDataStore.parsedGlobalResolvers()) }
            try {
                val light = Semaphore(PING_CONCURRENCY)
                // Real tunnel clients are heavy, and Slipstream's native library is a singleton.
                val heavy = Semaphore(1)
                coroutineScope {
                    profiles.filter { it.tunnelType != TunnelType.SNOWFLAKE }.map { profile ->
                        async {
                            val gate = if (mode == PingMode.FULL && profile.tunnelType in E2E_TUNNEL_TYPES) heavy else light
                            val result = gate.withPermit { pingOne(profile, mode) }
                            publishPing(profile.id, result)
                        }
                    }.awaitAll()
                }
            } finally {
                _uiState.update { it.copy(isPingRunning = false) }
            }
        }
    }

    private fun publishPing(id: Long, result: PingResult) {
        _uiState.update { it.copy(pingResults = it.pingResults + (id to result)) }
    }

    /**
     * Resolvers that Connect would actually use for [profile]: the user's
     * Global DNS override list when enabled and non-empty, otherwise the
     * profile's own resolvers.
     */
    private suspend fun effectiveResolvers(profile: ServerProfile): List<DnsResolver> {
        return preferencesDataStore.parsedGlobalResolvers().ifEmpty { profile.resolvers }
    }

    /** Same fallback Connect uses when a DNS profile has no resolver. */
    private suspend fun pingTargets(profile: ServerProfile): List<DnsProbe.Target> {
        val list = effectiveResolvers(profile).map { DnsProbe.Target(it.host, it.port) }
        return list.ifEmpty { listOf(DnsProbe.Target("8.8.8.8", 53), DnsProbe.Target("8.8.4.4", 53)) }
    }

    private suspend fun pingOne(profile: ServerProfile, mode: PingMode): PingResult {
        if (profile.tunnelType == TunnelType.SNOWFLAKE) return PingResult.Skipped
        if (mode == PingMode.FULL && profile.tunnelType in E2E_TUNNEL_TYPES) return testProfileE2e(profile)
        return try {
            when (val plan = PingPlanner.plan(profile)) {
                is PingPlan.Dns ->
                    if (mode == PingMode.RESOLVER) pingResolvers(profile, plan) else pingDomain(profile, plan)
                is PingPlan.Tcp -> pingTcpTarget(plan.host, plan.port)
                PingPlan.Unmeasurable -> pingHostBestEffort(profile)
                is PingPlan.Invalid -> PingResult.Error(plan.message)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            PingResult.Error(e.message?.take(24) ?: "Failed")
        }
    }

    /** DNS tunnel: does any resolver relay the profile's domain to its tunnel server? */
    private suspend fun pingDomain(profile: ServerProfile, plan: PingPlan.Dns): PingResult {
        val out = DnsProbe.probeProfile(pingTargets(profile), plan.transport, plan.dohUrl, plan.domain)
        return if (out.reached) PingResult.Success(out.best.rttMs) else PingResult.Error(out.best.label)
    }

    /** Client -> resolver round trip (root NS query), best resolver wins. */
    private suspend fun pingResolvers(profile: ServerProfile, plan: PingPlan.Dns): PingResult {
        val targets = if (plan.transport == DnsProbe.Transport.DOH) {
            listOf(DnsProbe.Target("doh", 443))
        } else {
            pingTargets(profile)
        }
        val outs = coroutineScope {
            targets.map { t -> async { DnsProbe.probeLiveness(t, plan.transport, plan.dohUrl) } }.awaitAll()
        }
        val ok = outs.filter { it.reached }
        return if (ok.isNotEmpty()) {
            PingResult.Success(ok.minOf { it.rttMs })
        } else {
            PingResult.Error(DnsProbe.bestFailure(outs).label)
        }
    }

    /** TCP connect: first attempt warms ARP/route caches, the better of two is reported. */
    private suspend fun pingTcpTarget(host: String, port: Int): PingResult {
        val addr = DnsProbe.resolveBounded(host, 3000) ?: return PingResult.Error("DNS failed")
        return withContext(Dispatchers.IO) {
            var best = Long.MAX_VALUE
            repeat(2) { attempt ->
                val t0 = System.nanoTime()
                try {
                    Socket().use { it.connect(InetSocketAddress(addr, port), TCP_PING_TIMEOUT_MS) }
                    best = minOf(best, (System.nanoTime() - t0) / 1_000_000L)
                } catch (e: Exception) {
                    if (attempt == 0) return@withContext PingResult.Error(tcpFailureLabel(e))
                }
            }
            PingResult.Success(best.coerceAtLeast(1))
        }
    }

    /**
     * Protocols whose server speaks UDP/ICMP/raw packets cannot be handshaken without
     * bringing the tunnel up. The host is still timed with a TCP SYN: a connect or a RST
     * both prove the host is alive and give the round trip. Anything else stays blank
     * instead of showing a failure that the protocol itself might not share.
     */
    private suspend fun pingHostBestEffort(profile: ServerProfile): PingResult {
        val host = profile.domain.trim()
        if (host.isEmpty()) return PingResult.Skipped
        val port = if (profile.cdnPort > 0) profile.cdnPort else 443
        val addr = DnsProbe.resolveBounded(host, 3000) ?: return PingResult.Skipped
        return withContext(Dispatchers.IO) {
            val t0 = System.nanoTime()
            try {
                Socket().use { it.connect(InetSocketAddress(addr, port), 2500) }
                PingResult.Success(((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1))
            } catch (e: java.net.ConnectException) {
                if (e.message?.contains("refused", ignoreCase = true) == true) {
                    PingResult.Success(((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1))
                } else {
                    PingResult.Skipped
                }
            } catch (_: Exception) {
                PingResult.Skipped
            }
        }
    }

    private fun tcpFailureLabel(e: Exception): String = when {
        e is java.net.SocketTimeoutException -> "Timeout"
        e is java.net.ConnectException && e.message?.contains("refused", true) == true -> "Refused"
        e is java.net.ConnectException && e.message?.contains("unreachable", true) == true -> "Unreachable"
        else -> e.message?.take(20) ?: "Failed"
    }

    private val singlePingJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    fun pingSingleProfile(profile: ServerProfile) {
        if (profile.tunnelType == TunnelType.SNOWFLAKE) return
        // A second tap while a probe is in flight must not start a competing probe.
        if (singlePingJobs[profile.id]?.isActive == true) return

        publishPing(profile.id, PingResult.Pending)

        singlePingJobs[profile.id] = viewModelScope.launch {
            _uiState.update { it.copy(pingingViaGlobalDns = preferencesDataStore.parsedGlobalResolvers()) }
            var result: PingResult = PingResult.Error("Timeout")
            repeat(2) { attempt ->
                if (result is PingResult.Success) return@repeat
                result = kotlinx.coroutines.withTimeoutOrNull(9_000L) { pingOne(profile, PingMode.DELAY) }
                    ?: PingResult.Error("Timeout")
            }
            publishPing(profile.id, result)
        }
    }

    // ── Live resolver details (DNS-tunnel profiles) ──────────────────
    data class ResolverRow(val host: String, val port: Int, val label: String, val rttMs: Long, val ok: Boolean)

    private val _resolverRows = MutableStateFlow<List<ResolverRow>>(emptyList())
    val resolverRows: StateFlow<List<ResolverRow>> = _resolverRows
    private val _resolverRowsBusy = MutableStateFlow(false)
    val resolverRowsBusy: StateFlow<Boolean> = _resolverRowsBusy

    /** Probes every resolver of [profile] against its tunnel domain and publishes one row per resolver. */
    fun refreshResolverDetails(profile: ServerProfile) {
        if (_resolverRowsBusy.value) return
        val plan = PingPlanner.plan(profile) as? PingPlan.Dns ?: run { _resolverRows.value = emptyList(); return }
        _resolverRowsBusy.value = true
        viewModelScope.launch {
            try {
                val targets = pingTargets(profile)
                val rows = coroutineScope {
                    targets.map { t ->
                        async {
                            val o = DnsProbe.probeResolver(t, plan.transport, plan.dohUrl, plan.domain)
                            ResolverRow(t.host, t.port, o.label, o.rttMs, o.reached)
                        }
                    }.awaitAll()
                }
                _resolverRows.value = rows.sortedWith(compareBy({ !it.ok }, { it.rttMs }))
            } finally {
                _resolverRowsBusy.value = false
            }
        }
    }

    fun cancelPing() {
        pingJob?.cancel()
        pingJob = null
        // Rows still waiting would otherwise spin forever.
        _uiState.update { st ->
            st.copy(
                isPingRunning = false,
                pingResults = st.pingResults.filterValues { it !is PingResult.Pending && it !is PingResult.Testing }
            )
        }
    }

    fun clearPingResults() {
        _uiState.update { it.copy(pingResults = emptyMap(), pingingViaGlobalDns = emptyList()) }
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
        _uiState.update { it.copy(profiles = sorted) }
        viewModelScope.launch {
            profileRepository.updateProfileOrder(sorted.map { it.id })
        }
    }

    fun sortProfilesByProtocol() {
        val current = _uiState.value.profiles
        val sorted = current.sortedBy { it.tunnelType.displayName }
        if (sorted == current) return
        _uiState.update { it.copy(profiles = sorted) }
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
        _uiState.update { it.copy(profiles = sorted) }
        viewModelScope.launch {
            profileRepository.updateProfileOrder(sorted.map { it.id })
        }
    }

    fun sortProfilesByName() {
        val current = _uiState.value.profiles
        val sorted = current.sortedBy { it.name.lowercase() }
        if (sorted == current) return
        _uiState.update { it.copy(profiles = sorted) }
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

    fun deleteSubscriptionGroup(group: String) {
        viewModelScope.launch {
            try {
                preferencesDataStore.deleteSubscriptionUrl(group)
                preferencesDataStore.deleteSubscriptionMeta(group)
            } catch (_: Exception) {}
            deleteProfilesMatching { it.subscriptionGroup == group }
        }
    }

    fun deleteAllSubscriptionGroups() {
        viewModelScope.launch {
            try {
                val urls = preferencesDataStore.subscriptionUrls.first()
                for (group in urls.keys) {
                    preferencesDataStore.deleteSubscriptionUrl(group)
                }
            } catch (_: Exception) {}
            deleteProfilesMatching { it.subscriptionGroup.isNotBlank() }
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
                    publishPing(profile.id, PingResult.Testing(prefix + phase))
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
}
