package com.instinct.widget;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private TextView lastSync;
    private String pendingAssetDigest;
    // Operation guards: each new update download / sync bumps its generation, so
    // late callbacks queued from a previous operation are dropped, and a terminal
    // status can never be overwritten by a late progress event.
    private int updateGen = 0;
    private boolean updateRunning = false;
    private int syncGen = 0;
    private boolean syncRunning = false;
    private boolean syncDataStarted = false;
    // Background syncs write prefs in this process; refresh the visible UI on change.
    private final SharedPreferences.OnSharedPreferenceChangeListener syncPrefsListener =
            (prefs, key) -> runOnUiThread(() -> { reload(); refreshStatus(); });
    private ListView listView;
    private TextView empty;
    private Button syncNow;
    private DrawerLayout drawer;
    private SwipeRefreshLayout swipeRefresh;
    private FeedAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        FeedSyncScheduler.schedule(this);
        lastSync = findViewById(R.id.lastSync);
        listView = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        syncNow = findViewById(R.id.syncNow);
        adapter = new FeedAdapter(this);
        listView.setAdapter(adapter);
        drawer = findViewById(R.id.drawerLayout);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        // pull-down on the list triggers the same sync the menu button does
        swipeRefresh.setOnRefreshListener(() -> sync());
        // accessible menu fallback that needs no visible chrome and never
        // competes with the Android 10+ edge Back gesture: long-press any item
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            drawer.openDrawer(GravityCompat.START);
            return true;
        });
        // same fallback where no item can be long-pressed: empty area, empty
        // state and the unpaired pane, plus a TalkBack action on each surface
        installMenuAccess(listView);
        installMenuAccess(empty);
        installMenuAccess(findViewById(R.id.pairingPane));

        Button generate = findViewById(R.id.generateKey);
        findViewById(R.id.openCanvas).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, com.instinct.widget.canvas.ReviewCanvasActivity.class)));
        findViewById(R.id.openBot).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, com.instinct.widget.bot.BotActivity.class)));
        Button copy = findViewById(R.id.copyKey);
        TextView repair = findViewById(R.id.repair);
        EditText feedUrlInput = findViewById(R.id.feedUrlInput);
        // The feed-address field only matters on public builds with no built-in address;
        // hide it where build config already provides one so behavior stays unambiguous.
        boolean unconfigured = SyncEngine.FEED_URL == null || SyncEngine.FEED_URL.isEmpty();
        feedUrlInput.setVisibility(unconfigured ? View.VISIBLE : View.GONE);
        findViewById(R.id.saveFeedUrl).setVisibility(unconfigured ? View.VISIBLE : View.GONE);
        feedUrlInput.setText(Prefs.feedUrl(this));
        findViewById(R.id.saveFeedUrl).setOnClickListener(v -> {
            String url = feedUrlInput.getText().toString().trim();
            if (!url.isEmpty() && !url.startsWith("https://")) {
                Toast.makeText(this, "Feed address must start with https://", Toast.LENGTH_LONG).show();
                return;
            }
            Prefs.saveFeedUrl(this, url);
            Toast.makeText(this, "Feed address saved", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.updateLine).setOnClickListener(v -> {
            Object asset = v.getTag();
            if (!(asset instanceof String) || ((String) asset).isEmpty()) return;
            if (updateRunning) return; // one download at a time; the core also locks
            TextView line = (TextView) v;
            ProgressBar bar = findViewById(R.id.updateProgress);
            if (!UpdateInstall.canInstall(this)) {
                line.setText("Allow installs from this app, then tap again");
                UpdateInstall.openInstallPermissionSettings(this);
                return;
            }
            updateRunning = true;
            final int gen = ++updateGen;
            line.setEnabled(false);
            line.setText("downloading update...");
            bar.setIndeterminate(true); // until the server states a length
            bar.setProgress(0);
            bar.setVisibility(View.VISIBLE);
            UpdateInstall.downloadAndInstall(this, (String) asset, pendingAssetDigest,
                    new UpdateInstall.Callback() {
                        @Override public void onStatus(String message) {
                            if (gen != updateGen) return; // stale operation
                            updateRunning = false;
                            line.setEnabled(true);
                            bar.setVisibility(View.GONE); // every terminal path hides the bar
                            line.setText(message);
                        }
                        @Override public void onProgress(long done, long total) {
                            if (gen != updateGen || !updateRunning) return; // late event
                            bar.setVisibility(View.VISIBLE);
                            if (total > 0) {
                                int pct = (int) (done * 100 / total);
                                bar.setIndeterminate(false);
                                bar.setProgress(pct);
                                line.setText("downloading update: " + UpdateInstall.mb(done)
                                        + " / " + UpdateInstall.mb(total) + " (" + pct + "%)");
                            } else {
                                // honest unknown total: bytes only, never a fake percent
                                bar.setIndeterminate(true);
                                line.setText("downloading update: " + UpdateInstall.mb(done));
                            }
                        }
                    });
        });
        generate.setOnClickListener(v -> generateKeys());
        if (Prefs.isPaired(this)) generate.setText("Replace pairing key");
        copy.setOnClickListener(v -> copyKey());
        repair.setOnClickListener(v -> showPairing());
        syncNow.setOnClickListener(v -> sync());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Prefs.get(this).registerOnSharedPreferenceChangeListener(syncPrefsListener);
        if (Prefs.isPaired(this)) showList(); else showPairing();
        refreshStatus();
        if (getIntent() != null && getIntent().getBooleanExtra("sync", false) && Prefs.isPaired(this)) {
            getIntent().removeExtra("sync");
            sync();
        }
        ((TextView) findViewById(R.id.version)).setText("v" + BuildConfig.VERSION_NAME);
        checkForUpdate();
    }

    @Override
    protected void onPause() {
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(syncPrefsListener);
        super.onPause();
    }

    private void showPairing() {
        findViewById(R.id.pairingPane).setVisibility(View.VISIBLE);
        swipeRefresh.setVisibility(View.GONE);
        syncNow.setVisibility(View.GONE);
        findViewById(R.id.repair).setVisibility(View.GONE);
        if (Prefs.isPaired(this)) showKey();
    }

    private void showList() {
        findViewById(R.id.pairingPane).setVisibility(View.GONE);
        swipeRefresh.setVisibility(View.VISIBLE);
        syncNow.setVisibility(View.VISIBLE);
        findViewById(R.id.repair).setVisibility(View.VISIBLE);
        reload();
    }

    private void generateKeys() {
        if (Prefs.isPaired(this)) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Replace pairing key?")
                    .setMessage("This phone already has a key. Creating a new one means you must send the new public key to your assistant's WhatsApp chat before sync works again.")
                    .setPositiveButton("Replace", (d, w) -> doGenerateKeys())
                    .setNegativeButton("Keep current", null)
                    .show();
            return;
        }
        doGenerateKeys();
    }

    private void doGenerateKeys() {
        try {
            KeyPair kp = Crypto.generate();
            Prefs.saveKeys(this, Crypto.encodePrivate(kp.getPrivate()), Crypto.encodePublic(kp.getPublic()));
            // a new key orphans the old cache: pairing is visibly pending until a successful decrypt
            Prefs.resetForRepair(this);
            showPairing();
            showKey();
            WidgetProvider.updateAll(this);
        } catch (Exception e) {
            Toast.makeText(this, "Key generation failed", Toast.LENGTH_LONG).show();
        }
    }

    private void showKey() {
        TextView label = findViewById(R.id.pubkeyLabel);
        TextView key = findViewById(R.id.pubkey);
        Button copy = findViewById(R.id.copyKey);
        label.setVisibility(View.VISIBLE);
        key.setVisibility(View.VISIBLE);
        copy.setVisibility(View.VISIBLE);
        key.setText(Prefs.publicKey(this));
    }

    private void copyKey() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("itdapk public key", Prefs.publicKey(this)));
        Toast.makeText(this, "Public key copied. Send it to your assistant's WhatsApp chat.", Toast.LENGTH_LONG).show();
    }

    // Update-check cadence: on app open and on each manual sync, never in the background.
    private void checkForUpdate() {
        UpdateCheck.run(this, (newer, assetUrl, releaseUrl, assetSha256) -> {
            if (updateRunning) return; // never stomp an active download's line or digest
            TextView line = findViewById(R.id.updateLine);
            pendingAssetDigest = assetSha256;
            if (newer != null) {
                line.setText("Update available: v" + newer + " - tap to download and install (Android asks you to confirm)");
                line.setTag(assetUrl);
                line.setVisibility(View.VISIBLE);
            } else {
                line.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void onBackPressed() {
        // an open menu consumes Back first; only a shut menu leaves the activity
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
            return;
        }
        super.onBackPressed();
    }

    private void installMenuAccess(View v) {
        v.setOnLongClickListener(view -> {
            drawer.openDrawer(GravityCompat.START);
            return true;
        });
        ViewCompat.addAccessibilityAction(v, "Open menu", (view, args) -> {
            drawer.openDrawer(GravityCompat.START);
            return true;
        });
    }

    private void sync() {
        if (!Prefs.isPaired(this)) { swipeRefresh.setRefreshing(false); return; }
        if (syncRunning) { swipeRefresh.setRefreshing(false); return; }
        syncRunning = true;
        syncDataStarted = false;
        final int gen = ++syncGen;
        syncNow.setEnabled(false);
        lastSync.setText("Syncing...");
        ProgressBar bar = findViewById(R.id.syncProgress);
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        SyncEngine.run(this, new SyncEngine.Callback() {
            @Override public void onDone(boolean ok, String message) {
                if (gen != syncGen) return; // stale operation
                syncRunning = false;
                syncNow.setEnabled(true); // re-enabled on every terminal path
                bar.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                reload();
                refreshStatus();
                Toast.makeText(MainActivity.this, ok ? "Synced" : message, Toast.LENGTH_LONG).show();
                WidgetProvider.refreshAll(MainActivity.this);
                checkForUpdate(); // manual sync doubles as an update check, as the comment above says
            }
            @Override public void onProgress(String stage, long done, long total) {
                if (gen != syncGen || !syncRunning) return; // late event after terminal
                // Two DISTINCT stages, never merged into an invented overall percent:
                // "page" = the viewer page's own load percent; "data" = the actual
                // list-payload bytes. Once real data flows, late page events are
                // ignored so the WebView's percent cannot overwrite the real bytes.
                if ("page".equals(stage)) {
                    if (syncDataStarted) return;
                    bar.setIndeterminate(false);
                    bar.setProgress((int) done);
                    lastSync.setText("Syncing: loading feed page " + done + "%");
                } else {
                    syncDataStarted = true;
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        bar.setIndeterminate(false);
                        bar.setProgress(pct);
                        lastSync.setText("Syncing: list data " + UpdateInstall.mb(done)
                                + " / " + UpdateInstall.mb(total) + " (" + pct + "%)");
                    } else {
                        bar.setIndeterminate(true); // honest unknown total: bytes only
                        lastSync.setText("Syncing: list data " + UpdateInstall.mb(done));
                    }
                }
            }
        });
    }

    private void reload() {
        String json = Prefs.feed(this);
        List<Object> rows = new ArrayList<>();
        if (json != null) {
            try {
                FeedParser.Feed feed = FeedParser.parse(json);
                int visibleSections = 0;
                for (FeedParser.Section sec : feed.sections) {
                    if (!sec.items.isEmpty()) visibleSections++;
                }
                for (FeedParser.Section sec : feed.sections) {
                    if (sec.items.isEmpty()) continue;
                    // one visible section: its header only repeats the obvious; skip
                    // it and give the row back to items (Option B, user-chosen)
                    if (visibleSections > 1) rows.add(sec);
                    rows.addAll(sec.items);
                }
            } catch (Exception ignored) { }
        }
        adapter.setRows(rows);
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void refreshStatus() {
        lastSync.setText(SyncStatusText.build(this, Prefs.status(this)));
    }

    static class FeedAdapter extends BaseAdapter {
        private final LayoutInflater inflater;
        private List<Object> rows = new ArrayList<>();
        FeedAdapter(Context c) { inflater = LayoutInflater.from(c); }
        void setRows(List<Object> r) { rows = r; notifyDataSetChanged(); }
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int i) { return rows.get(i) instanceof FeedParser.Section ? 0 : 1; }
        @Override public boolean isEnabled(int i) { return false; }
        @Override public View getView(int i, View convert, ViewGroup parent) {
            Object row = rows.get(i);
            if (row instanceof FeedParser.Section) {
                View v = convert != null ? convert : inflater.inflate(R.layout.list_section, parent, false);
                ((TextView) v.findViewById(R.id.section_title)).setText(((FeedParser.Section) row).title);
                return v;
            }
            View v = convert != null ? convert : inflater.inflate(R.layout.list_item, parent, false);
            FeedParser.Item item = (FeedParser.Item) row;
            String suffix = "in_progress".equals(item.status) ? "  (in progress)" : "";
            ((TextView) v.findViewById(R.id.item_title)).setText(item.title + suffix);
            TextView note = v.findViewById(R.id.item_note);
            if (item.note != null && !item.note.isEmpty()) { note.setText(item.note); note.setVisibility(View.VISIBLE); }
            else note.setVisibility(View.GONE);
            return v;
        }
    }
}
