#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <net/if.h>
#include <linux/if_packet.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <time.h>

static void print_mac(const unsigned char *mac) {
    printf("%02x:%02x:%02x:%02x:%02x:%02x",
        mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
}

static unsigned short le16(const unsigned char *p) {
    return (unsigned short)(p[0] | (p[1] << 8));
}

static const char *pkttype_name(int pkttype) {
    switch (pkttype) {
        case PACKET_HOST: return "HOST";
        case PACKET_BROADCAST: return "BCAST";
        case PACKET_MULTICAST: return "MCAST";
        case PACKET_OTHERHOST: return "OTHERHOST";
        case PACKET_OUTGOING: return "OUT";
        default: return "UNKNOWN";
    }
}

static const char *fc_type_name(unsigned type, unsigned subtype) {
    if (type == 0) {
        static const char *mgmt[] = {
            "assoc-req", "assoc-resp", "reassoc-req", "reassoc-resp",
            "probe-req", "probe-resp", "timing-adv", "reserved",
            "beacon", "atim", "disassoc", "auth",
            "deauth", "action", "action-noack", "reserved"
        };
        return mgmt[subtype & 0x0f];
    }
    if (type == 1) {
        static const char *ctrl[] = {
            "reserved", "reserved", "trigger", "tack",
            "beam-report-poll", "vht/ndp-ann", "ctrl-ext", "ctrl-wrap",
            "bar", "ba", "ps-poll", "rts",
            "cts", "ack", "cf-end", "cf-end-ack"
        };
        return ctrl[subtype & 0x0f];
    }
    if (type == 2) {
        static const char *data[] = {
            "data", "data-cf-ack", "data-cf-poll", "data-cf-ack-poll",
            "null", "cf-ack", "cf-poll", "cf-ack-poll",
            "qos-data", "qos-data-cf-ack", "qos-data-cf-poll", "qos-data-cf-ack-poll",
            "qos-null", "reserved", "qos-cf-poll", "qos-cf-ack-poll"
        };
        return data[subtype & 0x0f];
    }
    return "reserved";
}

static void dump_hex(const unsigned char *buf, int len, int max_bytes) {
    int i;
    int shown = len < max_bytes ? len : max_bytes;

    printf("    hex:");
    for (i = 0; i < shown; i++) {
        if ((i % 16) == 0) {
            printf("\n    %04x:", i);
        }
        printf(" %02x", buf[i]);
    }
    if (shown < len) {
        printf("\n    ... (%d bytes total)", len);
    }
    printf("\n");
}

static void print_ssid(const unsigned char *ssid, int len) {
    int i;
    putchar('"');
    for (i = 0; i < len; i++) {
        unsigned char c = ssid[i];
        if (c >= 32 && c <= 126) {
            putchar(c);
        } else {
            printf("\\x%02x", c);
        }
    }
    putchar('"');
}

static void dump_ies(const unsigned char *buf, int len) {
    int pos = 0;
    int found = 0;

    while (pos + 2 <= len) {
        int id = buf[pos];
        int ie_len = buf[pos + 1];
        const unsigned char *data = buf + pos + 2;

        if (pos + 2 + ie_len > len) {
            printf("    ie: truncated id=%d len=%d\n", id, ie_len);
            return;
        }

        if (id == 0) {
            printf("    ssid=");
            print_ssid(data, ie_len);
            printf("\n");
            found = 1;
        } else if (id == 3 && ie_len >= 1) {
            printf("    channel=%u\n", data[0]);
            found = 1;
        } else if (id == 48) {
            printf("    rsn_ie_len=%d\n", ie_len);
            found = 1;
        } else if (id == 221 && ie_len >= 3) {
            printf("    vendor_oui=%02x:%02x:%02x len=%d\n", data[0], data[1], data[2], ie_len);
            found = 1;
        }

        pos += 2 + ie_len;
    }

    if (!found) {
        printf("    ie: none parsed\n");
    }
}

static int looks_like_80211(const unsigned char *buf, int len) {
    unsigned short fc;
    unsigned version;
    unsigned type;

    if (len < 10) {
        return 0;
    }

    fc = le16(buf);
    version = fc & 0x3;
    type = (fc >> 2) & 0x3;

    if (version != 0 || type == 3) {
        return 0;
    }

    return 1;
}

static void decode_80211(const unsigned char *buf, int len) {
    unsigned short fc = le16(buf);
    unsigned short seq;
    unsigned type = (fc >> 2) & 0x3;
    unsigned subtype = (fc >> 4) & 0x0f;
    unsigned to_ds = (fc >> 8) & 0x1;
    unsigned from_ds = (fc >> 9) & 0x1;
    int hdr_len = 24;

    if (type == 1) {
        if ((subtype & 0x0c) == 0x0c) {
            hdr_len = 10;
        } else {
            hdr_len = 16;
        }
    } else if (type == 2) {
        hdr_len = (to_ds && from_ds) ? 30 : 24;
        if ((subtype & 0x08) && len >= hdr_len + 2) {
            hdr_len += 2;
        }
    }

    printf("    802.11 fc=0x%04x type=%u(%s) subtype=%u(%s) toDS=%u fromDS=%u",
        fc, type, type == 0 ? "mgmt" : (type == 1 ? "ctrl" : "data"),
        subtype, fc_type_name(type, subtype), to_ds, from_ds);

    if (len >= 24 && type != 1) {
        seq = le16(buf + 22);
        printf(" seq=%u frag=%u", seq >> 4, seq & 0x0f);
    }
    printf("\n");

    if (len >= 10) {
        printf("    addr1=");
        print_mac(buf + 4);
        printf("\n");
    }
    if (len >= 16 && type != 1) {
        printf("    addr2=");
        print_mac(buf + 10);
        printf("\n");
    }
    if (len >= 22 && type != 1) {
        printf("    addr3=");
        print_mac(buf + 16);
        printf("\n");
    }
    if (len >= 30 && type == 2 && to_ds && from_ds) {
        printf("    addr4=");
        print_mac(buf + 24);
        printf("\n");
    }

    if (type == 0 && (subtype == 4 || subtype == 5 || subtype == 8)) {
        int body = 24;
        if (subtype == 5 || subtype == 8) {
            body += 12;
        }
        if (len > body) {
            dump_ies(buf + body, len - body);
        }
    }
}

static void decode_ethernet(const unsigned char *buf, int len) {
    if (len < 14) {
        printf("    ethernet: short frame\n");
        return;
    }

    printf("    eth ");
    print_mac(buf + 6);
    printf(" -> ");
    print_mac(buf);
    printf(" type=0x%02x%02x\n", buf[12], buf[13]);
}

int main(int argc, char *argv[]) {
    int sock, n, count = 0;
    unsigned char buf[2048];
    struct ifreq ifr;
    struct sockaddr_ll sa;
    struct sockaddr_ll from;
    socklen_t fromlen;
    time_t start = time(NULL);
    int duration = argc > 1 ? atoi(argv[1]) : 30;

    sock = socket(AF_PACKET, SOCK_RAW, htons(0x0003)); /* ETH_P_ALL */
    if (sock < 0) { perror("socket"); return 1; }

    strncpy(ifr.ifr_name, "wlan0", IFNAMSIZ);
    ioctl(sock, SIOCGIFINDEX, &ifr);

    memset(&sa, 0, sizeof(sa));
    sa.sll_family   = AF_PACKET;
    sa.sll_ifindex  = ifr.ifr_ifindex;
    sa.sll_protocol = htons(0x0003);
    bind(sock, (struct sockaddr *)&sa, sizeof(sa));

    /* also set IFF_PROMISC on the interface */
    ioctl(sock, SIOCGIFFLAGS, &ifr);
    ifr.ifr_flags |= IFF_PROMISC;
    ioctl(sock, SIOCSIFFLAGS, &ifr);

    printf("Sniffing wlan0 for %d seconds...\n", duration);
    while (time(NULL) - start < duration) {
        memset(&from, 0, sizeof(from));
        fromlen = sizeof(from);
        n = recvfrom(sock, buf, sizeof(buf), 0, (struct sockaddr *)&from, &fromlen);
        if (n <= 0) continue;
        count++;
        printf("[%4d] len=%d pkttype=%s ifindex=%d hatype=0x%x protocol=0x%04x halen=%d\n",
            count, n, pkttype_name(from.sll_pkttype), from.sll_ifindex,
            from.sll_hatype, ntohs(from.sll_protocol), from.sll_halen);

        if (looks_like_80211(buf, n)) {
            decode_80211(buf, n);
        } else {
            decode_ethernet(buf, n);
        }

        dump_hex(buf, n, 64);
        fflush(stdout);
    }
    printf("Total frames: %d\n", count);
    return 0;
}
