package com.yep.kindle.dron.model;

/**
 * Live scan result for one access point.
 * Populated by WifiScanner and consumed by ThreatScorer and display classes.
 */
public class AP {
    public String mac        = "";
    public String ssid       = "";
    public String mode       = "";
    public String encryption = "Open";
    public int    channel    = 0;
    public int    signalDbm  = -999;
    public boolean hidden    = false;
    public double  dist      = 0;
    public int     distDeltaM = 0; // + = moving away, - = getting closer
    public int     threat    = 0;
    public String  flags     = "";
    public boolean ghost     = false; // true = known-threat not currently visible
}
