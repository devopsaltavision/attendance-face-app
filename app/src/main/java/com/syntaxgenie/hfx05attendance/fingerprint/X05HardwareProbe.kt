package com.syntaxgenie.hfx05attendance.fingerprint

import android.content.Context
import android.content.pm.ComponentInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Locale

class X05HardwareProbe(private val context: Context) {
    data class Report(
        val isX05: Boolean,
        val spiExists: Boolean,
        val spiReadable: Boolean,
        val spiWritable: Boolean,
        val commonApiPresent: Boolean,
        val requiredClassResults: Map<String, Boolean>,
        val nativeLibraryResults: Map<String, List<String>>,
        val spiMetadata: List<String>,
        val spiMasters: List<String>,
        val deviceTreeMatches: List<String>,
        val gpioMetadata: List<String>,
        val lowLevelAccessMetadata: List<String>,
        val selinuxMetadata: List<String>,
        val vendorPackageMetadata: List<String>,
        val relatedClasses: List<String>,
        val relatedServices: List<String>,
        val relatedPackages: List<String>,
        val deviceFileMetadata: List<String>,
        val packageName: String,
    ) {
        fun asText(scannerResult: String): String = buildString {
            appendLine("HF-X05 Hardware Diagnostic Report")
            appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(System.currentTimeMillis())}")
            appendLine()
            appendLine("=== LIVE DEVICE RESULT ===")
            appendLine("--- DEVICE ---")
            appendLine("Device model: ${Build.MODEL}")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Brand: ${Build.BRAND}")
            appendLine("Device: ${Build.DEVICE}")
            appendLine("Board: ${Build.BOARD}")
            appendLine("Display: ${Build.DISPLAY}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("App UID/package: ${Process.myUid()} / $packageName")
            appendLine("Recognized HF-X05: $isX05")
            appendLine("--- DEVICE FILES ---")
            deviceFileMetadata.forEach { appendLine(it) }
            appendLine("/dev/spidev3.0 exists: $spiExists")
            appendLine("/dev/spidev3.0 readable: $spiReadable")
            appendLine("/dev/spidev3.0 writable: $spiWritable")
            appendLine("Required Java/runtime classes:")
            requiredClassResults.forEach { (className, present) ->
                appendLine("  $className: ${present.asPresence()}")
            }
            appendLine("Native libraries in system/vendor runtime locations (not loaded):")
            nativeLibraryResults.forEach { (libraryName, locations) ->
                appendLine("  $libraryName: ${locations.isNotEmpty().asPresence()}")
                locations.forEach { location -> appendLine("    $location") }
            }
            appendLine("SPI device metadata (read-only):")
            spiMetadata.ifEmpty { listOf("not readable") }.forEach { appendLine("  $it") }
            appendLine("SPI masters (read-only):")
            spiMasters.ifEmpty { listOf("not readable") }.forEach { appendLine("  $it") }
            appendLine("Relevant device-tree entries (read-only, bounded scan):")
            deviceTreeMatches.ifEmpty { listOf("none readable") }.forEach { appendLine("  $it") }
            appendLine("GPIO 164 metadata (read-only; GPIO was not toggled):")
            gpioMetadata.ifEmpty { listOf("not readable") }.forEach { appendLine("  $it") }
            appendLine("SPI capability probe (RD ioctls only; no SPI transfer):")
            lowLevelAccessMetadata.filter { it.startsWith("SPI ") }
                .ifEmpty { listOf("probe unavailable") }
                .forEach { appendLine("  ${it.removePrefix("SPI ")}") }
            appendLine("MTGPIO access probe (open/close only; no ioctl):")
            lowLevelAccessMetadata.filter { it.startsWith("MTGPIO ") }
                .ifEmpty { listOf("probe unavailable") }
                .forEach { appendLine("  ${it.removePrefix("MTGPIO ")}") }
            appendLine("SELinux/runtime:")
            selinuxMetadata.forEach { appendLine("  $it") }
            appendLine("Vendor package metadata (PackageManager only; components were not invoked):")
            vendorPackageMetadata.ifEmpty { listOf("no matching visible packages") }
                .forEach { appendLine("  $it") }
            appendLine("Related runtime classes: ${relatedClasses.ifEmpty { listOf("none") }.joinToString()}")
            appendLine("Accessible related services: ${relatedServices.ifEmpty { listOf("none") }.joinToString()}")
            appendLine("Binder service names: not enumerated (Android has no public API for arbitrary Binder service listing)")
            appendLine("Visible related vendor packages (Android visibility rules apply): ${relatedPackages.ifEmpty { listOf("none") }.joinToString()}")
            appendLine("Fingerprint implementation: REAL / X05FingerprintManager")
            appendLine("Scanner initialization: $scannerResult")
            appendLine()
            appendLine("=== REFERENCE INFORMATION (NOT LIVE DETECTION) ===")
            appendLine("Expected sensor identifier: jmrz1011")
            appendLine("Expected SPI speed: 6000000 Hz")
            appendLine("Expected image: 256 x 360, 8-bit grayscale, 92160 bytes, 500 DPI")
            appendLine("Known sensor-ID register: 0x11")
            appendLine("Expected legacy ID values: 0x33 or 0x66")
            appendLine()
            appendLine("Safety: full diagnostics performed no SPI transfer, sensor write, GPIO ioctl/toggle, capture, sysfs write, or vendor component invocation.")
        }

        private fun Boolean.asPresence(): String = if (this) "PRESENT" else "NOT PRESENT"
    }

