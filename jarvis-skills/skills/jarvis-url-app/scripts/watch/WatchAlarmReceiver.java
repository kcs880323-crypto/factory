package app.jarvis.urlapp;

// 워치 알람 알림의 끄기 / 5분 뒤 단추 (내보내지 않음 — 이 앱의 알림만 부른다).

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class WatchAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (WatchAlarm.ACTION_DISMISS.equals(a)) WatchAlarm.act(ctx, false);
        else if (WatchAlarm.ACTION_SNOOZE.equals(a)) WatchAlarm.act(ctx, true);
    }
}
