# SPDX-License-Identifier: GPL-3.0-or-later

# JNI entry points are looked up by name from libfernix.so.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
