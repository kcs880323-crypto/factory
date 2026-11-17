package app.jarvis.urlapp;

// 인터넷이 안 될 때 못 보낸 카드 결제를 인터넷이 돌아오면 다시 보낸다 (안드로이드 JobScheduler).

import android.app.job.JobParameters;
import android.app.job.JobService;

public class CardRetryJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        if (!CardEngine.available(this)) return false;
        final CardEngine engine = CardEngine.get(this);
        engine.exec.execute(() -> {
            engine.flush();
            jobFinished(params, engine.hasPending());
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // 끝나지 못했으면 다음에 다시
    }
}
