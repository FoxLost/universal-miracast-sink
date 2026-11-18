#include <jni.h>
#include <errno.h>
#include <grp.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/xattr.h>
#include <time.h>
#include <stdint.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <stddef.h>
#include <poll.h>

namespace {
class ScopedFd {
public:
    explicit ScopedFd(int value) : value_(value) {}
    ~ScopedFd() { if (value_ >= 0) close(value_); }
    int get() const { return value_; }
    ScopedFd(const ScopedFd&) = delete;
    ScopedFd& operator=(const ScopedFd&) = delete;
private:
    int value_;
};

bool numeric(const char* name) {
    if (!*name) return false;
    for (; *name; ++name) if (*name < '0' || *name > '9') return false;
    return true;
}

int64_t monotonicMillis() {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return static_cast<int64_t>(now.tv_sec) * 1000 + now.tv_nsec / 1000000;
}

bool waitSocket(int fd, short events, int64_t deadline) {
    while (true) {
        const int64_t remaining = deadline - monotonicMillis();
        if (remaining <= 0) { errno = ETIMEDOUT; return false; }
        pollfd descriptor{fd, events, 0};
        const int result = poll(&descriptor, 1, static_cast<int>(remaining));
        if (result > 0) return true; // Let send/recvmsg report the socket error.
        if (result == 0) { errno = ETIMEDOUT; return false; }
        if (errno != EINTR) return false;
    }
}
}

