package com.instinct.widget.bot;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import org.json.JSONObject;

public class BotActivity extends Activity {
    private TextView logView;
    private TextView statusView;
    private android.widget.Button fetchXdcBtn;
    private TextView fetchStatus;
    private android.widget.ProgressBar fetchBar;
    private boolean fetchRunning = false;
    private int fetchGen = 0;
    private TextView setupHeader;
    private TextView logsHeader;
    private LinearLayout setupBox;
    private ScrollView logScroll;
    private float lastLogY;
    private final Handler handler = new Handler();
    private String lastStateJson = null;
    private BotImport importer;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // Status first: account, service state, chats served - no raw JSON.
        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setPadding(pad, pad, pad, 0);
        root.addView(statusView);

        // Day-to-day controls only.
        LinearLayout primary = new LinearLayout(this);
        primary.setOrientation(LinearLayout.HORIZONTAL);
        Button live = new Button(this); live.setText("Start bot"); live.setAllCaps(false);
        Button stop = new Button(this); stop.setText("Stop bot"); stop.setAllCaps(false);
        Button invite = new Button(this); invite.setText("Invitation"); invite.setAllCaps(false);
        primary.addView(live, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        primary.addView(stop, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        primary.addView(invite, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(primary);

        LinearLayout updates = new LinearLayout(this);
        updates.setOrientation(LinearLayout.HORIZONTAL);
        fetchXdcBtn = new Button(this); fetchXdcBtn.setText("Get latest app"); fetchXdcBtn.setAllCaps(false);
        Button publish = new Button(this); publish.setText("Publish update"); publish.setAllCaps(false);
        updates.addView(fetchXdcBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        updates.addView(publish, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(updates);
        // Fetch progress: a REAL bar plus the bytes line, wired to the actual
        // download listener. Known total = determinate percent; unknown total =
        // indeterminate with honest bytes; terminal success retains 100% + bytes;
        // resumed bytes appear before the network starts.
        fetchStatus = new TextView(this);
        fetchStatus.setTextSize(12);
        fetchStatus.setPadding(pad, 0, pad, 0);
        fetchStatus.setVisibility(android.view.View.GONE);
        root.addView(fetchStatus);
        fetchBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        fetchBar.setMax(100);
        fetchBar.setIndeterminate(true);
        fetchBar.setVisibility(android.view.View.GONE);
        root.addView(fetchBar);

        // Witness: on-device report of what the bot independently observed
        // in its served chats, plus the user deletion control. No report
        // content ever leaves the device.
        TextView witnessHeader = sectionHeader("Witness");
        witnessHeader.setText("Witness  \u25b8");
        witnessHeader.setFocusable(true);
        root.addView(witnessHeader);
        LinearLayout witnessBox = new LinearLayout(this);
        witnessBox.setOrientation(LinearLayout.VERTICAL);
        TextView witnessView = new TextView(this);
        witnessView.setPadding(pad, 0, pad, 0);
        witnessView.setText("The witness records which messages the bot observed in its chats, so missed or mistaken acknowledgments can be reviewed. Observations stay on this phone.");
        Button wRefresh = new Button(this); wRefresh.setText("Refresh witness report"); wRefresh.setAllCaps(false);
        Button wDelete = new Button(this); wDelete.setText("Delete witness data"); wDelete.setAllCaps(false);
        TextView actorLabel = new TextView(this);
        actorLabel.setPadding(pad, 0, pad, 0);
        actorLabel.setText("Account whose acknowledgments are witnessed (its DeltaChat address):");
        EditText actorInput = new EditText(this);
        actorInput.setSingleLine(true);
        Button wChoose = new Button(this); wChoose.setText("Choose witness chats"); wChoose.setAllCaps(false);
        witnessBox.addView(witnessView); witnessBox.addView(actorLabel); witnessBox.addView(actorInput);
        witnessBox.addView(wChoose); witnessBox.addView(wRefresh); witnessBox.addView(wDelete);
        witnessBox.setVisibility(android.view.View.GONE);
        root.addView(witnessBox);
        witnessHeader.setOnClickListener(v -> toggle(witnessHeader, witnessBox, "Witness"));
        {
            File cfg = new File(getFilesDir(), "witness_config.json");
            if (cfg.exists()) {
                try {
                    org.json.JSONArray w = new JSONObject(read(cfg)).optJSONArray("watched");
                    if (w != null && w.length() > 0) actorInput.setText(w.getString(0));
                } catch (Exception ignored) {}
            }
        }
        wChoose.setOnClickListener(v -> {
            if (!new File(getFilesDir(), "RUNNING").exists()) {
                Toast.makeText(this, "Start the bot service first", Toast.LENGTH_LONG).show();
                return;
            }
            File out = new File(getFilesDir(), "witness-chats.json");
            out.delete();
            try { new FileWriter(new File(getFilesDir(), "WITNESS_CHATS_REQUEST")).close(); }
            catch (Exception e) { return; }
            Toast.makeText(this, "Loading chats...", Toast.LENGTH_SHORT).show();
            final long tap = System.currentTimeMillis();
            final Runnable[] poll = new Runnable[1];
            poll[0] = new Runnable() { public void run() {
                if (out.exists() && out.lastModified() >= tap) {
                    try {
                        org.json.JSONArray arr = new org.json.JSONArray(read(out));
                        int n = arr.length();
                        String[] labels = new String[n];
                        boolean[] checked = new boolean[n];
                        final int[] ids = new int[n];
                        for (int i = 0; i < n; i++) {
                            JSONObject c = arr.getJSONObject(i);
                            ids[i] = c.getInt("chat_id");
                            String name = c.optString("name", "");
                            labels[i] = name.isEmpty() ? ("chat " + ids[i]) : name;
                            checked[i] = c.optBoolean("witnessed", false);
                        }
                        new android.app.AlertDialog.Builder(BotActivity.this)
                            .setTitle("Witness these chats")
                            .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                            .setPositiveButton("Save", (d, which) -> {
                                try {
                                    JSONObject cfg = new JSONObject();
                                    org.json.JSONArray chats = new org.json.JSONArray();
                                    for (int i = 0; i < n; i++) if (checked[i]) chats.put(ids[i]);
                                    cfg.put("chats", chats);
                                    org.json.JSONArray watched = new org.json.JSONArray();
                                    String actor = actorInput.getText().toString().trim();
                                    if (!actor.isEmpty()) watched.put(actor);
                                    cfg.put("watched", watched);
                                    FileWriter fw = new FileWriter(new File(getFilesDir(), "witness_config.json"));
                                    fw.write(cfg.toString()); fw.close();
                                    Toast.makeText(BotActivity.this, "Witness selection saved", Toast.LENGTH_SHORT).show();
                                } catch (Exception e) {
                                    Toast.makeText(BotActivity.this, "Save failed", Toast.LENGTH_LONG).show();
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                    } catch (Exception e) {
                        Toast.makeText(BotActivity.this, "Chat list unreadable", Toast.LENGTH_LONG).show();
                    }
                } else if (System.currentTimeMillis() - tap < 10000) {
                    handler.postDelayed(poll[0], 500);
                } else {
                    Toast.makeText(BotActivity.this, "Chat list not ready - is the bot service running?", Toast.LENGTH_LONG).show();
                }
            }};
            handler.postDelayed(poll[0], 500);
        });
        wRefresh.setOnClickListener(v -> {
            if (!new File(getFilesDir(), "RUNNING").exists()) {
                Toast.makeText(this, "Start the bot service first", Toast.LENGTH_LONG).show();
                return;
            }
            // Persist the witnessed-account field (merging the chat selection)
            // so editing it alone takes effect without reopening the chooser.
            try {
                File cfgFile = new File(getFilesDir(), "witness_config.json");
                JSONObject cfg = cfgFile.exists() ? new JSONObject(read(cfgFile)) : new JSONObject();
                org.json.JSONArray watched = new org.json.JSONArray();
                String actor = actorInput.getText().toString().trim();
                if (!actor.isEmpty()) watched.put(actor);
                cfg.put("watched", watched);
                if (!cfg.has("chats")) cfg.put("chats", new org.json.JSONArray());
                FileWriter cfw = new FileWriter(cfgFile); cfw.write(cfg.toString()); cfw.close();
            } catch (Exception ignored) {}
            File out = new File(getFilesDir(), "witness-summary.json");
            out.delete();
            try { new FileWriter(new File(getFilesDir(), "WITNESS_REPORT_REQUEST")).close(); }
            catch (Exception e) { return; }
            Toast.makeText(this, "Preparing the witness report...", Toast.LENGTH_SHORT).show();
            final long tap = System.currentTimeMillis();
            final Runnable[] poll = new Runnable[1];
            poll[0] = new Runnable() { public void run() {
                if (out.exists() && out.lastModified() >= tap) {
                    witnessView.setText(read(out));
                } else if (System.currentTimeMillis() - tap < 10000) {
                    handler.postDelayed(poll[0], 500);
                } else {
                    Toast.makeText(BotActivity.this, "Report not ready - is the bot service running?", Toast.LENGTH_LONG).show();
                }
            }};
            handler.postDelayed(poll[0], 500);
        });
        wDelete.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                .setTitle("Delete witness data?")
                .setMessage("This deletes all recorded observations from this phone. It cannot be undone.")
                .setPositiveButton("Delete", (d, which) -> {
                    try { new FileWriter(new File(getFilesDir(), "WITNESS_RESET_REQUEST")).close(); }
                    catch (Exception e) { return; }
                    new File(getFilesDir(), "witness-summary.json").delete();
                    witnessView.setText("Witness data deletion requested.");
                })
                .setNegativeButton("Cancel", null)
                .show();
        });

        // Setup & account: collapsed once the bot is provisioned.
        setupHeader = sectionHeader("Setup & account");
        setupHeader.setFocusable(true);
        root.addView(setupHeader);
        setupBox = new LinearLayout(this);
        setupBox.setOrientation(LinearLayout.VERTICAL);
        TextView steps = new TextView(this);
        steps.setPadding(pad, 0, pad, 0);
        steps.setText("First time: run the offline probe, create a bot account (one-time, uses network), start the bot, then show the invitation. On this phone, tap 'Open in DeltaChat' in its dialog (or scan the QR from another device). Add the bot to any chat: each group gets its own shared list and each direct chat its own personal list.");
        Button validate = new Button(this); validate.setText("1. Offline probe"); validate.setAllCaps(false);
        Button provision = new Button(this); provision.setText("2. Create bot account"); provision.setAllCaps(false);
        Button importBtn = new Button(this); importBtn.setText("Import bot backup"); importBtn.setAllCaps(false);
        setupBox.addView(steps); setupBox.addView(validate); setupBox.addView(provision); setupBox.addView(importBtn);
        root.addView(setupBox);
        setupHeader.setOnClickListener(v -> toggle(setupHeader, setupBox, "Setup & account"));
        {
            File stf = new File(getFilesDir(), "bot_state.json");
            String js = stf.exists() ? read(stf) : "";
            boolean prov = false;
            try { prov = new JSONObject(js).optBoolean("provisioned", false); } catch (Exception ignored) {}
            if (prov) {
                setupBox.setVisibility(android.view.View.GONE);
                setupHeader.setText("Setup & account  \u25b8");
            } else {
                setupHeader.setText("Setup & account  \u25be");
            }
        }

        // Logs are available on demand and have enough room to read.
        logsHeader = sectionHeader("Logs");
        logsHeader.setText("Logs  \u25b8");
        logsHeader.setFocusable(true);
        root.addView(logsHeader);
        logView = new TextView(this);
        logView.setPadding(pad, 0, pad, 0);
        logView.setTextIsSelectable(true);  // allow copying log lines
        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        int logH = (int) (getResources().getDisplayMetrics().heightPixels * 0.6);
        logScroll.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, logH));
        logScroll.setVisibility(android.view.View.GONE);
        // Nested inside the outer page ScrollView: while the page can still
        // scroll it intercepts every vertical drag, so the log box never
        // receives the gesture. Keep the gesture only while the log can
        // actually scroll that way.
        logScroll.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    lastLogY = ev.getY();
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    float y = ev.getY();
                    float dy = y - lastLogY;   // per-event delta: handles direction reversal
                    lastLogY = y;
                    boolean innerCan = (dy > 0 && v.canScrollVertically(-1)) || (dy < 0 && v.canScrollVertically(1));
                    v.getParent().requestDisallowInterceptTouchEvent(innerCan);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    break;
            }
            return false;
        });
        root.addView(logScroll);
        logsHeader.setOnClickListener(v -> {
            boolean show = logScroll.getVisibility() == android.view.View.GONE;
            toggle(logsHeader, logScroll, "Logs");
            // open at the tail: the freshest lines (start errors, stops) are
            // at the END, so opening at the top can look like nothing changed
            if (show) logScroll.post(() -> logScroll.scrollTo(0, logView.getHeight()));
        });

        ScrollView outer = new ScrollView(this);
        outer.addView(root);
        setContentView(outer);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        validate.setOnClickListener(v -> startMode("validate"));
        provision.setOnClickListener(v -> startMode("provision"));
        live.setOnClickListener(v -> {
            // Battery cost disclosure + exemption prompt before first live start.
            if (Build.VERSION.SDK_INT >= 23) {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    new android.app.AlertDialog.Builder(this)
                        .setTitle("Background battery use")
                        .setMessage("The bot keeps a permanent notification and a low-power connection to the chatmail relay so it can deliver updates at any time. This uses some battery. Android will next ask to exclude this app from battery optimization; stopping the service (Stop button) releases everything.")
                        .setPositiveButton("Continue", (d, w) -> {
                            try {
                                startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        android.net.Uri.parse("package:" + getPackageName())));
                            } catch (Exception ignored) {}
                            startMode("live");
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                    return;
                }
            }
            startMode("live");
        });
        invite.setOnClickListener(v -> {
            File stf = new File(getFilesDir(), "bot_state.json");
            String js = stf.exists() ? read(stf) : "";
            String botAddr = "";
            try { botAddr = new JSONObject(js).optString("bot_addr", ""); } catch (Exception ignored) {}
            if (botAddr.isEmpty() || botAddr.equals("null")) {
                Toast.makeText(this, "Create the bot account first (Setup & account)", Toast.LENGTH_LONG).show();
                return;
            }
            if (!new File(getFilesDir(), "RUNNING").exists()) {
                Toast.makeText(this, "Start the bot service first", Toast.LENGTH_LONG).show();
                return;
            }
            new File(getFilesDir(), "INVITE_DONE").delete();
            try { new FileWriter(new File(getFilesDir(), "INVITE_REQ")).close(); }
            catch (Exception e) { return; }
            Toast.makeText(this, "Preparing the invitation...", Toast.LENGTH_SHORT).show();
            final long tap = System.currentTimeMillis();
            final Runnable[] poll = new Runnable[1];
            poll[0] = new Runnable() { public void run() {
                File done = new File(getFilesDir(), "INVITE_DONE");
                File svg = new File(getFilesDir(), "invite.svg");
                if (done.exists() && svg.exists() && svg.lastModified() >= tap) {
                    WebView w = new WebView(BotActivity.this);
                    w.getSettings().setAllowFileAccess(true);
                    w.loadUrl("file://" + svg.getAbsolutePath());
                    new android.app.AlertDialog.Builder(BotActivity.this)
                        .setTitle("Bot invitation - scan it from your DeltaChat")
                        .setView(w)
                        // Same-device pairing: the QR content IS the deep link
                        // (openpgp4fpr scheme, handled by Delta Chat's exported
                        // RoutingActivity VIEW filter -> checkQr -> SecureJoin).
                        .setPositiveButton("Open in DeltaChat", (d, which) -> {
                            String qr = read(new File(getFilesDir(), "invite.txt")).trim();
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(qr)));
                            } catch (Exception e) {
                                Toast.makeText(BotActivity.this, "Delta Chat app not found - scan the QR instead", Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton("Close", null)
                        .show();
                } else if (System.currentTimeMillis() - tap < 10000) {
                    handler.postDelayed(poll[0], 500);
                } else {
                    Toast.makeText(BotActivity.this, "QR not ready - is the bot service running?", Toast.LENGTH_LONG).show();
                }
            }};
            handler.postDelayed(poll[0], 500);
        });
        importer = new BotImport(this, result -> {
            if (result.getOk()) {
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Bot imported")
                    .setMessage("Imported bot: " + result.getAddr() + "\n\nCheck this is the bot account you meant to import, then start the bot service.")
                    .setPositiveButton("OK", null).show();
            } else {
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Import failed")
                    .setMessage("Nothing was changed. " + result.getError())
                    .setPositiveButton("OK", null).show();
            }
            return kotlin.Unit.INSTANCE;
        });
        importBtn.setOnClickListener(v -> importer.launch());
        stop.setOnClickListener(v -> {
            // Clear the persisted mode first so START_STICKY / the boot
            // receiver treat this as a real stop, not a restart request.
            getSharedPreferences("bot", MODE_PRIVATE).edit().putString("last_mode", "").apply();
            stopService(new Intent(this, BotService.class));
        });
        publish.setOnClickListener(v -> startPublishFlow());
        fetchXdcBtn.setOnClickListener(v -> startFetchFlow());

        // Reattach to a publish that is still in flight across recreation:
        // the request or its crash-resume checkpoint must still exist, and
        // the persisted token/sha let the result poll correlate without
        // re-picking or re-queueing the file.
        android.content.SharedPreferences prefs = getSharedPreferences("bot", MODE_PRIVATE);
        String savedToken = prefs.getString("pending_publish_token", null);
        if (savedToken != null
                && (new File(getFilesDir(), "PUBLISH_REQ").exists()
                    || new File(getFilesDir(), "PUBLISH_PROG").exists())) {
            pendingPublishToken = savedToken;
            pendingPublishSha = prefs.getString("pending_publish_sha", null);
            pendingPublishName = prefs.getString("pending_publish_name", null);
            Toast.makeText(this, "A publish is still running - reattached to its result", Toast.LENGTH_SHORT).show();
            pollPublishResult(System.currentTimeMillis());
        } else if (savedToken != null) {
            // Nothing in flight: drop the stale reference. A completed
            // run's DONE stays as the runtime's dedup input, untouched.
            prefs.edit().remove("pending_publish_sha").remove("pending_publish_token")
                .remove("pending_publish_name").apply();
        }

        Runnable tick = new Runnable() { public void run() {
            refresh();
            handler.postDelayed(this, 1500);
        }};
        handler.post(tick);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_XDC) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            try {
                pendingPublishName = displayNameOf(data.getData());
                queuePublish(getContentResolver().openInputStream(data.getData()));
                final long tap = System.currentTimeMillis();
                Toast.makeText(this, "Publishing to all served chats...", Toast.LENGTH_SHORT).show();
                pollPublishResult(tap);
            } catch (Exception e) {
                Toast.makeText(this, "Could not read the picked file: " + e, Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (importer != null) importer.handleActivityResult(requestCode, resultCode, data);
    }

    private void startMode(String mode) {
        Intent i = new Intent(this, BotService.class);
        i.putExtra("mode", mode);
        startForegroundService(i);
    }

    private void refresh() {
        logView.setText(readLog());
        File f = new File(getFilesDir(), "bot_state.json");
        String json = f.exists() ? read(f) : "";
        boolean running = new File(getFilesDir(), "RUNNING").exists();
        if (json.equals(lastStateJson) && running == lastRunning) return;
        lastStateJson = json;
        lastRunning = running;
        String botAddr = "";
        int chats = 0;
        if (!json.isEmpty()) {
            try {
                JSONObject st = new JSONObject(json);
                botAddr = st.optString("bot_addr", "");
                JSONObject c = st.optJSONObject("chats");
                if (c != null) chats = c.length();
            } catch (Exception ignored) {}
        }
        statusView.setText((botAddr.isEmpty() || botAddr.equals("null") ? "No bot account yet" : "Bot: " + botAddr)
                + "\nService: " + (running ? "running" : "stopped") + "  ·  Chats served: " + chats);
    }

    private boolean lastRunning = false;

    private TextView sectionHeader(String title) {
        TextView h = new TextView(this);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        h.setTextSize(16);
        h.setPadding(pad, pad, pad, pad);
        h.setText(title);
        return h;
    }

    private void toggle(TextView header, android.view.View box, String title) {
        boolean show = box.getVisibility() == android.view.View.GONE;
        box.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
        header.setText(title + (show ? "  \u25be" : "  \u25b8"));
    }

    // ---- publish flow (app-side only: the runtime refuses chat-originated publishing) ----
    private static final int REQ_PICK_XDC = 43;
    private String pendingPublishSha = null;
    private String pendingPublishToken = null;
    private String pendingPublishName = null;

    private void startPublishFlow() {
        File stf = new File(getFilesDir(), "bot_state.json");
        String js = stf.exists() ? read(stf) : "";
        boolean prov = false;
        try { prov = new JSONObject(js).optBoolean("provisioned", false); } catch (Exception ignored) {}
        if (!prov) {
            Toast.makeText(this, "Create the bot account first (Setup & account)", Toast.LENGTH_LONG).show();
            return;
        }
        if (!new File(getFilesDir(), "RUNNING").exists()) {
            Toast.makeText(this, "Start the bot service first", Toast.LENGTH_LONG).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle("Publish new app version")
            .setMessage("Pick the new .xdc file, or use Get latest app. The bot sends it to every chat it serves.")
            .setPositiveButton("Pick .xdc", (d, w) -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_PICK_XDC);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // Manual-tap fetch of the newest .xdc from the pinned GitHub repo, then a
    // deliberate Publish tap. Nothing polls in the background and nothing is
    // published automatically. The progress line is wired to the actual download
    // listener: resumed bytes appear before the network starts, a percent only
    // when the server stated a length, and every terminal path re-enables the
    // button. fetchGen drops late callbacks from a previous attempt.
    private void startFetchFlow() {
        if (fetchRunning) return;
        fetchRunning = true;
        final int gen = ++fetchGen;
        fetchXdcBtn.setEnabled(false);
        fetchStatus.setVisibility(android.view.View.VISIBLE);
        fetchStatus.setText("Checking GitHub for the latest app version...");
        fetchBar.setIndeterminate(true); // until the server states a length
        fetchBar.setProgress(0);
        fetchBar.setVisibility(android.view.View.VISIBLE);
        android.os.Handler main = new android.os.Handler(getMainLooper());
        com.instinct.widget.ProgressPump pump = new com.instinct.widget.ProgressPump(main,
                (done, total) -> { // already on the main thread (pump guarantees it)
                    if (gen != fetchGen || !fetchRunning) return; // late event
                    fetchBar.setVisibility(android.view.View.VISIBLE);
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        fetchBar.setIndeterminate(false);
                        fetchBar.setProgress(pct);
                        fetchStatus.setText("Fetching app: " + kbText(done) + " / " + kbText(total)
                                + " (" + pct + "%)");
                    } else {
                        // honest unknown total: indeterminate bar + bytes, never a fake percent
                        fetchBar.setIndeterminate(true);
                        fetchStatus.setText("Fetching app: " + kbText(done));
                    }
                });
        new Thread(() -> {
            final XdcFetch.Found f;
            final String version;
            final File out;
            try {
                f = XdcFetch.fetchLatest();
                File dir = new File(getFilesDir(), "fetched-xdc");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                out = new File(dir, f.name);
                version = XdcFetch.downloadTo(dir, f, out, pump::offer);
            } catch (final XdcFetch.FetchException fe) {
                runOnUiThread(() -> {
                    if (gen != fetchGen) return;
                    fetchRunning = false;
                    fetchXdcBtn.setEnabled(true);
                    fetchStatus.setVisibility(android.view.View.GONE);
                    fetchBar.setVisibility(android.view.View.GONE); // failure: dialog explains, no fake final state
                    new android.app.AlertDialog.Builder(this)
                        .setTitle("Could not get the app version")
                        .setMessage(fe.getMessage())
                        .setPositiveButton("OK", null).show();
                });
                return;
            }
            runOnUiThread(() -> {
                if (gen != fetchGen) return;
                fetchRunning = false;
                fetchXdcBtn.setEnabled(true);
                fetchBar.setIndeterminate(false);
                fetchBar.setProgress(100); // terminal success retains the full bar
                fetchStatus.setText("Fetched " + f.name);
                new android.app.AlertDialog.Builder(this)
                .setTitle("App version ready")
                .setMessage("Fetched " + f.name + " (release " + f.tag + ", app version " + version + ").\n\n"
                    + "Publish it to EVERY chat the bot serves now?")
                .setPositiveButton("Publish", (d, w) -> {
                    File stf = new File(getFilesDir(), "bot_state.json");
                    String js = stf.exists() ? read(stf) : "";
                    boolean prov = false;
                    try { prov = new JSONObject(js).optBoolean("provisioned", false); } catch (Exception ignored) {}
                    if (!prov) {
                        Toast.makeText(this, "Create the bot account first (Setup & account)", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!new File(getFilesDir(), "RUNNING").exists()) {
                        Toast.makeText(this, "Start the bot service first", Toast.LENGTH_LONG).show();
                        return;
                    }
                    try {
                        pendingPublishName = f.name;
                        queuePublish(new FileInputStream(out));
                    } catch (Exception e) {
                        Toast.makeText(this, "Could not read the fetched file", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Not now", (d, w) -> out.delete())
                .show();
            });
        }).start();
    }

    /** THE production publish-result composition. The dialog shows exactly
     *  what this returns, and PublishDialogRenderTest asserts the rendered
     *  dialog text equals this method's output, so the rendered pixels are
     *  the real production wording, never a duplicated test copy. */
    static String composePublishSummary(JSONObject r, String publishName) {
        JSONObject pub = r.optJSONObject("published");
        JSONObject skip = r.optJSONObject("skipped");
        JSONObject errs = r.optJSONObject("errors");
        JSONObject part = r.optJSONObject("partial");
        int nPub = pub == null ? 0 : pub.length();
        int nSkip = skip == null ? 0 : skip.length();
        int nPart = part == null ? 0 : part.length();
        int nAdopt = 0, nHist = 0;
        if (pub != null) {
            java.util.Iterator<String> ait = pub.keys();
            while (ait.hasNext()) {
                JSONObject ao = pub.optJSONObject(ait.next());
                if (ao != null && ao.optBoolean("adopted", false)) nAdopt++;
                if (ao != null && ao.optBoolean("ingest_pending", false)) nHist++;
            }
        }
        StringBuilder msg = new StringBuilder();
        if (nPart > 0) {
            // Delivered count and fully-ready count stay DISTINCT: a partial
            // run DID deliver the bubble, it just did not finish the replay.
            // Never open with "Published to 0" for a delivered app.
            msg.append("Delivered to ").append(nPub + nPart)
               .append(" chat(s); fully ready in ").append(nPub).append(".");
        } else {
            msg.append("Published to ").append(nPub).append(" chat(s).");
        }
        if (nSkip > 0) msg.append(" Already had this exact file: ").append(nSkip).append(".");
        if (nAdopt > 0) msg.append(" Recovered the already-delivered app bubble without sending a duplicate: ").append(nAdopt).append(".");
        if (nHist > 0) msg.append(" Earlier history recovery is pending in ").append(nHist).append(" chats.");
        if (part != null && part.length() > 0) {
            // Third state: the app WAS delivered (bubble sent + registered)
            // but the history replay failed. Retrying the SAME file completes
            // the replay into the existing instance - no duplicate bubble.
            msg.append("\n\nDelivered but history replay failed in ")
               .append(part.length())
               .append(" chat(s); retry the SAME file to finish:");
            java.util.Iterator<String> pit = part.keys();
            while (pit.hasNext()) {
                String pk = pit.next();
                JSONObject po = part.optJSONObject(pk);
                msg.append("\nchat ").append(pk).append(": ")
                   .append(po == null ? "" : po.optString("error"));
            }
        }
        if (errs != null && errs.length() > 0) {
            StringBuilder raw = new StringBuilder();
            String lead = null;
            java.util.Iterator<String> it = errs.keys();
            while (it.hasNext()) {
                String k = it.next();
                String e = errs.optString(k);
                raw.append("\nchat ").append(k).append(": ").append(e);
                if (lead == null) lead = PublishErrors.friendlyLead(e, publishName);
            }
            if (lead != null) {
                msg.append("\n\n").append(lead)
                   .append("\n\nTechnical details:").append(raw);
            } else {
                msg.append("\n\nErrors:").append(raw);
            }
        }
        return msg.toString();
    }

    private void pollPublishResult(final long tap) {
        final Runnable[] poll = new Runnable[1];
        poll[0] = new Runnable() { public void run() {
            File done = new File(getFilesDir(), "PUBLISH_DONE");
            if (done.exists()) {
                try {
                    JSONObject r = new JSONObject(read(done));
                    // Correlate the result with THIS request: the request
                    // token is echoed back by the runtime, so a DONE from an
                    // earlier publish never shows as this one's result.
                    String doneReq = r.optString("request", "");
                    if (pendingPublishToken == null || !pendingPublishToken.equals(doneReq)) {
                        if (System.currentTimeMillis() - tap < 60000) handler.postDelayed(poll[0], 1000);
                        return;
                    }
                    String doneSha = r.optString("sha256", "");
                    if (pendingPublishSha != null && !doneSha.isEmpty()
                            && !pendingPublishSha.equals(doneSha)) {
                        if (System.currentTimeMillis() - tap < 60000) handler.postDelayed(poll[0], 1000);
                        return;
                    }
                    StringBuilder msg = new StringBuilder(
                            composePublishSummary(r, pendingPublishName));
                    getSharedPreferences("bot", MODE_PRIVATE).edit()
                        .remove("pending_publish_sha")
                        .remove("pending_publish_token")
                        .remove("pending_publish_name")
                        .apply();
                    new android.app.AlertDialog.Builder(BotActivity.this)
                        .setTitle("Publish result")
                        .setMessage(msg.toString())
                        .setPositiveButton("OK", null)
                        .show();
                } catch (Exception e) {
                    Toast.makeText(BotActivity.this, "Publish result unreadable: " + e, Toast.LENGTH_LONG).show();
                }
                return;
            }
            if (System.currentTimeMillis() - tap < 60000) {
                handler.postDelayed(poll[0], 1000);
            } else {
                Toast.makeText(BotActivity.this, "Publish timed out - is the bot service running?", Toast.LENGTH_LONG).show();
            }
        }};
        handler.postDelayed(poll[0], 1000);
    }

    // The picker's own display name, for plain-language result messages.
    private String displayNameOf(android.net.Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        return null;
    }

    // No network/core calls: the runtime watches the request file.
    void queuePublish(InputStream input) throws Exception {
        File req = new File(getFilesDir(), "PUBLISH_REQ");
        File prog = new File(getFilesDir(), "PUBLISH_PROG");
        if (req.exists() || prog.exists()) {
            input.close();
            throw new java.io.IOException("A publish is still pending; wait for it or restart the bot to resume");
        }
        File stage = new File(getFilesDir(), "publish-" + java.util.UUID.randomUUID() + ".tmp");
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = input; FileOutputStream out = new FileOutputStream(stage)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) { md.update(buf, 0, n); out.write(buf, 0, n); }
            out.getFD().sync();
        } catch (Exception e) { stage.delete(); throw e; }
        StringBuilder hex = new StringBuilder();
        for (byte bb : md.digest()) hex.append(String.format("%02x", bb));
        pendingPublishSha = hex.toString();
        pendingPublishToken = java.util.UUID.randomUUID().toString();
        File immutable = new File(getFilesDir(), "publish-" + pendingPublishSha + ".xdc");
        if (immutable.exists()) { stage.delete(); }
        else if (!stage.renameTo(immutable)) throw new java.io.IOException("Could not retain publish file");
        // Never overwrite an older current_app's bytes, nor delete DONE's dedup input.
        File tmp = new File(getFilesDir(), "PUBLISH_REQ.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write((immutable.getName() + "\n" + pendingPublishToken).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(req)) throw new java.io.IOException("Could not queue publish request");
        // Persist the pending reference privately: if the activity is
        // recreated while the bot is still working, the new instance
        // reattaches to THIS operation instead of losing correlation.
        getSharedPreferences("bot", MODE_PRIVATE).edit()
            .putString("pending_publish_sha", pendingPublishSha)
            .putString("pending_publish_token", pendingPublishToken)
            .putString("pending_publish_name", pendingPublishName)
            .apply();
    }

    private static String read(File f) {
        try {
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(buf); in.close();
            return n > 0 ? new String(buf, 0, n) : "";
        } catch (Exception e) { return ""; }
    }

    private static String kbText(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private String readLog() {
        StringBuilder sb = new StringBuilder();
        for (String name : new String[]{"bot.log", "validation.log"}) {
            try {
                File f = new File(getFilesDir(), name);
                if (f.exists()) {
                    String s = read(f);
                    int cut = Math.max(0, s.length() - 4000);
                    if (cut > 0) {
                        int nextLine = s.indexOf('\n', cut);
                        cut = nextLine >= 0 ? nextLine + 1 : s.length();
                    }
                    sb.append("== ").append(name).append(" ==\n").append(s.substring(cut)).append("\n\n");
                }
            } catch (Exception ignored) {}
        }
        return sb.length() == 0 ? "(nothing yet)" : sb.toString();
    }
}
