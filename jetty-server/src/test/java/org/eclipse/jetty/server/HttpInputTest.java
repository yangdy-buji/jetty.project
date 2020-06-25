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

package org.eclipse.jetty.server;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.TimerScheduler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class HttpInputTest
{
    static final InetSocketAddress LOCALADDRESS;
    public static final HttpInput.EofContent EARLY = new HttpInput.EofContent("EARLY");
    public static final HttpInput.Content CONTENT_EOF = new HttpInput.Content("World");

    static
    {
        InetAddress ip = null;
        try
        {
            ip = Inet4Address.getByName("127.0.0.42");
        }
        catch (UnknownHostException e)
        {
            e.printStackTrace();
        }
        finally
        {
            LOCALADDRESS = new InetSocketAddress(ip, 8888);
        }
    }

    private Server _server;
    private HttpChannel _channel;
    private HttpChannelState _state;
    private HttpInput _in;
    private final Queue<HttpInput.Content> _queue = new LinkedList<>();
    private final AtomicBoolean _needy = new AtomicBoolean();

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();
        Scheduler scheduler = new TimerScheduler();
        HttpConfiguration config = new HttpConfiguration();
        LocalConnector connector = new LocalConnector(_server, null, scheduler, null, 1, new HttpConnectionFactory(config));
        _server.addConnector(connector);
        _server.setHandler(new DumpHandler());
        // _server.start();

        AbstractEndPoint endp = new ByteArrayEndPoint(scheduler, 0)
        {
            @Override
            public InetSocketAddress getLocalAddress()
            {
                return LOCALADDRESS;
            }
        };

        _channel = new HttpChannel(connector, new HttpConfiguration(), endp, new HttpTransport()
        {
            private Throwable _channelError;

            @Override
            public void send(MetaData.Request request, MetaData.Response response, ByteBuffer content, boolean lastContent, Callback callback)
            {
            }

            @Override
            public boolean isPushSupported()
            {
                return false;
            }

            @Override
            public void push(MetaData.Request request)
            {
            }

            @Override
            public void onCompleted()
            {
            }

            @Override
            public void abort(Throwable failure)
            {
                _channelError = failure;
            }
        })
        {
            @Override
            public void needContent()
            {
                _needy.set(true);
            }

            @Override
            public void produceContent()
            {
                HttpInput.Content content = _queue.poll();
                if (content == HttpInput.EOF)
                    _state.onEof(false);
                else if (content == EARLY)
                    _state.onEof(true);
                else if (content == CONTENT_EOF)
                {
                    _state.onContent(content);
                    _state.onEof(false);
                }
                else if (content != null)
                    _state.onContent(content);
            }

            @Override
            public void failContent(Throwable failure)
            {
                failure.printStackTrace();
            }
        };
        _state = _channel.getState();
        _in = _channel.getRequest().getHttpInput();
        _queue.clear();
        _needy.set(false);
    }

    @AfterEach
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testInit() throws Exception
    {
        assertFalse(_in.isAsyncIO());
    }

    @Test
    public void testSimpleContent() throws Exception
    {
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer("Hello")));
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer(" ")));
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer("World")));
        _queue.add(HttpInput.EOF);

        assertThat(_in.available(), Matchers.greaterThan(0));
        String out = IO.toString(_in);
        assertThat(out, is("Hello World"));
    }

    @Test
    public void testLastContent() throws Exception
    {
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer("Hello")));
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer(" ")));
        _queue.add(new HttpInput.LastContent(BufferUtil.toBuffer("World")));

        assertThat(_in.available(), Matchers.greaterThan(0));
        String out = IO.toString(_in);
        assertThat(out, is("Hello World"));
    }

    @Test
    public void testEofWithContent() throws Exception
    {
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer("Hello")));
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer(" ")));
        _queue.add(CONTENT_EOF);
        assertThat(_in.available(), Matchers.greaterThan(0));
        String out = IO.toString(_in);
        assertThat(out, is("Hello World"));
    }

    @Test
    public void testEarlyEof() throws Exception
    {
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer("Hello")));
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer(" ")));
        _queue.add(EARLY);

        assertThat(_in.available(), Matchers.greaterThan(0));
        Assertions.assertThrows(EofException.class, ()->IO.toString(_in));
    }

    @Test
    public void testSimpleInterceptor() throws Exception
    {
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer("Hello")));
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer(" ")));
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer("World")));
        _queue.add(HttpInput.EOF);

        _in.addInterceptor(content ->
        {
            if (content.isEmpty() && content.isEmpty())
                return content;
            byte[] buffer = new byte[1024];
            int l = content.get(buffer, 0, buffer.length);
            for (int i = 0; i < l; i++)
                buffer[i] = (byte)Character.toUpperCase((char)buffer[i]);
            return new HttpInput.Content(BufferUtil.toBuffer(buffer, 0, l));
        });

        assertThat(_in.available(), Matchers.greaterThan(0));
        String out = IO.toString(_in);
        assertThat(out, is("HELLO WORLD"));
    }

    @Test
    public void testConsumingInterceptor() throws Exception
    {
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer("HeXllo")));
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer("XXX")));
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer(" ")));
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer("NULL")));
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer("X")));
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer("XWorld")));
        _queue.add(HttpInput.EOF);

        _in.addInterceptor(content ->
        {
            if (content.isEmpty() && content.isEmpty())
                return content;
            byte[] buffer = new byte[1024];
            int l = content.get(buffer, 0, buffer.length);

            String c = new String(buffer, 0, l, StandardCharsets.ISO_8859_1);
            if ("NULL".equals(c))
                return null;
            return new HttpInput.Content(BufferUtil.toBuffer(c.replaceAll("X", "")));
        });

        assertThat(_in.available(), Matchers.greaterThan(0));
        String out = IO.toString(_in);
        assertThat(out, is("Hello World"));
    }

    @Test
    public void testByteInterceptor() throws Exception
    {
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer("Hello")));
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer(" ")));
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer("World")));
        _queue.add(HttpInput.EOF);

        _in.addInterceptor(content ->
        {
            if (content.isEmpty() && content.isEmpty())
                return content;
            byte[] buffer = new byte[1];
            int l = content.get(buffer, 0, buffer.length);
            return new HttpInput.Content(BufferUtil.toBuffer(buffer, 0, l));
        });

        assertThat(_in.available(), Matchers.greaterThan(0));
        String out = IO.toString(_in);
        assertThat(out, is("Hello World"));
    }

    @Test
    public void testEofDelayInterceptor() throws Exception
    {
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer("Hello")));
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer(" ")));
        _queue.add(new HttpInput.Content(BufferUtil.toBuffer("World")));
        _queue.add(HttpInput.EOF);

        AtomicReference<HttpInput.Content> delayed = new AtomicReference<>();

        _in.addInterceptor(content ->
        {
            if (content.isEmpty() && content.isEmpty())
            {
                if (delayed.compareAndSet(null, content))
                    return null;
                return delayed.get();
            }
            byte[] buffer = new byte[1];
            int l = content.get(buffer, 0, buffer.length);
            return new HttpInput.Content(BufferUtil.toBuffer(buffer, 0, l));
        });

        assertThat(_in.available(), Matchers.greaterThan(0));
        String out = IO.toString(_in);
        assertThat(out, is("Hello World"));
    }
}