namespace {
void fail(JNIEnv* env, const char* operation, int error) {
    char message[256];
    snprintf(message, sizeof(message), "%s failed: %s (errno=%d)",
             operation, strerror(error), error);
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

void invalidIdentity(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

void verifyIdentity(JNIEnv* env) {
    uid_t realUid, effectiveUid, savedUid;
    gid_t realGid, effectiveGid, savedGid;
    if (getresuid(&realUid, &effectiveUid, &savedUid) == -1) {
        fail(env, "getresuid", errno);
        return;
    }
    if (getresgid(&realGid, &effectiveGid, &savedGid) == -1) {
        fail(env, "getresgid", errno);
        return;
    }
    const int groups = getgroups(0, nullptr);
    if (groups == -1) {
        fail(env, "getgroups", errno);
        return;
    }
    if (realUid != 1000 || effectiveUid != 1000 || savedUid != 1000 ||
        realGid != 1000 || effectiveGid != 1000 || savedGid != 1000 || groups != 0) {
        invalidIdentity(env, "Invalid system uid/gid or retained supplementary groups");
    }
}
}

extern "C" JNIEXPORT void JNICALL
Java_foxlost_miracast_sink_p2p_RootIdentity_adoptSystemIdentity(JNIEnv* env, jobject) {
    // This entry point belongs only to the separately launched root app_process VM.
    if (getuid() != 0 || geteuid() != 0) {
        invalidIdentity(env, "Root identity required before credential change");
        return;
    }
    // Supplementary groups must be removed while CAP_SETGID is still available.
    if (setgroups(0, nullptr) == -1) {
        fail(env, "setgroups", errno);
        return;
    }
    const int clearedGroups = getgroups(0, nullptr);
    if (clearedGroups == -1) {
        fail(env, "getgroups", errno);
        return;
    }
    if (clearedGroups != 0) {
        invalidIdentity(env, "Supplementary groups were not cleared");
        return;
    }
    if (setgid(1000) == -1) {
        fail(env, "setgid", errno);
        return;
    }
    if (setuid(1000) == -1) {
        fail(env, "setuid", errno);
        return;
    }
    verifyIdentity(env);
}

extern "C" JNIEXPORT void JNICALL
Java_foxlost_miracast_sink_p2p_RootIdentity_verifySystemIdentity(JNIEnv* env, jobject) {
    verifyIdentity(env);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_foxlost_miracast_sink_p2p_SupplicantSocketLabel_discoverNativeContext(
        JNIEnv* env, jobject, jlong inode) {
    // A Java /proc scan spends most of its budget allocating File/String objects
    // and crossing JNI for every readlink. Do the bounded search in one syscall
    // loop instead, without a PID/FD-count cutoff that misses a busy daemon.
    DIR* processes = opendir("/proc");
    if (!processes) { fail(env, "opendir(/proc)", errno); return nullptr; }
    const int64_t deadline = monotonicMillis() + 2000;
    unsigned visited = 0, pids = 0, inaccessible = 0, matches = 0, deniedLinks = 0;
    unsigned deniedLabels = 0;
    int labelError = 0, directoryError = 0, linkError = 0;
    char label[4096];
    ssize_t labelSize = -1;
    char expected[80];
    snprintf(expected, sizeof(expected), "socket:[%llu]",
             static_cast<unsigned long long>(inode));
    while (dirent* process = readdir(processes)) {
        if (monotonicMillis() >= deadline) break;
        if (!numeric(process->d_name)) continue;
        ++pids;
        char directory[512];
        snprintf(directory, sizeof(directory), "/proc/%s/fd", process->d_name);
        DIR* descriptors = opendir(directory);
        if (!descriptors) { ++inaccessible; directoryError = errno; continue; }
        while (dirent* descriptor = readdir(descriptors)) {
            if (monotonicMillis() >= deadline) break;
            if (!numeric(descriptor->d_name)) continue;
            ++visited;
            char target[80];
            const ssize_t length = readlinkat(dirfd(descriptors), descriptor->d_name,
                                             target, sizeof(target) - 1);
            if (length < 0) { ++deniedLinks; linkError = errno; continue; }
            if (length >= static_cast<ssize_t>(sizeof(target) - 1)) continue;
            target[length] = '\0';
            if (strcmp(target, expected) != 0) continue;
            ++matches;
            // O_PATH follows the proc magic link and pins the sockfs inode.
            // Opening a socket with O_RDONLY fails ENXIO; O_PATH is intentional.
            ScopedFd pinned(openat(dirfd(descriptors), descriptor->d_name, O_PATH | O_CLOEXEC));
            if (pinned.get() < 0) { labelError = errno; continue; }
            struct stat identity{};
            if (fstat(pinned.get(), &identity) < 0) { labelError = errno; continue; }
            if (!S_ISSOCK(identity.st_mode) || identity.st_ino != static_cast<ino_t>(inode)) {
                labelError = ESTALE;
                continue;
            }
            char ownPath[80];
            snprintf(ownPath, sizeof(ownPath), "/proc/self/fd/%d", pinned.get());
            // fgetxattr rejects O_PATH descriptors with EBADF. Following our own
            // pinned descriptor accesses the sockfs inode, never the bound path.
            // SELinux socket_post_create assigns this inode and sk_security the
            // same SID; UNIX_DGRAM SO_PEERSEC is not supported by SELinux.
            labelSize = getxattr(ownPath, "security.selinux", label, sizeof(label));
            if (labelSize < 0) { ++deniedLabels; labelError = errno; continue; }
            break;
        }
        closedir(descriptors);
        if (labelSize >= 0) break;
    }
    closedir(processes);
    if (labelSize < 0) {
        char message[512];
        snprintf(message, sizeof(message),
                 "Cannot read supplicant sockfs SID: inode=%llu pids=%u fds=%u matching=%u "
                 "pid_dir_failures=%u errno=%d link_failures=%u errno=%d label_failures=%u "
                 "label_errno=%d (%s) "
                 "deadline=%s; check root SELinux proc/fd and getattr permissions",
                 static_cast<unsigned long long>(inode), pids, visited, matches, inaccessible,
                 directoryError, deniedLinks, linkError, deniedLabels, labelError, strerror(labelError),
                 monotonicMillis() >= deadline ? "expired" : "not expired");
        invalidIdentity(env, message);
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(labelSize));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(labelSize),
                                       reinterpret_cast<const jbyte*>(label));
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_foxlost_miracast_sink_p2p_SupplicantSocketLabel_peerNativeContext(
        JNIEnv* env, jobject, jstring path) {
    const char* value = env->GetStringUTFChars(path, nullptr);
    if (!value) return nullptr;
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    const size_t length = strlen(value);
    if (length == 0 || length >= sizeof(address.sun_path) || value[0] != '/') {
        env->ReleaseStringUTFChars(path, value);
        invalidIdentity(env, "Invalid absolute supplicant peer path");
        return nullptr;
    }
    memcpy(address.sun_path, value, length + 1);
    env->ReleaseStringUTFChars(path, value);
    ScopedFd probe(socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0));
    if (probe.get() < 0) { fail(env, "socket(peer SID probe)", errno); return nullptr; }
    const int enabled = 1;
    // Linux SO_PASSSEC uses SCM_SECURITY for datagram sender labels. SO_PEERSEC
    // only implements stream peer_sid and is deliberately not used here.
    if (setsockopt(probe.get(), SOL_SOCKET, SO_PASSSEC, &enabled, sizeof(enabled)) < 0) {
        fail(env, "SO_PASSSEC(peer SID probe)", errno); return nullptr;
    }
    if (connect(probe.get(), reinterpret_cast<sockaddr*>(&address),
                offsetof(sockaddr_un, sun_path) + length + 1) < 0) {
        fail(env, "connect(peer SID probe)", errno); return nullptr;
    }
    // Connect before binding, so an attacker cannot queue packets before peer
    // filtering. A family-only bind asks Linux for a unique abstract reply name.
    sockaddr_un reply{};
    reply.sun_family = AF_UNIX;
    if (bind(probe.get(), reinterpret_cast<sockaddr*>(&reply), sizeof(sa_family_t)) < 0) {
        fail(env, "autobind(peer SID probe)", errno); return nullptr;
    }
    char response[32];
    bool drained = false;
    for (int attempt = 0; attempt < 256; ++attempt) {
        if (recv(probe.get(), response, sizeof(response), MSG_DONTWAIT) >= 0) continue;
        if (errno == EAGAIN || errno == EWOULDBLOCK) { drained = true; break; }
        if (errno != EINTR) { fail(env, "drain(peer SID probe)", errno); return nullptr; }
    }
    if (!drained) {
        invalidIdentity(env, "Peer SID probe queue did not drain");
        return nullptr;
    }
    const int64_t deadline = monotonicMillis() + 1200;
    while (true) {
        if (!waitSocket(probe.get(), POLLOUT, deadline)) {
            fail(env, "poll(PING peer SID probe)", errno); return nullptr;
        }
        const ssize_t sent = send(probe.get(), "PING", 4, MSG_NOSIGNAL | MSG_DONTWAIT);
        if (sent == 4) break;
        if (sent < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        fail(env, "send(PING peer SID probe)", sent < 0 ? errno : EIO); return nullptr;
    }
    alignas(cmsghdr) char ancillary[CMSG_SPACE(4096)]{};
    iovec payload{response, sizeof(response)};
    msghdr message{};
    message.msg_iov = &payload;
    message.msg_iovlen = 1;
    message.msg_control = ancillary;
    message.msg_controllen = sizeof(ancillary);
    ssize_t count;
    while (true) {
        if (!waitSocket(probe.get(), POLLIN, deadline)) {
            fail(env, "poll(PONG peer SID probe)", errno); return nullptr;
        }
        message.msg_controllen = sizeof(ancillary);
        message.msg_flags = 0;
        count = recvmsg(probe.get(), &message, MSG_TRUNC | MSG_DONTWAIT | MSG_CMSG_CLOEXEC);
        if (count >= 0) break;
        if (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK) continue;
        fail(env, "recvmsg(PONG peer SID probe)", errno); return nullptr;
    }
    bool unexpectedControl = false;
    for (cmsghdr* header = CMSG_FIRSTHDR(&message); header;
         header = CMSG_NXTHDR(&message, header)) {
        if (header->cmsg_level == SOL_SOCKET && header->cmsg_type == SCM_RIGHTS &&
            header->cmsg_len >= CMSG_LEN(0)) {
            const size_t descriptors = (header->cmsg_len - CMSG_LEN(0)) / sizeof(int);
            for (size_t index = 0; index < descriptors; ++index) {
                int receivedFd;
                memcpy(&receivedFd, CMSG_DATA(header) + index * sizeof(int), sizeof(int));
                close(receivedFd);
            }
        }
        if (header->cmsg_level != SOL_SOCKET || header->cmsg_type != 0x03) unexpectedControl = true;
    }
    if (unexpectedControl || (message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) != 0 ||
        !((count == 4 && memcmp(response, "PONG", 4) == 0) ||
          (count == 5 && memcmp(response, "PONG\n", 5) == 0))) {
        invalidIdentity(env, "Invalid or truncated PONG in peer SID probe");
        return nullptr;
    }
    const unsigned char* context = nullptr;
    size_t contextLength = 0;
    for (cmsghdr* header = CMSG_FIRSTHDR(&message); header;
         header = CMSG_NXTHDR(&message, header)) {
        // SCM_SECURITY is Linux UAPI value 0x03 (not exposed by all NDK headers).
        if (header->cmsg_level != SOL_SOCKET || header->cmsg_type != 0x03) continue;
        if (context || header->cmsg_len <= CMSG_LEN(0) ||
            header->cmsg_len > CMSG_LEN(4096)) {
            invalidIdentity(env, "Ambiguous or invalid SCM_SECURITY peer SID");
            return nullptr;
        }
        context = CMSG_DATA(header);
        contextLength = header->cmsg_len - CMSG_LEN(0);
    }
    if (!context) {
        invalidIdentity(env, "PONG has no SCM_SECURITY; kernel/policy does not provide datagram peer SID");
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(contextLength));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(contextLength),
                                       reinterpret_cast<const jbyte*>(context));
    return result;
}
