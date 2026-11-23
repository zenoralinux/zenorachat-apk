package org.telegram.messenger;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;

/**
 * Resolves public media URLs returned by the Zenora PHP API.
 *
 * upload_public_url() returns an app-root-relative path (for example
 * /telegram/uploads/...). Resolving that path against SITE_BASE duplicates
 * /telegram, so root-relative paths resolve against the origin while ordinary
 * relative paths resolve against the app base.
 */
public final class ZenoraMediaPreviewUrl {
    public static final String SITE_BASE = "https://miladalizadw.ir/telegram/";

    private ZenoraMediaPreviewUrl() {
    }

    public static String resolvePublicUrl(String rawPath) {
        if (rawPath == null || containsUnsafeCharacters(rawPath)) {
            return "";
        }
        String value = rawPath.trim();
        if (value.isEmpty() || value.startsWith("//")) {
            return "";
        }

        try {
            URL base = new URL(SITE_BASE);
            URL resolved = new URL(base, value);
            String protocol = resolved.getProtocol().toLowerCase(Locale.US);
            if (!"https".equals(protocol) && !"http".equals(protocol)) {
                return "";
            }
            if (resolved.getHost() == null || resolved.getHost().isEmpty()
                    || resolved.getUserInfo() != null) {
                return "";
            }
            return resolved.toExternalForm();
        } catch (MalformedURLException error) {
            return "";
        }
    }

    /**
     * Compact chat-list thumbnails must remain on this app's public uploads
     * path. In particular, /telegram/telegram/uploads/... is invalid.
     */
    public static boolean isAllowedChatListThumbnailUrl(String value) {
        if (value == null || value.trim().isEmpty() || containsUnsafeCharacters(value)) {
            return false;
        }
        try {
            URL base = new URL(SITE_BASE);
            URL image = new URL(value.trim());
            if (!"https".equalsIgnoreCase(image.getProtocol())
                    || !base.getHost().equalsIgnoreCase(image.getHost())
                    || image.getUserInfo() != null
                    || (image.getPort() != -1 && image.getPort() != 443)
                    || image.getRef() != null) {
                return false;
            }

            String appPath = base.getPath();
            if (!appPath.endsWith("/")) {
                appPath += "/";
            }
            String expectedPrefix = appPath + "uploads/";
            String imagePath = image.getPath();
            if (imagePath == null || !imagePath.startsWith(expectedPrefix)
                    || imagePath.contains("//") || imagePath.contains("/../")
                    || imagePath.endsWith("/..") || imagePath.contains("/./")
                    || imagePath.endsWith("/.") || imagePath.indexOf(92) >= 0) {
                return false;
            }
            return true;
        } catch (MalformedURLException error) {
            return false;
        }
    }

    private static boolean containsUnsafeCharacters(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isISOControl(character) || Character.isWhitespace(character)
                    || character == 92) {
                return true;
            }
        }
        return false;
    }
}
