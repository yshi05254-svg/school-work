package com.android.server.location.listeners;

import android.util.ArrayMap;
import android.util.ArraySet;
import com.android.internal.listeners.ListenerExecutor;
import com.android.internal.util.Preconditions;
import com.android.server.location.listeners.ListenerRegistration;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/* JADX INFO: loaded from: classes2.dex */
public abstract class ListenerMultiplexer<TKey, TListener, TRegistration extends ListenerRegistration<TListener>, TMergedRegistration> {
    private TMergedRegistration mMerged;
    protected final Object mMultiplexerLock = new Object();
    private final ArrayMap<TKey, TRegistration> mRegistrations = new ArrayMap<>();
    private final ListenerMultiplexer<TKey, TListener, TRegistration, TMergedRegistration>.UpdateServiceBuffer mUpdateServiceBuffer = new UpdateServiceBuffer();
    private final ListenerMultiplexer<TKey, TListener, TRegistration, TMergedRegistration>.ReentrancyGuard mReentrancyGuard = new ReentrancyGuard();
    private int mActiveRegistrationsCount = 0;
    private boolean mServiceRegistered = false;

    protected abstract boolean isActive(TRegistration tregistration);

    protected abstract TMergedRegistration mergeRegistrations(Collection<TRegistration> collection);

    protected abstract boolean registerWithService(TMergedRegistration tmergedregistration, Collection<TRegistration> collection);

    protected abstract void unregisterWithService();

    protected boolean reregisterWithService(TMergedRegistration oldMerged, TMergedRegistration newMerged, Collection<TRegistration> registrations) {
        return registerWithService(newMerged, registrations);
    }

    protected void onRegister() {
    }

    protected void onUnregister() {
    }

    protected void onRegistrationAdded(TKey key, TRegistration registration) {
    }

    protected void onRegistrationReplaced(TKey oldKey, TRegistration oldRegistration, TKey newKey, TRegistration newRegistration) {
        onRegistrationRemoved(oldKey, oldRegistration);
        onRegistrationAdded(newKey, newRegistration);
    }

    protected void onRegistrationRemoved(TKey key, TRegistration registration) {
    }

    protected void onActive() {
    }

    protected void onInactive() {
    }

    protected final void putRegistration(TKey key, TRegistration registration) {
        replaceRegistration(key, key, registration);
    }

    protected final void replaceRegistration(TKey oldKey, TKey key, TRegistration registration) {
        Objects.requireNonNull(oldKey);
        Objects.requireNonNull(key);
        Objects.requireNonNull(registration);
        synchronized (this.mMultiplexerLock) {
            boolean z = true;
            Preconditions.checkState(!this.mReentrancyGuard.isReentrant());
            if (oldKey != key && this.mRegistrations.containsKey(key)) {
                z = false;
            }
            Preconditions.checkArgument(z);
            ListenerMultiplexer<TKey, TListener, TRegistration, TMergedRegistration>.UpdateServiceBuffer ignored1 = this.mUpdateServiceBuffer.acquire();
            try {
                ListenerMultiplexer<TKey, TListener, TRegistration, TMergedRegistration>.ReentrancyGuard ignored2 = this.mReentrancyGuard.acquire();
                try {
                    boolean wasEmpty = this.mRegistrations.isEmpty();
                    TRegistration oldRegistration = null;
                    int oldIndex = this.mRegistrations.indexOfKey(oldKey);
                    if (oldIndex >= 0) {
                        oldRegistration = this.mRegistrations.valueAt(oldIndex);
                        unregister(oldRegistration);
                        oldRegistration.onUnregister();
                        if (oldKey != key) {
                            this.mRegistrations.removeAt(oldIndex);
                        }
                    }
                    if (oldKey == key && oldIndex >= 0) {
                        this.mRegistrations.setValueAt(oldIndex, registration);
                    } else {
                        this.mRegistrations.put(key, registration);
                    }
                    if (wasEmpty) {
                        onRegister();
                    }
                    registration.onRegister(key);
                    if (oldRegistration == null) {
                        onRegistrationAdded(key, registration);
                    } else {
                        onRegistrationReplaced(oldKey, oldRegistration, key, registration);
                    }
                    onRegistrationActiveChanged(registration);
                    if (ignored2 != null) {
                        ignored2.close();
                    }
                    if (ignored1 != null) {
                        ignored1.close();
                    }
                } catch (Throwable th) {
                    if (ignored2 != null) {
                        try {
                            ignored2.close();
                        } catch (Throwable th2) {
                            th.addSuppressed(th2);
                        }
                    }
                    throw th;
                }
            } catch (Throwable th3) {
                if (ignored1 != null) {
                    try {
                        ignored1.close();
                    } catch (Throwable th4) {
                        th3.addSuppressed(th4);
                    }
                }
                throw th3;
            }
        }
    }

