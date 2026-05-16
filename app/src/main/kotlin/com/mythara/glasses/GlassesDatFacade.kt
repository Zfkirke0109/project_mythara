package com.mythara.glasses

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.removeStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.removeDisplay
import com.meta.wearable.dat.display.types.DisplayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Thin façade over the Meta DAT SDK so the rest of Mythara doesn't
 * directly import `com.meta.wearable.dat.*`.
 *
 * ## Why this exists
 *
 * Meta DAT lives on GitHub Packages and needs a personal-access token
 * with `read:packages` scope to pull (`github_token=...` in
 * `local.properties`, gitignored — see `settings.gradle.kts`).
 *
 * Wrapping the SDK behind this single object keeps DAT-specific types
 * out of every other v3 file: [GlassesGestureRouter], [GlassesScreenStore],
 * [GlassesConnectionService], the photo ingester, the face pipeline,
 * the GlassesMemoryScreen UI — none of them import DAT classes.
 *
 * ## Lifecycle expected by [GlassesConnectionService]
 *
 *  1. [initializeIfAvailable] on `Application.onCreate` (or lazily).
 *  2. [startRegistration] from a user-driven Settings action when the
 *     user wants to pair Mythara with Meta AI.
 *  3. [startSession] after [connectionState] reports `Paired`. The FGS
 *     calls this as soon as it starts.
 *  4. [render] for every [GlassesScreenStore] transition.
 *  5. [capturePhoto] from [photo.GlassesPhotoCapture].
 *  6. [stopSession] on FGS teardown.
 *
 * ## Display button callbacks
 *
 * The DAT display DSL takes per-button `onClick` lambdas. [GlassesScreenRenderer]
 * wires each one to [publishEvent] so the rest of the app can subscribe
 * to [events] without touching DAT.
 *
 * ## DatResult ergonomics
 *
 * The SDK's `DatResult<T, E : DatError>` exposes `fold(onSuccess, onFailure(err, throwable))`
 * and `onFailure { err, _ -> ... }` — error-typed two-arg lambdas. The
 * single-arg `getOrElse { throwable -> ... }` form gives back a Throwable,
 * NOT the typed DatError. We use `fold` whenever we want the typed error
 * (so we can read `.description`).
 */
object GlassesDatFacade {

    private const val TAG = "Mythara/GlassesDAT"

    /** Scope for the registration / session / display / stream state
     *  collectors. Cancelled-and-recreated only on full process death;
     *  the [stopSession] path tears down individual collectors with
     *  [Job.cancel] rather than killing the scope. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var initializedOnce = false
    private var session: DeviceSession? = null
    private var stream: Stream? = null
    private var display: Display? = null

    private var registrationJob: Job? = null
    private var sessionStateJob: Job? = null
    private var sessionErrorJob: Job? = null
    private var streamStateJob: Job? = null
    private var displayStateJob: Job? = null

    private val _connectionState = MutableStateFlow(GlassesConnectionState.NotInitialized)
    val connectionState: StateFlow<GlassesConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<GlassesEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<GlassesEvent> = _events.asSharedFlow()

    fun isAvailable(): Boolean = initializedOnce

    /** Idempotent SDK boot. Safe to call from Application.onCreate even
     *  before the user has installed Meta AI — registrationState will
     *  remain `AVAILABLE` until they kick off [startRegistration]. */
    fun initializeIfAvailable(context: Context) {
        if (initializedOnce) return
        initializedOnce = true
        runCatching {
            Wearables.initialize(context.applicationContext).onFailure { error, _ ->
                Log.w(TAG, "Wearables.initialize failed: ${error.description}")
                _connectionState.value = GlassesConnectionState.Error
            }
        }.onFailure {
            Log.w(TAG, "Wearables.initialize threw: ${it.message}")
            _connectionState.value = GlassesConnectionState.Error
            return
        }
        _connectionState.value = GlassesConnectionState.Initialized
        observeRegistration()
    }

