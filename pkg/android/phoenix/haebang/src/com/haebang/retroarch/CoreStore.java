package com.haebang.retroarch;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.retroarch.browser.preferences.util.UserPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 코어 폴더 · 받기 대기열 · buildbot 목록을 한곳에서 다룬다.
 * 공급자(CoreBridgeProvider)와 받기 서비스(CoreInstallService)가 같은 객체를 쓴다.
 *
 * 받는 곳은 공식 buildbot(https) 로 고정한다 — retroarch.cfg 의 core_updater_buildbot_cores_url 은
 * 다른 앱이 고칠 수 있는 자리(옛 안드로이드의 Android/data)에 있어서, 그 값을 따르면 남의 .so 를 깔게 될 수 있다.
 */
final class CoreStore
{
   static final String TAG = "HBCoreBridge";
   static final int API_VERSION = 1;
   static final String SUFFIX = "_libretro_android.so";
   static final String BUILDBOT = "https://buildbot.libretro.com/nightly/android/latest/";

   static final String QUEUED = "queued";
   static final String DOWNLOADING = "downloading";
   static final String INSTALLING = "installing";
   static final String DONE = "done";
   static final String FAILED = "failed";
   static final String CANCELLED = "cancelled";

   private static final long INDEX_MAX_AGE_MS = 6L * 3600 * 1000;
   private static final int MAX_JOBS_KEPT = 100;
   private static final Pattern CORE_ID = Pattern.compile("[A-Za-z0-9_+\\-]{1,80}");

   private static CoreStore instance;

   static synchronized CoreStore get(Context ctx)
   {
      if (instance == null)
         instance = new CoreStore(ctx.getApplicationContext());
      return instance;
   }

   interface Listener
   {
      void onProgress(Job job);
   }

   static final class Job
   {
      int id;
      String core;
      String source;
      String state;
      int progress;
      long bytes;
      long total;
      String error = "";
      String note = "";
      String crc32 = "";
      long created;
      long finished;
      boolean force;
      volatile boolean cancel;

      JSONObject toJson() throws JSONException
      {
         JSONObject o = new JSONObject();
         o.put("id", id);
         o.put("core", core);
         o.put("source", source);
         o.put("state", state);
         o.put("progress", progress);
         o.put("bytes", bytes);
         o.put("total", total);
         o.put("error", error);
         o.put("note", note);
         o.put("crc32", crc32);
         o.put("created", created);
         o.put("finished", finished);
         return o;
      }

      static Job fromJson(JSONObject o)
      {
         Job j = new Job();
         j.id = o.optInt("id");
         j.core = o.optString("core");
         j.source = o.optString("source");
         j.state = o.optString("state");
         j.progress = o.optInt("progress");
         j.bytes = o.optLong("bytes");
         j.total = o.optLong("total");
         j.error = o.optString("error");
         j.note = o.optString("note");
         j.crc32 = o.optString("crc32");
         j.created = o.optLong("created");
         j.finished = o.optLong("finished");
         return j;
      }

      boolean active()
      {
         return QUEUED.equals(state) || DOWNLOADING.equals(state) || INSTALLING.equals(state);
      }
   }

   static final class IndexEntry
   {
      String core;
      String file;
      String date;
      String crc32;
   }

   private static final class CrcCache
   {
      long size;
      long mtime;
      String crc;
   }

   private final Context app;
   private final File jobsFile;
   private final LinkedHashMap<Integer, Job> jobs = new LinkedHashMap<>();
   private final Map<String, CrcCache> crcCache = new HashMap<>();
   private int nextId = 1;

   private CoreStore(Context app)
   {
      this.app = app;
      this.jobsFile = new File(app.getFilesDir(), "hb_core_jobs.json");
      loadJobs();
   }

   // ---------------------------------------------------------------- 경로 · 기기

   File coresDir()
   {
      // UserPreferences.updateConfigFile 이 libretro_directory 를 늘 <dataDir>/cores/ 로 덮어쓴다 — 같은 자리를 쓴다.
      File d = new File(app.getApplicationInfo().dataDir, "cores");
      if (!d.isDirectory())
         d.mkdirs();
      return d;
   }

   File infoDir()
   {
      return new File(app.getApplicationInfo().dataDir, "info");
   }

