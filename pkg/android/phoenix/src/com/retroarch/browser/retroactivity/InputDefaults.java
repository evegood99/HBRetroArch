package com.retroarch.browser.retroactivity;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.retroarch.browser.preferences.util.UserPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * HB: 깔면 누구나 먼저 하는 입력 설정 둘을 기본으로 둔다(사용자 요청 2026-11-13).
 *  - SELECT + START 로 메뉴 열기(input_menu_toggle_gamepad_combo = 4)
 *  - 패드가 연결돼 있으면 화면 버튼(오버레이) 숨기기(input_overlay_hide_when_gamepad_connected = true)
 *
 * 기본값(config.def.h)은 hb14 부터 그렇지만, 이미 깐 기기의 retroarch.cfg 에는 옛 값("0" · "false")이 적혀 있어 기본값만으로는 안 바뀐다.
 * RetroArch 가 설정을 읽기 전에(onCreate) 설정 파일마다 한 번만 고친다. 했다는 표시는 SharedPreferences 에 남기므로,
 * 그 뒤 사용자가 메뉴에서 바꾸면 그대로 둔다.
 *
 * 메뉴 열기는 사용자가 이미 정해 둔 것이 있으면 건드리지 않는다 — 패드 조합이 '없음'(0)이 아니거나,
 * 메뉴 열기 단축키에 패드 버튼 · 축 · 마우스 버튼을 묶어 둔 경우.
 * 그 줄들만 바꾸고 다른 줄(순서 · 빈 값)은 그대로 둔다.
 */
final class InputDefaults
{
  private static final String TAG = "HBInputDefaults";
  private static final String PREFS = "haebang";
  private static final String DONE = "hb_input_default_v1:";
  private static final String COMBO = "input_menu_toggle_gamepad_combo";
  private static final String COMBO_START_SELECT = "4";   // INPUT_COMBO_START_SELECT
  private static final String HIDE = "input_overlay_hide_when_gamepad_connected";
  private static final String[] MENU_BINDS = {
      "input_menu_toggle_btn", "input_menu_toggle_axis", "input_menu_toggle_mbtn" };

  private InputDefaults() {}

  static void apply(Activity activity, String configPath)
  {
    try {
      String path = (configPath != null && !configPath.isEmpty())
          ? configPath : UserPreferences.getDefaultConfigPath(activity);
      SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
      String done = DONE + path;
      if (prefs.getBoolean(done, false))
        return;

      File file = new File(path);
      // 파일이 없거나 그 줄이 없으면 기본값(SELECT + START · 숨김)을 쓴다 — 고칠 것이 없다.
      if (file.isFile()) {
        String changed = rewrite(file);
        if (!changed.isEmpty())
          Log.i(TAG, "Set once:" + changed + " (" + path + ")");
      }
      prefs.edit().putBoolean(done, true).apply();
    } catch (Exception e) {
      Log.w(TAG, "Could not set the input defaults: " + e);
    }
  }

  /** 바꾼 것(로그용, 없으면 빈 글자). */
  private static String rewrite(File file) throws IOException
  {
    List<String> lines = new ArrayList<String>();
    BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
    try {
      String line;
      while ((line = r.readLine()) != null)
        lines.add(line);
    } finally {
      r.close();
    }
    boolean endsWithNewline = file.length() > 0 && lastByte(file) == '\n';

    // 메뉴 열기를 사용자가 정해 두었나
    boolean menuSet = false;
    for (String line : lines) {
      String[] kv = split(line);
      if (kv == null)
        continue;
      if (kv[0].equals(COMBO) && !kv[1].equals("0") && !kv[1].isEmpty())
        menuSet = true;
      for (String bind : MENU_BINDS)
        if (kv[0].equals(bind) && !kv[1].isEmpty() && !kv[1].equals("nul"))
          menuSet = true;
    }

    StringBuilder changed = new StringBuilder();
    for (int i = 0; i < lines.size(); i++) {
      String[] kv = split(lines.get(i));
      if (kv == null)
        continue;
      if (kv[0].equals(COMBO) && !menuSet) {
        lines.set(i, COMBO + " = \"" + COMBO_START_SELECT + "\"");
        changed.append(" menu = SELECT + START");
      } else if (kv[0].equals(HIDE) && !kv[1].equals("true")) {
        lines.set(i, HIDE + " = \"true\"");
        changed.append(" overlay hidden with a gamepad");
      }
    }
    if (changed.length() == 0)
      return "";

    File tmp = new File(file.getPath() + ".hbtmp");
    Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
    try {
      for (int i = 0; i < lines.size(); i++) {
        w.write(lines.get(i));
        if (i < lines.size() - 1 || endsWithNewline)
          w.write('\n');
      }
    } finally {
      w.close();
    }
    if (!tmp.renameTo(file)) {
      tmp.delete();
      throw new IOException("could not replace " + file);
    }
    return changed.toString();
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

  private static int lastByte(File file) throws IOException
  {
    java.io.RandomAccessFile f = new java.io.RandomAccessFile(file, "r");
    try {
      f.seek(f.length() - 1);
      return f.read();
    } finally {
      f.close();
    }
  }
}