    protected final void removeRegistrationIf(Predicate<TKey> predicate) {
        synchronized (this.mMultiplexerLock) {
            Preconditions.checkState(!this.mReentrancyGuard.isReentrant());
            ListenerMultiplexer<TKey, TListener, TRegistration, TMergedRegistration>.UpdateServiceBuffer ignored1 = this.mUpdateServiceBuffer.acquire();
            try {
                ListenerMultiplexer<TKey, TListener, TRegistration, TMergedRegistration>.ReentrancyGuard ignored2 = this.mReentrancyGuard.acquire();
                try {
                    int size = this.mRegistrations.size();
                    for (int i = 0; i < size; i++) {
                        TKey key = this.mRegistrations.keyAt(i);
                        if (predicate.test(key)) {
                            removeRegistration(key, this.mRegistrations.valueAt(i));
                        }
                    }
                    if (ignored2 != null) {
                        ignored2.close();
                    }
                    if (ignored1 != null) {
                        ignored1.close();
                    }
                } catch (Throwable th) {
                    if (ignored2 != null) {
                        try {
                            ignored2.close();
                        } catch (Throwable th2) {
                            th.addSuppressed(th2);
                        }
                    }
                    throw th;
                }
            } catch (Throwable th3) {
                if (ignored1 != null) {
                    try {
                        ignored1.close();
                    } catch (Throwable th4) {
                        th3.addSuppressed(th4);
                    }
                }
                throw th3;
            }
        }
    }

    protected final void removeRegistration(TKey key) {
        synchronized (this.mMultiplexerLock) {
            Preconditions.checkState(!this.mReentrancyGuard.isReentrant());
            int index = this.mRegistrations.indexOfKey(key);
            if (index < 0) {
                return;
            }
            removeRegistration(index);
        }
    }

    protected final void removeRegistration(TKey key, ListenerRegistration<?> registration) {
        synchronized (this.mMultiplexerLock) {
            int index = this.mRegistrations.indexOfKey(key);
            if (index < 0) {
                return;
            }
            TRegistration tregistrationValueAt = this.mRegistrations.valueAt(index);
            if (tregistrationValueAt != registration) {
                return;
            }
            if (this.mReentrancyGuard.isReentrant()) {
                unregister(tregistrationValueAt);
                this.mReentrancyGuard.markForRemoval(key, tregistrationValueAt);
            } else {
                removeRegistration(index);
            }
        }
    }

    private void removeRegistration(int index) {
        TKey key = this.mRegistrations.keyAt(index);
        TRegistration registration = this.mRegistrations.valueAt(index);
        ListenerMultiplexer<TKey, TListener, TRegistration, TMergedRegistration>.UpdateServiceBuffer ignored1 = this.mUpdateServiceBuffer.acquire();
        try {
            ListenerMultiplexer<TKey, TListener, TRegistration, TMergedRegistration>.ReentrancyGuard ignored2 = this.mReentrancyGuard.acquire();
            try {
                unregister(registration);
                onRegistrationRemoved(key, registration);
                registration.onUnregister();
                this.mRegistrations.removeAt(index);
                if (this.mRegistrations.isEmpty()) {
                    onUnregister();
                }
                if (ignored2 != null) {
                    ignored2.close();
                }
                if (ignored1 != null) {
                    ignored1.close();
                }
            } catch (Throwable th) {
                if (ignored2 != null) {
                    try {
                        ignored2.close();
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                    }
                }
                throw th;
            }
        } catch (Throwable th3) {
            if (ignored1 != null) {
                try {
                    ignored1.close();
                } catch (Throwable th4) {
                    th3.addSuppressed(th4);
                }
            }
            throw th3;
        }
    }