   String configPath()
   {
      return UserPreferences.getDefaultConfigPath(app);
   }

   /** 이 앱이 실제로 돌고 있는 ABI — APK 의 lib 폴더 이름(arm64 · arm · x86 · x86_64)으로 정한다. */
   String abi()
   {
      String name = new File(app.getApplicationInfo().nativeLibraryDir).getName();
      if ("arm64".equals(name)) return "arm64-v8a";
      if ("arm".equals(name)) return "armeabi-v7a";
      if ("x86".equals(name) || "x86_64".equals(name)) return name;
      String[] abis = Build.SUPPORTED_ABIS;
      return abis != null && abis.length > 0 ? abis[0] : "arm64-v8a";
   }

   String buildbotUrl()
   {
      return BUILDBOT + abi() + "/";
   }

   /** BIOS 자리 — retroarch.cfg 의 system_directory, 없거나 default 면 RetroArch 기본 규칙대로 짐작한다. */
   String systemDir()
   {
      String v = readConfigValue("system_directory");
      if (v != null && v.length() > 0 && !"default".equals(v))
         return v;
      File shared = new File(Environment.getExternalStorageDirectory(), "RetroArch/system");
      if (shared.getParentFile() != null && shared.getParentFile().isDirectory())
         return shared.getAbsolutePath();
      File ext = app.getExternalFilesDir(null);
      return ext != null ? new File(ext, "system").getAbsolutePath() : "";
   }

   private String readConfigValue(String key)
   {
      File f = new File(configPath());
      if (!f.isFile())
         return null;
      BufferedReader r = null;
      try
      {
         r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
         String line;
         while ((line = r.readLine()) != null)
         {
            int eq = line.indexOf('=');
            if (eq <= 0 || !line.substring(0, eq).trim().equals(key))
               continue;
            return unquote(line.substring(eq + 1).trim());
         }
      }
      catch (IOException ignored)
      {
      }
      finally
      {
         closeQuietly(r);
      }
      return null;
   }

   // ---------------------------------------------------------------- 코어 이름

   /** 'snes9x' · 'snes9x_libretro_android.so' · '.../snes9x_libretro_android.so.zip' 을 모두 'snes9x' 로. 못 쓰는 이름이면 null. */
   static String coreId(String s)
   {
      if (s == null)
         return null;
      s = s.trim();
      int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
      if (slash >= 0)
         s = s.substring(slash + 1);
      if (s.endsWith(".zip"))
         s = s.substring(0, s.length() - 4);
      if (s.endsWith(SUFFIX))
         s = s.substring(0, s.length() - SUFFIX.length());
      else if (s.endsWith("_libretro.so"))
         s = s.substring(0, s.length() - "_libretro.so".length());
      else if (s.endsWith("_libretro"))
         s = s.substring(0, s.length() - "_libretro".length());
      return CORE_ID.matcher(s).matches() ? s : null;
   }

   static List<String> splitCores(String arg)
   {
      List<String> out = new ArrayList<>();
      if (arg == null)
         return out;
      for (String part : arg.split("[,\\s]+"))
      {
         if (part.length() > 0)
            out.add(part);
      }
      return out;
   }

   File coreFile(String id)
   {
      return new File(coresDir(), id + SUFFIX);
   }

   // ---------------------------------------------------------------- 깔린 코어

   static final class Installed
   {
      String core;
      File file;
      String crc32;
      String abi;
      boolean abiOk;
      Map<String, String> info;
   }

   List<Installed> listInstalled(boolean withCrc)
   {
      List<Installed> out = new ArrayList<>();
      File[] files = coresDir().listFiles();
      if (files == null)
         return out;
      List<File> sorted = new ArrayList<>();
      for (File f : files)
      {
         if (f.isFile() && f.getName().endsWith(SUFFIX) && !f.getName().startsWith("."))
            sorted.add(f);
      }
      Collections.sort(sorted);
      for (File f : sorted)
      {
         Installed it = new Installed();
         it.core = f.getName().substring(0, f.getName().length() - SUFFIX.length());
         it.file = f;
         it.crc32 = withCrc ? crc32(f) : "";
         it.abi = elfAbi(f);
         it.abiOk = abi().equals(it.abi);
         it.info = readInfo(it.core);
         out.add(it);
      }
      return out;
   }

