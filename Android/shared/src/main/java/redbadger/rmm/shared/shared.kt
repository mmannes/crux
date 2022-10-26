// This file was autogenerated by some hot garbage in the `uniffi` crate.
// Trust me, you don't want to mess with it!

@file:Suppress("NAME_SHADOWING")

package redbadger.rmm.shared

// Common helper code.
//
// Ideally this would live in a separate .kt file where it can be unittested etc
// in isolation, and perhaps even published as a re-useable package.
//
// However, it's important that the detils of how this helper code works (e.g. the
// way that different builtin types are passed across the FFI) exactly match what's
// expected by the Rust code on the other side of the interface. In practice right
// now that means coming from the exact some version of `uniffi` that was used to
// compile the Rust component. The easiest way to ensure this is to bundle the Kotlin
// helpers directly inline like we're doing here.

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.ByReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// This is a helper for safely working with byte buffers returned from the Rust code.
// A rust-owned buffer is represented by its capacity, its current length, and a
// pointer to the underlying data.

@Structure.FieldOrder("capacity", "len", "data")
open class RustBuffer : Structure() {
    @JvmField var capacity: Int = 0

    @JvmField var len: Int = 0

    @JvmField var data: Pointer? = null

    class ByValue : RustBuffer(), Structure.ByValue
    class ByReference : RustBuffer(), Structure.ByReference

    companion object {
        internal fun alloc(size: Int = 0) = rustCall() { status ->
            _UniFFILib.INSTANCE.ffi_shared_9554_rustbuffer_alloc(size, status).also {
                if (it.data == null) {
                    throw RuntimeException("RustBuffer.alloc() returned null data pointer (size=$size)")
                }
            }
        }

        internal fun free(buf: RustBuffer.ByValue) = rustCall() { status ->
            _UniFFILib.INSTANCE.ffi_shared_9554_rustbuffer_free(buf, status)
        }
    }

    @Suppress("TooGenericExceptionThrown")
    fun asByteBuffer() =
        this.data?.getByteBuffer(0, this.len.toLong())?.also {
            it.order(ByteOrder.BIG_ENDIAN)
        }
}

/**
 * The equivalent of the `*mut RustBuffer` type.
 * Required for callbacks taking in an out pointer.
 *
 * Size is the sum of all values in the struct.
 */
class RustBufferByReference : ByReference(16) {
    /**
     * Set the pointed-to `RustBuffer` to the given value.
     */
    fun setValue(value: RustBuffer.ByValue) {
        // NOTE: The offsets are as they are in the C-like struct.
        val pointer = getPointer()
        pointer.setInt(0, value.capacity)
        pointer.setInt(4, value.len)
        pointer.setPointer(8, value.data)
    }
}

// This is a helper for safely passing byte references into the rust code.
// It's not actually used at the moment, because there aren't many things that you
// can take a direct pointer to in the JVM, and if we're going to copy something
// then we might as well copy it into a `RustBuffer`. But it's here for API
// completeness.

@Structure.FieldOrder("len", "data")
open class ForeignBytes : Structure() {
    @JvmField var len: Int = 0

    @JvmField var data: Pointer? = null

    class ByValue : ForeignBytes(), Structure.ByValue
}

// The FfiConverter interface handles converter types to and from the FFI
//
// All implementing objects should be public to support external types.  When a
// type is external we need to import it's FfiConverter.
public interface FfiConverter<KotlinType, FfiType> {
    // Convert an FFI type to a Kotlin type
    fun lift(value: FfiType): KotlinType

    // Convert an Kotlin type to an FFI type
    fun lower(value: KotlinType): FfiType

    // Read a Kotlin type from a `ByteBuffer`
    fun read(buf: ByteBuffer): KotlinType

    // Calculate bytes to allocate when creating a `RustBuffer`
    //
    // This must return at least as many bytes as the write() function will
    // write. It can return more bytes than needed, for example when writing
    // Strings we can't know the exact bytes needed until we the UTF-8
    // encoding, so we pessimistically allocate the largest size possible (3
    // bytes per codepoint).  Allocating extra bytes is not really a big deal
    // because the `RustBuffer` is short-lived.
    fun allocationSize(value: KotlinType): Int

