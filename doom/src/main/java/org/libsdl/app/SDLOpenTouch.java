package org.libsdl.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Message;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.beloko.touchcontrols.ControlInterpreter;
import com.beloko.touchcontrols.TouchSettings;

import com.nakara.android.AppSettings;
import com.nakara.android.Utils;

/**
 * Freedoom's minimal replacement for emileb's AndroidCore SDLOpenTouch.
 *
 * org.libsdl.app2012.SDLActivity (the vendored SDL2 Java glue) calls these
 * static hooks; the original routed them into the full AndroidCore framework.
 * This version wires them to the app's own AppSettings plus the vendored
 * com.beloko.touchcontrols ControlInterpreter, and boots the engine through
 * NativeLib.init() (which never returns; the engine owns the thread).
 */
public class SDLOpenTouch
{
    static final String TAG = "SDLOpenTouch";

    static float resDiv = 1.0f;
    static boolean divDone = false;

    // The engine renders at a fixed landscape resolution for the whole
    // session (it cannot resize after init); the Game activity is locked to
    // sensorLandscape so the surface never changes aspect mid-game.
    static int fbWidth = 0;
    static int fbHeight = 0;

    public static boolean swapMouseXY = false;
    public static boolean invertMouseX = false;
    public static boolean invertMouseY = false;

    static NativeLib engine;

    public static ControlInterpreter controlInterp;

    // Bridge references set by SDLActivity so this class stays independent of
    // the package-specific SDL classes.
    static View surfaceView;
    static Runnable audioPauseCallback;
    static Runnable audioResumeCallback;
    static Runnable enableRelativeMouseCallback;

    public static void setBridge(View surface, Runnable audioPause, Runnable audioResume, Runnable enableRelativeMouse)
    {
        surfaceView = surface;
        audioPauseCallback = audioPause;
        audioResumeCallback = audioResume;
        enableRelativeMouseCallback = enableRelativeMouse;
    }

    public static void onPause(Context context)
    {
        if (audioPauseCallback != null)
            audioPauseCallback.run();
    }

    public static void onResume(Context context)
    {
        if (audioResumeCallback != null)
            audioResumeCallback.run();
    }

    public static void Setup(Activity activity, Intent intent)
    {
        AppSettings.INSTANCE.reloadSettings(activity.getApplication());

        NativeConsoleBox.init(activity);

        // fullscreen + keep screen on
        activity.requestWindowFeature(Window.FEATURE_NO_TITLE);
        activity.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        activity.getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Utils.setImmersionMode(activity);

        engine = new NativeLib();

        controlInterp = new ControlInterpreter(
                engine,
                Utils.getGameGamepadConfig(activity.getResources()),
                TouchSettings.gamePadControlsFile,
                TouchSettings.gamePadEnabled);

        int div = intent.getIntExtra("res_div", 1);
        resDiv = 1.0f / div;
    }

    public static void RunApplication(Activity activity, Intent intent, float displayWidth, float displayHeight)
    {
        String args = intent.getStringExtra("args");
        if (args == null)
            args = "";

        // The engine resolution is fixed for the whole session: always the
        // landscape dimensions of the surface, regardless of the orientation
        // the game was launched in.
        fbWidth = (int) Math.max(displayWidth, displayHeight);
        fbHeight = (int) Math.min(displayWidth, displayHeight);

        // Force emileb's GLES backend: the default (1 = Vulkan) cannot host
        // the GL-drawn touch controls, which render inside the engine's
        // swap-buffer callback. The GLES framebuffer takes its size from
        // vid_defwidth/vid_defheight (desktop default 640x480), so pass the
        // fixed landscape size, like Delta Touch's $W/$H substitution does.
        args += " +set vid_preferbackend 2 +set vid_fullscreen 1"
                + " +set vid_defwidth " + fbWidth
                + " +set vid_defheight " + fbHeight;

        String[] argsArray = Utils.createArgs(args);

        String gamePath = intent.getStringExtra("game_path");

        int options = 0;
        // The engine renders with its own GLES3 backend; the touch controls
        // draw with the matching GLES context.
        options |= 0x10; // GAME_OPTION_GLES3

        int gameType = 1; // GAME_TYPE_DOOM
        int wheelNbr = 10;

        String userFiles = gamePath + "/user_files";
        new java.io.File(userFiles).mkdirs();
        // Write autoexec.cfg with forced button bindings. GZDoom loads this
        // AFTER the user's config, so these override any existing bindings.
        // NOTE: On Android, GetUserFile("autoexec.cfg") resolves to
        // <userFiles>/uzdoom_dev/config/autoexec.cfg (see i_specialpaths_android.cpp),
        // NOT <userFiles>/autoexec.cfg. The path must match exactly.
        // Current mappings (v1.0.39):
        //   L = left mouse click (sword) + J key failsafe -> +attack
        //   R = right mouse click (magnet) — mouse-only, simple press (no hold)
        //   G = K key -> CloseGallerymenu (gallery room change; game prompts
        //       "pad k", waits for K itself; K also runs GalleryChecks ACS)
        //   (USE button removed per user request)
        try {
            String autoexecDir = userFiles + "/uzdoom_dev/config";
            new java.io.File(autoexecDir).mkdirs();
            String autoexecPath = autoexecDir + "/autoexec.cfg";
            java.io.FileWriter w = new java.io.FileWriter(autoexecPath, false);
            w.write("// Nakara Android: forced button bindings (auto-generated)\n");
            w.write("bind j +attack\n");
            w.write("bind k CloseGallerymenu\n");
            w.close();
            // Bulletproof fallback: explicitly pass -exec <path> so GZDoom loads
            // the bindings even if the [*.AutoExec] ini section has a stale path.
            // -exec files are processed after autoexec.cfg via D_MultiExec.
            String[] newArgs = new String[argsArray.length + 2];
            System.arraycopy(argsArray, 0, newArgs, 0, argsArray.length);
            newArgs[argsArray.length] = "-exec";
            newArgs[argsArray.length + 1] = autoexecPath;
            argsArray = newArgs;
        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to write autoexec.cfg", e);
        }
        String logFilename = userFiles + "/gzdoom_log.txt";
        String tmpFiles = activity.getCacheDir().getAbsolutePath();
        String sourceDir = activity.getApplicationContext().getApplicationInfo().sourceDir;
        String nativeSoPath = activity.getApplicationInfo().nativeLibraryDir;
        String pngFiles = activity.getFilesDir().getAbsolutePath();

        Utils.copyPNGAssets(activity, pngFiles);

        Log.v(TAG, "native .so path = " + nativeSoPath);
        Log.v(TAG, "gamePath = " + gamePath);
        Log.v(TAG, "userFiles = " + userFiles);

        NativeLib.audioOverride(0, 0);

        // Only secondary_path may be null in the glue; every other string is
        // dereferenced unconditionally.
        String resDir = gamePath + "/res";

        // Never returns: the engine main loop runs on this (the SDL) thread.
        NativeLib.init(pngFiles + "/", options, wheelNbr, argsArray, gameType, gamePath,
                null, logFilename, nativeSoPath, userFiles, tmpFiles, sourceDir, resDir);
    }

