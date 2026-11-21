package app.jarvis.urlapp;

// 작업 예약(JobScheduler)을 앱이 꺼지지 않게 부른다.
// 안드로이드 14+(targetSdk 34+)는 인터넷 조건(setRequiredNetworkType)을 건 작업에 ACCESS_NETWORK_STATE 권한이 없으면
// SecurityException 을 던진다 (매니페스트에 늘 넣는다). 그 밖의 거절(작업 수 제한 등)도 앱을 끄지 않고 false 로 알린다.

import android.app.job.JobInfo;
import android.app.job.JobScheduler;

final class Jobs {
    private Jobs() {}

    static boolean safe(JobScheduler js, JobInfo job) {
        if (js == null) return false;
        try {
            return js.schedule(job) == JobScheduler.RESULT_SUCCESS;
        } catch (RuntimeException e) {
            android.util.Log.w("urlapp", "작업 예약 실패: " + e);
            return false;
        }
    }
}
