package com.vulntriage.ui;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;

/**
 * Applies the Windows DWM dark/light title bar to all windows owned by this
 * process. Safe to call on non-Windows — exits immediately.
 */
public final class WindowsDarkMode {

    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final boolean IS_WIN =
        System.getProperty("os.name", "").toLowerCase().contains("win");

    private interface Dwmapi extends Library {
        Dwmapi INSTANCE = Native.load("dwmapi", Dwmapi.class, W32APIOptions.DEFAULT_OPTIONS);
        int DwmSetWindowAttribute(WinDef.HWND hwnd, int attr,
                                   IntByReference value, int size);
    }

    private WindowsDarkMode() {}

    public static void apply(boolean dark) {
        if (!IS_WIN) return;
        try {
            int pid = (int) ProcessHandle.current().pid();
            User32.INSTANCE.EnumWindows((hwnd, data) -> {
                IntByReference winPid = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hwnd, winPid);
                if (winPid.getValue() == pid) setDark(hwnd, dark);
                return true;
            }, null);
        } catch (Throwable ignored) {}
    }

    private static void setDark(WinDef.HWND hwnd, boolean dark) {
        try {
            Dwmapi.INSTANCE.DwmSetWindowAttribute(
                hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, new IntByReference(dark ? 1 : 0), 4);
        } catch (Throwable ignored) {}
    }
}