    // Write a Kotlin type to a `ByteBuffer`
    fun write(value: KotlinType, buf: ByteBuffer)

    // Lower a value into a `RustBuffer`
    //
    // This method lowers a value into a `RustBuffer` rather than the normal
    // FfiType.  It's used by the callback interface code.  Callback interface
    // returns are always serialized into a `RustBuffer` regardless of their
    // normal FFI type.
    fun lowerIntoRustBuffer(value: KotlinType): RustBuffer.ByValue {
        val rbuf = RustBuffer.alloc(allocationSize(value))
        try {
            val bbuf = rbuf.data!!.getByteBuffer(0, rbuf.capacity.toLong()).also {
                it.order(ByteOrder.BIG_ENDIAN)
            }
            write(value, bbuf)
            rbuf.writeField("len", bbuf.position())
            return rbuf
        } catch (e: Throwable) {
            RustBuffer.free(rbuf)
            throw e
        }
    }

    // Lift a value from a `RustBuffer`.
    //
    // This here mostly because of the symmetry with `lowerIntoRustBuffer()`.
    // It's currently only used by the `FfiConverterRustBuffer` class below.
    fun liftFromRustBuffer(rbuf: RustBuffer.ByValue): KotlinType {
        val byteBuf = rbuf.asByteBuffer()!!
        try {
            val item = read(byteBuf)
            if (byteBuf.hasRemaining()) {
                throw RuntimeException("junk remaining in buffer after lifting, something is very wrong!!")
            }
            return item
        } finally {
            RustBuffer.free(rbuf)
        }
    }
}

// FfiConverter that uses `RustBuffer` as the FfiType
public interface FfiConverterRustBuffer<KotlinType> : FfiConverter<KotlinType, RustBuffer.ByValue> {
    override fun lift(value: RustBuffer.ByValue) = liftFromRustBuffer(value)
    override fun lower(value: KotlinType) = lowerIntoRustBuffer(value)
}

// A handful of classes and functions to support the generated data structures.
// This would be a good candidate for isolating in its own ffi-support lib.
// Error runtime.
@Structure.FieldOrder("code", "error_buf")
internal open class RustCallStatus : Structure() {
    @JvmField var code: Int = 0

    @JvmField var error_buf: RustBuffer.ByValue = RustBuffer.ByValue()

    fun isSuccess(): Boolean {
        return code == 0
    }

    fun isError(): Boolean {
        return code == 1
    }

    fun isPanic(): Boolean {
        return code == 2
    }
}

class InternalException(message: String) : Exception(message)

// Each top-level error class has a companion object that can lift the error from the call status's rust buffer
interface CallStatusErrorHandler<E> {
    fun lift(error_buf: RustBuffer.ByValue): E
}

// Helpers for calling Rust
// In practice we usually need to be synchronized to call this safely, so it doesn't
// synchronize itself

// Call a rust function that returns a Result<>.  Pass in the Error class companion that corresponds to the Err
private inline fun <U, E : Exception> rustCallWithError(errorHandler: CallStatusErrorHandler<E>, callback: (RustCallStatus) -> U): U {
    var status = RustCallStatus()
    val return_value = callback(status)
    if (status.isSuccess()) {
        return return_value
    } else if (status.isError()) {
        throw errorHandler.lift(status.error_buf)
    } else if (status.isPanic()) {
        // when the rust code sees a panic, it tries to construct a rustbuffer
        // with the message.  but if that code panics, then it just sends back
        // an empty buffer.
        if (status.error_buf.len > 0) {
            throw InternalException(FfiConverterString.lift(status.error_buf))
        } else {
            throw InternalException("Rust panic")
        }
    } else {
        throw InternalException("Unknown rust call status: $status.code")
    }
}

// CallStatusErrorHandler implementation for times when we don't expect a CALL_ERROR
object NullCallStatusErrorHandler : CallStatusErrorHandler<InternalException> {
    override fun lift(error_buf: RustBuffer.ByValue): InternalException {
        RustBuffer.free(error_buf)
        return InternalException("Unexpected CALL_ERROR")
    }
}

