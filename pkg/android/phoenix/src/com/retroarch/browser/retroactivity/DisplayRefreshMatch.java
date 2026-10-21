package com.retroarch.browser.retroactivity;

import android.app.Activity;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import com.retroarch.browser.preferences.util.ConfigFile;
import com.retroarch.browser.preferences.util.UserPreferences;

/**
 * HB: 화면 모드를 RetroArch 의 화면 주사율 설정(video_refresh_rate)에 맞춘다.
 *
 * 120Hz 같은 높은 주사율 화면(RG 476H 등)에서 앱이 바라는 주사율을 말하지 않으면 시스템은 높은 모드로 돈다.
 * RetroArch 는 video_refresh_rate(기본 60)를 화면 주사율로 알고 소리 속도에 맞춰 프레임을 만드므로, 120Hz 에서는
 * 프레임이 2칸(16.7ms)씩 고르게 보이지 못하고 1칸 · 3칸이 엇갈리는 구간이 주기적으로 생긴다(틈틈이 느려 보임).
 *
 * 실행 때 REFRESH 값이 없으면 그 설정에 가장 가까운 모드(같은 해상도)를 고른다.
 * REFRESH 를 주면 예전처럼 그 값을 쓴다(REFRESH 0 = 시스템에 맡김).
 */
final class DisplayRefreshMatch
{
  private static final String TAG = "HBRefresh";
  private static final float DEFAULT_REFRESH = 60f;

  private DisplayRefreshMatch() {}

  static void apply(Activity activity, String configPath)
  {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
      return;
    try {
      float target = configuredRefresh(activity, configPath);
      Display display = activity.getWindowManager().getDefaultDisplay();
      if (display == null)
        return;
      Display.Mode current = display.getMode();
      Display.Mode best = null;
      for (Display.Mode m : display.getSupportedModes()) {
        if (m.getPhysicalWidth() != current.getPhysicalWidth()
            || m.getPhysicalHeight() != current.getPhysicalHeight())
          continue;
        if (best == null
            || Math.abs(m.getRefreshRate() - target) < Math.abs(best.getRefreshRate() - target))
          best = m;
      }
      if (best == null)
        return;
      WindowManager.LayoutParams params = activity.getWindow().getAttributes();
      if (params.preferredDisplayModeId == best.getModeId())
        return;
      params.preferredDisplayModeId = best.getModeId();
      activity.getWindow().setAttributes(params);
      Log.i(TAG, String.format("video_refresh_rate %.3f -> display mode %d (%.3f Hz, was %.3f Hz)",
            target, best.getModeId(), best.getRefreshRate(), current.getRefreshRate()));
    } catch (RuntimeException e) {
      Log.w(TAG, "Could not pick a display mode: " + e);
    }
  }

  private static float configuredRefresh(Activity activity, String configPath)
  {
    try {
      String path = (configPath != null && !configPath.isEmpty())
          ? configPath : UserPreferences.getDefaultConfigPath(activity);
      ConfigFile config = new ConfigFile(path);
      if (config.keyExists("video_refresh_rate")) {
        float value = config.getFloat("video_refresh_rate");
        if (value >= 20f && value <= 500f)
          return value;
      }
    } catch (RuntimeException e) {
      Log.w(TAG, "Could not read video_refresh_rate: " + e);
    }
    return DEFAULT_REFRESH;
  }
}
