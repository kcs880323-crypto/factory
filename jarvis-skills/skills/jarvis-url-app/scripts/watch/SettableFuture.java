package app.jarvis.urlapp;

// 타일 요청에 돌려줄 결과 (ListenableFuture 의 가장 작은 구현 — 라이브러리를 더 넣지 않으려고).

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class SettableFuture<T> implements ListenableFuture<T> {
    private final CountDownLatch done = new CountDownLatch(1);
    private final List<Runnable> listeners = new ArrayList<>();
    private T value;
    private Throwable error;

    static <T> SettableFuture<T> of(T v) {
        SettableFuture<T> f = new SettableFuture<>();
        f.set(v);
        return f;
    }

    void set(T v) {
        finish(v, null);
    }

    void fail(Throwable t) {
        finish(null, t);
    }

    private void finish(T v, Throwable t) {
        List<Runnable> run;
        synchronized (this) {
            if (done.getCount() == 0) return;
            value = v;
            error = t;
            done.countDown();
            run = new ArrayList<>(listeners);
            listeners.clear();
        }
        for (Runnable r : run) r.run();
    }

    @Override
    public void addListener(Runnable listener, Executor executor) {
        Runnable r = () -> executor.execute(listener);
        synchronized (this) {
            if (done.getCount() > 0) {
                listeners.add(r);
                return;
            }
        }
        r.run();
    }

    @Override
    public boolean cancel(boolean mayInterrupt) {
        return false;
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public boolean isDone() {
        return done.getCount() == 0;
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        done.await();
        if (error != null) throw new ExecutionException(error);
        return value;
    }

    @Override
    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        if (!done.await(timeout, unit)) throw new TimeoutException();
        if (error != null) throw new ExecutionException(error);
        return value;
    }
}
