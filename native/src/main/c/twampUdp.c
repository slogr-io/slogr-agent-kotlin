/*
 * twampUdp.c — JNI UDP socket operations for TWAMP packet I/O.
 *
 * Provides low-level UDP socket control (TTL, TOS, RECVTTL, RECVTOS) that is
 * not available through standard Java DatagramSocket in Java < 17.
 *
 * JNI class: io.slogr.agent.native.SlogrNative (Kotlin object)
 */

#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <stdio.h>
#include <stdint.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

/* SO_TIMESTAMPING / SCM_TIMESTAMPING — available since Linux 2.6.30. */
#ifndef SO_TIMESTAMPING
#  define SO_TIMESTAMPING 37
#endif
#ifndef SCM_TIMESTAMPING
#  define SCM_TIMESTAMPING SO_TIMESTAMPING
#endif
/* Flags: receive software timestamp + report it in the cmsg. */
#ifndef SOF_TIMESTAMPING_RX_SOFTWARE
#  define SOF_TIMESTAMPING_RX_SOFTWARE (1 << 3)
#endif
#ifndef SOF_TIMESTAMPING_SOFTWARE
#  define SOF_TIMESTAMPING_SOFTWARE    (1 << 4)
#endif

/* struct scm_timestamping — three timespec entries (software, deprecated HW, raw HW). */
struct slogr_scm_timestamping {
    struct timespec ts[3];
};

/* NTP era 0 epoch offset: seconds between 1900-01-01 and 1970-01-01. */
#define NTP_EPOCH_OFFSET 2208988800ULL

#include "twampUdp.h"

#define SLOGR_RECV_BUF 9000

/* Populate a sockaddr from a JNI byte array (4 bytes = IPv4, 16 = IPv6). */
static struct sockaddr *fill_sockaddr(JNIEnv *env, jbyteArray ip, jshort port,
                                      int *addrlen,
                                      struct sockaddr_in  *a4,
                                      struct sockaddr_in6 *a6)
{
    jbyte *buf = (*env)->GetByteArrayElements(env, ip, NULL);
    jsize  len = (*env)->GetArrayLength(env, ip);
    struct sockaddr *result;

    if (len == 4) {
        memset(a4, 0, sizeof(*a4));
        a4->sin_family = AF_INET;
        memcpy(&a4->sin_addr.s_addr, buf, 4);
        a4->sin_port  = htons((uint16_t)port);
        *addrlen      = (int)sizeof(*a4);
        result        = (struct sockaddr *)a4;
    } else {
        memset(a6, 0, sizeof(*a6));
        a6->sin6_family = AF_INET6;
        memcpy(a6->sin6_addr.s6_addr, buf, 16);
        a6->sin6_port   = htons((uint16_t)port);
        *addrlen        = (int)sizeof(*a6);
        result          = (struct sockaddr *)a6;
    }

    (*env)->ReleaseByteArrayElements(env, ip, buf, JNI_ABORT);
    return result;
}

/* ── Socket lifecycle ──────────────────────────────────────────────────── */

JNIEXPORT jint JNICALL
Java_io_slogr_agent_native_SlogrNative_createSocket(JNIEnv *env, jobject obj)
{
    return socket(AF_INET, SOCK_DGRAM, 0);
}

JNIEXPORT jint JNICALL
Java_io_slogr_agent_native_SlogrNative_createSocket6(JNIEnv *env, jobject obj)
{
    return socket(AF_INET6, SOCK_DGRAM, 0);
}

JNIEXPORT void JNICALL
Java_io_slogr_agent_native_SlogrNative_closeSocket(JNIEnv *env, jobject obj, jint fd)
{
    close((int)fd);
}

JNIEXPORT jint JNICALL
Java_io_slogr_agent_native_SlogrNative_bindSocket(JNIEnv *env, jobject obj,
                                                   jint fd, jint ip, jint port)
{
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family      = AF_INET;
    addr.sin_addr.s_addr = htonl((uint32_t)ip);
    addr.sin_port        = htons((uint16_t)port);
    return bind((int)fd, (struct sockaddr *)&addr, sizeof(addr));
}

