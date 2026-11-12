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
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import org.json.JSONArray;
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
        root.setBackgroundColor(0xFF101828); // match the app theme's dark home background
        Button validate = new Button(this); validate.setText("1. Offline validation probe (no network)");
        Button provision = new Button(this); provision.setText("2. Create bot account (ONE-TIME, uses network)");
        Button live = new Button(this); live.setText("3. Start/restart bot service (network; permanent notification, uses battery)");
        Button invite = new Button(this); invite.setText("4. Show invitation QR (scan it from your DeltaChat)");
        Button stop = new Button(this); stop.setText("Stop service");
        candBox = new LinearLayout(this); candBox.setOrientation(LinearLayout.VERTICAL);
        logView = new TextView(this);
        ScrollView sv = new ScrollView(this); sv.addView(logView);
        TextView steps = new TextView(this);
        steps.setText("Setup: 1 probe, 2 create account, 3 start bot, 4 show the invitation - on this same phone tap 'Open in DeltaChat' in its dialog (or scan the QR from another device), then send the bot any message (it will not reply yet). Come back here and confirm the owner below. Add the bot to your shared chat; it serves chats it is added to. DM the bot a new .xdc to publish.");
        Button importBtn = new Button(this); importBtn.setText("Import bot backup (moves the bot from the old test app)");
        root.addView(steps);
        root.addView(validate); root.addView(provision); root.addView(importBtn); root.addView(live); root.addView(invite); root.addView(stop);
        root.addView(candBox); root.addView(sv);
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

        Runnable tick = new Runnable() { public void run() {
            refresh();
            handler.postDelayed(this, 1500);
        }};
        handler.post(tick);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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
            String owner = st.optString("owner_addr", "");
            TextView t = new TextView(this);
            if (!botAddr.isEmpty()) t.setText("bot: " + botAddr + "   ");
            if (!owner.isEmpty()) {
                t.setText(t.getText() + "owner: " + owner
                        + " | chats: " + st.optString("chats", "{}"));
                candBox.addView(t);
                return;
            }
            candBox.addView(t);
            JSONArray cands = st.optJSONArray("owner_candidates");
            if (cands == null || cands.length() == 0) return;
            TextView h = new TextView(this);
            h.setText("Confirm the owner (they must message the bot from their DeltaChat first):");
            candBox.addView(h);
            for (int i = 0; i < cands.length(); i++) {
                JSONObject c = cands.getJSONObject(i);
                final String addr = c.getString("addr");
                final String name = c.optString("name", "");
                Button cb = new Button(this);
                cb.setText("Owner = " + name + " <" + addr + ">");
                cb.setOnClickListener(v -> {
                    new android.app.AlertDialog.Builder(this)
                        .setTitle("Confirm owner")
                        .setMessage("This gives " + addr + " FULL control of this bot: publishing app updates into every chat the bot serves and managing list identity. This is the address Delta Chat itself verified for that contact. Confirm only if it is your own DeltaChat address.")
                        .setPositiveButton("Confirm owner", (d, w) -> {
                            try {
                                if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
                                Python.getInstance().getModule("bot_runtime")
                                        .callAttr("confirm_owner", getFilesDir().getAbsolutePath(), addr);
                            } catch (Exception ignored) {}
                            lastStateJson = ""; // force refresh
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                });
                candBox.addView(cb);
            }
        } catch (Exception ignored) {}
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
