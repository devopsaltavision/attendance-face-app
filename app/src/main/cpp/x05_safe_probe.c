#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/spi/spidev.h>
#include <stdint.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

enum {
    SPI_OPEN_SUCCESS,
    SPI_OPEN_ERRNO,
    SPI_MODE_VALUE,
    SPI_MODE_ERRNO,
    SPI_BITS_VALUE,
    SPI_BITS_ERRNO,
    SPI_SPEED_VALUE,
    SPI_SPEED_ERRNO,
    SPI_LSB_VALUE,
    SPI_LSB_ERRNO,
    SPI_CLOSE_SUCCESS,
    SPI_CLOSE_ERRNO,
    SPI_RESULT_SIZE
};

enum {
    ACTIVE_OPEN_SUCCESS,
    ACTIVE_OPEN_ERRNO,
    ACTIVE_MODE,
    ACTIVE_MODE_ERRNO,
    ACTIVE_BITS,
    ACTIVE_BITS_ERRNO,
    ACTIVE_SPEED,
    ACTIVE_SPEED_ERRNO,
    ACTIVE_LSB,
    ACTIVE_LSB_ERRNO,
    ACTIVE_TRANSFER_SUCCESS,
    ACTIVE_TRANSFER_ERRNO,
    ACTIVE_IOCTL_RETURN,
    ACTIVE_RX_0,
    ACTIVE_RX_1,
    ACTIVE_RX_2,
    ACTIVE_CLOSE_SUCCESS,
    ACTIVE_CLOSE_ERRNO,
    ACTIVE_RESULT_SIZE
};

enum {
    GPIO_RO_OPEN_SUCCESS,
    GPIO_RO_OPEN_ERRNO,
    GPIO_RO_CLOSE_SUCCESS,
    GPIO_RO_CLOSE_ERRNO,
    GPIO_RW_OPEN_SUCCESS,
    GPIO_RW_OPEN_ERRNO,
    GPIO_RW_CLOSE_SUCCESS,
    GPIO_RW_CLOSE_ERRNO,
    GPIO_RESULT_SIZE
};

static void query_u8(int fd, unsigned long request, jint *value, jint *error) {
    uint8_t result = 0;
    errno = 0;
    if (ioctl(fd, request, &result) == 0) {
        *value = result;
        *error = 0;
    } else {
        *value = -1;
        *error = errno;
    }
}

static void query_u32(int fd, unsigned long request, jint *value, jint *error) {
    uint32_t result = 0;
    errno = 0;
    if (ioctl(fd, request, &result) == 0) {
        *value = (jint) result;
        *error = 0;
    } else {
        *value = -1;
        *error = errno;
    }
}

JNIEXPORT jintArray JNICALL
Java_com_syntaxgenie_hfx05attendance_fingerprint_LowLevelAccessProbe_nativeProbeSpi(
        JNIEnv *env,
        jobject instance) {
    (void) instance;
    jint result[SPI_RESULT_SIZE] = {0, 0, -1, 0, -1, 0, -1, 0, -1, 0, 0, 0};
    errno = 0;
    const int fd = open("/dev/spidev3.0", O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        result[SPI_OPEN_ERRNO] = errno;
    } else {
        result[SPI_OPEN_SUCCESS] = 1;
        query_u8(fd, SPI_IOC_RD_MODE, &result[SPI_MODE_VALUE], &result[SPI_MODE_ERRNO]);
        query_u8(fd, SPI_IOC_RD_BITS_PER_WORD, &result[SPI_BITS_VALUE], &result[SPI_BITS_ERRNO]);
        query_u32(fd, SPI_IOC_RD_MAX_SPEED_HZ, &result[SPI_SPEED_VALUE], &result[SPI_SPEED_ERRNO]);
        query_u8(fd, SPI_IOC_RD_LSB_FIRST, &result[SPI_LSB_VALUE], &result[SPI_LSB_ERRNO]);
        errno = 0;
        if (close(fd) == 0) {
            result[SPI_CLOSE_SUCCESS] = 1;
        } else {
            result[SPI_CLOSE_ERRNO] = errno;
        }
    }

    jintArray output = (*env)->NewIntArray(env, SPI_RESULT_SIZE);
    if (output != NULL) {
        (*env)->SetIntArrayRegion(env, output, 0, SPI_RESULT_SIZE, result);
    }
    return output;
}

