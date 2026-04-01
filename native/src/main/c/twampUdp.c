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
#include <sys/time.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

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
 * recvMsg — receive a UDP packet via recvmsg(2) and extract TTL and TOS from
 * IP_RECVTTL / IP_RECVTOS ancillary data (requires setSocketOption first).
 *
 * Output arrays (each length 1):
 *   ip[]   — source IPv4 address as host-order int
 *   port[] — source port
 *   ttl[]  — received IP TTL (0 if not available)
 *   tos[]  — received IP TOS (0 if not available)
 *
 * Returns: bytes read (> 0), 0 on EOF, -1 on timeout/error.
 */
JNIEXPORT jint JNICALL
Java_io_slogr_agent_native_SlogrNative_recvMsg(JNIEnv *env, jobject obj,
                                                jint fd, jbyteArray data, jint len,
                                                jintArray ip, jshortArray port,
                                                jshortArray ttl, jshortArray tos)
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

    /* Ancillary data: TTL and TOS */
    jshort *ttl_p = (*env)->GetShortArrayElements(env, ttl, NULL);
    jshort *tos_p = (*env)->GetShortArrayElements(env, tos, NULL);

    struct cmsghdr *cmsg;
    for (cmsg = CMSG_FIRSTHDR(&msg); cmsg; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
        if (cmsg->cmsg_level == IPPROTO_IP && cmsg->cmsg_type == IP_TTL) {
            *ttl_p = (jshort)(*(int *)CMSG_DATA(cmsg));
        } else if (cmsg->cmsg_level == IPPROTO_IPV6 &&
                   cmsg->cmsg_type == IPV6_HOPLIMIT) {
            *ttl_p = (jshort)(*(int *)CMSG_DATA(cmsg));
        } else if (cmsg->cmsg_level == IPPROTO_IP && cmsg->cmsg_type == IP_TOS) {
            *tos_p = (jshort)(*(int *)CMSG_DATA(cmsg));
        }
    }

    (*env)->ReleaseShortArrayElements(env, ttl, ttl_p, 0);
    (*env)->ReleaseShortArrayElements(env, tos, tos_p, 0);

    return rv;
}
