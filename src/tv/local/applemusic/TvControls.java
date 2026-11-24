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
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;

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
        window.setCallback((Window.Callback) Proxy.newProxyInstance(
                TvControls.class.getClassLoader(), new Class<?>[] {Window.Callback.class},
                new RemoteCallback(delegate, focus)));
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

        RemoteCallback(Window.Callback delegate, Focus focus) {
            this.delegate = delegate;
            this.focus = focus;
        }

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("dispatchKeyEvent") && focus.onKey((KeyEvent) args[0])) return true;
            if (name.equals("dispatchTouchEvent")) focus.hide();
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        }
    }

    private enum Zone { CONTENT, TOP, PLAYER }

    private static final class Focus {
        private static final long SCROLL_COOLDOWN_MS = 260;
        private final ViewGroup decor;
        private final FocusRing ring;
        private final Rect screen = new Rect();
        private final int[] location = new int[2];
        private final List<Target> targets = new ArrayList<>();
        private final List<Target> scrollers = new ArrayList<>();
        private final Runnable refreshAfterAction;
        private final Runnable longVerticalPress;
        private final EnumMap<Zone, Target> remembered = new EnumMap<>(Zone.class);
        private final HashSet<Integer> interceptedKeys = new HashSet<>();
        private Zone zone = Zone.CONTENT;
        private Target current;
        private boolean active;
        private int pendingVerticalKey = KeyEvent.KEYCODE_UNKNOWN;
        private boolean firstVerticalPress;
        private boolean verticalWasLong;
        private boolean skipConfirm;
        private long lastScroll;

        Focus(ViewGroup decor) {
            this.decor = decor;
            this.ring = new FocusRing(decor.getResources().getDisplayMetrics().density);
            this.refreshAfterAction = () -> {
                if (active && this.decor.isAttachedToWindow()) refresh(true);
            };
            this.longVerticalPress = () -> {
                if (pendingVerticalKey == KeyEvent.KEYCODE_UNKNOWN || !active
                        || !this.decor.isAttachedToWindow() || !this.decor.hasWindowFocus()) return;
                verticalWasLong = true;
                switchZone(pendingVerticalKey == KeyEvent.KEYCODE_DPAD_UP ? Zone.TOP : Zone.PLAYER);
            };
        }

        boolean onKey(KeyEvent event) {
            final int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
                cancelVertical();
                if (active) scheduleRefresh();
                return false;
            }
            final boolean vertical = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
            final boolean horizontal = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
            final boolean confirm = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                    || key == KeyEvent.KEYCODE_NUMPAD_ENTER || key == KeyEvent.KEYCODE_BUTTON_A;
            final boolean page = key == KeyEvent.KEYCODE_PAGE_UP || key == KeyEvent.KEYCODE_PAGE_DOWN;
            if (!(vertical || horizontal || confirm || page)) return false;

            if (event.getAction() == KeyEvent.ACTION_UP && interceptedKeys.remove(key)) {
                if (vertical && key == pendingVerticalKey) {
                    if (!event.isCanceled() && !verticalWasLong
                            && event.getEventTime() - event.getDownTime() >= ViewConfiguration.getLongPressTimeout()) {
                        longVerticalPress.run();
                    }
                    boolean move = !event.isCanceled() && !verticalWasLong && !firstVerticalPress;
                    cancelVertical();
                    if (move) shortVertical(key);
                } else if (confirm && !skipConfirm && !event.isCanceled() && refresh(false)) {
                    current.activate(AccessibilityNodeInfo.ACTION_CLICK);
                    scheduleRefresh();
                }
                return true;
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN || decor.getWidth() == 0 || decor.getHeight() == 0) return false;
            WindowInsets insets = decor.getRootWindowInsets();
            if (insets != null && insets.isVisible(WindowInsets.Type.ime())) {
                hide();
                return false;
            }
            if (vertical && interceptedKeys.contains(key) && event.getRepeatCount() > 0) return true;

            boolean hadFocus = current != null && active;
            active = true;
            if (!refresh(true)) return false;
            interceptedKeys.add(key);
            if (vertical) {
                if (event.getRepeatCount() == 0) {
                    cancelVertical();
                    pendingVerticalKey = key;
                    firstVerticalPress = !hadFocus;
                    verticalWasLong = false;
                    decor.postDelayed(longVerticalPress, ViewConfiguration.getLongPressTimeout());
                }
                return true;
            }
            cancelVertical();
            if (confirm) {
                if (event.getRepeatCount() == 0) skipConfirm = !hadFocus;
                if (event.isLongPress() && current.has(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                    skipConfirm = current.activate(AccessibilityNodeInfo.ACTION_LONG_CLICK);
                    scheduleRefresh();
                }
                return true;
            }
            if (!hadFocus) return true;
            if (page) {
                if (zone == Zone.CONTENT) {
                    scroll(key == KeyEvent.KEYCODE_PAGE_UP ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN);
                }
            } else {
                moveWithinZone(key);
            }
            return true;
        }

        private void cancelVertical() {
            decor.removeCallbacks(longVerticalPress);
            pendingVerticalKey = KeyEvent.KEYCODE_UNKNOWN;
        }

        private void shortVertical(int key) {
            if (!refresh(true)) return;
            if ((zone == Zone.TOP && key == KeyEvent.KEYCODE_DPAD_DOWN)
                    || (zone == Zone.PLAYER && key == KeyEvent.KEYCODE_DPAD_UP)) {
                switchZone(Zone.CONTENT);
                return;
            }
            moveWithinZone(key);
        }

        private void moveWithinZone(int key) {
            Target next = inDirection(current.bounds, key);
            if (next != null) {
                current = next;
                draw();
            } else if (zone == Zone.CONTENT) {
                scroll(key);
            }
        }

        private void switchZone(Zone destination) {
            if (!refresh(true) || destination == zone) return;
            Target previous = remembered.get(destination);
            Target selected = firstInZone(destination, previous, current);
            if (selected == null) return;
            remembered.put(zone, current);
            zone = destination;
            current = selected;
            draw();
        }

        private boolean refresh(boolean allowDefault) {
            decor.getLocationOnScreen(location);
            screen.set(location[0], location[1], location[0] + decor.getWidth(), location[1] + decor.getHeight());
            targets.clear();
            scrollers.clear();
            collect(decor, true);
            classifyZones();
            Target retained = null;
            if (current != null) {
                for (Target item : targets) {
                    if (item.zone == zone && item.sameControl(current)) {
                        retained = item;
                        break;
                    }
                }
            }
            if (retained == null && allowDefault) retained = firstInZone(zone, current, null);
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
                    && bounds.height() < screen.height() * .16f) return Zone.TOP;
            if (playerBaseline != null
                    && bounds.height() < screen.height() * .16f
                    && Math.abs(bounds.centerY() - playerBaseline) <= Math.max(12f, screen.height() * .04f)
                    && bounds.centerX() >= screen.left + screen.width() * .09f
                    && bounds.centerX() <= screen.right - screen.width() * .09f) {
                return Zone.PLAYER;
            }
            return Zone.CONTENT;
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
            bounds.offset(location[0], location[1]);
            if (!bounds.intersect(screen)) return;

            if (ComposeNodes.isHost(view)) {
                ComposeNodes.collect(view, screen, targets, scrollers);
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
            Target nearest = null;
            double min = Double.POSITIVE_INFINITY;
            for (Target candidate : targets) {
                if (candidate == current || candidate.zone != zone) continue;
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
            int main = horizontal
                    ? (forward ? to.left - from.right : from.left - to.right)
                    : (forward ? to.top - from.bottom : from.top - to.bottom);
            return Math.max(main, 0) + .17 * Math.abs(centerDelta) + .28 * cross + 2.8 * Math.max(separation, 0);
        }

        private boolean scroll(int direction) {
            if (zone != Zone.CONTENT || current == null
                    || SystemClock.uptimeMillis() - lastScroll < SCROLL_COOLDOWN_MS) return false;
            final Rect anchor = new Rect(current.bounds);
            int pageAction;
            switch (direction) {
                case KeyEvent.KEYCODE_DPAD_UP: pageAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId(); break;
                case KeyEvent.KEYCODE_DPAD_DOWN: pageAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId(); break;
                case KeyEvent.KEYCODE_DPAD_LEFT: pageAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId(); break;
                default: pageAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId();
            }
            boolean forward = direction == KeyEvent.KEYCODE_DPAD_DOWN || direction == KeyEvent.KEYCODE_DPAD_RIGHT;
            int genericAction = forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            List<Target> candidates = new ArrayList<>(scrollers);
            candidates.sort((a, b) -> Long.compare(a.area(), b.area()));
            for (Target item : candidates) {
                if (!item.bounds.contains(anchor.centerX(), anchor.centerY())) continue;
                if (item.scroll(pageAction, genericAction, direction)) {
                    lastScroll = SystemClock.uptimeMillis();
                    decor.postDelayed(() -> {
                        if (!active || zone != Zone.CONTENT) return;
                        refresh(true);
                        Target next = inDirection(anchor, direction);
                        if (next != null) {
                            current = next;
                            draw();
                        }
                    }, 180L);
                    return true;
                }
            }
            return false;
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
        final List<AccessibilityNodeInfo.AccessibilityAction> actions;
        Zone zone = Zone.CONTENT;

        Target(View view, AccessibilityNodeProvider provider, int id, Rect bounds,
               List<AccessibilityNodeInfo.AccessibilityAction> actions) {
            this.view = view;
            this.provider = provider;
            this.id = id;
            this.bounds = bounds;
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
            if (action == AccessibilityNodeInfo.ACTION_CLICK) return view.performClick();
            if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) return view.performLongClick();
            return view.performAccessibilityAction(action, null);
        }

        boolean scroll(int pageAction, int genericAction, int direction) {
            if (provider != null) {
                return (has(pageAction) && activate(pageAction))
                        || (has(genericAction) && activate(genericAction));
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

        static boolean isHost(View view) { return HOST.equals(view.getClass().getName()); }

        static void collect(View view, Rect screen, List<Target> targets, List<Target> scrollers) {
            if (incompatible) return;
            AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
            if (provider == null) return;
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
                if (map == null) return;
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
                        Target item = new Target(view, provider, id, bounds, node.getActionList());
                        if (node.isScrollable() || item.has(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                                || item.has(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                                || item.has(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId())
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