    fun inspect(progress: (String) -> Unit = {}): Report {
        progress("Collecting device information...")
        val spi = File(SPI_PATH)
        val requiredClassResults = REQUIRED_CLASSES.associateWith(::classExists)
        val relatedClasses = RELATED_CLASS_CANDIDATES.filter(::classExists)
        progress("Checking SPI...")
        val spiMetadata = inspectSpiDevice()
        val lowLevel = inspectLowLevelAccess()
        progress("Checking GPIO...")
        val gpioMetadata = inspectGpio164()
        progress("Checking vendor packages...")
        val packages = inspectVendorPackages()
        return Report(
            isX05 = Build.MODEL.equals("X05", true) || Build.MODEL.equals("HF-X05", true),
            spiExists = spi.exists(),
            spiReadable = spi.canRead(),
            spiWritable = spi.canWrite(),
            commonApiPresent = requiredClassResults[COMMON_API_CLASS] == true,
            requiredClassResults = requiredClassResults,
            nativeLibraryResults = REQUIRED_NATIVE_LIBRARIES.associateWith(::findNativeLibrary),
            spiMetadata = spiMetadata,
            spiMasters = listDirectoryNames("/sys/class/spi_master"),
            deviceTreeMatches = inspectRelevantDeviceTree(),
            gpioMetadata = gpioMetadata,
            lowLevelAccessMetadata = lowLevel,
            selinuxMetadata = inspectSelinux(),
            vendorPackageMetadata = packages,
            relatedClasses = relatedClasses,
            relatedServices = SERVICE_CANDIDATES.filter { serviceName ->
                runCatching { context.getSystemService(serviceName) }.getOrNull() != null
            },
            relatedPackages = visibleRelatedPackages(),
            deviceFileMetadata = inspectDeviceFiles(),
            packageName = context.packageName,
        )
    }

    private fun inspectDeviceFiles(): List<String> = buildList {
        addAll(describeFile("/dev/spidev3.0"))
        addAll(describeFile("/dev/mtgpio"))
    }

