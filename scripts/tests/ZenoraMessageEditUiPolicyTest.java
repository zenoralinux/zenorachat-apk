package org.telegram.messenger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
public final class ZenoraMessageEditUiPolicyTest {
 private static final class Row{
  final long dialogId,messageId;long version;String body;
  Row(long d,long m,long v,String b){dialogId=d;messageId=m;version=v;body=b;}
 }
 private static final ZenoraMessageEditUiPolicy.MessageIdentity<Row> ID=new ZenoraMessageEditUiPolicy.MessageIdentity<Row>(){
  public long getDialogId(Row r){return r.dialogId;}public long getMessageId(Row r){return r.messageId;}
 };
 private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);System.out.println("PASS: "+label);}
 public static void main(String[] args){
  long did=7001L;Row staleMap=new Row(did,91,1,"old map"),adapter=new Row(did,91,1,"old adapter");
  List<Row> active=new ArrayList<>(Arrays.asList(adapter));
  Row found=ZenoraMessageEditUiPolicy.findActiveMessage(did,91,staleMap,active,ID);
  check(found==adapter,"active Adapter instance wins over a detached dictionary copy");
  Row history=new Row(did,120,2,"history");
  List<Row> sqliteLoadedChatMessages=new ArrayList<>(Arrays.asList(history));
  // Regression: liveMessageObjects intentionally has no entry (indexedMessage=null),
  // but ChatActivity still owns this history row in its active adapter list.
  found=ZenoraMessageEditUiPolicy.findActiveMessage(did,120,null,sqliteLoadedChatMessages,ID);
  check(found==history,"history-loaded row is found without bounded live-object cache");
  check(found==ZenoraMessageEditUiPolicy.findActiveMessage(did,120,null,sqliteLoadedChatMessages,ID),
      "missing liveMessageObjects entry does not block updating the existing ChatActivity message");
  check(ZenoraMessageEditUiPolicy.findActiveMessage(did,91,null,null,ID)==null,"missing row is not fabricated or reloaded");
  Row wrongDialog=new Row(did+1,91,3,"other dialog");
  check(ZenoraMessageEditUiPolicy.findActiveMessage(did,91,wrongDialog,Arrays.asList(wrongDialog),ID)==null,"same ID in another dialog is rejected");
  Row fallback=new Row(did,92,1,"dictionary fallback");
  check(ZenoraMessageEditUiPolicy.findActiveMessage(did,92,fallback,new ArrayList<Row>(),ID)==fallback,"scoped dictionary is only a fallback");
  check(!ZenoraMessageEditUiPolicy.canApplyEditVersion(4,3),"older API/event version cannot overwrite newer edit");
  check(ZenoraMessageEditUiPolicy.canApplyEditVersion(3,3),"equal-version duplicate is idempotently reconcilable");
  check(ZenoraMessageEditUiPolicy.canApplyEditVersion(3,4),"newer canonical edit is accepted");
  if(ZenoraMessageEditUiPolicy.canApplyEditVersion(adapter.version,4)){adapter.body="canonical v4";adapter.version=4;}
  if(ZenoraMessageEditUiPolicy.canApplyEditVersion(adapter.version,2)){adapter.body="stale v2";adapter.version=2;}
  check(adapter.version==4&&"canonical v4".equals(adapter.body),"out-of-order delivery leaves active object at newest version");
  System.out.println("OK: Zenora live message edit UI policy behavioral tests passed.");
 }
}
