package com.android.server.location.provider;

import android.location.LocationResult;
import android.location.provider.ProviderProperties;
import android.location.provider.ProviderRequest;
import android.location.util.identity.CallerIdentity;
import android.os.Binder;
import android.os.Bundle;
import com.android.internal.util.Preconditions;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/* JADX INFO: loaded from: classes2.dex */
public abstract class AbstractLocationProvider {
    private IAbstractLocationProviderWrapper mAbstractLocationProviderWrapper = new AbstractLocationProviderWrapper();
    private final LocationProviderController mController;
    protected final Executor mExecutor;
    private final AtomicReference<InternalState> mInternalState;

    public interface Listener {
        void onReportLocation(LocationResult locationResult);

        void onStateChanged(State state, State state2);
    }

    protected abstract void dump(FileDescriptor fileDescriptor, PrintWriter printWriter, String[] strArr);

    protected abstract void onExtraCommand(int i, int i2, String str, Bundle bundle);

    protected abstract void onFlush(Runnable runnable);

    protected abstract void onSetRequest(ProviderRequest providerRequest);

    public static final class State {
        public static final State EMPTY_STATE = new State(false, null, null, Collections.emptySet());
        public final boolean allowed;
        public final Set<String> extraAttributionTags;
        public final CallerIdentity identity;
        public final ProviderProperties properties;

        private State(boolean allowed, ProviderProperties properties, CallerIdentity identity, Set<String> extraAttributionTags) {
            this.allowed = allowed;
            this.properties = properties;
            this.identity = identity;
            this.extraAttributionTags = (Set) Objects.requireNonNull(extraAttributionTags);
        }

        public State withAllowed(boolean allowed) {
            if (allowed == this.allowed) {
                return this;
            }
            return new State(allowed, this.properties, this.identity, this.extraAttributionTags);
        }

        public State withProperties(ProviderProperties properties) {
            if (Objects.equals(properties, this.properties)) {
                return this;
            }
            return new State(this.allowed, properties, this.identity, this.extraAttributionTags);
        }

        public State withIdentity(CallerIdentity identity) {
            if (Objects.equals(identity, this.identity)) {
                return this;
            }
            return new State(this.allowed, this.properties, identity, this.extraAttributionTags);
        }

