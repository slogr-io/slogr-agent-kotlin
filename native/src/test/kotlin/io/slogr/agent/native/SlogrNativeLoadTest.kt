package io.slogr.agent.native

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Verifies that libslogr-native.so loads successfully on Linux.
 * Requires the library to be built first via `make -C native/`.
 * Skipped on non-Linux platforms.
 */
@EnabledOnOs(OS.LINUX)
class SlogrNativeLoadTest {

    @Test
    fun `SlogrNative loads without error on Linux`() {
        // Accessing SlogrNative triggers its init block.
        // If loading fails, isLoaded is false and requireLoaded() throws.
        assertTrue(SlogrNative.isLoaded,
            "libslogr-native.so failed to load. " +
            "Build it first with: make -C agent-kotlin/native/. " +
            "Set SLOGR_NATIVE_DIR to the output directory.")
    }

    @Test
    fun `requireLoaded does not throw when library is present`() {
        if (!SlogrNative.isLoaded) {
            println("SKIP: library not loaded — set SLOGR_NATIVE_DIR")
            return
        }
        SlogrNative.requireLoaded()  // must not throw
    }
}