JNIEXPORT jint JNICALL
Java_io_slogr_agent_native_SlogrNative_bindSocket6(JNIEnv *env, jobject obj,
                                                    jint fd, jbyteArray ip, jshort port)
{
    struct sockaddr_in  a4;
    struct sockaddr_in6 a6;
    int addrlen = 0;
    struct sockaddr *addr = fill_sockaddr(env, ip, port, &addrlen, &a4, &a6);
    return bind((int)fd, addr, (socklen_t)addrlen);
}

JNIEXPORT jint JNICALL
Java_io_slogr_agent_native_SlogrNative_connectSocket(JNIEnv *env, jobject obj,
                                                      jint fd, jbyteArray ip, jshort port)
{
    struct sockaddr_in  a4;
    struct sockaddr_in6 a6;
    int addrlen = 0;
    struct sockaddr *addr = fill_sockaddr(env, ip, port, &addrlen, &a4, &a6);
    return connect((int)fd, addr, (socklen_t)addrlen);
}

/* ── Socket options ────────────────────────────────────────────────────── */

/*
 * setSocketOption — set IP_TTL=255 and enable IP_RECVTTL + IP_RECVTOS
 * ancillary data on the socket so recvmsg can capture them.
 */
JNIEXPORT jint JNICALL
Java_io_slogr_agent_native_SlogrNative_setSocketOption(JNIEnv *env, jobject obj,
                                                        jint fd, jint ttl)
{
    uint8_t ip_ttl = (uint8_t)ttl;
    int     one    = 1;

    if (setsockopt((int)fd, IPPROTO_IP, IP_TTL, &ip_ttl, sizeof(ip_ttl)) != 0) {
        fprintf(stderr, "slogr-native: setsockopt(IP_TTL): %s\n", strerror(errno));
        return -1;
    }
    if (setsockopt((int)fd, IPPROTO_IP, IP_RECVTTL, &one, sizeof(one)) != 0) {
        fprintf(stderr, "slogr-native: setsockopt(IP_RECVTTL): %s\n", strerror(errno));
        return -1;
    }
    if (setsockopt((int)fd, IPPROTO_IP, IP_RECVTOS, &one, sizeof(one)) != 0) {
        fprintf(stderr, "slogr-native: setsockopt(IP_RECVTOS): %s\n", strerror(errno));
        return -1;
    }
    return 0;
}

