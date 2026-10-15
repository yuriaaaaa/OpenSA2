package org.libsdl.app;

import android.view.InputDevice;
import android.view.KeyEvent;

import java.util.Locale;

/**
 * OpenSA2's on-screen controller as an SDL joystick (an Xbox-style pad: A B X Y, LB RB, Back
 * Start, both sticks, both triggers, the D-pad), so the player hands it to the game like any
 * Bluetooth pad. It lives in SDL's package because SDL's joystick calls are package-private; SDL's
 * own files are unchanged.
 *
 * Slots: the player gives each new pad the lowest free XInput slot, and the game reads player one
 * from slot 0. So the touch pad is added only while no real controller is connected, before SDL
 * hears of any real one, and taken away the moment one connects, so the real controller takes
 * slot 0. Devices that only look like controllers (Bluetooth headphones and remotes report
 * gamepad or D-pad sources for their media keys) are not counted as controllers, and SDL is told
 * to forget them, so they never take player one's slot.
 */
public final class Sa2VirtualPad {
    public static final int DEVICE_ID = 0x5A20001;

    // SDL_GamepadButton bits of the Android mapping (SDL_CreateMappingForAndroidGamepad).
    public static final int A = 1, B = 1 << 1, X = 1 << 2, Y = 1 << 3, BACK = 1 << 4, START = 1 << 6,
            LSTICK = 1 << 7, RSTICK = 1 << 8, LB = 1 << 9, RB = 1 << 10,
            UP = 1 << 11, DOWN = 1 << 12, LEFT = 1 << 13, RIGHT = 1 << 14;
    private static final int[] KEYS = {
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_SELECT, 0, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_THUMBL,
        KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    };
    private static final int BUTTON_MASK = A | B | X | Y | BACK | START | LSTICK | RSTICK | LB | RB | UP | DOWN | LEFT | RIGHT;

    private static boolean sInstalled, sWanted = true, sAdded, sPolled;
    private static final java.util.Set<Integer> sForgotten = new java.util.HashSet<>();
    private static int sButtons;
    private static final float[] sAxes = {0, 0, 0, 0, -1, -1};   // LX LY RX RY LT RT (triggers -1 = up)

    public interface Listener {
        /** A real controller connected (true) or the last one went (false). */
        void controllerPresent(boolean present);
    }

    private static Listener sListener;

    private Sa2VirtualPad() {}

    /** Before SDL starts (before the activity's super.onCreate): SDL then asks this handler. */
    public static void install(Listener l) {
        sListener = l;
        if (sInstalled) return;
        sInstalled = true;
        SDLControllerManager.mJoystickHandler = new Handler();
    }

    /** Whether the touch pad should exist (touch controls switched on). */
    public static synchronized void setWanted(boolean wanted) {
        sWanted = wanted;
        sync();
    }

    public static boolean looksLikeController(InputDevice device) {
        if (device == null || device.isVirtual()) return false;
        int s = device.getSources();
        boolean candidate = (s & InputDevice.SOURCE_CLASS_JOYSTICK) != 0
                || (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (s & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
        if (!candidate) return false;
        String name = device.getName() == null ? "" : device.getName().toLowerCase(Locale.ROOT);
        if (name.contains("consumer control") || name.contains("headset") || name.contains("headphone")
                || name.contains("earbud") || name.contains("avrcp") || name.contains("buds")) return false;
        if ((s & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) return true;
        boolean[] has = device.hasKeys(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_START);
        return has[0] || has[1] || has[2];
    }

    public static boolean realControllerConnected() {
        for (int id : InputDevice.getDeviceIds()) {
            if (looksLikeController(InputDevice.getDevice(id))) return true;
        }
        return false;
    }

    private static synchronized void sync() {
        if (!sPolled) return;   // SDL's joysticks are not up yet: the first poll adds the pad
        boolean want = sWanted && !realControllerConnected();
        if (want && !sAdded) {
            SDLControllerManager.nativeAddJoystick(DEVICE_ID, "OpenSA2 Touch", "opensa2-touch", 0, 0,
                    BUTTON_MASK, 6, 0x3F, 0, false, false, false, false);
            sAdded = true;
            for (int i = 0; i < 6; i++) SDLControllerManager.onNativeJoy(DEVICE_ID, i, sAxes[i]);
        } else if (!want && sAdded) {
            release();
            SDLControllerManager.nativeRemoveJoystick(DEVICE_ID);
            sAdded = false;
        }
    }

    public static synchronized boolean active() {
        return sAdded;
    }

    /** The whole pad's state: SDL button bits, sticks -1..1 (down positive), triggers 0..1. */
    public static synchronized void set(int buttons, float lx, float ly, float rx, float ry, float lt, float rt) {
        if (!sAdded) return;
        int changed = buttons ^ sButtons;
        for (int i = 0; i < KEYS.length; i++) {
            if ((changed & (1 << i)) == 0 || KEYS[i] == 0) continue;
            if ((buttons & (1 << i)) != 0) SDLControllerManager.onNativePadDown(DEVICE_ID, KEYS[i], 0);
            else SDLControllerManager.onNativePadUp(DEVICE_ID, KEYS[i], 0);
        }
        sButtons = buttons;
        float[] want = {lx, ly, rx, ry, lt * 2 - 1, rt * 2 - 1};
        for (int i = 0; i < 6; i++) {
            if (want[i] != sAxes[i]) {
                sAxes[i] = want[i];
                SDLControllerManager.onNativeJoy(DEVICE_ID, i, want[i]);
            }
        }
    }

    public static synchronized void release() {
        if (sAdded) set(0, 0, 0, 0, 0, 0, 0);
    }

    /** SDL's handler, with the touch pad first and look-alike controllers taken out. */
    static final class Handler extends SDLJoystickHandler {
        private boolean mHadReal;

        @Override
        synchronized void pollInputDevices() {
            sPolled = true;
            boolean real = realControllerConnected();
            synchronized (Sa2VirtualPad.class) {
                boolean want = sWanted && !real;
                if (!want && sAdded) {
                    release();
                    SDLControllerManager.nativeRemoveJoystick(DEVICE_ID);
                    sAdded = false;
                }
                if (want && !sAdded) {
                    SDLControllerManager.nativeAddJoystick(DEVICE_ID, "OpenSA2 Touch", "opensa2-touch", 0, 0,
                            BUTTON_MASK, 6, 0x3F, 0, false, false, false, false);
                    sAdded = true;
                    for (int i = 0; i < 6; i++) SDLControllerManager.onNativeJoy(DEVICE_ID, i, sAxes[i]);
                }
            }
            super.pollInputDevices();
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice d = InputDevice.getDevice(id);
                if (d != null && SDLControllerManager.isDeviceSDLJoystick(id) && !looksLikeController(d)
                        && sForgotten.add(id)) {
                    SDLControllerManager.nativeRemoveJoystick(id);
                }
            }
            if (real != mHadReal) {
                mHadReal = real;
                Listener l = sListener;
                if (l != null) l.controllerPresent(real);
            }
        }
    }
}
