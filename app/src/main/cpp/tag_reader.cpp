// SPDX-License-Identifier: GPL-3.0-or-later
//
// JNI bridge to TagLib. See spec/architecture.md §7.

#include <jni.h>
#include <android/log.h>

#include <memory>
#include <vector>

#include <fileref.h>
#include <tfilestream.h>
#include <tpropertymap.h>
#include <tvariant.h>
#include <tbytevector.h>
#include <tversionnumber.h>

namespace {

constexpr const char *kLogTag = "FernixTags";

// TagLib strings may contain characters outside the BMP, which JNI's
// NewStringUTF (modified UTF-8) mishandles, so go through UTF-16.
jstring toJString(JNIEnv *env, const TagLib::String &s) {
    const TagLib::ByteVector utf16 = s.data(TagLib::String::UTF16LE);
    return env->NewString(reinterpret_cast<const jchar *>(utf16.data()),
                          static_cast<jsize>(utf16.size() / 2));
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_libregeek_fernix_tags_TagReader_nativeTaglibVersion(JNIEnv *env, jclass) {
    return toJString(env, TagLib::runtimeVersion().toString());
}

// Takes ownership of fd (it is closed by TagLib's FileStream).
// Returns alternating key/value pairs; a multi-value tag repeats its key per value.
// Returns null if the file cannot be read.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_libregeek_fernix_tags_TagReader_nativeReadProperties(JNIEnv *env, jclass, jint fd) {
    auto stream = std::make_unique<TagLib::FileStream>(fd, true);
    if (!stream->isOpen()) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "Cannot open fd %d", fd);
        return nullptr;
    }

    TagLib::PropertyMap properties;
    {
        // FileRef does not own the stream; it must be destroyed first.
        const TagLib::FileRef ref(stream.get(), false);
        if (ref.isNull()) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "Unsupported or unreadable file");
            return nullptr;
        }
        properties = ref.properties();
    }

    std::vector<std::pair<TagLib::String, TagLib::String>> pairs;
    for (const auto &[key, values] : properties) {
        for (const auto &value : values) {
            pairs.emplace_back(key, value);
        }
    }

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(pairs.size() * 2), stringClass, nullptr);
    if (result == nullptr) return nullptr;  // OutOfMemoryError pending

    jsize i = 0;
    for (const auto &[key, value] : pairs) {
        jstring k = toJString(env, key);
        jstring v = toJString(env, value);
        env->SetObjectArrayElement(result, i++, k);
        env->SetObjectArrayElement(result, i++, v);
        env->DeleteLocalRef(k);
        env->DeleteLocalRef(v);
    }
    return result;
}

// Takes ownership of fd. Returns the embedded pictures as groups of four objects:
// [String pictureType, String mimeType, String description, byte[] data] (spec §7 "Pictures").
// pictureType is TagLib's name for the ID3v2 APIC type ("Front Cover", "Artist", ...); Kotlin
// maps it to the number. An empty array means no pictures; null means the file cannot be read.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_libregeek_fernix_tags_TagReader_nativeReadPictures(JNIEnv *env, jclass, jint fd) {
    auto stream = std::make_unique<TagLib::FileStream>(fd, true);
    if (!stream->isOpen()) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "Cannot open fd %d", fd);
        return nullptr;
    }

    TagLib::List<TagLib::VariantMap> pictures;
    {
        const TagLib::FileRef ref(stream.get(), false);
        if (ref.isNull()) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "Unsupported or unreadable file");
            return nullptr;
        }
        pictures = ref.complexProperties("PICTURE");
    }

    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(pictures.size() * 4), objectClass, nullptr);
    if (result == nullptr) return nullptr;  // OutOfMemoryError pending

    jsize i = 0;
    for (const auto &picture : pictures) {
        const TagLib::ByteVector data = picture.value("data").toByteVector();
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(data.size()));
        if (bytes == nullptr) return nullptr;  // OutOfMemoryError pending
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(data.size()),
                                reinterpret_cast<const jbyte *>(data.data()));

        jstring type = toJString(env, picture.value("pictureType").toString());
        jstring mime = toJString(env, picture.value("mimeType").toString());
        jstring description = toJString(env, picture.value("description").toString());
        env->SetObjectArrayElement(result, i++, type);
        env->SetObjectArrayElement(result, i++, mime);
        env->SetObjectArrayElement(result, i++, description);
        env->SetObjectArrayElement(result, i++, bytes);
        env->DeleteLocalRef(type);
        env->DeleteLocalRef(mime);
        env->DeleteLocalRef(description);
        env->DeleteLocalRef(bytes);
    }
    return result;
}