/* setSocketOption6 — IPv6 equivalent: hop limit + RECVHOPLIMIT */
JNIEXPORT jint JNICALL
Java_io_slogr_agent_native_SlogrNative_setSocketOption6(JNIEnv *env, jobject obj,
                                                         jint fd, jint ttl)
{
    int hops = (int)ttl;
    int one  = 1;

    if (setsockopt((int)fd, IPPROTO_IPV6, IPV6_UNICAST_HOPS, &hops, sizeof(hops)) != 0) {
        fprintf(stderr, "slogr-native: setsockopt(IPV6_UNICAST_HOPS): %s\n", strerror(errno));
        return -1;
    }
    if (setsockopt((int)fd, IPPROTO_IPV6, IPV6_RECVHOPLIMIT, &one, sizeof(one)) != 0) {
        fprintf(stderr, "slogr-native: setsockopt(IPV6_RECVHOPLIMIT): %s\n", strerror(errno));
        return -1;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_slogr_agent_native_SlogrNative_setSocketTos(JNIEnv *env, jobject obj,
                                                     jint fd, jshort tos)
{
    uint8_t ip_tos = (uint8_t)tos;
    if (setsockopt((int)fd, IPPROTO_IP, IP_TOS, &ip_tos, sizeof(ip_tos)) != 0) {
        fprintf(stderr, "slogr-native: setsockopt(IP_TOS): %s\n", strerror(errno));
        return -1;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_slogr_agent_native_SlogrNative_setSocketTos6(JNIEnv *env, jobject obj,
                                                      jint fd, jshort tos)
{
    int tclass = (int)tos;
    if (setsockopt((int)fd, IPPROTO_IPV6, IPV6_TCLASS, &tclass, sizeof(tclass)) != 0) {
        fprintf(stderr, "slogr-native: setsockopt(IPV6_TCLASS): %s\n", strerror(errno));
        return -1;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_slogr_agent_native_SlogrNative_setSocketTimeout(JNIEnv *env, jobject obj,
                                                         jint fd, jint ms)
{
    struct timeval tv;
    tv.tv_sec  = (time_t)(ms / 1000);
    tv.tv_usec = (suseconds_t)((ms % 1000) * 1000);
    return setsockopt((int)fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv)) == 0 ? 0 : -1;
}

/* ── Packet I/O ────────────────────────────────────────────────────────── */

/*
 * getLocalPort — return the ephemeral port the socket is bound to via getsockname().
 * Needed after bind(port=0) to discover the kernel-assigned port number.
 */
JNIEXPORT jint JNICALL
Java_io_slogr_agent_native_SlogrNative_getLocalPort(JNIEnv *env, jobject obj, jint fd)
{
    struct sockaddr_in addr;
    socklen_t len = sizeof(addr);
    if (getsockname((int)fd, (struct sockaddr *)&addr, &len) != 0) return 0;
    return (jint)ntohs(addr.sin_port);
}

/*
 * enableTimestamping — enable SO_TIMESTAMPING (RX_SOFTWARE | SOFTWARE) on fd.
 * Must be called after setSocketOption. Returns 0 on success, -1 on error.
 */
JNIEXPORT jint JNICALL
Java_io_slogr_agent_native_SlogrNative_enableTimestamping(JNIEnv *env, jobject obj, jint fd)
{
    int flags = SOF_TIMESTAMPING_RX_SOFTWARE | SOF_TIMESTAMPING_SOFTWARE;
    if (setsockopt((int)fd, SOL_SOCKET, SO_TIMESTAMPING, &flags, sizeof(flags)) != 0) {
        fprintf(stderr, "slogr-native: setsockopt(SO_TIMESTAMPING): %s\n", strerror(errno));
        return -1;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_io_slogr_agent_native_SlogrNative_sendTo(JNIEnv *env, jobject obj,
                                               jint fd, jbyteArray ip, jshort port,
                                               jbyteArray data, jint len)
{
    struct sockaddr_in  a4;
    struct sockaddr_in6 a6;
    int addrlen = 0;
    struct sockaddr *addr = fill_sockaddr(env, ip, port, &addrlen, &a4, &a6);

    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    int rv = (int)sendto((int)fd, buf, (size_t)len, 0, addr, (socklen_t)addrlen);
    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    return rv;
}

/*
 * recvMsg — receive a UDP packet via recvmsg(2) and extract ancillary data:
 *   IP_RECVTTL / IP_RECVTOS    — TTL and DSCP (requires setSocketOption first)
 *   SCM_TIMESTAMPING           — kernel T2 timestamp (requires enableTimestamping first)
 *
 * Output arrays (each length 1):
 *   ip[]       — source IPv4 address as host-order int
 *   port[]     — source port
 *   ttl[]      — received IP TTL (0 if not available)
 *   tos[]      — received IP TOS (0 if not available)
 *   ntpTs[]    — kernel T2 in NTP 64-bit format (0 if unavailable)
 *   tsSource[] — 1 if ntpTs was filled by kernel timestamp, 0 otherwise
 *
 * Returns: bytes read (> 0), 0 on EOF, -1 on timeout/error.
 */
JNIEXPORT jint JNICALL
Java_io_slogr_agent_native_SlogrNative_recvMsg(JNIEnv *env, jobject obj,
                                                jint fd, jbyteArray data, jint len,
                                                jintArray ip, jshortArray port,
                                                jshortArray ttl, jshortArray tos,
                                                jlongArray ntpTs, jintArray tsSource)
{
    char             ctrl_buf[SLOGR_RECV_BUF];
    struct sockaddr_in src;
    struct iovec     iov;
    struct msghdr    msg;
    jbyte           *buf;
    int              rv;

    memset(&msg, 0, sizeof(msg));
    memset(&src, 0, sizeof(src));

    buf             = (*env)->GetByteArrayElements(env, data, NULL);
    iov.iov_base    = buf;
    iov.iov_len     = (size_t)len;
    msg.msg_iov     = &iov;
    msg.msg_iovlen  = 1;
    msg.msg_name    = &src;
    msg.msg_namelen = sizeof(src);
    msg.msg_control    = ctrl_buf;
    msg.msg_controllen = sizeof(ctrl_buf);

    rv = (int)recvmsg((int)fd, &msg, 0);
    (*env)->ReleaseByteArrayElements(env, data, buf, 0);  /* copy back */

    if (rv <= 0) return rv;

    /* Source address */
    jint   *ip_p   = (*env)->GetIntArrayElements(env, ip, NULL);
    jshort *port_p = (*env)->GetShortArrayElements(env, port, NULL);
    *ip_p   = (jint)ntohl(src.sin_addr.s_addr);
    *port_p = (jshort)ntohs(src.sin_port);
    (*env)->ReleaseIntArrayElements(env, ip, ip_p, 0);
    (*env)->ReleaseShortArrayElements(env, port, port_p, 0);

    /* Ancillary data: TTL, TOS, and kernel timestamp */
    jshort *ttl_p      = (*env)->GetShortArrayElements(env, ttl, NULL);
    jshort *tos_p      = (*env)->GetShortArrayElements(env, tos, NULL);
    jlong  *ntp_ts_p   = (*env)->GetLongArrayElements(env, ntpTs, NULL);
    jint   *ts_src_p   = (*env)->GetIntArrayElements(env, tsSource, NULL);

    struct cmsghdr *cmsg;
    for (cmsg = CMSG_FIRSTHDR(&msg); cmsg; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
        if (cmsg->cmsg_level == IPPROTO_IP && cmsg->cmsg_type == IP_TTL) {
            *ttl_p = (jshort)(*(int *)CMSG_DATA(cmsg));
        } else if (cmsg->cmsg_level == IPPROTO_IPV6 &&
                   cmsg->cmsg_type == IPV6_HOPLIMIT) {
            *ttl_p = (jshort)(*(int *)CMSG_DATA(cmsg));
        } else if (cmsg->cmsg_level == IPPROTO_IP && cmsg->cmsg_type == IP_TOS) {
            *tos_p = (jshort)(*(int *)CMSG_DATA(cmsg));
        } else if (cmsg->cmsg_level == SOL_SOCKET &&
                   cmsg->cmsg_type  == SCM_TIMESTAMPING) {
            /* ts[0] = software RX timestamp */
            struct slogr_scm_timestamping *tss =
                (struct slogr_scm_timestamping *)CMSG_DATA(cmsg);
            if (tss->ts[0].tv_sec != 0 || tss->ts[0].tv_nsec != 0) {
                uint64_t secs = (uint64_t)tss->ts[0].tv_sec + NTP_EPOCH_OFFSET;
                uint64_t frac = ((uint64_t)tss->ts[0].tv_nsec << 32) / 1000000000ULL;
                *ntp_ts_p  = (jlong)((secs << 32) | frac);
                *ts_src_p  = 1;
            }
        }
    }

    (*env)->ReleaseShortArrayElements(env, ttl, ttl_p, 0);
    (*env)->ReleaseShortArrayElements(env, tos, tos_p, 0);
    (*env)->ReleaseLongArrayElements(env, ntpTs, ntp_ts_p, 0);
    (*env)->ReleaseIntArrayElements(env, tsSource, ts_src_p, 0);

    return rv;
}