static void probe_open_close(const char *path, int flags, jint *open_success, jint *open_error,
                             jint *close_success, jint *close_error) {
    errno = 0;
    const int fd = open(path, flags | O_CLOEXEC);
    if (fd < 0) {
        *open_error = errno;
        return;
    }
    *open_success = 1;
    errno = 0;
    if (close(fd) == 0) {
        *close_success = 1;
    } else {
        *close_error = errno;
    }
}

JNIEXPORT jintArray JNICALL
Java_com_syntaxgenie_hfx05attendance_fingerprint_LowLevelAccessProbe_nativeProbeMtgpio(
        JNIEnv *env,
        jobject instance) {
    (void) instance;
    jint result[GPIO_RESULT_SIZE] = {0};
    probe_open_close("/dev/mtgpio", O_RDONLY,
                     &result[GPIO_RO_OPEN_SUCCESS], &result[GPIO_RO_OPEN_ERRNO],
                     &result[GPIO_RO_CLOSE_SUCCESS], &result[GPIO_RO_CLOSE_ERRNO]);
    probe_open_close("/dev/mtgpio", O_RDWR,
                     &result[GPIO_RW_OPEN_SUCCESS], &result[GPIO_RW_OPEN_ERRNO],
                     &result[GPIO_RW_CLOSE_SUCCESS], &result[GPIO_RW_CLOSE_ERRNO]);

    jintArray output = (*env)->NewIntArray(env, GPIO_RESULT_SIZE);
    if (output != NULL) {
        (*env)->SetIntArrayRegion(env, output, 0, GPIO_RESULT_SIZE, result);
    }
    return output;
}

/*
 * One deliberately constrained interoperability probe. The framing is the exact
 * SenRegisterRead framing observed in the reference driver: [register, 0x00]
 * followed by one dummy byte in exactly one full-duplex spidev message. There
 * are no sensor-register writes and no retries.
 */
JNIEXPORT jintArray JNICALL
Java_com_syntaxgenie_hfx05attendance_fingerprint_LowLevelAccessProbe_nativeReadSensorId(
        JNIEnv *env,
        jobject instance) {
    (void) instance;
    jint result[ACTIVE_RESULT_SIZE] = {
            0, 0, -1, 0, -1, 0, -1, 0, -1, 0, 0, 0, -1, -1, -1, -1, 0, 0
    };
    errno = 0;
    const int fd = open("/dev/spidev3.0", O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        result[ACTIVE_OPEN_ERRNO] = errno;
    } else {
        result[ACTIVE_OPEN_SUCCESS] = 1;
        query_u8(fd, SPI_IOC_RD_MODE, &result[ACTIVE_MODE], &result[ACTIVE_MODE_ERRNO]);
        query_u8(fd, SPI_IOC_RD_BITS_PER_WORD, &result[ACTIVE_BITS], &result[ACTIVE_BITS_ERRNO]);
        query_u32(fd, SPI_IOC_RD_MAX_SPEED_HZ, &result[ACTIVE_SPEED], &result[ACTIVE_SPEED_ERRNO]);
        query_u8(fd, SPI_IOC_RD_LSB_FIRST, &result[ACTIVE_LSB], &result[ACTIVE_LSB_ERRNO]);

        /* Refuse unknown configuration rather than changing it. */
        if (result[ACTIVE_MODE_ERRNO] == 0 && result[ACTIVE_MODE] == SPI_MODE_0 &&
            result[ACTIVE_BITS_ERRNO] == 0 &&
            (result[ACTIVE_BITS] == 0 || result[ACTIVE_BITS] == 8) &&
            result[ACTIVE_LSB_ERRNO] == 0 && result[ACTIVE_LSB] == 0) {
            uint8_t tx[3] = {0x11, 0x00, 0xfe};
            uint8_t rx[3] = {0};
            struct spi_ioc_transfer transfer = {0};
            transfer.tx_buf = (uintptr_t) tx;
            transfer.rx_buf = (uintptr_t) rx;
            transfer.len = sizeof(tx);
            transfer.bits_per_word = 8;
            transfer.speed_hz = 6000000;
            errno = 0;
            const int ioctl_result = ioctl(fd, SPI_IOC_MESSAGE(1), &transfer);
            result[ACTIVE_IOCTL_RETURN] = ioctl_result;
            if (ioctl_result >= 0) {
                result[ACTIVE_TRANSFER_SUCCESS] = 1;
                result[ACTIVE_RX_0] = rx[0];
                result[ACTIVE_RX_1] = rx[1];
                result[ACTIVE_RX_2] = rx[2];
            } else {
                result[ACTIVE_TRANSFER_ERRNO] = errno;
            }
        } else {
            result[ACTIVE_TRANSFER_ERRNO] = EINVAL;
        }

        errno = 0;
        if (close(fd) == 0) {
            result[ACTIVE_CLOSE_SUCCESS] = 1;
        } else {
            result[ACTIVE_CLOSE_ERRNO] = errno;
        }
    }

    jintArray output = (*env)->NewIntArray(env, ACTIVE_RESULT_SIZE);
    if (output != NULL) {
        (*env)->SetIntArrayRegion(env, output, 0, ACTIVE_RESULT_SIZE, result);
    }
    return output;
}

