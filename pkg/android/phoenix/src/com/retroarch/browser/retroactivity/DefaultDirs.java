package com.retroarch.browser.retroactivity;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

import com.retroarch.browser.preferences.util.UserPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * HB: 게임 화면을 바로 켤 때도 기본 폴더(자료 · 코어 정보 · 세이브 · 재생목록 …)를 안다.
 *
 * RetroArch 는 기본 폴더를 인텐트 추가값 DATADIR · SDCARD · EXTERNAL · APK 로 정한다(platform_unix.c).
 * 이 값은 아이콘으로 켤 때의 첫 화면(MainMenuActivity)만 넣는다. 해방런처 · ES-DE 처럼 RetroActivityFuture 를
 * 바로 켜면 기본 폴더가 전부 빈 값이고, 새로 설치한 뒤 처음을 그렇게 켜면 설정 파일이
 * assets_directory = "default"(빈 값) 로 만들어진다. 그 뒤로는 아이콘으로 켜도 자료 폴더를 몰라
 * 한글이 '??' 인 비트맵 글꼴 · 빈 아이콘으로 뜬다(레트로이드 포켓 노바, 2026-11-13).
 *
 * RetroArch 가 인텐트 · 설정을 읽기 전에(onCreate 맨 앞):
 * 1. 빠진 추가값을 첫 화면이 넣는 값으로 채운다(준 값은 그대로).
 * 2. 이미 그렇게 만들어진 설정 파일이면(assets_directory 가 "default" 나 빈 값) 빈 폴더 칸을 지워
 *    RetroArch 가 기본 폴더로 다시 채우게 한다. 세이브 · 상태저장은 쓰던 자리를 지킨다 —
 *    그 설정으로 쓴 세이브는 롬 옆에 있으니 savefiles_in_content_dir · savestates_in_content_dir 를 켠다
 *    (폴더 칸은 그대로 빈 값. 기본 폴더가 채워지면 빈 값이 RetroArch/saves 가 되어 그 세이브를 못 찾는다).
 */
final class DefaultDirs
{
  private static final String TAG = "HBDefaults";

  private DefaultDirs() {}

  static void apply(Activity activity)
  {
    try {
      Intent intent = activity.getIntent();
      if (intent == null)
        return;
      ApplicationInfo ai = activity.getApplicationInfo();
      String storage = Environment.getExternalStorageDirectory().getAbsolutePath();
      String external = storage + "/Android/data/" + activity.getPackageName() + "/files";
      StringBuilder filled = new StringBuilder();

      fill(intent, "CONFIGFILE", UserPreferences.getDefaultConfigPath(activity), filled);
      fill(intent, "DATADIR", ai.dataDir, filled);
      fill(intent, "APK", ai.sourceDir, filled);
      fill(intent, "SDCARD", storage, filled);
      fill(intent, "EXTERNAL", external, filled);
      fill(intent, "IME", Settings.Secure.getString(activity.getContentResolver(),
          Settings.Secure.DEFAULT_INPUT_METHOD), filled);
      if (filled.length() > 0)
        Log.i(TAG, "filled intent extras:" + filled);

      heal(new File(intent.getStringExtra("CONFIGFILE")));
    } catch (Exception e) {
      Log.w(TAG, "Could not check default folders: " + e);
    }
  }

  private static void fill(Intent intent, String key, String value, StringBuilder filled)
  {
    String given = intent.getStringExtra(key);
    if ((given != null && !given.isEmpty()) || value == null || value.isEmpty())
      return;
    intent.putExtra(key, value);
    filled.append(' ').append(key);
  }

  /** 폴더 칸(…_directory · …_dir · …_path)의 이름인가. 세이브 · 상태저장 폴더는 뺀다. */
  private static boolean dirKey(String key)
  {
    if (key.equals("savefile_directory") || key.equals("savestate_directory"))
      return false;
    return key.endsWith("_directory") || key.endsWith("_dir") || key.endsWith("_path");
  }

