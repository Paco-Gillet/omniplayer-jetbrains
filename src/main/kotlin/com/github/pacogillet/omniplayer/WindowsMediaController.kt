package com.github.pacogillet.omniplayer

import com.github.pacogillet.omniplayer.WinRt.combase
import com.intellij.openapi.diagnostic.Logger
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Controls the active Windows media session directly through the WinRT API
 * `Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager` ("GSMTC") — the same
 * system component that the hardware media keys and the Windows volume overlay drive.
 *
 * Compared with simulating media keys, this targets the current session explicitly (no global
 * broadcast), exposes the real playback state, and can read the now-playing metadata.
 *
 * Every WinRT call is funnelled onto a single dedicated thread: WinRT objects are apartment-bound,
 * so the thread is initialised once (`RoInitialize`, multithreaded apartment) and all interactions
 * are posted to it.
 */
object WindowsMediaController {

    /** Snapshot of the current session, exposed to the UI. */
    data class NowPlaying(val title: String, val artist: String, val isPlaying: Boolean)

    private val log = Logger.getInstance(WindowsMediaController::class.java)

    private const val CLASS_NAME =
        "Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager"
    private const val IID_SESSION_MANAGER_STATICS = "{2050C4EE-11A0-57DE-AED7-C97C70338245}"

    // Vtable indices — taken verbatim from the windows-rs `*_Vtbl` structs (the ABI order, which
    // is NOT the documentation order). All are offset by the 6 IInspectable base slots.
    private const val STATICS_REQUEST_ASYNC = 6
    private const val MANAGER_GET_CURRENT_SESSION = 6
    private const val SESSION_TRY_GET_MEDIA_PROPERTIES_ASYNC = 7
    private const val SESSION_GET_PLAYBACK_INFO = 9
    private const val SESSION_TRY_SKIP_NEXT_ASYNC = 16
    private const val SESSION_TRY_SKIP_PREVIOUS_ASYNC = 17
    private const val SESSION_TRY_TOGGLE_PLAY_PAUSE_ASYNC = 20
    private const val PLAYBACK_INFO_GET_PLAYBACK_STATUS = 7
    private const val MEDIA_PROPERTIES_GET_TITLE = 6
    private const val MEDIA_PROPERTIES_GET_ARTIST = 9

    /** GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing */
    private const val PLAYBACK_STATUS_PLAYING = 4

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OmniPlayer-WinRT").apply { isDaemon = true }
    }

    @Volatile private var roInitialized = false
    /** Cached IGlobalSystemMediaTransportControlsSessionManager (lives for the IDE session). */
    private var sessionManager: Pointer? = null

    // --- Public API (callable from any thread; work is marshalled onto the worker) ---

    fun playPause() = post { withCurrentSession { tryAsync(it, SESSION_TRY_TOGGLE_PLAY_PAUSE_ASYNC) } }
    fun nextTrack() = post { withCurrentSession { tryAsync(it, SESSION_TRY_SKIP_NEXT_ASYNC) } }
    fun previousTrack() = post { withCurrentSession { tryAsync(it, SESSION_TRY_SKIP_PREVIOUS_ASYNC) } }

    /** Reads the current session's metadata and playback state, or `null` when nothing is playing. */
    fun getNowPlaying(): NowPlaying? = compute { readNowPlaying() }

    // --- Worker-thread internals ---

    private fun ensureReady() {
        if (!roInitialized) {
            combase.RoInitialize(WinRt.RO_INIT_MULTITHREADED)
            roInitialized = true
        }
        if (sessionManager == null) sessionManager = requestSessionManager()
    }

    private fun requestSessionManager(): Pointer? = WinRt.withHString(CLASS_NAME) { classId ->
        val factoryRef = PointerByReference()
        if (combase.RoGetActivationFactory(classId, WinRt.guid(IID_SESSION_MANAGER_STATICS), factoryRef) != 0) {
            return@withHString null
        }
        val statics = factoryRef.value ?: return@withHString null
        try {
            val asyncRef = PointerByReference()
            if (WinRt.call(statics, STATICS_REQUEST_ASYNC, asyncRef) != 0 || asyncRef.value == null) {
                return@withHString null
            }
            WinRt.awaitInterface(asyncRef.value)
        } finally {
            WinRt.release(statics)
        }
    }

    private inline fun withCurrentSession(action: (Pointer) -> Unit) {
        val session = currentSession() ?: return
        try {
            action(session)
        } finally {
            WinRt.release(session)
        }
    }

    private fun currentSession(): Pointer? {
        val manager = sessionManager ?: return null
        val ref = PointerByReference()
        if (WinRt.call(manager, MANAGER_GET_CURRENT_SESSION, ref) != 0) return null
        return ref.value // null when no app currently owns a media session
    }

    private fun tryAsync(session: Pointer, vtableIndex: Int) {
        val asyncRef = PointerByReference()
        if (WinRt.call(session, vtableIndex, asyncRef) == 0 && asyncRef.value != null) {
            WinRt.awaitVoid(asyncRef.value)
        }
    }

    private fun readNowPlaying(): NowPlaying? {
        val session = currentSession() ?: return null
        try {
            val isPlaying = readIsPlaying(session)
            var title = ""
            var artist = ""
            val asyncRef = PointerByReference()
            if (WinRt.call(session, SESSION_TRY_GET_MEDIA_PROPERTIES_ASYNC, asyncRef) == 0 && asyncRef.value != null) {
                val properties = WinRt.awaitInterface(asyncRef.value)
                if (properties != null) {
                    try {
                        title = readHStringProperty(properties, MEDIA_PROPERTIES_GET_TITLE)
                        artist = readHStringProperty(properties, MEDIA_PROPERTIES_GET_ARTIST)
                    } finally {
                        WinRt.release(properties)
                    }
                }
            }
            return NowPlaying(title, artist, isPlaying)
        } finally {
            WinRt.release(session)
        }
    }

    private fun readIsPlaying(session: Pointer): Boolean {
        val infoRef = PointerByReference()
        if (WinRt.call(session, SESSION_GET_PLAYBACK_INFO, infoRef) != 0 || infoRef.value == null) return false
        val playbackInfo = infoRef.value
        try {
            val status = IntByReference()
            return WinRt.call(playbackInfo, PLAYBACK_INFO_GET_PLAYBACK_STATUS, status) == 0 &&
                status.value == PLAYBACK_STATUS_PLAYING
        } finally {
            WinRt.release(playbackInfo)
        }
    }

    private fun readHStringProperty(obj: Pointer, vtableIndex: Int): String {
        val ref = PointerByReference()
        if (WinRt.call(obj, vtableIndex, ref) != 0) return ""
        val handle = ref.value
        try {
            return WinRt.readHString(handle)
        } finally {
            combase.WindowsDeleteString(handle)
        }
    }

    // --- Worker dispatch ---

    private fun post(task: () -> Unit) {
        worker.submit {
            try {
                ensureReady()
                task()
            } catch (t: Throwable) {
                log.warn("WinRT media action failed", t)
            }
        }
    }

    private fun <T> compute(task: () -> T): T? {
        return try {
            worker.submit(Callable {
                ensureReady()
                task()
            }).get(5, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            log.warn("WinRT media query failed", t)
            null
        }
    }
}
