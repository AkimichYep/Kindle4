#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/i2c-dev.h>
#include <linux/i2c.h>

#define ADDR               0x06
#define REG_FLOAT_VOLTAGE  0x03   /* bits[5:0]: 3.50V + n*20mV */
#define REG_CHARGE_CTRL    0x05   /* bit6: USB_SUSPEND          */

/* Usage:
 *   smb347 [/dev/i2c/N] <command>
 * Bus path is optional; default probes /dev/i2c-1, /dev/i2c1, /dev/i2c/1.
 * Example: smb347 /dev/i2c0 scan
 */

static int open_bus(const char *path) {
    int fd = open(path, O_RDWR);
    if (fd < 0) { fprintf(stderr, "open %s: %s\n", path, strerror(errno)); }
    else { fprintf(stderr, "bus: %s\n", path); }
    return fd;
}

static int probe_bus() {
    const char *paths[] = {"/dev/i2c-1", "/dev/i2c1", "/dev/i2c/1", NULL};
    for (int i = 0; paths[i]; i++) {
        int fd = open(paths[i], O_RDWR);
        if (fd >= 0) { fprintf(stderr, "bus: %s\n", paths[i]); return fd; }
    }
    fprintf(stderr, "no i2c bus found\n"); return -1;
}

static unsigned char rdwr_read(int fd, unsigned char addr, unsigned char reg) {
    unsigned char wbuf[1] = { reg };
    unsigned char rbuf[1] = { 0 };
    struct i2c_msg msgs[2];
    msgs[0].addr = addr; msgs[0].flags = 0;        msgs[0].len = 1; msgs[0].buf = wbuf;
    msgs[1].addr = addr; msgs[1].flags = I2C_M_RD; msgs[1].len = 1; msgs[1].buf = rbuf;
    struct i2c_rdwr_ioctl_data req; req.msgs = msgs; req.nmsgs = 2;
    if (ioctl(fd, I2C_RDWR, &req) < 0) {
        fprintf(stderr, "read reg 0x%02x @0x%02x: %s\n", reg, addr, strerror(errno));
        return 0xFF;
    }
    return rbuf[0];
}

static void rdwr_write(int fd, unsigned char addr, unsigned char reg, unsigned char val) {
    unsigned char buf[2] = { reg, val };
    struct i2c_msg msg; msg.addr = addr; msg.flags = 0; msg.len = 2; msg.buf = buf;
    struct i2c_rdwr_ioctl_data req; req.msgs = &msg; req.nmsgs = 1;
    if (ioctl(fd, I2C_RDWR, &req) < 0)
        fprintf(stderr, "write reg 0x%02x @0x%02x: %s\n", reg, addr, strerror(errno));
}

/* Dump all 14 SMB347 config registers (0x00-0x0D) + status regs (0x35-0x3F) */
static void dump_all(int fd, unsigned char addr) {
    printf("--- config registers (0x00-0x0D) ---\n");
    for (int r = 0x00; r <= 0x0D; r++) {
        unsigned char v = rdwr_read(fd, addr, r);
        printf("  reg[0x%02x] = 0x%02x\n", r, v);
    }
    printf("--- status registers (0x35-0x3F) ---\n");
    for (int r = 0x35; r <= 0x3F; r++) {
        unsigned char v = rdwr_read(fd, addr, r);
        printf("  reg[0x%02x] = 0x%02x\n", r, v);
    }
}

static void print_status(int fd) {
    unsigned char fv = rdwr_read(fd, ADDR, REG_FLOAT_VOLTAGE);
    unsigned char cc = rdwr_read(fd, ADDR, REG_CHARGE_CTRL);
    printf("float_voltage=%.2fV  reg=0x%02x\n", 3.50 + (fv & 0x3F) * 0.02, fv);
    printf("charge_ctrl=0x%02x  suspend=%d\n", cc, (cc >> 6) & 1);
}

static void do_scan(int fd) {
    printf("Scanning...\n");
    for (int addr = 1; addr <= 0x7F; addr++) {
        unsigned char buf[1] = { 0 };
        struct i2c_msg msg; msg.addr = addr; msg.flags = I2C_M_RD; msg.len = 1; msg.buf = buf;
        struct i2c_rdwr_ioctl_data req; req.msgs = &msg; req.nmsgs = 1;
        if (ioctl(fd, I2C_RDWR, &req) >= 0)
            printf("  0x%02x ACK  byte=0x%02x\n", addr, buf[0]);
    }
    printf("done.\n");
}

int main(int argc, char **argv) {
    int argoff = 1;
    int fd = -1;

    /* Optional bus path as first argument */
    if (argc >= 2 && argv[1][0] == '/') {
        fd = open_bus(argv[1]);
        argoff = 2;
    } else {
        fd = probe_bus();
    }
    if (fd < 0) return 1;

    const char *cmd = argc > argoff ? argv[argoff] : "status";

    if (!strcmp(cmd, "status")) {
        print_status(fd);

    } else if (!strcmp(cmd, "dump")) {
        unsigned char addr = ADDR;
        if (argc > argoff + 1) { unsigned int a; sscanf(argv[argoff+1], "%x", &a); addr = a; }
        dump_all(fd, addr);

    } else if (!strcmp(cmd, "limit")) {
        unsigned char fv = rdwr_read(fd, ADDR, REG_FLOAT_VOLTAGE);
        printf("before: 0x%02x  %.2fV\n", fv, 3.50 + (fv & 0x3F) * 0.02);
        rdwr_write(fd, ADDR, REG_FLOAT_VOLTAGE, (fv & 0xC0) | 30); /* 4.10V */
        fv = rdwr_read(fd, ADDR, REG_FLOAT_VOLTAGE);
        printf("after:  0x%02x  %.2fV\n", fv, 3.50 + (fv & 0x3F) * 0.02);

    } else if (!strcmp(cmd, "default")) {
        unsigned char fv = rdwr_read(fd, ADDR, REG_FLOAT_VOLTAGE);
        rdwr_write(fd, ADDR, REG_FLOAT_VOLTAGE, (fv & 0xC0) | 35); /* 4.20V */
        fv = rdwr_read(fd, ADDR, REG_FLOAT_VOLTAGE);
        printf("after:  0x%02x  %.2fV\n", fv, 3.50 + (fv & 0x3F) * 0.02);

    } else if (!strcmp(cmd, "suspend")) {
        unsigned char cc = rdwr_read(fd, ADDR, REG_CHARGE_CTRL);
        rdwr_write(fd, ADDR, REG_CHARGE_CTRL, cc | 0x40);
        printf("suspended  ctrl=0x%02x\n", rdwr_read(fd, ADDR, REG_CHARGE_CTRL));

    } else if (!strcmp(cmd, "resume")) {
        unsigned char cc = rdwr_read(fd, ADDR, REG_CHARGE_CTRL);
        rdwr_write(fd, ADDR, REG_CHARGE_CTRL, cc & ~0x40);
        printf("resumed  ctrl=0x%02x\n", rdwr_read(fd, ADDR, REG_CHARGE_CTRL));

    } else if (!strcmp(cmd, "scan")) {
        do_scan(fd);

    } else {
        fprintf(stderr,
            "usage: smb347 [/dev/i2c/N] <command>\n"
            "  status      — float voltage + charge control\n"
            "  dump [addr] — all registers (default addr=0x06)\n"
            "  limit       — cap battery at ~85%% (4.10V)\n"
            "  default     — restore 4.20V\n"
            "  suspend     — stop charging\n"
            "  resume      — resume charging\n"
            "  scan        — probe all I2C addresses\n");
        close(fd); return 1;
    }

    close(fd); return 0;
}