    /** Surface registration UI through the Meta AI app. Called from a
     *  Settings panel button — the user comes back into Mythara via the
     *  callback URI scheme declared in AndroidManifest. */
    fun startRegistration(activity: Activity) {
        runCatching { Wearables.startRegistration(activity) }
            .onFailure { Log.w(TAG, "startRegistration threw: ${it.message}") }
    }

    fun startUnregistration(activity: Activity) {
        runCatching { Wearables.startUnregistration(activity) }
            .onFailure { Log.w(TAG, "startUnregistration threw: ${it.message}") }
    }

    /** Build a session against a display-capable device, then attach the
     *  camera stream + display capability. Returns true once both
     *  capabilities have reported their STARTED state. */
    suspend fun startSession(): Boolean {
        if (!initializedOnce) {
            Log.w(TAG, "startSession before initializeIfAvailable — no-op")
            return false
        }
        if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
            Log.i(TAG, "startSession skipped — registration state is ${Wearables.registrationState.value}")
            return false
        }
        if (session != null) {
            Log.d(TAG, "startSession called but session already active")
            return true
        }

        var newSession: DeviceSession? = null
        Wearables.createSession(
            AutoDeviceSelector(filter = { it.isDisplayCapable() }),
        ).fold(
            onSuccess = { newSession = it },
            onFailure = { err, _ ->
                Log.w(TAG, "createSession failed: ${err.description}")
            },
        )
        val s = newSession ?: return false
        session = s

        // Observe session state + errors. Display + stream are added
        // only after we see DeviceSessionState.STARTED.
        sessionErrorJob = scope.launch {
            s.errors.collect { err ->
                Log.w(TAG, "session.error: ${err.description}")
            }
        }
        sessionStateJob = scope.launch {
            s.state.collect { state ->
                Log.d(TAG, "session.state -> $state")
                when (state) {
                    DeviceSessionState.STARTED -> {
                        if (stream == null) attachStream(s)
                        if (display == null) attachDisplay(s)
                    }
                    DeviceSessionState.STOPPED -> {
                        _connectionState.value = GlassesConnectionState.Disconnected
                    }
                    else -> Unit
                }
            }
        }

