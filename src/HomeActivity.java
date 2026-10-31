package home.custom.tv;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.TextUtils;
import android.util.TypedValue;
import android.util.Xml;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HomeActivity extends Activity {

    // ---- palette -------------------------------------------------------
    private static final int COLOR_PAGE = 0xFFF2F6FB;
    private static final int COLOR_TEXT = 0xFF141A22;
    private static final int COLOR_MUTED = 0xFF5B6675;
    private static final int COLOR_NAVY = 0xFF1F3B5C;
    private static final int COLOR_LINE = 0xFFD3DBE6;
    private static final int COLOR_FOCUS = 0xFF3B82F6;

    // ---- data sources --------------------------------------------------
    private static final String BBC_FEED = "https://feeds.bbci.co.uk/news/rss.xml";
    private static final String SKY_FEED = "https://feeds.skynews.com/feeds/rss/home.xml";
    // Default location for the weather readout (central London).
    // Change these to wherever the TV lives.
    private static final double WEATHER_LAT = 51.5074;
    private static final double WEATHER_LON = -0.1278;
    private static final String WEATHER_URL = "https://api.open-meteo.com/v1/forecast"
            + "?latitude=" + WEATHER_LAT + "&longitude=" + WEATHER_LON
            + "&current=temperature_2m&timezone=Europe%2FLondon";

    // ---- timing --------------------------------------------------------
    private static final long STORY_MS = 12000L;
    private static final long REFRESH_MS = 15L * 60L * 1000L;
    private static final long RETRY_MS = 60L * 1000L;
    private static final int MAX_PER_FEED = 6;
    private static final int HERO_MAX = 5;

    // ---- navigation ----------------------------------------------------
    private static final String[] NAV_LABELS =
            {"Home", "Apps", "Movies", "TV Shows", "Live TV", "Settings"};
    private static final int NAV_HOME = 0, NAV_APPS = 1, NAV_MOVIES = 2,
            NAV_TV = 3, NAV_LIVE = 4, NAV_SETTINGS = 5;

    private static final String[] preferredAppOrder = {
            "prime video", "youtube", "netflix", "disney", "bbc iplayer", "iplayer",
            "radio dial", "aurora", "media player"};

    private static final Typeface SANS = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    // ---- state ---------------------------------------------------------
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final List<Story> stories = new ArrayList<Story>();
    private List<AppEntry> installedApps = new ArrayList<AppEntry>();
    private boolean activityAlive;
    private float unit = 1f;
    private int currentStory;
    private int sceneIndex = -1;
    private Drawable sceneCurrent;
    private String newsSig = "";
    private String appsSig = "";

    private FrameLayout root;
    private ImageView bgBottom, bgTop;
    private LinearLayout[] navItems;
    private TextView[] navLabels;
    private TextView weatherText, dateText;
    private LinearLayout heroBox, heroDots, openStoryButton, nextStoryButton;
    private TextView heroKicker, heroTitle, heroDescription;
    private HorizontalScrollView newsScroll, appsScroll;
    private LinearLayout newsRow, appsRow;
    private View settingsButton;

    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("EEE d MMM  HH:mm", Locale.UK);

    private static class Story {
        String title, summary, link, imageUrl, source;
    }

    private static class AppEntry {
        String label, packageName, className;
        Drawable banner, icon;
    }

    // ====================================================================
    // lifecycle
    // ====================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        dateFormat.setTimeZone(TimeZone.getTimeZone("Europe/London"));
        calculateLayoutMetrics();
        buildHomeScreen();
        enterImmersiveMode();
        openStoryButton.requestFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); // Home pressed while already on the home screen
        newsScroll.scrollTo(0, 0);
        appsScroll.scrollTo(0, 0);
        openStoryButton.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityAlive = true;
        enterImmersiveMode();
        mainHandler.removeCallbacksAndMessages(null);
        mainHandler.post(clockTick);
        mainHandler.post(weatherTick);
        mainHandler.post(newsTick);
        mainHandler.postDelayed(storyTick, STORY_MS);
        loadInstalledApps();
    }

    @Override
    protected void onPause() {
        activityAlive = false;
        mainHandler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true; // this is the home screen
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openSystemSettings();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ====================================================================
    // periodic work
    // ====================================================================

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (!activityAlive) return;
            updateClock();
            mainHandler.postDelayed(this, 15000L);
        }
    };

    private final Runnable weatherTick = new Runnable() {
        @Override public void run() {
            if (activityAlive) fetchWeather();
        }
    };

    private final Runnable newsTick = new Runnable() {
        @Override public void run() {
            if (activityAlive) fetchNews();
        }
    };

    private final Runnable storyTick = new Runnable() {
        @Override public void run() {
            if (!activityAlive) return;
            advanceStory();
            mainHandler.postDelayed(this, STORY_MS);
        }
    };

    private void updateClock() {
        dateText.setText(dateFormat.format(new Date()));
    }

    // ====================================================================
    // layout helpers
    // ====================================================================

    /** Everything is designed on a 1920x1080 canvas and scaled to the real width. */
    private void calculateLayoutMetrics() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        unit = Math.max(dm.widthPixels, dm.heightPixels) / 1920f;
    }

    private int px(float v) {
        return Math.round(v * unit);
    }

    private FrameLayout.LayoutParams flp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private TextView text(String s, float sizePx, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    private GradientDrawable rect(int fill, int radiusPx, int strokePx, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0) g.setStroke(strokePx, strokeColor);
        return g;
    }

    private GradientDrawable gradient(GradientDrawable.Orientation o, int[] colors) {
        return new GradientDrawable(o, colors);
    }

    /** Fill that gains a blue focus ring when focused. */
    private StateListDrawable focusableBackgroundDrawable(int normal, int focused, int radiusPx) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rect(focused, radiusPx, px(4), COLOR_FOCUS));
        s.addState(new int[]{}, rect(normal, radiusPx, 0, 0));
        return s;
    }

    /** Transparent overlay that draws only the focus ring (for image tiles). */
    private StateListDrawable ringForeground(int radiusPx) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rect(0x00000000, radiusPx, px(4), COLOR_FOCUS));
        s.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return s;
    }

    private void roundClip(View v, final int radiusPx) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        v.setClipToOutline(true);
        v.setElevation(px(6)); // soft shadow under cards and tiles
    }

    private View.OnFocusChangeListener focusScaleListener(final View target, final float scale) {
        return new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                float s = hasFocus ? scale : 1f;
                target.animate().scaleX(s).scaleY(s)
                        .translationZ(hasFocus ? px(8) : 0f).setDuration(140).start();
            }
        };
    }

    private void makeFocusable(View v, float scale, View.OnClickListener click) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(click);
        v.setOnFocusChangeListener(focusScaleListener(v, scale));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ====================================================================
    // glyphs (drawn in code, so no extra image resources are needed)
    // ====================================================================

    private static class Glyph extends View {
        static final int HOME = 0, GEAR = 1, CLOUD = 2, ARROW = 3, NEXT = 4, GRID = 5;
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Glyph(Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            paint.setColor(color);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float sw = Math.min(w, h) * 0.09f;
            paint.setStrokeWidth(sw);
            paint.setStyle(Paint.Style.STROKE);
            switch (kind) {
                case HOME:
                    path.reset();
                    path.moveTo(0.12f * w, 0.48f * h);
                    path.lineTo(0.50f * w, 0.14f * h);
                    path.lineTo(0.88f * w, 0.48f * h);
                    path.moveTo(0.22f * w, 0.42f * h);
                    path.lineTo(0.22f * w, 0.84f * h);
                    path.lineTo(0.78f * w, 0.84f * h);
                    path.lineTo(0.78f * w, 0.42f * h);
                    path.moveTo(0.42f * w, 0.84f * h);
                    path.lineTo(0.42f * w, 0.60f * h);
                    path.lineTo(0.58f * w, 0.60f * h);
                    path.lineTo(0.58f * w, 0.84f * h);
                    c.drawPath(path, paint);
                    break;
                case GEAR:
                    c.drawCircle(w / 2, h / 2, 0.28f * w, paint);
                    c.drawCircle(w / 2, h / 2, 0.11f * w, paint);
                    paint.setStrokeWidth(sw * 1.9f);
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4.0;
                        float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                        c.drawLine(w / 2 + cos * 0.34f * w, h / 2 + sin * 0.34f * h,
                                w / 2 + cos * 0.44f * w, h / 2 + sin * 0.44f * h, paint);
                    }
                    break;
                case CLOUD:
                    paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(0.30f * w, 0.62f * h, 0.16f * w, paint);
                    c.drawCircle(0.50f * w, 0.46f * h, 0.22f * w, paint);
                    c.drawCircle(0.72f * w, 0.60f * h, 0.17f * w, paint);
                    c.drawRect(0.30f * w, 0.62f * h, 0.72f * w, 0.78f * h, paint);
                    break;
                case ARROW:
                    c.drawLine(0.15f * w, 0.5f * h, 0.84f * w, 0.5f * h, paint);
                    path.reset();
                    path.moveTo(0.55f * w, 0.22f * h);
                    path.lineTo(0.85f * w, 0.5f * h);
                    path.lineTo(0.55f * w, 0.78f * h);
                    c.drawPath(path, paint);
                    break;
                case NEXT:
                    path.reset();
                    path.moveTo(0.35f * w, 0.20f * h);
                    path.lineTo(0.68f * w, 0.50f * h);
                    path.lineTo(0.35f * w, 0.80f * h);
                    c.drawPath(path, paint);
                    break;
                case GRID:
                    paint.setStyle(Paint.Style.FILL);
                    float[] p = {0.22f, 0.50f, 0.78f};
                    for (int r = 0; r < 3; r++)
                        for (int col = 0; col < 3; col++)
                            c.drawCircle(p[col] * w, p[r] * h, 0.075f * Math.min(w, h), paint);
                    break;
                default:
                    break;
            }
        }
    }

    // ====================================================================
    // screen construction
    // ====================================================================

    private void buildHomeScreen() {
        root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_PAGE);
        root.setClipChildren(false);

        // scenic backdrop: photo + readability scrims that melt into the page colour
        FrameLayout backdrop = new FrameLayout(this);
        bgBottom = new ImageView(this);
        bgTop = new ImageView(this);
        sceneCurrent = loadScene(0);
        sceneIndex = 0;
        ImageView[] layers = {bgBottom, bgTop};
        for (int i = 0; i < layers.length; i++) {
            layers[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            layers[i].setImageDrawable(sceneCurrent);
            backdrop.addView(layers[i], new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        View leftScrim = new View(this);
        leftScrim.setBackground(gradient(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xB3F2F6FB, 0x66F2F6FB, 0x00F2F6FB}));
        backdrop.addView(leftScrim, new FrameLayout.LayoutParams(px(1150),
                ViewGroup.LayoutParams.MATCH_PARENT));
        View topScrim = new View(this);
        topScrim.setBackground(gradient(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xAAF2F6FB, 0x00F2F6FB}));
        backdrop.addView(topScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(200)));
        View bottomFade = new View(this);
        bottomFade.setBackground(gradient(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00F2F6FB, 0xCCF2F6FB, 0xFFF2F6FB}));
        backdrop.addView(bottomFade, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(320), Gravity.BOTTOM));
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(720)));

        createTopBar();
        createHero();
        wireFocus();
        createNewsSection();
        createAppsSection();
        updateNavigationStyles();
        setContentView(root);
    }

    /** Down from the top bar always lands on Open story, not whatever is geometrically nearest. */
    private void wireFocus() {
        int target = View.generateViewId();
        openStoryButton.setId(target);
        for (LinearLayout item : navItems) item.setNextFocusDownId(target);
        settingsButton.setNextFocusDownId(target);
    }

    private Drawable loadScene(int index) {
        int[] ids = {R.drawable.bg1, R.drawable.bg2, R.drawable.bg3};
        return getResources().getDrawable(ids[index % ids.length], getTheme());
    }

    private void showScene(int index) {
        if (index == sceneIndex) return;
        sceneIndex = index;
        final Drawable next = loadScene(index);
        bgTop.animate().cancel();
        bgTop.setImageDrawable(sceneCurrent);
        bgTop.setAlpha(1f);
        bgBottom.setImageDrawable(next);
        sceneCurrent = next;
        bgTop.animate().alpha(0f).setDuration(900).withEndAction(new Runnable() {
            @Override public void run() {
                bgTop.setImageDrawable(next);
                bgTop.setAlpha(1f);
            }
        }).start();
    }

    // ---- top bar -------------------------------------------------------

    private void createTopBar() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        tabs.setClipChildren(false);
        navItems = new LinearLayout[NAV_LABELS.length];
        navLabels = new TextView[NAV_LABELS.length];
        for (int i = 0; i < NAV_LABELS.length; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(px(34), 0, px(34), 0);
            if (i == NAV_HOME) {
                item.addView(new Glyph(this, Glyph.HOME, COLOR_NAVY),
                        llp(px(36), px(36), 0, 0, px(14), 0));
            }
            TextView label = text(NAV_LABELS[i], px(28), COLOR_MUTED, SANS);
            item.addView(label);
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_focused}, rect(0xFFFFFFFF, px(35), px(4), COLOR_FOCUS));
            bg.addState(new int[]{android.R.attr.state_selected}, rect(0xFFFFFFFF, px(35), 0, 0));
            bg.addState(new int[]{}, rect(0x00000000, px(35), 0, 0));
            item.setBackground(bg);
            item.setElevation(px(3)); // lifts the white Home pill off the page
            makeFocusable(item, 1.05f, new View.OnClickListener() {
                @Override public void onClick(View v) { onTabClick(index); }
            });
            navItems[i] = item;
            navLabels[i] = label;
            tabs.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, px(70)));
        }
        root.addView(tabs, flp(ViewGroup.LayoutParams.WRAP_CONTENT, px(70),
                Gravity.TOP | Gravity.LEFT, px(76), px(55), 0, 0));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        right.addView(new Glyph(this, Glyph.CLOUD, 0xFF9FB3C8), llp(px(50), px(40), 0, 0, px(12), 0));
        weatherText = text("--\u00b0C", px(28), COLOR_TEXT, SANS);
        right.addView(weatherText);
        View divider = new View(this);
        divider.setBackgroundColor(COLOR_LINE);
        right.addView(divider, llp(px(2), px(38), px(30), 0, px(30), 0));
        dateText = text("", px(28), COLOR_TEXT, SANS);
        right.addView(dateText);

        FrameLayout gear = new FrameLayout(this);
        settingsButton = gear;
        gear.setContentDescription("Open Android Settings");
        StateListDrawable gbg = new StateListDrawable();
        GradientDrawable gFocus = rect(0xFFFFFFFF, 0, px(4), COLOR_FOCUS);
        gFocus.setShape(GradientDrawable.OVAL);
        GradientDrawable gNormal = rect(0xFFFFFFFF, 0, px(2), COLOR_LINE);
        gNormal.setShape(GradientDrawable.OVAL);
        gbg.addState(new int[]{android.R.attr.state_focused}, gFocus);
        gbg.addState(new int[]{}, gNormal);
        gear.setBackground(gbg);
        gear.setElevation(px(3));
        gear.addView(new Glyph(this, Glyph.GEAR, COLOR_NAVY),
                flp(px(42), px(42), Gravity.CENTER, 0, 0, 0, 0));
        makeFocusable(gear, 1.08f, new View.OnClickListener() {
            @Override public void onClick(View v) { openSystemSettings(); }
        });
        right.addView(gear, llp(px(80), px(80), px(40), 0, 0, 0));

        root.addView(right, flp(ViewGroup.LayoutParams.WRAP_CONTENT, px(80),
                Gravity.TOP | Gravity.RIGHT, 0, px(50), px(76), 0));
    }

    private void updateNavigationStyles() {
        for (int i = 0; i < navItems.length; i++) {
            boolean selected = (i == NAV_HOME);
            navItems[i].setSelected(selected);
            navLabels[i].setTextColor(selected ? COLOR_TEXT : COLOR_MUTED);
            navLabels[i].setTypeface(selected ? MEDIUM : SANS);
        }
    }

    private void onTabClick(int index) {
        switch (index) {
            case NAV_APPS:
                showAllAppsDialog();
                break;
            case NAV_MOVIES:
                openCategory("Movies", "netflix", "prime video", "disney", "apple tv", "plex");
                break;
            case NAV_TV:
                openCategory("TV Shows", "iplayer", "itv", "all 4", "my5", "netflix", "disney", "prime video");
                break;
            case NAV_LIVE:
                openLiveTv();
                break;
            case NAV_SETTINGS:
                openSystemSettings();
                break;
            default:
                break; // Home: already here
        }
    }

    // ---- hero ----------------------------------------------------------

    private void createHero() {
        heroBox = new LinearLayout(this);
        heroBox.setOrientation(LinearLayout.VERTICAL);

        heroKicker = text("Latest news", px(28), COLOR_MUTED, SANS);
        heroBox.addView(heroKicker);

        heroTitle = text("Loading latest headlines", px(60), COLOR_TEXT, BOLD);
        heroTitle.setMinLines(2);
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroBox.addView(heroTitle, llp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(8), 0, 0));

        heroDescription = text("Connecting to BBC and Sky news feeds", px(26), COLOR_MUTED, SANS);
        heroDescription.setMinLines(2);
        heroDescription.setMaxLines(2);
        heroDescription.setEllipsize(TextUtils.TruncateAt.END);
        heroBox.addView(heroDescription, llp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(10), 0, 0));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setClipChildren(false);
        openStoryButton = createActionButton("Open story", Glyph.ARROW, true,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { openCurrentStory(); }
                });
        nextStoryButton = createActionButton("Next story", Glyph.NEXT, false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { advanceStory(); }
                });
        buttons.addView(openStoryButton, llp(ViewGroup.LayoutParams.WRAP_CONTENT, px(64), 0, 0, px(24), 0));
        buttons.addView(nextStoryButton, llp(ViewGroup.LayoutParams.WRAP_CONTENT, px(64), 0, 0, 0, 0));
        heroBox.addView(buttons, llp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(26), 0, 0));

        heroDots = new LinearLayout(this);
        heroDots.setOrientation(LinearLayout.HORIZONTAL);
        heroDots.setGravity(Gravity.CENTER_VERTICAL);
        heroBox.addView(heroDots, llp(ViewGroup.LayoutParams.WRAP_CONTENT, px(14), 0, px(24), 0, 0));

        heroBox.setClipChildren(false);
        root.addView(heroBox, flp(px(1000), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT, px(108), px(190), 0, 0));
    }

    private LinearLayout createActionButton(String label, int glyphKind, boolean primary,
                                            View.OnClickListener click) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(px(34), 0, px(34), 0);
        int fg = primary ? Color.WHITE : COLOR_NAVY;
        b.addView(new Glyph(this, glyphKind, fg), llp(px(30), px(30), 0, 0, px(14), 0));
        b.addView(text(label, px(26), fg, MEDIUM));
        if (primary) {
            b.setBackground(focusableBackgroundDrawable(COLOR_NAVY, COLOR_NAVY, px(32)));
        } else {
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_focused}, rect(0xFFFFFFFF, px(32), px(4), COLOR_FOCUS));
            bg.addState(new int[]{}, rect(0xFFFFFFFF, px(32), px(2), COLOR_LINE));
            b.setBackground(bg);
            b.setElevation(px(3));
        }
        makeFocusable(b, 1.06f, click);
        return b;
    }

    private int heroCount() {
        return Math.min(HERO_MAX, stories.size());
    }

    private void applyStory() {
        if (stories.isEmpty()) return;
        Story s = stories.get(Math.min(currentStory, stories.size() - 1));
        heroKicker.setText("Top story \u00b7 " + s.source);
        heroTitle.setText(s.title);
        heroDescription.setText(TextUtils.isEmpty(s.summary) ? s.source : s.summary);
    }

    private void updateDots() {
        heroDots.removeAllViews();
        int n = heroCount();
        for (int i = 0; i < n; i++) {
            View d = new View(this);
            d.setBackground(rect(i == currentStory ? COLOR_NAVY : 0xFFB9C4D2, px(7), 0, 0));
            heroDots.addView(d, llp(i == currentStory ? px(44) : px(14), px(14), 0, 0, px(14), 0));
        }
    }

    private void showStory(int index) {
        int n = heroCount();
        if (n == 0) return;
        currentStory = ((index % n) + n) % n;
        updateDots();
        showScene(currentStory % 3);
        heroBox.animate().cancel();
        heroBox.animate().alpha(0f).setDuration(140).withEndAction(new Runnable() {
            @Override public void run() {
                applyStory();
                heroBox.animate().alpha(1f).setDuration(260).start();
            }
        }).start();
    }

    private void advanceStory() {
        if (heroCount() > 1) showStory(currentStory + 1);
        mainHandler.removeCallbacks(storyTick);
        if (activityAlive) mainHandler.postDelayed(storyTick, STORY_MS);
    }

    private void openCurrentStory() {
        if (stories.isEmpty()) {
            toast("This headline has no article link");
            return;
        }
        openLink(stories.get(Math.min(currentStory, stories.size() - 1)));
    }

    private void openLink(Story s) {
        if (s == null || TextUtils.isEmpty(s.link)) {
            toast("This headline has no article link");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(s.link));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("No browser is installed to open this story");
        } catch (Exception e) {
            toast("Unable to open this story");
        }
    }

    // ---- news row ------------------------------------------------------

    private void createNewsSection() {
        root.addView(text("Latest news", px(28), COLOR_TEXT, MEDIUM), flp(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT, px(108), px(598), 0, 0));

        newsScroll = new HorizontalScrollView(this);
        newsScroll.setHorizontalScrollBarEnabled(false);
        newsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        newsScroll.setClipToPadding(false);
        newsScroll.setClipChildren(false);
        newsScroll.setPadding(px(108), px(16), px(108), px(16));
        newsRow = new LinearLayout(this);
        newsRow.setOrientation(LinearLayout.HORIZONTAL);
        newsRow.setClipChildren(false);
        newsScroll.addView(newsRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(newsScroll, flp(ViewGroup.LayoutParams.MATCH_PARENT, px(222),
                Gravity.TOP | Gravity.LEFT, 0, px(628), 0, 0));
        fillNewsCards();
    }

    private void fillNewsCards() {
        newsRow.removeAllViews();
        if (stories.isEmpty()) {
            FrameLayout ph = new FrameLayout(this);
            ph.setBackground(rect(0xFFE3E9F1, px(22), 0, 0));
            TextView t = text("Headlines from BBC News and Sky News will appear here.",
                    px(24), COLOR_MUTED, SANS);
            t.setPadding(px(22), px(22), px(22), px(22));
            ph.addView(t, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            newsRow.addView(ph, llp(px(400), px(190), 0, 0, 0, 0));
            return;
        }
        for (int i = 0; i < stories.size(); i++) {
            Story s = stories.get(i);
            ImageView img = new ImageView(this);
            View card = createNewsCard(s, img);
            newsRow.addView(card, llp(px(400), px(190), 0, 0, px(24), 0));
            loadThumb(s, img);
        }
    }

    private View createNewsCard(final Story s, ImageView img) {
        int r = px(22);
        FrameLayout card = new FrameLayout(this);
        card.setBackground(rect(0xFF2A3B52, r, 0, 0));
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(this);
        shade.setBackground(gradient(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x10000000, 0xDD000000}));
        card.addView(shade, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.BOTTOM);
        info.setPadding(px(18), px(14), px(18), px(16));
        TextView title = text(s.title, px(24), Color.WHITE, BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(title);
        info.addView(text(s.source, px(20), 0xFFD5DDE8, SANS), llp(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0, px(4), 0, 0));
        card.addView(info, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        card.setForeground(ringForeground(r));
        roundClip(card, r);
        card.setContentDescription(s.title);
        makeFocusable(card, 1.06f, new View.OnClickListener() {
            @Override public void onClick(View v) { openLink(s); }
        });
        card.setNextFocusUpId(openStoryButton.getId());
        return card;
    }

    private void loadThumb(final Story s, final ImageView target) {
        if (TextUtils.isEmpty(s.imageUrl)) return;
        io.execute(new Runnable() {
            @Override public void run() {
                Bitmap bmp = null;
                if (s.imageUrl.contains("/240/")) {
                    bmp = fetchBitmap(s.imageUrl.replace("/240/", "/624/")); // sharper if available
                }
                if (bmp == null) bmp = fetchBitmap(s.imageUrl);
                final Bitmap result = bmp;
                if (result == null) return;
                mainHandler.post(new Runnable() {
                    @Override public void run() { target.setImageBitmap(result); }
                });
            }
        });
    }

    private Bitmap fetchBitmap(String url) {
        HttpURLConnection c = null;
        try {
            c = open(url);
            InputStream in = new BufferedInputStream(c.getInputStream());
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---- apps row ------------------------------------------------------

    private void createAppsSection() {
        root.addView(text("Your apps", px(28), COLOR_TEXT, MEDIUM), flp(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT, px(108), px(864), 0, 0));
        appsScroll = new HorizontalScrollView(this);
        appsScroll.setHorizontalScrollBarEnabled(false);
        appsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        appsScroll.setClipToPadding(false);
        appsScroll.setClipChildren(false);
        appsScroll.setPadding(px(108), px(16), px(108), px(16));
        appsRow = new LinearLayout(this);
        appsRow.setOrientation(LinearLayout.HORIZONTAL);
        appsRow.setClipChildren(false);
        appsScroll.addView(appsRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(appsScroll, flp(ViewGroup.LayoutParams.MATCH_PARENT, px(139),
                Gravity.TOP | Gravity.LEFT, 0, px(888), 0, 0));
    }

    private void loadInstalledApps() {
        io.execute(new Runnable() {
            @Override public void run() {
                final List<AppEntry> apps = queryApps();
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        StringBuilder sig = new StringBuilder();
                        for (AppEntry a : apps) sig.append(a.packageName).append('|');
                        installedApps = apps;
                        if (!sig.toString().equals(appsSig)) {
                            appsSig = sig.toString();
                            fillAppTiles();
                        }
                    }
                });
            }
        });
    }

    private List<AppEntry> queryApps() {
        PackageManager pm = getPackageManager();
        Set<String> seen = new HashSet<String>();
        List<AppEntry> out = new ArrayList<AppEntry>();
        String[] hide = {"copydata", "backuptransport", "configupdater", "partnercustomizer"};
        String[] categories = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String category : categories) {
            Intent q = new Intent(Intent.ACTION_MAIN);
            q.addCategory(category);
            List<ResolveInfo> found = pm.queryIntentActivities(q, 0);
            if (found == null) continue;
            for (ResolveInfo ri : found) {
                ActivityInfo ai = ri.activityInfo;
                if (ai == null || getPackageName().equals(ai.packageName)) continue;
                String pkgLower = ai.packageName.toLowerCase(Locale.UK);
                boolean hidden = false;
                for (String h : hide) {
                    if (pkgLower.contains(h)) { hidden = true; break; }
                }
                if (hidden) continue;
                if (!seen.add(ai.packageName)) continue;
                AppEntry e = new AppEntry();
                e.packageName = ai.packageName;
                e.className = ai.name;
                e.label = String.valueOf(ri.loadLabel(pm));
                Drawable banner = null;
                try {
                    banner = ai.loadBanner(pm);
                    if (banner == null && ai.applicationInfo != null) {
                        banner = ai.applicationInfo.loadBanner(pm);
                    }
                } catch (Exception ignored) { }
                e.banner = banner;
                e.icon = ri.loadIcon(pm);
                out.add(e);
            }
        }
        Collections.sort(out, new Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) {
                int ia = orderIndex(a), ib = orderIndex(b);
                if (ia != ib) return ia < ib ? -1 : 1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    private int orderIndex(AppEntry a) {
        String l = a.label.toLowerCase(Locale.UK);
        String p = a.packageName.toLowerCase(Locale.UK);
        for (int i = 0; i < preferredAppOrder.length; i++) {
            if (l.contains(preferredAppOrder[i]) || p.contains(preferredAppOrder[i].replace(" ", ""))) {
                return i;
            }
        }
        return preferredAppOrder.length;
    }

    private void fillAppTiles() {
        appsRow.removeAllViews();
        for (AppEntry app : installedApps) {
            appsRow.addView(createAppTile(app, px(190), px(107), false),
                    llp(px(190), px(107), 0, 0, px(22), 0));
        }
        appsRow.addView(createAllAppsTile(), llp(px(190), px(107), 0, 0, 0, 0));
    }

    private View createAppTile(final AppEntry app, int w, int h, boolean iconOnly) {
        int r = px(20);
        FrameLayout tile = new FrameLayout(this);
        tile.setContentDescription(app.label);
        tile.setBackground(rect(0xFFFFFFFF, r, 0, 0));
        if (app.banner != null && !iconOnly) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(app.banner);
            tile.addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            col.setPadding(px(8), px(6), px(8), px(6));
            ImageView icon = new ImageView(this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImageDrawable(app.icon);
            col.addView(icon, llp(px(iconOnly ? 64 : 52), px(iconOnly ? 64 : 52), 0, 0, 0, px(6)));
            TextView label = text(app.label, px(20), COLOR_TEXT, SANS);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            col.addView(label, llp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));
            tile.addView(col, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        tile.setForeground(ringForeground(r));
        roundClip(tile, r);
        makeFocusable(tile, 1.08f, new View.OnClickListener() {
            @Override public void onClick(View v) { launchApp(app); }
        });
        return tile;
    }

    private View createAllAppsTile() {
        int r = px(20);
        FrameLayout tile = new FrameLayout(this);
        tile.setContentDescription("All Apps");
        tile.setBackground(rect(0xFFE3E9F1, r, 0, 0));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.addView(new Glyph(this, Glyph.GRID, 0xFF6B7686), llp(px(48), px(48), 0, 0, 0, px(4)));
        col.addView(text("All Apps", px(20), COLOR_TEXT, SANS));
        tile.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tile.setForeground(ringForeground(r));
        roundClip(tile, r);
        makeFocusable(tile, 1.08f, new View.OnClickListener() {
            @Override public void onClick(View v) { showAllAppsDialog(); }
        });
        return tile;
    }

    private void showAllAppsDialog() {
        showAppsDialog("All apps", installedApps);
    }

    private void showAppsDialog(String title, final List<AppEntry> list) {
        if (list.isEmpty()) {
            toast("No apps found");
            return;
        }
        final Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        final int cols = 6;
        final int cellW = px(210), cellH = px(130), gap = px(10);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(44), px(36), px(44), px(36));
        box.setBackground(rect(COLOR_PAGE, px(32), 0, 0));
        box.addView(text(title, px(36), COLOR_TEXT, BOLD), llp(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 0, 0, px(20)));

        final GridLayout grid = new GridLayout(this);
        grid.setColumnCount(cols);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        for (int i = 0; i < list.size(); i++) {
            final AppEntry app = list.get(i);
            View cell = createAppTile(app, cellW, cellH, true);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    dialog.dismiss();
                    launchApp(app);
                }
            });
            GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
            gp.width = cellW;
            gp.height = cellH;
            gp.setMargins(gap, gap, gap, gap);
            grid.addView(cell, gp);
        }
        int rows = (list.size() + cols - 1) / cols;
        int scrollH = Math.min(rows * (cellH + 2 * gap), px(620));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.setClipChildren(false);
        sv.addView(grid);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, scrollH));

        int boxW = cols * (cellW + 2 * gap) + px(88);
        dialog.setContentView(box, new ViewGroup.LayoutParams(boxW, ViewGroup.LayoutParams.WRAP_CONTENT));
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(boxW, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
    }

    private void launchApp(AppEntry app) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setClassName(app.packageName, app.className);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("App is no longer available");
        } catch (Exception e) {
            toast("Unable to open " + app.label);
        }
    }

    /** Live TV = the TV tuner itself. Falls back to any live-channels app. */
    private void openLiveTv() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setClassName("com.tcl.tv", "com.tcl.tv.TVActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        } catch (Exception ignored) { }
        launchFirstMatching("live TV", "live tv", "live channels", "iplayer", "itv", "sky");
    }

    /** One match opens straight away; several show a picker; none says so. */
    private void openCategory(String title, String... keys) {
        List<AppEntry> matches = new ArrayList<AppEntry>();
        for (AppEntry a : installedApps) {
            String l = a.label.toLowerCase(Locale.UK);
            for (String key : keys) {
                if (l.contains(key)) {
                    matches.add(a);
                    break;
                }
            }
        }
        if (matches.isEmpty()) {
            toast("No " + title + " apps are installed");
        } else if (matches.size() == 1) {
            launchApp(matches.get(0));
        } else {
            showAppsDialog(title, matches);
        }
    }

    private void launchFirstMatching(String what, String... keys) {
        for (String key : keys) {
            for (AppEntry a : installedApps) {
                if (a.label.toLowerCase(Locale.UK).contains(key)) {
                    launchApp(a);
                    return;
                }
            }
        }
        toast("No " + what + " app is installed");
    }

    private void openSystemSettings() {
        try {
            Intent i = new Intent("android.settings.SETTINGS");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("Settings is unavailable");
        }
    }

    // ====================================================================
    // networking: weather + news
    // ====================================================================

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; CustomTVHome/1.0)");
        c.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*");
        return c;
    }

    private String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    private void fetchWeather() {
        io.execute(new Runnable() {
            @Override public void run() {
                String result = null;
                HttpURLConnection c = null;
                try {
                    c = open(WEATHER_URL);
                    JSONObject cur = new JSONObject(readAll(c.getInputStream())).optJSONObject("current");
                    double t = cur == null ? Double.NaN : cur.optDouble("temperature_2m", Double.NaN);
                    if (!Double.isNaN(t)) result = String.format(Locale.UK, "%.1f\u00b0C", t);
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
                final String text = result;
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (!activityAlive) return;
                        if (text != null) weatherText.setText(text);
                        mainHandler.removeCallbacks(weatherTick);
                        mainHandler.postDelayed(weatherTick, text != null ? REFRESH_MS : RETRY_MS);
                    }
                });
            }
        });
    }

    private void fetchNews() {
        io.execute(new Runnable() {
            @Override public void run() {
                final List<Story> merged = mergeStories(
                        loadFeed(BBC_FEED, "BBC News"), loadFeed(SKY_FEED, "Sky News"));
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (activityAlive) onNewsLoaded(merged);
                    }
                });
            }
        });
    }

    private List<Story> loadFeed(String url, String source) {
        HttpURLConnection c = null;
        try {
            c = open(url);
            return parseFeed(new BufferedInputStream(c.getInputStream()), source);
        } catch (Exception e) {
            return new ArrayList<Story>();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private List<Story> mergeStories(List<Story> a, List<Story> b) {
        List<Story> out = new ArrayList<Story>();
        Set<String> seen = new HashSet<String>();
        int max = Math.max(a.size(), b.size());
        for (int i = 0; i < max; i++) {
            if (i < a.size() && seen.add(a.get(i).title.toLowerCase(Locale.UK))) out.add(a.get(i));
            if (i < b.size() && seen.add(b.get(i).title.toLowerCase(Locale.UK))) out.add(b.get(i));
        }
        return out;
    }

    private void onNewsLoaded(List<Story> list) {
        mainHandler.removeCallbacks(newsTick);
        if (list.isEmpty()) {
            if (stories.isEmpty()) showNewsError();
            mainHandler.postDelayed(newsTick, RETRY_MS);
            return;
        }
        mainHandler.postDelayed(newsTick, REFRESH_MS);
        StringBuilder sig = new StringBuilder();
        for (Story s : list) sig.append(s.title).append('|');
        if (sig.toString().equals(newsSig)) return; // nothing new: leave focus and scroll alone
        newsSig = sig.toString();
        stories.clear();
        stories.addAll(list);
        currentStory = 0;
        fillNewsCards();
        applyStory();
        updateDots();
    }

    private void showNewsError() {
        heroKicker.setText("Latest news");
        heroTitle.setText("News feeds unavailable");
        heroDescription.setText("Check the TV's internet connection and try again.");
        heroDots.removeAllViews();
    }

    private List<Story> parseFeed(InputStream in, String source) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(in, null);
        List<Story> out = new ArrayList<Story>();
        Story s = null;
        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT && out.size() < MAX_PER_FEED) {
            if (ev == XmlPullParser.START_TAG) {
                String tag = p.getName();
                if ("item".equals(tag)) {
                    s = new Story();
                    s.source = source;
                } else if (s != null) {
                    if ("title".equals(tag)) {
                        s.title = cleanFeedText(p.nextText());
                    } else if ("description".equals(tag)) {
                        s.summary = cleanFeedText(p.nextText());
                    } else if ("link".equals(tag)) {
                        s.link = p.nextText().trim();
                    } else if ("media:thumbnail".equals(tag)) {
                        String u = p.getAttributeValue(null, "url");
                        if (!TextUtils.isEmpty(u)) s.imageUrl = u;
                    } else if ("enclosure".equals(tag) || "media:content".equals(tag)) {
                        String u = p.getAttributeValue(null, "url");
                        String type = p.getAttributeValue(null, "type");
                        if (s.imageUrl == null && !TextUtils.isEmpty(u)
                                && type != null && type.startsWith("image")) {
                            s.imageUrl = u;
                        }
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && "item".equals(p.getName()) && s != null) {
                if (!TextUtils.isEmpty(s.title)) out.add(s);
                s = null;
            }
            ev = p.next();
        }
        return out;
    }

    private String cleanFeedText(String raw) {
        if (raw == null) return "";
        CharSequence cs = Build.VERSION.SDK_INT >= 24
                ? Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY)
                : Html.fromHtml(raw);
        return cs.toString().replaceAll("[\\s\\u00A0\\uFFFC]+", " ").trim();
    }
}
