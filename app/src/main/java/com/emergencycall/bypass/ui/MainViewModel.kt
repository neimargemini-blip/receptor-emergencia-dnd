package com.emergencycall.bypass.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.emergencycall.bypass.data.local.AlertStorage
import com.emergencycall.bypass.data.local.ContactStorage
import com.emergencycall.bypass.data.local.PreferencesManager
import com.emergencycall.bypass.data.model.AlertSource
import com.emergencycall.bypass.data.model.EmergencyAlert
import com.emergencycall.bypass.data.model.EmergencyContact
import com.emergencycall.bypass.service.EmergencyCallRingerService
import com.emergencycall.bypass.service.EmergencyMonitorService
import com.emergencycall.bypass.service.SosEmitterService
import com.emergencycall.bypass.util.AppUpdateInfo
import com.emergencycall.bypass.util.LocationHelper
import com.emergencycall.bypass.util.PermissionHelper
import com.emergencycall.bypass.util.UpdateManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PermissionsStatus(
    val hasDndAccess: Boolean = false,
    val hasPhonePermissions: Boolean = false,
    val hasSmsPermissions: Boolean = false,
    val hasSendSmsPermission: Boolean = false,
    val hasLocationPermissions: Boolean = false,
    val isLocationEnabled: Boolean = false,
    val hasCallPhonePermission: Boolean = false,
    val hasReadContactsPermission: Boolean = false,
    val isIgnoringBattery: Boolean = false,
    val hasNotification: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val contactStorage = ContactStorage.getInstance(application)
    private val alertStorage = AlertStorage.getInstance(application)
    private val preferencesManager = PreferencesManager.getInstance(application)

    // 1. Contatos para RECEBER (Autorizados a furar Não Perturbe)
    val receiverContacts: StateFlow<List<EmergencyContact>> = contactStorage.receiverContactsFlow

    // 2. Contatos para ENVIAR SOS (Recebem meu SMS a cada 10s com GPS e ligação viva-voz)
    val senderContacts: StateFlow<List<EmergencyContact>> = contactStorage.senderContactsFlow

    val alerts: StateFlow<List<EmergencyAlert>> = alertStorage.alertsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isMonitoringActive = MutableStateFlow(preferencesManager.isMonitoringActive)
    val isMonitoringActive: StateFlow<Boolean> = _isMonitoringActive.asStateFlow()

    private val _permissionsStatus = MutableStateFlow(PermissionsStatus())
    val permissionsStatus: StateFlow<PermissionsStatus> = _permissionsStatus.asStateFlow()

    // Estados do Modo Emissor de SOS
    val isSosRunning: StateFlow<Boolean> = SosEmitterService.isSosRunning
    val sosStartedAt: StateFlow<Long?> = SosEmitterService.sosStartedAt
    val lastMapsUrl: StateFlow<String?> = SosEmitterService.lastMapsUrl
    val lastCoordinates: StateFlow<Pair<Double, Double>?> = SosEmitterService.lastCoordinates
    val totalSmsSent: StateFlow<Int> = SosEmitterService.totalSmsSent
    val secondsToNextSms: StateFlow<Int> = SosEmitterService.secondsToNextSms

    // Contagem Regressiva de 10 segundos antes do início do SOS real
    private var sosCountdownJob: Job? = null
    private val _sosCountdown = MutableStateFlow<Int?>(null)
    val sosCountdown: StateFlow<Int?> = _sosCountdown.asStateFlow()

    // Simulação de Chamadas / Testes
    private var simulationJob: Job? = null
    private val _simulationCountdown = MutableStateFlow<Int?>(null)
    val simulationCountdown: StateFlow<Int?> = _simulationCountdown.asStateFlow()

    private val _simulationType = MutableStateFlow("Chamada Telefônica")
    val simulationType: StateFlow<String> = _simulationType.asStateFlow()

    // Toque Selecionado
    private val _selectedRingtoneTitle = MutableStateFlow(
        preferencesManager.selectedRingtoneTitle ?: "Alarme Crítico de Emergência (Sirene)"
    )
    val selectedRingtoneTitle: StateFlow<String> = _selectedRingtoneTitle.asStateFlow()

    private val _selectedRingtoneUri = MutableStateFlow(preferencesManager.selectedRingtoneUri)
    val selectedRingtoneUri: StateFlow<String?> = _selectedRingtoneUri.asStateFlow()

    // Auto-Reinício se for fechado / removido dos recentes
    private val _isAutoRestartEnabled = MutableStateFlow(preferencesManager.isAutoRestartEnabled)
    val isAutoRestartEnabled: StateFlow<Boolean> = _isAutoRestartEnabled.asStateFlow()

    // ==================== ATUALIZAÇÕES DO REPOSITÓRIO GITHUB ====================
    private val _updateInfo = MutableStateFlow<AppUpdateInfo?>(null)
    val updateInfo: StateFlow<AppUpdateInfo?> = _updateInfo.asStateFlow()

    private val _isCheckingUpdates = MutableStateFlow(false)
    val isCheckingUpdates: StateFlow<Boolean> = _isCheckingUpdates.asStateFlow()

    private val _updateDownloadProgress = MutableStateFlow<Int?>(null) // null = inativo, 0..100 = baixando
    val updateDownloadProgress: StateFlow<Int?> = _updateDownloadProgress.asStateFlow()

    private val _updateErrorMessage = MutableStateFlow<String?>(null)
    val updateErrorMessage: StateFlow<String?> = _updateErrorMessage.asStateFlow()

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    init {
        refreshPermissions()
        // Garante que o serviço de segundo plano esteja rodando se o monitoramento estiver ativo
        if (preferencesManager.isMonitoringActive) {
            EmergencyMonitorService.start(application)
        }
        // Consulta em segundo plano se há atualizações no repositório GitHub
        checkForUpdates(silent = true)
    }

    fun refreshPermissions() {
        val app = getApplication<Application>()
        _permissionsStatus.value = PermissionsStatus(
            hasDndAccess = PermissionHelper.hasDndAccess(app),
            hasPhonePermissions = PermissionHelper.hasPhonePermissions(app),
            hasSmsPermissions = PermissionHelper.hasSmsPermissions(app),
            hasSendSmsPermission = PermissionHelper.hasSendSmsPermission(app),
            hasLocationPermissions = PermissionHelper.hasLocationPermissions(app),
            isLocationEnabled = LocationHelper.isLocationEnabled(app),
            hasCallPhonePermission = PermissionHelper.hasCallPhonePermission(app),
            hasReadContactsPermission = PermissionHelper.hasReadContactsPermission(app),
            isIgnoringBattery = PermissionHelper.isIgnoringBatteryOptimizations(app),
            hasNotification = PermissionHelper.hasNotificationPermission(app)
        )
    }

    fun toggleMonitoring(active: Boolean) {
        preferencesManager.isMonitoringActive = active
        _isMonitoringActive.value = active
        if (active) {
            EmergencyMonitorService.start(getApplication())
        } else {
            EmergencyMonitorService.stop(getApplication())
        }
    }

    // ==================== CONFIGURAÇÃO DO TOQUE ====================

    fun setSelectedRingtone(uri: String?, title: String?) {
        val toneTitle = if (title.isNullOrBlank()) "Alarme Crítico de Emergência (Sirene)" else title
        preferencesManager.selectedRingtoneUri = uri
        preferencesManager.selectedRingtoneTitle = toneTitle
        _selectedRingtoneTitle.value = toneTitle
        _selectedRingtoneUri.value = uri
    }

    fun resetDefaultRingtone() {
        setSelectedRingtone(null, "Alarme Crítico de Emergência (Sirene)")
    }

    // ==================== AÇÕES PARA CONTATOS DO RECEPTOR ====================

    fun addReceiverContact(name: String, phoneNumber: String) {
        val contact = EmergencyContact(
            name = name.trim(),
            phoneNumber = phoneNumber.trim()
        )
        contactStorage.saveReceiverContact(contact)
    }

    fun removeReceiverContact(contactId: String) {
        contactStorage.removeReceiverContact(contactId)
    }

    fun toggleReceiverContact(contactId: String) {
        contactStorage.toggleReceiverContact(contactId)
    }

    // ==================== AÇÕES PARA CONTATOS DO EMISSOR (SOS) ====================

    fun addSenderContact(name: String, phoneNumber: String) {
        val contact = EmergencyContact(
            name = name.trim(),
            phoneNumber = phoneNumber.trim()
        )
        contactStorage.saveSenderContact(contact)
    }

    fun removeSenderContact(contactId: String) {
        contactStorage.removeSenderContact(contactId)
    }

    fun toggleSenderContact(contactId: String) {
        contactStorage.toggleSenderContact(contactId)
    }

    fun clearAlerts() {
        alertStorage.clearAlerts()
    }

    // ==================== DISPARO DE SOS COM CONTAGEM DE 10s ====================

    /**
     * Dispara o SOS imediatamente ao ser acionado: inicia a sirene crítica, pisca o flash,
     * vibra e envia SMS periódico a cada 10s, só parando quando o usuário interromper.
     */
    fun triggerSosButtonClick() {
        if (isSosRunning.value) {
            // Se já estiver transmitindo SOS, o clique encerra a transmissão imediatamente
            stopEmergencySos()
        } else {
            // Aciona imediatamente o SOS sem espera
            startEmergencySos()
        }
    }

    fun cancelSosCountdown() {
        sosCountdownJob?.cancel()
        sosCountdownJob = null
        _sosCountdown.value = null
    }

    fun startEmergencySos() {
        cancelSosCountdown()
        SosEmitterService.start(getApplication())
    }

    fun stopEmergencySos() {
        cancelSosCountdown()
        SosEmitterService.stop(getApplication())
    }

    // ==================== TESTES E SIMULAÇÕES NA ABA RECEBER ====================

    /**
     * Toca o alarme IMEDIATAMENTE (sem espera) para testar o som a 50% e a fala do contato.
     */
    fun testRingtoneNow(contact: EmergencyContact? = null) {
        val callerName = contact?.name ?: "Contato de Teste"
        val callerNumber = contact?.phoneNumber ?: "(11) 98765-4321"

        EmergencyCallRingerService.start(
            context = getApplication(),
            callerName = callerName,
            callerNumber = callerNumber,
            triggerType = "CALL"
        )
    }

    /**
     * Simula uma chamada telefônica recebida após 5 segundos, dando tempo para bloquear a tela e testar DND.
     */
    fun simulateCallIn5s(contact: EmergencyContact? = null) {
        val callerName = contact?.name ?: "Contato VIP Teste"
        val callerNumber = contact?.phoneNumber ?: "(11) 98765-4321"

        _simulationType.value = "Chamada Telefônica"
        simulationJob?.cancel()
        simulationJob = viewModelScope.launch {
            for (i in 5 downTo 1) {
                _simulationCountdown.value = i
                delay(1000L)
            }
            _simulationCountdown.value = null

            EmergencyCallRingerService.start(
                context = getApplication(),
                callerName = callerName,
                callerNumber = callerNumber,
                triggerType = "CALL"
            )
        }
    }

    /**
     * Simula recebimento de SMS de emergência com mapa após 5 segundos.
     */
    fun simulateSmsIn5s(contact: EmergencyContact? = null) {
        val callerName = contact?.name ?: "Contato VIP Teste"
        val callerNumber = contact?.phoneNumber ?: "(11) 98765-4321"

        _simulationType.value = "SMS de Emergência com Localização"
        simulationJob?.cancel()
        simulationJob = viewModelScope.launch {
            for (i in 5 downTo 1) {
                _simulationCountdown.value = i
                delay(1000L)
            }
            _simulationCountdown.value = null

            val testLocationUrl = "https://www.google.com/maps?q=-23.55052,-46.633308"
            val messageText = "SOS! Preciso de ajuda imediata. Localização: $testLocationUrl"

            alertStorage.addAlert(
                EmergencyAlert(
                    senderName = callerName,
                    senderNumber = callerNumber,
                    messageBody = messageText,
                    mapsUrl = testLocationUrl,
                    source = AlertSource.SMS
                )
            )

            EmergencyCallRingerService.start(
                context = getApplication(),
                callerName = callerName,
                callerNumber = callerNumber,
                triggerType = "SMS",
                messageBody = messageText,
                mapsUrl = testLocationUrl
            )
        }
    }

    fun simulateEmergencyCall(contact: EmergencyContact) = simulateCallIn5s(contact)
    fun simulateEmergencySms(contact: EmergencyContact) = simulateSmsIn5s(contact)

    fun stopTestRinger() {
        EmergencyCallRingerService.stop(getApplication())
    }

    fun cancelSimulation() {
        simulationJob?.cancel()
        simulationJob = null
        _simulationCountdown.value = null
    }

    fun toggleAutoRestart(enabled: Boolean) {
        preferencesManager.isAutoRestartEnabled = enabled
        _isAutoRestartEnabled.value = enabled
    }

    /**
     * Simula o fechamento do serviço e testa se o watchdog de auto-reinício o acorda de volta em 1.5s
     */
    fun testAutoRestartWatchdog() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            // Envia broadcast diretamente para o AutoRestartReceiver para comprovar o despertar
            val intent = android.content.Intent(app, com.emergencycall.bypass.receiver.AutoRestartReceiver::class.java).apply {
                action = com.emergencycall.bypass.receiver.AutoRestartReceiver.ACTION_AUTO_RESTART
            }
            app.sendBroadcast(intent)
        }
    }

    // ==================== GESTÃO DE ATUALIZAÇÕES ====================

    fun checkForUpdates(silent: Boolean = false) {
        viewModelScope.launch {
            _isCheckingUpdates.value = true
            _updateErrorMessage.value = null
            val info = UpdateManager.checkForUpdates(getApplication())
            _isCheckingUpdates.value = false
            _updateInfo.value = info

            if (info != null) {
                if (info.isUpdateAvailable) {
                    _showUpdateDialog.value = true
                } else if (!silent) {
                    _showUpdateDialog.value = true
                }
            } else if (!silent) {
                _updateErrorMessage.value = "Não foi possível verificar atualizações no momento. Verifique sua conexão."
            }
        }
    }

    fun openUpdateDialog() {
        _showUpdateDialog.value = true
    }

    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
    }

    fun startApkDownload(apkUrl: String) {
        viewModelScope.launch {
            _updateDownloadProgress.value = 0
            _updateErrorMessage.value = null
            UpdateManager.downloadAndInstallApk(
                context = getApplication(),
                apkUrl = apkUrl,
                onProgress = { percent ->
                    _updateDownloadProgress.value = percent
                },
                onSuccess = {
                    _updateDownloadProgress.value = null
                },
                onError = { error ->
                    _updateDownloadProgress.value = null
                    _updateErrorMessage.value = error
                }
            )
        }
    }

    fun openRepositoryReleasePage(url: String) {
        UpdateManager.openInBrowser(getApplication(), url)
    }
}
