package app.jarvis.urlapp;

// 워치 타일 (화면을 옆으로 넘기면 나오는 카드): 폰 위젯과 같은 요약 — 제목, 줄 3개까지, '말하기' 단추, 언제 받았는지.
// 내용은 폰이 Data Layer 로 보내 준다 (/urlapp/widget). 타일을 열 때 요약이 10분보다 오래됐으면 폰에 새로 달라고 한다.
// '말하기'를 누르면 워치의 대화 화면(WatchAskActivity)이 열려 바로 듣는다.
// Tiles 1.1 (Java 판) — 최신 Kotlin 판은 라이브러리가 너무 많아 이 빌드(그래들 없음)에 맞지 않아서.

import android.net.Uri;

import androidx.wear.tiles.ActionBuilders;
import androidx.wear.tiles.ColorBuilders;
import androidx.wear.tiles.DimensionBuilders;
import androidx.wear.tiles.EventBuilders;
import androidx.wear.tiles.LayoutElementBuilders;
import androidx.wear.tiles.ModifiersBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.ResourceBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;
import androidx.wear.tiles.TimelineBuilders;

import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;
import com.google.common.util.concurrent.ListenableFuture;

public class WatchTile extends TileService {
    private static final String RES_VERSION = "1";
    private static final long FRESH_MS = 15 * 60_000L;
    private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFF9AA0A6, NEG = 0xFFFF6B81;

    @Override
    protected ListenableFuture<TileBuilders.Tile> onTileRequest(RequestBuilders.TileRequest request) {
        SettableFuture<TileBuilders.Tile> f = new SettableFuture<>();
        // Data Layer 에 남아 있는 가장 새 요약을 한 번 읽고 (워치 앱을 막 깔았을 때도), 못 읽으면 보관한 것으로.
        // 연결 부품이 실패해도 타일은 그린다 (1.18 은 부품이 빠져 여기서 꺼져 타일이 검게만 보였다)
        try {
            Wearable.getDataClient(this).getDataItems(Uri.parse("wear://*/urlapp/widget"))
                    .addOnCompleteListener(task -> {
                        try {
                            if (task.isSuccessful()) take(task.getResult());
                        } catch (Throwable ignored) {
                            // 보관한 것으로
                        }
                        f.set(tile());
                    });
        } catch (Throwable t) {
            f.set(tile());
        }
        return f;
    }

    private void take(DataItemBuffer items) {
        try {
            long best = WatchStore.at(this);
            String json = null;
            for (DataItem item : items) {
                DataMap m = DataMapItem.fromDataItem(item).getDataMap();
                long at = m.getLong("at", 0);
                if (at > best) {
                    best = at;
                    json = m.getString("json");
                }
            }
            if (json != null) WatchStore.save(this, json, best);
        } finally {
            items.release();
        }
    }

    @Override
    protected ListenableFuture<ResourceBuilders.Resources> onResourcesRequest(RequestBuilders.ResourcesRequest request) {
        return SettableFuture.of(new ResourceBuilders.Resources.Builder().setVersion(RES_VERSION).build());
    }

    @Override
    protected void onTileEnterEvent(EventBuilders.TileEnterEvent event) {
        if (System.currentTimeMillis() - WatchStore.at(this) > 10 * 60_000L) WatchPhone.send(this, "/urlapp/refresh", "{}");
    }

