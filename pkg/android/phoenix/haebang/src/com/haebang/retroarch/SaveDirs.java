package com.haebang.retroarch;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 그 게임의 롬저장 · 상태저장 폴더 — 코어 브릿지 'saves' 조회.
 *
 * RetroArch 가 정하는 규칙을 그대로 옮겼다(그대로여야 런처가 그림 · 파일을 제자리에서 찾는다).
 * - 폴더 값: configuration.c 의 savefile_directory · savestate_directory 읽기 — '~' · ':' 를 펴고, 폴더가 아니면 버린다.
 *   'default' · 빈 값 · 버린 값은 기본 폴더인데, 프런트엔드가 DATADIR 없이 게임 화면을 바로 켜면 기본 폴더가 비어
 *   **롬 폴더**가 된다(기기에서 확인 — 앱 아이콘으로 켜면 <내부>/RetroArch/saves).
 * - 이름 · 하위 폴더: runloop.c 의 runloop_path_set_basename · runloop_path_set_redirect.
 * - library_name 은 코어가 실행 때 알려 주는 이름이다. 코어를 올리지 않으므로 info 의 corename 을 쓰고,
 *   그 폴더가 없을 때 이름을 정규화(소문자 영숫자)해 같은 폴더가 있으면 그것을 쓴다.
 * 코어 · 게임별 덮어쓰기 설정(config/<코어>/…cfg)은 보지 않는다.
 */
final class SaveDirs
{
   private SaveDirs() {}

   static JSONObject resolve(CoreStore st, String configPath, String core, String rom) throws JSONException
   {
      Map<String, String> cfg = readConfig(new File(configPath));
      JSONObject o = new JSONObject();
      o.put("ok", true);
      o.put("config_path", configPath);

      String saveRaw = cfg.get("savefile_directory");
      String stateRaw = cfg.get("savestate_directory");
      String saveRoot = root(saveRaw);
      String stateRoot = root(stateRaw);
      boolean sortSaves = bool(cfg, "sort_savefiles_enable", true);
      boolean sortStates = bool(cfg, "sort_savestates_enable", true);
      boolean sortSavesByContent = bool(cfg, "sort_savefiles_by_content_enable", false);
      boolean sortStatesByContent = bool(cfg, "sort_savestates_by_content_enable", false);
      boolean savesInContent = bool(cfg, "savefiles_in_content_dir", false);
      boolean statesInContent = bool(cfg, "savestates_in_content_dir", false);

      // 빈 값 = 롬 폴더(위 설명)
      o.put("save_root", saveRoot);
      o.put("state_root", stateRoot);
      o.put("save_root_config", saveRaw == null ? "" : saveRaw);
      o.put("state_root_config", stateRaw == null ? "" : stateRaw);
      o.put("sort_savefiles", sortSaves);
      o.put("sort_savestates", sortStates);
      o.put("sort_savefiles_by_content", sortSavesByContent);
      o.put("sort_savestates_by_content", sortStatesByContent);
      o.put("savefiles_in_content_dir", savesInContent);
      o.put("savestates_in_content_dir", statesInContent);
      o.put("thumbnail", bool(cfg, "savestate_thumbnail_enable", true));
      o.put("auto_save", bool(cfg, "savestate_auto_save", false));
      o.put("auto_load", bool(cfg, "savestate_auto_load", false));
      o.put("state_slot", integer(cfg, "state_slot", 0));

      if (core == null || core.isEmpty() || rom == null || rom.isEmpty())
         return o;

      String basename = runtimeBasename(rom);
      String romDir = basedir(basename);
      String contentDirName = parentDirName(basename);
      String saveInter = (saveRoot.isEmpty() || savesInContent) ? romDir : saveRoot;
      String stateInter = (stateRoot.isEmpty() || statesInContent) ? romDir : stateRoot;
      String saveParent = sortSavesByContent ? join(saveInter, contentDirName) : saveInter;
      String stateParent = sortStatesByContent ? join(stateInter, contentDirName) : stateInter;

      // library_name — info 의 corename. 그 폴더가 없으면 정규화한 이름이 같은 폴더를 찾는다:
      // corename → 끝 괄호를 뗀 corename('MAME 2010 (0.139)' 의 실제 이름은 'MAME 2010') → 코어 id
      String corename = st.readInfo(core).get("corename");
      String library = corename;
      String from = corename != null && !corename.isEmpty() ? "info" : "none";
      boolean any = (sortSaves || sortStates);
      if (any && !(library != null && !library.isEmpty()
            && ((sortSaves && new File(saveParent, library).isDirectory())
               || (sortStates && new File(stateParent, library).isDirectory()))))
      {
         for (String want : new String[] {
               corename == null ? "" : norm(corename),
               corename == null ? "" : norm(stripParen(corename)),
               norm(core) })
         {
            String found = sortSaves ? sameName(saveParent, want) : null;
            if (found == null && sortStates)
               found = sameName(stateParent, want);
            if (found != null)
            {
               library = found;
               from = "folder";
               break;
            }
         }
      }
      if (any && (library == null || library.isEmpty()))
      {
         o.put("ok", false);
         o.put("error", "no library_name for core " + core + " (no info file)");
         return o;
      }

      // 내용별(롬 폴더 이름) 다음에 코어별(library_name) — runloop_path_set_redirect 순서
      String saveDir = sortSaves ? join(saveParent, library) : saveParent;
      String stateDir = sortStates ? join(stateParent, library) : stateParent;

      o.put("save_dir", trimSlash(saveDir));
      o.put("state_dir", trimSlash(stateDir));
      o.put("base", name(basename));
      o.put("library_name", library == null ? "" : library);
      o.put("library_name_from", from);
      o.put("save_dir_exists", new File(saveDir).isDirectory());
      o.put("state_dir_exists", new File(stateDir).isDirectory());
      return o;
   }