        s.start()
        return true
    }

    private fun attachStream(s: DeviceSession) {
        var newStream: Stream? = null
        s.addStream(
            StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24),
        ).fold(
            onSuccess = { newStream = it },
            onFailure = { err, _ -> Log.w(TAG, "addStream failed: ${err.description}") },
        )
        val st = newStream ?: return
        stream = st
        streamStateJob = scope.launch {
            st.state.collect { state -> Log.d(TAG, "stream.state -> $state") }
        }
        st.start().onFailure { err, _ ->
            Log.w(TAG, "stream.start failed: ${err.description}")
        }
    }

    private fun attachDisplay(s: DeviceSession) {
        s.addDisplay().fold(
            onSuccess = { newDisplay ->
                display = newDisplay
                displayStateJob = scope.launch {
                    newDisplay.state.collect { st ->
                        Log.d(TAG, "display.state -> $st")
                        if (st == DisplayState.STARTED) {
                            _connectionState.value = GlassesConnectionState.SessionActive
                            // Push a Root render so the user sees something
                            // the moment the display comes alive.
                            runCatching { render(GlassesScreen.Root) }
                        }
                    }
                }
            },
            onFailure = { err, _ ->
                Log.w(TAG, "addDisplay failed: ${err.description}")
            },
        )
    }

    fun stopSession() {
        runCatching { stream?.stop() }
        runCatching { session?.removeStream() }
        runCatching { session?.removeDisplay() }
        runCatching { session?.stop() }
        stream = null
        display = null
        session = null
        streamStateJob?.cancel(); streamStateJob = null
        displayStateJob?.cancel(); displayStateJob = null
        sessionStateJob?.cancel(); sessionStateJob = null
        sessionErrorJob?.cancel(); sessionErrorJob = null
        if (Wearables.registrationState.value == RegistrationState.REGISTERED) {
            _connectionState.value = GlassesConnectionState.Paired
        } else {
            _connectionState.value = GlassesConnectionState.Disconnected
        }
    }

    /** Capture a still from the glasses POV. Returns null when no
     *  stream is attached or the capture fails — caller (the photo
     *  ingester) logs and shows a [GlassesScreen.Error]. */
    suspend fun capturePhoto(): Bitmap? {
        val s = stream ?: run {
            Log.w(TAG, "capturePhoto with no stream attached")
            return null
        }
        if (s.state.value != StreamState.STREAMING && s.state.value != StreamState.STARTED) {
            Log.w(TAG, "capturePhoto while stream is ${s.state.value}")
        }
        var bitmap: Bitmap? = null
        s.capturePhoto().fold(
            onSuccess = { photoData ->
                bitmap = when (photoData) {
                    is PhotoData.Bitmap -> photoData.bitmap
                    is PhotoData.HEIC -> decodeByteBuffer(photoData.data)
                    else -> {
                        Log.w(TAG, "unknown PhotoData subtype ${photoData::class.simpleName}")
                        null
                    }
                }
            },
            onFailure = { err, _ ->
                Log.w(TAG, "capturePhoto failed: ${err.description}")
            },
        )
        return bitmap
    }

    private fun decodeByteBuffer(buf: ByteBuffer): Bitmap? {
        return runCatching {
            val bytes = ByteArray(buf.remaining())
            buf.duplicate().get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    /** Render a Mythara-level [GlassesScreen] onto the glasses display
     *  via the DAT `sendContent` DSL. Returns true on success. */
    suspend fun render(screen: GlassesScreen): Boolean {
        val d = display ?: run {
            Log.d(TAG, "render(${screen::class.simpleName}) skipped — display not attached")
            return false
        }
        if (d.state.value != DisplayState.STARTED) {
            Log.d(TAG, "render(${screen::class.simpleName}) skipped — display.state=${d.state.value}")
            return false
        }
        var ok = true
        runCatching {
            GlassesScreenRenderer.render(d, screen) { evt -> publishEvent(evt) }
        }.onFailure {
            Log.w(TAG, "renderer threw on $screen: ${it.message}")
            ok = false
        }
        return ok
    }

    /** Fired by [GlassesScreenRenderer] from each DAT-display button's
     *  `onClick` callback. Also reachable from in-app tests for
     *  simulating events without real glasses. */
    fun publishEvent(event: GlassesEvent) {
        _events.tryEmit(event)
    }

    private fun observeRegistration() {
        registrationJob?.cancel()
        registrationJob = scope.launch {
            Wearables.registrationState.collect { state ->
                Log.d(TAG, "registrationState -> $state")
                _connectionState.value = when (state) {
                    RegistrationState.REGISTERED -> GlassesConnectionState.Paired
                    RegistrationState.AVAILABLE -> GlassesConnectionState.Initialized
                    RegistrationState.UNAVAILABLE -> GlassesConnectionState.NotInitialized
                    RegistrationState.UNREGISTERING -> GlassesConnectionState.Initialized
                    RegistrationState.REGISTERING -> _connectionState.value
                    else -> _connectionState.value
                }
            }
        }
    }
}

/** Lifecycle phases the rest of Mythara can render UI from. */
enum class GlassesConnectionState {
    /** DAT not on classpath OR Wearables.initialize hasn't been called. */
    NotInitialized,
    /** SDK initialized; not yet paired with Meta AI. */
    Initialized,
    /** Paired with Meta AI; glasses discoverable but no active session. */
    Paired,
    /** Active DeviceSession + Stream + Display capability all STARTED. */
    SessionActive,
    /** Session ended (user removed glasses, BT dropped, etc.). */
    Disconnected,
    /** Error state — surface description via GlassesPanel. */
    Error,
}
