package com.retroarch.browser.retroactivity;

import android.util.Log;
import android.view.PointerIcon;
import android.view.View;
import android.view.WindowManager;
import android.content.Intent;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import com.retroarch.browser.preferences.util.ConfigFile;
import com.retroarch.browser.preferences.util.UserPreferences;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class RetroActivityFuture extends RetroActivityCamera {

  // Tracks activity lifecycle state for MainMenuActivity resume detection
  public static volatile boolean isRunning = false;

  // If set to true then RetroArch will completely exit when it loses focus
  private boolean quitfocus = false;

  // Top-level window decor view
  private View mDecorView;

  // Constants used for Handler messages
  private static final int HANDLER_WHAT_TOGGLE_IMMERSIVE = 1;
  private static final int HANDLER_WHAT_TOGGLE_POINTER_CAPTURE = 2;
  private static final int HANDLER_WHAT_TOGGLE_POINTER_NVIDIA = 3;
  private static final int HANDLER_WHAT_TOGGLE_POINTER_ICON = 4;
  private static final int HANDLER_ARG_TRUE = 1;
  private static final int HANDLER_ARG_FALSE = 0;
  private static final int HANDLER_MESSAGE_DELAY_DEFAULT_MS = 300;

  // Handler used for UI events
  private final Handler mHandler = new Handler(Looper.getMainLooper()) {
    @Override
    public void handleMessage(Message msg) {
      boolean state = (msg.arg1 == HANDLER_ARG_TRUE) ? true : false;

      if (msg.what == HANDLER_WHAT_TOGGLE_IMMERSIVE) {
        attemptToggleImmersiveMode(state);
      } else if (msg.what == HANDLER_WHAT_TOGGLE_POINTER_CAPTURE) {
        attemptTogglePointerCapture(state);
      } else if (msg.what == HANDLER_WHAT_TOGGLE_POINTER_NVIDIA) {
        attemptToggleNvidiaCursorVisibility(state);
      } else if (msg.what == HANDLER_WHAT_TOGGLE_POINTER_ICON) {
        attemptTogglePointerIcon(state);
      }
    }
  };

  @Override
  public void onCreate(Bundle savedInstanceState) {
    // HB: RetroArch 가 설정을 읽기 전에 — 바로 켤 때도 기본 자료(글꼴 · 아이콘 · 코어 정보)를 풀게 한다(BundleAssetsCheck)
    BundleAssetsCheck.apply(this, getIntent().getStringExtra("CONFIGFILE"));
    // HB: 상태저장 그림을 설정 파일마다 한 번 켠다(해방런처 세이브 창)
    SavestateThumbDefault.apply(this, getIntent().getStringExtra("CONFIGFILE"));
    super.onCreate(savedInstanceState);

    isRunning = true;
    mDecorView = getWindow().getDecorView();

    // If QUITFOCUS parameter is provided then enable that Retroarch quits when focus is lost
    quitfocus = getIntent().hasExtra("QUITFOCUS");
  }

  @Override
  public void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    
    // Extract game parameters from new intent
    String newRom = intent.getStringExtra("ROM");
    String newCore = intent.getStringExtra("LIBRETRO");
    
    // Get current intent parameters for comparison
    Intent currentIntent = getIntent();
    String currentRom = currentIntent != null ? currentIntent.getStringExtra("ROM") : null;
    String currentCore = currentIntent != null ? currentIntent.getStringExtra("LIBRETRO") : null;
    
    
    // HB: RELOADSAVES — 바깥에서 세이브 파일을 바꿨다(해방런처 세이브 동기화). 떠 있던 게임은 쓰지 않고 디스크에서 새로 켠다.
    boolean reloadSaves = "1".equals(intent.getStringExtra("RELOADSAVES"));

    // Check if we're trying to launch different content
    // HB: 같은 게임이라도 ENTRYSLOT(그 상태저장에서 시작)이 오면 다시 띄운다 — 그대로 두면 이어 하기가 된다.
    if ((newRom != null && !newRom.equals(currentRom)) ||
        (newCore != null && !newCore.equals(currentCore)) ||
        (newRom != null && entrySlot(intent) != null) ||
        reloadSaves) {
      // HB: 끝내기 전에 지금 게임을 나갈 때처럼 저장한다(롬저장, AUTOSAVESTATE 면 .state.auto).
      // 홈에서 온 요청이면 이미 썼으니 롬저장만 다시 쓰고 상태저장은 건너뛴다.
      // RELOADSAVES 면 쓰지 않는다 — 메모리의 옛 세이브가 바깥에서 바꾼 파일을 덮는다. 뒤로 갈 때 이미 썼다(hb8).
      if (reloadSaves)
        Log.i("HBLeaveSave", "RELOADSAVES: relaunching without saving the running game");
      else
        leaveSave();
      // HB: 업스트림 #18587 — 새 작업으로 다시 띄운 뒤 끝낸다. 끝내기만 하면 다시 보내지 않는
      // 프런트엔드(Pegasus 등)는 다른 게임을 한 번 더 눌러야 했다.
      Intent restartIntent = new Intent(intent);
      restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
      startActivity(restartIntent);
      System.exit(0);
    } else {
      // Same content, just update intent
      setIntent(intent);
    }
  }

  /** HB: 네이티브(platform_unix.c 가 RegisterNatives 로 붙임) — 메인 스레드가 나갈 때처럼 저장하고 끝나면(최대 5초) 돌아온다. */
  private native void hbNativeLeaveSave();

  private void leaveSave() {
    try {
      hbNativeLeaveSave();
    } catch (UnsatisfiedLinkError e) {
      // 네이티브가 아직 안 올라왔다 — 저장할 게임도 없다.
      Log.w("HBLeaveSave", "native side not ready: " + e);
    }
  }

  /** HB: ENTRYSLOT — "auto" · "0" · 앞자리 0 없는 1~999 만(네이티브 hb_entry_slot_valid 와 같다). 그 밖은 null. */
  private static String entrySlot(Intent intent) {
    String v = intent.getStringExtra("ENTRYSLOT");
    if (v == null)
      return null;
    if (v.equals("auto") || v.matches("0|[1-9][0-9]{0,2}"))
      return v;
    Log.w("HBEntrySlot", "Ignoring ENTRYSLOT \"" + v + "\"");
    return null;
  }

  // HB: 나갈 때 저장(롬저장 · AUTOSAVESTATE 의 .state.auto)은 onSaveInstanceState(onStop 뒤)에서 한다.
  // 슬립이면 그때 화면은 이미 꺼져 기기가 잠들 수 있다 — onPause 부터 그 저장이 끝날 때까지(길어야 30초) CPU 를 깨워 둔다.
  private PowerManager.WakeLock leaveWakeLock;

  private void holdForLeave(boolean hold) {
    try {
      if (hold) {
        if (leaveWakeLock == null) {
          PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
          leaveWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HBRetroArch:leave");
          leaveWakeLock.setReferenceCounted(false);
        }
        leaveWakeLock.acquire(30000);
      } else if (leaveWakeLock != null && leaveWakeLock.isHeld()) {
        leaveWakeLock.release();
      }
    } catch (RuntimeException e) {
      Log.w("HBLeaveSave", "wake lock: " + e);
    }
  }

  @Override
  public void onPause() {
    holdForLeave(true);
    super.onPause();
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    try {
      super.onSaveInstanceState(outState);
    } finally {
      holdForLeave(false);
    }
  }

  @Override
  public void onResume() {
    holdForLeave(false);
    super.onResume();

    setSustainedPerformanceMode(sustainedPerformanceMode);

    // Check for Android UI specific parameters
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      String refresh = getIntent().getStringExtra("REFRESH");

      // If REFRESH parameter is provided then try to set refreshrate accordingly
      if (refresh != null) {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.preferredRefreshRate = Integer.parseInt(refresh);
        getWindow().setAttributes(params);
      } else {
        // HB: 없으면 화면 모드를 video_refresh_rate(기본 60)에 맞춘다 — 120Hz 화면의 주기적 끊김(DisplayRefreshMatch)
        DisplayRefreshMatch.apply(this, getIntent().getStringExtra("CONFIGFILE"));
      }
    }

    // Checks if Android versions is above 9.0 (28) and enable the screen to write over notch if the user desires
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      ConfigFile configFile = new ConfigFile(UserPreferences.getDefaultConfigPath(this));
      try {
        if (configFile.getBoolean("video_notch_write_over_enable")) {
          getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
      } catch (Exception e) {
        Log.w("Key doesn't exist yet.", e.getMessage());
      }
    }
  }

  @Override
  public void onStop() {
    super.onStop();

    // If QUITFOCUS parameter was set then completely exit RetroArch when focus is lost
    if (quitfocus) System.exit(0);
  }

  @Override
  public void onDestroy() {
    holdForLeave(false);
    super.onDestroy();
    isRunning = false;
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);

    mHandlerSendUiMessage(HANDLER_WHAT_TOGGLE_IMMERSIVE, hasFocus);

    try {
      ConfigFile configFile = new ConfigFile(UserPreferences.getDefaultConfigPath(this));
      if (configFile.getBoolean("input_auto_mouse_grab")) {
        inputGrabMouse(hasFocus);
      }
    } catch (Exception e) {
      Log.w("[onWindowFocusChanged] exception thrown:", e.getMessage());
    }
  }

  private void mHandlerSendUiMessage(int what, boolean state) {
    int arg1 = (state ? HANDLER_ARG_TRUE : HANDLER_ARG_FALSE);
    int arg2 = -1;

    Message message = mHandler.obtainMessage(what, arg1, arg2);
    mHandler.sendMessageDelayed(message, HANDLER_MESSAGE_DELAY_DEFAULT_MS);
  }

  public void inputGrabMouse(boolean state) {
    mHandlerSendUiMessage(HANDLER_WHAT_TOGGLE_POINTER_CAPTURE, state);
    mHandlerSendUiMessage(HANDLER_WHAT_TOGGLE_POINTER_NVIDIA, state);
    mHandlerSendUiMessage(HANDLER_WHAT_TOGGLE_POINTER_ICON, state);
  }

  private void attemptToggleImmersiveMode(boolean state) {
    // Attempt to toggle "Immersive Mode" for Android 4.4 (19) and up
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      try {
        if (state) {
          mDecorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LOW_PROFILE
            | View.SYSTEM_UI_FLAG_IMMERSIVE);
        } else {
          mDecorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
      } catch (Exception e) {
        Log.w("[attemptToggleImmersiveMode] exception thrown:", e.getMessage());
      }
    }
  }

  private void attemptTogglePointerCapture(boolean state) {
    // Attempt requestPointerCapture for Android 8.0 (26) and up
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      try {
        if (state) {
          mDecorView.requestPointerCapture();
        } else {
          mDecorView.releasePointerCapture();
        }
      } catch (Exception e) {
        Log.w("[attemptTogglePointerCapture] exception thrown:", e.getMessage());
      }
    }
  }

  private void attemptToggleNvidiaCursorVisibility(boolean state) {
    // Attempt setCursorVisibility for Android 4.1 (16) and up
    // only works if NVIDIA Android extensions for NVIDIA Shield are available
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
      try {
        boolean cursorVisibility = !state;
        Method mInputManager_setCursorVisibility = InputManager.class.getMethod("setCursorVisibility", boolean.class);
        InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        mInputManager_setCursorVisibility.invoke(inputManager, cursorVisibility);
      } catch (NoSuchMethodException e) {
        // Extensions were not available so do nothing
      } catch (Exception e) {
        Log.w("[attemptToggleNvidiaCursorVisibility] exception thrown:", e.getMessage());
      }
    }
  }

  private void attemptTogglePointerIcon(boolean state) {
    // Attempt setPointerIcon for Android 7.x (24, 25) only
    // For Android 8.0+, requestPointerCapture is used
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      try {
        if (state) {
          PointerIcon nullPointerIcon = PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL);
          mDecorView.setPointerIcon(nullPointerIcon);
        } else {
          // Restore the pointer icon to it's default value
          mDecorView.setPointerIcon(null);
        }
      } catch (Exception e) {
        Log.w("[attemptTogglePointerIcon] exception thrown:", e.getMessage());
      }
    }
  }
}