   // ---------------------------------------------------------------- 폴더 값

   /** configuration.c: 'default' · 빈 값 · 폴더가 아닌 값이면 기본(빈 값 = 롬 폴더), 아니면 편 경로. */
   private static String root(String raw)
   {
      if (raw == null || raw.isEmpty() || "default".equals(raw))
         return "";
      String p = expandSpecial(raw);
      return new File(p).isDirectory() ? p : "";
   }

   /** fill_pathname_expand_special: '~' 는 HOME, ':' 는 실행 파일 폴더 — 둘 다 그 뒤 두 글자째부터 붙인다. */
   static String expandSpecial(String in)
   {
      String dir = null;
      if (in.startsWith("~"))
         dir = System.getenv("HOME");
      else if (in.startsWith(":"))
      {
         try
         {
            File exe = new File("/proc/self/exe").getCanonicalFile();
            dir = exe.getParent();
         }
         catch (IOException e)
         {
            dir = null;
         }
      }
      if (dir == null || dir.isEmpty())
         return in;
      String rest = in.length() > 2 ? in.substring(2) : "";
      return (dir.endsWith("/") ? dir : dir + "/") + rest;
   }

   // ---------------------------------------------------------------- 이름

   /**
    * runloop_path_set_basename: 압축 파일 안('….zip#안/게임.sfc')이면 압축 파일 폴더 + 안쪽 파일 이름,
    * 그리고 경로 전체의 마지막 '.' 뒤를 뗀다(맨 앞 '.' 은 두고).
    */
   static String runtimeBasename(String rom)
   {
      String s = rom;
      int delim = archiveDelim(s);
      if (delim >= 0)
      {
         String archive = s.substring(0, delim);
         String inner = s.substring(delim + 1);
         int slash = archive.lastIndexOf('/');
         String dir = slash >= 0 ? archive.substring(0, slash + 1) : "./";
         int innerSlash = inner.lastIndexOf('/');
         s = dir + (innerSlash >= 0 ? inner.substring(innerSlash + 1) : inner);
      }
      int dot = s.lastIndexOf('.');
      if (dot > 0)
         s = s.substring(0, dot);
      return s;
   }