// Call a rust function that returns a plain value
private inline fun <U> rustCall(callback: (RustCallStatus) -> U): U {
    return rustCallWithError(NullCallStatusErrorHandler, callback)
}

// Contains loading, initialization code,
// and the FFI Function declarations in a com.sun.jna.Library.
@Synchronized
private fun findLibraryName(componentName: String): String {
    val libOverride = System.getProperty("uniffi.component.$componentName.libraryOverride")
    if (libOverride != null) {
        return libOverride
    }
    return "shared"
}

private inline fun <reified Lib : Library> loadIndirect(
    componentName: String
): Lib {
    return Native.load<Lib>(findLibraryName(componentName), Lib::class.java)
}

// A JNA Library to expose the extern-C FFI definitions.
// This is an implementation detail which will be called internally by the public API.

internal interface _UniFFILib : Library {
    companion object {
        internal val INSTANCE: _UniFFILib by lazy {
            loadIndirect<_UniFFILib>(componentName = "shared")
                .also { lib: _UniFFILib ->
                    FfiConverterTypePlatform.register(lib)
                }
        }
    }

    fun ffi_shared_9554_Core_object_free(
        `ptr`: Pointer,
        _uniffi_out_err: RustCallStatus
    ): Unit

    fun shared_9554_Core_new(
        _uniffi_out_err: RustCallStatus
    ): Pointer

    fun shared_9554_Core_update(
        `ptr`: Pointer,
        `msg`: RustBuffer.ByValue,
        _uniffi_out_err: RustCallStatus
    ): RustBuffer.ByValue

    fun ffi_shared_9554_Platform_init_callback(
        `callbackStub`: ForeignCallback,
        _uniffi_out_err: RustCallStatus
    ): Unit

    fun shared_9554_add_for_platform(
        `left`: Int,
        `right`: Int,
        `platform`: Long,
        _uniffi_out_err: RustCallStatus
    ): RustBuffer.ByValue

    fun ffi_shared_9554_rustbuffer_alloc(
        `size`: Int,
        _uniffi_out_err: RustCallStatus
    ): RustBuffer.ByValue

    fun ffi_shared_9554_rustbuffer_from_bytes(
        `bytes`: ForeignBytes.ByValue,
        _uniffi_out_err: RustCallStatus
    ): RustBuffer.ByValue

    fun ffi_shared_9554_rustbuffer_free(
        `buf`: RustBuffer.ByValue,
        _uniffi_out_err: RustCallStatus
    ): Unit

    fun ffi_shared_9554_rustbuffer_reserve(
        `buf`: RustBuffer.ByValue,
        `additional`: Int,
        _uniffi_out_err: RustCallStatus
    ): RustBuffer.ByValue
}

// Public interface members begin here.

public object FfiConverterUByte : FfiConverter<UByte, Byte> {
    override fun lift(value: Byte): UByte {
        return value.toUByte()
    }

    override fun read(buf: ByteBuffer): UByte {
        return lift(buf.get())
    }

    override fun lower(value: UByte): Byte {
        return value.toByte()
    }

    override fun allocationSize(value: UByte) = 1

    override fun write(value: UByte, buf: ByteBuffer) {
        buf.put(value.toByte())
    }
}

public object FfiConverterUInt : FfiConverter<UInt, Int> {
    override fun lift(value: Int): UInt {
        return value.toUInt()
    }

    override fun read(buf: ByteBuffer): UInt {
        return lift(buf.getInt())
    }

    override fun lower(value: UInt): Int {
        return value.toInt()
    }

    override fun allocationSize(value: UInt) = 4

    override fun write(value: UInt, buf: ByteBuffer) {
        buf.putInt(value.toInt())
    }
}

public object FfiConverterString : FfiConverter<String, RustBuffer.ByValue> {
    // Note: we don't inherit from FfiConverterRustBuffer, because we use a
    // special encoding when lowering/lifting.  We can use `RustBuffer.len` to
    // store our length and avoid writing it out to the buffer.
    override fun lift(value: RustBuffer.ByValue): String {
        try {
            val byteArr = ByteArray(value.len)
            value.asByteBuffer()!!.get(byteArr)
            return byteArr.toString(Charsets.UTF_8)
        } finally {
            RustBuffer.free(value)
        }
    }