#define CAPTURE_REPORT_SIZE 16384
#define GPIO_NUMBER_ENCODED 0x800000a4UL
#define GPIO_INIT_REQUEST 0x400490ffUL
#define GPIO_DIRECTION_OUT_REQUEST 0x40049008UL
#define GPIO_OUTPUT_HIGH_REQUEST 0x40049015UL
#define GPIO_OUTPUT_LOW_REQUEST 0x40049014UL
#define SCANNER_RESET_REQUEST 0x00006ba1UL
#define CAPTURE_WIDTH 256
#define CAPTURE_HEIGHT 360
#define CAPTURE_BYTES (CAPTURE_WIDTH * CAPTURE_HEIGHT)
#define IMAGE_HEADER_BYTES 3
#define IMAGE_TX_STAGING_BYTES (CAPTURE_BYTES + IMAGE_HEADER_BYTES)
#define TRANSFER_SPEED_HZ 6000000
#define SPIDEV_BUFSIZ_PATH "/sys/module/spidev/parameters/bufsiz"

typedef struct {
    char text[CAPTURE_REPORT_SIZE];
    size_t used;
} capture_report;

static void report_line(capture_report *report, const char *format, ...) {
    if (report->used >= sizeof(report->text) - 1) return;
    va_list args;
    va_start(args, format);
    const int written = vsnprintf(report->text + report->used,
                                  sizeof(report->text) - report->used, format, args);
    va_end(args);
    if (written > 0) {
        const size_t available = sizeof(report->text) - report->used;
        report->used += (size_t) written < available ? (size_t) written : available - 1;
    }
}

static void progress(JNIEnv *env, jobject listener, jmethodID method, const char *message) {
    if (listener == NULL || method == NULL) return;
    jstring value = (*env)->NewStringUTF(env, message);
    if (value != NULL) {
        (*env)->CallVoidMethod(env, listener, method, value);
        (*env)->DeleteLocalRef(env, value);
    }
}

static long read_spidev_bufsiz(int *saved_errno) {
    char value[32] = {0};
    errno = 0;
    const int fd = open(SPIDEV_BUFSIZ_PATH, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        *saved_errno = errno;
        return -1;
    }
    errno = 0;
    const ssize_t count = read(fd, value, sizeof(value) - 1);
    const int read_errno = count < 0 ? errno : 0;
    errno = 0;
    const int close_result = close(fd);
    if (count <= 0) {
        *saved_errno = count < 0 ? read_errno : EIO;
        return -1;
    }
    if (close_result < 0) {
        *saved_errno = errno;
        return -1;
    }
    char *end = NULL;
    errno = 0;
    const long parsed = strtol(value, &end, 10);
    if (errno != 0 || end == value || parsed <= 0) {
        *saved_errno = errno != 0 ? errno : EINVAL;
        return -1;
    }
    *saved_errno = 0;
    return parsed;
}

static int spi_register_read(int fd, uint8_t address, uint8_t *value, int *saved_errno) {
    uint8_t tx[3] = {address, 0x00, 0xfe};
    uint8_t rx[3] = {0};
    struct spi_ioc_transfer transfer = {0};
    transfer.tx_buf = (uintptr_t) tx;
    transfer.rx_buf = (uintptr_t) rx;
    transfer.len = sizeof(tx);
    transfer.speed_hz = TRANSFER_SPEED_HZ;
    transfer.bits_per_word = 8;
    errno = 0;
    const int result = ioctl(fd, SPI_IOC_MESSAGE(1), &transfer);
    *saved_errno = result < 0 ? errno : 0;
    if (result < 0) return result;
    *value = rx[2];
    return result;
}

