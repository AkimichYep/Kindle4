package com.yep.kindle.dron.display;

import com.yep.kindle.dron.model.DetectorContext;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;

/**
 * Preview tool for the new Time & Day of Week transition screen.
 * Shows preview for all 7 days of the week to inspect quotes and ASCII art.
 */
public class TimePagePreview {

    public static void main(String[] args) {
        DetectorContext ctx = new DetectorContext(
                10, 6, 3, "Kharkiv",
                null, null, new LinkedHashMap<>(), new HashSet<>()
        );

        System.out.println("===============================================================================");
        System.out.println("                 NEW TIME & DAY OF WEEK SCREEN PREVIEW                         ");
        System.out.println("===============================================================================");

        System.out.println("\n>>> CURRENT MOMENT TIME SCREEN (buildTimeScreen) <<<");
        printScreen(ScreenBuilder.buildTimeScreen(ctx));

        String[] days = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
        for (int i = 0; i < 7; i++) {
            System.out.println("\n============================= [ DAY: " + days[i].toUpperCase() + " ] =============================");
            printScreen(ScreenBuilder.buildTimeScreenForDay(ctx, i + 1));
        }
    }

    private static void printScreen(String[] screen) {
        System.out.println("   +--------------------------------------------------+ (Col 1 to 50)");
        for (int row = 0; row < screen.length; row++) {
            String line = screen[row] != null ? screen[row] : "";
            if (line.length() < 50) {
                StringBuilder sb = new StringBuilder(line);
                while (sb.length() < 50) sb.append(' ');
                line = sb.toString();
            } else if (line.length() > 50) {
                line = line.substring(0, 50);
            }
            System.out.printf("%02d |%s|\n", row + 1, line);
        }
        System.out.println("   +--------------------------------------------------+");
    }
}
