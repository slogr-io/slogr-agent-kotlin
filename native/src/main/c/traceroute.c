/*
 * traceroute.c — JNI raw-socket traceroute probes for Slogr.
 *
 * Implements per-hop ICMP and UDP traceroute probes using SOCK_RAW.
 * Requires CAP_NET_RAW on Linux (provided via systemd AmbientCapabilities).
 *
 * JNI class: io.slogr.agent.native.SlogrNative (Kotlin object)
 */

#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/ip.h>
#include <netinet/ip_icmp.h>
#include <netinet/udp.h>
#include <arpa/inet.h>
#include <time.h>

#include "traceroute.h"

/* Magic 2-byte identifier in ICMP ECHO id field to distinguish our probes. */
#define SLOGR_PROBE_ID   0x5367u   /* 'S','g' */
#define ICMP_ECHO_TYPE   8
#define ICMP_REPLY_TYPE  0
#define ICMP_TEXCEED     11        /* time exceeded */
#define ICMP_UNREACH     3         /* destination unreachable */
#define PROBE_DATA_LEN   32
#define RECV_BUF_LEN     1024
/* UDP probe destination port base (RFC 33434). */
#define UDP_PROBE_PORT   33434

/* ── Helpers ──────────────────────────────────────────────────────────── */

static uint16_t inet_cksum(const void *data, size_t len)
{
    const uint16_t *buf = (const uint16_t *)data;
    uint32_t sum = 0;
    while (len > 1) { sum += *buf++; len -= 2; }
    if (len)          sum += *(const uint8_t *)buf;
    while (sum >> 16) sum  = (sum & 0xffff) + (sum >> 16);
    return (uint16_t)(~sum);
}

static long now_us(void)
{
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (long)tv.tv_sec * 1000000L + (long)tv.tv_usec;
}

static void set_timeout(int fd, int ms)
{
    struct timeval tv;
    tv.tv_sec  = ms / 1000;
    tv.tv_usec = (ms % 1000) * 1000;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
}

/* Write results back to JNI output arrays. */
static void write_outputs(JNIEnv *env,
                          jbyteArray hopIpOut, jintArray metaOut,
                          const struct in_addr *hop_addr,
                          int reached, int icmp_type, int icmp_code)
{
    jbyte *ip_p = (*env)->GetByteArrayElements(env, hopIpOut, NULL);
    if (hop_addr)
        memcpy(ip_p, &hop_addr->s_addr, 4);
    else
        memset(ip_p, 0, 4);
    (*env)->ReleaseByteArrayElements(env, hopIpOut, ip_p, 0);

    jint *meta = (*env)->GetIntArrayElements(env, metaOut, NULL);
    meta[0] = reached;
    meta[1] = icmp_type;
    meta[2] = icmp_code;
    (*env)->ReleaseIntArrayElements(env, metaOut, meta, 0);
}

/* ── icmpProbe ─────────────────────────────────────────────────────────── */