   /** CRC32(8자리 소문자 16진) — buildbot .index-extended 의 값과 같은 방식(풀어 놓은 .so 전체). 크기 · 시각이 같으면 다시 안 잰다. */
   String crc32(File f)
   {
      synchronized (crcCache)
      {
         CrcCache c = crcCache.get(f.getAbsolutePath());
         if (c != null && c.size == f.length() && c.mtime == f.lastModified())
            return c.crc;
      }
      CRC32 crc = new CRC32();
      InputStream in = null;
      try
      {
         in = new FileInputStream(f);
         byte[] buf = new byte[64 * 1024];
         int n;
         while ((n = in.read(buf)) > 0)
            crc.update(buf, 0, n);
      }
      catch (IOException e)
      {
         return "";
      }
      finally
      {
         closeQuietly(in);
      }
      String hex = String.format("%08x", crc.getValue());
      CrcCache c = new CrcCache();
      c.size = f.length();
      c.mtime = f.lastModified();
      c.crc = hex;
      synchronized (crcCache)
      {
         crcCache.put(f.getAbsolutePath(), c);
      }
      return hex;
   }

   /** <info>/<core>_libretro.info 의 key = "value" 줄. 없으면 빈 지도. */
   Map<String, String> readInfo(String core)
   {
      Map<String, String> out = new HashMap<>();
      File f = new File(infoDir(), core + "_libretro.info");
      if (!f.isFile())
         return out;
      BufferedReader r = null;
      try
      {
         r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
         String line;
         while ((line = r.readLine()) != null)
         {
            int eq = line.indexOf('=');
            if (eq <= 0 || line.trim().startsWith("#"))
               continue;
            out.put(line.substring(0, eq).trim(), unquote(line.substring(eq + 1).trim()));
         }
      }
      catch (IOException ignored)
      {
      }
      finally
      {
         closeQuietly(r);
      }
      return out;
   }

   boolean delete(String core)
   {
      File f = coreFile(core);
      return f.isFile() && f.delete();
   }

   // ---------------------------------------------------------------- buildbot 목록

   private File indexCacheFile()
   {
      return new File(app.getCacheDir(), "hb_index_" + abi() + ".txt");
   }

   /** 받아 둔 .index-extended. refresh 면 지금 받고, 아니면 받아 둔 것(없으면 빈 목록). 오래됐는지는 indexAgeMs 로 본다. */
   List<IndexEntry> index(boolean refresh) throws IOException
   {
      File cache = indexCacheFile();
      if (refresh || !cache.isFile())
      {
         if (!refresh && !cache.isFile())
            return new ArrayList<>();
         File tmp = new File(cache.getPath() + ".part");
         download(buildbotUrl() + ".index-extended", tmp, null);
         if (!tmp.renameTo(cache))
            throw new IOException("rename failed: " + cache);
      }
      return parseIndex(cache);
   }

   long indexAgeMs()
   {
      File cache = indexCacheFile();
      return cache.isFile() ? System.currentTimeMillis() - cache.lastModified() : -1;
   }

   boolean indexStale()
   {
      long age = indexAgeMs();
      return age < 0 || age > INDEX_MAX_AGE_MS;
   }

   private static List<IndexEntry> parseIndex(File f) throws IOException
   {
      List<IndexEntry> out = new ArrayList<>();
      BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
      try
      {
         String line;
         while ((line = r.readLine()) != null)
         {
            String[] p = line.trim().split("\\s+");
            if (p.length < 3)
               continue;
            String id = coreId(p[2]);
            if (id == null)
               continue;
            IndexEntry e = new IndexEntry();
            e.date = p[0];
            e.crc32 = p[1].toLowerCase();
            e.file = p[2];
            e.core = id;
            out.add(e);
         }
      }
      finally
      {
         closeQuietly(r);
      }
      Collections.sort(out, new java.util.Comparator<IndexEntry>()
      {
         @Override
         public int compare(IndexEntry a, IndexEntry b)
         {
            return a.core.compareTo(b.core);
         }
      });
      return out;
   }

