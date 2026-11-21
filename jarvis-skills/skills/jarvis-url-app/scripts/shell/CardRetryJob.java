package app.jarvis.urlapp;

// 모아 둔 카드 결제를 45초 뒤 처리하고(HOLD_JOB), 인터넷이 안 될 때 못 보낸 결제를 다시 보낸다(RETRY_JOB).
// 사이트가 상태 주소를 주면 6시간마다 폰 상태를 알린다(STATUS_JOB).

import android.app.job.JobParameters;
import android.app.job.JobService;

public class CardRetryJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        if (!CardEngine.available(this)) return false;
        final CardEngine engine = CardEngine.get(this);
        final boolean hold = params.getJobId() == CardEngine.HOLD_JOB;
        if (params.getJobId() == CardEngine.STATUS_JOB) { // 6시간마다 사이트에 폰 상태 알리기
            engine.exec.execute(() -> {
                engine.reportStatus(true);
                jobFinished(params, false);
            });
            return true;
        }
        engine.exec.execute(() -> {
            if (hold) engine.processHolding(); // 모아 둔 결제 처리 (같은 결제 합치기 → 등록)
            engine.flush();
            jobFinished(params, !hold && engine.hasPending());
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // 끝나지 못했으면 다음에 다시
    }
}
