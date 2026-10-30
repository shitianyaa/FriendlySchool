package com.yiran.friendlyschool.targets;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** 人民日报签到状态：后台确认写盘，再投递接续结果，不依赖 Android 运行时。 */
final class PeopleDailyPersistence {

    private PeopleDailyPersistence() {
    }

    /**
     * committed 只在 commit 返回 true 后成立；写盘或排队失败均携带异常/失败结果。
     * 无法投递结果时就地通知，调用方的失败分支只能做线程安全的日志与名额清理。
     */
    static void commit(Executor writer, Executor callback, BooleanSupplier write,
                       BiConsumer<Boolean, Throwable> completion) {
        AtomicBoolean delivered = new AtomicBoolean();
        try {
            writer.execute(() -> {
                if (delivered.get()) {
                    return;
                }
                boolean committed = false;
                Throwable failure = null;
                try {
                    committed = write.getAsBoolean();
                } catch (Throwable t) {
                    failure = t;
                }
                final boolean saved = committed;
                final Throwable error = failure;
                try {
                    callback.execute(() -> completeOnce(delivered, completion, saved, error));
                } catch (RuntimeException | Error t) {
                    if (delivered.get()) {
                        throw t; // 已执行的接续自身报错，不能伪装成排队失败或重复回调。
                    }
                    completeOnce(delivered, completion, saved, t);
                }
            });
        } catch (RuntimeException | Error t) {
            if (delivered.get()) {
                throw t;
            }
            completeOnce(delivered, completion, false, t);
        }
    }

    private static void completeOnce(AtomicBoolean delivered,
                                     BiConsumer<Boolean, Throwable> completion,
                                     boolean committed, Throwable failure) {
        if (delivered.compareAndSet(false, true)) {
            completion.accept(committed, failure);
        }
    }
}
