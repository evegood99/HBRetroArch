package com.retroarch.browser.retroactivity;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import com.retroarch.browser.preferences.util.ConfigFile;
import com.retroarch.browser.preferences.util.UserPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * HB: 게임 화면을 바로 켤 때도 기본 자료(assets — 글꼴 · 아이콘 · 코어 정보)를 풀게 한다.
 *
 * RetroArch 는 설정의 bundle_assets_extract_version_current 가 _last_version 과 다를 때만 자료를 푼다.
 * 그런데 current(와 APK 경로 bundle_assets_src_path)를 적는 일은 아이콘으로 켤 때의 첫 화면
 * (MainMenuActivity → UserPreferences.updateConfigFile)만 한다. 해방런처 · ES-DE 처럼 RetroActivityFuture 를
 * 바로 켜면 새로 설치 · 업데이트한 뒤에도 자료를 풀지 않고, 한글이 '??' 인 비트맵 글꼴 · 빈 아이콘으로 뜬다.
 *
 * RetroArch 가 설정을 읽기 전에(onCreate 맨 앞) 확인한다.
 * 1. 설정 파일을 이 앱이 쓸 수 없으면(adb 로 써 넣어 주인이 셸인 파일 등) 같은 내용으로 다시 만들어 앱 것으로 바꾼다.
 *    — 쓸 수 없으면 자료를 푼 기록도, 메뉴에서 바꾼 설정도 저장되지 않는다.
 * 2. 이 APK 의 버전으로 아직 안 풀었으면 current · APK 경로를 적는다(첫 화면이 하던 일).
 * 3. 버전은 같은데 자료 폴더가 비었으면(같은 버전을 새로 설치하고 옛 설정을 되돌린 경우) last 를 0 으로 되돌려 다시 풀게 한다.
 */
final class BundleAssetsCheck
{
  private static final String TAG = "HBAssets";

  private BundleAssetsCheck() {}

  static void apply(Activity activity, String configPath)
  {
    try {
      String path = (configPath != null && !configPath.isEmpty())
          ? configPath : UserPreferences.getDefaultConfigPath(activity);
      File file = new File(path);
      makeWritable(file);

      ApplicationInfo ai = activity.getApplicationInfo();
      int version = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionCode;
      ConfigFile config = new ConfigFile();
      if (file.isFile())
        config.open(path);
      if (config.keyExists("bundle_assets_extract_enable") && !config.getBoolean("bundle_assets_extract_enable"))
        return;   // 사용자가 끈 것은 따른다

      int last = intOr(config, "bundle_assets_extract_last_version");
      int current = intOr(config, "bundle_assets_extract_version_current");
      boolean missing = assetsMissing(ai.dataDir);
      if (version == last && !missing)
        return;   // 이 버전으로 이미 풀었다
      boolean ready = current == version && current != last
          && ai.sourceDir.equals(config.getString("bundle_assets_src_path"));
      if (ready)
        return;   // 이미 풀 준비가 됐다(아이콘으로 켠 첫 화면이 적음)

      config.setString("bundle_assets_src_path", ai.sourceDir);
      config.setString("bundle_assets_dst_path", ai.dataDir);
      config.setString("bundle_assets_dst_path_subdir", "assets");
      config.setInt("bundle_assets_extract_version_current", version);
      if (version == last)
        config.setInt("bundle_assets_extract_last_version", 0);
      config.write(path);
      Log.i(TAG, "assets will be extracted: version " + version + ", last " + last
          + (missing ? ", assets missing" : ""));
    } catch (Exception e) {
      Log.w(TAG, "Could not check bundled assets: " + e);
    }
  }

  private static int intOr(ConfigFile config, String key)
  {
    try {
      return config.keyExists(key) ? config.getInt(key) : 0;
    } catch (RuntimeException e) {
      return 0;
    }
  }

  /** 자료를 풀면 <dataDir>/assets(메뉴 그림 · 글꼴)와 <dataDir>/info(코어 정보)가 생긴다. 둘 중 하나라도 없으면 다시 푼다. */
  private static boolean assetsMissing(String dataDir)
  {
    File assets = new File(dataDir, "assets");
    String[] info = new File(dataDir, "info").list();
    return !assets.isDirectory() || info == null || info.length == 0;
  }

  /** 앱이 쓸 수 없는 설정 파일이면 같은 폴더에 같은 내용으로 새로 만들어 바꿔 넣는다(폴더는 앱 것이라 지우고 이름을 바꿀 수 있다). */
  private static void makeWritable(File file) throws IOException
  {
    if (!file.isFile() || file.canWrite())
      return;
    File tmp = new File(file.getPath() + ".hbtmp");
    InputStream in = new FileInputStream(file);
    OutputStream out = new FileOutputStream(tmp);
    try {
      byte[] buf = new byte[64 * 1024];
      int n;
      while ((n = in.read(buf)) > 0)
        out.write(buf, 0, n);
    } finally {
      in.close();
      out.close();
    }
    if (!file.delete() || !tmp.renameTo(file)) {
      tmp.delete();
      throw new IOException("could not replace read-only config " + file);
    }
    Log.i(TAG, "Config file was not writable by the app; rewrote it: " + file);
  }
}
