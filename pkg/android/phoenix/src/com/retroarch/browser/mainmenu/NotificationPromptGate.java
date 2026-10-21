package com.retroarch.browser.mainmenu;

import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * HB: 시스템이 띄운 '알림 허용' 창의 답을 기다린 뒤 RetroActivityFuture 로 넘어가게 한다.
 *
 * 안드로이드 13+ 는 targetSdk 32 이하 앱이 알림 채널을 가지고 있으면(HB 는 코어 받기 진행 알림),
 * 아이콘으로 켤 때 이 화면의 작업(task) 위에 그 창을 대신 띄운다. 이 화면은 곧바로 끝나며
 * RetroActivityFuture(따로 된 작업)로 넘어가므로 창이 0.2초 만에 덮이고, 답을 못 한 채 켤 때마다 다시 뜬다.
 *
 * 켜질 때 이 작업 맨 위에 다른 앱의 화면(그 창)이 있으면 넘어가기를 미루고, 그 창이 닫혀
 * 이 화면이 다시 앞에 오면(허용 · 허용 안 함 · 뒤로) 넘어간다. 창이 없으면 지금처럼 바로 넘어간다.
 */
final class NotificationPromptGate
{
	private static final String TAG = "HBNotifGate";

	private final MainMenuActivity activity;
	private boolean waiting;
	private boolean released;

	NotificationPromptGate(MainMenuActivity activity)
	{
		this.activity = activity;
	}

	/** finalStartup 맨 앞에서 부른다. true 면 지금은 넘어가지 않는다(창이 닫히면 다시 부른다). */
	boolean hold()
	{
		if (released)
			return false;
		if (waiting)
			return true;
		if (!mayPrompt() || !foreignOnTop())
		{
			released = true;
			return false;
		}
		waiting = true;
		Log.i(TAG, "Notification permission prompt is showing; waiting for the answer.");
		return true;
	}

	void onResume()
	{
		check();
	}

	void onWindowFocusChanged(boolean hasFocus)
	{
		if (hasFocus)
			check();
	}

	private void check()
	{
		if (!waiting || released || foreignOnTop())
			return;
		waiting  = false;
		released = true;
		Log.i(TAG, "Notification permission prompt closed; continuing.");
		activity.finalStartup();
	}

	/** 시스템이 알림 허용 창을 대신 띄울 수 있는 경우만 — 아니면 작업 목록을 묻지도 않는다. */
	private boolean mayPrompt()
	{
		if (Build.VERSION.SDK_INT < 33)
			return false;
		if (activity.getApplicationInfo().targetSdkVersion >= 33)
			return false;
		NotificationManager nm = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
		return nm != null && !nm.areNotificationsEnabled();
	}

	/** 이 화면의 작업 맨 위가 다른 앱의 화면인가(그 창은 이 작업 안에 뜬다). */
	private boolean foreignOnTop()
	{
		if (Build.VERSION.SDK_INT < 29)
			return false;
		try
		{
			ActivityManager am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
			if (am == null)
				return false;
			int taskId = activity.getTaskId();
			for (ActivityManager.AppTask task : am.getAppTasks())
			{
				ActivityManager.RecentTaskInfo info = task.getTaskInfo();
				if (info == null || info.taskId != taskId)
					continue;
				ComponentName top = info.topActivity;
				return top != null && !activity.getPackageName().equals(top.getPackageName());
			}
		}
		catch (RuntimeException e)
		{
			Log.w(TAG, "Could not read the task: " + e);
		}
		return false;
	}
}
