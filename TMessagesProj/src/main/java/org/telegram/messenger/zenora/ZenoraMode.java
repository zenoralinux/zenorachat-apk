package org.telegram.messenger.zenora;

/**
 * The APK is a dedicated ZenoraChat client. Telegram UI components are kept,
 * while authentication and chat data are provided by ZenoraChat's HTTPS API.
 */
public final class ZenoraMode {
    private ZenoraMode() {}
    public static boolean isEnabled() {
        return true;
    }
}
