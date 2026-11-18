# The wired recovery helper is called through JNI only when USB bring-up stalls.
-keepclasseswithmembernames,includedescriptorclasses class com.carlink.core.connection.UsbReset {
    native <methods>;
}
