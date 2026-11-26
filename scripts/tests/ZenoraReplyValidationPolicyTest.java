package org.telegram.messenger;

public final class ZenoraReplyValidationPolicyTest {
    private static ZenoraReplyValidationPolicy.Input base() {
        ZenoraReplyValidationPolicy.Input input = new ZenoraReplyValidationPolicy.Input();
        input.parentExists = true;
        input.ownerId = 100;
        input.objectId = 100;
        input.parentDialogId = 55;
        input.currentDialogId = 55;
        input.parentRoomId = 7;
        input.currentRoomId = 7;
        input.mediaIsTextCompatible = true;
        input.textPresent = true;
        return input;
    }

    private static void expect(String reason, ZenoraReplyValidationPolicy.Input input) {
        ZenoraReplyValidationPolicy.Result result = ZenoraReplyValidationPolicy.validate(input);
        if (!reason.equals(result.reasonCode)) {
            throw new AssertionError("Expected " + reason + " but got " + result.reasonCode);
        }
    }

    private static void accepted(ZenoraReplyValidationPolicy.Input input, String label) {
        ZenoraReplyValidationPolicy.Result result = ZenoraReplyValidationPolicy.validate(input);
        if (!result.accepted || !ZenoraReplyValidationPolicy.PASS.equals(result.reasonCode)
                || result.replyToId != 100) throw new AssertionError(label + " was rejected: " + result.reasonCode);
    }

    public static void main(String[] args) {
        ZenoraReplyValidationPolicy.Input value = base();
        accepted(value, "media=null or TL_messageMediaEmpty text owner (media is text compatible)");
        value = base(); value.ownerId = -2; value.objectId = -2; expect(ZenoraReplyValidationPolicy.PARENT_PENDING_LOCAL_ID, value);
        value = base(); value.ownerId = 0; expect(ZenoraReplyValidationPolicy.PARENT_ID_INVALID, value);
        value = base(); value.objectId = 101; expect(ZenoraReplyValidationPolicy.PARENT_ID_MISMATCH, value);
        value = base(); value.parentDialogId = 56; expect(ZenoraReplyValidationPolicy.PARENT_DIALOG_MISMATCH, value);
        value = base(); value.parentRoomId = 8; expect(ZenoraReplyValidationPolicy.PARENT_ROOM_MISMATCH, value);
        value = base(); value.mediaIsTextCompatible = false; expect(ZenoraReplyValidationPolicy.PARENT_MEDIA_UNSUPPORTED, value);
        value = base(); value.textPresent = false; expect(ZenoraReplyValidationPolicy.PARENT_TEXT_EMPTY, value);
        value = base(); value.service = true; expect(ZenoraReplyValidationPolicy.PARENT_SERVICE, value);
        value = base(); value.ephemeral = true; expect(ZenoraReplyValidationPolicy.PARENT_EPHEMERAL, value);
        value = base(); value.topic = true; expect(ZenoraReplyValidationPolicy.PARENT_TOPIC, value);
        value = base(); value.quote = true; expect(ZenoraReplyValidationPolicy.PARENT_QUOTE, value);
        value = base(); value.forwarding = true; expect(ZenoraReplyValidationPolicy.FORWARDING_UNSUPPORTED, value);
        value = base(); value.scheduled = true; expect(ZenoraReplyValidationPolicy.SCHEDULE_UNSUPPORTED, value);
        value = base(); value.editing = true; expect(ZenoraReplyValidationPolicy.EDITING_UNSUPPORTED, value);
        value = base(); value.parentExists = false; expect(ZenoraReplyValidationPolicy.PARENT_MISSING, value);
        // from_id is deliberately not a policy input: present, absent, sender=self or peer all pass.
        // A parent which itself has an ordinary reply header also remains eligible.
        accepted(base(), "self-authored ordinary text reply parent");
        accepted(base(), "peer-authored ordinary text reply parent");
        System.out.println("Zenora reply validation policy tests passed");
    }
}