    protected final void updateService() {
        synchronized (this.mMultiplexerLock) {
            if (this.mUpdateServiceBuffer.isBuffered()) {
                this.mUpdateServiceBuffer.markUpdateServiceRequired();
                return;
            }
            int size = this.mRegistrations.size();
            ArrayList<TRegistration> actives = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                TRegistration registration = this.mRegistrations.valueAt(i);
                if (registration.isActive()) {
                    actives.add(registration);
                }
            }
            if (actives.isEmpty()) {
                if (this.mServiceRegistered) {
                    this.mMerged = null;
                    this.mServiceRegistered = false;
                    unregisterWithService();
                }
            } else {
                TMergedRegistration merged = mergeRegistrations(actives);
                if (this.mServiceRegistered) {
                    if (!Objects.equals(merged, this.mMerged)) {
                        this.mServiceRegistered = reregisterWithService(this.mMerged, merged, actives);
                        this.mMerged = this.mServiceRegistered ? merged : null;
                    }
                } else {
                    this.mServiceRegistered = registerWithService(merged, actives);
                    this.mMerged = this.mServiceRegistered ? merged : null;
                }
            }
        }
    }

    protected final void resetService() {
        synchronized (this.mMultiplexerLock) {
            if (this.mServiceRegistered) {
                this.mMerged = null;
                this.mServiceRegistered = false;
                unregisterWithService();
                updateService();
            }
        }
    }

    public UpdateServiceLock newUpdateServiceLock() {
        return new UpdateServiceLock(this.mUpdateServiceBuffer);
    }

    protected final boolean findRegistration(Predicate<TRegistration> predicate) {
        synchronized (this.mMultiplexerLock) {
            ListenerMultiplexer<TKey, TListener, TRegistration, TMergedRegistration>.ReentrancyGuard ignored = this.mReentrancyGuard.acquire();
            try {
                int size = this.mRegistrations.size();
                for (int i = 0; i < size; i++) {
                    TRegistration registration = this.mRegistrations.valueAt(i);
                    if (predicate.test(registration)) {
                        if (ignored != null) {
                            ignored.close();
                        }
                        return true;
                    }
                }
                if (ignored != null) {
                    ignored.close();
                }
                return false;
            } catch (Throwable th) {
                if (ignored != null) {
                    try {
                        ignored.close();
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                    }
                }
                throw th;
            }
        }
    }

    protected final void updateRegistrations(Predicate<TRegistration> predicate) {
        synchronized (this.mMultiplexerLock) {
            ListenerMultiplexer<TKey, TListener, TRegistration, TMergedRegistration>.UpdateServiceBuffer ignored1 = this.mUpdateServiceBuffer.acquire();
            try {
                ListenerMultiplexer<TKey, TListener, TRegistration, TMergedRegistration>.ReentrancyGuard ignored2 = this.mReentrancyGuard.acquire();
                try {
                    int size = this.mRegistrations.size();
                    for (int i = 0; i < size; i++) {
                        TRegistration registration = this.mRegistrations.valueAt(i);
                        if (predicate.test(registration)) {
                            onRegistrationActiveChanged(registration);
                        }
                    }
                    if (ignored2 != null) {
                        ignored2.close();
                    }
                    if (ignored1 != null) {
                        ignored1.close();
                    }
                } catch (Throwable th) {
                    if (ignored2 != null) {
                        try {
                            ignored2.close();
                        } catch (Throwable th2) {
                            th.addSuppressed(th2);
                        }
                    }
                    throw th;
                }
            } catch (Throwable th3) {
                if (ignored1 != null) {
                    try {
                        ignored1.close();
                    } catch (Throwable th4) {
                        th3.addSuppressed(th4);
                    }
                }
                throw th3;
            }
        }
    }

    /* JADX WARN: Code duplicated, block: B:46:0x0053 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    protected final boolean updateRegistration(Object key, Predicate<TRegistration> predicate) {
        synchronized (this.mMultiplexerLock) {
            ListenerMultiplexer<TKey, TListener, TRegistration, TMergedRegistration>.UpdateServiceBuffer ignored1 = this.mUpdateServiceBuffer.acquire();
            try {
                ListenerMultiplexer<TKey, TListener, TRegistration, TMergedRegistration>.ReentrancyGuard ignored2 = this.mReentrancyGuard.acquire();
                try {
                    int index = this.mRegistrations.indexOfKey(key);
                    if (index >= 0) {
                        TRegistration registration = this.mRegistrations.valueAt(index);
                        if (predicate.test(registration)) {
                            onRegistrationActiveChanged(registration);
                        }
                        if (ignored2 != null) {
                            ignored2.close();
                        }
                        if (ignored1 != null) {
                            ignored1.close();
                        }
                        return true;
                    }
                    if (ignored2 != null) {
                        ignored2.close();
                    }
                    if (ignored1 != null) {
                        ignored1.close();
                    }
                    return false;
                } catch (Throwable th) {
                    if (ignored2 != null) {
                        try {
                            ignored2.close();
                        } catch (Throwable th2) {
                            th.addSuppressed(th2);
                        }
                    }
                    throw th;
                }
            } catch (Throwable th3) {
                if (ignored1 != null) {
                    try {
                        ignored1.close();
                    } catch (Throwable th4) {
                        th3.addSuppressed(th4);
                    }
                }
                throw th3;
            }
            if (ignored1 != null) {
                ignored1.close();
            }
            throw th3;
        }
    }

    private void onRegistrationActiveChanged(TRegistration registration) {
        boolean active = registration.isRegistered() && isActive(registration);
        boolean changed = registration.setActive(active);
        if (changed) {
            if (active) {
                int i = this.mActiveRegistrationsCount + 1;
                this.mActiveRegistrationsCount = i;
                if (i == 1) {
                    onActive();
                }
                registration.onActive();
            } else {
                registration.onInactive();
                int i2 = this.mActiveRegistrationsCount - 1;
                this.mActiveRegistrationsCount = i2;
                if (i2 == 0) {
                    onInactive();
                }
            }
            updateService();
        }
    }

    protected final void deliverToListeners(Function<TRegistration, ListenerExecutor.ListenerOperation<TListener>> function) {
        ListenerExecutor.ListenerOperation<TListener> operation;
        synchronized (this.mMultiplexerLock) {
            ListenerMultiplexer<TKey, TListener, TRegistration, TMergedRegistration>.ReentrancyGuard ignored = this.mReentrancyGuard.acquire();
            try {
                int size = this.mRegistrations.size();
                for (int i = 0; i < size; i++) {
                    TRegistration registration = this.mRegistrations.valueAt(i);
                    if (registration.isActive() && (operation = function.apply(registration)) != null) {
                        registration.executeOperation(operation);
                    }
                }
                if (ignored != null) {
                    ignored.close();
                }
            } catch (Throwable th) {
                if (ignored != null) {
                    try {
                        ignored.close();
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                    }
                }
                throw th;
            }
        }
    }

    protected final void deliverToListeners(ListenerExecutor.ListenerOperation<TListener> operation) {
        synchronized (this.mMultiplexerLock) {
            ListenerMultiplexer<TKey, TListener, TRegistration, TMergedRegistration>.ReentrancyGuard ignored = this.mReentrancyGuard.acquire();
            try {
                int size = this.mRegistrations.size();
                for (int i = 0; i < size; i++) {
                    TRegistration registration = this.mRegistrations.valueAt(i);
                    if (registration.isActive()) {
                        registration.executeOperation(operation);
                    }
                }
                if (ignored != null) {
                    ignored.close();
                }
            } catch (Throwable th) {
                if (ignored != null) {
                    try {
                        ignored.close();
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                    }
                }
                throw th;
            }
        }
    }

    private void unregister(TRegistration registration) {
        registration.unregisterInternal();
        onRegistrationActiveChanged(registration);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (this.mMultiplexerLock) {
            pw.print("service: ");
            pw.print(getServiceState());
            pw.println();
            if (!this.mRegistrations.isEmpty()) {
                pw.println("listeners:");
                int size = this.mRegistrations.size();
                for (int i = 0; i < size; i++) {
                    TRegistration registration = this.mRegistrations.valueAt(i);
                    pw.print("  ");
                    pw.print(registration);
                    if (!registration.isActive()) {
                        pw.println(" (inactive)");
                    } else {
                        pw.println();
                    }
                }
            }
        }
    }

    protected String getServiceState() {
        if (this.mServiceRegistered) {
            if (this.mMerged != null) {
                return this.mMerged.toString();
            }
            return "registered";
        }
        return "unregistered";
    }

    private final class ReentrancyGuard implements AutoCloseable {
        private int mGuardCount = 0;
        private ArraySet<Map.Entry<TKey, ListenerRegistration<?>>> mScheduledRemovals = null;

        ReentrancyGuard() {
        }

        boolean isReentrant() {
            boolean z;
            synchronized (ListenerMultiplexer.this.mMultiplexerLock) {
                z = this.mGuardCount != 0;
            }
            return z;
        }

        void markForRemoval(TKey key, ListenerRegistration<?> registration) {
            synchronized (ListenerMultiplexer.this.mMultiplexerLock) {
                Preconditions.checkState(isReentrant());
                if (this.mScheduledRemovals == null) {
                    this.mScheduledRemovals = new ArraySet<>(ListenerMultiplexer.this.mRegistrations.size());
                }
                this.mScheduledRemovals.add(new AbstractMap.SimpleImmutableEntry(key, registration));
            }
        }

        ListenerMultiplexer<TKey, TListener, TRegistration, TMergedRegistration>.ReentrancyGuard acquire() {
            synchronized (ListenerMultiplexer.this.mMultiplexerLock) {
                this.mGuardCount++;
            }
            return this;
        }

        @Override // java.lang.AutoCloseable
        public void close() {
            synchronized (ListenerMultiplexer.this.mMultiplexerLock) {
                Preconditions.checkState(this.mGuardCount > 0);
                ArraySet<Map.Entry<TKey, ListenerRegistration<?>>> scheduledRemovals = null;
                int i = this.mGuardCount - 1;
                this.mGuardCount = i;
                if (i == 0) {
                    scheduledRemovals = this.mScheduledRemovals;
                    this.mScheduledRemovals = null;
                }
                if (scheduledRemovals == null) {
                    return;
                }
                ListenerMultiplexer<TKey, TListener, TRegistration, TMergedRegistration>.UpdateServiceBuffer ignored = ListenerMultiplexer.this.mUpdateServiceBuffer.acquire();
                try {
                    int size = scheduledRemovals.size();
                    for (int i2 = 0; i2 < size; i2++) {
                        Map.Entry<TKey, ListenerRegistration<?>> entry = scheduledRemovals.valueAt(i2);
                        ListenerMultiplexer.this.removeRegistration(entry.getKey(), entry.getValue());
                    }
                    if (ignored != null) {
                        ignored.close();
                    }
                } catch (Throwable th) {
                    if (ignored != null) {
                        try {
                            ignored.close();
                        } catch (Throwable th2) {
                            th.addSuppressed(th2);
                        }
                    }
                    throw th;
                }
            }
        }
    }

    private final class UpdateServiceBuffer implements AutoCloseable {
        private int mBufferCount = 0;
        private boolean mUpdateServiceRequired = false;

        UpdateServiceBuffer() {
        }

        synchronized boolean isBuffered() {
            return this.mBufferCount != 0;
        }

        synchronized void markUpdateServiceRequired() {
            Preconditions.checkState(isBuffered());
            this.mUpdateServiceRequired = true;
        }

        synchronized ListenerMultiplexer<TKey, TListener, TRegistration, TMergedRegistration>.UpdateServiceBuffer acquire() {
            this.mBufferCount++;
            return this;
        }

        @Override // java.lang.AutoCloseable
        public void close() {
            boolean updateServiceRequired = false;
            synchronized (this) {
                Preconditions.checkState(this.mBufferCount > 0);
                int i = this.mBufferCount - 1;
                this.mBufferCount = i;
                if (i == 0) {
                    updateServiceRequired = this.mUpdateServiceRequired;
                    this.mUpdateServiceRequired = false;
                }
            }
            if (updateServiceRequired) {
                ListenerMultiplexer.this.updateService();
            }
        }
    }

    public static final class UpdateServiceLock implements AutoCloseable {
        private ListenerMultiplexer<?, ?, ?, ?>.UpdateServiceBuffer mUpdateServiceBuffer;

        UpdateServiceLock(ListenerMultiplexer<?, ?, ?, ?>.UpdateServiceBuffer updateServiceBuffer) {
            this.mUpdateServiceBuffer = updateServiceBuffer.acquire();
        }

        @Override // java.lang.AutoCloseable
        public void close() {
            if (this.mUpdateServiceBuffer != null) {
                ListenerMultiplexer<?, ?, ?, ?>.UpdateServiceBuffer buffer = this.mUpdateServiceBuffer;
                this.mUpdateServiceBuffer = null;
                buffer.close();
            }
        }
    }
}
