package app.jarvis.urlapp;

// 호출어 엔진의 문 (껍데기 쪽). 실제 엔진(VoskEngine, scripts/hotword/)은 --hotword 앱에만 따로(classes2.dex) 들어가
// 다른 앱의 껍데기는 Vosk·JNA 없이 그대로 컴파일된다. HotwordService 가 이름으로 찾아 만든다.

interface HotwordEngine {
    /** 모델 폴더를 열고 문법(JSON 배열)으로 듣는 인식기를 만든다. 몇 초 걸린다 (작업 스레드에서). */
    void open(String modelDir, String grammar, float sampleRate) throws Exception;

    /** 문법만 바꾼다 (민감도 바꿀 때). */
    void grammar(String grammar) throws Exception;

    /** 소리 조각(16비트). true = 말이 한 번 끝나 result() 를 읽을 때. */
    boolean accept(short[] pcm, int n);

    String result();

    String partial();

    /** 지금까지 들은 것을 끝내고 결과 (그다음 새로 듣는다). */
    String finish();

    void reset();

    void close();
}
