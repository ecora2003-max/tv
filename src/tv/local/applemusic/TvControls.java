package tv.local.applemusic;

import android.app.Activity;
import android.app.Application;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.EditText;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.function.IntConsumer;

public final class TvControls {
    private static boolean lifecycleRegistered;

    private TvControls() {}

    public static void install(Activity activity) {
        attach(activity);
        if (!lifecycleRegistered) {
            lifecycleRegistered = true;
            activity.getApplication().registerActivityLifecycleCallbacks(new ActivityCallbacks());
        }
    }

    private static void attach(Activity activity) {
        Window window = activity.getWindow();
        Window.Callback delegate = window.getCallback();
        if (delegate == null || !(window.getDecorView() instanceof ViewGroup)) return;
        if (Proxy.isProxyClass(delegate.getClass()) && Proxy.getInvocationHandler(delegate) instanceof RemoteCallback) return;
        if (activity.getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }
        Focus focus = new Focus((ViewGroup) window.getDecorView());
        LoginPointer pointer = new LoginPointer(activity, window, focus);
        AudioManager audio = activity.getSystemService(AudioManager.class);
        TrackKeys trackKeys = audio == null ? null : new TrackKeys(key -> dispatchMediaKey(activity, audio, key));
        focus.decor.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
            if (!hasFocus) {
                focus.hide();
                pointer.hide();
                if (trackKeys != null) trackKeys.clear();
            }
        });
        window.setCallback((Window.Callback) Proxy.newProxyInstance(
                TvControls.class.getClassLoader(), new Class<?>[] {Window.Callback.class},
                new RemoteCallback(delegate, focus, trackKeys, pointer)));
    }

    private static void dispatchMediaKey(Activity activity, AudioManager audio, int key) {
        if (key == KeyEvent.KEYCODE_MEDIA_STOP) {
            MediaController controller = activity.getMediaController();
            if (controller != null && activity.getPackageName().equals(controller.getPackageName())) {
                PlaybackState state = controller.getPlaybackState();
                if (state != null && (state.getActions() & PlaybackState.ACTION_STOP) != 0) {
                    controller.getTransportControls().stop();
                    return;
                }
            }
            key = KeyEvent.KEYCODE_MEDIA_PAUSE;
        }
        // Send one complete press to the system's active media session.
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0));
    }

    private static final class ActivityCallbacks implements Application.ActivityLifecycleCallbacks {
        public void onActivityResumed(Activity activity) { attach(activity); }
        public void onActivityCreated(Activity activity, Bundle state) {}
        public void onActivityStarted(Activity activity) {}
        public void onActivityPaused(Activity activity) {}
        public void onActivityStopped(Activity activity) {}
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        public void onActivityDestroyed(Activity activity) {}
    }

    private static final class RemoteCallback implements InvocationHandler {
        private final Window.Callback delegate;
        private final Focus focus;
        private final TrackKeys trackKeys;
        private final LoginPointer pointer;

        RemoteCallback(Window.Callback delegate, Focus focus, TrackKeys trackKeys, LoginPointer pointer) {
            this.delegate = delegate;
            this.focus = focus;
            this.trackKeys = trackKeys;
            this.pointer = pointer;
        }

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("dispatchKeyEvent")) {
                KeyEvent event = (KeyEvent) args[0];
                if (trackKeys != null && trackKeys.onKey(event)) {
                    if (event.getAction() == KeyEvent.ACTION_UP && focus.active) focus.scheduleRefresh();
                    return true;
                }
                if (pointer.onKey(event)) return true;
                if (focus.onKey(event)) return true;
                if (focus.composeLogin && pointer.onKey(event)) return true;
            }
            if (name.equals("dispatchTouchEvent")) {
                focus.hide();
                pointer.hide();
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        }
    }

    private static boolean isConfirm(int key) {
        return key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER || key == KeyEvent.KEYCODE_BUTTON_A;
    }

    private static void touch(Window window, long downTime, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, time, action, x, y, 0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try {
            window.superDispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private static final class LoginPointer {
        private final Window window;
        private final Focus focus;
        private final boolean authActivity;
        private final float density;
        private final PointerDrawable cursor = new PointerDrawable();
        private final HashSet<Integer> pressed = new HashSet<>();
        private float x;
        private float y;
        private boolean clickArmed;

        LoginPointer(Activity activity, Window window, Focus focus) {
            this.window = window;
            this.focus = focus;
            density = focus.decor.getResources().getDisplayMetrics().density;
            String name = activity.getClass().getName();
            authActivity = name.equals("com.apple.android.music.navigation.deeplink.SdkAuthActivity")
                    || name.equals("com.apple.android.music.commerce.activities.SonosAuthActivity");
        }

        boolean onKey(KeyEvent event) {
            int key = event.getKeyCode();
            boolean direction = key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_RIGHT;
            if (key == KeyEvent.KEYCODE_BACK) {
                hide();
                return false;
            }
            if (!direction && !isConfirm(key)) return false;
            boolean release = event.getAction() == KeyEvent.ACTION_UP;
            boolean ownedRelease = release && pressed.remove(key);
            if (release && !ownedRelease) return false;
            if (focus.imeVisible() || !loginVisible()) {
                hide();
                return ownedRelease;
            }
            if (release) {
                if (isConfirm(key)) {
                    if (clickArmed && !event.isCanceled() && cursor.getCallback() != null) {
                        long now = SystemClock.uptimeMillis();
                        touch(window, now, now, MotionEvent.ACTION_DOWN, x, y);
                        touch(window, now, now, MotionEvent.ACTION_UP, x, y);
                    }
                    clickArmed = false;
                }
                return true;
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN || focus.decor.getWidth() == 0
                    || focus.decor.getHeight() == 0) return false;
            pressed.add(key);
            if (isConfirm(key) && event.getRepeatCount() == 0) clickArmed = cursor.getCallback() != null;
            if (cursor.getCallback() == null) {
                focus.hide();
                x = focus.decor.getWidth() / 2f;
                y = focus.decor.getHeight() / 2f;
                focus.decor.getOverlay().add(cursor);
            }
            float step = density * (event.getRepeatCount() >= 4 ? 52 : 22);
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) x -= step;
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) x += step;
            if (key == KeyEvent.KEYCODE_DPAD_UP) y -= step;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) y += step;
            x = Math.max(0, Math.min(focus.decor.getWidth() - 1, x));
            y = Math.max(0, Math.min(focus.decor.getHeight() - 1, y));
            int radius = Math.round(density * 9);
            cursor.setBounds((int) x - radius, (int) y - radius, (int) x + radius, (int) y + radius);
            return true;
        }

        private boolean loginVisible() {
            if (authActivity || hasLoginForm(focus.decor)) return true;
            if (!focus.composeLogin) return false;
            focus.refresh(false);
            return focus.composeLogin;
        }

        private static boolean hasLoginForm(View view) {
            if (!view.isShown() || view.getWidth() == 0 || view.getHeight() == 0) return false;
            if (view instanceof WebView && isSignInUrl(((WebView) view).getUrl())) return true;
            if (view instanceof EditText && ((EditText) view).getTransformationMethod()
                    instanceof android.text.method.PasswordTransformationMethod) return true;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    if (hasLoginForm(group.getChildAt(i))) return true;
                }
            }
            return false;
        }

        private static boolean isSignInUrl(String url) {
            if (url == null) return false;
            Uri uri = Uri.parse(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            if ("idmsa.apple.com".equalsIgnoreCase(host) || "appleid.apple.com".equalsIgnoreCase(host)
                    || "account.apple.com".equalsIgnoreCase(host)) return true;
            String path = uri.getPath();
            return "buy.itunes.apple.com".equalsIgnoreCase(host) && path != null
                    && path.toLowerCase(java.util.Locale.ROOT).contains("authenticate");
        }

        void hide() {
            clickArmed = false;
            if (cursor.getCallback() != null) focus.decor.getOverlay().remove(cursor);
        }
    }

    private static final class PointerDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            paint.setColor(Color.BLACK);
            canvas.drawCircle(bounds.centerX(), bounds.centerY(), bounds.width() / 2f, paint);
            paint.setColor(0xff93e5ff);
            canvas.drawCircle(bounds.centerX(), bounds.centerY(), bounds.width() / 3f, paint);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static final class TrackKeys {
        private final IntConsumer mediaCommand;
        private final HashMap<Integer, Long> presses = new HashMap<>();

        TrackKeys(IntConsumer mediaCommand) { this.mediaCommand = mediaCommand; }

        boolean onKey(KeyEvent event) {
            int key = event.getKeyCode();
            int trackKey;
            switch (key) {
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                case KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD:
                    trackKey = KeyEvent.KEYCODE_MEDIA_NEXT;
                    break;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                case KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD:
                    trackKey = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                    break;
                case KeyEvent.KEYCODE_MEDIA_STOP:
                    trackKey = KeyEvent.KEYCODE_MEDIA_STOP;
                    break;
                default:
                    return false;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) presses.put(key, event.getDownTime());
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                Long downTime = presses.get(key);
                if (downTime != null && downTime == event.getDownTime()) {
                    presses.remove(key);
                    if (!event.isCanceled()) mediaCommand.accept(trackKey);
                }
                return true;
            }
            return false;
        }

        void clear() { presses.clear(); }
    }

    private enum Zone { CONTENT, TOP, PLAYER }

    private static final class Focus {
        private static final long SCROLL_SETTLE_MS = 180;
        private final ViewGroup decor;
        private final FocusRing ring;
        private final Rect screen = new Rect();
        private final Rect contentViewport = new Rect();
        private final int minimumVisibleHeight;
        private final int[] location = new int[2];
        private final List<Target> targets = new ArrayList<>();
        private final List<Target> obscuredContent = new ArrayList<>();
        private final List<Target> scrollers = new ArrayList<>();
        private final Runnable refreshAfterAction;
        private final EnumMap<Zone, Target> remembered = new EnumMap<>(Zone.class);
        private final HashSet<Integer> interceptedKeys = new HashSet<>();
        private Zone zone = Zone.CONTENT;
        private Target current;
        private boolean active;
        private boolean composeLogin;
        private int heldVerticalKey = KeyEvent.KEYCODE_UNKNOWN;
        private Target verticalOrigin;
        private boolean verticalShortcutTaken;
        private boolean skipConfirm;
        private Runnable pendingScroll;

        Focus(ViewGroup decor) {
            this.decor = decor;
            float density = decor.getResources().getDisplayMetrics().density;
            this.ring = new FocusRing(density);
            this.minimumVisibleHeight = Math.round(26f * density);
            this.refreshAfterAction = () -> {
                if (active && this.decor.isAttachedToWindow()) refresh(true);
            };
        }

        boolean onKey(KeyEvent event) {
            final int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
                cancelVertical();
                cancelScroll();
                skipConfirm = true;
                if (active) scheduleRefresh();
                return false;
            }
            final boolean vertical = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
            final boolean horizontal = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
            final boolean confirm = isConfirm(key);
            final boolean page = key == KeyEvent.KEYCODE_PAGE_UP || key == KeyEvent.KEYCODE_PAGE_DOWN;
            if (!(vertical || horizontal || confirm || page)) return false;

            if (event.getAction() == KeyEvent.ACTION_UP && interceptedKeys.remove(key)) {
                if (key == heldVerticalKey) {
                    if (!event.isCanceled()) tryVerticalShortcut(event);
                    cancelVertical();
                }
                if (confirm && active && !skipConfirm && !event.isCanceled() && !imeVisible() && refresh(false)) {
                    current.activate(AccessibilityNodeInfo.ACTION_CLICK);
                    scheduleRefresh();
                }
                return true;
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN || decor.getWidth() == 0 || decor.getHeight() == 0) return false;
            if (imeVisible()) {
                hide();
                return false;
            }
            boolean initialPress = event.getRepeatCount() == 0;
            if (!initialPress && !interceptedKeys.contains(key)) return false;
            if (vertical && !initialPress && (key != heldVerticalKey || verticalShortcutTaken)) return true;

            boolean hadFocus = current != null && active;
            active = true;
            boolean hasFocus = refresh(true);
            if (composeLogin) return false;
            if (!hasFocus) return !initialPress;
            interceptedKeys.add(key);
            if (vertical) {
                if (initialPress) {
                    cancelVertical();
                    heldVerticalKey = key;
                    verticalOrigin = zone == Zone.CONTENT ? current : null;
                } else {
                    tryVerticalShortcut(event);
                }
                if (hadFocus && !verticalShortcutTaken) {
                    shortVertical(key);
                    verticalShortcutTaken = zone == outerZone(key);
                }
                return true;
            }
            cancelVertical();
            if (confirm) {
                cancelScroll();
                if (initialPress) skipConfirm = !hadFocus;
                if (event.isLongPress() && current.has(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                    skipConfirm = current.activate(AccessibilityNodeInfo.ACTION_LONG_CLICK);
                    scheduleRefresh();
                }
                return true;
            }
            if (!hadFocus) return true;
            if (page) {
                if (zone == Zone.CONTENT) {
                    scroll(key == KeyEvent.KEYCODE_PAGE_UP ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN, true);
                }
            } else {
                moveWithinZone(key);
            }
            return true;
        }

        private void tryVerticalShortcut(KeyEvent event) {
            // Input can arrive late when the UI is busy; the event timestamps describe the actual hold.
            boolean held = event.isLongPress()
                    || event.getEventTime() - event.getDownTime() >= ViewConfiguration.getLongPressTimeout();
            if (!held || verticalShortcutTaken || !active || !decor.hasWindowFocus()
                    || imeVisible() || !refresh(true)) return;
            verticalShortcutTaken = switchZone(outerZone(heldVerticalKey));
            if (verticalShortcutTaken && verticalOrigin != null) remembered.put(Zone.CONTENT, verticalOrigin);
        }

        private void cancelVertical() {
            heldVerticalKey = KeyEvent.KEYCODE_UNKNOWN;
            verticalOrigin = null;
            verticalShortcutTaken = false;
        }

        private boolean imeVisible() {
            WindowInsets insets = decor.getRootWindowInsets();
            return insets != null && insets.isVisible(WindowInsets.Type.ime());
        }

        private static Zone outerZone(int key) {
            return key == KeyEvent.KEYCODE_DPAD_UP ? Zone.TOP : Zone.PLAYER;
        }

        private void shortVertical(int key) {
            if (zone == Zone.CONTENT && moveWithinZone(key)) return;
            switchZone(verticalDestination(zone, key));
        }

        private static Zone verticalDestination(Zone zone, int key) {
            Zone outer = outerZone(key);
            return zone == Zone.CONTENT || zone == outer ? outer : Zone.CONTENT;
        }

        private boolean moveWithinZone(int key) {
            Target next = inDirection(current.bounds, key);
            if (next == null) return scroll(key, false);
            cancelScroll();
            current = next;
            draw();
            return true;
        }

        private boolean switchZone(Zone destination) {
            if (destination == zone) return false;
            Target previous = remembered.get(destination);
            Target selected = firstInZone(destination, previous, current);
            if (selected == null) return false;
            cancelScroll();
            remembered.put(zone, current);
            zone = destination;
            current = selected;
            draw();
            return true;
        }

        private boolean refresh(boolean allowDefault) {
            decor.getLocationOnScreen(location);
            screen.set(location[0], location[1], location[0] + decor.getWidth(), location[1] + decor.getHeight());
            targets.clear();
            scrollers.clear();
            composeLogin = false;
            collect(decor, true);
            classifyZones();
            clipContentTargets();
            Target retained = null;
            if (current != null) {
                for (Target item : targets) {
                    if (item.zone == zone && item.sameControl(current)) {
                        retained = item;
                        break;
                    }
                }
            }
            if (retained == null && allowDefault) {
                retained = firstInZone(zone, current == null ? remembered.get(zone) : current, null);
            }
            if (retained == null && allowDefault && zone != Zone.CONTENT) {
                zone = Zone.CONTENT;
                retained = firstInZone(zone, remembered.get(zone), null);
            }
            if (retained == null && allowDefault && !targets.isEmpty()) {
                retained = targets.get(0);
                zone = retained.zone;
            }
            current = retained;
            draw();
            return current != null;
        }

        private void classifyZones() {
            // The mini-player controls are compact and share a baseline near the bottom.
            // Find that row before considering a partially visible album card or title row.
            Target row = null;
            int votes = 0;
            final float tolerance = Math.max(12f, screen.height() * .025f);
            for (Target anchor : targets) {
                if (!compactPlayerCandidate(screen, anchor.bounds)) continue;
                int count = 0;
                for (Target item : targets) {
                    if (compactPlayerCandidate(screen, item.bounds)
                            && Math.abs(item.bounds.centerY() - anchor.bounds.centerY()) <= tolerance) {
                        count++;
                    }
                }
                if (count > votes) {
                    votes = count;
                    row = anchor;
                }
            }
            final Integer playerBaseline = votes >= 2 ? row.bounds.centerY() : null;
            for (Target item : targets) item.zone = zoneFor(screen, item.bounds, playerBaseline);
        }

        private static boolean compactPlayerCandidate(Rect screen, Rect bounds) {
            return bounds.centerY() >= screen.top + screen.height() * .84f
                    && bounds.centerX() > screen.left + screen.width() * .10f
                    && bounds.centerX() < screen.right - screen.width() * .10f
                    && bounds.width() < screen.width() * .105f
                    && bounds.height() < screen.height() * .15f;
        }

        private static Zone zoneFor(Rect screen, Rect bounds, Integer playerBaseline) {
            if (bounds.centerY() <= screen.top + screen.height() * .135f
                    && bounds.height() < screen.height() * .16f
                    && bounds.width() < screen.width() * .55f) return Zone.TOP;
            if (playerBaseline != null
                    && bounds.height() < screen.height() * .16f
                    && bounds.width() < screen.width() * .55f
                    && Math.abs(bounds.centerY() - playerBaseline) <= Math.max(12f, screen.height() * .04f)
                    && bounds.centerX() >= screen.left + screen.width() * .09f
                    && bounds.centerX() <= screen.right - screen.width() * .09f) {
                return Zone.PLAYER;
            }
            return Zone.CONTENT;
        }

        private void clipContentTargets() {
            contentViewport.set(screen);
            int topControls = 0;
            int topEdge = screen.top;
            int bottomEdge = screen.bottom;
            for (Target target : targets) {
                if (target.zone == Zone.TOP) {
                    topControls++;
                    topEdge = Math.max(topEdge, target.bounds.bottom);
                } else if (target.zone == Zone.PLAYER) {
                    bottomEdge = Math.min(bottomEdge, target.bounds.top - minimumVisibleHeight / 3);
                }
            }
            if (topControls >= 2) contentViewport.top = topEdge;
            contentViewport.bottom = bottomEdge;
            obscuredContent.clear();
            targets.removeIf(target -> {
                if (target.zone != Zone.CONTENT) return false;
                if (!hasVisibleContent(target.bounds, contentViewport, minimumVisibleHeight)) {
                    obscuredContent.add(target);
                    return true;
                }
                target.bounds.intersect(contentViewport);
                return false;
            });
        }

        private static boolean hasVisibleContent(Rect bounds, Rect viewport, int minimumHeight) {
            int visibleHeight = Math.min(bounds.bottom, viewport.bottom) - Math.max(bounds.top, viewport.top);
            boolean touchesEdge = bounds.top <= viewport.top || bounds.bottom >= viewport.bottom;
            return visibleHeight * 3 >= bounds.height() * 2 && (!touchesEdge || visibleHeight >= minimumHeight);
        }

        private Target firstInZone(Zone destination, Target previous, Target origin) {
            Target best = null;
            double bestScore = Double.POSITIVE_INFINITY;
            for (Target item : targets) {
                if (item.zone != destination) continue;
                if (previous != null && item.sameControl(previous)) return item;
                double score;
                if (origin != null && destination != Zone.CONTENT) {
                    score = Math.abs(item.bounds.centerX() - origin.bounds.centerX())
                            + .25 * Math.abs(item.bounds.centerY() - origin.bounds.centerY());
                } else if (previous != null) {
                    score = Math.abs(item.bounds.centerX() - previous.bounds.centerX())
                            + Math.abs(item.bounds.centerY() - previous.bounds.centerY());
                } else {
                    score = item.bounds.top - screen.top + .15 * (item.bounds.left - screen.left);
                }
                if (score < bestScore) {
                    bestScore = score;
                    best = item;
                }
            }
            return best;
        }

        private void collect(View view, boolean root) {
            if (!view.isShown() || view.getAlpha() <= .02f) return;
            Rect bounds = new Rect();
            if (!view.getLocalVisibleRect(bounds)) return;
            view.getLocationOnScreen(location);
            // The local visible rect uses the view's scrolled content coordinates.
            bounds.offset(location[0] - view.getScrollX(), location[1] - view.getScrollY());
            if (!bounds.intersect(screen)) return;

            if (ComposeNodes.isHost(view)) {
                composeLogin |= ComposeNodes.collect(view, screen, targets, scrollers);
            } else {
                boolean scrollable = view.canScrollVertically(1) || view.canScrollVertically(-1)
                        || view.canScrollHorizontally(1) || view.canScrollHorizontally(-1);
                if (!root && scrollable) scrollers.add(new Target(view, null, 0, bounds, null));
                if (!root && !scrollable && view.isEnabled() && view.isClickable()
                        && bounds.height() < screen.height() * .83f) {
                    addTarget(targets, new Target(view, null, 0, bounds, null));
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), false);
            }
        }

        private Target inDirection(Rect origin, int direction) {
            return inDirection(origin, direction, targets);
        }

        private Target inDirection(Rect origin, int direction, List<Target> candidates) {
            Target nearest = null;
            double min = Double.POSITIVE_INFINITY;
            for (Target candidate : candidates) {
                if (candidate.zone != zone) continue;
                double score = distance(origin, candidate.bounds, direction);
                if (score < min) {
                    min = score;
                    nearest = candidate;
                }
            }
            return nearest;
        }

        private static double distance(Rect from, Rect to, int direction) {
            boolean horizontal = direction == KeyEvent.KEYCODE_DPAD_LEFT || direction == KeyEvent.KEYCODE_DPAD_RIGHT;
            boolean forward = direction == KeyEvent.KEYCODE_DPAD_RIGHT || direction == KeyEvent.KEYCODE_DPAD_DOWN;
            int centerDelta = horizontal ? to.centerX() - from.centerX() : to.centerY() - from.centerY();
            if (forward ? centerDelta <= 1 : centerDelta >= -1) return Double.POSITIVE_INFINITY;
            int cross = horizontal ? Math.abs(to.centerY() - from.centerY()) : Math.abs(to.centerX() - from.centerX());
            int separation = horizontal
                    ? Math.max(to.top - from.bottom, from.top - to.bottom)
                    : Math.max(to.left - from.right, from.left - to.right);
            // Horizontal input stays on the current row, including a title's own overflow button.
            if (horizontal && separation >= 0) return Double.POSITIVE_INFINITY;
            int main = horizontal
                    ? (forward ? to.left - from.right : from.left - to.right)
                    : (forward ? to.top - from.bottom : from.top - to.bottom);
            int span = horizontal ? Math.max(from.width(), to.width()) : Math.max(from.height(), to.height());
            // Slightly staggered neighbors still belong to the same row or column.
            if (separation >= 0 && main < 0 && Math.abs(centerDelta) < span / 2f) {
                return Double.POSITIVE_INFINITY;
            }
            return Math.max(main, 0) + .17 * Math.abs(centerDelta) + .28 * cross + 2.8 * Math.max(separation, 0);
        }

        private boolean scroll(int direction, boolean page) {
            if (zone != Zone.CONTENT || current == null) return false;
            if (pendingScroll != null) return true;
            final Target previous = current;
            final Rect anchor = previous.bounds;
            int pageAction;
            switch (direction) {
                case KeyEvent.KEYCODE_DPAD_UP: pageAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId(); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: pageAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId(); break;
                case KeyEvent.KEYCODE_DPAD_LEFT: pageAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId(); break;
                default: pageAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId();
            }
            boolean forward = direction == KeyEvent.KEYCODE_DPAD_DOWN || direction == KeyEvent.KEYCODE_DPAD_RIGHT;
            int genericAction = forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            boolean vertical = direction == KeyEvent.KEYCODE_DPAD_UP || direction == KeyEvent.KEYCODE_DPAD_DOWN;
            Target concealed = !page && vertical ? inDirection(anchor, direction, obscuredContent) : null;
            float distance = !page && vertical ? rowScrollDistance(previous.originalHeight, concealed == null ? null : concealed.bounds,
                    contentViewport, forward, minimumVisibleHeight) : 0f;
            float scrollY = forward ? distance : -distance;
            List<Target> candidates = new ArrayList<>(scrollers);
            candidates.sort((a, b) -> Long.compare(a.area(), b.area()));
            for (Target item : candidates) {
                if (!item.bounds.contains(anchor.centerX(), anchor.centerY())) continue;
                if (item.scroll(pageAction, genericAction, direction, scrollY)) {
                    Rect viewport = new Rect(item.bounds);
                    viewport.intersect(contentViewport);
                    pendingScroll = () -> {
                        pendingScroll = null;
                        if (!active || !refresh(true) || zone != Zone.CONTENT) return;
                        Rect origin = current.sameControl(previous) ? current.bounds
                                : scrollEntryBounds(anchor, viewport, direction);
                        Target next = inDirection(origin, direction);
                        if (next != null) {
                            current = next;
                            draw();
                        }
                    };
                    decor.postDelayed(pendingScroll, item.provider == null ? SCROLL_SETTLE_MS : 270L);
                    return true;
                }
            }
            return false;
        }

        private static float rowScrollDistance(int anchorHeight, Rect concealed, Rect viewport, boolean down, int minimum) {
            float distance = Math.max(minimum * 2f, anchorHeight * 1.12f);
            if (concealed != null) {
                int rowHeight = Math.max(concealed.height(), anchorHeight);
                int reveal = down ? concealed.top + rowHeight - viewport.bottom
                        : viewport.top - (concealed.bottom - rowHeight);
                distance = Math.max(distance, reveal + minimum / 4f);
            }
            return Math.min(viewport.height() * .22f, distance);
        }

        private static Rect scrollEntryBounds(Rect anchor, Rect viewport, int direction) {
            Rect edge = new Rect(anchor);
            switch (direction) {
                case KeyEvent.KEYCODE_DPAD_UP: edge.offset(0, viewport.bottom - edge.top); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: edge.offset(0, viewport.top - edge.bottom); break;
                case KeyEvent.KEYCODE_DPAD_LEFT: edge.offset(viewport.right - edge.left, 0); break;
                default: edge.offset(viewport.left - edge.right, 0);
            }
            return edge;
        }

        private void cancelScroll() {
            if (pendingScroll != null) {
                decor.removeCallbacks(pendingScroll);
                pendingScroll = null;
            }
        }

        private void scheduleRefresh() {
            decor.removeCallbacks(refreshAfterAction);
            decor.postDelayed(refreshAfterAction, 180L);
        }

        private void draw() {
            if (!active || current == null) {
                if (ring.getCallback() != null) decor.getOverlay().remove(ring);
                return;
            }
            Rect local = new Rect(current.bounds);
            local.offset(-screen.left, -screen.top);
            ring.setBounds(local);
            if (ring.getCallback() == null) decor.getOverlay().add(ring);
            ring.invalidateSelf();
        }

        void hide() {
            cancelVertical();
            cancelScroll();
            decor.removeCallbacks(refreshAfterAction);
            skipConfirm = true;
            active = false;
            if (current != null) remembered.put(zone, current);
            current = null;
            draw();
        }
    }

    private static void addTarget(List<Target> targets, Target value) {
        if (value.bounds.width() < 12 || value.bounds.height() < 12) return;
        for (Target target : targets) {
            if (target.bounds.equals(value.bounds)) return;
        }
        targets.add(value);
    }

    private static final class Target {
        final View view;
        final AccessibilityNodeProvider provider;
        final int id;
        final Rect bounds;
        final int originalHeight;
        final List<AccessibilityNodeInfo.AccessibilityAction> actions;
        Zone zone = Zone.CONTENT;

        Target(View view, AccessibilityNodeProvider provider, int id, Rect bounds,
               List<AccessibilityNodeInfo.AccessibilityAction> actions) {
            this.view = view;
            this.provider = provider;
            this.id = id;
            this.bounds = bounds;
            this.originalHeight = bounds.height();
            this.actions = actions == null ? null : new ArrayList<>(actions);
        }

        long area() { return (long) bounds.width() * bounds.height(); }

        boolean sameControl(Target target) { return view == target.view && id == target.id; }

        boolean has(int action) {
            if (actions == null) return action == AccessibilityNodeInfo.ACTION_CLICK ? view.isClickable() : view.isLongClickable();
            for (AccessibilityNodeInfo.AccessibilityAction value : actions) {
                if (value.getId() == action) return true;
            }
            return false;
        }

        boolean activate(int action) {
            if (provider != null) return provider.performAction(id, action, null);
            if (action == AccessibilityNodeInfo.ACTION_CLICK && view instanceof EditText) {
                if (!view.requestFocus()) return false;
                InputMethodManager keyboard = view.getContext().getSystemService(InputMethodManager.class);
                return keyboard != null && keyboard.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
            }
            if (action == AccessibilityNodeInfo.ACTION_CLICK) return view.performClick();
            if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) return view.performLongClick();
            return view.performAccessibilityAction(action, null);
        }

        boolean scroll(int pageAction, int genericAction, int direction, float scrollY) {
            if (provider != null) {
                if (has(pageAction)) {
                    Boolean handled = scrollY == 0f ? null : ComposeNodes.scrollBy(view, id, scrollY);
                    return handled == null ? activate(pageAction) : handled;
                }
                // A generic action can scroll the wrong axis, or go forward at the end of a row.
                return has(genericAction)
                        && !has(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId())
                        && !has(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId())
                        && !has(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId())
                        && !has(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId())
                        && activate(genericAction);
            }
            int sign = direction == KeyEvent.KEYCODE_DPAD_DOWN || direction == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
            boolean vertical = direction == KeyEvent.KEYCODE_DPAD_UP || direction == KeyEvent.KEYCODE_DPAD_DOWN;
            boolean canScroll = vertical ? view.canScrollVertically(sign) : view.canScrollHorizontally(sign);
            return canScroll && (activate(pageAction) || activate(genericAction));
        }
    }

    private static final class ComposeNodes {
        private static final String HOST = "androidx.compose.ui.platform.a";
        private static Field delegate;
        private static Field mapKeys;
        private static Method visibleNodes;
        private static Method containsId;
        private static boolean incompatible;
        private static Method lookupNode;
        private static Field semanticsNode;
        private static Field config;
        private static Method actionFor;
        private static Object scrollByKey;
        private static Field actionCallback;
        private static Method invokeScroll;
        private static boolean scrollBindingFailed;

        static boolean isHost(View view) { return HOST.equals(view.getClass().getName()); }

        static Boolean scrollBy(View view, int id, float y) {
            if (scrollBindingFailed) return null;
            try {
                if (invokeScroll == null) bindScroll(view.getClass().getClassLoader());
                Object map = visibleNodes.invoke(delegate.get(view));
                if (map == null) return null;
                Object entry = lookupNode.invoke(map, id);
                if (entry == null) return null;
                Object node = semanticsNode.get(entry);
                Object action = actionFor.invoke(null, config.get(node), scrollByKey);
                if (action == null) return null;
                Object callback = actionCallback.get(action);
                return callback == null ? null : (Boolean) invokeScroll.invoke(callback, 0f, y);
            } catch (ReflectiveOperationException | RuntimeException error) {
                scrollBindingFailed = true;
                android.util.Log.d("AppleMusicTvFocus", "Compose ScrollBy binding unavailable", error);
                return null;
            }
        }

        private static void bindScroll(ClassLoader loader) throws ReflectiveOperationException {
            // Build 1614's accessibility delegate uses this same ScrollBy action with a viewport-sized delta.
            lookupNode = visibleNodes.getReturnType().getDeclaredMethod("b", int.class);
            lookupNode.setAccessible(true);
            semanticsNode = Class.forName("w1.A", false, loader).getDeclaredField("a");
            semanticsNode.setAccessible(true);
            config = semanticsNode.getType().getDeclaredField("d");
            config.setAccessible(true);
            Field scrollKey = Class.forName("w1.p", false, loader).getDeclaredField("d");
            scrollKey.setAccessible(true);
            scrollByKey = scrollKey.get(null);
            actionFor = Class.forName("w1.r", false, loader)
                    .getDeclaredMethod("a", config.getType(), scrollKey.getType());
            actionFor.setAccessible(true);
            actionCallback = Class.forName("w1.a", false, loader).getDeclaredField("b");
            actionCallback.setAccessible(true);
            invokeScroll = Class.forName("pi.p", false, loader).getMethod("invoke", Object.class, Object.class);
            invokeScroll.setAccessible(true);
        }

        static boolean collect(View view, Rect screen, List<Target> targets, List<Target> scrollers) {
            if (incompatible) return false;
            AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
            if (provider == null) return false;
            boolean editable = false;
            boolean loginLabel = false;
            try {
                if (delegate == null) {
                    delegate = view.getClass().getDeclaredField("d0");
                    delegate.setAccessible(true);
                    visibleNodes = delegate.getType().getDeclaredMethod("s");
                    visibleNodes.setAccessible(true);
                    Class<?> mapType = visibleNodes.getReturnType();
                    mapKeys = mapType.getDeclaredField("b");
                    mapKeys.setAccessible(true);
                    containsId = mapType.getDeclaredMethod("a", int.class);
                    containsId.setAccessible(true);
                }
                Object map = visibleNodes.invoke(delegate.get(view));
                if (map == null) return false;
                int[] keys = (int[]) mapKeys.get(map);
                HashSet<Integer> seen = new HashSet<>();
                for (int id : keys) {
                    if (!seen.add(id) || !Boolean.TRUE.equals(containsId.invoke(map, id))) continue;
                    AccessibilityNodeInfo node = provider.createAccessibilityNodeInfo(id);
                    if (node == null) continue;
                    try {
                        if (!node.isVisibleToUser() || !node.isEnabled()) continue;
                        Rect bounds = new Rect();
                        node.getBoundsInScreen(bounds);
                        if (!bounds.intersect(screen)) continue;
                        editable |= node.isEditable();
                        loginLabel |= node.isPassword() || (!node.isEditable()
                                && (isAccountLabel(node.getText()) || isAccountLabel(node.getContentDescription())));
                        Target item = new Target(view, provider, id, bounds, node.getActionList());
                        if (node.isScrollable() || item.has(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                                || item.has(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                                || item.has(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId())
                                || item.has(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId())
                                || item.has(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId())
                                || item.has(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId())) {
                            scrollers.add(item);
                        }
                        if (id != -1 && item.has(AccessibilityNodeInfo.ACTION_CLICK)
                                && bounds.height() < screen.height() * .83f) addTarget(targets, item);
                    } finally {
                        node.recycle();
                    }
                }
            } catch (NoSuchFieldException | NoSuchMethodException error) {
                incompatible = true;
            } catch (ReflectiveOperationException | RuntimeException error) {
                // Compose can replace the semantics tree during a screen transition.
                android.util.Log.d("AppleMusicTvFocus", "Semantics snapshot could not be collected", error);
            }
            return editable && loginLabel;
        }

        private static boolean isAccountLabel(CharSequence value) {
            if (value == null) return false;
            String label = value.toString().toLowerCase(java.util.Locale.ROOT);
            return label.contains("apple account") || label.contains("apple-account") || label.contains("apple-id")
                    || label.contains("apple id");
        }
    }

    private static final class FocusRing extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float width;

        FocusRing(float density) { width = Math.max(4f, density * 3f); }

        @Override public void draw(Canvas canvas) {
            RectF frame = new RectF(getBounds());
            frame.inset(width, width);
            if (frame.isEmpty()) return;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width * 2f);
            paint.setColor(Color.BLACK);
            canvas.drawRoundRect(frame, width * 2, width * 2, paint);
            paint.setStrokeWidth(width);
            paint.setColor(0xff93e5ff);
            canvas.drawRoundRect(frame, width * 2, width * 2, paint);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
