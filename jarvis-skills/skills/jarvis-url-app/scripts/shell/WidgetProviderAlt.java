package app.jarvis.urlapp;

// 두 번째 홈 화면 위젯 (예: 가계부의 '카드 사용'). 위젯 스크립트가 돌려준 값의 "alt" 를 그린다.
// 위젯 스크립트에 "// urlapp:alt-label 이름" 이 있을 때만 매니페스트에 들어가고, 위젯 목록에서 그 이름으로 따로 고른다.
public class WidgetProviderAlt extends WidgetProvider {
    @Override
    boolean alt() {
        return true;
    }
}
