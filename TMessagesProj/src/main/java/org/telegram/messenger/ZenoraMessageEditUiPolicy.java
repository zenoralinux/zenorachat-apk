package org.telegram.messenger;
import java.util.List;
/** Android-independent selection and version policy for live Zenora message edits. */
public final class ZenoraMessageEditUiPolicy {
 private ZenoraMessageEditUiPolicy() {}
 public interface MessageIdentity<T>{long getDialogId(T message);long getMessageId(T message);}
 /** Prefer the Adapter list instance to a detached/stale dictionary object. */
 public static <T>T findActiveMessage(long dialogId,long messageId,T indexedMessage,List<T> activeMessages,MessageIdentity<T> identity){
  if(dialogId==0L||messageId<=0L||identity==null)return null;
  if(activeMessages!=null)for(int i=0;i<activeMessages.size();i++){
   T candidate=activeMessages.get(i);
   if(matches(candidate,dialogId,messageId,identity))return candidate;
  }
  return matches(indexedMessage,dialogId,messageId,identity)?indexedMessage:null;
 }
 /** Equal versions may replay idempotently; strictly older versions are rejected. */
 public static boolean canApplyEditVersion(long currentVersion,long incomingVersion){
  return incomingVersion>0L&&currentVersion<=incomingVersion;
 }
 private static <T>boolean matches(T message,long dialogId,long messageId,MessageIdentity<T> identity){
  return message!=null&&identity.getDialogId(message)==dialogId&&identity.getMessageId(message)==messageId;
 }
}
