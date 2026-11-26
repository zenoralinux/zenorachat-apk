package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

/** Adapts real Telegram MessageObjects to the testable Zenora reply policy. */
public final class ZenoraReplyValidator {
    private ZenoraReplyValidator() { }

    public static final class Result {
        public final boolean accepted;
        public final String reasonCode;
        public final long replyToId;
        public final MessageObject parent;

        private Result(boolean accepted, String reasonCode, long replyToId, MessageObject parent) {
            this.accepted = accepted;
            this.reasonCode = reasonCode;
            this.replyToId = replyToId;
            this.parent = parent;
        }
    }

    public static Result validate(int account, MessageObject parent, long currentDialogId,
                                  long currentRoomId, boolean quoteSelected,
                                  MessageObject topicParent, boolean forwarding,
                                  boolean scheduled, boolean editing) {
        ZenoraReplyValidationPolicy.Input input = new ZenoraReplyValidationPolicy.Input();
        TLRPC.Message owner = parent == null ? null : parent.messageOwner;
        long objectId = parent == null ? 0L : parent.getId();
        long parentDialogId = parent == null ? 0L : parent.getDialogId();
        long parentRoomId = parent == null ? 0L
                : ZenoraChatController.getRoomIdForDialog(account, parentDialogId);
        boolean hasParentReplyHeader = owner != null && owner.reply_to != null;

        input.parentExists = parent != null && owner != null;
        input.ownerId = owner == null ? 0L : owner.id;
        input.objectId = objectId;
        input.parentDialogId = parentDialogId;
        input.currentDialogId = currentDialogId;
        input.parentRoomId = parentRoomId;
        input.currentRoomId = currentRoomId;
        // A null media pointer is a valid text shape in some native/history paths.
        input.mediaIsTextCompatible = owner != null
                && (owner.media == null || owner.media instanceof TLRPC.TL_messageMediaEmpty);
        input.textPresent = owner != null && !TextUtils.isEmpty(owner.message)
                && !TextUtils.isEmpty(owner.message.trim());
        input.service = owner != null && owner.action != null;
        input.ephemeral = parent != null && parent.isEphemeral();
        input.topic = topicParent != null
                || owner != null && owner.reply_to != null && owner.reply_to.reply_to_top_id > 0;
        input.quote = quoteSelected || hasParentReplyHeader
                && !TextUtils.isEmpty(owner.reply_to.quote_text);
        input.forwarding = forwarding || owner != null && owner.fwd_from != null;
        input.scheduled = scheduled || parent != null && parent.scheduled;
        input.editing = editing;

        ZenoraReplyValidationPolicy.Result policy = ZenoraReplyValidationPolicy.validate(input);
        String media = owner == null ? "none"
                : owner.media == null ? "null" : owner.media.getClass().getSimpleName();
        String fromPeerClass = owner == null || owner.from_id == null
                ? "null" : owner.from_id.getClass().getSimpleName();
        long senderId = owner == null || owner.from_id == null
                ? 0L : DialogObject.getPeerDialogId(owner.from_id);
        String action = owner == null || owner.action == null
                ? "null" : owner.action.getClass().getSimpleName();
        FileLog.d("[ZenoraChat][ReplyValidator] account=" + account
                + " ownerExists=" + (owner != null)
                + " ownerId=" + input.ownerId
                + " objectId=" + input.objectId
                + " parentDialog=" + parentDialogId
                + " currentDialog=" + currentDialogId
                + " parentRoom=" + parentRoomId
                + " currentRoom=" + currentRoomId
                + " media=" + media
                + " textPresent=" + input.textPresent
                + " from_id=" + fromPeerClass
                + " senderId=" + senderId
                + " action=" + action
                + " ephemeral=" + input.ephemeral
                + " topic=" + input.topic
                + " quote=" + input.quote
                + " forwarding=" + input.forwarding
                + " scheduled=" + input.scheduled
                + " editing=" + input.editing
                + " result=" + policy.reasonCode);
        return new Result(policy.accepted, policy.reasonCode, policy.replyToId, parent);
    }
}
