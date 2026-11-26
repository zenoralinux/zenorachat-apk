package org.telegram.messenger;

/** Standalone JDK-only regression tests; invoked by the Android build workflow. */
public final class ZenoraMediaPreviewUrlTest {
    private static int assertions;

    private ZenoraMediaPreviewUrlTest() {
    }

    public static void main(String[] args) {
        String valid = "https://miladalizadw.ir/telegram/uploads/thumbnails/chat_list_abc.jpg";

        equals(valid,
                ZenoraMediaPreviewUrl.resolvePublicUrl("/telegram/uploads/thumbnails/chat_list_abc.jpg"),
                "app-root-relative upload URL must not duplicate /telegram");
        equals(valid,
                ZenoraMediaPreviewUrl.resolvePublicUrl("uploads/thumbnails/chat_list_abc.jpg"),
                "ordinary relative upload URL must resolve beneath the app base");
        equals(valid,
                ZenoraMediaPreviewUrl.resolvePublicUrl(valid),
                "absolute public URL must remain unchanged");
        equals("", ZenoraMediaPreviewUrl.resolvePublicUrl(null), "null URL must be empty");
        equals("", ZenoraMediaPreviewUrl.resolvePublicUrl("  "), "blank URL must be empty");
        equals("", ZenoraMediaPreviewUrl.resolvePublicUrl("//evil.example/image.jpg"),
                "protocol-relative URLs must be rejected");
        equals("", ZenoraMediaPreviewUrl.resolvePublicUrl("https://miladalizadw.ir/image.jpg" + (char) 10),
                "control characters must be rejected");

        check(ZenoraMediaPreviewUrl.isAllowedChatListThumbnailUrl(valid),
                "the dedicated app uploads thumbnail URL must be accepted");
        check(!ZenoraMediaPreviewUrl.isAllowedChatListThumbnailUrl(
                        "https://miladalizadw.ir/telegram/telegram/uploads/thumbnails/chat_list_abc.jpg"),
                "duplicated app path must be rejected");
        check(!ZenoraMediaPreviewUrl.isAllowedChatListThumbnailUrl(
                        "https://miladalizadw.ir/uploads/thumbnails/chat_list_abc.jpg"),
                "uploads outside the app subdirectory must be rejected");
        check(!ZenoraMediaPreviewUrl.isAllowedChatListThumbnailUrl(
                        "http://miladalizadw.ir/telegram/uploads/thumbnails/chat_list_abc.jpg"),
                "non-TLS thumbnail URLs must be rejected");
        check(!ZenoraMediaPreviewUrl.isAllowedChatListThumbnailUrl(
                        "https://evil.example/telegram/uploads/thumbnails/chat_list_abc.jpg"),
                "foreign hosts must be rejected");
        check(!ZenoraMediaPreviewUrl.isAllowedChatListThumbnailUrl(
                        "https://user@miladalizadw.ir/telegram/uploads/thumbnails/chat_list_abc.jpg"),
                "userinfo URLs must be rejected");
        check(!ZenoraMediaPreviewUrl.isAllowedChatListThumbnailUrl(
                        "https://miladalizadw.ir:444/telegram/uploads/thumbnails/chat_list_abc.jpg"),
                "non-standard ports must be rejected");
        check(!ZenoraMediaPreviewUrl.isAllowedChatListThumbnailUrl(
                        "https://miladalizadw.ir/telegram/uploads/../private.jpg"),
                "path traversal must be rejected");

        System.out.println("Zenora media preview URL tests passed (" + assertions + " assertions).");
    }

    private static void equals(String expected, String actual, String message) {
        assertions++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