JNIEXPORT jlong JNICALL
Java_io_slogr_agent_native_SlogrNative_icmpProbe(JNIEnv *env, jobject obj,
    jbyteArray targetIpArr, jint ttl, jint timeoutMs,
    jbyteArray hopIpOut, jintArray metaOut)
{
    /* Decode target IP */
    jbyte *tb = (*env)->GetByteArrayElements(env, targetIpArr, NULL);
    struct in_addr target;
    memcpy(&target.s_addr, tb, 4);
    (*env)->ReleaseByteArrayElements(env, targetIpArr, tb, JNI_ABORT);

    /* Raw socket for send + receive (ICMP) */
    int fd = socket(AF_INET, SOCK_RAW, IPPROTO_ICMP);
    if (fd < 0) {
        write_outputs(env, hopIpOut, metaOut, NULL, 0, -1, -1);
        return -2L;
    }

    /* Set outgoing TTL */
    uint8_t ip_ttl = (uint8_t)ttl;
    if (setsockopt(fd, IPPROTO_IP, IP_TTL, &ip_ttl, sizeof(ip_ttl)) < 0) {
        close(fd);
        write_outputs(env, hopIpOut, metaOut, NULL, 0, -1, -1);
        return -2L;
    }
    set_timeout(fd, timeoutMs);

    /* Build ICMP ECHO REQUEST */
    uint8_t pkt[sizeof(struct icmphdr) + PROBE_DATA_LEN];
    memset(pkt, 0, sizeof(pkt));
    struct icmphdr *icmph = (struct icmphdr *)pkt;
    icmph->type             = ICMP_ECHO_TYPE;
    icmph->code             = 0;
    icmph->un.echo.id       = htons(SLOGR_PROBE_ID);
    icmph->un.echo.sequence = htons((uint16_t)ttl);
    memset(pkt + sizeof(struct icmphdr), (uint8_t)ttl, PROBE_DATA_LEN);
    icmph->checksum = inet_cksum(pkt, sizeof(pkt));

    struct sockaddr_in dst;
    memset(&dst, 0, sizeof(dst));
    dst.sin_family = AF_INET;
    dst.sin_addr   = target;

    long t_send = now_us();
    if (sendto(fd, pkt, sizeof(pkt), 0,
               (struct sockaddr *)&dst, sizeof(dst)) < 0) {
        close(fd);
        write_outputs(env, hopIpOut, metaOut, NULL, 0, -1, -1);
        return -2L;
    }

    /* Receive loop: keep reading until we find our response or timeout */
    uint8_t          rbuf[RECV_BUF_LEN];
    struct sockaddr_in src;
    socklen_t          slen = sizeof(src);

    for (;;) {
        int rv = (int)recvfrom(fd, rbuf, sizeof(rbuf), 0,
                               (struct sockaddr *)&src, &slen);
        if (rv <= 0) {
            /* Timeout or error */
            close(fd);
            write_outputs(env, hopIpOut, metaOut, NULL, 0, -1, -1);
            return -1L;
        }

        long t_recv = now_us();

        /* Skip IP header to reach the outer ICMP header */
        struct ip *iph = (struct ip *)rbuf;
        int iphlen = iph->ip_hl * 4;
        if (rv < iphlen + (int)sizeof(struct icmphdr)) continue;

        struct icmphdr *rh = (struct icmphdr *)(rbuf + iphlen);
        uint8_t rtype = rh->type;
        uint8_t rcode = rh->code;

        if (rtype == ICMP_REPLY_TYPE) {
            /* ECHO REPLY — destination reached */
            if (ntohs(rh->un.echo.id)       == SLOGR_PROBE_ID &&
                ntohs(rh->un.echo.sequence) == (uint16_t)ttl) {
                close(fd);
                write_outputs(env, hopIpOut, metaOut, &src.sin_addr, 1,
                              (int)rtype, (int)rcode);
                return (jlong)(t_recv - t_send);
            }
        } else if (rtype == ICMP_TEXCEED || rtype == ICMP_UNREACH) {
            /*
             * TTL exceeded or unreachable — the payload contains the original
             * IP header + first 8 bytes of the original ICMP (enough for the
             * id/sequence fields).
             */
            int inner_off = iphlen + (int)sizeof(struct icmphdr);
            if (rv < inner_off + (int)(sizeof(struct ip) + 8)) continue;

            struct ip *ih2 = (struct ip *)(rbuf + inner_off);
            if (ih2->ip_p != IPPROTO_ICMP) continue;

            int ih2len = ih2->ip_hl * 4;
            struct icmphdr *ih2c = (struct icmphdr *)((uint8_t *)ih2 + ih2len);

            if (ntohs(ih2c->un.echo.id)       == SLOGR_PROBE_ID &&
                ntohs(ih2c->un.echo.sequence) == (uint16_t)ttl) {
                int reached = (rtype == ICMP_UNREACH) ? 1 : 0;
                close(fd);
                write_outputs(env, hopIpOut, metaOut, &src.sin_addr, reached,
                              (int)rtype, (int)rcode);
                return (jlong)(t_recv - t_send);
            }
        }
        /* Unrelated ICMP packet — keep waiting */
    }
}

/* ── udpProbe ──────────────────────────────────────────────────────────── */

