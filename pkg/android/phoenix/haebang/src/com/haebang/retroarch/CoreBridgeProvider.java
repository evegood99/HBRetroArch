package com.haebang.retroarch;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 바깥(해방툴의 adb · HB런처 같은 기기 안 앱)이 코어를 보고 받게 하는 코어 브릿지.
 *
 * 읽기 — content://<패키지>.cores/{info|cores|jobs|available}
 *   ?core=snes9x  한 코어만 · ?format=json  한 줄 'json' 칸에 JSON 으로 · ?refresh=1  (available) 목록을 지금 받음
 * 부르기 — call(method, arg, extras)
 *   ping · info · install(arg=코어들, force) · update(arg=코어들 또는 빈 값=깔린 것 전부) · job(arg=번호) · cancel(arg=번호)
 *   refresh_index · install_file(arg=기기 안 경로, name) · delete(arg=코어)
 *
 * install · update 는 공식 buildbot 에서만 받으므로 누구나 부를 수 있다.
 * install_file · delete 는 셸(adb) · root · 같은 서명 권한(<패키지>.permission.MANAGE_CORES)만 — 아무 .so 나 넣게 되기 때문이다.
 */
public class CoreBridgeProvider extends ContentProvider
{
   private static final String[] CORE_COLUMNS = {
         "core", "file", "path", "size", "mtime", "crc32", "abi", "abi_ok",
         "display_name", "system_name", "extensions", "core_version", "firmware_count" };
   private static final String[] JOB_COLUMNS = {
         "id", "core", "source", "state", "progress", "bytes", "total", "error", "note", "crc32", "created", "finished" };
   private static final String[] AVAILABLE_COLUMNS = {
         "core", "file", "date", "crc32", "installed", "local_crc32", "up_to_date" };

   @Override
   public boolean onCreate()
   {
      return true;
   }

   private CoreStore store()
   {
      return CoreStore.get(getContext());
   }

   // ---------------------------------------------------------------- 읽기