   /** 목록에서 그 코어 — 목록을 아직 안 받았으면 null(있다고도 없다고도 못 함). */
   IndexEntry indexEntry(String core)
   {
      try
      {
         for (IndexEntry e : index(false))
         {
            if (e.core.equals(core))
               return e;
         }
      }
      catch (IOException ignored)
      {
      }
      return null;
   }

   // ---------------------------------------------------------------- 받기 대기열

   synchronized Job enqueue(String core, boolean force)
   {
      // 같은 코어가 이미 대기 · 받는 중이면 새로 만들지 않고 그 일을 돌려준다.
      for (Job j : jobs.values())
      {
         if (j.core.equals(core) && j.active())
            return j;
      }
      Job j = newJob(core, "buildbot");
      j.force = force;
      // 있어도 ABI 가 어긋나면(32↔64 로 다시 깐 경우) 못 쓰는 파일이므로 다시 받는다.
      if (!force && coreFile(core).isFile() && abiOk(coreFile(core)))
      {
         j.state = DONE;
         j.progress = 100;
         j.note = "already_installed";
         j.crc32 = crc32(coreFile(core));
         j.finished = System.currentTimeMillis();
      }
      saveJobs();
      return j;
   }

   synchronized Job newJob(String core, String source)
   {
      Job j = new Job();
      j.id = nextId++;
      j.core = core;
      j.source = source;
      j.state = QUEUED;
      j.created = System.currentTimeMillis();
      jobs.put(j.id, j);
      trimJobs();
      return j;
   }

   synchronized Job job(int id)
   {
      return jobs.get(id);
   }

   synchronized List<Job> jobs()
   {
      return new ArrayList<>(jobs.values());
   }

   synchronized boolean hasQueued()
   {
      for (Job j : jobs.values())
      {
         if (QUEUED.equals(j.state))
            return true;
      }
      return false;
   }

   synchronized Job takeNext()
   {
      for (Job j : jobs.values())
      {
         if (QUEUED.equals(j.state))
         {
            j.state = DOWNLOADING;
            saveJobs();
            return j;
         }
      }
      return null;
   }

   synchronized boolean cancel(int id)
   {
      Job j = jobs.get(id);
      if (j == null || !j.active())
         return false;
      j.cancel = true;
      if (QUEUED.equals(j.state))
         finish(j, CANCELLED, "");
      return true;
   }

   private synchronized void finish(Job j, String state, String error)
   {
      j.state = state;
      j.error = error == null ? "" : error;
      if (DONE.equals(state))
         j.progress = 100;
      j.finished = System.currentTimeMillis();
      saveJobs();
   }

   /** 서비스의 일꾼 스레드에서 부른다 — buildbot 에서 zip 을 받아 풀어 코어 폴더에 넣는다. */
   void run(Job j, Listener listener)
   {
      File zip = new File(app.getCacheDir(), "hb_dl_" + j.id + ".zip");
      try
      {
         download(buildbotUrl() + j.core + SUFFIX + ".zip", zip, j, listener);
         if (j.cancel)
         {
            finish(j, CANCELLED, "");
            return;
         }
         synchronized (this)
         {
            j.state = INSTALLING;
         }
         listener.onProgress(j);
         installZip(zip, j.core);
         j.crc32 = crc32(coreFile(j.core));
         finish(j, DONE, "");
      }
      catch (Exception e)
      {
         Log.w(TAG, "install " + j.core + " failed", e);
         finish(j, j.cancel ? CANCELLED : FAILED, j.cancel ? "" : String.valueOf(e.getMessage()));
      }
      finally
      {
         zip.delete();
         listener.onProgress(j);
      }
   }

   /** 기기 안 파일(.so 또는 .so.zip)을 코어 폴더로 — 셸(adb)에서 올려 둔 파일용. 성공하면 코어 이름. */
   String installFile(File src, String nameOverride) throws IOException
   {
      if (!src.isFile())
         throw new IOException("file not found: " + src);
      String id = coreId(nameOverride != null && nameOverride.length() > 0 ? nameOverride : src.getName());
      if (id == null)
         throw new IOException("bad core name: " + src.getName());
      if (src.getName().endsWith(".zip"))
      {
         installZip(src, id);
      }
      else
      {
         InputStream in = new FileInputStream(src);
         try
         {
            placeCore(in, id);
         }
         finally
         {
            closeQuietly(in);
         }
      }
      return id;
   }