    private fun describeFile(path: String): List<String> {
        val file = File(path)
        val stat = runCatching { Os.stat(path) }.getOrNull()
        return listOf(
            "$path exists/readable/writable: ${file.exists()}/${file.canRead()}/${file.canWrite()}",
            "$path canonical: ${runCatching { file.canonicalPath }.getOrDefault("unavailable")}",
            "$path permissions: ${stat?.st_mode?.let(::formatMode) ?: "unavailable"}",
            "$path owner/group: ${stat?.st_uid ?: "unavailable"}/${stat?.st_gid ?: "unavailable"}",
        )
    }

    private fun formatMode(mode: Int): String {
        val flags = intArrayOf(OsConstants.S_IRUSR, OsConstants.S_IWUSR, OsConstants.S_IXUSR,
            OsConstants.S_IRGRP, OsConstants.S_IWGRP, OsConstants.S_IXGRP,
            OsConstants.S_IROTH, OsConstants.S_IWOTH, OsConstants.S_IXOTH)
        val chars = "rwxrwxrwx"
        return buildString { flags.forEachIndexed { index, flag -> append(if (mode and flag != 0) chars[index] else '-') } }
    }

    private fun visibleRelatedPackages(): List<String> {
        val packages = @Suppress("DEPRECATION")
        context.packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
        return packages.asSequence()
            .map { it.packageName }
            .filter { packageName ->
                val lower = packageName.lowercase(Locale.US)
                PACKAGE_TERMS.any(lower::contains)
            }
            .sorted()
            .toList()
    }

    private fun inspectLowLevelAccess(): List<String> = runCatching {
        val probe = LowLevelAccessProbe()
        val spi = probe.probeSpi()
        val mtgpio = probe.probeMtgpio()
        listOf(
            "SPI open O_RDWR: ${if (spi.openSuccess) "SUCCESS" else "FAILED (${errnoText(spi.openErrno)})"}",
            "SPI mode: ${if (spi.openSuccess) spi.mode.describe() else "NOT ATTEMPTED"}",
            "SPI bits per word: ${if (spi.openSuccess) spi.bitsPerWord.describe() else "NOT ATTEMPTED"}",
            "SPI max speed Hz: ${if (spi.openSuccess) spi.maxSpeedHz.describe() else "NOT ATTEMPTED"}",
            "SPI LSB first: ${if (spi.openSuccess) spi.lsbFirst.describe() else "NOT ATTEMPTED"}",
            "SPI close: ${if (!spi.openSuccess) "NOT ATTEMPTED" else if (spi.closeSuccess) "SUCCESS" else "FAILED (${errnoText(spi.closeErrno)})"}",
            "MTGPIO O_RDONLY: ${mtgpio.readOnly.describe()}",
            "MTGPIO O_RDWR: ${mtgpio.readWrite.describe()}",
        )
    }.getOrElse { error ->
        listOf(
            "SPI probe unavailable: ${error.javaClass.simpleName}",
            "MTGPIO probe unavailable: ${error.javaClass.simpleName}",
        )
    }

    private fun inspectSelinux(): List<String> {
        val rawState = readSmallText(File("/sys/fs/selinux/enforce"))
        val state = when (rawState) {
            "1" -> "enforcing"
            "0" -> "permissive"
            else -> "unknown${rawState?.let { " ($it)" }.orEmpty()}"
        }
        return listOf(
            "state: $state",
            "app UID: ${Process.myUid()}",
            "package: ${context.packageName}",
            "process context: ${readSmallText(File("/proc/self/attr/current")) ?: "unknown"}",
        )
    }

    private fun inspectVendorPackages(): List<String> {
        val packageNames = linkedSetOf(HIBORY_PACKAGE)
        packageNames += visibleRelatedPackages().filter { packageName ->
            val lower = packageName.lowercase(Locale.US)
            VENDOR_PACKAGE_TERMS.any(lower::contains)
        }
        return packageNames.flatMap { packageName -> inspectPackage(packageName) }
    }

