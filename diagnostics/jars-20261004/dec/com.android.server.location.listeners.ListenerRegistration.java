package com.android.server.location.listeners;

import com.android.internal.listeners.ListenerExecutor;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/* JADX INFO: loaded from: classes2.dex */
public class ListenerRegistration<TListener> implements ListenerExecutor {
    private boolean mActive = false;
    private final Executor mExecutor;
    private volatile TListener mListener;

    protected ListenerRegistration(Executor executor, TListener tlistener) {
        this.mExecutor = (Executor) Objects.requireNonNull(executor);
        this.mListener = (TListener) Objects.requireNonNull(tlistener);
    }

    protected String getTag() {
        return "ListenerRegistration";
    }

    protected final Executor getExecutor() {
        return this.mExecutor;
    }

    protected void onRegister(Object key) {
    }

    protected void onUnregister() {
    }

    protected void onActive() {
    }

    protected void onInactive() {
    }

    public final boolean isActive() {
        return this.mActive;
    }

    final boolean setActive(boolean active) {
        if (active != this.mActive) {
            this.mActive = active;
            return true;
        }
        return false;
    }

    public final boolean isRegistered() {
        return this.mListener != null;
    }

    final void unregisterInternal() {
        this.mListener = null;
        onListenerUnregister();
    }

    protected void onListenerUnregister() {
    }

    protected void onOperationFailure(ListenerExecutor.ListenerOperation<TListener> operation, Exception exception) {
        throw new AssertionError(exception);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ Object lambda$executeOperation$0() {
        return this.mListener;
    }

    protected final void executeOperation(ListenerExecutor.ListenerOperation<TListener> operation) {
        executeSafely(this.mExecutor, new Supplier() { // from class: com.android.server.location.listeners.ListenerRegistration$$ExternalSyntheticLambda0
            @Override // java.util.function.Supplier
            public final Object get() {
                return this.f$0.lambda$executeOperation$0();
            }
        }, operation, new ListenerExecutor.FailureCallback() { // from class: com.android.server.location.listeners.ListenerRegistration$$ExternalSyntheticLambda1
            public final void onFailure(ListenerExecutor.ListenerOperation listenerOperation, Exception exc) {
                this.f$0.onOperationFailure(listenerOperation, exc);
            }
        });
    }

    public String toString() {
        return "[]";
    }

    public final boolean equals(Object obj) {
        return this == obj;
    }

    public final int hashCode() {
        return super.hashCode();
    }
}