   private void installZip(File zip, String id) throws IOException
   {
      ZipFile zf = new ZipFile(zip);
      try
      {
         ZipEntry pick = null;
         Enumeration<? extends ZipEntry> en = zf.entries();
         while (en.hasMoreElements())
         {
            ZipEntry e = en.nextElement();
            String name = e.getName();
            // 경로가 섞인 이름은 받지 않는다(zip slip). buildbot zip 은 .so 하나뿐이다.
            if (e.isDirectory() || name.contains("/") || name.contains("\\"))
               continue;
            if (name.endsWith(".so"))
            {
               pick = e;
               break;
            }
         }
         if (pick == null)
            throw new IOException("no .so in zip");
         InputStream in = zf.getInputStream(pick);
         try
         {
            placeCore(in, id);
         }
         finally
         {
            closeQuietly(in);
         }
      }
      finally
      {
         zf.close();
      }
   }

   /** 임시 파일에 다 쓰고 ELF · ABI 를 본 다음 이름을 바꿔 넣는다 — 실행 중인 코어가 있어도 옛 파일은 그대로 매핑된 채 남는다. */
   private void placeCore(InputStream in, String id) throws IOException
   {
      File dir = coresDir();
      File tmp = new File(dir, "." + id + SUFFIX + ".part");
      FileOutputStream out = new FileOutputStream(tmp);
      try
      {
         byte[] buf = new byte[64 * 1024];
         int n;
         while ((n = in.read(buf)) > 0)
            out.write(buf, 0, n);
         out.getFD().sync();
      }
      finally
      {
         out.close();
      }
      try
      {
         checkElf(tmp);
      }
      catch (IOException e)
      {
         tmp.delete();
         throw e;
      }
      File dest = coreFile(id);
      if (!tmp.renameTo(dest))
      {
         tmp.delete();
         throw new IOException("rename failed: " + dest);
      }
   }

   /** ELF 머리의 기계 종류 · 비트 → ABI 이름. ELF 가 아니면 null, 모르는 기계면 "unknown". */
   static String elfAbi(File f)
   {
      byte[] h = new byte[20];
      InputStream in = null;
      try
      {
         in = new FileInputStream(f);
         if (in.read(h) != h.length)
            return null;
      }
      catch (IOException e)
      {
         return null;
      }
      finally
      {
         closeQuietly(in);
      }
      if (h[0] != 0x7f || h[1] != 'E' || h[2] != 'L' || h[3] != 'F')
         return null;
      int cls = h[4];
      int machine = (h[18] & 0xff) | ((h[19] & 0xff) << 8);
      if (machine == 183 && cls == 2) return "arm64-v8a";
      if (machine == 40 && cls == 1) return "armeabi-v7a";
      if (machine == 3 && cls == 1) return "x86";
      if (machine == 62 && cls == 2) return "x86_64";
      return "unknown";
   }

   /** 이 코어를 지금 앱이 불러올 수 있는지 — 통합판은 설치 방식에 따라 32비트로도 돌므로 깔린 코어와 어긋날 수 있다. */
   boolean abiOk(File f)
   {
      return abi().equals(elfAbi(f));
   }

   /** ELF 머리 · 기계 종류가 이 앱 ABI 와 맞는지 — 32비트 코어를 64비트 앱에 넣는 흔한 실수를 막는다. */
   private void checkElf(File f) throws IOException
   {
      String got = elfAbi(f);
      if (got == null)
         throw new IOException("not an ELF file");
      if (!got.equals(abi()))
         throw new IOException("core ABI mismatch (" + got + ") — this app runs as " + abi());
   }

   private void download(String url, File dest, Job job) throws IOException
   {
      download(url, dest, job, null);
   }

