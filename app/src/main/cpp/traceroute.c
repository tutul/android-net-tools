// One traceroute probe over an unprivileged ICMP ("ping") socket.
//
// Apps can't open raw sockets, but Android allows SOCK_DGRAM + IPPROTO_ICMP(V6).
// We set the TTL / hop limit per probe and enable IP_RECVERR, so the kernel
// queues the routers' "time exceeded" replies on the socket's error queue,
// along with the router's address (the "offender").

#include <jni.h>
#include <errno.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <netinet/icmp6.h>
#include <netinet/ip_icmp.h>
#include <sys/socket.h>
#include <linux/errqueue.h>

static void throw_io(JNIEnv *env, const char *what, int err) {
    char msg[160];
    snprintf(msg, sizeof msg, "%s: %s", what, strerror(err));
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"), msg);
}

static long long now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000000LL + ts.tv_nsec / 1000;
}

static void addr_to_string(const struct sockaddr *sa, char *out, socklen_t len) {
    const void *src = sa->sa_family == AF_INET6
        ? (const void *) &((const struct sockaddr_in6 *) sa)->sin6_addr
        : (const void *) &((const struct sockaddr_in *) sa)->sin_addr;
    if (!inet_ntop(sa->sa_family, src, out, len)) snprintf(out, len, "?");
}

// The echo header we sent comes back with the error; bytes 6-7 are our sequence.
static int seq_of(const unsigned char *icmp, ssize_t n) {
    return n >= 8 ? (icmp[6] << 8 | icmp[7]) : -1;
}

JNIEXPORT jint JNICALL
Java_today_tutul_nettools_net_NativeTrace_open(JNIEnv *env, jobject thiz, jboolean v6) {
    int fd = socket(v6 ? AF_INET6 : AF_INET, SOCK_DGRAM | SOCK_CLOEXEC,
                    v6 ? IPPROTO_ICMPV6 : IPPROTO_ICMP);
    if (fd < 0) {
        throw_io(env, "socket", errno);
        return -1;
    }
    int on = 1;
    int r = v6 ? setsockopt(fd, IPPROTO_IPV6, IPV6_RECVERR, &on, sizeof on)
               : setsockopt(fd, IPPROTO_IP, IP_RECVERR, &on, sizeof on);
    if (r < 0) {
        int err = errno;
        close(fd);
        throw_io(env, "IP_RECVERR", err);
        return -1;
    }
    return fd;
}

JNIEXPORT void JNICALL
Java_today_tutul_nettools_net_NativeTrace_close(JNIEnv *env, jobject thiz, jint fd) {
    close(fd);
}

