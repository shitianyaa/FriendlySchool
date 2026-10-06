package com.yiran.friendlyschool.targets;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * 酷安内嵌开屏的实例级投递状态。
 *
 * <p>使用弱引用 + 对象身份（==）作为 key，避免 Fragment 销毁后被模块长期持有。
 * PENDING 在主线程 runnable 入队前同步占位，因此同一实例生命周期重复进入时不会
 * 重复排队；SENT 为终态；只有真正的投递异常会进入 DISPATCH_FAILED，允许后续
 * 生命周期重新尝试。
 */
final class CoolApkSplashDispatch {

    enum State {
        NONE,
        PENDING,
        SENT,
        DISPATCH_FAILED
    }

    private static final class InstanceKey extends WeakReference<Object> {
        private final int hash;

        InstanceKey(Object referent, ReferenceQueue<Object> queue) {
            super(referent, queue);
            hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof InstanceKey)) {
                return false;
            }
            Object self = get();
            Object theirs = ((InstanceKey) other).get();
            return self != null && self == theirs;
        }
    }

    private final ReferenceQueue<Object> queue = new ReferenceQueue<Object>();
    private final Map<InstanceKey, State> states = new HashMap<InstanceKey, State>();

    synchronized boolean tryMarkPending(Object instance) {
        if (instance == null) {
            return false;
        }
        expunge();
        InstanceKey key = new InstanceKey(instance, queue);
        State current = states.get(key);
        if (current == State.PENDING || current == State.SENT) {
            return false;
        }
        states.put(key, State.PENDING);
        return true;
    }

    synchronized void markSent(Object instance) {
        if (instance == null) {
            return;
        }
        expunge();
        states.put(new InstanceKey(instance, queue), State.SENT);
    }

    synchronized void markDispatchFailed(Object instance) {
        if (instance == null) {
            return;
        }
        expunge();
        InstanceKey key = new InstanceKey(instance, queue);
        if (states.get(key) != State.SENT) {
            states.put(key, State.DISPATCH_FAILED);
        }
    }

    synchronized void clearPending(Object instance) {
        if (instance == null) {
            return;
        }
        expunge();
        InstanceKey key = new InstanceKey(instance, queue);
        if (states.get(key) == State.PENDING) {
            states.remove(key);
        }
    }

    synchronized State stateOf(Object instance) {
        if (instance == null) {
            return State.NONE;
        }
        expunge();
        State state = states.get(new InstanceKey(instance, queue));
        return state == null ? State.NONE : state;
    }

    synchronized int trackedCount() {
        expunge();
        return states.size();
    }

    private void expunge() {
        for (Reference<? extends Object> ref = queue.poll(); ref != null; ref = queue.poll()) {
            states.remove(ref);
        }
    }
}
