package com.github.pacogillet.omniplayer

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary

/**
 * Low-level bridge to the Windows Runtime (WinRT) through JNA/COM.
 *
 * WinRT objects are COM objects (they all derive from `IInspectable`): there is no managed
 * projection on the JVM, so each method is invoked by its index in the object's vtable.
 *
 * Vtable layout reminder — every WinRT interface starts with the six `IInspectable` slots:
 * `0` QueryInterface, `1` AddRef, `2` Release, `3` GetIids, `4` GetRuntimeClassName,
 * `5` GetTrustLevel. Interface-specific methods therefore start at index `6`, in IDL
 * declaration order. The orders used by callers are taken from the `windows-rs` generated
 * bindings (the `*_Vtbl` structs), which are the authoritative source for these offsets.
 */
internal object WinRt {

    /** `combase.dll` — the WinRT runtime activation entry points and the `HSTRING` helpers. */
    interface Combase : StdCallLibrary {
        fun RoInitialize(initType: Int): Int
        fun RoUninitialize()
        // Fast-pass HSTRING creation: references an existing UTF-16 buffer (no heap copy). This is
        // used instead of WindowsCreateString, which did not marshal reliably through JNA here.
        fun WindowsCreateStringReference(
            sourceString: Pointer,
            length: Int,
            hstringHeader: Pointer,
            string: PointerByReference,
        ): Int
        fun WindowsDeleteString(string: Pointer?): Int
        fun WindowsGetStringRawBuffer(string: Pointer, length: IntByReference?): Pointer?
        fun RoGetActivationFactory(activatableClassId: Pointer, iid: Pointer, factory: PointerByReference): Int
    }

    val combase: Combase = Native.load("combase", Combase::class.java)

    /** `RO_INIT_TYPE.RO_INIT_MULTITHREADED` — apartment-free, so objects can be reused on our worker. */
    const val RO_INIT_MULTITHREADED = 1

    // IUnknown
    private const val QUERY_INTERFACE = 0
    private const val RELEASE = 2

    /** `sizeof(HSTRING_HEADER)` on x64 — opaque scratch space for a fast-pass string reference. */
    private const val HSTRING_HEADER_SIZE = 24L

    // IAsyncInfo / IAsyncOperation<T>
    private const val IID_ASYNC_INFO = "{00000036-0000-0000-C000-000000000046}"
    private const val ASYNC_INFO_GET_STATUS = 7
    private const val ASYNC_OPERATION_GET_RESULTS = 8
    private const val ASYNC_STATUS_COMPLETED = 1 // AsyncStatus: Started=0, Completed=1, Canceled=2, Error=3

    /** Cache of native 16-byte GUID buffers, kept referenced so JNA's `Memory` is not collected. */
    private val guidCache = HashMap<String, Memory>()