   @Override
   public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder)
   {
      List<String> seg = uri.getPathSegments();
      String table = seg.isEmpty() ? "info" : seg.get(0);
      String only = pickCore(uri, selection, selectionArgs);
      boolean json = "json".equals(uri.getQueryParameter("format"));
      try
      {
         JSONArray rows;
         String[] cols;
         if ("info".equals(table))
         {
            JSONObject o = info();
            cols = names(o);
            rows = new JSONArray().put(o);
         }
         else if ("cores".equals(table))
         {
            cols = CORE_COLUMNS;
            rows = cores(only);
         }
         else if ("jobs".equals(table))
         {
            cols = JOB_COLUMNS;
            rows = jobs(only);
         }
         else if ("available".equals(table))
         {
            cols = AVAILABLE_COLUMNS;
            rows = available(only, "1".equals(uri.getQueryParameter("refresh")));
         }
         else
         {
            throw new IllegalArgumentException("unknown table: " + table + " (info · cores · jobs · available)");
         }
         return json ? jsonCursor(rows) : cursor(cols, rows);
      }
      catch (JSONException e)
      {
         throw new IllegalStateException(e);
      }
   }

   /** ?core=x 또는 selection 'core=x' · 'core = "x"' · 'core=?'+args 를 받는다(content query --where 용). */
   private static String pickCore(Uri uri, String selection, String[] args)
   {
      String q = uri.getQueryParameter("core");
      if (q != null)
         return CoreStore.coreId(q);
      if (selection == null)
         return null;
      String s = selection.trim();
      if (!s.startsWith("core"))
         return null;
      s = s.substring(4).trim();
      if (!s.startsWith("="))
         return null;
      s = s.substring(1).trim();
      if ("?".equals(s) && args != null && args.length > 0)
         s = args[0];
      s = s.replace("\"", "").replace("'", "");
      return CoreStore.coreId(s);
   }

   private JSONObject info() throws JSONException
   {
      CoreStore st = store();
      Context ctx = getContext();
      JSONObject o = new JSONObject();
      o.put("api_version", CoreStore.API_VERSION);
      o.put("package", ctx.getPackageName());
      o.put("version_name", st.versionName());
      o.put("version_code", st.versionCode());
      o.put("abi", st.abi());
      o.put("android_sdk", Build.VERSION.SDK_INT);
      o.put("cores_dir", st.coresDir().getAbsolutePath());
      o.put("info_dir", st.infoDir().getAbsolutePath());
      o.put("system_dir", st.systemDir());
      o.put("config_path", st.configPath());
      o.put("buildbot_url", st.buildbotUrl());
      o.put("core_count", st.listInstalled(false).size());
      o.put("index_age_ms", st.indexAgeMs());
      return o;
   }

   private JSONArray cores(String only) throws JSONException
   {
      JSONArray rows = new JSONArray();
      for (CoreStore.Installed it : store().listInstalled(true))
      {
         if (only != null && !only.equals(it.core))
            continue;
         Map<String, String> info = it.info;
         JSONObject o = new JSONObject();
         o.put("core", it.core);
         o.put("file", it.file.getName());
         o.put("path", it.file.getAbsolutePath());
         o.put("size", it.file.length());
         o.put("mtime", it.file.lastModified());
         o.put("crc32", it.crc32);
         o.put("abi", str(it.abi));
         o.put("abi_ok", it.abiOk ? 1 : 0);
         o.put("display_name", str(info.get("display_name")));
         o.put("system_name", str(info.get("systemname")));
         o.put("extensions", str(info.get("supported_extensions")));
         o.put("core_version", str(info.get("display_version")));
         o.put("firmware_count", str(info.get("firmware_count")));
         rows.put(o);
      }
      return rows;
   }

   private JSONArray jobs(String only) throws JSONException
   {
      JSONArray rows = new JSONArray();
      for (CoreStore.Job j : store().jobs())
      {
         if (only != null && !only.equals(j.core))
            continue;
         rows.put(j.toJson());
      }
      return rows;
   }

   private JSONArray available(String only, boolean refresh) throws JSONException
   {
      CoreStore st = store();
      List<CoreStore.IndexEntry> index;
      try
      {
         index = st.index(refresh);
      }
      catch (Exception e)
      {
         throw new IllegalStateException("buildbot index: " + e.getMessage());
      }
      JSONArray rows = new JSONArray();
      for (CoreStore.IndexEntry e : index)
      {
         if (only != null && !only.equals(e.core))
            continue;
         File f = st.installedFile(e.core);
         boolean installed = f != null;
         String local = installed ? st.crc32(f) : "";
         JSONObject o = new JSONObject();
         o.put("core", e.core);
         o.put("file", e.file);
         o.put("date", e.date);
         o.put("crc32", e.crc32);
         o.put("installed", installed ? 1 : 0);
         o.put("local_crc32", local);
         o.put("up_to_date", installed ? (local.equals(e.crc32) ? 1 : 0) : 0);
         rows.put(o);
      }
      return rows;
   }

   // ---------------------------------------------------------------- 부르기

   @Override
   public Bundle call(String method, String arg, Bundle extras)
   {
      JSONObject r = new JSONObject();
      try
      {
         r.put("method", method);
         try
         {
            handle(method, arg, extras == null ? new Bundle() : extras, r);
            if (!r.has("ok"))
               r.put("ok", true);
         }
         catch (SecurityException e)
         {
            r.put("ok", false);
            r.put("error", "permission: " + e.getMessage());
         }
         catch (Exception e)
         {
            r.put("ok", false);
            r.put("error", String.valueOf(e.getMessage()));
         }
      }
      catch (JSONException e)
      {
         throw new IllegalStateException(e);
      }
      Bundle b = new Bundle();
      b.putBoolean("ok", r.optBoolean("ok"));
      if (r.has("error"))
         b.putString("error", r.optString("error"));
      if (r.has("jobs"))
         b.putString("job_ids", joinIds(r.optJSONArray("jobs")));
      // adb 쪽은 이 한 칸만 읽으면 된다 — 'json=' 뒤를 JSON 으로 푼다.
      b.putString("json", r.toString());
      return b;
   }

   private void handle(String method, String arg, Bundle extras, JSONObject r) throws Exception
   {
      CoreStore st = store();
      if ("ping".equals(method))
      {
         r.put("api_version", CoreStore.API_VERSION);
         r.put("version_name", st.versionName());
         return;
      }
      if ("info".equals(method))
      {
         r.put("info", info());
         return;
      }
      if ("install".equals(method) || "update".equals(method))
      {
         boolean update = "update".equals(method);
         List<String> names = CoreStore.splitCores(arg);
         if (update && names.isEmpty())
         {
            for (CoreStore.Installed it : st.listInstalled(false))
               names.add(it.core);
         }
         if (names.isEmpty())
            throw new IllegalArgumentException("no core given (arg=snes9x,genesis_plus_gx)");
         boolean force = extras.getBoolean("force", false);
         if (update)
         {
            // 지금 목록을 받아 CRC 가 다른 것만 다시 받는다.
            st.index(true);
         }
         else if (st.indexStale())
         {
            // 이름 확인용 — 못 받아도(오프라인) 받기 자체는 해 본다.
            try
            {
               st.index(true);
            }
            catch (Exception ignored)
            {
            }
         }
         JSONArray out = new JSONArray();
         JSONArray rejected = new JSONArray();
         boolean started = false;
         for (String name : names)
         {
            String id = CoreStore.coreId(name);
            if (id == null)
            {
               rejected.put(new JSONObject().put("name", name).put("error", "bad core name"));
               continue;
            }
            CoreStore.IndexEntry known = st.indexEntry(id);
            if (known == null && st.indexAgeMs() >= 0)
            {
               rejected.put(new JSONObject().put("name", name).put("error", "not on buildbot for " + st.abi()));
               continue;
            }
            boolean again = force;
            if (update)
            {
               File f = st.installedFile(id);
               again = f == null || known == null || !st.crc32(f).equals(known.crc32);
            }
            CoreStore.Job j = st.enqueue(id, again);
            if (CoreStore.QUEUED.equals(j.state))
               started = true;
            out.put(j.toJson());
         }
         if (started)
            CoreInstallService.start(getContext());
         r.put("jobs", out);
         if (rejected.length() > 0)
         {
            r.put("rejected", rejected);
            if (out.length() == 0)
               r.put("ok", false);
         }
         return;
      }
      if ("job".equals(method))
      {
         CoreStore.Job j = st.job(parseId(arg));
         if (j == null)
            throw new IllegalArgumentException("no such job: " + arg);
         r.put("job", j.toJson());
         return;
      }
      if ("cancel".equals(method))
      {
         r.put("ok", st.cancel(parseId(arg)));
         return;
      }
      if ("refresh_index".equals(method))
      {
         r.put("count", st.index(true).size());
         return;
      }
      if ("install_file".equals(method))
      {
         enforceManage();
         if (arg == null || arg.length() == 0)
            throw new IllegalArgumentException("arg = path on device (.so or .so.zip)");
         CoreStore.Job j = st.newJob(CoreStore.coreId(extras.getString("name", new File(arg).getName())) + "", "file");
         try
         {
            String id = st.installFile(new File(arg), extras.getString("name"));
            j.core = id;
            j.crc32 = st.crc32(st.coreFile(id));
            j.state = CoreStore.DONE;
            j.progress = 100;
            r.put("core", id);
            r.put("path", st.coreFile(id).getAbsolutePath());
            r.put("crc32", j.crc32);
         }
         catch (Exception e)
         {
            j.state = CoreStore.FAILED;
            j.error = String.valueOf(e.getMessage());
            throw e;
         }
         finally
         {
            j.finished = System.currentTimeMillis();
            st.saveJobs();
         }
         return;
      }
      if ("delete".equals(method))
      {
         enforceManage();
         String id = CoreStore.coreId(arg);
         if (id == null)
            throw new IllegalArgumentException("bad core name: " + arg);
         r.put("core", id);
         r.put("ok", st.delete(id));
         return;
      }
      throw new IllegalArgumentException("unknown method: " + method
            + " (ping · info · install · update · job · cancel · refresh_index · install_file · delete)");
   }

   /** 셸(adb, uid 2000) · root · 이 앱 자신 · MANAGE_CORES 서명 권한을 가진 앱만. */
   private void enforceManage()
   {
      int uid = Binder.getCallingUid();
      if (uid == 0 || uid == 2000 || uid == Process.myUid())
         return;
      String perm = getContext().getPackageName() + ".permission.MANAGE_CORES";
      if (getContext().checkCallingPermission(perm) == PackageManager.PERMISSION_GRANTED)
         return;
      throw new SecurityException("uid " + uid + " needs " + perm);
   }

   private static int parseId(String arg)
   {
      try
      {
         return Integer.parseInt(arg == null ? "" : arg.trim());
      }
      catch (NumberFormatException e)
      {
         throw new IllegalArgumentException("job id must be a number: " + arg);
      }
   }

   // ---------------------------------------------------------------- 커서

   private static MatrixCursor cursor(String[] cols, JSONArray rows) throws JSONException
   {
      MatrixCursor c = new MatrixCursor(cols, rows.length());
      for (int i = 0; i < rows.length(); i++)
      {
         JSONObject o = rows.getJSONObject(i);
         Object[] v = new Object[cols.length];
         for (int k = 0; k < cols.length; k++)
            v[k] = o.opt(cols[k]);
         c.addRow(v);
      }
      return c;
   }

   private static MatrixCursor jsonCursor(JSONArray rows)
   {
      MatrixCursor c = new MatrixCursor(new String[] { "json" }, 1);
      c.addRow(new Object[] { rows.toString() });
      return c;
   }

   private static String[] names(JSONObject o)
   {
      List<String> out = new ArrayList<>();
      java.util.Iterator<String> it = o.keys();
      while (it.hasNext())
         out.add(it.next());
      return out.toArray(new String[0]);
   }

   private static String joinIds(JSONArray jobs)
   {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; jobs != null && i < jobs.length(); i++)
      {
         JSONObject j = jobs.optJSONObject(i);
         if (j == null)
            continue;
         if (sb.length() > 0)
            sb.append(',');
         sb.append(j.optInt("id"));
      }
      return sb.toString();
   }

   private static String str(String s)
   {
      return s == null ? "" : s;
   }

   // ---------------------------------------------------------------- 안 쓰는 것

   @Override
   public String getType(Uri uri)
   {
      List<String> seg = uri.getPathSegments();
      return "vnd.android.cursor.dir/vnd." + getContext().getPackageName() + "." + (seg.isEmpty() ? "info" : seg.get(0));
   }

   @Override
   public Uri insert(Uri uri, ContentValues values)
   {
      throw new UnsupportedOperationException("use call(install)");
   }

   @Override
   public int delete(Uri uri, String selection, String[] selectionArgs)
   {
      throw new UnsupportedOperationException("use call(delete)");
   }

   @Override
   public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs)
   {
      throw new UnsupportedOperationException("read-only");
   }
}
