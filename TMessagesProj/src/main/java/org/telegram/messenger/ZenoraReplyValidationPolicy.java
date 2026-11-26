package org.telegram.messenger;

/** Pure policy for the deliberately narrow Zenora text-reply capability. */
public final class ZenoraReplyValidationPolicy {
    public static final String PASS = "PASS";
    public static final String PARENT_MISSING = "PARENT_MISSING";
    public static final String PARENT_ID_INVALID = "PARENT_ID_INVALID";
    public static final String PARENT_PENDING_LOCAL_ID = "PARENT_PENDING_LOCAL_ID";
    public static final String PARENT_ID_MISMATCH = "PARENT_ID_MISMATCH";
    public static final String PARENT_DIALOG_MISMATCH = "PARENT_DIALOG_MISMATCH";
    public static final String PARENT_ROOM_MISMATCH = "PARENT_ROOM_MISMATCH";
    public static final String PARENT_MEDIA_UNSUPPORTED = "PARENT_MEDIA_UNSUPPORTED";
    public static final String PARENT_TEXT_EMPTY = "PARENT_TEXT_EMPTY";
    public static final String PARENT_SERVICE = "PARENT_SERVICE";
    public static final String PARENT_EPHEMERAL = "PARENT_EPHEMERAL";
    public static final String PARENT_TOPIC = "PARENT_TOPIC";
    public static final String PARENT_QUOTE = "PARENT_QUOTE";
    public static final String FORWARDING_UNSUPPORTED = "FORWARDING_UNSUPPORTED";
    public static final String SCHEDULE_UNSUPPORTED = "SCHEDULE_UNSUPPORTED";
    public static final String EDITING_UNSUPPORTED = "EDITING_UNSUPPORTED";

    public static final class Input {
        public boolean parentExists;
        public long ownerId;
        public long objectId;
        public long parentDialogId;
        public long currentDialogId;
        public long parentRoomId;
        public long currentRoomId;
        public boolean mediaIsTextCompatible;
        public boolean textPresent;
        public boolean service;
        public boolean ephemeral;
        public boolean topic;
        public boolean quote;
        public boolean forwarding;
        public boolean scheduled;
        public boolean editing;
    }

    public static final class Result {
        public final boolean accepted;
        public final String reasonCode;
        public final long replyToId;

        private Result(boolean accepted, String reasonCode, long replyToId) {
            this.accepted = accepted;
            this.reasonCode = reasonCode;
            this.replyToId = replyToId;
        }
    }

    private ZenoraReplyValidationPolicy() { }

    public static Result validate(Input input) {
        if (input == null || !input.parentExists) return reject(PARENT_MISSING);
        if (input.ownerId == 0 || input.objectId == 0) return reject(PARENT_ID_INVALID);
        if (input.ownerId < 0 || input.objectId < 0) return reject(PARENT_PENDING_LOCAL_ID);
        if (input.ownerId != input.objectId) return reject(PARENT_ID_MISMATCH);
        if (input.parentDialogId == 0 || input.currentDialogId == 0
                || input.parentDialogId != input.currentDialogId) return reject(PARENT_DIALOG_MISMATCH);
        if (input.parentRoomId <= 0 || input.currentRoomId <= 0
                || input.parentRoomId != input.currentRoomId) return reject(PARENT_ROOM_MISMATCH);
        if (!input.mediaIsTextCompatible) return reject(PARENT_MEDIA_UNSUPPORTED);
        if (!input.textPresent) return reject(PARENT_TEXT_EMPTY);
        if (input.service) return reject(PARENT_SERVICE);
        if (input.ephemeral) return reject(PARENT_EPHEMERAL);
        if (input.topic) return reject(PARENT_TOPIC);
        if (input.quote) return reject(PARENT_QUOTE);
        if (input.forwarding) return reject(FORWARDING_UNSUPPORTED);
        if (input.scheduled) return reject(SCHEDULE_UNSUPPORTED);
        if (input.editing) return reject(EDITING_UNSUPPORTED);
        return new Result(true, PASS, input.ownerId);
    }

    private static Result reject(String reason) {
        return new Result(false, reason, 0L);
    }
}
