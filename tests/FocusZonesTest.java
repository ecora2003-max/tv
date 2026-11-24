import android.graphics.Rect;
import android.view.KeyEvent;
import java.lang.reflect.Method;

class FocusZonesTest {
    public static void main(String[] args) throws Exception {
        Class<?> focus = Class.forName("tv.local.applemusic.TvControls$Focus");
        Method zone = focus.getDeclaredMethod("zoneFor", Rect.class, Rect.class, Integer.class);
        Method anchor = focus.getDeclaredMethod("compactPlayerCandidate", Rect.class, Rect.class);
        zone.setAccessible(true);
        anchor.setAccessible(true);
        Rect screen = new Rect(0, 0, 1920, 1080);
        Rect topNav = new Rect(500, 35, 657, 116);
        Rect outsideSmallMenu = new Rect(1780, 58, 1844, 125);
        Rect album = new Rect(72, 254, 478, 668);
        Rect partialCover = new Rect(490, 798, 880, 1050);
        Rect playPause = new Rect(505, 935, 585, 1008);
        Rect nowPlaying = new Rect(740, 930, 1100, 1020);
        expectZone(zone, screen, topNav, 971, "TOP");
        expectZone(zone, screen, outsideSmallMenu, 971, "TOP");
        expectZone(zone, screen, album, 971, "CONTENT");
        expectZone(zone, screen, partialCover, 971, "CONTENT");
        expectZone(zone, screen, playPause, 971, "PLAYER");
        expectZone(zone, screen, nowPlaying, 971, "PLAYER");
        Rect albumRow = new Rect(20, 928, 1900, 1009);
        expectZone(zone, screen, albumRow, 971, "CONTENT");
        expectZone(zone, screen, new Rect(20, 48, 1900, 113), 971, "CONTENT");
        expectZone(zone, screen, playPause, null, "CONTENT");
        if (!(Boolean) anchor.invoke(null, screen, playPause)) throw new AssertionError("Play button should anchor the bar");
        if ((Boolean) anchor.invoke(null, screen, partialCover)) throw new AssertionError("A cover must not anchor the bar");
        Object content = zone.invoke(null, screen, album, 971);
        Object top = zone.invoke(null, screen, topNav, 971);
        Object player = zone.invoke(null, screen, playPause, 971);
        Method destination = focus.getDeclaredMethod("verticalDestination", content.getClass(), int.class);
        destination.setAccessible(true);
        expectDestination(destination, content, KeyEvent.KEYCODE_DPAD_UP, top);
        expectDestination(destination, content, KeyEvent.KEYCODE_DPAD_DOWN, player);
        expectDestination(destination, top, KeyEvent.KEYCODE_DPAD_DOWN, content);
        expectDestination(destination, player, KeyEvent.KEYCODE_DPAD_UP, content);
        expectDestination(destination, top, KeyEvent.KEYCODE_DPAD_UP, top);
        expectDestination(destination, player, KeyEvent.KEYCODE_DPAD_DOWN, player);

        Method distance = focus.getDeclaredMethod("distance", Rect.class, Rect.class, int.class);
        distance.setAccessible(true);
        Rect rowItem = new Rect(100, 220, 460, 284);
        Rect offsetItem = new Rect(490, 234, 850, 298);
        Rect nextRow = new Rect(100, 340, 460, 404);
        expectDirection(distance, rowItem, offsetItem, KeyEvent.KEYCODE_DPAD_DOWN, false);
        expectDirection(distance, rowItem, offsetItem, KeyEvent.KEYCODE_DPAD_RIGHT, true);
        expectDirection(distance, rowItem, nextRow, KeyEvent.KEYCODE_DPAD_DOWN, true);
        expectDirection(distance, rowItem, new Rect(490, 340, 850, 404), KeyEvent.KEYCODE_DPAD_RIGHT, false);

        Method visible = focus.getDeclaredMethod("hasVisibleContent", Rect.class, Rect.class, int.class);
        visible.setAccessible(true);
        Rect viewport = new Rect(0, 120, 1920, 920);
        if ((Boolean) visible.invoke(null, albumRow, viewport, 26)
                || (Boolean) visible.invoke(null, new Rect(20, 904, 1900, 984), viewport, 26)
                || (Boolean) visible.invoke(null, new Rect(20, 1060, 1900, 1080), screen, 26)
                || !(Boolean) visible.invoke(null, new Rect(20, 854, 1900, 934), viewport, 26)) {
            throw new AssertionError("Focus targets must be substantially visible above the player");
        }
        Method scrollDistance = focus.getDeclaredMethod("rowScrollDistance", int.class, Rect.class,
                Rect.class, boolean.class, int.class);
        scrollDistance.setAccessible(true);
        Rect nearPlayer = new Rect(20, 840, 1900, 905);
        Rect underPlayer = new Rect(20, 960, 1900, 1025);
        float step = (Float) scrollDistance.invoke(null, nearPlayer.height(), underPlayer, viewport, true, 26);
        if (step < 105 || step >= viewport.height() / 4f) {
            throw new AssertionError("Scroll should reveal the concealed row within a fraction of the viewport");
        }
        System.out.println("Focus zone classification checks passed");
    }

    static void expectDestination(Method method, Object source, int key, Object expected) throws Exception {
        Object actual = method.invoke(null, source, key);
        if (actual != expected) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    static void expectDirection(Method method, Rect from, Rect to, int key, boolean reachable) throws Exception {
        double score = (Double) method.invoke(null, from, to, key);
        if (Double.isFinite(score) != reachable) {
            throw new AssertionError("Unexpected focus score for key " + key + ": " + score);
        }
    }

    static void expectZone(Method method, Rect screen, Rect control, Integer baseline, String expected) throws Exception {
        Object actual = method.invoke(null, screen, control, baseline);
        if (!expected.equals(actual.toString())) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