    override fun read(buf: ByteBuffer): String {
        val len = buf.getInt()
        val byteArr = ByteArray(len)
        buf.get(byteArr)
        return byteArr.toString(Charsets.UTF_8)
    }

    override fun lower(value: String): RustBuffer.ByValue {
        val byteArr = value.toByteArray(Charsets.UTF_8)
        // Ideally we'd pass these bytes to `ffi_bytebuffer_from_bytes`, but doing so would require us
        // to copy them into a JNA `Memory`. So we might as well directly copy them into a `RustBuffer`.
        val rbuf = RustBuffer.alloc(byteArr.size)
        rbuf.asByteBuffer()!!.put(byteArr)
        return rbuf
    }

    // We aren't sure exactly how many bytes our string will be once it's UTF-8
    // encoded.  Allocate 3 bytes per unicode codepoint which will always be
    // enough.
    override fun allocationSize(value: String): Int {
        val sizeForLength = 4
        val sizeForString = value.length * 3
        return sizeForLength + sizeForString
    }

    override fun write(value: String, buf: ByteBuffer) {
        val byteArr = value.toByteArray(Charsets.UTF_8)
        buf.putInt(byteArr.size)
        buf.put(byteArr)
    }
}

// Interface implemented by anything that can contain an object reference.
//
// Such types expose a `destroy()` method that must be called to cleanly
// dispose of the contained objects. Failure to call this method may result
// in memory leaks.
//
// The easiest way to ensure this method is called is to use the `.use`
// helper method to execute a block and destroy the object at the end.
interface Disposable {
    fun destroy()
    companion object {
        fun destroy(vararg args: Any?) {
            args.filterIsInstance<Disposable>()
                .forEach(Disposable::destroy)
        }
    }
}

inline fun <T : Disposable?, R> T.use(block: (T) -> R) =
    try {
        block(this)
    } finally {
        try {
            // N.B. our implementation is on the nullable type `Disposable?`.
            this?.destroy()
        } catch (e: Throwable) {
            // swallow
        }
    }