JNIEXPORT jlong JNICALL
Java_io_slogr_agent_native_SlogrNative_udpProbe(JNIEnv *env, jobject obj,
    jbyteArray targetIpArr, jint targetPort, jint ttl, jint timeoutMs,
    jbyteArray hopIpOut, jintArray metaOut)
{
    /* Decode target IP */
    jbyte *tb = (*env)->GetByteArrayElements(env, targetIpArr, NULL);
    struct in_addr target;
    memcpy(&target.s_addr, tb, 4);
    (*env)->ReleaseByteArrayElements(env, targetIpArr, tb, JNI_ABORT);

    /* UDP send socket */
    int send_fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (send_fd < 0) {
        write_outputs(env, hopIpOut, metaOut, NULL, 0, -1, -1);
        return -2L;
    }

    uint8_t ip_ttl = (uint8_t)ttl;
    if (setsockopt(send_fd, IPPROTO_IP, IP_TTL, &ip_ttl, sizeof(ip_ttl)) < 0) {
        close(send_fd);
        write_outputs(env, hopIpOut, metaOut, NULL, 0, -1, -1);
        return -2L;
    }

    /* Raw socket to receive ICMP errors */
    int recv_fd = socket(AF_INET, SOCK_RAW, IPPROTO_ICMP);
    if (recv_fd < 0) {
        close(send_fd);
        write_outputs(env, hopIpOut, metaOut, NULL, 0, -1, -1);
        return -2L;
    }
    set_timeout(recv_fd, timeoutMs);

    /* Send a short UDP datagram */
    struct sockaddr_in dst;
    memset(&dst, 0, sizeof(dst));
    dst.sin_family = AF_INET;
    dst.sin_addr   = target;
    dst.sin_port   = htons((uint16_t)(targetPort > 0 ? targetPort : UDP_PROBE_PORT));

    uint8_t payload[4] = { 0x53, 0x6c, 0x67, 0x72 };  /* "Slgr" */
    long t_send = now_us();
    if (sendto(send_fd, payload, sizeof(payload), 0,
               (struct sockaddr *)&dst, sizeof(dst)) < 0) {
        close(send_fd);
        close(recv_fd);
        write_outputs(env, hopIpOut, metaOut, NULL, 0, -1, -1);
        return -2L;
    }
    close(send_fd);

    /* Receive loop: look for ICMP TIME_EXCEEDED or PORT_UNREACH */
    uint8_t          rbuf[RECV_BUF_LEN];
    struct sockaddr_in src;
    socklen_t          slen = sizeof(src);

    for (;;) {
        int rv = (int)recvfrom(recv_fd, rbuf, sizeof(rbuf), 0,
                               (struct sockaddr *)&src, &slen);
        if (rv <= 0) {
            close(recv_fd);
            write_outputs(env, hopIpOut, metaOut, NULL, 0, -1, -1);
            return -1L;
        }

        long t_recv = now_us();

        struct ip *iph = (struct ip *)rbuf;
        int iphlen = iph->ip_hl * 4;
        if (rv < iphlen + (int)sizeof(struct icmphdr)) continue;

        struct icmphdr *rh = (struct icmphdr *)(rbuf + iphlen);
        uint8_t rtype = rh->type;
        uint8_t rcode = rh->code;

        if (rtype != ICMP_TEXCEED && rtype != ICMP_UNREACH) continue;

        /*
         * Check inner IP header: the destination must match our target.
         * (No sequence number in UDP, so we match by target address.)
         */
        int inner_off = iphlen + (int)sizeof(struct icmphdr);
        if (rv < inner_off + (int)(sizeof(struct ip) + sizeof(struct udphdr))) continue;

        struct ip *ih2 = (struct ip *)(rbuf + inner_off);
        if (ih2->ip_p != IPPROTO_UDP) continue;

        if (memcmp(&ih2->ip_dst.s_addr, &target.s_addr, 4) != 0) continue;

        int reached = (rtype == ICMP_UNREACH && rcode == ICMP_UNREACH_PORT) ? 1 : 0;
        close(recv_fd);
        write_outputs(env, hopIpOut, metaOut, &src.sin_addr, reached,
                      (int)rtype, (int)rcode);
        return (jlong)(t_recv - t_send);
    }
}

/* ── tcpProbe helpers ──────────────────────────────────────────────────── */

/* Plain TCP header struct — no bitfields, portable across endianness. */
typedef struct {
    uint16_t src_port;
    uint16_t dst_port;
    uint32_t seq;
    uint32_t ack_seq;
    uint8_t  data_off;   /* (header_len_in_32bit_words << 4) | reserved */
    uint8_t  flags;      /* FIN|SYN|RST|PSH|ACK|URG */
    uint16_t window;
    uint16_t checksum;
    uint16_t urg_ptr;
} tcp_hdr_t;

