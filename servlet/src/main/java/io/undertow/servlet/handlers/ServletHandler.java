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

package io.undertow.servlet.handlers;

import java.io.IOException;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.UnavailableException;

import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.blocking.BlockingHttpHandler;
import io.undertow.servlet.UndertowServletLogger;
import io.undertow.servlet.api.InstanceHandle;
import io.undertow.servlet.core.ManagedServlet;
import io.undertow.servlet.spec.AsyncContextImpl;
import io.undertow.servlet.spec.HttpServletRequestImpl;
import io.undertow.servlet.spec.HttpServletResponseImpl;

/**
 * The handler that is responsible for invoking the servlet
 * <p/>
 * TODO: do we want to move lifecycle considerations out of this handler?
 *
 * @author Stuart Douglas
 */
public class ServletHandler implements BlockingHttpHandler {

    private final ManagedServlet managedServlet;
    private final boolean asyncSupported;

    private static final AtomicLongFieldUpdater<ServletHandler> unavailableUntilUpdater = AtomicLongFieldUpdater.newUpdater(ServletHandler.class, "unavailableUntil");

    @SuppressWarnings("unused")
    private volatile long unavailableUntil = 0;

    public ServletHandler(final ManagedServlet managedServlet) {
        this.managedServlet = managedServlet;
        this.asyncSupported = managedServlet.getServletInfo().isAsyncSupported();
    }

    @Override
    public void handleBlockingRequest(final HttpServerExchange exchange) throws IOException, ServletException {
        if (managedServlet.isPermanentlyUnavailable()) {
            UndertowServletLogger.REQUEST_LOGGER.debugf("Returning 404 for servlet %s due to permanent unavailability", managedServlet.getServletInfo().getName());
            exchange.setResponseCode(404);
            return;
        }

        long until = unavailableUntil;
        if (until != 0) {
            UndertowServletLogger.REQUEST_LOGGER.debugf("Returning 503 for servlet %s due to temporary unavailability", managedServlet.getServletInfo().getName());
            if (System.currentTimeMillis() < until) {
                exchange.setResponseCode(503);
                return;
            } else {
                unavailableUntilUpdater.compareAndSet(this, until, 0);
            }
        }
        if(!asyncSupported) {
            exchange.putAttachment(AsyncContextImpl.ASYNC_SUPPORTED, false);
        }
        ServletRequest request = exchange.getAttachment(HttpServletRequestImpl.ATTACHMENT_KEY);
        ServletResponse response = exchange.getAttachment(HttpServletResponseImpl.ATTACHMENT_KEY);
        InstanceHandle<? extends Servlet> servlet = null;
        try {
            servlet = managedServlet.getServlet();
            servlet.getInstance().service(request, response);
        } catch (UnavailableException e) {
            if (e.isPermanent()) {
                UndertowServletLogger.REQUEST_LOGGER.stoppingServletDueToPermanentUnavailability(managedServlet.getServletInfo().getName(), e);
                managedServlet.stop();
                managedServlet.setPermanentlyUnavailable(true);
                exchange.setResponseCode(404);
            } else {
                unavailableUntilUpdater.set(this, System.currentTimeMillis() + e.getUnavailableSeconds() * 1000);
                UndertowServletLogger.REQUEST_LOGGER.stoppingServletUntilDueToTemporaryUnavailability(managedServlet.getServletInfo().getName(), new Date(until), e);
                exchange.setResponseCode(503);
            }
        } finally {
            if(servlet != null) {
                servlet.release();
            }
        }
    }

    public ManagedServlet getManagedServlet() {
        return managedServlet;
    }
}
