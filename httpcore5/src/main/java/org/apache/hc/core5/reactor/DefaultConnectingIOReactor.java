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

package org.apache.hc.core5.reactor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadFactory;

import org.apache.hc.core5.concurrent.DefaultThreadFactory;
import org.apache.hc.core5.function.Callback;
import org.apache.hc.core5.function.Decorator;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.TimeValue;

/**
 * Multi-core I/O reactor that can act as {@link ConnectionInitiator} Internally
 * this I/O reactor distributes newly created I/O session equally across multiple
 * I/O worker threads for a more optimal resource utilization and a better
 * I/O performance. Usually it is recommended to have one worker I/O reactor
 * per physical CPU core.
 *
 * @since 4.0
 */
public class DefaultConnectingIOReactor extends AbstractIOReactorBase implements IOReactorService {

    private final Deque<ExceptionEvent> auditLog;
    private final int workerCount;
    private final SingleCoreIOReactor[] workers;
    private final MultiCoreIOReactor ioReactor;
    private final IOWorkers.Selector workerSelector;

    private final static ThreadFactory THREAD_FACTORY = new DefaultThreadFactory("I/O client dispatch", true);

    public DefaultConnectingIOReactor(
            final IOEventHandlerFactory eventHandlerFactory,
            final IOReactorConfig ioReactorConfig,
            final ThreadFactory threadFactory,
            final Decorator<IOSession> ioSessionDecorator,
            final IOSessionListener sessionListener,
            final Callback<IOSession> sessionShutdownCallback) {
        Args.notNull(eventHandlerFactory, "Event handler factory");
        this.auditLog = new ConcurrentLinkedDeque<>();
        this.workerCount = ioReactorConfig != null ? ioReactorConfig.getIoThreadCount() : IOReactorConfig.DEFAULT.getIoThreadCount();
        this.workers = new SingleCoreIOReactor[workerCount];
        final Thread[] threads = new Thread[workerCount];
        for (int i = 0; i < this.workers.length; i++) {
            final SingleCoreIOReactor dispatcher = new SingleCoreIOReactor(
                    auditLog,
                    eventHandlerFactory,
                    ioReactorConfig != null ? ioReactorConfig : IOReactorConfig.DEFAULT,
                    ioSessionDecorator,
                    sessionListener,
                    sessionShutdownCallback);
            this.workers[i] = dispatcher;
            threads[i] = (threadFactory != null ? threadFactory : THREAD_FACTORY).newThread(new IOReactorWorker(dispatcher));
        }
        this.ioReactor = new MultiCoreIOReactor(this.workers, threads);
        this.workerSelector =  IOWorkers.newSelector(workers);
    }

    public DefaultConnectingIOReactor(
            final IOEventHandlerFactory eventHandlerFactory,
            final IOReactorConfig config,
            final Callback<IOSession> sessionShutdownCallback) {
        this(eventHandlerFactory, config, null, null, null, sessionShutdownCallback);
    }

    /**
     * Creates an instance of DefaultConnectingIOReactor with default configuration.
     *
     * @since 5.0
     */
    public DefaultConnectingIOReactor(final IOEventHandlerFactory eventHandlerFactory) {
        this(eventHandlerFactory, null, null);
    }

    @Override
    public void start() {
        ioReactor.start();
    }

    @Override
    public IOReactorStatus getStatus() {
        return ioReactor.getStatus();
    }

    @Override
    IOWorkers.Selector getWorkerSelector() {
        return workerSelector;
    }

    @Override
    public List<ExceptionEvent> getExceptionLog() {
        return auditLog.isEmpty() ? Collections.<ExceptionEvent>emptyList() : new ArrayList<>(auditLog);
    }

    @Override
    public void initiateShutdown() {
        ioReactor.initiateShutdown();
    }

    @Override
    public void awaitShutdown(final TimeValue waitTime) throws InterruptedException {
        ioReactor.awaitShutdown(waitTime);
    }

    @Override
    public void close(final CloseMode closeMode) {
        ioReactor.close(closeMode);
    }

    @Override
    public void close() throws IOException {
        ioReactor.close();
    }

}
