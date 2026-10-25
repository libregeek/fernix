// SPDX-License-Identifier: GPL-3.0-or-later
//
// JNI bridge to TagLib. See spec/architecture.md §7.

#include <jni.h>
#include <android/log.h>

#include <memory>
#include <vector>

#include <audioproperties.h>
#include <fileref.h>
#include <flac/flacproperties.h>
#include <mp4/mp4properties.h>
#include <mpeg/mpegfile.h>
#include <riff/wav/wavproperties.h>
#include <tfile.h>
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

// The parsed file behind a stream. A stream opened from a descriptor has no name, so FileRef can
// only sniff the content, and its MP3 check looks for a frame header in the 1024 bytes after the
// ID3v2 tag: a file with more padding than that (tag editors write about 2 KB) is "unsupported"
// although it plays. When FileRef finds nothing, try MPEG, which scans the whole file for a frame,
// and accept it only if it finds one (spec §7 "File type detection"). Must not outlive the stream.
class TagFile {
public:
    TagFile(TagLib::IOStream *stream, bool readAudioProperties,
            TagLib::AudioProperties::ReadStyle style = TagLib::AudioProperties::Average)
        : ref_(stream, readAudioProperties, style) {
        if (!ref_.isNull()) {
            file_ = ref_.file();
            return;
        }
        auto mpeg = std::make_unique<TagLib::MPEG::File>(stream, readAudioProperties, style);
        if (mpeg->isValid() && mpeg->firstFrameOffset() >= 0) {
            mpeg_ = std::move(mpeg);
            file_ = mpeg_.get();
        }
    }

    bool isNull() const { return file_ == nullptr; }
    TagLib::File *get() const { return file_; }

private:
    TagLib::FileRef ref_;  // does not own the stream
    std::unique_ptr<TagLib::MPEG::File> mpeg_;
    TagLib::File *file_ = nullptr;
};

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
    try {
        // FileRef does not own the stream; it must be destroyed first.
        const TagFile file(stream.get(), false);
        if (file.isNull()) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "Unsupported or unreadable file");
            return nullptr;
        }
        properties = file.get()->properties();
    } catch (...) {
        // A corrupt file must never take the scan (or the app) down with it: it is just unreadable.
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "Exception while reading properties");
        return nullptr;
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
    try {
        const TagFile file(stream.get(), false);
        if (file.isNull()) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "Unsupported or unreadable file");
            return nullptr;
        }
        pictures = file.get()->complexProperties("PICTURE");
    } catch (...) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "Exception while reading pictures");
        return nullptr;
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

// Takes ownership of fd. Returns { lengthMs, bitrateKbps, sampleRate, channels, bitsPerSample };
// a field the format does not report is 0 (only FLAC, WAV and ALAC have bits per sample). Returns null
// if the file cannot be read or has no audio properties (spec §7 "Audio properties").
extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_libregeek_fernix_tags_TagReader_nativeReadAudioProperties(JNIEnv *env, jclass, jint fd) {
    auto stream = std::make_unique<TagLib::FileStream>(fd, true);
    if (!stream->isOpen()) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "Cannot open fd %d", fd);
        return nullptr;
    }

    jint values[5];
    try {
        const TagFile file(stream.get(), true);
        if (file.isNull() || file.get()->audioProperties() == nullptr) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag, "No audio properties");
            return nullptr;
        }
        const TagLib::AudioProperties *properties = file.get()->audioProperties();
        int bits = 0;
        if (const auto *flac = dynamic_cast<const TagLib::FLAC::Properties *>(properties)) {
            bits = flac->bitsPerSample();
        } else if (const auto *wav = dynamic_cast<const TagLib::RIFF::WAV::Properties *>(properties)) {
            bits = wav->bitsPerSample();
        } else if (const auto *mp4 = dynamic_cast<const TagLib::MP4::Properties *>(properties)) {
            // Only a lossless ALAC has a bit depth; AAC is lossy and TagLib's 16 for it means nothing.
            if (mp4->codec() == TagLib::MP4::Properties::ALAC) bits = mp4->bitsPerSample();
        }
        values[0] = properties->lengthInMilliseconds();
        values[1] = properties->bitrate();
        values[2] = properties->sampleRate();
        values[3] = properties->channels();
        values[4] = bits;
    } catch (...) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "Exception while reading audio properties");
        return nullptr;
    }

    jintArray result = env->NewIntArray(5);
    if (result == nullptr) return nullptr;  // OutOfMemoryError pending
    env->SetIntArrayRegion(result, 0, 5, values);
    return result;
}
