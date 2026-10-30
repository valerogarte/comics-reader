import com.valerogarte.comicsreader.sftp.SftpClient;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
public class SftpSmoke {
 static final int PORT=Integer.getInteger("sftp.qa.port",22991), CONTROL_PORT=Integer.getInteger("sftp.qa.controlPort",22992);
 static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 static String command(String json)throws Exception{try(Socket s=new Socket("127.0.0.1",CONTROL_PORT)){s.getOutputStream().write((json+"\n").getBytes(StandardCharsets.UTF_8));return new BufferedReader(new InputStreamReader(s.getInputStream())).readLine();}}
 static long transferred(String json){java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("\\\"length\\\"\\s*:\\s*(\\d+)").matcher(json);long total=0;while(matcher.find())total+=Long.parseLong(matcher.group(1));return total;}
 static long connections(String json){java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("\\\"connections\\\"\\s*:\\s*(\\d+)").matcher(json);long total=0;while(matcher.find())total+=Long.parseLong(matcher.group(1));return total;}
 public static void main(String[] args)throws Exception{
  command("{\"op\":\"reset\"}"); File dir=Files.createTempDirectory("sftp-native-smoke-").toFile(); File hosts=new File(dir,"known_hosts"); hosts.createNewFile();
  SftpClient.Credentials credentials=new SftpClient.Credentials("127.0.0.1",PORT,"qaA","fixture-password"); SftpClient client=new SftpClient(hosts); int passed=0;
  try{
   require(client.listShelf(credentials,"/library","/").stream().noneMatch(e->e.name.equals("escape")),"Escaping symlink listed");passed++;
   List<SftpClient.Entry> entries=client.listShelf(credentials,"/library","/Comics/DC/Sandman");require(entries.size()==2,"Fixture listing failed");passed++;
   long wireBefore=transferred(command("{\"op\":\"stats\"}")); byte[] one=client.readShelfRange(credentials,"/library","/Comics/DC/Sandman/a.cbz",0,1); long wireAfter=transferred(command("{\"op\":\"stats\"}")); require(one.length==1&&wireAfter-wireBefore==1,"One-byte range overfetched on the wire");passed++;
   byte[] range=client.readShelfRange(credentials,"/library","/Comics/DC/Sandman/a.cbz",0,4);require(Arrays.equals(range,new byte[]{0x50,0x4b,3,4}),"Range bytes mismatch");passed++;
   String collidingPassword="gJxture-password";require("fixture-password".hashCode()==collidingPassword.hashCode(),"Collision fixture is invalid");
   SftpClient.Credentials wrongCollision=new SftpClient.Credentials("127.0.0.1",PORT,"qaA",collidingPassword);
   boolean collisionRejected=false;try{client.listShelf(wrongCollision,"/library","/");}catch(Exception expected){collisionRejected=SftpClient.isAuthFailure(expected);}require(collisionRejected,"Password hash collision reused an authenticated session");passed++;
   SftpClient collisionClient=new SftpClient(hosts);try{collisionRejected=false;try{collisionClient.listShelf(wrongCollision,"/library","/");}catch(Exception expected){collisionRejected=SftpClient.isAuthFailure(expected);}require(collisionRejected&&!collisionClient.listShelf(credentials,"/library","/").isEmpty(),"Password hash collision shared authentication cooldown");}finally{collisionClient.disconnectAll();}passed++;
   boolean rejected=false;try{client.readShelfRange(credentials,"/library","/../outside/secret.cbz",0,4);}catch(IllegalArgumentException expected){rejected=true;}require(rejected,"Traversal accepted");passed++;
   rejected=false;try{client.readShelfRange(credentials,"/library","/escape/secret.cbz",0,4);}catch(IllegalArgumentException expected){rejected=true;}require(rejected,"Escaping symlink read accepted");passed++;
   rejected=false;try{client.readShelfRange(credentials,"/library","/Comics/DC/Sandman/a.cbz",-1,4);}catch(IllegalArgumentException expected){rejected=true;}require(rejected,"Negative offset accepted");passed++;
   command("{\"op\":\"delay\",\"user\":\"qaA\",\"seconds\":1.2}"); SftpClient shortDeadline=new SftpClient(hosts,300);long began=System.nanoTime();rejected=false;try{shortDeadline.readShelfRange(credentials,"/library","/Comics/DC/Sandman/a.cbz",0,1);}catch(Exception expected){rejected=true;}finally{shortDeadline.disconnectAll();command("{\"op\":\"delay\",\"user\":\"qaA\",\"seconds\":0}");}long elapsedMs=(System.nanoTime()-began)/1000000L;require(rejected&&elapsedMs<1500,"Unanswered SFTP read did not respect its deadline");passed++;
   byte[] literal=client.readShelfRange(credentials,"/library","/who?.txt",0,1);require(literal.length==1&&literal[0]=='q',"Literal question mark path expanded as glob");passed++;
   File copy=new File(dir,"a.cbz.part");long count=client.downloadShelf(credentials,"/library","/Comics/DC/Sandman/a.cbz",copy,null);require(count==copy.length()&&count>500000,"Explicit stream download incomplete");passed++;
   SftpClient guarded=new SftpClient(hosts,15000,300);
   try{
    command("{\"op\":\"delay\",\"user\":\"qaA\",\"seconds\":1.2}");File stalled=new File(dir,"stalled.part");began=System.nanoTime();rejected=false;
    try{guarded.downloadShelf(credentials,"/library","/readme.txt",stalled,null);}catch(Exception expected){rejected=true;}finally{command("{\"op\":\"delay\",\"user\":\"qaA\",\"seconds\":0}");}
    require(rejected&&!stalled.exists()&&(System.nanoTime()-began)/1000000L<1500,"Stalled download kept waiting or retained partial file");passed++;
    command("{\"op\":\"delay\",\"user\":\"qaA\",\"seconds\":0.01}");File progressing=new File(dir,"progressing.part");began=System.nanoTime();
    try{long bytes=guarded.downloadShelf(credentials,"/library","/slow.txt",progressing,null);require(bytes==progressing.length()&&bytes>4000000&&(System.nanoTime()-began)/1000000L>300,"Active download must outlive the inactivity timeout");}finally{command("{\"op\":\"delay\",\"user\":\"qaA\",\"seconds\":0}");}passed++;
    command("{\"op\":\"delay\",\"user\":\"qaA\",\"operation\":\"list\",\"seconds\":1.2}");began=System.nanoTime();rejected=false;
    try{guarded.listShelf(credentials,"/library","/");}catch(Exception expected){rejected=true;}finally{command("{\"op\":\"delay\",\"user\":\"qaA\",\"operation\":\"list\",\"seconds\":0}");}
    require(rejected&&(System.nanoTime()-began)/1000000L<1500,"Stalled listing ignored its deadline");passed++;
    command("{\"op\":\"delay\",\"user\":\"qaA\",\"operation\":\"stat\",\"seconds\":1.2}");began=System.nanoTime();rejected=false;
    try{guarded.readShelfRange(credentials,"/library","/readme.txt",0,1);}catch(Exception expected){rejected=true;}finally{command("{\"op\":\"delay\",\"user\":\"qaA\",\"operation\":\"stat\",\"seconds\":0}");}
    require(rejected&&(System.nanoTime()-began)/1000000L<1500,"STAT before range read ignored its deadline");passed++;
    command("{\"op\":\"delay\",\"user\":\"qaA\",\"operation\":\"stat\",\"seconds\":0.08}");began=System.nanoTime();
    try{List<SftpClient.Entry> linked=guarded.listShelf(credentials,"/library","/linked");require(linked.size()==8&&(System.nanoTime()-began)/1000000L>300,"Active symlink listing must outlive its inactivity timeout");}finally{command("{\"op\":\"delay\",\"user\":\"qaA\",\"operation\":\"stat\",\"seconds\":0}");}passed++;
   }finally{guarded.disconnectAll();}
   long beforeCancelConnections=connections(command("{\"op\":\"stats\"}"));File interrupted=new File(dir,"interrupted.part");CountDownLatch entered=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
   command("{\"op\":\"delay\",\"user\":\"qaA\",\"seconds\":1.2}");
   Thread worker=new Thread(()->{entered.countDown();try{client.downloadShelf(credentials,"/library","/readme.txt",interrupted,null);}catch(Throwable expected){failure.set(expected);}},"qa-interrupted-download");worker.start();
   try{require(entered.await(1,TimeUnit.SECONDS),"Download worker did not start");Thread.sleep(200);began=System.nanoTime();worker.interrupt();worker.join(1000);require(!worker.isAlive()&&failure.get()!=null&&!interrupted.exists(),"Interrupted download did not close its channel and partial file promptly");require(!client.listShelf(credentials,"/library","/").isEmpty(),"Interrupt closed unrelated channels");require(connections(command("{\"op\":\"stats\"}"))==beforeCancelConnections,"Interrupt disconnected the shared SSH session");}finally{command("{\"op\":\"delay\",\"user\":\"qaA\",\"seconds\":0}");if(worker.isAlive()){client.disconnectAll();worker.join(3000);}}passed++;
   File cancelled=new File(dir,"cancelled.part");rejected=false;try{client.downloadShelf(credentials,"/library","/slow.txt",cancelled,bytes->{throw new java.util.concurrent.CancellationException();});}catch(Exception expected){rejected=true;}require(rejected&&!cancelled.exists(),"Cancelled download left partial file");passed++;
   File changing=new File(dir,"changing.part");boolean[] changed={false};rejected=false;
   try{client.downloadShelf(credentials,"/library","/Comics/DC/Sandman/a.cbz",changing,bytes->{if(!changed[0]){changed[0]=true;try{command("{\"op\":\"mutate\",\"user\":\"qaA\",\"version\":2}");}catch(Exception e){throw new RuntimeException(e);}}});}catch(Exception expected){rejected=true;}
   require(rejected&&!changing.exists(),"Changed remote file accepted or partial retained");passed++;
   client.disconnectAll();require(hosts.length()>0,"Host pin not persisted");command("{\"op\":\"rotate\"}");
   SftpClient recreated=new SftpClient(hosts);rejected=false;try{recreated.listShelf(credentials,"/library","/");}catch(Exception expected){rejected=true;}finally{recreated.disconnectAll();}require(rejected,"Changed host key accepted");passed++;
   System.out.println("Native SftpClient real SSH checks passed: "+passed);
  }finally{client.disconnectAll();command("{\"op\":\"reset\"}");for(File file:Objects.requireNonNull(dir.listFiles()))file.delete();dir.delete();}
 }
}
