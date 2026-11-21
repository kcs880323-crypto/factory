package app.jarvis.urlapp;

// 첫 위젯과 같은 내용을 다른 크기로 (--widget-sizes 의 세 번째 크기, 예: 4x2,3x2,2x2 의 2x2).
// 위젯 목록에 크기별로 따로 보인다. 그 크기를 줄 때만 매니페스트에 들어간다.
public class WidgetProviderS3 extends WidgetProvider {
    @Override
    String kind() {
        return "s3";
    }
}