    @Suppress("DEPRECATION")
    private fun inspectPackage(packageName: String): List<String> {
        val flags = PackageManager.GET_PERMISSIONS or
            PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_META_DATA
        val info = runCatching { context.packageManager.getPackageInfo(packageName, flags) }.getOrNull()
            ?: return listOf("package $packageName: NOT VISIBLE / NOT INSTALLED")
        return buildList {
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            add("package ${info.packageName}: PRESENT")
            add("  version: ${info.versionName ?: "unknown"} ($versionCode)")
            add("  UID: ${info.applicationInfo?.uid ?: -1}")
            val appFlags = info.applicationInfo?.flags ?: 0
            add("  system app: ${appFlags and ApplicationInfo.FLAG_SYSTEM != 0}")
            add("  updated system app: ${appFlags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0}")
            add("  privileged app: not reliably inferable through public PackageManager API")
            add("  metadata keys: ${info.applicationInfo?.metaData?.keySet()?.sorted()?.joinToString() ?: "none visible"}")
            add("  requested permissions:")
            val permissions = info.requestedPermissions.orEmpty()
            val permissionFlags = info.requestedPermissionsFlags
            if (permissions.isEmpty()) add("    none visible")
            permissions.forEachIndexed { index, permission ->
                val flagsValue = permissionFlags?.getOrNull(index)?.toInt() ?: 0
                val granted = flagsValue and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
                add("    $permission: ${if (granted) "GRANTED" else "NOT GRANTED"}")
            }
            addComponents("exported activities", info.activities) { it.permission }
            addComponents("exported services", info.services) { it.permission }
            addComponents("exported receivers", info.receivers) { it.permission }
            addComponents("exported providers", info.providers) { provider ->
                listOfNotNull(provider.readPermission, provider.writePermission).distinct().joinToString()
                    .ifBlank { null }
            }
            add("  intent actions: not exposed by PackageInfo for arbitrary installed components")
        }
    }

    private fun <T : ComponentInfo> MutableList<String>.addComponents(
        label: String,
        components: Array<T>?,
        permission: (T) -> String?,
    ) {
        add("  $label:")
        val exported = components?.filter(ComponentInfo::exported).orEmpty()
        if (exported.isEmpty()) add("    none visible")
        exported.forEach { component ->
            add("    ${component.name}; exported=true; permission=${permission(component) ?: "none"}")
        }
    }

    private fun classExists(className: String): Boolean =
        runCatching { Class.forName(className, false, context.classLoader) }.isSuccess

    private fun findNativeLibrary(libraryName: String): List<String> = RUNTIME_LIBRARY_DIRECTORIES
        .asSequence()
        .map { directory -> File(directory, libraryName) }
        .filter { candidate -> runCatching { candidate.isFile }.getOrDefault(false) }
        .map(File::getAbsolutePath)
        .distinct()
        .toList()

    private fun inspectSpiDevice(): List<String> {
        val result = mutableListOf<String>()
        result += "spidev bufsiz: ${readSmallText(File(SPIDEV_BUFSIZ_PATH)) ?: "unavailable"}"
        val spiBus = File("/sys/bus/spi/devices")
        result += "devices: ${listDirectoryNames(spiBus.path).ifEmpty { listOf("none readable") }.joinToString()}"

        val device = File(spiBus, "spi3.0")
        result += "spi3.0 directory: ${if (device.exists()) "PRESENT" else "NOT PRESENT"}"
        SPI_TEXT_ATTRIBUTES.forEach { attribute ->
            readSmallText(File(device, attribute))?.let { value -> result += "$attribute: $value" }
        }
        SPI_LINK_ATTRIBUTES.forEach { attribute ->
            describeLink(File(device, attribute))?.let { value -> result += "$attribute: $value" }
        }

        val ofNode = runCatching { File(device, "of_node").canonicalFile }.getOrNull()
        if (ofNode != null && ofNode.exists()) {
            result += "of_node resolved: ${ofNode.path}"
            DEVICE_TREE_PROPERTIES.forEach { property ->
                readSmallText(File(ofNode, property))?.let { value -> result += "of_node/$property: $value" }
            }
        }
        return result
    }

