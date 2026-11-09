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
import java.util.regex.Pattern;

/**
 * HB: 상태저장 그림(savestate_thumbnail_enable)을 안드로이드에서도 켠다 — 해방런처 세이브 창이 그 그림을 보인다.
 *
 * 기본값(config.def.h)은 hb8 부터 켜짐이지만, 이미 깐 기기의 retroarch.cfg 에는 "false" 가 적혀 있어 기본값만으로는 안 바뀐다.
 * RetroArch 가 설정을 읽기 전에(onCreate, BundleAssetsCheck 다음) 설정 파일마다 한 번만 그 줄을 "true" 로 고친다.
 * 했다는 표시는 SharedPreferences 에 남기므로, 그 뒤 사용자가 메뉴에서 끄면 그대로 둔다.
 * 그 한 줄만 바꾸고 다른 줄(순서 · 빈 값)은 그대로 둔다 — ConfigFile.write 는 전체를 다시 쓰며 빈 값을 버린다.
 */
final class SavestateThumbDefault
{
  private static final String TAG = "HBThumb";
  private static final String PREFS = "haebang";
  private static final String DONE = "hb_thumb_default_v1:";
  private static final String KEY = "savestate_thumbnail_enable";
  private static final Pattern LINE = Pattern.compile("^\\s*" + KEY + "\\s*=.*$");

  private SavestateThumbDefault() {}

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
      // 파일이 없거나 그 줄이 없으면 기본값(켜짐)을 쓴다 — 고칠 것이 없다.
      if (file.isFile() && turnOn(file))
        Log.i(TAG, "Turned on save state thumbnails once: " + path);
      prefs.edit().putBoolean(done, true).apply();
    } catch (Exception e) {
      Log.w(TAG, "Could not turn on save state thumbnails: " + e);
    }
  }

  /** 그 줄이 "true" 가 아니면 바꿔 쓴다. 바꿨으면 true. */
  private static boolean turnOn(File file) throws IOException
  {
    List<String> lines = new ArrayList<>();
    boolean endsWithNewline;
    BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
    try {
      String line;
      while ((line = r.readLine()) != null)
        lines.add(line);
    } finally {
      r.close();
    }
    endsWithNewline = file.length() > 0 && lastByte(file) == '\n';

    boolean changed = false;
    String on = KEY + " = \"true\"";
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (!LINE.matcher(line).matches())
        continue;
      if (!line.trim().equals(on)) {
        lines.set(i, on);
        changed = true;
      }
    }
    if (!changed)
      return false;

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
    return true;
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
