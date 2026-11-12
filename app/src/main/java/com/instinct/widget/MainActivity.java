package com.instinct.widget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private TextView lastSync;
    private ListView listView;
    private TextView empty;
    private Button syncNow;
    private FeedAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        lastSync = findViewById(R.id.lastSync);
        listView = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        syncNow = findViewById(R.id.syncNow);
        adapter = new FeedAdapter(this);
        listView.setAdapter(adapter);

        Button generate = findViewById(R.id.generateKey);
        Button copy = findViewById(R.id.copyKey);
        TextView repair = findViewById(R.id.repair);
        generate.setOnClickListener(v -> generateKeys());
        if (Prefs.isPaired(this)) generate.setText("Replace pairing key");
        copy.setOnClickListener(v -> copyKey());
        repair.setOnClickListener(v -> showPairing());
        syncNow.setOnClickListener(v -> sync());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Prefs.isPaired(this)) showList(); else showPairing();
        refreshStatus();
        if (getIntent() != null && getIntent().getBooleanExtra("sync", false) && Prefs.isPaired(this)) {
            getIntent().removeExtra("sync");
            sync();
        }
    }

    private void showPairing() {
        findViewById(R.id.pairingPane).setVisibility(View.VISIBLE);
        findViewById(R.id.listPane).setVisibility(View.GONE);
        syncNow.setVisibility(View.GONE);
        findViewById(R.id.repair).setVisibility(View.GONE);
        if (Prefs.isPaired(this)) showKey();
    }

    private void showList() {
        findViewById(R.id.pairingPane).setVisibility(View.GONE);
        findViewById(R.id.listPane).setVisibility(View.VISIBLE);
        syncNow.setVisibility(View.VISIBLE);
        findViewById(R.id.repair).setVisibility(View.VISIBLE);
        reload();
    }

    private void generateKeys() {
        if (Prefs.isPaired(this)) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Replace pairing key?")
                    .setMessage("This phone already has a key. Creating a new one means you must send the new public key to Instinct before sync works again.")
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
        cm.setPrimaryClip(ClipData.newPlainText("Instinct widget public key", Prefs.publicKey(this)));
        Toast.makeText(this, "Public key copied. Send it to Instinct on WhatsApp.", Toast.LENGTH_LONG).show();
    }

    private void sync() {
        syncNow.setEnabled(false);
        lastSync.setText("Syncing...");
        SyncEngine.run(this, (ok, message) -> {
            syncNow.setEnabled(true);
            reload();
            refreshStatus();
            Toast.makeText(this, ok ? "Synced" : message, Toast.LENGTH_LONG).show();
            AppWidgetManager awm = AppWidgetManager.getInstance(this);
            int[] ids = awm.getAppWidgetIds(new ComponentName(this, WidgetProvider.class));
            awm.notifyAppWidgetViewDataChanged(ids, R.id.widget_list);
            WidgetProvider.updateAll(this);
        });
    }

    private void reload() {
        String json = Prefs.feed(this);
        List<Object> rows = new ArrayList<>();
        if (json != null) {
            try {
                FeedParser.Feed feed = FeedParser.parse(json);
                for (FeedParser.Section sec : feed.sections) {
                    if (sec.items.isEmpty()) continue;
                    rows.add(sec);
                    rows.addAll(sec.items);
                }
            } catch (Exception ignored) { }
        }
        adapter.setRows(rows);
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void refreshStatus() {
        long at = Prefs.feedAt(this);
        if (at > 0) {
            String gen = shortGen(Prefs.feedGen(this));
            String asOf = gen.isEmpty() ? "" : "List as of " + gen + "  ·  ";
            lastSync.setText(asOf + "fetched " + DateUtils.getRelativeTimeSpanString(at));
        } else {
            lastSync.setText(Prefs.status(this));
        }
    }

    static String shortGen(String iso) {
        // "2026-11-12T07:01:00Z" -> "2026-11-12 14:01" in the phone's own timezone
        if (iso == null || iso.isEmpty()) return "";
        try {
            java.time.ZonedDateTime z = java.time.OffsetDateTime.parse(iso)
                    .atZoneSameInstant(java.time.ZoneId.systemDefault());
            return z.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (Exception e) {
            return iso.length() >= 16 ? iso.substring(0, 10) + " " + iso.substring(11, 16) : iso;
        }
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