// The base class for all UniFFI Object types.
//
// This class provides core operations for working with the Rust `Arc<T>` pointer to
// the live Rust struct on the other side of the FFI.
//
// There's some subtlety here, because we have to be careful not to operate on a Rust
// struct after it has been dropped, and because we must expose a public API for freeing
// the Kotlin wrapper object in lieu of reliable finalizers. The core requirements are:
//
//   * Each `FFIObject` instance holds an opaque pointer to the underlying Rust struct.
//     Method calls need to read this pointer from the object's state and pass it in to
//     the Rust FFI.
//
//   * When an `FFIObject` is no longer needed, its pointer should be passed to a
//     special destructor function provided by the Rust FFI, which will drop the
//     underlying Rust struct.
//
//   * Given an `FFIObject` instance, calling code is expected to call the special
//     `destroy` method in order to free it after use, either by calling it explicitly
//     or by using a higher-level helper like the `use` method. Failing to do so will
//     leak the underlying Rust struct.
//
//   * We can't assume that calling code will do the right thing, and must be prepared
//     to handle Kotlin method calls executing concurrently with or even after a call to
//     `destroy`, and to handle multiple (possibly concurrent!) calls to `destroy`.
//
//   * We must never allow Rust code to operate on the underlying Rust struct after
//     the destructor has been called, and must never call the destructor more than once.
//     Doing so may trigger memory unsafety.
//
// If we try to implement this with mutual exclusion on access to the pointer, there is the
// possibility of a race between a method call and a concurrent call to `destroy`:
//
//    * Thread A starts a method call, reads the value of the pointer, but is interrupted
//      before it can pass the pointer over the FFI to Rust.
//    * Thread B calls `destroy` and frees the underlying Rust struct.
//    * Thread A resumes, passing the already-read pointer value to Rust and triggering
//      a use-after-free.
//
// One possible solution would be to use a `ReadWriteLock`, with each method call taking
// a read lock (and thus allowed to run concurrently) and the special `destroy` method
// taking a write lock (and thus blocking on live method calls). However, we aim not to
// generate methods with any hidden blocking semantics, and a `destroy` method that might
// block if called incorrectly seems to meet that bar.
//
// So, we achieve our goals by giving each `FFIObject` an associated `AtomicLong` counter to track
// the number of in-flight method calls, and an `AtomicBoolean` flag to indicate whether `destroy`
// has been called. These are updated according to the following rules:
//
//    * The initial value of the counter is 1, indicating a live object with no in-flight calls.
//      The initial value for the flag is false.
//
//    * At the start of each method call, we atomically check the counter.
//      If it is 0 then the underlying Rust struct has already been destroyed and the call is aborted.
//      If it is nonzero them we atomically increment it by 1 and proceed with the method call.
//
//    * At the end of each method call, we atomically decrement and check the counter.
//      If it has reached zero then we destroy the underlying Rust struct.
//
//    * When `destroy` is called, we atomically flip the flag from false to true.
//      If the flag was already true we silently fail.
//      Otherwise we atomically decrement and check the counter.
//      If it has reached zero then we destroy the underlying Rust struct.
//
// Astute readers may observe that this all sounds very similar to the way that Rust's `Arc<T>` works,
// and indeed it is, with the addition of a flag to guard against multiple calls to `destroy`.
//
// The overall effect is that the underlying Rust struct is destroyed only when `destroy` has been
// called *and* all in-flight method calls have completed, avoiding violating any of the expectations
// of the underlying Rust code.
//
// In the future we may be able to replace some of this with automatic finalization logic, such as using
// the new "Cleaner" functionaility in Java 9. The above scheme has been designed to work even if `destroy` is
// invoked by garbage-collection machinery rather than by calling code (which by the way, it's apparently also
// possible for the JVM to finalize an object while there is an in-flight call to one of its methods [1],
// so there would still be some complexity here).
//
// Sigh...all of this for want of a robust finalization mechanism.
//
// [1] https://stackoverflow.com/questions/24376768/can-java-finalize-an-object-when-it-is-still-in-scope/24380219
//
abstract class FFIObject(
    protected val pointer: Pointer
) : Disposable, AutoCloseable {

    private val wasDestroyed = AtomicBoolean(false)
    private val callCounter = AtomicLong(1)

    protected open fun freeRustArcPtr() {
        // To be overridden in subclasses.
    }

    override fun destroy() {
        // Only allow a single call to this method.
        // TODO: maybe we should log a warning if called more than once?
        if (this.wasDestroyed.compareAndSet(false, true)) {
            // This decrement always matches the initial count of 1 given at creation time.
            if (this.callCounter.decrementAndGet() == 0L) {
                this.freeRustArcPtr()
            }
        }
    }

    @Synchronized
    override fun close() {
        this.destroy()
    }

    internal inline fun <R> callWithPointer(block: (ptr: Pointer) -> R): R {
        // Check and increment the call counter, to keep the object alive.
        // This needs a compare-and-set retry loop in case of concurrent updates.
        do {
            val c = this.callCounter.get()
            if (c == 0L) {
                throw IllegalStateException("${this.javaClass.simpleName} object has already been destroyed")
            }
            if (c == Long.MAX_VALUE) {
                throw IllegalStateException("${this.javaClass.simpleName} call counter would overflow")
            }
        } while (!this.callCounter.compareAndSet(c, c + 1L))
        // Now we can safely do the method call without the pointer being freed concurrently.
        try {
            return block(this.pointer)
        } finally {
            // This decrement aways matches the increment we performed above.
            if (this.callCounter.decrementAndGet() == 0L) {
                this.freeRustArcPtr()
            }
        }
    }
}

public interface CoreInterface {

    fun `update`(`msg`: Msg): Cmd
}