    private fun inspectRelevantDeviceTree(): List<String> {
        val root = DEVICE_TREE_ROOTS.map(::File).firstOrNull(File::isDirectory) ?: return emptyList()
        val queue = ArrayDeque<Pair<File, Int>>()
        val matches = linkedSetOf<String>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_DEVICE_TREE_NODES && matches.size < MAX_DEVICE_TREE_MATCHES) {
            val (node, depth) = queue.removeFirst()
            visited++
            val properties = DEVICE_TREE_PROPERTIES.mapNotNull { property ->
                readSmallText(File(node, property))?.let { "$property=$it" }
            }
            val searchable = (node.name + " " + properties.joinToString(" ")).lowercase(Locale.US)
            if (HARDWARE_TERMS.any(searchable::contains)) {
                val relative = runCatching { node.relativeTo(root).path }.getOrDefault(node.path)
                matches += "$relative${properties.joinToString(prefix = " [", postfix = "]").takeIf { properties.isNotEmpty() }.orEmpty()}"
            }
            if (depth < MAX_DEVICE_TREE_DEPTH) {
                runCatching { node.listFiles() }
                    .getOrNull()
                    .orEmpty()
                    .filter { it.isDirectory && !isSymbolicLink(it) }
                    .forEach { queue.add(it to depth + 1) }
            }
        }
        matches += "scan summary: root=${root.path}, nodesVisited=$visited, resultLimit=$MAX_DEVICE_TREE_MATCHES"
        return matches.toList()
    }

    private fun inspectGpio164(): List<String> {
        val result = mutableListOf<String>()
        val gpio = File("/sys/class/gpio/gpio164")
        result += "/sys/class/gpio/gpio164: ${if (gpio.exists()) "PRESENT" else "NOT PRESENT"}"
        listOf("direction", "value", "active_low", "edge").forEach { attribute ->
            readSmallText(File(gpio, attribute))?.let { value -> result += "gpio164/$attribute: $value" }
        }
        val export = File("/sys/class/gpio/export")
        result += "/sys/class/gpio/export exists/readable/writable: ${export.exists()}/${export.canRead()}/${export.canWrite()}"

        val gpioClass = File("/sys/class/gpio")
        runCatching { gpioClass.listFiles() }.getOrNull().orEmpty()
            .filter { it.name.startsWith("gpiochip") }
            .forEach { chip ->
                val base = readSmallText(File(chip, "base"))?.toIntOrNull()
                val count = readSmallText(File(chip, "ngpio"))?.toIntOrNull()
                val label = readSmallText(File(chip, "label")) ?: "unknown"
                val owns = base != null && count != null && GPIO_NUMBER in base until (base + count)
                result += "${chip.name}: base=${base ?: "unknown"}, ngpio=${count ?: "unknown"}, label=$label, ownsGPIO164=$owns"
            }

        val mtgpio = File("/dev/mtgpio")
        result += "/dev/mtgpio exists/readable/writable: ${mtgpio.exists()}/${mtgpio.canRead()}/${mtgpio.canWrite()}"
        return result
    }

    private fun listDirectoryNames(path: String): List<String> = runCatching { File(path).list() }
        .getOrNull()
        .orEmpty()
        .sorted()

    private fun describeLink(file: File): String? {
        if (!file.exists()) return null
        return runCatching { file.canonicalPath }.getOrElse { file.path }
    }

    private fun isSymbolicLink(file: File): Boolean = runCatching {
        file.canonicalFile != file.absoluteFile
    }.getOrDefault(true)

    private fun readSmallText(file: File): String? {
        if (!file.isFile || !file.canRead()) return null
        return runCatching {
            file.inputStream().buffered().use { input ->
                val bytes = ByteArray(MAX_METADATA_BYTES)
                val count = input.read(bytes)
                if (count <= 0) return@use null
                bytes.copyOf(count)
                    .toString(Charsets.UTF_8)
                    .replace('\u0000', ' ')
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .takeIf(String::isNotEmpty)
            }
        }.getOrNull()
    }

    companion object {
        const val SPI_PATH = "/dev/spidev3.0"
        private const val SPIDEV_BUFSIZ_PATH = "/sys/module/spidev/parameters/bufsiz"
        private const val GPIO_NUMBER = 164
        private const val MAX_METADATA_BYTES = 4096
        private const val MAX_DEVICE_TREE_NODES = 2000
        private const val MAX_DEVICE_TREE_DEPTH = 14
        private const val MAX_DEVICE_TREE_MATCHES = 80
        private const val COMMON_API_CLASS = "android.hibory.CommonApi"
        private const val HIBORY_PACKAGE = "com.hibory.hideapp"
        private val REQUIRED_CLASSES = listOf(
            COMMON_API_CLASS,
            "com.hfteco.finger.FingerprintScanner",
            "com.hfteco.finger.FingerprintImage",
            "com.hfteco.finger.Result",
            "com.hfteco.finger.FingerSDK",
        )
        private val RELATED_CLASS_CANDIDATES = listOf(
            "android.hibory.HFUsb",
            "android.hardware.fingerprint.FingerprintManager",
            "android.hardware.biometrics.BiometricManager",
            "com.hfteco.finger.FingerSDK",
        )
        private val REQUIRED_NATIVE_LIBRARIES = listOf(
            "libHibory_CommonApi.so",
            "libFingerprintScanner.so",
            "libFingerprintImage.so",
            "libFingerprintAlgorithm.so",
            "libfingerprint-isoansi-lib.so",
        )
        private val RUNTIME_LIBRARY_DIRECTORIES = (
            listOf(
                "/system/lib64",
                "/system/lib",
                "/system_ext/lib64",
                "/system_ext/lib",
                "/product/lib64",
                "/product/lib",
                "/vendor/lib64",
                "/vendor/lib",
                "/odm/lib64",
                "/odm/lib",
            ) + System.getProperty("java.library.path").orEmpty()
                .split(File.pathSeparatorChar)
                .filter(String::isNotBlank)
            ).distinct()
        private val SERVICE_CANDIDATES = listOf(
            "fingerprint",
            "finger",
            "biometric",
            "face",
            "hibory",
            "spi",
        )
        private val PACKAGE_TERMS = listOf("hfteco", "hibory", "fingerprint", "finger", "biometric", "proline", "x05")
        private val VENDOR_PACKAGE_TERMS = PACKAGE_TERMS
        private val SPI_TEXT_ATTRIBUTES = listOf("modalias", "uevent", "max-frequency", "spi-max-frequency")
        private val SPI_LINK_ATTRIBUTES = listOf("driver", "subsystem", "of_node")
        private val DEVICE_TREE_PROPERTIES = listOf(
            "compatible",
            "name",
            "status",
            "modalias",
            "max-frequency",
            "spi-max-frequency",
            "label",
        )
        private val DEVICE_TREE_ROOTS = listOf("/sys/firmware/devicetree/base", "/proc/device-tree")
        private val HARDWARE_TERMS = listOf(
            "fingerprint",
            "finger",
            "fp",
            "spi",
            "goodix",
            "elan",
            "focal",
            "silead",
            "chipone",
            "fpc",
            "egis",
            "synaptics",
            "hfteco",
            "hibory",
            "jmrz",
            "jmrz1011",
            "spidev",
            "gpio",
            "mtgpio",
        )

        private fun errnoText(errno: Int): String = when (errno) {
            1 -> "EPERM (1)"; 2 -> "ENOENT (2)"; 5 -> "EIO (5)"; 13 -> "EACCES (13)"
            19 -> "ENODEV (19)"; 22 -> "EINVAL (22)"; 25 -> "ENOTTY (25)"
            else -> "errno=$errno"
        }
    }
}
