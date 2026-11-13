package com.instinct.widget.bot;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.webkit.WebView;
import android.widget.Button;
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
    private LinearLayout candBox;
    private final Handler handler = new Handler();
    private String lastStateJson = "";
    private BotImport importer;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        Button validate = new Button(this); validate.setText("1. Offline validation probe (no network)");
        Button provision = new Button(this); provision.setText("2. Create bot account (ONE-TIME, uses network)");
        Button importBtn = new Button(this); importBtn.setText("Import bot backup (moves the bot from the old test app)");
        Button live = new Button(this); live.setText("3. Start/restart bot service (network; permanent notification, uses battery)");
        Button invite = new Button(this); invite.setText("4. Show invitation QR (scan it from your DeltaChat)");
        Button stop = new Button(this); stop.setText("Stop service");
        Button publish = new Button(this); publish.setText("5. Publish new app version (.xdc) to all chats");
        Button fetchXdc = new Button(this); fetchXdc.setText("6. Get latest app version (.xdc) from GitHub");
        candBox = new LinearLayout(this); candBox.setOrientation(LinearLayout.VERTICAL);
        logView = new TextView(this);
        TextView steps = new TextView(this);
        steps.setText("Setup: 1 probe, 2 create account, 3 start bot, 4 show the invitation - on this same phone tap 'Open in DeltaChat' in its dialog (or scan the QR from another device). Add the bot to any chat: each group gets its own shared list and each direct chat its own personal list, kept separate. Button 5 publishes a new .xdc from this app to every chat the bot serves; button 6 downloads the newest .xdc from GitHub so you do not have to pick a file yourself.");
        // One scroll container for everything: the setup text and buttons
        // scroll out of the way so the log can fill the screen (user request:
        // screenshot more of the log). All controls preserved.
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(steps);
        content.addView(validate); content.addView(provision); content.addView(importBtn); content.addView(live); content.addView(invite); content.addView(stop);
        content.addView(publish);
        content.addView(fetchXdc);
        content.addView(candBox); content.addView(logView);
        ScrollView sv = new ScrollView(this); sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

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
                Toast.makeText(this, "Create the bot account first (button 2)", Toast.LENGTH_LONG).show();
                return;
            }
            if (!new File(getFilesDir(), "RUNNING").exists()) {
                Toast.makeText(this, "Start the bot service first (button 3)", Toast.LENGTH_LONG).show();
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
                    .setMessage("Imported bot: " + result.getAddr() + "\n\nCheck this matches the address in the old app, then start the service (button 3). Uninstall the old app only after the bot is live here.")
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
        fetchXdc.setOnClickListener(v -> startFetchFlow());

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
        File f = new File(getFilesDir(), "bot_state.json");
        String json = f.exists() ? read(f) : "";
        logView.setText(readLog());
        if (json.equals(lastStateJson)) return;
        lastStateJson = json;
        candBox.removeAllViews();
        if (json.isEmpty()) return;
        try {
            JSONObject st = new JSONObject(json);
            String botAddr = st.optString("bot_addr", "");
            TextView t = new TextView(this);
            if (!botAddr.isEmpty()) t.setText("bot: " + botAddr + "   ");
            t.setText(t.getText() + "chats served: " + st.optString("chats", "{}"));
            candBox.addView(t);
        } catch (Exception ignored) {}
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
            Toast.makeText(this, "Create the bot account first (button 2)", Toast.LENGTH_LONG).show();
            return;
        }
        if (!new File(getFilesDir(), "RUNNING").exists()) {
            Toast.makeText(this, "Start the bot service first (button 3)", Toast.LENGTH_LONG).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle("Publish new app version")
            .setMessage("Pick the new .xdc file (or use button 6 to fetch the latest from GitHub). The bot sends it to EVERY chat it serves (each group and each direct chat with the bot).")
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
    // published automatically.
    private void startFetchFlow() {
        Toast.makeText(this, "Checking GitHub for the latest app version...", Toast.LENGTH_SHORT).show();
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
                version = XdcFetch.downloadTo(dir, f, out);
            } catch (final XdcFetch.FetchException fe) {
                runOnUiThread(() -> new android.app.AlertDialog.Builder(this)
                    .setTitle("Could not get the app version")
                    .setMessage(fe.getMessage())
                    .setPositiveButton("OK", null).show());
                return;
            }
            runOnUiThread(() -> new android.app.AlertDialog.Builder(this)
                .setTitle("App version ready")
                .setMessage("Fetched " + f.name + " (release " + f.tag + ", app version " + version + ").\n\n"
                    + "Publish it to EVERY chat the bot serves now?")
                .setPositiveButton("Publish", (d, w) -> {
                    File stf = new File(getFilesDir(), "bot_state.json");
                    String js = stf.exists() ? read(stf) : "";
                    boolean prov = false;
                    try { prov = new JSONObject(js).optBoolean("provisioned", false); } catch (Exception ignored) {}
                    if (!prov) {
                        Toast.makeText(this, "Create the bot account first (button 2)", Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!new File(getFilesDir(), "RUNNING").exists()) {
                        Toast.makeText(this, "Start the bot service first (button 3)", Toast.LENGTH_LONG).show();
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
                .show());
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

    private String readLog() {
        StringBuilder sb = new StringBuilder();
        for (String name : new String[]{"bot.log", "validation.log"}) {
            try {
                File f = new File(getFilesDir(), name);
                if (f.exists()) {
                    String s = read(f);
                    int cut = Math.max(0, s.length() - 4000);
                    sb.append("== ").append(name).append(" ==\n").append(s.substring(cut)).append("\n\n");
                }
            } catch (Exception ignored) {}
        }
        return sb.length() == 0 ? "(nothing yet)" : sb.toString();
    }
}
