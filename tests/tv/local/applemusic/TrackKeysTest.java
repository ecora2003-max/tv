package tv.local.applemusic;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static android.view.KeyEvent.*;

public final class TrackKeysTest {
    public static void main(String[] args) {
        List<Integer> commands = new ArrayList<>();
        TvControls.TrackKeys keys = new TvControls.TrackKeys(commands::add);

        expectPress(keys, KEYCODE_MEDIA_FAST_FORWARD, 1);
        expectPress(keys, KEYCODE_MEDIA_REWIND, 2);
        expectPress(keys, KEYCODE_MEDIA_NEXT, 3);
        expectPress(keys, KEYCODE_MEDIA_PREVIOUS, 4);
        expectPress(keys, KEYCODE_MEDIA_SKIP_FORWARD, 5);
        expectPress(keys, KEYCODE_MEDIA_SKIP_BACKWARD, 6);

        expectHandled(keys, ACTION_DOWN, KEYCODE_MEDIA_FAST_FORWARD, 0, 7, false);
        expectHandled(keys, ACTION_UP, KEYCODE_MEDIA_FAST_FORWARD, 0, 7, true);
        expectHandled(keys, ACTION_DOWN, KEYCODE_MEDIA_FAST_FORWARD, 0, 8, false);
        keys.clear();
        expectHandled(keys, ACTION_UP, KEYCODE_MEDIA_FAST_FORWARD, 0, 8, false);

        expectHandled(keys, ACTION_DOWN, KEYCODE_MEDIA_REWIND, 0, 10, false);
        expectHandled(keys, ACTION_UP, KEYCODE_MEDIA_REWIND, 0, 9, false);
        expectHandled(keys, ACTION_UP, KEYCODE_MEDIA_REWIND, 0, 10, false);

        expectPassedThrough(keys, KEYCODE_MEDIA_PLAY_PAUSE);
        expectPassedThrough(keys, KEYCODE_MEDIA_PAUSE);
        expectPassedThrough(keys, KEYCODE_DPAD_DOWN);

        List<Integer> expected = Arrays.asList(KEYCODE_MEDIA_NEXT, KEYCODE_MEDIA_PREVIOUS,
                KEYCODE_MEDIA_NEXT, KEYCODE_MEDIA_PREVIOUS, KEYCODE_MEDIA_NEXT,
                KEYCODE_MEDIA_PREVIOUS, KEYCODE_MEDIA_PREVIOUS);
        if (!commands.equals(expected)) throw new AssertionError("Unexpected track commands: " + commands);
        System.out.println("Track key dispatch checks passed");
    }

    private static void expectPress(TvControls.TrackKeys keys, int code, long downTime) {
        expectHandled(keys, ACTION_DOWN, code, 0, downTime, false);
        expectHandled(keys, ACTION_DOWN, code, 1, downTime, false);
        expectHandled(keys, ACTION_DOWN, code, 2, downTime, false);
        expectHandled(keys, ACTION_UP, code, 0, downTime, false);
        expectHandled(keys, ACTION_UP, code, 0, downTime, false);
    }

    private static void expectHandled(TvControls.TrackKeys keys, int action, int code, int repeat,
                                      long downTime, boolean canceled) {
        if (!keys.onKey(new KeyEvent(action, code, repeat, downTime, canceled))) {
            throw new AssertionError("Expected media key to be handled: " + code);
        }
    }

    private static void expectPassedThrough(TvControls.TrackKeys keys, int code) {
        if (keys.onKey(new KeyEvent(ACTION_DOWN, code, 0, 11, false))
                || keys.onKey(new KeyEvent(ACTION_UP, code, 0, 11, false))) {
            throw new AssertionError("Expected key to pass through: " + code);
        }
    }
}
