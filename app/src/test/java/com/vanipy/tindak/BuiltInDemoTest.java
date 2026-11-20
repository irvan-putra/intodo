package com.vanipy.tindak;
import static org.junit.Assert.*;
import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class) @Config(sdk=32)
public class BuiltInDemoTest {
 Context c;
 @Before public void init(){c=RuntimeEnvironment.getApplication();Prefs.get(c).edit().clear().commit();}
 @Test public void demoWorksWithoutKeyOrAnyUrl()throws Exception{Prefs.ensureBuiltInDemo(c);assertFalse(Prefs.isPaired(c));assertTrue(Prefs.setDemo(c,true));assertEquals(6,FeedParser.parse(Prefs.feed(c)).sections.stream().mapToInt(s->s.items.size()).sum());assertTrue(DemoFeed.isSynthetic(Prefs.feed(c)));final boolean[] done={false};SyncEngine.run(c,(ok,msg)->{assertTrue(ok);assertTrue(msg.contains("offline"));done[0]=true;});assertTrue(done[0]);assertEquals(0,Prefs.checkAt(c));assertEquals("",Prefs.realUrl(c));assertEquals("",Prefs.demoUrl(c));}
 @Test public void preservesRealCacheAndKeysAndModeAcrossMigration(){Prefs.saveKeys(c,"private-test","public-test");Prefs.saveFeedUrl(c,DualFeedTest.REAL);Prefs.saveFeed(c,DualFeedTest.REAL_JSON,"real-g");Prefs.saveCheck(c);long checked=Prefs.checkAt(c);Context old=Prefs.captureFeed(c);Prefs.ensureBuiltInDemo(c);assertFalse(Prefs.isCurrentFeed(old));assertFalse(Prefs.isDemo(c));Prefs.setDemo(c,true);assertEquals(DemoFeed.BUILT_IN,Prefs.feed(c));Prefs.setDemo(c,false);assertEquals(DualFeedTest.REAL_JSON,Prefs.feed(c));assertEquals("real-g",Prefs.feedGen(c));assertEquals(checked,Prefs.checkAt(c));assertEquals(DualFeedTest.REAL,Prefs.realUrl(c));assertEquals("private-test",Prefs.privateKey(c));assertEquals("public-test",Prefs.publicKey(c));}
 @Test public void existingExternalDemoBackedUpOnce(){Prefs.configureFeeds(c,DualFeedTest.REAL,DualFeedTest.DEMO,false);Prefs.setDemo(c,true);Prefs.saveFeed(c,DualFeedTest.DEMO_JSON,"old-demo");Prefs.ensureBuiltInDemo(c);assertEquals(DualFeedTest.DEMO_JSON,Prefs.get(c).getString("external_demo_feed",null));assertEquals(DualFeedTest.DEMO,Prefs.get(c).getString("external_demo_feed_url",null));assertEquals(DemoFeed.BUILT_IN,Prefs.feed(c));Prefs.ensureBuiltInDemo(c);assertEquals(DualFeedTest.DEMO_JSON,Prefs.get(c).getString("external_demo_feed",null));}
 @Test public void onlyRealUrlRequiredAndNoKeyReplacement(){Prefs.saveKeys(c,"private-test","public-test");Prefs.saveFeed(c,DualFeedTest.REAL_JSON,"real-g");Prefs.configureRealFeed(c,DualFeedTest.REAL,false);assertTrue(Prefs.dualConfigured(c));assertEquals("",Prefs.demoUrl(c));assertEquals(DualFeedTest.REAL_JSON,Prefs.feed(c));assertEquals("private-test",Prefs.privateKey(c));assertTrue(Prefs.setDemo(c,true));assertTrue(Prefs.setDemo(c,false));assertEquals(DualFeedTest.REAL_JSON,Prefs.feed(c));}
 @Test public void singleLegacySyntheticCacheKeptInRecovery(){Prefs.saveFeed(c,DualFeedTest.DEMO_JSON,"old-demo");Prefs.configureRealFeed(c,DualFeedTest.REAL,true);assertEquals(DualFeedTest.DEMO_JSON,Prefs.get(c).getString("legacy_feed",null));assertNull(Prefs.feed(c));Prefs.setDemo(c,true);assertEquals(DemoFeed.BUILT_IN,Prefs.feed(c));}
 @Test public void newDemoInvalidatesOldFetchAndDoesNotBecomeReal(){Prefs.ensureBuiltInDemo(c);Context old=Prefs.captureFeed(c);Prefs.setDemo(c,true);assertFalse(Prefs.isCurrentFeed(old));Prefs.setDemo(c,false);assertNull(Prefs.feed(c));}
}