    public static boolean surfaceChanged(Context context, SurfaceHolder holder, int width, int height)
    {
        Log.v(TAG, "surfaceChanged: " + width + " x " + height);

        if (resDiv != 1.0f && !divDone)
        {
            holder.setFixedSize((int) ((width * resDiv) + 0.5f), (int) ((height * resDiv) + 0.5f));
            divDone = true;
            return true;
        }

        NativeLib.setScreenSize(width, height);

        if (controlInterp != null)
            controlInterp.setScreenSize(width, height);

        if (enableRelativeMouseCallback != null)
            enableRelativeMouseCallback.run();

        return false;
    }

    public static boolean onTouchEvent(MotionEvent event)
    {
        // Disabled: using custom Android overlay instead of Beloko controls.
        // The native touchcontrols system is fully replaced; forwarding touches
        // to it causes screen dimming and interferes with the custom overlay.
        return false;
    }

    public static boolean onKey(int keyCode, KeyEvent event)
    {
        int source = event.getSource();
        // Stop right mouse button being backbutton
        if ((source == InputDevice.SOURCE_MOUSE) || (source == InputDevice.SOURCE_MOUSE_RELATIVE))
        {
            return true;
        }

        // We always want the back button to do an escape
        if (keyCode == KeyEvent.KEYCODE_BACK)
        {
            if (event.getAction() == KeyEvent.ACTION_DOWN)
            {
                NativeLib.backButton();
            }
            return true;
        }

        if (controlInterp == null)
            return false;

        if (event.getAction() == KeyEvent.ACTION_DOWN)
        {
            return controlInterp.onKeyDown(keyCode, event);
        }
        else if (event.getAction() == KeyEvent.ACTION_UP)
        {
            return controlInterp.onKeyUp(keyCode, event);
        }

        return false;
    }

    // Sent by the native exit() override (Clibs_OpenTouch android_jni_inc.cpp)
    // when the engine terminates; the process must die so the next launch
    // starts with clean native state.
    static final int COMMAND_EXIT_APP = 0x8007;

    // Sent by the native vibrate channel (TouchInterfaceBase::vibrate and the
    // fire/hit haptic hooks). msg.obj is the Integer duration in milliseconds.
    static final int COMMAND_VIBRATE = 0x8005;

    public static boolean CommandHandler(Activity activity, Message msg)
    {
        if (msg.arg1 == COMMAND_EXIT_APP)
        {
            Log.v(TAG, "COMMAND_EXIT_APP: finishing");
            activity.finish();
            android.os.Process.killProcess(android.os.Process.myPid());
            return true;
        }
        if (msg.arg1 == COMMAND_VIBRATE)
        {
            // Read the pref straight from disk: the :Game process does not run
            // AppSettings.reloadSettings, so the static field is unreliable here.
            if (AppSettings.getBoolOption(activity, "vibrate", true))
            {
                int ms = (msg.obj instanceof Integer) ? (Integer) msg.obj : 30;
                try
                {
                    Vibrator v;
                    if (Build.VERSION.SDK_INT >= 31)
                    {
                        VibratorManager vm = (VibratorManager)
                            activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                        v = vm.getDefaultVibrator();
                    }
                    else
                    {
                        v = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
                    }
                    if (v != null && v.hasVibrator())
                    {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        {
                            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                        }
                        else
                        {
                            v.vibrate(ms);
                        }
                    }
                }
                catch (Exception e)
                {
                    Log.w(TAG, "vibrate failed", e);
                }
            }
            return true;
        }
        return false;
    }
}