#define TCP_FLAG_SYN  0x02u
#define TCP_FLAG_RST  0x04u
#define TCP_FLAG_ACK  0x10u

/* TCP pseudo-header for checksum computation (RFC 793). */
typedef struct {
    uint32_t src_ip;
    uint32_t dst_ip;
    uint8_t  zero;
    uint8_t  proto;
    uint16_t tcp_len;
} tcp_pseudo_t;

static uint16_t tcp_checksum(uint32_t src_ip, uint32_t dst_ip,
                              const tcp_hdr_t *tcp)
{
    tcp_pseudo_t ph;
    ph.src_ip  = src_ip;
    ph.dst_ip  = dst_ip;
    ph.zero    = 0;
    ph.proto   = IPPROTO_TCP;
    ph.tcp_len = htons((uint16_t)sizeof(tcp_hdr_t));

    uint8_t buf[sizeof(tcp_pseudo_t) + sizeof(tcp_hdr_t)];
    memcpy(buf,              &ph,  sizeof(ph));
    memcpy(buf + sizeof(ph), tcp, sizeof(tcp_hdr_t));
    return inet_cksum(buf, sizeof(buf));
}

/*
 * Route a dummy UDP connect to discover the local IP the kernel would
 * use for a packet destined to `target`.  Does not send any data.
 */
static uint32_t get_local_ip_for(const struct in_addr *target)
{
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) return 0;

    struct sockaddr_in dst;
    memset(&dst, 0, sizeof(dst));
    dst.sin_family = AF_INET;
    dst.sin_addr   = *target;
    dst.sin_port   = htons(9);

    if (connect(fd, (struct sockaddr *)&dst, sizeof(dst)) < 0) {
        close(fd); return 0;
    }
    struct sockaddr_in local;
    socklen_t local_len = sizeof(local);
    getsockname(fd, (struct sockaddr *)&local, &local_len);
    close(fd);
    return local.sin_addr.s_addr;
}

/*
 * Send a TCP RST to cleanly abort a half-open connection after receiving
 * a SYN-ACK from the destination.
 */
static void send_tcp_rst(uint32_t src_ip, uint32_t dst_ip,
                         uint16_t src_port, uint16_t dst_port,
                         uint32_t seq_num)
{
    int fd = socket(AF_INET, SOCK_RAW, IPPROTO_RAW);
    if (fd < 0) return;

    int one = 1;
    setsockopt(fd, IPPROTO_IP, IP_HDRINCL, &one, sizeof(one));

    uint8_t pkt[sizeof(struct ip) + sizeof(tcp_hdr_t)];
    memset(pkt, 0, sizeof(pkt));

    struct ip *iph = (struct ip *)pkt;
    iph->ip_hl          = 5;
    iph->ip_v           = 4;
    iph->ip_len         = htons((uint16_t)sizeof(pkt));
    iph->ip_ttl         = 64;
    iph->ip_p           = IPPROTO_TCP;
    iph->ip_src.s_addr  = src_ip;
    iph->ip_dst.s_addr  = dst_ip;

    tcp_hdr_t *tcph = (tcp_hdr_t *)(pkt + sizeof(struct ip));
    tcph->src_port = htons(src_port);
    tcph->dst_port = htons(dst_port);
    tcph->seq      = htonl(seq_num);
    tcph->data_off = (uint8_t)(5u << 4);
    tcph->flags    = TCP_FLAG_RST;
    tcph->window   = 0;
    tcph->checksum = tcp_checksum(src_ip, dst_ip, tcph);

    struct sockaddr_in dst_addr;
    memset(&dst_addr, 0, sizeof(dst_addr));
    dst_addr.sin_family      = AF_INET;
    dst_addr.sin_addr.s_addr = dst_ip;

    sendto(fd, pkt, sizeof(pkt), 0,
           (struct sockaddr *)&dst_addr, sizeof(dst_addr));
    close(fd);
}

/* ── tcpProbe ──────────────────────────────────────────────────────────── */

