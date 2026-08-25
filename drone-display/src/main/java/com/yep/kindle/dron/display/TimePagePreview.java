package com.yep.kindle.dron.display;

import com.yep.kindle.dron.model.DetectorContext;
import com.yep.kindle.dron.util.AppLog;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;

/**
 * Preview tool for the new Time & Day of Week transition screen.
 * Shows preview for all 7 days of the week to inspect quotes and ASCII art.
 */
public class TimePagePreview {

    public static void main(String[] args) {
        AppLog.init(System.getProperty("kindle.log.file", "time-page-preview.log"), 256 * 1024L);
        DetectorContext ctx = new DetectorContext(
                10, 6, 3, "Kharkiv",
                null, null, new LinkedHashMap<>(), new HashSet<>()
        );

        AppLog.info("===============================================================================");
        AppLog.info("                 NEW TIME & DAY OF WEEK SCREEN PREVIEW                         ");
        AppLog.info("===============================================================================");

        AppLog.info("\n>>> CURRENT MOMENT TIME SCREEN (buildTimeScreen) <<<");
        printScreen(ScreenBuilder.buildTimeScreen(ctx));

        String[] days = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
        for (int i = 0; i < 7; i++) {
            AppLog.info("\n============================= [ DAY: " + days[i].toUpperCase() + " ] =============================");
            printScreen(ScreenBuilder.buildTimeScreenForDay(ctx, i + 1));
        }
    }

    private static void printScreen(String[] screen) {
        AppLog.info("   +--------------------------------------------------+ (Col 1 to 50)");
        for (int row = 0; row < screen.length; row++) {
            String line = screen[row] != null ? screen[row] : "";
            if (line.length() < 50) {
                StringBuilder sb = new StringBuilder(line);
                while (sb.length() < 50) sb.append(' ');
                line = sb.toString();
            } else if (line.length() > 50) {
                line = line.substring(0, 50);
            }
            AppLog.info(String.format("%02d |%s|", row + 1, line));
        }
        AppLog.info("   +--------------------------------------------------+");
    }
}
