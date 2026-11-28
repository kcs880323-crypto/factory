package app.jarvis.urlapp;

// 폰이 보낸 것을 워치가 받는다 (Google Play 서비스가 깨운다).
//   데이터 /urlapp/widget {json, at}            → 보관하고 타일을 새로 그리게 한다
//   메시지 /urlapp/status·/urlapp/answer {id, …} → 대화 화면(WatchAskActivity)에 넘긴다 (없으면 보관)
//   메시지 /urlapp/alarm {act: ring|stop, …}     → 폰 알람과 같이 진동·알림 (WatchAlarm)

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import androidx.wear.tiles.TileService;

import java.nio.charset.StandardCharsets;

public class WatchListener extends WearableListenerService {
    @Override
    public void onDataChanged(DataEventBuffer events) {
        boolean changed = false;
        try {
            changed = save(events);
        } catch (Throwable t) { // 받은 것이 이상해도 꺼지지 않게
            changed = false;
        }
        if (changed) TileService.getUpdater(this).requestUpdate(WatchTile.class);
    }

    private boolean save(DataEventBuffer events) {
        boolean changed = false;
        try {
            for (DataEvent e : events) {
                if (e.getType() != DataEvent.TYPE_CHANGED || !"/urlapp/widget".equals(e.getDataItem().getUri().getPath())) {
                    continue;
                }
                DataMap m = DataMapItem.fromDataItem(e.getDataItem()).getDataMap();
                long at = m.getLong("at", System.currentTimeMillis());
                if (at >= WatchStore.at(this)) {
                    WatchStore.save(this, m.getString("json"), at);
                    changed = true;
                }
            }
        } finally {
            events.release();
        }
        return changed;
    }

    @Override
    public void onMessageReceived(MessageEvent e) {
        String path = e.getPath();
        if ("/urlapp/status".equals(path) || "/urlapp/answer".equals(path)) {
            WatchAskActivity.fromPhone(getApplicationContext(), path, new String(e.getData(), StandardCharsets.UTF_8));
        } else if ("/urlapp/alarm".equals(path)) { // 폰 알람이 울림·멈춤 → 워치도 같이 진동 (WatchAlarm)
            WatchAlarm.fromPhone(getApplicationContext(), new String(e.getData(), StandardCharsets.UTF_8));
        }
    }
}
