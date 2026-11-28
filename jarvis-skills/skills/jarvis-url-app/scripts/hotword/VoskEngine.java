package app.jarvis.urlapp;

// '하이 자비스' 호출어 엔진 — Vosk(Apache-2.0, alphacephei) 한국어 small 모델을 폰 안에서만 쓴다.
// --hotword 앱에만 classes2.dex 로 들어간다 (Vosk·JNA 와 함께). 소리는 어디에도 보내거나 저장하지 않는다.

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

public final class VoskEngine implements HotwordEngine {
    private Model model;
    private Recognizer rec;
    private float rate = 16000f;

    public VoskEngine() {
    }

    @Override
    public void open(String modelDir, String grammar, float sampleRate) throws Exception {
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        rate = sampleRate;
        model = new Model(modelDir);
        rec = make(grammar);
    }

    private Recognizer make(String grammar) throws Exception {
        Recognizer r = new Recognizer(model, rate, grammar);
        r.setWords(true);
        return r;
    }

    @Override
    public void grammar(String grammar) throws Exception {
        Recognizer next = make(grammar);
        Recognizer old = rec;
        rec = next;
        if (old != null) old.close();
    }

    @Override
    public boolean accept(short[] pcm, int n) {
        return rec != null && rec.acceptWaveForm(pcm, n);
    }

    @Override
    public String result() {
        return rec == null ? "" : rec.getResult();
    }

    @Override
    public String partial() {
        return rec == null ? "" : rec.getPartialResult();
    }

    @Override
    public String finish() {
        if (rec == null) return "";
        String r = rec.getFinalResult();
        rec.reset();
        return r;
    }

    @Override
    public void reset() {
        if (rec != null) rec.reset();
    }

    @Override
    public void close() {
        if (rec != null) rec.close();
        rec = null;
        if (model != null) model.close();
        model = null;
    }
}
