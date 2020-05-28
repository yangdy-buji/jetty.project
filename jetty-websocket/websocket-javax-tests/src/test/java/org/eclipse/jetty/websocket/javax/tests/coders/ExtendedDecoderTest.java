//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.tests.coders;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import javax.websocket.Decoder;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.javax.client.internal.JavaxWebSocketClientContainer;
import org.eclipse.jetty.websocket.javax.common.decoders.AbstractDecoder;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.javax.tests.EventSocket;
import org.eclipse.jetty.websocket.javax.tests.WSURI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ExtendedDecoderTest
{
    private Server server;
    private URI serverUri;
    private JavaxWebSocketClientContainer client;

    @BeforeEach
    public void before() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);
        JavaxWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
            container.addEndpoint(DecoderServerEndpoint.class));
        server.start();
        serverUri = WSURI.toWebsocket(server.getURI());

        client = new JavaxWebSocketClientContainer();
        client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        server.stop();
        client.stop();
    }

    @Test
    public void test() throws Exception
    {
        EventSocket clientEndpoint = new EventSocket();
        Session session = client.connectToServer(clientEndpoint, serverUri);
        String message = "test";
        session.getBasicRemote().sendText(message);
        String response = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(response, is(message + StringWrapperExtension.suffix));
    }

    @ServerEndpoint(value = "/", decoders = StringWrapperDecoder.class)
    public static class DecoderServerEndpoint
    {
        @OnMessage
        public String onMessage(StringWrapper message)
        {
            return message.getString();
        }
    }

    public static class StringWrapperDecoder extends AbstractDecoder implements Decoder.Text<StringWrapperExtension>
    {
        @Override
        public StringWrapperExtension decode(String s)
        {
            return new StringWrapperExtension(s);
        }

        @Override
        public boolean willDecode(String s)
        {
            return true;
        }
    }

    public static class StringWrapper
    {
        private final String _string;

        public StringWrapper(String s)
        {
            _string = s;
        }

        public String getString()
        {
            return _string;
        }
    }

    public static class StringWrapperExtension extends StringWrapper
    {
        public static final String suffix = ":Extension";

        public StringWrapperExtension(String s)
        {
            super(s);
        }

        @Override
        public String getString()
        {
            return super.getString() + suffix;
        }
    }
}