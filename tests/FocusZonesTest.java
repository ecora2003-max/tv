import android.graphics.Rect;
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
        expectZone(zone, screen, playPause, null, "CONTENT");
        if (!(Boolean) anchor.invoke(null, screen, playPause)) throw new AssertionError("Play button should anchor the bar");
        if ((Boolean) anchor.invoke(null, screen, partialCover)) throw new AssertionError("A cover must not anchor the bar");
        System.out.println("Focus zone classification checks passed");
    }
    static void expectZone(Method method, Rect screen, Rect control, Integer baseline, String expected) throws Exception {
        Object actual = method.invoke(null, screen, control, baseline);
        if (!expected.equals(actual.toString())) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
