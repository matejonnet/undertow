/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.examples.websockets;

import io.undertow.Undertow;
import io.undertow.examples.UndertowExample;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.resource.ClassPathResourceManager;
import io.undertow.server.session.InMemorySessionManager;
import io.undertow.server.session.Session;
import io.undertow.server.session.SessionAttachmentHandler;
import io.undertow.server.session.SessionCookieConfig;
import io.undertow.server.session.SessionManager;
import io.undertow.util.Headers;
import io.undertow.util.Sessions;
import io.undertow.util.StatusCodes;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import io.undertow.websockets.spi.WebSocketHttpExchange;

import java.util.Deque;
import java.util.Map;

import static io.undertow.Handlers.resource;
import static io.undertow.Handlers.websocket;

/**
 * @author Stuart Douglas
 */
@UndertowExample("Web Sockets With Http Session")
public class WebSocketServerWithSessionSupport {

    public static void main(final String[] args) {

        PathHandler pathHandler = new PathHandler();

        pathHandler.addPrefixPath("/myapp", websocket(new WebSocketConnectionCallback() {

            @Override
            public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
                channel.getReceiveSetter().set(new AbstractReceiveListener() {

                    @Override
                    protected void onFullTextMessage(WebSocketChannel channel, BufferedTextMessage message) {
                        Session session = (Session) exchange.getSession();
                        String responseMessage = "Session data: ";
                        if (session != null) {
                            responseMessage += "" + getSessionData(session);
                        } else {
                            responseMessage += "There is NO active session!";
                        }
                        responseMessage += "; ";
                        responseMessage += "Just received over ws: " + message.getData() + ";";

                        WebSockets.sendText(responseMessage, channel, null);
                    }
                });
                channel.resumeReceives();
            }
        }));

        pathHandler.addPrefixPath("/",
            resource(
                    new ClassPathResourceManager(WebSocketServer.class.getClassLoader(), WebSocketServer.class.getPackage()))
                    .setWelcomeFiles("index-with-session.html")
        );

        pathHandler.addPrefixPath("/addToSession", new HttpHandler() {
            @Override
            public void handleRequest(HttpServerExchange exchange)
                    throws Exception {

                Map<String, Deque<String>> reqParams = exchange
                        .getQueryParameters();

                Deque<String> deque = reqParams.get("attrName");
                Deque<String> dequeVal = reqParams.get("value");

                Session session = Sessions.getOrCreateSession(exchange);
                session.setAttribute(deque.getLast(), dequeVal.getLast());

                exchange.setStatusCode(StatusCodes.TEMPORARY_REDIRECT);
                exchange.getResponseHeaders().put(Headers.LOCATION, "/");
                exchange.getResponseSender().close();
            }
        });
        pathHandler.addPrefixPath("/destroySession", new HttpHandler() {
            public void handleRequest(HttpServerExchange exchange)
                    throws Exception {

                Session session = Sessions.getOrCreateSession(exchange);
                session.invalidate(exchange);

                exchange.setStatusCode(StatusCodes.TEMPORARY_REDIRECT);
                exchange.getResponseHeaders().put(Headers.LOCATION, "/");
                exchange.getResponseSender().close();
            }
        });

        SessionManager sessionManager = new InMemorySessionManager(
                "SESSION_MANAGER");
        SessionCookieConfig sessionConfig = new SessionCookieConfig();
        /*
         * Use the sessionAttachmentHandler to add the sessionManager and
         * sessionCofing to the exchange of every request
         */
        SessionAttachmentHandler sessionAttachmentHandler = new SessionAttachmentHandler(
                sessionManager, sessionConfig);
        // set as next handler your root handler
        sessionAttachmentHandler.setNext(pathHandler);

        System.out
                .println("Open the url and fill the form to add attributes into the session");
        Undertow server = Undertow.builder().addHttpListener(8080, "localhost")
                .setHandler(sessionAttachmentHandler).build();
        server.start();

    }

    private static String getSessionData(Session session) {
        StringBuilder sb = new StringBuilder();

        for (String string : session.getAttributeNames()) {
            sb.append(string + " : " + session.getAttribute(string));
        }
        return sb.toString();
    }
}