    private TileBuilders.Tile tile() {
        WatchStore.Summary s = WatchStore.load(this, getString(getApplicationInfo().labelRes));
        int accent = accent();
        LayoutElementBuilders.Column.Builder col = new LayoutElementBuilders.Column.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(text(s.title, 15, WHITE, true, 1))
                .addContent(space(4));
        for (WatchStore.Row r : s.rows) {
            int vc = "neg".equals(r.tone) ? NEG : "pos".equals(r.tone) ? accent : WHITE;
            LayoutElementBuilders.Spannable.Builder line = new LayoutElementBuilders.Spannable.Builder()
                    .setMaxLines(1)
                    .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END);
            if (!r.label.isEmpty()) line.addSpan(span(r.label + (r.value.isEmpty() ? "" : "  "), 13, GRAY, false));
            if (!r.value.isEmpty()) line.addSpan(span(r.value, 13, vc, true));
            col.addContent(line.build());
        }
        col.addContent(space(8)).addContent(askChip(accent));
        String note = CrashLog.has(this) ? "⚠ 앱을 열면 지난 오류가 보여요" : s.note; // CrashLog: 꺼진 이유가 남아 있음
        if (!note.isEmpty()) col.addContent(space(4)).addContent(text(note, 11, CrashLog.has(this) ? NEG : GRAY, false, 1));

        LayoutElementBuilders.Box root = new LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setPadding(new ModifiersBuilders.Padding.Builder()
                                .setStart(DimensionBuilders.dp(22)).setEnd(DimensionBuilders.dp(22)).build())
                        .build())
                .addContent(col.build())
                .build();
        return new TileBuilders.Tile.Builder()
                .setResourcesVersion(RES_VERSION)
                .setFreshnessIntervalMillis(FRESH_MS)
                .setTimeline(new TimelineBuilders.Timeline.Builder()
                        .addTimelineEntry(new TimelineBuilders.TimelineEntry.Builder()
                                .setLayout(new LayoutElementBuilders.Layout.Builder().setRoot(root).build())
                                .build())
                        .build())
                .build();
    }

    /** '말하기' 단추: 워치의 대화 화면을 연다. */
    private LayoutElementBuilders.LayoutElement askChip(int accent) {
        ActionBuilders.LaunchAction launch = new ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(new ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(getPackageName())
                        .setClassName(WatchAskActivity.class.getName())
                        .build())
                .build();
        return new LayoutElementBuilders.Box.Builder()
                .setModifiers(new ModifiersBuilders.Modifiers.Builder()
                        .setClickable(new ModifiersBuilders.Clickable.Builder().setId("ask").setOnClick(launch).build())
                        .setBackground(new ModifiersBuilders.Background.Builder()
                                .setColor(ColorBuilders.argb(accent))
                                .setCorner(new ModifiersBuilders.Corner.Builder().setRadius(DimensionBuilders.dp(20)).build())
                                .build())
                        .setPadding(new ModifiersBuilders.Padding.Builder()
                                .setStart(DimensionBuilders.dp(18)).setEnd(DimensionBuilders.dp(18))
                                .setTop(DimensionBuilders.dp(8)).setBottom(DimensionBuilders.dp(8)).build())
                        .build())
                .addContent(text("🎙 말하기", 14, 0xFF0B1220, true, 1))
                .build();
    }

    private static LayoutElementBuilders.Text text(String t, float sp, int color, boolean bold, int lines) {
        return new LayoutElementBuilders.Text.Builder()
                .setText(t)
                .setMaxLines(lines)
                .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
                .setFontStyle(font(sp, color, bold))
                .build();
    }

    private static LayoutElementBuilders.SpanText span(String t, float sp, int color, boolean bold) {
        return new LayoutElementBuilders.SpanText.Builder().setText(t).setFontStyle(font(sp, color, bold)).build();
    }

    private static LayoutElementBuilders.FontStyle font(float sp, int color, boolean bold) {
        return new LayoutElementBuilders.FontStyle.Builder()
                .setSize(DimensionBuilders.sp(sp))
                .setColor(ColorBuilders.argb(color))
                .setWeight(bold ? LayoutElementBuilders.FONT_WEIGHT_BOLD : LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                .build();
    }

    private static LayoutElementBuilders.Spacer space(float dp) {
        return new LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(dp)).build();
    }

    private int accent() {
        int id = getResources().getIdentifier("watch_accent", "color", getPackageName());
        return id == 0 ? 0xFF2DD4DA : getResources().getColor(id, null);
    }
}