   private void download(String url, File dest, Job job, Listener listener) throws IOException
   {
      HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
      c.setConnectTimeout(15000);
      c.setReadTimeout(30000);
      c.setInstanceFollowRedirects(true);
      c.setRequestProperty("User-Agent", "HBRetroArch/" + versionName());
      try
      {
         int code = c.getResponseCode();
         if (code == 404)
            throw new IOException("not on buildbot (404): " + url);
         if (code != 200)
            throw new IOException("HTTP " + code + ": " + url);
         long total = c.getContentLength();
         if (job != null)
            job.total = total;
         InputStream in = c.getInputStream();
         OutputStream out = new FileOutputStream(dest);
         try
         {
            byte[] buf = new byte[64 * 1024];
            int n;
            long got = 0;
            long lastReport = 0;
            while ((n = in.read(buf)) > 0)
            {
               if (job != null && job.cancel)
                  throw new IOException("cancelled");
               out.write(buf, 0, n);
               got += n;
               if (job != null)
               {
                  job.bytes = got;
                  job.progress = total > 0 ? (int) (got * 100 / total) : 0;
                  long now = System.currentTimeMillis();
                  if (listener != null && now - lastReport > 500)
                  {
                     lastReport = now;
                     listener.onProgress(job);
                  }
               }
            }
         }
         finally
         {
            closeQuietly(in);
            out.close();
         }
      }
      finally
      {
         c.disconnect();
      }
   }

   // ---------------------------------------------------------------- 일 기록

   private void trimJobs()
   {
      while (jobs.size() > MAX_JOBS_KEPT)
      {
         Integer oldest = null;
         for (Map.Entry<Integer, Job> e : jobs.entrySet())
         {
            if (!e.getValue().active())
            {
               oldest = e.getKey();
               break;
            }
         }
         if (oldest == null)
            break;
         jobs.remove(oldest);
      }
   }

   private synchronized void loadJobs()
   {
      if (!jobsFile.isFile())
         return;
      try
      {
         byte[] data = readAll(jobsFile);
         JSONObject root = new JSONObject(new String(data, "UTF-8"));
         nextId = Math.max(1, root.optInt("next_id", 1));
         JSONArray arr = root.optJSONArray("jobs");
         for (int i = 0; arr != null && i < arr.length(); i++)
         {
            Job j = Job.fromJson(arr.getJSONObject(i));
            // 앱이 죽어 끊긴 일은 실패로 남긴다 — 다시 받으려면 다시 부른다.
            if (j.active())
            {
               j.state = FAILED;
               j.error = "interrupted";
               j.finished = System.currentTimeMillis();
            }
            jobs.put(j.id, j);
            nextId = Math.max(nextId, j.id + 1);
         }
      }
      catch (Exception e)
      {
         Log.w(TAG, "jobs file unreadable — starting fresh", e);
      }
   }

   synchronized void saveJobs()
   {
      try
      {
         JSONObject root = new JSONObject();
         root.put("next_id", nextId);
         JSONArray arr = new JSONArray();
         for (Job j : jobs.values())
            arr.put(j.toJson());
         root.put("jobs", arr);
         File tmp = new File(jobsFile.getPath() + ".part");
         FileOutputStream out = new FileOutputStream(tmp);
         try
         {
            out.write(root.toString().getBytes("UTF-8"));
         }
         finally
         {
            out.close();
         }
         tmp.renameTo(jobsFile);
      }
      catch (Exception e)
      {
         Log.w(TAG, "jobs file not saved", e);
      }
   }

   // ---------------------------------------------------------------- 잡다

   String versionName()
   {
      try
      {
         return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
      }
      catch (Exception e)
      {
         return "";
      }
   }

   long versionCode()
   {
      try
      {
         return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionCode;
      }
      catch (Exception e)
      {
         return 0;
      }
   }

   private static String unquote(String v)
   {
      if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\""))
         return v.substring(1, v.length() - 1);
      return v;
   }

   private static byte[] readAll(File f) throws IOException
   {
      InputStream in = new FileInputStream(f);
      try
      {
         byte[] data = new byte[(int) f.length()];
         int off = 0;
         while (off < data.length)
         {
            int n = in.read(data, off, data.length - off);
            if (n <= 0)
               break;
            off += n;
         }
         return data;
      }
      finally
      {
         closeQuietly(in);
      }
   }

   static void closeQuietly(java.io.Closeable c)
   {
      if (c == null)
         return;
      try
      {
         c.close();
      }
      catch (IOException ignored)
      {
      }
   }
}
