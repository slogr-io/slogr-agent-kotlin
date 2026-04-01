#ifndef SLOGR_TRACEROUTE_H
#define SLOGR_TRACEROUTE_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * icmpProbe — send one ICMP ECHO REQUEST with the given TTL to targetIp and
 * wait for an ICMP TIME_EXCEEDED or ECHO_REPLY response.
 *
 * Returns: RTT in microseconds (>= 0 on success, -1 on timeout, -2 on error).
 *
 * Output arrays (caller-allocated, minimum size shown):
 *   hopIpOut[4]  — network-byte-order IPv4 bytes of the responding router
 *                  (zeroed on timeout/error)
 *   metaOut[3]   — [0] reached (1=destination, 0=TTL expired), [1] ICMP type,
 *                  [2] ICMP code (-1 for both if timeout)
 */
JNIEXPORT jlong JNICALL
Java_io_slogr_agent_native_SlogrNative_icmpProbe(JNIEnv *, jobject,
    jbyteArray targetIp, jint ttl, jint timeoutMs,
    jbyteArray hopIpOut, jintArray metaOut);

/*
 * udpProbe — send one UDP datagram with the given TTL to targetIp:targetPort
 * and wait for an ICMP TIME_EXCEEDED or PORT_UNREACH response.
 *
 * Same return convention and output arrays as icmpProbe.
 */
JNIEXPORT jlong JNICALL
Java_io_slogr_agent_native_SlogrNative_udpProbe(JNIEnv *, jobject,
    jbyteArray targetIp, jint targetPort, jint ttl, jint timeoutMs,
    jbyteArray hopIpOut, jintArray metaOut);

/*
 * tcpProbe — send a TCP SYN with the given TTL to targetIp:destPort (typically 443).
 *
 * Listens on a separate ICMP socket for ICMP Time Exceeded (intermediate hop)
 * and on a raw TCP socket for SYN-ACK or RST (destination reached).
 * If a SYN-ACK is received, sends a RST to close cleanly without completing
 * the handshake.
 *
 * Same return convention and output arrays as icmpProbe.
 * metaOut[1] and metaOut[2] are -1 for TCP responses (no ICMP type/code).
 */
JNIEXPORT jlong JNICALL
Java_io_slogr_agent_native_SlogrNative_tcpProbe(JNIEnv *, jobject,
    jbyteArray targetIp, jint destPort, jint ttl, jint timeoutMs,
    jbyteArray hopIpOut, jintArray metaOut);

#ifdef __cplusplus
}
#endif
#endif /* SLOGR_TRACEROUTE_H */
