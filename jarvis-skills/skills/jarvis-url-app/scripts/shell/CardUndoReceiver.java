package app.jarvis.urlapp;

// "가계부에 등록했어요" 알림의 [되돌리기] 단추. 앱 밖으로 열려 있지 않다 (exported=false, 이 앱의 알림만 부른다).

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class CardUndoReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !CardEngine.ACTION_UNDO.equals(intent.getAction())) return;
        final String id = intent.getStringExtra("id");
        if (id == null || !CardEngine.available(context)) return;
        final PendingResult result = goAsync();
        final CardEngine engine = CardEngine.get(context);
        engine.exec.execute(() -> {
            try {
                engine.undo(id);
            } finally {
                result.finish();
            }
        });
    }
}