class Core(
    pointer: Pointer
) : FFIObject(pointer), CoreInterface {
    constructor() :
        this(
            rustCall() { _status ->
                _UniFFILib.INSTANCE.shared_9554_Core_new(_status)
            }
        )

    /**
     * Disconnect the object from the underlying Rust object.
     *
     * It can be called more than once, but once called, interacting with the object
     * causes an `IllegalStateException`.
     *
     * Clients **must** call this method once done with the object, or cause a memory leak.
     */
    protected override fun freeRustArcPtr() {
        rustCall() { status ->
            _UniFFILib.INSTANCE.ffi_shared_9554_Core_object_free(this.pointer, status)
        }
    }

    override fun `update`(`msg`: Msg): Cmd =
        callWithPointer {
            rustCall() { _status ->
                _UniFFILib.INSTANCE.shared_9554_Core_update(it, FfiConverterTypeMsg.lower(`msg`), _status)
            }
        }.let {
            FfiConverterTypeCmd.lift(it)
        }
}

public object FfiConverterTypeCore : FfiConverter<Core, Pointer> {
    override fun lower(value: Core): Pointer = value.callWithPointer { it }

    override fun lift(value: Pointer): Core {
        return Core(value)
    }

    override fun read(buf: ByteBuffer): Core {
        // The Rust code always writes pointers as 8 bytes, and will
        // fail to compile if they don't fit.
        return lift(Pointer(buf.getLong()))
    }

    override fun allocationSize(value: Core) = 8

    override fun write(value: Core, buf: ByteBuffer) {
        // The Rust code always expects pointers written as 8 bytes,
        // and will fail to compile if they don't fit.
        buf.putLong(Pointer.nativeValue(lower(value)))
    }
}

sealed class Cmd {
    data class Render(
        val `catFact`: String
    ) : Cmd()
    data class Get(
        val `url`: String
    ) : Cmd()
}

public object FfiConverterTypeCmd : FfiConverterRustBuffer<Cmd> {
    override fun read(buf: ByteBuffer): Cmd {
        return when (buf.getInt()) {
            1 -> Cmd.Render(
                FfiConverterString.read(buf)
            )
            2 -> Cmd.Get(
                FfiConverterString.read(buf)
            )
            else -> throw RuntimeException("invalid enum value, something is very wrong!!")
        }
    }

    override fun allocationSize(value: Cmd) = when (value) {
        is Cmd.Render -> {
            // Add the size for the Int that specifies the variant plus the size needed for all fields
            (
                4 +
                    FfiConverterString.allocationSize(value.`catFact`)
                )
        }
        is Cmd.Get -> {
            // Add the size for the Int that specifies the variant plus the size needed for all fields
            (
                4 +
                    FfiConverterString.allocationSize(value.`url`)
                )
        }
    }

    override fun write(value: Cmd, buf: ByteBuffer) {
        when (value) {
            is Cmd.Render -> {
                buf.putInt(1)
                FfiConverterString.write(value.`catFact`, buf)
                Unit
            }
            is Cmd.Get -> {
                buf.putInt(2)
                FfiConverterString.write(value.`url`, buf)
                Unit
            }
        }.let { /* this makes the `when` an expression, which ensures it is exhaustive */ }
    }
}

sealed class Msg {
    object ClearFact : Msg()

    object GetFact : Msg()

    object FetchFact : Msg()

    data class ReceiveFact(
        val `bytes`: List<UByte>
    ) : Msg()
}

public object FfiConverterTypeMsg : FfiConverterRustBuffer<Msg> {
    override fun read(buf: ByteBuffer): Msg {
        return when (buf.getInt()) {
            1 -> Msg.ClearFact
            2 -> Msg.GetFact
            3 -> Msg.FetchFact
            4 -> Msg.ReceiveFact(
                FfiConverterSequenceUByte.read(buf)
            )
            else -> throw RuntimeException("invalid enum value, something is very wrong!!")
        }
    }

    override fun allocationSize(value: Msg) = when (value) {
        is Msg.ClearFact -> {
            // Add the size for the Int that specifies the variant plus the size needed for all fields
            (
                4
                )
        }
        is Msg.GetFact -> {
            // Add the size for the Int that specifies the variant plus the size needed for all fields
            (
                4
                )
        }
        is Msg.FetchFact -> {
            // Add the size for the Int that specifies the variant plus the size needed for all fields
            (
                4
                )
        }
        is Msg.ReceiveFact -> {
            // Add the size for the Int that specifies the variant plus the size needed for all fields
            (
                4 +
                    FfiConverterSequenceUByte.allocationSize(value.`bytes`)
                )
        }
    }

