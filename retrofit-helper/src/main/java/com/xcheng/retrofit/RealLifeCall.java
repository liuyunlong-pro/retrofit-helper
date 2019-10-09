package com.xcheng.retrofit;

import android.arch.lifecycle.Lifecycle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

final class RealLifeCall<T> implements LifeCall<T> {
    private final Call<T> delegate;
    private final Executor callbackExecutor;
    private final Lifecycle.Event event;
    private final LifecycleProvider provider;
    /**
     * LifeCall是否被释放了
     * like rxAndroid MainThreadDisposable or rxJava ObservableUnsubscribeOn, IoScheduler
     */
    private final AtomicBoolean once = new AtomicBoolean();
    /**
     * 保存最后一次生命周期事件
     */
    private volatile Lifecycle.Event lastEvent;

    RealLifeCall(Call<T> delegate, Executor callbackExecutor,
                 Lifecycle.Event event, LifecycleProvider provider) {
        this.delegate = delegate;
        this.callbackExecutor = callbackExecutor;
        this.event = event;
        this.provider = provider;
        provider.observe(this);
    }

    @Override
    public void enqueue(final Callback<T> callback) {
        Utils.checkNotNull(callback, "callback==null");
        if (isDisposed()) {
            //如果释放了请求直接回调onCompleted方法，保持和execute方法一致
            callbackExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    callback.onCompleted(delegate, new DisposedException(lastEvent));
                    provider.removeObserver(RealLifeCall.this);
                }
            });
            return;
        }
        delegate.enqueue(new Callback<T>() {
            @Override
            public void onStart(Call<T> call) {
                if (!isDisposed()) {
                    callback.onStart(call);
                }
            }

            @NonNull
            @Override
            public HttpError parseThrowable(Call<T> call, Throwable t) {
                if (!isDisposed()) {
                    return callback.parseThrowable(call, t);
                }
                return new HttpError("Already disposed.", t);
            }

            @NonNull
            @Override
            public T transform(Call<T> call, T t) {
                if (!isDisposed()) {
                    return callback.transform(call, t);
                }
                return t;
            }

            @Override
            public void onSuccess(Call<T> call, T t) {
                if (!isDisposed()) {
                    callback.onSuccess(call, t);
                }
            }

            @Override
            public void onError(Call<T> call, HttpError error) {
                if (!isDisposed()) {
                    callback.onError(call, error);
                }
            }

            @Override
            public void onCompleted(Call<T> call, @Nullable Throwable t) {
                //like okhttp RealCall#timeoutExit
                callback.onCompleted(call, isDisposed() ? new DisposedException(lastEvent, t) : t);
                provider.removeObserver(RealLifeCall.this);
            }
        });
    }

    @NonNull
    @Override
    public T execute() throws Throwable {
        try {
            if (isDisposed()) {
                throw new DisposedException(lastEvent);
            }
            T body = delegate.execute();
            if (isDisposed()) {
                throw new DisposedException(lastEvent);
            }
            return body;
        } catch (Throwable t) {
            if (isDisposed() && !(t instanceof DisposedException)) {
                throw new DisposedException(lastEvent, t);
            }
            throw t;
        } finally {
            provider.removeObserver(this);
        }
    }

    @Override
    public void onChanged(@NonNull Lifecycle.Event event) {
        if (event != Lifecycle.Event.ON_ANY) {
            lastEvent = event;
        }
        if (this.event == event
                || event == Lifecycle.Event.ON_DESTROY
                //Activity和Fragment的生命周期是不会传入 {@code Lifecycle.Event.ON_ANY},
                //可以手动调用此方法传入 {@code Lifecycle.Event.ON_ANY},用于区分是否为手动调用
                || event == Lifecycle.Event.ON_ANY) {
            if (once.compareAndSet(false, true)/*保证原子性*/) {
                delegate.cancel();
                Log.d(RetrofitFactory.TAG, "disposed by-->" + event + ", " + delegate.request());
            }
        }
    }

    @Override
    public boolean isDisposed() {
        return once.get();
    }
}