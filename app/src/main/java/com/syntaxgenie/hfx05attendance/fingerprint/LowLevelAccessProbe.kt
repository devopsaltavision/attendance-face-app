package com.syntaxgenie.hfx05attendance.fingerprint

class LowLevelAccessProbe {
    data class IoctlValue(val value: Int, val errno: Int) {
        fun describe(): String = if (errno == 0) value.toString() else "FAILED (${errnoText(errno)})"
    }

    data class SpiResult(
        val openSuccess: Boolean,
        val openErrno: Int,
        val mode: IoctlValue,
        val bitsPerWord: IoctlValue,
        val maxSpeedHz: IoctlValue,
        val lsbFirst: IoctlValue,
        val closeSuccess: Boolean,
        val closeErrno: Int,
    )

    data class OpenResult(
        val openSuccess: Boolean,
        val openErrno: Int,
        val closeSuccess: Boolean,
        val closeErrno: Int,
    ) {
        fun describe(): String = when {
            !openSuccess -> "FAILED (${errnoText(openErrno)})"
            closeSuccess -> "SUCCESS; close=SUCCESS"
            else -> "SUCCESS; close=FAILED (${errnoText(closeErrno)})"
        }
    }

    data class MtgpioResult(val readOnly: OpenResult, val readWrite: OpenResult)

    data class ActiveProbeResult(
        val spi: SpiResult,
        val transferSuccess: Boolean,
        val transferErrno: Int,
        val ioctlReturn: Int,
        val rx: List<Int>,
    )

    fun probeSpi(): SpiResult {
        val value = nativeProbeSpi()
        return SpiResult(
            openSuccess = value[0] == 1,
            openErrno = value[1],
            mode = IoctlValue(value[2], value[3]),
            bitsPerWord = IoctlValue(value[4], value[5]),
            maxSpeedHz = IoctlValue(value[6], value[7]),
            lsbFirst = IoctlValue(value[8], value[9]),
            closeSuccess = value[10] == 1,
            closeErrno = value[11],
        )
    }

    fun probeMtgpio(): MtgpioResult {
        val value = nativeProbeMtgpio()
        return MtgpioResult(
            readOnly = OpenResult(value[0] == 1, value[1], value[2] == 1, value[3]),
            readWrite = OpenResult(value[4] == 1, value[5], value[6] == 1, value[7]),
        )
    }

    fun readSensorId(): ActiveProbeResult {
        val value = nativeReadSensorId()
        return ActiveProbeResult(
            spi = SpiResult(
                value[0] == 1, value[1], IoctlValue(value[2], value[3]),
                IoctlValue(value[4], value[5]), IoctlValue(value[6], value[7]),
                IoctlValue(value[8], value[9]), value[16] == 1, value[17],
            ),
            transferSuccess = value[10] == 1,
            transferErrno = value[11],
            ioctlReturn = value[12],
            rx = listOf(value[13], value[14], value[15]),
        )
    }

    fun captureRaw(listener: CaptureProgressListener): RawCaptureResult =
        nativeCaptureRaw(listener)

    private external fun nativeProbeSpi(): IntArray
    private external fun nativeProbeMtgpio(): IntArray
    private external fun nativeReadSensorId(): IntArray
    private external fun nativeCaptureRaw(listener: CaptureProgressListener): RawCaptureResult

    private companion object {
        fun errnoText(errno: Int): String = when (errno) {
            0 -> "0"
            1 -> "EPERM (1)"
            2 -> "ENOENT (2)"
            5 -> "EIO (5)"
            13 -> "EACCES (13)"
            19 -> "ENODEV (19)"
            22 -> "EINVAL (22)"
            25 -> "ENOTTY (25)"
            else -> "errno=$errno"
        }

        init {
            System.loadLibrary("x05_safe_probe")
        }
    }
}