static int spi_register_write(int fd, uint8_t address, uint8_t value, int *saved_errno) {
    uint8_t tx[3] = {address, 0x80, value};
    uint8_t rx[3] = {0};
    struct spi_ioc_transfer transfer = {0};
    transfer.tx_buf = (uintptr_t) tx;
    transfer.rx_buf = (uintptr_t) rx;
    transfer.len = sizeof(tx);
    transfer.speed_hz = TRANSFER_SPEED_HZ;
    transfer.bits_per_word = 8;
    errno = 0;
    const int result = ioctl(fd, SPI_IOC_MESSAGE(1), &transfer);
    *saved_errno = result < 0 ? errno : 0;
    return result;
}

static int sensor_initialize(int fd, capture_report *report, uint8_t *sensor_id) {
    int saved_errno = 0;
    uint8_t reg02 = 0;
    int result = spi_register_read(fd, 0x02, &reg02, &saved_errno);
    report_line(report, "register 0x02 read: result=%d errno=%d value=0x%02X\n",
                result, saved_errno, reg02);
    if (result < 0) return -1;
    const uint8_t updated = reg02 | 0x80;
    result = spi_register_write(fd, 0x02, updated, &saved_errno);
    report_line(report, "register 0x02 write: result=%d errno=%d value=0x%02X\n",
                result, saved_errno, updated);
    if (result < 0) return -1;

    *sensor_id = 0;
    for (int attempt = 1; attempt <= 20; ++attempt) {
        uint8_t candidate = 0;
        result = spi_register_read(fd, 0x11, &candidate, &saved_errno);
        report_line(report, "sensor ID attempt %d: result=%d errno=%d value=0x%02X\n",
                    attempt, result, saved_errno, candidate);
        if (result < 0) return -1;
        if (candidate == 0x33 || candidate == 0x66) {
            *sensor_id = candidate;
            break;
        }
    }
    if (*sensor_id == 0) {
        report_line(report, "sensor ID: SENSOR NOT RECOGNIZED\n");
        return -2;
    }
    report_line(report, "sensor ID: SENSOR RECOGNIZED, ID=0x%02X\n", *sensor_id);

    static const uint8_t defaults[][2] = {
            {0x0e, 0xa5}, {0xfa, 0x40}, {0xfb, 0x15}, {0xfc, 0x26},
            {0xfd, 0x5e}, {0xfe, 0x2c}, {0xff, 0x0f}, {0x00, 0x80},
            {0x01, 0xb4}, {0x02, 0x1c}, {0x03, 0xe6}, {0x04, 0x8d},
            {0x05, 0x00}, {0x06, 0x00}, {0x07, 0xff}, {0x08, 0x67},
            {0x09, 0x01}, {0x0a, 0x01}, {0x0b, 0xff},
    };
    for (size_t index = 0; index < sizeof(defaults) / sizeof(defaults[0]); ++index) {
        result = spi_register_write(fd, defaults[index][0], defaults[index][1], &saved_errno);
        if (result < 0) {
            report_line(report, "sensor table initialization: FAILED at register=0x%02X result=%d errno=%d\n",
                        defaults[index][0], result, saved_errno);
            return -1;
        }
    }
    report_line(report, "sensor table initialization: SUCCESS (19 writes)\n");
    return 0;
}