        public State withExtraAttributionTags(Set<String> extraAttributionTags) {
            if (extraAttributionTags.equals(this.extraAttributionTags)) {
                return this;
            }
            return new State(this.allowed, this.properties, this.identity, extraAttributionTags);
        }

        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof State)) {
                return false;
            }
            State state = (State) o;
            return this.allowed == state.allowed && this.properties == state.properties && Objects.equals(this.identity, state.identity) && this.extraAttributionTags.equals(state.extraAttributionTags);
        }

        public int hashCode() {
            return Objects.hash(Boolean.valueOf(this.allowed), this.properties, this.identity, this.extraAttributionTags);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    static class InternalState {
        public final Listener listener;
        public final State state;

        InternalState(Listener listener, State state) {
            this.listener = listener;
            this.state = state;
        }

        /* JADX INFO: Access modifiers changed from: package-private */
        public InternalState withListener(Listener listener) {
            if (listener == this.listener) {
                return this;
            }
            return new InternalState(listener, this.state);
        }

        InternalState withState(State state) {
            if (state.equals(this.state)) {
                return this;
            }
            return new InternalState(this.listener, state);
        }

        InternalState withState(UnaryOperator<State> operator) {
            return withState((State) operator.apply(this.state));
        }
    }

    protected AbstractLocationProvider(Executor executor, CallerIdentity identity, ProviderProperties properties, Set<String> extraAttributionTags) {
        Preconditions.checkArgument(identity == null || identity.getListenerId() == null);
        this.mExecutor = (Executor) Objects.requireNonNull(executor);
        this.mInternalState = new AtomicReference<>(new InternalState(null, State.EMPTY_STATE.withIdentity(identity).withProperties(properties).withExtraAttributionTags(extraAttributionTags)));
        this.mController = new Controller();
    }

    LocationProviderController getController() {
        return this.mController;
    }

    protected void setState(final UnaryOperator<State> operator) {
        final AtomicReference<State> oldStateRef = new AtomicReference<>();
        InternalState newInternalState = this.mInternalState.updateAndGet(new UnaryOperator() { // from class: com.android.server.location.provider.AbstractLocationProvider$$ExternalSyntheticLambda4
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                return AbstractLocationProvider.lambda$setState$0(oldStateRef, operator, (AbstractLocationProvider.InternalState) obj);
            }
        });
        State oldState = oldStateRef.get();
        if (!oldState.equals(newInternalState.state) && newInternalState.listener != null) {
            long identity = Binder.clearCallingIdentity();
            try {
                newInternalState.listener.onStateChanged(oldState, newInternalState.state);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    static /* synthetic */ InternalState lambda$setState$0(AtomicReference oldStateRef, UnaryOperator operator, InternalState internalState) {
        oldStateRef.set(internalState.state);
        return internalState.withState((UnaryOperator<State>) operator);
    }

    public final State getState() {
        return this.mInternalState.get().state;
    }

    protected void setAllowed(final boolean allowed) {
        setState(new UnaryOperator() { // from class: com.android.server.location.provider.AbstractLocationProvider$$ExternalSyntheticLambda1
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                return ((AbstractLocationProvider.State) obj).withAllowed(allowed);
            }
        });
    }

    protected void setProperties(final ProviderProperties properties) {
        setState(new UnaryOperator() { // from class: com.android.server.location.provider.AbstractLocationProvider$$ExternalSyntheticLambda3
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                return ((AbstractLocationProvider.State) obj).withProperties(properties);
            }
        });
    }

    protected void setIdentity(final CallerIdentity identity) {
        Preconditions.checkArgument(identity == null || identity.getListenerId() == null);
        setState(new UnaryOperator() { // from class: com.android.server.location.provider.AbstractLocationProvider$$ExternalSyntheticLambda2
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                return ((AbstractLocationProvider.State) obj).withIdentity(identity);
            }
        });
    }

    public final Set<String> getExtraAttributionTags() {
        return this.mInternalState.get().state.extraAttributionTags;
    }

    protected void setExtraAttributionTags(final Set<String> extraAttributionTags) {
        setState(new UnaryOperator() { // from class: com.android.server.location.provider.AbstractLocationProvider$$ExternalSyntheticLambda0
            @Override // java.util.function.Function
            public final Object apply(Object obj) {
                return ((AbstractLocationProvider.State) obj).withExtraAttributionTags(extraAttributionTags);
            }
        });
    }

    protected void reportLocation(LocationResult locationResult) {
        Listener listener = this.mInternalState.get().listener;
        if (listener != null) {
            long identity = Binder.clearCallingIdentity();
            try {
                listener.onReportLocation((LocationResult) Objects.requireNonNull(locationResult));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    protected void onStart() {
    }

    protected void onStop() {
    }

    /* JADX INFO: Access modifiers changed from: private */
    class Controller implements LocationProviderController {
        private boolean mStarted = false;

        Controller() {
        }

        @Override // com.android.server.location.provider.LocationProviderController
        public State setListener(final Listener listener) {
            InternalState oldInternalState = (InternalState) AbstractLocationProvider.this.mInternalState.getAndUpdate(new UnaryOperator() { // from class: com.android.server.location.provider.AbstractLocationProvider$Controller$$ExternalSyntheticLambda5
                @Override // java.util.function.Function
                public final Object apply(Object obj) {
                    return ((AbstractLocationProvider.InternalState) obj).withListener(listener);
                }
            });
            Preconditions.checkState(listener == null || oldInternalState.listener == null);
            return oldInternalState.state;
        }

        @Override // com.android.server.location.provider.LocationProviderController
        public boolean isStarted() {
            return this.mStarted;
        }

        @Override // com.android.server.location.provider.LocationProviderController
        public void start() {
            Preconditions.checkState(!this.mStarted);
            this.mStarted = true;
            Executor executor = AbstractLocationProvider.this.mExecutor;
            final AbstractLocationProvider abstractLocationProvider = AbstractLocationProvider.this;
            executor.execute(new Runnable() { // from class: com.android.server.location.provider.AbstractLocationProvider$Controller$$ExternalSyntheticLambda1
                @Override // java.lang.Runnable
                public final void run() {
                    abstractLocationProvider.onStart();
                }
            });
        }

        @Override // com.android.server.location.provider.LocationProviderController
        public void stop() {
            Preconditions.checkState(this.mStarted);
            this.mStarted = false;
            Executor executor = AbstractLocationProvider.this.mExecutor;
            final AbstractLocationProvider abstractLocationProvider = AbstractLocationProvider.this;
            executor.execute(new Runnable() { // from class: com.android.server.location.provider.AbstractLocationProvider$Controller$$ExternalSyntheticLambda3
                @Override // java.lang.Runnable
                public final void run() {
                    abstractLocationProvider.onStop();
                }
            });
        }

        @Override // com.android.server.location.provider.LocationProviderController
        public void setRequest(final ProviderRequest request) {
            Preconditions.checkState(this.mStarted);
            AbstractLocationProvider.this.mExecutor.execute(new Runnable() { // from class: com.android.server.location.provider.AbstractLocationProvider$Controller$$ExternalSyntheticLambda2
                @Override // java.lang.Runnable
                public final void run() {
                    this.f$0.lambda$setRequest$1(request);
                }
            });
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$setRequest$1(ProviderRequest request) {
            AbstractLocationProvider.this.onSetRequest(request);
        }

        @Override // com.android.server.location.provider.LocationProviderController
        public void flush(final Runnable listener) {
            Preconditions.checkState(this.mStarted);
            AbstractLocationProvider.this.mExecutor.execute(new Runnable() { // from class: com.android.server.location.provider.AbstractLocationProvider$Controller$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    this.f$0.lambda$flush$2(listener);
                }
            });
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$flush$2(Runnable listener) {
            AbstractLocationProvider.this.onFlush(listener);
        }

        @Override // com.android.server.location.provider.LocationProviderController
        public void sendExtraCommand(final int uid, final int pid, final String command, final Bundle extras) {
            Preconditions.checkState(this.mStarted);
            AbstractLocationProvider.this.mExecutor.execute(new Runnable() { // from class: com.android.server.location.provider.AbstractLocationProvider$Controller$$ExternalSyntheticLambda4
                @Override // java.lang.Runnable
                public final void run() {
                    this.f$0.lambda$sendExtraCommand$3(uid, pid, command, extras);
                }
            });
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$sendExtraCommand$3(int uid, int pid, String command, Bundle extras) {
            AbstractLocationProvider.this.onExtraCommand(uid, pid, command, extras);
        }
    }

    public IAbstractLocationProviderWrapper getWrapper() {
        return this.mAbstractLocationProviderWrapper;
    }

    private class AbstractLocationProviderWrapper implements IAbstractLocationProviderWrapper {
        private AbstractLocationProviderWrapper() {
        }

        @Override // com.android.server.location.provider.IAbstractLocationProviderWrapper
        public State setListener(Listener listener) {
            return AbstractLocationProvider.this.mController.setListener(listener);
        }

        @Override // com.android.server.location.provider.IAbstractLocationProviderWrapper
        public boolean isStarted() {
            return AbstractLocationProvider.this.mController.isStarted();
        }

        @Override // com.android.server.location.provider.IAbstractLocationProviderWrapper
        public void start() {
            AbstractLocationProvider.this.mController.start();
        }

        @Override // com.android.server.location.provider.IAbstractLocationProviderWrapper
        public void stop() {
            AbstractLocationProvider.this.mController.stop();
        }

        @Override // com.android.server.location.provider.IAbstractLocationProviderWrapper
        public void setRequest(ProviderRequest request) {
            AbstractLocationProvider.this.mController.setRequest(request);
        }

        @Override // com.android.server.location.provider.IAbstractLocationProviderWrapper
        public void flush(Runnable listener) {
            AbstractLocationProvider.this.mController.flush(listener);
        }
    }
}