  private static void heal(File file) throws Exception
  {
    if (!file.isFile() || !file.canWrite())
      return;
    List<String> lines = new ArrayList<String>();
    BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
    try {
      String line;
      while ((line = in.readLine()) != null)
        lines.add(line);
    } finally {
      in.close();
    }

    boolean broken = false;
    for (String line : lines) {
      String[] kv = split(line);
      if (kv != null && kv[0].equals("assets_directory") && empty(kv[1]))
        broken = true;
    }
    if (!broken)
      return;

    // 세이브 · 상태저장 폴더가 빈 값이면 지금까지 롬 옆에 썼다(기본 폴더가 비어 있었으니). 이제 기본 폴더가 채워져
    // 빈 값이 RetroArch/saves · states 가 되므로, '콘텐츠 폴더에 저장' 을 켜서 쓰던 자리를 지킨다.
    boolean savesInContent = false, statesInContent = false;
    for (String line : lines) {
      String[] kv = split(line);
      if (kv == null)
        continue;
      if (kv[0].equals("savefile_directory") && empty(kv[1]))
        savesInContent = true;
      if (kv[0].equals("savestate_directory") && empty(kv[1]))
        statesInContent = true;
    }

    List<String> kept = new ArrayList<String>();
    StringBuilder dropped = new StringBuilder();
    boolean keptConfigDir = false;
    for (String line : lines) {
      String[] kv = split(line);
      if (kv != null && dirKey(kv[0]) && empty(kv[1])) {
        // 코어 옵션 · 오버라이드 폴더가 빈 값이면 지금까지 설정 파일 옆(<설정 폴더>/<코어>/)에 썼다.
        // 거기 쓴 것이 있으면 그 자리를 그대로 적는다 — 지우면 RetroArch/config 로 바뀌어 코어 옵션이 처음으로 돌아간다.
        if (kv[0].equals("rgui_config_directory") && hasSubdir(file.getParentFile())) {
          kept.add("rgui_config_directory = \"" + file.getParent() + "\"");
          keptConfigDir = true;
          continue;
        }
        dropped.append(' ').append(kv[0]);
        continue;
      }
      if (kv != null && ((savesInContent && kv[0].equals("savefiles_in_content_dir"))
          || (statesInContent && kv[0].equals("savestates_in_content_dir"))))
        continue;
      kept.add(line);
    }
    if (savesInContent)
      kept.add("savefiles_in_content_dir = \"true\"");
    if (statesInContent)
      kept.add("savestates_in_content_dir = \"true\"");

    File tmp = new File(file.getPath() + ".hbtmp");
    Writer out = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
    try {
      for (String line : kept)
        out.write(line + "\n");
    } finally {
      out.close();
    }
    if (!file.delete() || !tmp.renameTo(file)) {
      tmp.delete();
      throw new java.io.IOException("could not replace config " + file);
    }
    Log.i(TAG, "config had no default folders (assets_directory empty); saves in content dir "
        + savesInContent + ", states " + statesInContent + ", config dir kept " + keptConfigDir
        + "; cleared:" + dropped);
  }

  private static boolean hasSubdir(File dir)
  {
    File[] list = dir == null ? null : dir.listFiles();
    if (list != null)
      for (File f : list)
        if (f.isDirectory())
          return true;
    return false;
  }

  /** `key = "value"` → {key, value}. 아니면 null. */
  private static String[] split(String line)
  {
    int eq = line.indexOf('=');
    if (eq <= 0)
      return null;
    String key = line.substring(0, eq).trim();
    String value = line.substring(eq + 1).trim();
    if (key.isEmpty() || key.startsWith("#"))
      return null;
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
      value = value.substring(1, value.length() - 1);
    return new String[] { key, value };
  }

  private static boolean empty(String value)
  {
    return value.isEmpty() || value.equals("default");
  }
}
