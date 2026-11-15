package com.instinct.widget.bot;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
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
    private final Handler handler = new Handler();
    private String lastStateJson = null;
    private BotImport importer;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Roll back (or finish) an import that a crash left halfway.
        BotImport.recoverInterruptedImport(this);
        // A crash can also hit AFTER a completed export wrote its result
        // but before the UI presented the transfer code / save step. Once
        // recovery is done, re-present that result instead of dropping a
        // finished, recoverable export.
        resumeFinishedExportWhenReady();
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
        // Nested inside the outer page ScrollView: while the page can still
        // scroll it intercepts every vertical drag, so the log box never
        // receives the gesture. Keep the gesture only while the log can
        // actually scroll that way. This must run in dispatchTouchEvent:
        // the selectable log TextView consumes ACTION_DOWN, so neither an
        // OnTouchListener nor onInterceptTouchEvent reliably sees the start
        // of the gesture.
        logScroll = new ScrollView(this) {
            private float lastY = -1f;

            @Override
            public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        lastY = ev.getY();
                        getParent().requestDisallowInterceptTouchEvent(true);
                        break;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (lastY >= 0f) {
                            float y = ev.getY();
                            float dy = y - lastY;   // per-event delta: handles direction reversal
                            lastY = y;
                            boolean canScroll = (dy > 0 && canScrollVertically(-1))
                                    || (dy < 0 && canScrollVertically(1));
                            getParent().requestDisallowInterceptTouchEvent(canScroll);
                        }
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        lastY = -1f;
                        getParent().requestDisallowInterceptTouchEvent(false);
                        break;
                }
                return super.dispatchTouchEvent(ev);
            }

            // The selectable text asks ancestors to keep the gesture while a
            // selection is being dragged: honor that, or the nested scroll
            // handoff steals selection drags. Outside an active selection the
            // request stays ignored - vertical drags in the log box belong to
            // the dispatchTouchEvent handoff above.
            @Override
            public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
                if (disallowIntercept && logView != null && logView.hasSelection()) {
                    super.requestDisallowInterceptTouchEvent(true);
                }
            }
        };
        logScroll.addView(logView);
        int logH = (int) (getResources().getDisplayMetrics().heightPixels * 0.6);
        logScroll.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, logH));
        logScroll.setVisibility(android.view.View.GONE);
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
        validate.setOnClickListener(v -> { if (recoveryPending()) return; startMode("validate"); });
        provision.setOnClickListener(v -> { if (recoveryPending()) return; startMode("provision"); });
        live.setOnClickListener(v -> {
            if (recoveryPending()) return;
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
                String settingsNote = result.getPrefsWarning() == null
                    ? "\n\nYour settings (feed pairing, cached feed, canvas layout) came with it."
                    : "\n\nThe bot is in, but settings transfer had a problem: " + result.getPrefsWarning();
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Bot imported")
                    .setMessage("Imported bot: " + result.getAddr() + settingsNote + "\n\nCheck this is the bot account you meant to import, then start the bot service.")
                    .setPositiveButton("OK", null).show();
            } else {
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Import failed")
                    .setMessage("Nothing was changed. " + result.getError())
                    .setPositiveButton("OK", null).show();
            }
            return kotlin.Unit.INSTANCE;
        });
        importBtn.setOnClickListener(v -> { if (recoveryPending()) return; importer.launch(); });

        Button exportBtn = new Button(this); exportBtn.setText("Export bot + settings"); exportBtn.setAllCaps(false);
        setupBox.addView(exportBtn);
        exportBtn.setOnClickListener(v -> { if (recoveryPending()) return; startExportFlow(); });
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
        if (requestCode == REQ_SAVE_BUNDLE) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null || pendingBundlePath == null) {
                Toast.makeText(this, "Bundle not saved - export result kept, you can save again", Toast.LENGTH_LONG).show();
                return;
            }
            try {
                InputStream in = new FileInputStream(pendingBundlePath);
                FileOutputStream out = (FileOutputStream) getContentResolver().openOutputStream(data.getData());
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close(); in.close();
                new File(getFilesDir(), "EXPORT_RESULT").delete();
                deleteRecursively(new File(getFilesDir(), "export-out"));
                pendingBundlePath = null;
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Bundle saved")
                    .setMessage("In the new app: choose 'Import bot backup', pick this file, enter the transfer code.\n\nCarried over: the bot account, its chats, your feed pairing, the cached feed and the canvas layout. Not carried: the home-screen widget itself (Android keeps that with this app - add the widget once from the new app; it will show your feed right away).\n\nThe live bot here stays PAUSED. Only uninstall this app after the new app shows the bot running with the same address. Start bot resumes this bot if you abort the migration.")
                    .setPositiveButton("OK", null)
                    .show();
            } catch (Exception e) {
                Toast.makeText(this, "Save failed: " + e, Toast.LENGTH_LONG).show();
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

    /** Fail-closed gate: while crash recovery is running (or has failed),
     * no control that could race it - service start, import, export - may
     * proceed. */
    private boolean recoveryPending() {
        if (BotImport.isRecoveryComplete()) return false;
        Toast.makeText(this, "Finishing cleanup of the previous session - try again in a moment (if this stays, restart the app once)", Toast.LENGTH_LONG).show();
        return true;
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursively(k);
        }
        f.delete();
    }

    // ---- export flow (move this install's bot + settings to the new app package) ----
    private static final int REQ_SAVE_BUNDLE = 45;
    private String pendingBundlePath = null;

    /** Snapshot of the allowlisted app settings for the migration bundle.
     * Written just before the export service mode starts; migration.py
     * wraps it into the bundle and deletes this plaintext copy. */
    private void writeExportPrefsSnapshot() throws Exception {
        android.content.SharedPreferences w = getSharedPreferences("instinct_widget", MODE_PRIVATE);
        JSONObject iw = new JSONObject();
        for (String k : new String[]{"priv", "pub", "feed", "feed_gen", "feed_url", "status"}) {
            String v = w.getString(k, null);
            if (v != null) iw.put(k, v);
        }
        long feedAt = w.getLong("feed_at", 0);
        if (feedAt != 0) iw.put("feed_at", feedAt);
        JSONObject root = new JSONObject();
        if (iw.length() > 0) root.put("instinct_widget", iw);
        String layout = getSharedPreferences("review_canvas", MODE_PRIVATE).getString("layout", null);
        if (layout != null) {
            JSONObject rc = new JSONObject();
            rc.put("layout", layout);
            root.put("review_canvas", rc);
        }
        File tmp = new File(getFilesDir(), "EXPORT_PREFS.json.tmp");
        FileWriter fw = new FileWriter(tmp);
        fw.write(root.toString());
        fw.close();
        if (!tmp.renameTo(new File(getFilesDir(), "EXPORT_PREFS.json"))) {
            throw new IllegalStateException("could not stage settings snapshot");
        }
    }

    private void startExportFlow() {
        File stf = new File(getFilesDir(), "bot_state.json");
        String js = stf.exists() ? read(stf) : "";
        boolean prov = false;
        try { prov = new JSONObject(js).optBoolean("provisioned", false); } catch (Exception ignored) {}
        if (!prov) {
            Toast.makeText(this, "Create the bot account first", Toast.LENGTH_LONG).show();
            return;
        }
        // An in-flight publish cannot ride the bundle (its checkpoint files
        // stay behind); refuse instead of silently dropping it.
        String pendingPublish = getSharedPreferences("bot", MODE_PRIVATE)
            .getString("pending_publish_token", null);
        if (pendingPublish != null
                && (new File(getFilesDir(), "PUBLISH_REQ").exists()
                    || new File(getFilesDir(), "PUBLISH_PROG").exists())) {
            new android.app.AlertDialog.Builder(this)
                .setTitle("Publish still in flight")
                .setMessage("A publish operation is still running. Let it finish (or tap Stop bot and publish again from the new app) before exporting.")
                .setPositiveButton("OK", null)
                .show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle("Export bot + settings")
            .setMessage("This pauses the live bot and writes ONE protected bundle holding the bot's Delta Chat account, its owner/chat state AND your app settings (feed pairing keys, cached feed, canvas layout), all encrypted with a one-time transfer code shown next.\n\nKeep the bundle file and the code private: together they ARE the bot. You pick where the file goes and copy the code yourself; nothing is sent anywhere.\n\nThe live bot stays paused until the import finishes in the new app (or resume it here with Start bot).")
            .setPositiveButton("Export", (d, w) -> runExportAfterStop())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void runExportAfterStop() {
        getSharedPreferences("bot", MODE_PRIVATE).edit().putString("last_mode", "").apply();
        stopService(new Intent(this, BotService.class));
        Toast.makeText(this, "Pausing the live bot...", Toast.LENGTH_SHORT).show();
        final long t0 = System.currentTimeMillis();
        final Runnable[] poll = new Runnable[1];
        poll[0] = new Runnable() { public void run() {
            if (!new File(getFilesDir(), "RUNNING").exists()) {
                try {
                    writeExportPrefsSnapshot();
                } catch (Exception e) {
                    Toast.makeText(BotActivity.this, "Could not snapshot settings: " + e + " - bot left stopped", Toast.LENGTH_LONG).show();
                    return;
                }
                new File(getFilesDir(), "EXPORT_RESULT").delete();
                startMode("export");
                pollExportResult(System.currentTimeMillis());
            } else if (System.currentTimeMillis() - t0 < 15000) {
                handler.postDelayed(poll[0], 500);
            } else {
                Toast.makeText(BotActivity.this, "Live bot did not stop in time - tap Stop bot, then retry", Toast.LENGTH_LONG).show();
            }
        }};
        handler.postDelayed(poll[0], 500);
    }

    /** Re-present a finished export whose UI never ran (process death
     * after EXPORT_RESULT landed). Waits for crash recovery to finish
     * first so it can never race it; same handling as the live poll. */
    private void resumeFinishedExportWhenReady() {
        final long t0 = System.currentTimeMillis();
        final Runnable[] wait = new Runnable[1];
        wait[0] = new Runnable() { public void run() {
            if (!BotImport.isRecoveryComplete()) {
                if (System.currentTimeMillis() - t0 < 15000) {
                    handler.postDelayed(wait[0], 300);
                }   // else: recovery FAILED -> controls stay locked anyway
                return;
            }
            File res = new File(getFilesDir(), "EXPORT_RESULT");
            if (!res.exists() || pendingBundlePath != null) return;
            try {
                JSONObject r = new JSONObject(read(res));
                String err = r.optString("error", "");
                if (!err.isEmpty()) {
                    res.delete();
                    new android.app.AlertDialog.Builder(BotActivity.this)
                        .setTitle("Export failed")
                        .setMessage(err + "\n\nThe live bot will restart.")
                        .setPositiveButton("OK", (d, w) -> startMode("live"))
                        .show();
                } else {
                    Toast.makeText(BotActivity.this,
                        "Resuming your finished export", Toast.LENGTH_SHORT).show();
                    pendingBundlePath = r.getString("bundle");
                    showTransferCode(r.getString("code"));
                }
            } catch (Exception e) {
                // Unreadable result: keep it; nothing is lost silently.
                Toast.makeText(BotActivity.this,
                    "Export result unreadable: " + e, Toast.LENGTH_LONG).show();
            }
        }};
        handler.postDelayed(wait[0], 300);
    }

    private void pollExportResult(final long t0) {
        final Runnable[] poll = new Runnable[1];
        poll[0] = new Runnable() { public void run() {
            File res = new File(getFilesDir(), "EXPORT_RESULT");
            if (res.exists()) {
                // Export done: leave no persisted mode so a START_STICKY
                // restart does not silently re-run the export.
                getSharedPreferences("bot", MODE_PRIVATE).edit().putString("last_mode", "").apply();
                try {
                    JSONObject r = new JSONObject(read(res));
                    String err = r.optString("error", "");
                    if (!err.isEmpty()) {
                        res.delete();
                        new android.app.AlertDialog.Builder(BotActivity.this)
                            .setTitle("Export failed")
                            .setMessage(err + "\n\nThe live bot will restart.")
                            .setPositiveButton("OK", (d, w) -> startMode("live"))
                            .show();
                    } else {
                        pendingBundlePath = r.getString("bundle");
                        showTransferCode(r.getString("code"));
                    }
                } catch (Exception e) {
                    Toast.makeText(BotActivity.this, "Export result unreadable: " + e, Toast.LENGTH_LONG).show();
                }
                return;
            }
            if (System.currentTimeMillis() - t0 < 120000) {
                handler.postDelayed(poll[0], 1000);
            } else {
                // Leave no secret-bearing export files behind on timeout.
                new File(getFilesDir(), "EXPORT_RESULT").delete();
                new File(getFilesDir(), "EXPORT_PREFS.json").delete();
                deleteRecursively(new File(getFilesDir(), "export-out"));
                Toast.makeText(BotActivity.this, "Export timed out - resuming the live bot", Toast.LENGTH_LONG).show();
                startMode("live");
            }
        }};
        handler.postDelayed(poll[0], 1000);
    }

    private void showTransferCode(final String code) {
        TextView tv = new TextView(this);
        tv.setText(code);
        tv.setTextIsSelectable(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        new android.app.AlertDialog.Builder(this)
            .setTitle("1/2: one-time transfer code")
            .setMessage("Enter this code in the new app's import screen. It protects the bot's whole identity - never send it through a chat.")
            .setView(tv)
            .setNeutralButton("Copy code", (d, w) -> { copyCode(code); showTransferCode(code); })
            .setPositiveButton("Next", (d, w) -> showSaveStep())
            .setNegativeButton("Abort + resume bot", (d, w) -> abortExport())
            .show();
    }

    private void showSaveStep() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("2/2: save the bundle file")
            .setMessage("Choose where the .dcmig bundle is written (Downloads is fine). You will pick this file in the new app's import screen.")
            .setPositiveButton("Choose location", (d, w) -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                i.setType("application/octet-stream");
                i.putExtra(Intent.EXTRA_TITLE, "intodo-migration.dcmig");
                startActivityForResult(i, REQ_SAVE_BUNDLE);
            })
            .setNegativeButton("Abort + resume bot", (d, w) -> abortExport())
            .show();
    }

    private void copyCode(String code) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("bot migration code", code);
        if (Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle b = new android.os.PersistableBundle();
            b.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(b);
        }
        cm.setPrimaryClip(clip);
        Toast.makeText(this, "Code copied (marked sensitive)", Toast.LENGTH_SHORT).show();
    }

    private void abortExport() {
        new File(getFilesDir(), "EXPORT_RESULT").delete();
        new File(getFilesDir(), "EXPORT_PREFS.json").delete();
        deleteRecursively(new File(getFilesDir(), "export-out"));
        startMode("live");
    }

    private void refresh() {
        // setText clears any in-progress selection, so only push the log into
        // the view when it actually changed, and never while a selection is
        // active (the user may be copying it).
        String log = readLog();
        if (!log.contentEquals(logView.getText()) && !logView.hasSelection()) {
            logView.setText(log);
        }
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