   /** path_get_archive_delim: 마지막 '/' 뒤에서 '.zip#' · '.apk#' · '.7z#'(대소문자 무시)의 '#' 자리. */
   private static int archiveDelim(String path)
   {
      int from = path.lastIndexOf('/') + 1;
      String low = path.toLowerCase(Locale.ROOT);
      for (String ext : new String[] { ".zip#", ".apk#", ".7z#" })
      {
         int i = low.indexOf(ext, from);
         if (i >= 0)
            return i + ext.length() - 1;
      }
      return -1;
   }

   /** fill_pathname_basedir: 마지막 '/' 까지(슬래시 포함). 없으면 './'. */
   private static String basedir(String path)
   {
      int slash = path.lastIndexOf('/');
      return slash >= 0 ? path.substring(0, slash + 1) : "./";
   }

   /** fill_pathname_parent_dir_name: 바로 위 폴더 이름. */
   private static String parentDirName(String path)
   {
      String dir = trimSlash(basedir(path));
      int slash = dir.lastIndexOf('/');
      return slash >= 0 ? dir.substring(slash + 1) : dir;
   }

   private static String name(String path)
   {
      int slash = path.lastIndexOf('/');
      return slash >= 0 ? path.substring(slash + 1) : path;
   }

   /** fill_pathname_join: 사이에 '/' 하나. */
   private static String join(String dir, String name)
   {
      if (dir.isEmpty())
         return name;
      return (dir.endsWith("/") ? dir : dir + "/") + name;
   }

   private static String trimSlash(String dir)
   {
      while (dir.length() > 1 && dir.endsWith("/"))
         dir = dir.substring(0, dir.length() - 1);
      return dir;
   }

   /** 소문자 영숫자만 — 'Genesis Plus GX' 와 'genesis_plus_gx' 를 같게 본다. */
   static String norm(String s)
   {
      StringBuilder b = new StringBuilder();
      for (char c : s.toLowerCase(Locale.ROOT).toCharArray())
      {
         if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))
            b.append(c);
      }
      return b.toString();
   }

   /** 끝의 '(…)' 을 뗀다 — info 의 corename 에만 붙은 버전 표시('MAME 2015 (0.160)' · 'MAME (Git)'). */
   static String stripParen(String s)
   {
      return s.replaceAll("\\s*\\([^()]*\\)\\s*$", "");
   }

   private static String sameName(String parent, String want)
   {
      if (want.isEmpty())
         return null;
      File[] subs = new File(parent).listFiles();
      if (subs == null)
         return null;
      for (File f : subs)
      {
         if (f.isDirectory() && norm(f.getName()).equals(want))
            return f.getName();
      }
      return null;
   }

   // ---------------------------------------------------------------- 설정 파일

   /** key = "value" 줄 전부. 따옴표 없는 값은 그대로(RetroArch 와 같다). */
   static Map<String, String> readConfig(File f)
   {
      Map<String, String> out = new HashMap<>();
      if (!f.isFile())
         return out;
      BufferedReader r = null;
      try
      {
         r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
         String line;
         while ((line = r.readLine()) != null)
         {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#"))
               continue;
            int eq = t.indexOf('=');
            if (eq <= 0)
               continue;
            String v = t.substring(eq + 1).trim();
            if (v.startsWith("\""))
            {
               int end = v.indexOf('"', 1);
               v = end > 0 ? v.substring(1, end) : v.substring(1);
            }
            out.put(t.substring(0, eq).trim(), v);
         }
      }
      catch (IOException ignored)
      {
      }
      finally
      {
         CoreStore.closeQuietly(r);
      }
      return out;
   }

   /** config_get_bool: 'true' · '1' 이 참, 'false' · '0' 이 거짓, 그 밖은 기본값. */
   private static boolean bool(Map<String, String> cfg, String key, boolean def)
   {
      String v = cfg.get(key);
      if ("true".equals(v) || "1".equals(v))
         return true;
      if ("false".equals(v) || "0".equals(v))
         return false;
      return def;
   }

   private static int integer(Map<String, String> cfg, String key, int def)
   {
      try
      {
         return Integer.parseInt(cfg.get(key));
      }
      catch (RuntimeException e)
      {
         return def;
      }
   }
}
