#include <jni.h>
#include <cerrno>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>
#include <algorithm>

namespace {
void fail(JNIEnv* env, const std::string& message) {
    jclass type = env->FindClass("java/io/IOException");
    env->ThrowNew(type, (message + ": " + std::strerror(errno)).c_str());
}

std::vector<std::string> strings(JNIEnv* env, jobjectArray values) {
    std::vector<std::string> result;
    const jsize count = env->GetArrayLength(values);
    for (jsize i = 0; i < count; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        const char* utf = env->GetStringUTFChars(value, nullptr);
        result.emplace_back(utf);
        env->ReleaseStringUTFChars(value, utf);
        env->DeleteLocalRef(value);
    }
    return result;
}

int directory_at(JNIEnv* env, int root, const std::vector<std::string>& segments, bool create) {
    int current = dup(root);
    if (current < 0) { fail(env, "dup root fd"); return -1; }
    for (const auto& segment : segments) {
        if (create && mkdirat(current, segment.c_str(), 0700) < 0 && errno != EEXIST) {
            fail(env, "mkdirat"); close(current); return -1;
        }
        int next = openat(current, segment.c_str(), O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (next < 0) { fail(env, "openat directory"); close(current); return -1; }
        close(current); current = next;
    }
    return current;
}

int parent_at(JNIEnv* env, int root, const std::vector<std::string>& path, bool create) {
    if (path.empty()) { errno = EINVAL; fail(env, "empty relative path"); return -1; }
    return directory_at(env, root, std::vector<std::string>(path.begin(), path.end() - 1), create);
}

jlong open_root(JNIEnv* env, jobject, jstring path) {
    const char* utf = env->GetStringUTFChars(path, nullptr);
    if (mkdir(utf, 0700) < 0 && errno != EEXIST) { fail(env, "mkdir root"); env->ReleaseStringUTFChars(path, utf); return -1; }
    int fd = open(utf, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    env->ReleaseStringUTFChars(path, utf);
    if (fd < 0) { fail(env, "open trusted root"); return -1; }
    return fd;
}
void close_fd(JNIEnv*, jobject, jlong fd) { if (fd >= 0) close(static_cast<int>(fd)); }
void ensure_dir(JNIEnv* env, jobject, jlong root, jobjectArray path) { int fd = directory_at(env, root, strings(env, path), true); if (fd >= 0) close(fd); }

jboolean is_type(JNIEnv* env, int root, jobjectArray path, mode_t type) {
    auto parts = strings(env, path); if (parts.empty()) return type == S_IFDIR;
    int parent = parent_at(env, root, parts, false); if (parent < 0) { env->ExceptionClear(); return false; }
    struct stat value{}; int result = fstatat(parent, parts.back().c_str(), &value, AT_SYMLINK_NOFOLLOW); close(parent);
    return result == 0 && (value.st_mode & S_IFMT) == type;
}
jboolean is_regular(JNIEnv* env, jobject, jlong root, jobjectArray path) { return is_type(env, root, path, S_IFREG); }
jboolean is_directory(JNIEnv* env, jobject, jlong root, jobjectArray path) { return is_type(env, root, path, S_IFDIR); }

jlong size_of(JNIEnv* env, jobject, jlong root, jobjectArray path) {
    auto parts = strings(env, path); int parent = parent_at(env, root, parts, false); if (parent < 0) return -1;
    int fd = openat(parent, parts.back().c_str(), O_RDONLY | O_NOFOLLOW | O_CLOEXEC); close(parent);
    if (fd < 0) { fail(env, "openat size"); return -1; }
    struct stat value{}; if (fstat(fd, &value) < 0 || !S_ISREG(value.st_mode)) { fail(env, "fstat regular file"); close(fd); return -1; }
    close(fd); return value.st_size;
}

jlong open_relative(JNIEnv* env, int root, jobjectArray path, int flags, bool create_parents) {
    auto parts = strings(env, path); int parent = parent_at(env, root, parts, create_parents); if (parent < 0) return -1;
    int fd = openat(parent, parts.back().c_str(), flags | O_NOFOLLOW | O_CLOEXEC, 0600); close(parent);
    if (fd < 0) { fail(env, "openat file"); return -1; } return fd;
}
jlong open_read(JNIEnv* env, jobject, jlong root, jobjectArray path) { return open_relative(env, root, path, O_RDONLY, false); }
jlong create_exclusive(JNIEnv* env, jobject, jlong root, jobjectArray path) { return open_relative(env, root, path, O_WRONLY | O_CREAT | O_EXCL, true); }

jint read_fd(JNIEnv* env, jobject, jlong fd, jbyteArray bytes, jint offset, jint count) {
    std::vector<jbyte> buffer(count); ssize_t value = read(fd, buffer.data(), count);
    if (value < 0) { fail(env, "read fd"); return -1; }
    if (value > 0) env->SetByteArrayRegion(bytes, offset, value, buffer.data());
    return value == 0 ? -1 : static_cast<jint>(value);
}
void write_fd(JNIEnv* env, jobject, jlong fd, jbyteArray bytes, jint offset, jint count) {
    std::vector<jbyte> buffer(count); env->GetByteArrayRegion(bytes, offset, count, buffer.data());
    size_t written = 0; while (written < static_cast<size_t>(count)) { ssize_t value = write(fd, buffer.data() + written, count - written); if (value < 0) { fail(env, "write fd"); return; } written += value; }
}
void sync_fd(JNIEnv* env, jobject, jlong fd) { if (fsync(fd) < 0) fail(env, "fsync fd"); }

void atomic_replace(JNIEnv* env, jobject, jlong root, jobjectArray parent_path, jstring from, jstring to) {
    int parent = directory_at(env, root, strings(env, parent_path), false); if (parent < 0) return;
    const char* a = env->GetStringUTFChars(from, nullptr); const char* b = env->GetStringUTFChars(to, nullptr);
    if (renameat(parent, a, parent, b) < 0) fail(env, "renameat replace");
    env->ReleaseStringUTFChars(from, a); env->ReleaseStringUTFChars(to, b); close(parent);
}
void atomic_move(JNIEnv* env, jobject, jlong root, jobjectArray from_path, jstring from, jobjectArray to_path, jstring to) {
    int source = directory_at(env, root, strings(env, from_path), false); if (source < 0) return;
    int target = directory_at(env, root, strings(env, to_path), false); if (target < 0) { close(source); return; }
    const char* a = env->GetStringUTFChars(from, nullptr); const char* b = env->GetStringUTFChars(to, nullptr);
    if (renameat(source, a, target, b) < 0) fail(env, "renameat move");
    env->ReleaseStringUTFChars(from, a); env->ReleaseStringUTFChars(to, b); close(source); close(target);
}
void delete_relative(JNIEnv* env, jobject, jlong root, jobjectArray path) {
    auto parts = strings(env, path); if (parts.empty()) return; int parent = parent_at(env, root, parts, false); if (parent < 0) { env->ExceptionClear(); return; }
    struct stat value{}; if (fstatat(parent, parts.back().c_str(), &value, AT_SYMLINK_NOFOLLOW) < 0) { close(parent); if (errno != ENOENT) fail(env, "fstatat delete"); return; }
    int flags = S_ISDIR(value.st_mode) ? AT_REMOVEDIR : 0; if (unlinkat(parent, parts.back().c_str(), flags) < 0 && errno != ENOENT) fail(env, "unlinkat"); close(parent);
}
jobjectArray list_relative(JNIEnv* env, jobject, jlong root, jobjectArray path) {
    int fd = directory_at(env, root, strings(env, path), false); if (fd < 0) return nullptr;
    DIR* directory = fdopendir(fd); if (!directory) { close(fd); fail(env, "fdopendir"); return nullptr; }
    std::vector<std::string> names; while (dirent* entry = readdir(directory)) { std::string name(entry->d_name); if (name != "." && name != "..") names.push_back(name); }
    closedir(directory); std::sort(names.begin(), names.end()); jclass string_type = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(names.size(), string_type, nullptr); for (size_t i = 0; i < names.size(); ++i) { jstring name = env->NewStringUTF(names[i].c_str()); env->SetObjectArrayElement(result, i, name); env->DeleteLocalRef(name); } return result;
}
void sync_directory(JNIEnv* env, jobject, jlong root, jobjectArray path) { int fd = directory_at(env, root, strings(env, path), false); if (fd < 0) return; if (fsync(fd) < 0) fail(env, "fsync directory fd"); close(fd); }

JNINativeMethod methods[] = {
    {"nativeOpenRoot", "(Ljava/lang/String;)J", reinterpret_cast<void*>(open_root)}, {"nativeClose", "(J)V", reinterpret_cast<void*>(close_fd)},
    {"nativeEnsureDirectory", "(J[Ljava/lang/String;)V", reinterpret_cast<void*>(ensure_dir)}, {"nativeIsRegular", "(J[Ljava/lang/String;)Z", reinterpret_cast<void*>(is_regular)},
    {"nativeIsDirectory", "(J[Ljava/lang/String;)Z", reinterpret_cast<void*>(is_directory)}, {"nativeSize", "(J[Ljava/lang/String;)J", reinterpret_cast<void*>(size_of)},
    {"nativeOpenRead", "(J[Ljava/lang/String;)J", reinterpret_cast<void*>(open_read)}, {"nativeCreateExclusive", "(J[Ljava/lang/String;)J", reinterpret_cast<void*>(create_exclusive)},
    {"nativeRead", "(J[BII)I", reinterpret_cast<void*>(read_fd)}, {"nativeWrite", "(J[BII)V", reinterpret_cast<void*>(write_fd)}, {"nativeSync", "(J)V", reinterpret_cast<void*>(sync_fd)},
    {"nativeAtomicReplace", "(J[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", reinterpret_cast<void*>(atomic_replace)},
    {"nativeAtomicMove", "(J[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)V", reinterpret_cast<void*>(atomic_move)},
    {"nativeDelete", "(J[Ljava/lang/String;)V", reinterpret_cast<void*>(delete_relative)}, {"nativeList", "(J[Ljava/lang/String;)[Ljava/lang/String;", reinterpret_cast<void*>(list_relative)},
    {"nativeSyncDirectory", "(J[Ljava/lang/String;)V", reinterpret_cast<void*>(sync_directory)},
};
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr; if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass type = env->FindClass("com/uhg0/ar_flutter_plugin_2/capture/AndroidDescriptorNativeV2");
    if (!type || env->RegisterNatives(type, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
