cat > /mnt/us/run_capture.sh << 'EOF'
#!/bin/sh
LOG=/mnt/us/sniff-nodot11.txt
echo "=== $(date) ===" > $LOG

ifconfig wlan0 down
sleep 1
rmmod ar6003
sleep 1
insmod /mnt/us/ar6003.ko promiscuous=1
#insmod /mnt/us/ar6003.ko promiscuous=1 processDot11Hdr=1
sleep 3
ifconfig wlan0 up
sleep 2

echo "--- dmesg ---" >> $LOG
dmesg | tail -10 >> $LOG

echo "--- sniffing 20s ---" >> $LOG
/mnt/us/sniffer 200 >> $LOG 2>&1

echo "--- wmiconfig stats ---" >> $LOG
wmiconfig -i wlan0 --getTargetStats >> $LOG 2>&1

echo "=== Done ===" >> $LOG
reboot
EOF