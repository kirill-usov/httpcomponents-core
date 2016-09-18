/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.hc.core5.pool.nio;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.concurrent.BasicFuture;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.pool.ConnPool;
import org.apache.hc.core5.pool.ConnPoolControl;
import org.apache.hc.core5.pool.PoolEntry;
import org.apache.hc.core5.pool.PoolEntryCallback;
import org.apache.hc.core5.pool.PoolStats;
import org.apache.hc.core5.reactor.ConnectingIOReactor;
import org.apache.hc.core5.reactor.IOReactorStatus;
import org.apache.hc.core5.reactor.IOSession;
import org.apache.hc.core5.reactor.SessionRequest;
import org.apache.hc.core5.reactor.SessionRequestCallback;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.Asserts;

/**
 * Abstract non-blocking connection pool.
 *
 * @param <T> route
 * @param <C> connection object
 * @param <E> pool entry
 *
 * @since 4.2
 */
@Contract(threading = ThreadingBehavior.SAFE_CONDITIONAL)
public abstract class AbstractNIOConnPool<T, C, E extends PoolEntry<T, C>>
                                                  implements ConnPool<T, E>, ConnPoolControl<T> {

    private final ConnectingIOReactor ioreactor;
    private final NIOConnFactory<T, C> connFactory;
    private final SocketAddressResolver<T> addressResolver;
    private final SessionRequestCallback sessionRequestCallback;
    private final Map<T, RouteSpecificPool<T, C, E>> routeToPool;
    private final LinkedList<LeaseRequest<T, C, E>> leasingRequests;
    private final Set<SessionRequest> pending;
    private final Set<E> leased;
    private final LinkedList<E> available;
    private final ConcurrentLinkedQueue<LeaseRequest<T, C, E>> completedRequests;
    private final Map<T, Integer> maxPerRoute;
    private final Lock lock;
    private final AtomicBoolean isShutDown;

    private volatile int defaultMaxPerRoute;
    private volatile int maxTotal;

    /**
     * @since 4.3
     */
    public AbstractNIOConnPool(
            final ConnectingIOReactor ioreactor,
            final NIOConnFactory<T, C> connFactory,
            final SocketAddressResolver<T> addressResolver,
            final int defaultMaxPerRoute,
            final int maxTotal) {
        super();
        Args.notNull(ioreactor, "I/O reactor");
        Args.notNull(connFactory, "Connection factory");
        Args.notNull(addressResolver, "Address resolver");
        Args.positive(defaultMaxPerRoute, "Max per route value");
        Args.positive(maxTotal, "Max total value");
        this.ioreactor = ioreactor;
        this.connFactory = connFactory;
        this.addressResolver = addressResolver;
        this.sessionRequestCallback = new InternalSessionRequestCallback();
        this.routeToPool = new HashMap<>();
        this.leasingRequests = new LinkedList<>();
        this.pending = new HashSet<>();
        this.leased = new HashSet<>();
        this.available = new LinkedList<>();
        this.completedRequests = new ConcurrentLinkedQueue<>();
        this.maxPerRoute = new HashMap<>();
        this.lock = new ReentrantLock();
        this.isShutDown = new AtomicBoolean(false);
        this.defaultMaxPerRoute = defaultMaxPerRoute;
        this.maxTotal = maxTotal;
    }

    protected abstract E createEntry(T route, C conn);

    /**
     * @since 4.3
     */
    protected void onLease(final E entry) {
    }

    /**
     * @since 4.3
     */
    protected void onRelease(final E entry) {
    }

    /**
     * @since 4.4
     */
    protected void onReuse(final E entry) {
    }

    public boolean isShutdown() {
        return this.isShutDown.get();
    }

    public void shutdown(final long deadline, final TimeUnit timeUnit) throws IOException {
        Args.notNull(timeUnit, "Time unit");
        if (this.isShutDown.compareAndSet(false, true)) {
            fireCallbacks();
            this.lock.lock();
            try {
                for (final SessionRequest sessionRequest: this.pending) {
                    sessionRequest.cancel();
                }
                for (final E entry: this.available) {
                    entry.close();
                }
                for (final E entry: this.leased) {
                    entry.close();
                }
                for (final RouteSpecificPool<T, C, E> pool: this.routeToPool.values()) {
                    pool.shutdown();
                }
                this.routeToPool.clear();
                this.leased.clear();
                this.pending.clear();
                this.available.clear();
                this.leasingRequests.clear();
                this.ioreactor.shutdown(deadline, timeUnit);
            } finally {
                this.lock.unlock();
            }
        }
    }

    private RouteSpecificPool<T, C, E> getPool(final T route) {
        RouteSpecificPool<T, C, E> pool = this.routeToPool.get(route);
        if (pool == null) {
            pool = new RouteSpecificPool<T, C, E>(route) {

                @Override
                protected E createEntry(final T route, final C conn) {
                    return AbstractNIOConnPool.this.createEntry(route, conn);
                }

            };
            this.routeToPool.put(route, pool);
        }
        return pool;
    }

    public Future<E> lease(
            final T route, final Object state,
            final long connectTimeout, final TimeUnit tunit,
            final FutureCallback<E> callback) {
        return this.lease(route, state, connectTimeout, connectTimeout, tunit, callback);
    }

    /**
     * @since 4.3
     */
    public Future<E> lease(
            final T route, final Object state,
            final long connectTimeout, final long leaseTimeout, final TimeUnit tunit,
            final FutureCallback<E> callback) {
        Args.notNull(route, "Route");
        Args.notNull(tunit, "Time unit");
        Asserts.check(!this.isShutDown.get(), "Connection pool shut down");
        final BasicFuture<E> future = new BasicFuture<>(callback);
        this.lock.lock();
        try {
            final long timeout = connectTimeout > 0 ? tunit.toMillis(connectTimeout) : 0;
            final LeaseRequest<T, C, E> request = new LeaseRequest<>(route, state, timeout, leaseTimeout, future);
            final boolean completed = processPendingRequest(request);
            if (!request.isDone() && !completed) {
                this.leasingRequests.add(request);
            }
            if (request.isDone()) {
                this.completedRequests.add(request);
            }
        } finally {
            this.lock.unlock();
        }
        fireCallbacks();
        return future;
    }

    @Override
    public Future<E> lease(final T route, final Object state, final FutureCallback<E> callback) {
        return lease(route, state, -1, TimeUnit.MICROSECONDS, callback);
    }

    public Future<E> lease(final T route, final Object state) {
        return lease(route, state, -1, TimeUnit.MICROSECONDS, null);
    }

    @Override
    public void release(final E entry, final boolean reusable) {
        if (entry == null) {
            return;
        }
        if (this.isShutDown.get()) {
            return;
        }
        this.lock.lock();
        try {
            if (this.leased.remove(entry)) {
                final RouteSpecificPool<T, C, E> pool = getPool(entry.getRoute());
                pool.free(entry, reusable);
                if (reusable) {
                    this.available.addFirst(entry);
                    onRelease(entry);
                } else {
                    entry.close();
                }
                processNextPendingRequest();
            }
        } finally {
            this.lock.unlock();
        }
        fireCallbacks();
    }

    private void processPendingRequests() {
        final ListIterator<LeaseRequest<T, C, E>> it = this.leasingRequests.listIterator();
        while (it.hasNext()) {
            final LeaseRequest<T, C, E> request = it.next();
            final boolean completed = processPendingRequest(request);
            if (request.isDone() || completed) {
                it.remove();
            }
            if (request.isDone()) {
                this.completedRequests.add(request);
            }
        }
    }

    private void processNextPendingRequest() {
        final ListIterator<LeaseRequest<T, C, E>> it = this.leasingRequests.listIterator();
        while (it.hasNext()) {
            final LeaseRequest<T, C, E> request = it.next();
            final boolean completed = processPendingRequest(request);
            if (request.isDone() || completed) {
                it.remove();
            }
            if (request.isDone()) {
                this.completedRequests.add(request);
            }
            if (completed) {
                return;
            }
        }
    }

    private boolean processPendingRequest(final LeaseRequest<T, C, E> request) {
        final T route = request.getRoute();
        final Object state = request.getState();
        final long deadline = request.getDeadline();

        final long now = System.currentTimeMillis();
        if (now > deadline) {
            request.failed(new TimeoutException());
            return false;
        }

        final RouteSpecificPool<T, C, E> pool = getPool(route);
        E entry;
        for (;;) {
            entry = pool.getFree(state);
            if (entry == null) {
                break;
            }
            if (entry.isClosed() || entry.isExpired(System.currentTimeMillis())) {
                entry.close();
                this.available.remove(entry);
                pool.free(entry, false);
            } else {
                break;
            }
        }
        if (entry != null) {
            this.available.remove(entry);
            this.leased.add(entry);
            request.completed(entry);
            onReuse(entry);
            onLease(entry);
            return true;
        }

        // New connection is needed
        final int maxPerRoute = getMax(route);
        // Shrink the pool prior to allocating a new connection
        final int excess = Math.max(0, pool.getAllocatedCount() + 1 - maxPerRoute);
        if (excess > 0) {
            for (int i = 0; i < excess; i++) {
                final E lastUsed = pool.getLastUsed();
                if (lastUsed == null) {
                    break;
                }
                lastUsed.close();
                this.available.remove(lastUsed);
                pool.remove(lastUsed);
            }
        }

        if (pool.getAllocatedCount() < maxPerRoute) {
            final int totalUsed = this.pending.size() + this.leased.size();
            final int freeCapacity = Math.max(this.maxTotal - totalUsed, 0);
            if (freeCapacity == 0) {
                return false;
            }
            final int totalAvailable = this.available.size();
            if (totalAvailable > freeCapacity - 1) {
                if (!this.available.isEmpty()) {
                    final E lastUsed = this.available.removeLast();
                    lastUsed.close();
                    final RouteSpecificPool<T, C, E> otherpool = getPool(lastUsed.getRoute());
                    otherpool.remove(lastUsed);
                }
            }

            final SocketAddress localAddress;
            final SocketAddress remoteAddress;
            try {
                remoteAddress = this.addressResolver.resolveRemoteAddress(route);
                localAddress = this.addressResolver.resolveLocalAddress(route);
            } catch (final IOException ex) {
                request.failed(ex);
                return false;
            }

            final SessionRequest sessionRequest = this.ioreactor.connect(
                    remoteAddress, localAddress, route, this.sessionRequestCallback);
            final int timout = request.getConnectTimeout() < Integer.MAX_VALUE ?
                    (int) request.getConnectTimeout() : Integer.MAX_VALUE;
            sessionRequest.setConnectTimeout(timout);
            this.pending.add(sessionRequest);
            pool.addPending(sessionRequest, request.getFuture());
            return true;
        }
        return false;
    }

    private void fireCallbacks() {
        LeaseRequest<T, C, E> request;
        while ((request = this.completedRequests.poll()) != null) {
            final BasicFuture<E> future = request.getFuture();
            final Exception ex = request.getException();
            final E result = request.getResult();
            if (ex != null) {
                future.failed(ex);
            } else if (result != null) {
                future.completed(result);
            } else {
                future.cancel();
            }
        }
    }

    public void validatePendingRequests() {
        this.lock.lock();
        try {
            final long now = System.currentTimeMillis();
            final ListIterator<LeaseRequest<T, C, E>> it = this.leasingRequests.listIterator();
            while (it.hasNext()) {
                final LeaseRequest<T, C, E> request = it.next();
                final long deadline = request.getDeadline();
                if (now > deadline) {
                    it.remove();
                    request.failed(new TimeoutException());
                    this.completedRequests.add(request);
                }
            }
        } finally {
            this.lock.unlock();
        }
        fireCallbacks();
    }

    protected void requestCompleted(final SessionRequest request) {
        if (this.isShutDown.get()) {
            return;
        }
        @SuppressWarnings("unchecked")
        final
        T route = (T) request.getAttachment();
        this.lock.lock();
        try {
            this.pending.remove(request);
            final RouteSpecificPool<T, C, E> pool = getPool(route);
            final IOSession session = request.getSession();
            try {
                final C conn = this.connFactory.create(route, session);
                final E entry = pool.createEntry(request, conn);
                this.leased.add(entry);
                pool.completed(request, entry);
                onLease(entry);
            } catch (final IOException ex) {
                pool.failed(request, ex);
            }
        } finally {
            this.lock.unlock();
        }
        fireCallbacks();
    }

    protected void requestCancelled(final SessionRequest request) {
        if (this.isShutDown.get()) {
            return;
        }
        @SuppressWarnings("unchecked")
        final
        T route = (T) request.getAttachment();
        this.lock.lock();
        try {
            this.pending.remove(request);
            final RouteSpecificPool<T, C, E> pool = getPool(route);
            pool.cancelled(request);
            if (this.ioreactor.getStatus().compareTo(IOReactorStatus.ACTIVE) <= 0) {
                processNextPendingRequest();
            }
        } finally {
            this.lock.unlock();
        }
        fireCallbacks();
    }

    protected void requestFailed(final SessionRequest request) {
        if (this.isShutDown.get()) {
            return;
        }
        @SuppressWarnings("unchecked")
        final
        T route = (T) request.getAttachment();
        this.lock.lock();
        try {
            this.pending.remove(request);
            final RouteSpecificPool<T, C, E> pool = getPool(route);
            pool.failed(request, request.getException());
            processNextPendingRequest();
        } finally {
            this.lock.unlock();
        }
        fireCallbacks();
    }

    protected void requestTimeout(final SessionRequest request) {
        if (this.isShutDown.get()) {
            return;
        }
        @SuppressWarnings("unchecked")
        final
        T route = (T) request.getAttachment();
        this.lock.lock();
        try {
            this.pending.remove(request);
            final RouteSpecificPool<T, C, E> pool = getPool(route);
            pool.timeout(request);
            processNextPendingRequest();
        } finally {
            this.lock.unlock();
        }
        fireCallbacks();
    }

    private int getMax(final T route) {
        final Integer v = this.maxPerRoute.get(route);
        if (v != null) {
            return v.intValue();
        }
        return this.defaultMaxPerRoute;
    }

    @Override
    public void setMaxTotal(final int max) {
        Args.positive(max, "Max value");
        this.lock.lock();
        try {
            this.maxTotal = max;
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public int getMaxTotal() {
        this.lock.lock();
        try {
            return this.maxTotal;
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public void setDefaultMaxPerRoute(final int max) {
        Args.positive(max, "Max value");
        this.lock.lock();
        try {
            this.defaultMaxPerRoute = max;
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public int getDefaultMaxPerRoute() {
        this.lock.lock();
        try {
            return this.defaultMaxPerRoute;
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public void setMaxPerRoute(final T route, final int max) {
        Args.notNull(route, "Route");
        Args.positive(max, "Max value");
        this.lock.lock();
        try {
            this.maxPerRoute.put(route, Integer.valueOf(max));
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public int getMaxPerRoute(final T route) {
        Args.notNull(route, "Route");
        this.lock.lock();
        try {
            return getMax(route);
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public PoolStats getTotalStats() {
        this.lock.lock();
        try {
            return new PoolStats(
                    this.leased.size(),
                    this.pending.size(),
                    this.available.size(),
                    this.maxTotal);
        } finally {
            this.lock.unlock();
        }
    }

    @Override
    public PoolStats getStats(final T route) {
        Args.notNull(route, "Route");
        this.lock.lock();
        try {
            final RouteSpecificPool<T, C, E> pool = getPool(route);
            return new PoolStats(
                    pool.getLeasedCount(),
                    pool.getPendingCount(),
                    pool.getAvailableCount(),
                    getMax(route));
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Returns snapshot of all knows routes
     *
     * @since 4.4
     */
    public Set<T> getRoutes() {
        this.lock.lock();
        try {
            return new HashSet<>(routeToPool.keySet());
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Enumerates all available connections.
     *
     * @since 4.3
     */
    protected void enumAvailable(final PoolEntryCallback<T, C> callback) {
        this.lock.lock();
        try {
            final Iterator<E> it = this.available.iterator();
            while (it.hasNext()) {
                final E entry = it.next();
                callback.process(entry);
                if (entry.isClosed()) {
                    final RouteSpecificPool<T, C, E> pool = getPool(entry.getRoute());
                    pool.remove(entry);
                    it.remove();
                }
            }
            processPendingRequests();
            purgePoolMap();
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Enumerates all leased connections.
     *
     * @since 4.3
     */
    protected void enumLeased(final PoolEntryCallback<T, C> callback) {
        this.lock.lock();
        try {
            final Iterator<E> it = this.leased.iterator();
            while (it.hasNext()) {
                final E entry = it.next();
                callback.process(entry);
            }
            processPendingRequests();
        } finally {
            this.lock.unlock();
        }
    }

    private void purgePoolMap() {
        final Iterator<Map.Entry<T, RouteSpecificPool<T, C, E>>> it = this.routeToPool.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<T, RouteSpecificPool<T, C, E>> entry = it.next();
            final RouteSpecificPool<T, C, E> pool = entry.getValue();
            if (pool.getAllocatedCount() == 0) {
                it.remove();
            }
        }
    }

    public void closeIdle(final long idletime, final TimeUnit tunit) {
        Args.notNull(tunit, "Time unit");
        long time = tunit.toMillis(idletime);
        if (time < 0) {
            time = 0;
        }
        final long deadline = System.currentTimeMillis() - time;
        enumAvailable(new PoolEntryCallback<T, C>() {

            @Override
            public void process(final PoolEntry<T, C> entry) {
                if (entry.getUpdated() <= deadline) {
                    entry.close();
                }
            }

        });
    }

    public void closeExpired() {
        final long now = System.currentTimeMillis();
        enumAvailable(new PoolEntryCallback<T, C>() {

            @Override
            public void process(final PoolEntry<T, C> entry) {
                if (entry.isExpired(now)) {
                    entry.close();
                }
            }

        });
    }

    @Override
    public String toString() {
        final StringBuilder buffer = new StringBuilder();
        buffer.append("[leased: ");
        buffer.append(this.leased);
        buffer.append("][available: ");
        buffer.append(this.available);
        buffer.append("][pending: ");
        buffer.append(this.pending);
        buffer.append("]");
        return buffer.toString();
    }

    class InternalSessionRequestCallback implements SessionRequestCallback {

        @Override
        public void completed(final SessionRequest request) {
            requestCompleted(request);
        }

        @Override
        public void cancelled(final SessionRequest request) {
            requestCancelled(request);
        }

        @Override
        public void failed(final SessionRequest request) {
            requestFailed(request);
        }

        @Override
        public void timeout(final SessionRequest request) {
            requestTimeout(request);
        }

    }

}