static int sensor_get_image(int fd, uint8_t *image, capture_report *report) {
    int saved_errno = 0;
    int result = spi_register_write(fd, 0x09, 0x01, &saved_errno);
    if (result < 0) goto register_failure;
    result = spi_register_write(fd, 0x0a, 0x01, &saved_errno);
    if (result < 0) goto register_failure;
    uint8_t reg04 = 0;
    result = spi_register_read(fd, 0x04, &reg04, &saved_errno);
    if (result < 0) goto register_failure;
    result = spi_register_write(fd, 0x04, reg04 | 0x01, &saved_errno);
    if (result < 0) goto register_failure;

    int ready_reads = 0;
    for (; ready_reads < 2000; ++ready_reads) {
        uint8_t ready = 0xff;
        result = spi_register_read(fd, 0x10, &ready, &saved_errno);
        if (result < 0) goto register_failure;
        if (ready == 0) break;
    }
    report_line(report, "image readiness polls: %d\n", ready_reads + (ready_reads < 2000 ? 1 : 0));
    if (ready_reads == 2000) {
        report_line(report, "capture stage: FAILED readiness timeout\n");
        return -1;
    }

    uint8_t header[3] = {0xf0, 0x40, 0x00};
    uint8_t *dummy = malloc(CAPTURE_BYTES);
    if (dummy == NULL) {
        report_line(report, "capture stage: FAILED image dummy-buffer allocation\n");
        return -1;
    }
    memset(dummy, 0xfe, CAPTURE_BYTES);
    struct spi_ioc_transfer transfers[2] = {{0}, {0}};
    transfers[0].tx_buf = (uintptr_t) header;
    transfers[0].len = sizeof(header);
    transfers[0].speed_hz = TRANSFER_SPEED_HZ;
    transfers[0].bits_per_word = 8;
    transfers[0].cs_change = 0;
    transfers[1].tx_buf = (uintptr_t) dummy;
    transfers[1].rx_buf = (uintptr_t) image;
    transfers[1].len = CAPTURE_BYTES;
    transfers[1].speed_hz = TRANSFER_SPEED_HZ;
    transfers[1].bits_per_word = 8;
    transfers[1].cs_change = 0;
    errno = 0;
    result = ioctl(fd, SPI_IOC_MESSAGE(2), transfers);
    saved_errno = result < 0 ? errno : 0;
    free(dummy);
    report_line(report, "image transfer count: 1 logical ioctl (2 transfers)\n");
    report_line(report, "image transfer errno: %d\n", saved_errno);
    report_line(report, "image transfer ioctl: result=%d errno=%d expected=%d\n",
                result, saved_errno, CAPTURE_BYTES + 3);
    if (result < 0) {
        report_line(report, "image transfer failed\n");
        return -1;
    }
    if (result != CAPTURE_BYTES + 3) {
        report_line(report, "capture stage: FAILED unexpected image transfer byte count\n");
        return -1;
    }
    return 0;

register_failure:
    report_line(report, "capture stage: FAILED register operation result=%d errno=%d\n",
                result, saved_errno);
    return -1;
}

static int sensor_capture_raw(int fd, uint8_t *image, capture_report *report) {
    int saved_errno = 0;
    uint8_t control = 0;
    int result = spi_register_read(fd, 0xff, &control, &saved_errno);
    if (result < 0) return -1;
    result = spi_register_write(fd, 0xff, control & 0xfe, &saved_errno);
    if (result < 0) return -1;
    result = spi_register_read(fd, 0xff, &control, &saved_errno);
    if (result < 0) return -1;
    result = spi_register_write(fd, 0xff, control & 0xfd, &saved_errno);
    if (result < 0) return -1;
    uint8_t exposure = 0;
    result = spi_register_read(fd, 0xfd, &exposure, &saved_errno);
    if (result < 0) return -1;
    result = spi_register_write(fd, 0xfd, (exposure & 0xc0) | 0x1e, &saved_errno);
    if (result < 0) return -1;
    report_line(report, "capture stage: direct full-resolution capture at recovered default ADC offset; adaptive vendor calibration not reproduced\n");
    return sensor_get_image(fd, image, report);
}