    override fun write(value: Msg, buf: ByteBuffer) {
        when (value) {
            is Msg.ClearFact -> {
                buf.putInt(1)
                Unit
            }
            is Msg.GetFact -> {
                buf.putInt(2)
                Unit
            }
            is Msg.FetchFact -> {
                buf.putInt(3)
                Unit
            }
            is Msg.ReceiveFact -> {
                buf.putInt(4)
                FfiConverterSequenceUByte.write(value.`bytes`, buf)
                Unit
            }
        }.let { /* this makes the `when` an expression, which ensures it is exhaustive */ }
    }
}

sealed class PlatformException(message: String) : Exception(message) {
    // Each variant is a nested class
    // Flat enums carries a string error message, so no special implementation is necessary.
    class InternalPlatformException(message: String) : PlatformException(message)

    companion object ErrorHandler : CallStatusErrorHandler<PlatformException> {
        override fun lift(error_buf: RustBuffer.ByValue): PlatformException = FfiConverterTypePlatformError.lift(error_buf)
    }
}

public object FfiConverterTypePlatformError : FfiConverterRustBuffer<PlatformException> {
    override fun read(buf: ByteBuffer): PlatformException {
        return when (buf.getInt()) {
            1 -> PlatformException.InternalPlatformException(FfiConverterString.read(buf))
            else -> throw RuntimeException("invalid error enum value, something is very wrong!!")
        }
    }

    override fun allocationSize(value: PlatformException): Int {
        return 4
    }

    override fun write(value: PlatformException, buf: ByteBuffer) {
        when (value) {
            is PlatformException.InternalPlatformException -> {
                buf.putInt(1)
                Unit
            }
        }.let { /* this makes the `when` an expression, which ensures it is exhaustive */ }
    }
}

internal typealias Handle = Long
internal class ConcurrentHandleMap<T>(
    private val leftMap: MutableMap<Handle, T> = mutableMapOf(),
    private val rightMap: MutableMap<T, Handle> = mutableMapOf()
) {
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val currentHandle = AtomicLong(0L)
    private val stride = 1L

    fun insert(obj: T): Handle =
        lock.withLock {
            rightMap[obj]
                ?: currentHandle.getAndAdd(stride)
                .also { handle ->
                    leftMap[handle] = obj
                    rightMap[obj] = handle
                }
        }

    fun get(handle: Handle) = lock.withLock {
        leftMap[handle]
    }

    fun delete(handle: Handle) {
        this.remove(handle)
    }

    fun remove(handle: Handle): T? =
        lock.withLock {
            leftMap.remove(handle)?.let { obj ->
                rightMap.remove(obj)
                obj
            }
        }
}

interface ForeignCallback : com.sun.jna.Callback {
    public fun invoke(handle: Handle, method: Int, args: RustBuffer.ByValue, outBuf: RustBufferByReference): Int
}

// Magic number for the Rust proxy to call using the same mechanism as every other method,
// to free the callback once it's dropped by Rust.
internal const val IDX_CALLBACK_FREE = 0

public abstract class FfiConverterCallbackInterface<CallbackInterface>(
    protected val foreignCallback: ForeignCallback
) : FfiConverter<CallbackInterface, Handle> {
    private val handleMap = ConcurrentHandleMap<CallbackInterface>()

    // Registers the foreign callback with the Rust side.
    // This method is generated for each callback interface.
    internal abstract fun register(lib: _UniFFILib)

    fun drop(handle: Handle): RustBuffer.ByValue {
        return handleMap.remove(handle).let { RustBuffer.ByValue() }
    }

    override fun lift(value: Handle): CallbackInterface {
        return handleMap.get(value) ?: throw InternalException("No callback in handlemap; this is a Uniffi bug")
    }

    override fun read(buf: ByteBuffer) = lift(buf.getLong())

    override fun lower(value: CallbackInterface) =
        handleMap.insert(value).also {
            assert(handleMap.get(it) === value) { "Handle map is not returning the object we just placed there. This is a bug in the HandleMap." }
        }

    override fun allocationSize(value: CallbackInterface) = 8

    override fun write(value: CallbackInterface, buf: ByteBuffer) {
        buf.putLong(lower(value))
    }
}

