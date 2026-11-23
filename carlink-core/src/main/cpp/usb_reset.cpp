#include <jni.h>
#include <cerrno>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

extern "C" JNIEXPORT jint JNICALL
Java_com_carlink_core_connection_UsbReset_resetFileDescriptor(JNIEnv *, jobject, jint descriptor) {
    if (descriptor < 0) return -EBADF;
    return ioctl(descriptor, USBDEVFS_RESET, nullptr) == 0 ? 0 : -errno;
}
