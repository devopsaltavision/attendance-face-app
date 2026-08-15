package com.syntaxgenie.hfx05attendance.fingerprint.scanner.hfx05

import com.syntaxgenie.hfx05attendance.fingerprint.scanner.PixelFormat
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.ScannerError
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.ScannerProgress
import com.syntaxgenie.hfx05attendance.fingerprint.scanner.ScannerResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Hfx05FingerprintScannerTest {
    @Test
    fun successfulNativeCaptureMapsToFingerprintImage() {
        val pixels = ByteArray(Hfx05Constants.IMAGE_BYTE_COUNT) { (it % 256).toByte() }
        val scanner = scannerWith(result = Hfx05NativeCaptureResult(true, "capture ok", pixels))

        val result = scanner.capture()

        val image = (result as ScannerResult.Success).value
        assertEquals(Hfx05Constants.IMAGE_WIDTH, image.width)
        assertEquals(Hfx05Constants.IMAGE_HEIGHT, image.height)
        assertEquals(Hfx05Constants.IMAGE_BYTE_COUNT, image.byteCount)
        assertEquals(Hfx05Constants.IMAGE_DPI, image.dpi)
        assertEquals(PixelFormat.GRAYSCALE_8_BIT, image.pixelFormat)
        assertTrue(image.pixels().contentEquals(pixels))
    }

    @Test
    fun hardwareUnavailableDoesNotCallBridge() {
        var bridgeCalled = false
        val scanner = Hfx05FingerprintScanner(
            bridge = Hfx05CaptureBridge {
                bridgeCalled = true
                fail("Bridge must not be called")
                Hfx05NativeCaptureResult(false, "unreachable", null)
            },
            environment = FakeEnvironment(model = "sdk_gphone64_x86_64", existingPaths = emptySet()),
        )

        assertError(ScannerError.HARDWARE_UNAVAILABLE, scanner.isAvailable())
        assertError(ScannerError.HARDWARE_UNAVAILABLE, scanner.capture())
        assertTrue(!bridgeCalled)
    }

    @Test
    fun oneMissingNodeIsHardwareUnavailable() {
        val scanner = Hfx05FingerprintScanner(
            bridge = Hfx05CaptureBridge { fail("Bridge must not be called"); error("unreachable") },
            environment = FakeEnvironment(
                model = "HF-X05",
                existingPaths = setOf(Hfx05Constants.GPIO_DEVICE_PATH),
            ),
        )

        val error = assertError(ScannerError.HARDWARE_UNAVAILABLE, scanner.capture())
        assertEquals("SCN-001", error.error.code)
        assertTrue(error.diagnosticDetails.orEmpty().contains(Hfx05Constants.SPI_DEVICE_PATH))
    }

    @Test
    fun availableNodesAreAuthoritativeWhenModelIsUnexpected() {
        val scanner = Hfx05FingerprintScanner(
            bridge = Hfx05CaptureBridge {
                Hfx05NativeCaptureResult(true, "ok", ByteArray(Hfx05Constants.IMAGE_BYTE_COUNT))
            },
            environment = FakeEnvironment(model = "commercial-device-model", existingPaths = requiredPaths()),
        )

        assertEquals(ScannerResult.Success(true), scanner.isAvailable())
        assertTrue(scanner.capture() is ScannerResult.Success)
        assertTrue(!scanner.diagnostics.snapshot().modelRecognized)
    }

    @Test
    fun nativeCaptureFailureMapsToCaptureFailedAndPreservesReport() {
        val result = scannerWith(
            result = Hfx05NativeCaptureResult(false, "capture stage: FAILED", null),
        ).capture()

        val error = assertError(ScannerError.CAPTURE_FAILED, result)
        assertEquals("SCN-201", error.error.code)
        assertEquals("capture stage: FAILED", error.diagnosticDetails)
    }

    @Test
    fun successWithMissingImageMapsToInvalidImage() {
        val result = scannerWith(Hfx05NativeCaptureResult(true, "reported success", null)).capture()

        assertError(ScannerError.INVALID_IMAGE, result)
    }

    @Test
    fun wrongImageByteCountMapsToInvalidImage() {
        val result = scannerWith(Hfx05NativeCaptureResult(true, "reported success", ByteArray(12))).capture()

        assertError(ScannerError.INVALID_IMAGE, result)
    }

    @Test
    fun bridgeExceptionMapsToUnknownAndPreservesCause() {
        val failure = IllegalStateException("native bridge failed")
        val scanner = Hfx05FingerprintScanner(
            bridge = Hfx05CaptureBridge { throw failure },
            environment = availableEnvironment(),
        )

        val error = assertError(ScannerError.UNKNOWN, scanner.capture())
        assertEquals("SCN-999", error.error.code)
        assertEquals(failure, error.cause)
    }

    @Test
    fun nativeProgressIsMappedWithoutExposingHardwareMessages() {
        val progress = mutableListOf<ScannerProgress>()
        val bridge = Hfx05CaptureBridge { callback ->
            callback("Opening GPIO...")
            callback("Place finger on scanner...")
            callback("Capturing fingerprint...")
            callback("Cleaning up...")
            Hfx05NativeCaptureResult(true, "ok", ByteArray(Hfx05Constants.IMAGE_BYTE_COUNT))
        }

        Hfx05FingerprintScanner(bridge, availableEnvironment()).capture(progress::add)

        assertEquals(
            listOf(
                ScannerProgress.PREPARING,
                ScannerProgress.WAITING_FOR_FINGER,
                ScannerProgress.CAPTURING,
                ScannerProgress.CLEANING_UP,
            ),
            progress,
        )
    }

    @Test
    fun reliableNativeFailuresReceiveSpecificMappings() {
        assertEquals("SCN-202", assertError(
            ScannerError.CAPTURE_TIMEOUT,
            scannerWith(Hfx05NativeCaptureResult(false, "capture stage: FAILED readiness timeout", null)).capture(),
        ).error.code)
        assertEquals("SCN-103", assertError(
            ScannerError.SENSOR_NOT_DETECTED,
            scannerWith(Hfx05NativeCaptureResult(false, "sensor ID: SENSOR NOT RECOGNIZED", null)).capture(),
        ).error.code)
        assertEquals("SCN-104", assertError(
            ScannerError.SENSOR_INITIALIZATION_FAILED,
            scannerWith(Hfx05NativeCaptureResult(false, "sensor table initialization: FAILED", null)).capture(),
        ).error.code)
    }

    @Test
    fun diagnosticsRetainSuccessfulCaptureMetadataAndNativeReport() {
        val scanner = scannerWith(
            Hfx05NativeCaptureResult(true, "native engineering report", ByteArray(Hfx05Constants.IMAGE_BYTE_COUNT)),
        )

        scanner.capture()
        val snapshot = scanner.diagnostics.snapshot()

        assertTrue(snapshot.scannerAvailable == true)
        assertEquals("native engineering report", snapshot.lastTechnicalDetails)
        assertTrue(snapshot.lastCapture?.successful == true)
        assertEquals(Hfx05Constants.IMAGE_WIDTH, snapshot.lastCapture?.width)
        assertEquals(Hfx05Constants.IMAGE_HEIGHT, snapshot.lastCapture?.height)
        assertEquals(Hfx05Constants.IMAGE_BYTE_COUNT, snapshot.lastCapture?.byteCount)
    }

    private fun scannerWith(result: Hfx05NativeCaptureResult) = Hfx05FingerprintScanner(
        bridge = Hfx05CaptureBridge { result },
        environment = availableEnvironment(),
    )

    private fun availableEnvironment() = FakeEnvironment(
        model = "HF-X05",
        existingPaths = requiredPaths(),
    )

    private fun requiredPaths() = setOf(Hfx05Constants.GPIO_DEVICE_PATH, Hfx05Constants.SPI_DEVICE_PATH)

    private fun assertError(expected: ScannerError, result: ScannerResult<*>): ScannerResult.Error {
        assertTrue("Expected ScannerResult.Error but was $result", result is ScannerResult.Error)
        return (result as ScannerResult.Error).also { assertEquals(expected, it.error) }
    }

    private data class FakeEnvironment(
        override val model: String,
        val existingPaths: Set<String>,
    ) : Hfx05Environment {
        override val androidVersion = "11 (API 30)"
        override fun deviceExists(path: String): Boolean = path in existingPaths
    }
}
