/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.undertow.server.handlers;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import io.undertow.server.ExchangeCompletionListener;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.WorkerDispatcher;

import static org.xnio.Bits.longBitMask;

/**
 * A handler which limits the maximum number of concurrent requests.  Requests beyond the limit will
 * block until the previous request is complete.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public final class RequestLimitingHandler implements HttpHandler {
    @SuppressWarnings("unused")
    private volatile long state;
    private volatile HttpHandler nextHandler = ResponseCodeHandler.HANDLE_404;

    private static final AtomicLongFieldUpdater<RequestLimitingHandler> stateUpdater = AtomicLongFieldUpdater.newUpdater(RequestLimitingHandler.class, "state");
    private static final AtomicReferenceFieldUpdater<RequestLimitingHandler, HttpHandler> nextHandlerUpdater = AtomicReferenceFieldUpdater.newUpdater(RequestLimitingHandler.class, HttpHandler.class, "nextHandler");

    private static final long MASK_MAX = longBitMask(32, 63);
    private static final long MASK_CURRENT = longBitMask(0, 30);

    private final Queue<QueuedRequest> queue;

    private static final Class<Queue> linkedTransferQueue;

    private final ExchangeCompletionListener COMPLETION_LISTENER = new ExchangeCompletionListener() {

        @Override
        public void exchangeEvent(final HttpServerExchange exchange, final NextListener nextListener) {
            try {
                final QueuedRequest task = queue.poll();
                if (task != null) {
                    WorkerDispatcher.dispatch(exchange, task);
                } else {
                    decrementRequests();
                }
            } finally {
                nextListener.proceed();
            }
        }
    };

    static {
        Class<Queue> q;
        try {
            q = (Class<Queue>) Class.forName("java.util.concurrent.LinkedTransferQueue");
        } catch (ClassNotFoundException e) {
            q = null;
        }
        linkedTransferQueue = q;
    }

    /**
     * Construct a new instance. The maximum number of concurrent requests must be at least one.  The next handler
     * must not be {@code null}.
     *
     * @param maximumConcurrentRequests the maximum concurrent requests
     * @param nextHandler               the next handler
     */
    public RequestLimitingHandler(int maximumConcurrentRequests, HttpHandler nextHandler) {
        if (nextHandler == null) {
            throw new IllegalArgumentException("nextHandler is null");
        }
        if (maximumConcurrentRequests < 1) {
            throw new IllegalArgumentException("Maximum concurrent requests must be at least 1");
        }
        state = (maximumConcurrentRequests & 0xFFFFFFFFL) << 32;
        this.nextHandler = nextHandler;
        Queue<QueuedRequest> queue;
        if (linkedTransferQueue == null) {
            queue = new ConcurrentLinkedQueue<QueuedRequest>();
        } else {
            try {
                queue = linkedTransferQueue.newInstance();
            } catch (Throwable t) {
                queue = new ConcurrentLinkedQueue<QueuedRequest>();
            }
        }
        this.queue = queue;
    }

    public void handleRequest(final HttpServerExchange exchange) {
        exchange.addExchangeCompleteListener(COMPLETION_LISTENER);
        long oldVal, newVal;
        do {
            oldVal = state;
            final long current = oldVal & MASK_CURRENT;
            final long max = (oldVal & MASK_MAX) >> 32L;
            if (current >= max) {
                queue.add(new QueuedRequest(exchange));
                return;
            }
            newVal = oldVal + 1;
        } while (!stateUpdater.compareAndSet(this, oldVal, newVal));
        HttpHandlers.executeHandler(nextHandler, exchange);
    }

    /**
     * Get the maximum concurrent requests.
     *
     * @return the maximum concurrent requests
     */
    public int getMaximumConcurrentRequests() {
        return (int) (state >> 32L);
    }

    /**
     * Set the maximum concurrent requests.  The value must be greater than or equal to one.
     *
     * @param newMax the maximum concurrent requests
     */
    public int setMaximumConcurrentRequests(int newMax) {
        if (newMax < 1) {
            throw new IllegalArgumentException("Maximum concurrent requests must be at least 1");
        }
        long oldVal, newVal;
        int current, oldMax;
        do {
            oldVal = state;
            current = (int) (oldVal & MASK_CURRENT);
            oldMax = (int) ((oldVal & MASK_MAX) >> 32L);
            newVal = current | newMax & 0xFFFFFFFFL << 32L;
        } while (!stateUpdater.compareAndSet(this, oldVal, newVal));
        while (current < newMax) {
            // more space opened up!  Process queue entries for a while
            final QueuedRequest request = queue.poll();
            if (request != null) {
                // now bump up the counter by one; this *could* put us over the max if it changed in the meantime but that's OK
                newVal = stateUpdater.getAndIncrement(this);
                current = (int) (newVal & MASK_CURRENT);
                WorkerDispatcher.dispatch(request.exchange, request);
            }
        }
        return oldMax;
    }

    private void decrementRequests() {
        stateUpdater.decrementAndGet(this);
    }

    /**
     * Get the next handler.  Will not be {@code null}.
     *
     * @return the next handler
     */
    public HttpHandler getNextHandler() {
        return nextHandler;
    }

    /**
     * Set the next handler.  The value must not be {@code null}.
     *
     * @param nextHandler the next handler
     * @return the old next handler
     */
    public HttpHandler setNextHandler(final HttpHandler nextHandler) {
        HttpHandlers.handlerNotNull(nextHandler);
        return nextHandlerUpdater.getAndSet(this, nextHandler);
    }

    private final class QueuedRequest implements Runnable {
        private final HttpServerExchange exchange;

        QueuedRequest(final HttpServerExchange exchange) {
            this.exchange = exchange;
        }

        public void run() {
            HttpHandlers.executeHandler(nextHandler, exchange);
        }
    }

}
