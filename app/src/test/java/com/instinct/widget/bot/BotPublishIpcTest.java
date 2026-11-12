package com.instinct.widget.bot;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=32)
public class BotPublishIpcTest {
    BotActivity a;
    File dir;
    String text(File f) throws Exception {return new String(Files.readAllBytes(f.toPath()),StandardCharsets.UTF_8);}
    @Before public void setup() throws Exception {
        a=Robolectric.buildActivity(BotActivity.class).setup().get();dir=a.getFilesDir();
        for(File f:dir.listFiles()) if(f.isFile())f.delete();
    }
    @Test public void immutableFileAtomicRequestPreservesDone() throws Exception {
        File done=new File(dir,"PUBLISH_DONE"); Files.write(done.toPath(),"previous dedup result".getBytes());
        a.queuePublish(new ByteArrayInputStream("first bytes".getBytes()));
        String[] req1=text(new File(dir,"PUBLISH_REQ")).split("\n");
        assertEquals(2,req1.length);assertTrue(req1[0].matches("publish-[a-f0-9]{64}\\.xdc"));
        assertEquals("first bytes",text(new File(dir,req1[0])));
        assertEquals("previous dedup result",text(done));
        assertFalse(new File(dir,"PUBLISH_REQ.tmp").exists());
        new File(dir,"PUBLISH_REQ").delete();
        a.queuePublish(new ByteArrayInputStream("second bytes".getBytes()));
        String[] req2=text(new File(dir,"PUBLISH_REQ")).split("\n");
        assertNotEquals(req1[0],req2[0]);assertNotEquals(req1[1],req2[1]);
        assertEquals("first bytes",text(new File(dir,req1[0])));
        assertEquals("second bytes",text(new File(dir,req2[0])));
    }
    @Test public void sameBytesKeepFileButRotateToken() throws Exception {
        a.queuePublish(new ByteArrayInputStream("same bytes".getBytes()));
        String[] one=text(new File(dir,"PUBLISH_REQ")).split("\n");
        new File(dir,"PUBLISH_REQ").delete();
        a.queuePublish(new ByteArrayInputStream("same bytes".getBytes()));
        String[] two=text(new File(dir,"PUBLISH_REQ")).split("\n");
        assertEquals(one[0],two[0]);assertNotEquals(one[1],two[1]);
    }
    @Test public void pendingRequestNotOverwritten() throws Exception {
        a.queuePublish(new ByteArrayInputStream("one".getBytes()));
        String before=text(new File(dir,"PUBLISH_REQ"));
        try {a.queuePublish(new ByteArrayInputStream("two".getBytes()));fail("must refuse concurrent publish");}
        catch(IOException expected){}
        assertEquals(before,text(new File(dir,"PUBLISH_REQ")));
    }
    @Test public void pendingOperationSurvivesRecreation() throws Exception {
        a.queuePublish(new ByteArrayInputStream("bytes one".getBytes()));
        android.content.SharedPreferences p = a.getSharedPreferences("bot", 0);
        assertNotNull(p.getString("pending_publish_token", null));
        assertNotNull(p.getString("pending_publish_sha", null));
        // REQ still in flight -> a recreated activity reattaches to it
        BotActivity b = Robolectric.buildActivity(BotActivity.class).setup().get();
        java.lang.reflect.Field ft = BotActivity.class.getDeclaredField("pendingPublishToken");
        ft.setAccessible(true);
        assertEquals(p.getString("pending_publish_token", null), ft.get(b));
        java.lang.reflect.Field fs = BotActivity.class.getDeclaredField("pendingPublishSha");
        fs.setAccessible(true);
        assertEquals(p.getString("pending_publish_sha", null), fs.get(b));
    }
    @Test public void progressCheckpointNotOverwritten() throws Exception {
        Files.write(new File(dir,"PUBLISH_PROG").toPath(),"{}".getBytes());
        try {a.queuePublish(new ByteArrayInputStream("one".getBytes()));fail("must refuse pending resume");}
        catch(IOException expected){}
        assertFalse(new File(dir,"PUBLISH_REQ").exists());
    }
}