    /**
     * Native buffer for a `REFIID` argument (for QueryInterface / RoGetActivationFactory).
     *
     * A COM GUID is laid out little-endian for the first three fields (Data1 4 bytes, Data2 and
     * Data3 2 bytes each) followed by Data4 as-is. We build the 16 bytes by hand: JNA's
     * `Guid.GUID.fromString(...).toByteArray()` yields display (big-endian) order, which the
     * runtime rejects with E_NOINTERFACE (0x80004002).
     */
    fun guid(id: String): Pointer = guidCache.getOrPut(id) {
        val hex = id.replace("-", "").replace("{", "").replace("}", "")
        val b = ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val le = byteArrayOf(
            b[3], b[2], b[1], b[0], // Data1 (LE)
            b[5], b[4],             // Data2 (LE)
            b[7], b[6],             // Data3 (LE)
            b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15], // Data4 (as-is)
        )
        Memory(16).apply { write(0, le, 0, 16) }
    }

    /**
     * Invoke vtable method [index] on the COM interface pointer [self] and return the `HRESULT`.
     * The interface pointer is always passed implicitly as the first argument (`this`).
     */
    fun call(self: Pointer, index: Int, vararg args: Any?): Int {
        val vtable = self.getPointer(0)
        val method = vtable.getPointer(index.toLong() * Native.POINTER_SIZE)
        // ALT_CONVENTION == __stdcall on Win32; harmless on x64 where there is a single convention.
        val function = Function.getFunction(method, Function.ALT_CONVENTION)
        val all = arrayOfNulls<Any?>(args.size + 1)
        all[0] = self
        System.arraycopy(args, 0, all, 1, args.size)
        return function.invokeInt(all)
    }

    /** `IUnknown::Release` — must be called on every interface pointer we obtain. */
    fun release(self: Pointer?) {
        if (self != null) call(self, RELEASE)
    }

    /**
     * `IUnknown::QueryInterface` — return the [iid] interface of [self] (caller owns it and must
     * release it), or `null` if it is not supported.
     */
    fun queryInterface(self: Pointer, iid: String): Pointer? {
        val ref = PointerByReference()
        return if (call(self, QUERY_INTERFACE, guid(iid), ref) == 0) ref.value else null
    }

    /**
     * Obtain the activation factory for runtime class [classId] under interface [iid], run [block]
     * with it, and release the factory afterwards. Returns `null` if activation fails.
     */
    fun <T> withActivationFactory(classId: String, iid: String, block: (Pointer) -> T): T? =
        withHString(classId) { classIdHandle ->
            val factoryRef = PointerByReference()
            if (combase.RoGetActivationFactory(classIdHandle, guid(iid), factoryRef) != 0 || factoryRef.value == null) {
                return@withHString null
            }
            val factory = factoryRef.value
            try {
                block(factory)
            } finally {
                release(factory)
            }
        }

    /**
     * Create a fast-pass HSTRING for [value] and run [block] with it. The backing UTF-16 buffer and
     * the `HSTRING_HEADER` must stay alive for as long as the HSTRING is used, so they are held on
     * the stack here and the HSTRING is only valid inside [block]. A string reference owns no heap
     * allocation, so it must not be passed to `WindowsDeleteString`.
     */
    fun <T> withHString(value: String, block: (Pointer) -> T): T? {
        val buffer = Memory((value.length + 1).toLong() * 2).apply { setWideString(0, value) }
        val header = Memory(HSTRING_HEADER_SIZE).apply { clear() }
        val out = PointerByReference()
        if (combase.WindowsCreateStringReference(buffer, value.length, header, out) != 0 || out.value == null) {
            return null
        }
        return block(out.value)
    }

    fun readHString(handle: Pointer?): String {
        if (handle == null) return ""
        val length = IntByReference()
        val buffer = combase.WindowsGetStringRawBuffer(handle, length) ?: return ""
        return if (length.value == 0) "" else String(buffer.getCharArray(0, length.value))
    }

    /**
     * Block until the WinRT [asyncOperation] completes, then return its result interface pointer
     * (or `null`). The async operation is always released; the returned interface is owned by the
     * caller. Completion is detected by polling `IAsyncInfo::Status` — this avoids having to
     * implement a COM completion delegate, and GSMTC operations resolve almost instantly.
     */
    fun awaitInterface(asyncOperation: Pointer, timeoutMs: Long = 4000): Pointer? {
        try {
            if (!awaitCompletion(asyncOperation, timeoutMs)) return null
            val result = PointerByReference()
            return if (call(asyncOperation, ASYNC_OPERATION_GET_RESULTS, result) == 0) result.value else null
        } finally {
            release(asyncOperation)
        }
    }

    /** Await an `IAsyncOperation<Boolean>` whose result we don't need, then release it. */
    fun awaitVoid(asyncOperation: Pointer, timeoutMs: Long = 4000) {
        try {
            if (awaitCompletion(asyncOperation, timeoutMs)) {
                // Drain the boolean result so the operation is properly closed.
                call(asyncOperation, ASYNC_OPERATION_GET_RESULTS, Memory(1))
            }
        } finally {
            release(asyncOperation)
        }
    }

    /**
     * Await an `IAsyncOperation<UInt32>` (e.g. `DataReader.LoadAsync`) and return its result, or
     * `-1` on failure. The operation is always released.
     */
    fun awaitUInt(asyncOperation: Pointer, timeoutMs: Long = 4000): Int {
        try {
            if (!awaitCompletion(asyncOperation, timeoutMs)) return -1
            val result = IntByReference()
            return if (call(asyncOperation, ASYNC_OPERATION_GET_RESULTS, result) == 0) result.value else -1
        } finally {
            release(asyncOperation)
        }
    }

    private fun awaitCompletion(asyncOperation: Pointer, timeoutMs: Long): Boolean {
        val infoRef = PointerByReference()
        if (call(asyncOperation, QUERY_INTERFACE, guid(IID_ASYNC_INFO), infoRef) != 0 || infoRef.value == null) {
            return false
        }
        val asyncInfo = infoRef.value
        try {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (true) {
                val status = IntByReference()
                if (call(asyncInfo, ASYNC_INFO_GET_STATUS, status) != 0) return false
                if (status.value == ASYNC_STATUS_COMPLETED) return true
                if (status.value > ASYNC_STATUS_COMPLETED) return false // Canceled or Error
                if (System.nanoTime() > deadline) return false
                Thread.sleep(8)
            }
        } finally {
            release(asyncInfo)
        }
    }
}