JNIEXPORT jobject JNICALL
Java_com_syntaxgenie_hfx05attendance_fingerprint_LowLevelAccessProbe_nativeCaptureRaw(
        JNIEnv *env, jobject instance, jobject listener) {
    (void) instance;
    capture_report report = {{0}, 0};
    report_line(&report, "=== REAL FINGERPRINT CAPTURE TEST ===\n");
    jclass listener_class = listener == NULL ? NULL : (*env)->GetObjectClass(env, listener);
    jmethodID progress_method = listener_class == NULL ? NULL :
            (*env)->GetMethodID(env, listener_class, "onProgress", "(Ljava/lang/String;)V");
    int gpio_fd = -1;
    int spi_fd = -1;
    int gpio_direction_set = 0;
    uint8_t *image = NULL;
    int capture_success = 0;
    int bufsiz_errno = 0;
    const long spidev_bufsiz = read_spidev_bufsiz(&bufsiz_errno);
    if (spidev_bufsiz < 0) {
        report_line(&report, "spidev bufsiz: unavailable\n");
    } else {
        report_line(&report, "spidev bufsiz: %ld\n", spidev_bufsiz);
    }
    if (spidev_bufsiz < 0) {
        report_line(&report, "spidev bufsiz read errno: %d\n", bufsiz_errno);
        report_line(&report, "buffer-size precheck: UNKNOWN\n");
        report_line(&report, "proceeding with one standard SPI_IOC_MESSAGE image attempt\n");
    } else if (spidev_bufsiz < IMAGE_TX_STAGING_BYTES) {
        report_line(&report, "buffer-size precheck: TOO SMALL\n");
    } else {
        report_line(&report, "buffer-size precheck: SUFFICIENT\n");
    }
    report_line(&report, "required image bytes: %d\n", CAPTURE_BYTES);
    report_line(&report, "required standard-spidev TX staging bytes: %d (3-byte header + image clocks)\n",
                IMAGE_TX_STAGING_BYTES);
    report_line(&report, "buffer preparation method: standard spidev userspace staging; no private buffer-size ioctl\n");
    report_line(&report, "buffer-size ioctl result: NOT USED (reference 0x6D40 wrapper has no recovered caller/value)\n");
    report_line(&report, "image transfer strategy: one SPI_IOC_MESSAGE(2), header then 92160-byte full-duplex transfer, CS continuous\n");

    progress(env, listener, progress_method, "Opening GPIO...");
    errno = 0;
    gpio_fd = open("/dev/mtgpio", O_RDONLY | O_CLOEXEC);
    report_line(&report, "GPIO open O_RDONLY: fd=%d errno=%d\n", gpio_fd, gpio_fd < 0 ? errno : 0);
    if (gpio_fd < 0) goto cleanup;
    errno = 0;
    int result = ioctl(gpio_fd, GPIO_INIT_REQUEST, 0UL);
    report_line(&report, "GPIO init ioctl 0x400490FF: result=%d errno=%d\n", result, result < 0 ? errno : 0);
    if (result < 0) goto cleanup;
    progress(env, listener, progress_method, "Powering fingerprint sensor...");
    errno = 0;
    result = ioctl(gpio_fd, GPIO_DIRECTION_OUT_REQUEST, GPIO_NUMBER_ENCODED);
    report_line(&report, "GPIO direction 0x40049008: result=%d errno=%d\n", result, result < 0 ? errno : 0);
    if (result < 0) goto cleanup;
    gpio_direction_set = 1;
    errno = 0;
    result = ioctl(gpio_fd, GPIO_OUTPUT_HIGH_REQUEST, GPIO_NUMBER_ENCODED);
    report_line(&report, "GPIO high 0x40049015: result=%d errno=%d\n", result, result < 0 ? errno : 0);
    if (result < 0) goto cleanup;
    usleep(100000); /* Engineering margin; not a recovered vendor requirement. */
    report_line(&report, "power stabilization: 100 ms engineering margin\n");

    progress(env, listener, progress_method, "Opening SPI...");
    errno = 0;
    spi_fd = open("/dev/spidev3.0", O_RDWR | O_CLOEXEC);
    report_line(&report, "SPI open O_RDWR: fd=%d errno=%d\n", spi_fd, spi_fd < 0 ? errno : 0);
    if (spi_fd < 0) goto cleanup;
    uint8_t mode = 0xff, bits = 0xff, lsb = 0xff;
    uint32_t speed = 0;
    int mode_result = ioctl(spi_fd, SPI_IOC_RD_MODE, &mode);
    int bits_result = ioctl(spi_fd, SPI_IOC_RD_BITS_PER_WORD, &bits);
    int speed_result = ioctl(spi_fd, SPI_IOC_RD_MAX_SPEED_HZ, &speed);
    int lsb_result = ioctl(spi_fd, SPI_IOC_RD_LSB_FIRST, &lsb);
    report_line(&report, "SPI configuration: mode=%u(result=%d) bits=%u(result=%d) speed=%u(result=%d) LSB=%u(result=%d)\n",
                mode, mode_result, bits, bits_result, speed, speed_result, lsb, lsb_result);
    if (mode_result < 0 || bits_result < 0 || lsb_result < 0 || mode != SPI_MODE_0 ||
        (bits != 0 && bits != 8) || lsb != 0) goto cleanup;

    progress(env, listener, progress_method, "Resetting sensor...");
    errno = 0;
    result = ioctl(spi_fd, SCANNER_RESET_REQUEST);
    report_line(&report, "reset #1 ioctl 0x6BA1: result=%d errno=%d\n", result, result < 0 ? errno : 0);
    if (result < 0) goto cleanup;
    errno = 0;
    result = ioctl(spi_fd, SCANNER_RESET_REQUEST);
    report_line(&report, "reset #2 ioctl 0x6BA1: result=%d errno=%d\n", result, result < 0 ? errno : 0);
    if (result < 0) goto cleanup;

    progress(env, listener, progress_method, "Checking sensor ID...");
    uint8_t sensor_id = 0;
    result = sensor_initialize(spi_fd, &report, &sensor_id);
    if (result != 0) goto cleanup;
    progress(env, listener, progress_method, "Initializing sensor...");
    report_line(&report, "sensor initialization: SUCCESS\n");
    if (spidev_bufsiz >= 0 && spidev_bufsiz < IMAGE_TX_STAGING_BYTES) {
        report_line(&report, "image transfer count: 0\n");
        report_line(&report, "image transfer errno: NOT ATTEMPTED\n");
        report_line(&report, "RAW CAPTURE BLOCKED: spidev buffer too small (bufsiz=%ld, required=%d)\n",
                    spidev_bufsiz, IMAGE_TX_STAGING_BYTES);
        goto cleanup;
    }
    progress(env, listener, progress_method, "Place finger on scanner...");
    usleep(3000000);
    progress(env, listener, progress_method, "Capturing fingerprint...");
    image = malloc(CAPTURE_BYTES);
    if (image == NULL) {
        report_line(&report, "capture stage: FAILED image allocation\n");
        goto cleanup;
    }
    memset(image, 0, CAPTURE_BYTES);
    result = sensor_capture_raw(spi_fd, image, &report);
    if (result != 0) {
        report_line(&report, "RAW CAPTURE BLOCKED: standard image transfer or recovered minimum acquisition sequence failed\n");
        goto cleanup;
    }
    report_line(&report, "capture stage: IMAGE RECEIVED\nimage transfer bytes: %d\nimage dimensions: %dx%d\n",
                CAPTURE_BYTES, CAPTURE_WIDTH, CAPTURE_HEIGHT);
    capture_success = 1;

cleanup:
    progress(env, listener, progress_method, "Cleaning up...");
    if (spi_fd >= 0) {
        errno = 0;
        const int close_result = close(spi_fd);
        report_line(&report, "SPI close: result=%d errno=%d\n", close_result, close_result < 0 ? errno : 0);
    } else report_line(&report, "SPI close: not opened\n");
    if (gpio_fd >= 0 && gpio_direction_set) {
        errno = 0;
        const int direction_result = ioctl(gpio_fd, GPIO_DIRECTION_OUT_REQUEST, GPIO_NUMBER_ENCODED);
        report_line(&report, "GPIO cleanup direction: result=%d errno=%d\n",
                    direction_result, direction_result < 0 ? errno : 0);
        errno = 0;
        const int low_result = ioctl(gpio_fd, GPIO_OUTPUT_LOW_REQUEST, GPIO_NUMBER_ENCODED);
        report_line(&report, "GPIO low cleanup: result=%d errno=%d\n", low_result, low_result < 0 ? errno : 0);
    } else report_line(&report, "GPIO low cleanup: not required (direction was never set)\n");
    if (gpio_fd >= 0) {
        errno = 0;
        const int close_result = close(gpio_fd);
        report_line(&report, "GPIO close: result=%d errno=%d\n", close_result, close_result < 0 ? errno : 0);
    } else report_line(&report, "GPIO close: not opened\n");

    jclass result_class = (*env)->FindClass(env,
            "com/syntaxgenie/hfx05attendance/fingerprint/RawCaptureResult");
    jmethodID constructor = result_class == NULL ? NULL :
            (*env)->GetMethodID(env, result_class, "<init>", "(ZLjava/lang/String;[B)V");
    jstring report_string = (*env)->NewStringUTF(env, report.text);
    jbyteArray image_array = NULL;
    if (capture_success && image != NULL) {
        image_array = (*env)->NewByteArray(env, CAPTURE_BYTES);
        if (image_array != NULL) {
            (*env)->SetByteArrayRegion(env, image_array, 0, CAPTURE_BYTES, (const jbyte *) image);
        }
    }
    free(image);
    if (constructor == NULL || report_string == NULL) return NULL;
    return (*env)->NewObject(env, result_class, constructor,
                             capture_success ? JNI_TRUE : JNI_FALSE, report_string, image_array);
}
