package app.jarvis.urlapp;

// 두 번째 위젯의 다른 크기 (위젯 스크립트의 "// urlapp:alt-sizes 3x3,4x2" 의 3번째 크기, 예: 4×2). 그리는 값은 두 번째 위젯과 같다.
public class WidgetProviderAlt3 extends WidgetProvider {
    @Override
    String kind() {
        return "alt3";
    }
}
