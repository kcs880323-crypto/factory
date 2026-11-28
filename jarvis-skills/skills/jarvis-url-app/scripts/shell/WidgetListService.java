package app.jarvis.urlapp;

// 넘겨 보는 목록 위젯의 줄을 런처에 주는 곳 — 안드로이드 11 이하만 쓴다 (12+ 는 WidgetProvider 가 줄을 위젯에 바로 넣는다).
// 매니페스트에서 BIND_REMOTEVIEWS 권한으로만 묶을 수 있다 (시스템·런처). 읽기만: 저장한 위젯 요약의 rows.

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONArray;
import org.json.JSONObject;

public class WidgetListService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        Uri u = intent == null ? null : intent.getData();
        String kind = u != null && u.getLastPathSegment() != null ? u.getLastPathSegment() : "alt";
        return new Factory(getApplicationContext(), kind);
    }

    static final class Factory implements RemoteViewsFactory {
        private final Context ctx;
        private final String kind;
        private JSONArray rows = new JSONArray();

        Factory(Context ctx, String kind) {
            this.ctx = ctx;
            this.kind = kind;
        }

        @Override
        public void onCreate() {
            load();
        }

        @Override
        public void onDataSetChanged() {
            load();
        }

        private void load() {
            JSONObject d = WidgetProvider.dataFor(ctx, kind);
            JSONArray r = d != null ? d.optJSONArray("rows") : null;
            rows = r != null ? r : new JSONArray();
        }

        @Override
        public void onDestroy() {
        }

        @Override
        public int getCount() {
            return rows.length();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            JSONObject r = rows.optJSONObject(position);
            return WidgetProvider.itemView(ctx, r != null ? r : new JSONObject());
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 2; // 카드 · 묶음 제목 줄
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }
    }
}
