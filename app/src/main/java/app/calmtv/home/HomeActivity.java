package app.calmtv.home;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Calm Home: a tiny Apple TV style home screen.
 * Wallpaper + clock + a dock of pinned apps + an "All apps" grid. No libraries.
 */
public class HomeActivity extends Activity {

    static final long ROTATE_MS = 90000L;   // new wallpaper every 90 s
    static final long FADE_MS = 2000L;      // cross-fade length
    static final int[] WALLS = {
            R.drawable.wall_1, R.drawable.wall_2, R.drawable.wall_3,
            R.drawable.wall_4, R.drawable.wall_5, R.drawable.wall_6
    };

    static final class AppEntry {
        String pkg;
        CharSequence label;
        Drawable icon;
        Intent intent;
        boolean pinnable = true;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private float density;
    private int screenW;
    private FrameLayout root;
    private ImageView wallBottom;
    private ImageView wallTop;
    private LinearLayout dock;
    private FrameLayout allAppsLayer;

    private List<AppEntry> apps = new ArrayList<AppEntry>();
    private AppEntry settingsEntry;
    private String lastSignature = "";
    private int wallIndex;
    private boolean wallBusy;
    private boolean receiverRegistered;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadAppsAsync();
        }
    };

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            showNextWallpaper(false);
            main.postDelayed(this, ROTATE_MS);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("calm", MODE_PRIVATE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        density = dm.density;
        screenW = dm.widthPixels;
        wallIndex = prefs.getInt("wall", -1);
        buildUi();
        setContentView(root);
        showNextWallpaper(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
        receiverRegistered = true;
        loadAppsAsync();
        main.removeCallbacks(rotate);
        main.postDelayed(rotate, ROTATE_MS);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (receiverRegistered) {
            unregisterReceiver(pkgReceiver);
            receiverRegistered = false;
        }
        main.removeCallbacks(rotate);
        wallTop.animate().cancel();
        Drawable d = wallTop.getDrawable();
        if (wallTop.getAlpha() > 0f && d != null) {
            wallBottom.setImageDrawable(d);
        }
        wallTop.setAlpha(0f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Home pressed while we are already showing: go back to the dock.
        if (allAppsLayer != null) {
            hideAllApps();
        } else {
            focusDock();
        }
    }

    @Override
    public void onBackPressed() {
        if (allAppsLayer != null) {
            hideAllApps();
        }
        // On a home screen Back does nothing.
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // MENU key, or the dedicated "all apps" key (284) some remotes have.
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == 284) {
            if (allAppsLayer == null) {
                showAllApps();
            } else {
                hideAllApps();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ UI

    private int dp(float v) {
        return Math.round(v * density);
    }

    private ImageView newWallView() {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return iv;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        wallBottom = newWallView();
        wallTop = newWallView();
        wallTop.setAlpha(0f);
        root.addView(wallBottom, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(wallTop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Soft shading so white text and the dock stay readable on bright photos.
        View bottomScrim = new View(this);
        bottomScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x99000000}));
        root.addView(bottomScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320), Gravity.BOTTOM));
        View topScrim = new View(this);
        topScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000000, 0x00000000}));
        root.addView(topScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(200), Gravity.TOP));

        // Clock, top right.
        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.END);

        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("HH:mm");
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 54);
        time.setTextColor(Color.WHITE);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setShadowLayer(dp(6), 0, dp(1), 0x80000000);
        time.setIncludeFontPadding(false);
        clockBox.addView(time, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        date.setTextColor(0xE6FFFFFF);
        date.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        date.setShadowLayer(dp(5), 0, dp(1), 0x80000000);
        clockBox.addView(date, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        clp.topMargin = dp(36);
        clp.rightMargin = dp(64);
        root.addView(clockBox, clp);

        // Dock, bottom centre.
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(28), dp(14), dp(28), dp(6));
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x73000000);
        pill.setCornerRadius(dp(40));
        dock.setBackground(pill);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dlp.bottomMargin = dp(44);
        root.addView(dock, dlp);
    }

    // ------------------------------------------------------------------ apps

    private void loadAppsAsync() {
        if (io.isShutdown()) {
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                final List<AppEntry> list = queryApps();
                final AppEntry settings = querySettings();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        applyApps(list, settings);
                    }
                });
            }
        });
    }

    private List<AppEntry> queryApps() {
        PackageManager pm = getPackageManager();
        Map<String, AppEntry> map = new LinkedHashMap<String, AppEntry>();
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN);
            q.addCategory(cat);
            List<ResolveInfo> found = pm.queryIntentActivities(q, 0);
            for (ResolveInfo ri : found) {
                if (ri.activityInfo == null) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || map.containsKey(pkg)) {
                    continue;
                }
                AppEntry e = new AppEntry();
                e.pkg = pkg;
                e.label = ri.loadLabel(pm);
                e.icon = ri.loadIcon(pm);
                Intent launch = new Intent(Intent.ACTION_MAIN);
                launch.addCategory(cat);
                launch.setComponent(new ComponentName(pkg, ri.activityInfo.name));
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                e.intent = launch;
                map.put(pkg, e);
            }
        }
        List<AppEntry> list = new ArrayList<AppEntry>(map.values());
        Collections.sort(list, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.label.toString().compareToIgnoreCase(b.label.toString());
            }
        });
        return list;
    }

    private AppEntry querySettings() {
        PackageManager pm = getPackageManager();
        Intent si = new Intent(Settings.ACTION_SETTINGS);
        ResolveInfo ri = pm.resolveActivity(si, 0);
        if (ri == null || ri.activityInfo == null || "android".equals(ri.activityInfo.packageName)) {
            return null;
        }
        AppEntry e = new AppEntry();
        e.pkg = ri.activityInfo.packageName;
        e.label = "Settings";
        e.icon = ri.loadIcon(pm);
        e.pinnable = false;
        Intent launch = new Intent(Settings.ACTION_SETTINGS);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        e.intent = launch;
        return e;
    }

    private void applyApps(List<AppEntry> list, AppEntry settings) {
        StringBuilder sig = new StringBuilder();
        for (AppEntry e : list) {
            sig.append(e.pkg).append('|');
        }
        sig.append(settings == null ? "-" : "s");
        apps = list;
        settingsEntry = settings;
        if (sig.toString().equals(lastSignature)) {
            return;
        }
        lastSignature = sig.toString();
        ensureDefaultPins();
        rebuildDock();
        if (allAppsLayer == null && getCurrentFocus() == null) {
            focusDock();
        }
    }

    private AppEntry find(String pkg) {
        for (AppEntry e : apps) {
            if (e.pkg.equals(pkg)) {
                return e;
            }
        }
        return null;
    }

    private void launch(AppEntry e) {
        try {
            startActivity(new Intent(e.intent));
        } catch (Exception ex) {
            Toast.makeText(this, "Can't open " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ pins / dock

    private List<String> getPins() {
        List<String> out = new ArrayList<String>();
        String s = prefs.getString("pins", "");
        for (String p : s.split("\n")) {
            if (p.length() > 0) {
                out.add(p);
            }
        }
        return out;
    }

    private void savePins(List<String> pins) {
        StringBuilder sb = new StringBuilder();
        for (String p : pins) {
            sb.append(p).append('\n');
        }
        prefs.edit().putString("pins", sb.toString()).apply();
    }

    /** First run: pin YouTube, Net Mirror and the Play Store if they are installed. */
    private void ensureDefaultPins() {
        if (prefs.contains("pins")) {
            return;
        }
        List<String> pins = new ArrayList<String>();
        AppEntry yt = find("com.google.android.youtube.tv");
        if (yt == null) {
            for (AppEntry e : apps) {
                if (e.label.toString().equalsIgnoreCase("youtube")) {
                    yt = e;
                    break;
                }
            }
        }
        if (yt != null) {
            pins.add(yt.pkg);
        }
        for (AppEntry e : apps) {
            if (e.label.toString().toLowerCase(Locale.ROOT).contains("mirror")) {
                pins.add(e.pkg);
                break;
            }
        }
        if (find("com.android.vending") != null) {
            pins.add("com.android.vending");
        }
        savePins(pins);
    }

    private void rebuildDock() {
        dock.removeAllViews();
        for (String pkg : getPins()) {
            final AppEntry e = find(pkg);
            if (e == null) {
                continue;
            }
            AppTile t = new AppTile(this, e.icon, e.label, dp(104), dp(132), false);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    launch(e);
                }
            });
            t.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    togglePin(e, true);
                    return true;
                }
            });
            addToDock(t);
        }

        AppTile all = new AppTile(this, new GridIcon(), "All apps", dp(104), dp(132), false);
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAllApps();
            }
        });
        addToDock(all);

        if (settingsEntry != null) {
            final AppEntry s = settingsEntry;
            AppTile st = new AppTile(this, s.icon, s.label, dp(104), dp(132), false);
            st.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    launch(s);
                }
            });
            addToDock(st);
        }
    }

    private void addToDock(View tile) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        dock.addView(tile, lp);
    }

    private void focusDock() {
        if (dock.getChildCount() > 0) {
            dock.getChildAt(0).requestFocus();
        }
    }

    private void togglePin(AppEntry e, boolean fromDock) {
        List<String> pins = getPins();
        if (pins.contains(e.pkg)) {
            pins.remove(e.pkg);
            Toast.makeText(this, e.label + " removed from the dock", Toast.LENGTH_SHORT).show();
        } else {
            pins.add(e.pkg);
            Toast.makeText(this, e.label + " added to the dock", Toast.LENGTH_SHORT).show();
        }
        savePins(pins);
        rebuildDock();
        if (fromDock) {
            focusDock();
        }
    }

    // ------------------------------------------------------------------ all apps

    private void showAllApps() {
        if (allAppsLayer != null) {
            return;
        }
        allAppsLayer = new FrameLayout(this);
        allAppsLayer.setBackgroundColor(0xE60A0C10);
        allAppsLayer.setClickable(true);

        TextView title = new TextView(this);
        title.setText("All apps");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        tlp.leftMargin = dp(72);
        tlp.topMargin = dp(36);
        allAppsLayer.addView(title, tlp);

        TextView hint = new TextView(this);
        hint.setText("Hold OK on an app to add it to (or remove it from) the dock");
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        hlp.rightMargin = dp(72);
        hlp.topMargin = dp(46);
        allAppsLayer.addView(hint, hlp);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFocusable(false);
        sv.setClipToPadding(false);
        sv.setPadding(0, dp(104), 0, dp(40));

        final int cell = dp(168);
        int cols = Math.max(3, (screenW - dp(144)) / cell);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(Math.max(0, (screenW - cols * cell) / 2), 0, 0, 0);

        View first = null;
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                rows.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final AppEntry e = apps.get(i);
            AppTile t = new AppTile(this, e.icon, e.label, dp(96), cell - dp(16), true);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideAllApps();
                    launch(e);
                }
            });
            t.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    togglePin(e, false);
                    return true;
                }
            });
            row.addView(t, new LinearLayout.LayoutParams(cell, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (first == null) {
                first = t;
            }
        }
        sv.addView(rows, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        allAppsLayer.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        allAppsLayer.setAlpha(0f);
        root.addView(allAppsLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        allAppsLayer.animate().alpha(1f).setDuration(180);
        dock.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        if (first != null) {
            final View f = first;
            f.post(new Runnable() {
                @Override
                public void run() {
                    f.requestFocus();
                }
            });
        }
    }

    private void hideAllApps() {
        if (allAppsLayer == null) {
            return;
        }
        root.removeView(allAppsLayer);
        allAppsLayer = null;
        dock.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        focusDock();
    }

    // ------------------------------------------------------------------ wallpapers

    private void showNextWallpaper(final boolean instant) {
        if (wallBusy || io.isShutdown()) {
            return;
        }
        wallBusy = true;
        final int idx = (wallIndex + 1) % WALLS.length;
        io.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = decode(WALLS[idx]);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        wallBusy = false;
                        if (bmp == null) {
                            return;
                        }
                        wallIndex = idx;
                        prefs.edit().putInt("wall", idx).apply();
                        if (instant) {
                            wallBottom.setImageBitmap(bmp);
                            return;
                        }
                        wallTop.setImageBitmap(bmp);
                        wallTop.setAlpha(0f);
                        wallTop.animate().alpha(1f).setDuration(FADE_MS).withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                wallBottom.setImageBitmap(bmp);
                                wallTop.setAlpha(0f);
                            }
                        });
                    }
                });
            }
        });
    }

    private Bitmap decode(int resId) {
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(getResources(), resId, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= dm.widthPixels && o.outHeight / (sample * 2) >= dm.heightPixels) {
                sample *= 2;
            }
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeResource(getResources(), resId, o);
        } catch (Throwable t) {
            return null;
        }
    }
}
