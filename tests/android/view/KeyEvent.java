package android.view;

public final class KeyEvent {
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int KEYCODE_DPAD_UP = 19;
    public static final int KEYCODE_DPAD_DOWN = 20;
    public static final int KEYCODE_DPAD_RIGHT = 22;
    public static final int KEYCODE_MEDIA_PLAY_PAUSE = 85;
    public static final int KEYCODE_MEDIA_STOP = 86;
    public static final int KEYCODE_MEDIA_NEXT = 87;
    public static final int KEYCODE_MEDIA_PREVIOUS = 88;
    public static final int KEYCODE_MEDIA_REWIND = 89;
    public static final int KEYCODE_MEDIA_FAST_FORWARD = 90;
    public static final int KEYCODE_MEDIA_PAUSE = 127;
    public static final int KEYCODE_MEDIA_SKIP_FORWARD = 272;
    public static final int KEYCODE_MEDIA_SKIP_BACKWARD = 273;

    private final int action;
    private final int keyCode;
    private final int repeatCount;
    private final long downTime;
    private final boolean canceled;

    public KeyEvent(int action, int keyCode, int repeatCount, long downTime, boolean canceled) {
        this.action = action;
        this.keyCode = keyCode;
        this.repeatCount = repeatCount;
        this.downTime = downTime;
        this.canceled = canceled;
    }

    public int getAction() { return action; }
    public int getKeyCode() { return keyCode; }
    public int getRepeatCount() { return repeatCount; }
    public long getDownTime() { return downTime; }
    public boolean isCanceled() { return canceled; }
}