// Returns "<kind> <ip> <rtt_us> <icmp_code>" or null on timeout, where kind is
// R = echo reply from the target, T = time exceeded (a router on the way),
// U = destination unreachable, E = other error reported by the kernel.
JNIEXPORT jstring JNICALL
Java_today_tutul_nettools_net_NativeTrace_probe(JNIEnv *env, jobject thiz, jint fd,
                                                jbyteArray addr, jint scope_id, jint ttl,
                                                jint seq, jint timeout_ms) {
    struct sockaddr_storage dst;
    memset(&dst, 0, sizeof dst);
    socklen_t dst_len;
    int v6 = (*env)->GetArrayLength(env, addr) == 16;
    if (v6) {
        struct sockaddr_in6 *s = (struct sockaddr_in6 *) &dst;
        s->sin6_family = AF_INET6;
        s->sin6_scope_id = (uint32_t) scope_id;
        (*env)->GetByteArrayRegion(env, addr, 0, 16, (jbyte *) &s->sin6_addr);
        dst_len = sizeof *s;
    } else {
        struct sockaddr_in *s = (struct sockaddr_in *) &dst;
        s->sin_family = AF_INET;
        (*env)->GetByteArrayRegion(env, addr, 0, 4, (jbyte *) &s->sin_addr);
        dst_len = sizeof *s;
    }

    int r = v6 ? setsockopt(fd, IPPROTO_IPV6, IPV6_UNICAST_HOPS, &ttl, sizeof ttl)
               : setsockopt(fd, IPPROTO_IP, IP_TTL, &ttl, sizeof ttl);
    if (r < 0) {
        throw_io(env, "set ttl", errno);
        return NULL;
    }

    // For ping sockets the kernel fills in the identifier and checksum.
    unsigned char pkt[64];
    memset(pkt, 0, sizeof pkt);
    pkt[0] = v6 ? ICMP6_ECHO_REQUEST : ICMP_ECHO;
    pkt[6] = (unsigned char) (seq >> 8);
    pkt[7] = (unsigned char) seq;

    long long start = now_us();
    if (sendto(fd, pkt, sizeof pkt, 0, (struct sockaddr *) &dst, dst_len) < 0) {
        throw_io(env, "sendto", errno);
        return NULL;
    }

    char result[128];
    for (;;) {
        long long left_us = timeout_ms * 1000LL - (now_us() - start);
        if (left_us <= 0) return NULL;
        struct pollfd p = {.fd = fd, .events = POLLIN};
        r = poll(&p, 1, (int) ((left_us + 999) / 1000));
        if (r < 0) {
            if (errno == EINTR) continue;
            throw_io(env, "poll", errno);
            return NULL;
        }
        if (r == 0) return NULL;

        unsigned char data[128];
        struct sockaddr_storage from;
        char ip[INET6_ADDRSTRLEN];

        if (p.revents & POLLERR) {
            char ctrl[512];
            struct iovec iov = {.iov_base = data, .iov_len = sizeof data};
            struct msghdr msg = {
                .msg_name = &from, .msg_namelen = sizeof from,
                .msg_iov = &iov, .msg_iovlen = 1,
                .msg_control = ctrl, .msg_controllen = sizeof ctrl,
            };
            ssize_t n = recvmsg(fd, &msg, MSG_ERRQUEUE | MSG_DONTWAIT);
            long long rtt = now_us() - start;
            if (n < 0 || seq_of(data, n) != seq) continue; // stale reply from an earlier probe

            for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
                if (!((c->cmsg_level == IPPROTO_IP && c->cmsg_type == IP_RECVERR) ||
                      (c->cmsg_level == IPPROTO_IPV6 && c->cmsg_type == IPV6_RECVERR))) continue;
                struct sock_extended_err *ee = (struct sock_extended_err *) CMSG_DATA(c);
                char kind = 'E';
                if (ee->ee_origin == SO_EE_ORIGIN_ICMP) {
                    kind = ee->ee_type == ICMP_TIME_EXCEEDED ? 'T'
                         : ee->ee_type == ICMP_DEST_UNREACH ? 'U' : 'E';
                } else if (ee->ee_origin == SO_EE_ORIGIN_ICMP6) {
                    kind = ee->ee_type == ICMP6_TIME_EXCEEDED ? 'T'
                         : ee->ee_type == ICMP6_DST_UNREACH ? 'U' : 'E';
                }
                struct sockaddr *offender = SO_EE_OFFENDER(ee);
                if (offender->sa_family == AF_INET || offender->sa_family == AF_INET6) {
                    addr_to_string(offender, ip, sizeof ip);
                } else {
                    snprintf(ip, sizeof ip, "?");
                }
                snprintf(result, sizeof result, "%c %s %lld %d", kind, ip, rtt,
                         kind == 'E' ? (int) ee->ee_errno : (int) ee->ee_code);
                return (*env)->NewStringUTF(env, result);
            }
            continue;
        }

        if (p.revents & POLLIN) {
            socklen_t from_len = sizeof from;
            ssize_t n = recvfrom(fd, data, sizeof data, MSG_DONTWAIT,
                                 (struct sockaddr *) &from, &from_len);
            long long rtt = now_us() - start;
            if (n < 0 || seq_of(data, n) != seq) continue;
            int is_reply = v6 ? data[0] == ICMP6_ECHO_REPLY : data[0] == ICMP_ECHOREPLY;
            if (!is_reply) continue;
            addr_to_string((struct sockaddr *) &from, ip, sizeof ip);
            snprintf(result, sizeof result, "R %s %lld 0", ip, rtt);
            return (*env)->NewStringUTF(env, result);
        }
    }
}
