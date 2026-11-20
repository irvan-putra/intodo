package com.vanipy.tindak;
import static org.junit.Assert.*;
import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=32)
public class DualFeedTest {
    Context c;
    static final String REAL="https://files.synthetic.test/synthetic-real-test";
    static final String DEMO="https://files.synthetic.test/synthetic-demo-test";
    static final String REAL_JSON="{\"generated_at\":\"2026-11-18T12:00:00Z\",\"sections\":[{\"title\":\"Reminders\",\"items\":[{\"id\":\"test-private\",\"title\":\"PRIVATE TEST ITEM\"}]}]}";
    static final String DEMO_JSON="{\"generated_at\":\"2026-11-18T10:00:00Z\",\"sections\":[{\"title\":\"DEMO - Tasks\",\"items\":[{\"id\":\"demo-one\",\"title\":\"SYNTHETIC DEMO ITEM\"}]}]}";
    @Before public void init(){c=RuntimeEnvironment.getApplication();Prefs.get(c).edit().clear().commit();}
    @Test public void legacyRealPreserved(){Prefs.saveKeys(c,"private-test","public-test");Prefs.saveFeed(c,REAL_JSON,"real-gen");Prefs.saveFeedUrl(c,REAL);Prefs.configureFeeds(c,REAL,DEMO,false);assertEquals(REAL_JSON,Prefs.feed(c));assertEquals("real-gen",Prefs.feedGen(c));assertEquals("private-test",Prefs.privateKey(c));assertEquals(REAL,SyncEngine.effectiveFeedUrl(c));}
    @Test public void legacyDemoAssignedWithoutKeyLoss(){Prefs.saveKeys(c,"private-test","public-test");Prefs.saveFeed(c,DEMO_JSON,"demo-gen");Prefs.configureFeeds(c,REAL,DEMO,true);assertTrue(Prefs.isDemo(c));assertEquals(DEMO_JSON,Prefs.feed(c));Prefs.setDemo(c,false);assertNull(Prefs.feed(c));assertEquals("private-test",Prefs.privateKey(c));assertEquals(DEMO_JSON,Prefs.get(c).getString("legacy_feed",null));}
    @Test public void cachesAndFreshnessAreIndependent(){Prefs.configureFeeds(c,REAL,DEMO,false);Prefs.saveFeed(c,REAL_JSON,"2026-11-18T12:00:00Z");Prefs.saveCheck(c);Prefs.setDemo(c,true);assertNull(Prefs.feed(c));assertEquals("",Prefs.feedGen(c));assertEquals(0,Prefs.checkAt(c));Prefs.saveFeed(c,DEMO_JSON,"2026-11-18T10:00:00Z");Prefs.saveCheckFailure(c,"demo offline");Prefs.setDemo(c,false);assertEquals(REAL_JSON,Prefs.feed(c));assertEquals("2026-11-18T12:00:00Z",Prefs.feedGen(c));assertEquals("",Prefs.checkError(c));Prefs.setDemo(c,true);assertEquals(DEMO_JSON,Prefs.feed(c));assertEquals("demo offline",Prefs.checkError(c));}
    @Test public void switchingInvalidatesInflightEvenAfterSwitchBack(){Prefs.configureFeeds(c,REAL,DEMO,false);Context captured=Prefs.captureFeed(c);assertTrue(Prefs.isCurrentFeed(captured));Prefs.setDemo(c,true);assertFalse(Prefs.isCurrentFeed(captured));Prefs.setDemo(c,false);assertFalse(Prefs.isCurrentFeed(captured));}
    @Test public void scopedResponseCannotBecomeOtherMode(){Prefs.configureFeeds(c,REAL,DEMO,false);Context captured=Prefs.captureFeed(c);Prefs.setDemo(c,true);Prefs.saveFeed(captured,REAL_JSON,"g");assertNull(Prefs.feed(c));Prefs.setDemo(c,false);assertEquals(REAL_JSON,Prefs.feed(c));}
    @Test public void demoRefusesAccidentalRealCache(){Prefs.configureFeeds(c,REAL,DEMO,false);Prefs.setDemo(c,true);Prefs.saveFeed(c,REAL_JSON,"g");assertNull(Prefs.feed(c));assertFalse(DemoFeed.isSynthetic(REAL_JSON));assertTrue(DemoFeed.isSynthetic(DEMO_JSON));}
    @Test public void legacyWidgetFactoryDoesNotReturnRealRowsAfterSwitch(){Prefs.configureFeeds(c,REAL,DEMO,false);Prefs.saveFeed(c,REAL_JSON,"g");WidgetService.Factory factory=new WidgetService.Factory(c);factory.onCreate();assertEquals(1,factory.getCount());Prefs.setDemo(c,true);assertEquals(0,factory.getCount());assertNull(factory.getViewAt(0));factory.onDataSetChanged();assertEquals(0,factory.getCount());}
    @Test public void endpointChangeInvalidatesInflightAndOnlyOwnCache(){Prefs.configureFeeds(c,REAL,DEMO,false);Prefs.saveFeed(c,REAL_JSON,"g");Prefs.setDemo(c,true);Prefs.saveFeed(c,DEMO_JSON,"d");Context captured=Prefs.captureFeed(c);Prefs.configureFeeds(c,REAL,DEMO+"-new",false);assertFalse(Prefs.isCurrentFeed(captured));assertNull(Prefs.feed(c));Prefs.setDemo(c,false);assertEquals(REAL_JSON,Prefs.feed(c));}
}