JNIEXPORT jlong JNICALL
Java_io_slogr_agent_native_SlogrNative_tcpProbe(JNIEnv *env, jobject obj,
    jbyteArray targetIpArr, jint destPort, jint ttl, jint timeoutMs,
    jbyteArray hopIpOut, jintArray metaOut)
{
    /* Decode target IP */
    jbyte *tb = (*env)->GetByteArrayElements(env, targetIpArr, NULL);
    struct in_addr target;
    memcpy(&target.s_addr, tb, 4);
    (*env)->ReleaseByteArrayElements(env, targetIpArr, tb, JNI_ABORT);

    /* Find the local IP the kernel would route through for this target */
    uint32_t local_ip = get_local_ip_for(&target);
    if (local_ip == 0) {
        write_outputs(env, hopIpOut, metaOut, NULL, 0, -1, -1);
        return -2L;
    }

    /* Seed rand() once per process */
    static int rand_seeded = 0;
    if (!rand_seeded) {
        srand((unsigned)(time(NULL) ^ (unsigned long)getpid()));
        rand_seeded = 1;
    }
    uint16_t src_port = (uint16_t)(49152u + (unsigned)(rand() % 16383));
    uint32_t init_seq = (uint32_t)rand();

    /* Send socket: SOCK_RAW + IPPROTO_RAW + IP_HDRINCL for full TTL control */
    int send_fd = socket(AF_INET, SOCK_RAW, IPPROTO_RAW);
    if (send_fd < 0) {
        write_outputs(env, hopIpOut, metaOut, NULL, 0, -1, -1);
        return -2L;
    }
    int one = 1;
    setsockopt(send_fd, IPPROTO_IP, IP_HDRINCL, &one, sizeof(one));

    /* Receive sockets */
    int icmp_fd = socket(AF_INET, SOCK_RAW, IPPROTO_ICMP);
    if (icmp_fd < 0) {
        close(send_fd);
        write_outputs(env, hopIpOut, metaOut, NULL, 0, -1, -1);
        return -2L;
    }
    int tcp_fd = socket(AF_INET, SOCK_RAW, IPPROTO_TCP);
    if (tcp_fd < 0) {
        close(send_fd); close(icmp_fd);
        write_outputs(env, hopIpOut, metaOut, NULL, 0, -1, -1);
        return -2L;
    }

    /* Build IP + TCP SYN packet */
    uint8_t pkt[sizeof(struct ip) + sizeof(tcp_hdr_t)];
    memset(pkt, 0, sizeof(pkt));

    struct ip *iph = (struct ip *)pkt;
    iph->ip_hl         = 5;
    iph->ip_v          = 4;
    iph->ip_len        = htons((uint16_t)sizeof(pkt));
    iph->ip_id         = htons((uint16_t)ttl);
    iph->ip_ttl        = (uint8_t)ttl;
    iph->ip_p          = IPPROTO_TCP;
    iph->ip_src.s_addr = local_ip;
    iph->ip_dst        = target;
    /* ip_sum: kernel fills when IP_HDRINCL is used and ip_sum == 0 */

    tcp_hdr_t *tcph = (tcp_hdr_t *)(pkt + sizeof(struct ip));
    tcph->src_port = htons(src_port);
    tcph->dst_port = htons((uint16_t)destPort);
    tcph->seq      = htonl(init_seq);
    tcph->ack_seq  = 0;
    tcph->data_off = (uint8_t)(5u << 4);  /* 20-byte header, no options */
    tcph->flags    = TCP_FLAG_SYN;
    tcph->window   = htons(1024);
    tcph->checksum = tcp_checksum(local_ip, target.s_addr, tcph);

    struct sockaddr_in dst;
    memset(&dst, 0, sizeof(dst));
    dst.sin_family = AF_INET;
    dst.sin_addr   = target;

    long t_send = now_us();
    if (sendto(send_fd, pkt, sizeof(pkt), 0,
               (struct sockaddr *)&dst, sizeof(dst)) < 0) {
        close(send_fd); close(icmp_fd); close(tcp_fd);
        write_outputs(env, hopIpOut, metaOut, NULL, 0, -1, -1);
        return -2L;
    }
    close(send_fd);

    /* Poll ICMP and TCP receive sockets until response or timeout */
    int nfds = (icmp_fd > tcp_fd ? icmp_fd : tcp_fd) + 1;
    uint8_t rbuf[RECV_BUF_LEN];

    for (;;) {
        long remaining_us = (long)timeoutMs * 1000L - (now_us() - t_send);
        if (remaining_us <= 0) {
            close(icmp_fd); close(tcp_fd);
            write_outputs(env, hopIpOut, metaOut, NULL, 0, -1, -1);
            return -1L;
        }

        struct timeval tv;
        tv.tv_sec  = remaining_us / 1000000L;
        tv.tv_usec = remaining_us % 1000000L;

        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(icmp_fd, &rfds);
        FD_SET(tcp_fd, &rfds);

        int sr = select(nfds, &rfds, NULL, NULL, &tv);
        if (sr <= 0) {
            close(icmp_fd); close(tcp_fd);
            write_outputs(env, hopIpOut, metaOut, NULL, 0, -1, -1);
            return -1L;
        }

        long t_recv = now_us();

        /* ── ICMP socket: Time Exceeded from an intermediate hop ── */
        if (FD_ISSET(icmp_fd, &rfds)) {
            struct sockaddr_in src;
            socklen_t slen = sizeof(src);
            int rv = (int)recvfrom(icmp_fd, rbuf, sizeof(rbuf), 0,
                                   (struct sockaddr *)&src, &slen);
            if (rv > 0) {
                struct ip *ipr = (struct ip *)rbuf;
                int iphlen = ipr->ip_hl * 4;
                if (rv >= iphlen + (int)sizeof(struct icmphdr)) {
                    struct icmphdr *rh = (struct icmphdr *)(rbuf + iphlen);
                    uint8_t rtype = rh->type;
                    uint8_t rcode = rh->code;
                    if (rtype == ICMP_TEXCEED || rtype == ICMP_UNREACH) {
                        /*
                         * Inner IP+TCP must match: dest=target, src_port=our src_port.
                         * RFC 792: ICMP error contains original IP header + first 8 bytes
                         * of original datagram (covers source/dest port).
                         */
                        int inner_off = iphlen + (int)sizeof(struct icmphdr);
                        if (rv >= inner_off + (int)(sizeof(struct ip) + 4)) {
                            struct ip *ih2 = (struct ip *)(rbuf + inner_off);
                            if (ih2->ip_p == IPPROTO_TCP &&
                                memcmp(&ih2->ip_dst.s_addr, &target.s_addr, 4) == 0) {
                                int ih2len = ih2->ip_hl * 4;
                                if (rv >= inner_off + ih2len + 4) {
                                    uint16_t *tcp_ports =
                                        (uint16_t *)((uint8_t *)ih2 + ih2len);
                                    if (ntohs(tcp_ports[0]) == src_port) {
                                        int reached = (rtype == ICMP_UNREACH) ? 1 : 0;
                                        close(icmp_fd); close(tcp_fd);
                                        write_outputs(env, hopIpOut, metaOut,
                                                      &src.sin_addr, reached,
                                                      (int)rtype, (int)rcode);
                                        return (jlong)(t_recv - t_send);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        /* ── TCP socket: SYN-ACK or RST from the destination ── */
        if (FD_ISSET(tcp_fd, &rfds)) {
            struct sockaddr_in src;
            socklen_t slen = sizeof(src);
            int rv = (int)recvfrom(tcp_fd, rbuf, sizeof(rbuf), 0,
                                   (struct sockaddr *)&src, &slen);
            if (rv > 0) {
                struct ip *ipr = (struct ip *)rbuf;
                int iphlen = ipr->ip_hl * 4;
                if (rv >= iphlen + (int)sizeof(tcp_hdr_t)) {
                    tcp_hdr_t *trh = (tcp_hdr_t *)(rbuf + iphlen);
                    if (memcmp(&ipr->ip_src.s_addr, &target.s_addr, 4) == 0 &&
                        ntohs(trh->dst_port) == src_port &&
                        ntohs(trh->src_port) == (uint16_t)destPort) {
                        if (trh->flags & TCP_FLAG_SYN) {
                            /* SYN-ACK: send RST so target doesn't hold half-open state */
                            uint32_t rst_seq = ntohl(trh->ack_seq);
                            send_tcp_rst(local_ip, target.s_addr,
                                         src_port, (uint16_t)destPort, rst_seq);
                        }
                        /* Both SYN-ACK and RST mean destination reached */
                        close(icmp_fd); close(tcp_fd);
                        write_outputs(env, hopIpOut, metaOut, &src.sin_addr, 1, -1, -1);
                        return (jlong)(t_recv - t_send);
                    }
                }
            }
        }
    }
}
