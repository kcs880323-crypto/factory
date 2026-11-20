package app.jarvis.urlapp;

// 앱 안 업데이트의 설치 결과를 받는다 (AppUpdater 가 PackageInstaller 에 맡긴 것).
// 사용자 확인이 필요하면 안드로이드 설치 화면을 띄우고, 실패하면 이유를 알려 준다. 이 앱 안에서만 받는다(exported=false).

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

public class UpdateReceiver extends BroadcastReceiver {
    static final String ACTION = "app.jarvis.urlapp.action.UPDATE_STATUS";

    @Override
    @SuppressWarnings("deprecation") // getParcelableExtra(String): 안드로이드 13 미만
    public void onReceive(Context c, Intent i) {
        if (i == null || !ACTION.equals(i.getAction())) return;
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = Build.VERSION.SDK_INT >= 33 ? i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                c.startActivity(confirm); // "이 앱을 업데이트할까요?" — 사용자가 '업데이트'를 누른다
            } catch (Exception e) {
                Toast.makeText(c, "설치 화면을 열지 못했어요. 앱을 열고 다시 '업데이트'를 눌러 주세요.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (st == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(c, "업데이트했어요", Toast.LENGTH_SHORT).show();
            return;
        }
        if (st == PackageInstaller.STATUS_FAILURE_ABORTED) return; // 사용자가 취소
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String why = st == PackageInstaller.STATUS_FAILURE_CONFLICT || st == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                ? "서명 키가 다르거나 이 폰과 맞지 않는 파일이에요"
                : st == PackageInstaller.STATUS_FAILURE_BLOCKED ? "Play 프로텍트 등이 설치를 막았어요"
                : st == PackageInstaller.STATUS_FAILURE_STORAGE ? "저장 공간이 부족해요" : "설치하지 못했어요";
        Toast.makeText(c, "업데이트 실패: " + why + (msg == null ? "" : " (" + msg + ")"), Toast.LENGTH_LONG).show();
    }
}
