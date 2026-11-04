package dev.jarvis.universal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for what JARVIS is doing right now. Both the foreground service and
 * the holographic UI observe this object, so the interface never has to guess or poll, and the
 * service keeps working when the interface is closed.
 */
object JarvisState {

    enum class Phase { IDLE, LISTENING, CAPTURING, PROCESSING, SPEAKING, ERROR }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _task = MutableStateFlow("")
    val task: StateFlow<String> = _task.asStateFlow()

    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail.asStateFlow()

    private val _coreOnline = MutableStateFlow<Boolean?>(null)
    val coreOnline: StateFlow<Boolean?> = _coreOnline.asStateFlow()

    private val _coreDetail = MutableStateFlow("not checked")
    val coreDetail: StateFlow<String> = _coreDetail.asStateFlow()

    private val _micMuted = MutableStateFlow(false)
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    private val _wakeEnabled = MutableStateFlow(false)
    val wakeEnabled: StateFlow<Boolean> = _wakeEnabled.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _integrations = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val integrations: StateFlow<Map<String, Boolean>> = _integrations.asStateFlow()

    fun setPhase(phase: Phase) {
        _phase.value = phase
        if (phase != Phase.ERROR) _lastError.value = null
    }

    fun setTask(text: String) {
        _task.value = text.take(280)
    }

    fun setDetail(text: String) {
        _detail.value = text.take(280)
    }

    fun setCoreOnline(online: Boolean, detail: String) {
        _coreOnline.value = online
        _coreDetail.value = detail
    }

    fun setMuted(muted: Boolean) {
        _micMuted.value = muted
    }

    fun setWakeEnabled(enabled: Boolean) {
        _wakeEnabled.value = enabled
    }

    fun setAmplitude(value: Float) {
        _amplitude.value = value.coerceIn(0f, 1f)
    }

    fun setIntegrations(map: Map<String, Boolean>) {
        _integrations.value = map
    }

    fun error(message: String) {
        _lastError.value = message
        _phase.value = Phase.ERROR
    }
}