// Declaration and FfiConverters for Platform Callback Interface

public interface Platform {
    fun `get`(): String
}

// The ForeignCallback that is passed to Rust.
internal class ForeignCallbackTypePlatform : ForeignCallback {
    @Suppress("TooGenericExceptionCaught")
    override fun invoke(handle: Handle, method: Int, args: RustBuffer.ByValue, outBuf: RustBufferByReference): Int {
        val cb = FfiConverterTypePlatform.lift(handle)
        return when (method) {
            IDX_CALLBACK_FREE -> {
                FfiConverterTypePlatform.drop(handle)
                // No return value.
                // See docs of ForeignCallback in `uniffi/src/ffi/foreigncallbacks.rs`
                0
            }
            1 -> {
                // Call the method, write to outBuf and return a status code
                // See docs of ForeignCallback in `uniffi/src/ffi/foreigncallbacks.rs` for info
                try {
                    try {
                        val buffer = this.`invokeGet`(cb, args)
                        // Success
                        outBuf.setValue(buffer)
                        1
                    } catch (e: PlatformException) {
                        // Expected error
                        val buffer = FfiConverterTypePlatformError.lowerIntoRustBuffer(e)
                        outBuf.setValue(buffer)
                        -2
                    }
                } catch (e: Throwable) {
                    // Unexpected error
                    try {
                        // Try to serialize the error into a string
                        outBuf.setValue(FfiConverterString.lower(e.toString()))
                    } catch (e: Throwable) {
                        // If that fails, then it's time to give up and just return
                    }
                    -1
                }
            }

            else -> {
                // An unexpected error happened.
                // See docs of ForeignCallback in `uniffi/src/ffi/foreigncallbacks.rs`
                try {
                    // Try to serialize the error into a string
                    outBuf.setValue(FfiConverterString.lower("Invalid Callaback index"))
                } catch (e: Throwable) {
                    // If that fails, then it's time to give up and just return
                }
                -1
            }
        }
    }

    private fun `invokeGet`(kotlinCallbackInterface: Platform, args: RustBuffer.ByValue): RustBuffer.ByValue =
        try {
            kotlinCallbackInterface.`get`()
                .let {
                    FfiConverterString.lowerIntoRustBuffer(it)
                } // TODO catch errors and report them back to Rust.
            // https://github.com/mozilla/uniffi-rs/issues/351
        } finally {
            RustBuffer.free(args)
        }
}

// The ffiConverter which transforms the Callbacks in to Handles to pass to Rust.
public object FfiConverterTypePlatform : FfiConverterCallbackInterface<Platform>(
    foreignCallback = ForeignCallbackTypePlatform()
) {
    override fun register(lib: _UniFFILib) {
        rustCall() { status ->
            lib.ffi_shared_9554_Platform_init_callback(this.foreignCallback, status)
        }
    }
}

public object FfiConverterSequenceUByte : FfiConverterRustBuffer<List<UByte>> {
    override fun read(buf: ByteBuffer): List<UByte> {
        val len = buf.getInt()
        return List<UByte>(len) {
            FfiConverterUByte.read(buf)
        }
    }

    override fun allocationSize(value: List<UByte>): Int {
        val sizeForLength = 4
        val sizeForItems = value.map { FfiConverterUByte.allocationSize(it) }.sum()
        return sizeForLength + sizeForItems
    }

    override fun write(value: List<UByte>, buf: ByteBuffer) {
        buf.putInt(value.size)
        value.forEach {
            FfiConverterUByte.write(it, buf)
        }
    }
}

@Throws(PlatformException::class)
fun `addForPlatform`(`left`: UInt, `right`: UInt, `platform`: Platform): String {
    return FfiConverterString.lift(
        rustCallWithError(PlatformException) { _status ->
            _UniFFILib.INSTANCE.shared_9554_add_for_platform(FfiConverterUInt.lower(`left`), FfiConverterUInt.lower(`right`), FfiConverterTypePlatform.lower(`platform`), _status)
        }
    )
}
