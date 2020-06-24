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
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.server.HttpChannelState.InputState;
import org.eclipse.jetty.server.HttpChannelState.Mode;
import org.eclipse.jetty.server.HttpChannelState.State;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.TimerScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpChannelStateTest
{
    static final InetSocketAddress LOCALADDRESS;

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
    private final Queue<HttpInput.Content> _queue = new LinkedList<>();
    private final AtomicBoolean _needy = new AtomicBoolean();
    private final AtomicBoolean _asyncIO = new AtomicBoolean();

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
            public void push(org.eclipse.jetty.http.MetaData.Request request)
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
            public void needContent(boolean async)
            {
                _needy.set(true);
            }

            @Override
            public void produceContent()
            {
                HttpInput.Content content = _queue.poll();
                if (content == HttpInput.EOF)
                    _state.onEof(false);
                else if (content != null)
                    _state.onContent(content);
            }

            @Override
            public void failContent(Throwable failure)
            {
                failure.printStackTrace();
            }

            @Override
            protected HttpInput newHttpInput(HttpChannelState state)
            {
                return new HttpInput(state)
                {
                    @Override
                    public boolean isAsyncIO()
                    {
                        return _asyncIO.get();
                    }
                };
            }
        };
        _state = _channel.getState();
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
        assertFalse(_state.isAsyncStarted());
        assertFalse(_state.isAsync());
        assertTrue(_state.isInitial());
        assertTrue(_state.isIdle());
        assertFalse(_state.isSuspended());
        assertFalse(_state.isCompleted());
        assertFalse(_state.isResponseCommitted());
        assertFalse(_state.isResponseCompleted());
        assertFalse(_state.isSendError());
        assertFalse(_state.isExpired());
    }


    @Test
    public void testBlockingReadH1Sequence() throws Exception
    {
        // Add some initial content before dispatch
        HttpInput.Content contentIn =  new HttpInput.Content(BufferUtil.toBuffer("Hello"));
        assertFalse(_state.onContent(contentIn));

        // do the dispatch
        assertThat(_state.handling(), is(HttpChannelState.Action.DISPATCH));

        // content should be available
        HttpInput.Content contentOut = _state.nextContent(Mode.BLOCK);
        assertEquals(contentIn, contentOut);

        // Block for content in another thread.
        Exchanger<HttpInput.Content> content = new Exchanger<>();
        new Thread(()->
        {
            try
            {
                content.exchange(null);
                content.exchange(_state.nextContent(Mode.BLOCK), 10, TimeUnit.SECONDS);
            }
            catch (InterruptedException | TimeoutException e)
            {
                e.printStackTrace();
            }
        }).start();
        content.exchange(null);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while(_state.getInputState() == InputState.IDLE && System.nanoTime() < end)
            Thread.sleep(10);
        while(_state.getInputState() == InputState.PRODUCING && System.nanoTime() < end)
            Thread.sleep(10);
        assertThat(_state.getState(), is(State.HANDLING));
        assertThat(_state.getInputState(), is(InputState.BLOCKING));

        // Provide content to unblock
        _queue.add(contentIn);
        assertFalse(_state.onContentProducable());
        contentOut = content.exchange(null, 10, TimeUnit.SECONDS);
        assertEquals(contentIn, contentOut);

        // Provide last content
        HttpInput.Content contentLast =  new HttpInput.Content(BufferUtil.toBuffer("World"));
        _queue.add(contentLast);
        _queue.add(HttpInput.EOF);

        // Get last content
        contentOut = _state.nextContent(Mode.BLOCK);
        assertEquals(contentLast, contentOut);

        // Get EOF
        contentOut = _state.nextContent(Mode.BLOCK);
        assertEquals(HttpInput.EOF, contentOut);

        // Still EOF
        contentOut = _state.nextContent(Mode.BLOCK);
        assertEquals(HttpInput.EOF, contentOut);

        assertThat(_state.unhandle(), is(HttpChannelState.Action.COMPLETE));
    }

    @Test
    public void testBlockingReadH2Sequence() throws Exception
    {
        // Add some initial content before dispatch
        HttpInput.Content contentIn =  new HttpInput.Content(BufferUtil.toBuffer("Hello"));
        assertFalse(_state.onContent(contentIn));

        // Do the dispatch
        assertThat(_state.handling(), is(HttpChannelState.Action.DISPATCH));

        // Content should be available
        HttpInput.Content contentOut = _state.nextContent(Mode.BLOCK);
        assertEquals(contentIn, contentOut);

        // Block for content in another thread.
        Exchanger<HttpInput.Content> content = new Exchanger<>();
        new Thread(()->
        {
            try
            {
                content.exchange(null);
                content.exchange(_state.nextContent(Mode.BLOCK), 10, TimeUnit.SECONDS);
            }
            catch (InterruptedException | TimeoutException e)
            {
                e.printStackTrace();
            }
        }).start();
        content.exchange(null);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while(_state.getInputState() == InputState.IDLE && System.nanoTime() < end)
            Thread.sleep(10);
        while(_state.getInputState() == InputState.PRODUCING && System.nanoTime() < end)
            Thread.sleep(10);
        assertThat(_state.getState(), is(State.HANDLING));
        assertThat(_state.getInputState(), is(InputState.BLOCKING));

        // Provide content to unblock
        assertFalse(_state.onContent(contentIn));
        contentOut = content.exchange(null, 10, TimeUnit.SECONDS);
        assertEquals(contentIn, contentOut);

        // Provide last content
        HttpInput.Content contentLast =  new HttpInput.Content(BufferUtil.toBuffer("World"))
        {
            @Override
            public boolean isLast()
            {
                return true;
            }
        };
        assertFalse(_state.onContent(contentLast));

        // Get last content
        contentOut = _state.nextContent(Mode.BLOCK);
        assertEquals(contentLast, contentOut);

        // Get EOF
        contentOut = _state.nextContent(Mode.BLOCK);
        assertEquals(HttpInput.EOF, contentOut);

        // Still EOF
        contentOut = _state.nextContent(Mode.BLOCK);
        assertEquals(HttpInput.EOF, contentOut);

        assertThat(_state.unhandle(), is(HttpChannelState.Action.COMPLETE));
    }

    @Test
    public void testAsyncReadH1Sequence() throws Exception
    {
        _asyncIO.set(true);

        // Add some initial content
        HttpInput.Content contentIn =  new HttpInput.Content(BufferUtil.toBuffer("Hello"));
        assertFalse(_state.onContent(contentIn));

        // Do the dispatch and start async
        assertThat(_state.handling(), is(HttpChannelState.Action.DISPATCH));
        _state.startAsync(null);

        // set read listener
        _asyncIO.set(true);
        HttpInput.Content contentOut = _state.nextContent(Mode.ASYNC);
        assertEquals(contentIn, contentOut);
        assertFalse(_state.onSetReadListenerReady());

        // do the onDataAvailable call
        assertThat(_state.unhandle(), is(HttpChannelState.Action.READ_CALLBACK));
        contentOut = _state.nextContent(Mode.ASYNC);
        assertEquals(HttpInput.EMPTY, contentOut);

        // Try to consume more content
        contentOut = _state.nextContent(Mode.ASYNC);
        assertNull(contentOut);
        assertTrue(_needy.compareAndSet(true, false));

        // Now we wait for content
        assertThat(_state.unhandle(), is(HttpChannelState.Action.WAIT));

        // Content arrives while waiting
        _queue.add(contentIn);
        assertTrue(_state.onContentProducable());

        // do the handling call
        assertThat(_state.handling(), is(HttpChannelState.Action.READ_CALLBACK));
        contentOut = _state.nextContent(Mode.ASYNC);
        assertEquals(contentIn, contentOut);

        // Try to consume more content
        contentOut = _state.nextContent(Mode.ASYNC);
        assertNull(contentOut);
        assertTrue(_needy.compareAndSet(true, false));

        // More content arrives before we unhandle
        _queue.add(contentIn);
        assertFalse(_state.onContentProducable());

        // unhandle goes directly to read callback
        assertThat(_state.unhandle(), is(HttpChannelState.Action.READ_CALLBACK));
        contentOut = _state.nextContent(Mode.ASYNC);
        assertEquals(contentIn, contentOut);

        // Try to consume more content
        contentOut = _state.nextContent(Mode.ASYNC);
        assertNull(contentOut);
        assertTrue(_needy.compareAndSet(true, false));

        // last content arrives before isReady processing
        HttpInput.Content contentLast =  new HttpInput.Content(BufferUtil.toBuffer("World"));
        _queue.add(contentLast);
        _queue.add(HttpInput.EOF);
        assertFalse(_state.onContentProducable());

        // unhandle goes directly to read callback
        assertThat(_state.unhandle(), is(HttpChannelState.Action.READ_CALLBACK));
        contentOut = _state.nextContent(Mode.ASYNC);
        assertEquals(contentLast, contentOut);

        // We get the Async EOF
        contentOut = _state.nextContent(Mode.ASYNC);
        assertEquals(HttpInput.AEOF, contentOut);
        assertFalse(_state.onEofConsumed());

        // We get the EOF
        _state.onContent(HttpInput.EOF);
        contentOut = _state.nextContent(Mode.POLL);
        assertEquals(HttpInput.EOF, contentOut);
    }

    @Test
    public void testAsyncReadH2Sequence() throws Exception
    {
        // Add some initial content
        HttpInput.Content contentIn =  new HttpInput.Content(BufferUtil.toBuffer("Hello"));
        assertFalse(_state.onContent(contentIn));

        // Do the dispatch and start async
        assertThat(_state.handling(), is(HttpChannelState.Action.DISPATCH));
        _state.startAsync(null);

        // set read listener
        _asyncIO.set(true);
        HttpInput.Content contentOut = _state.nextContent(Mode.ASYNC);
        assertEquals(contentIn, contentOut);
        assertFalse(_state.onSetReadListenerReady());

        // do the onDataAvailable call
        assertThat(_state.unhandle(), is(HttpChannelState.Action.READ_CALLBACK));
        contentOut = _state.nextContent(Mode.ASYNC);
        assertEquals(HttpInput.EMPTY, contentOut);

        // Try to consume more content
        contentOut = _state.nextContent(Mode.ASYNC);
        assertNull(contentOut);
        assertTrue(_needy.compareAndSet(true, false));

        // Now we wait for content
        assertThat(_state.unhandle(), is(HttpChannelState.Action.WAIT));

        // Content arrives while waiting
        assertTrue(_state.onContent(contentIn));

        // do the handling call
        assertThat(_state.handling(), is(HttpChannelState.Action.READ_CALLBACK));
        contentOut = _state.nextContent(Mode.ASYNC);
        assertEquals(contentIn, contentOut);

        // Try to consume more content
        contentOut = _state.nextContent(Mode.ASYNC);
        assertNull(contentOut);
        assertTrue(_needy.compareAndSet(true, false));

        // More content arrives before we unhandle
        assertFalse(_state.onContent(contentIn));

        // unhandle goes directly to read callback
        assertThat(_state.unhandle(), is(HttpChannelState.Action.READ_CALLBACK));
        contentOut = _state.nextContent(Mode.ASYNC);
        assertEquals(contentIn, contentOut);

        // Try to consume more content
        contentOut = _state.nextContent(Mode.ASYNC);
        assertNull(contentOut);
        assertTrue(_needy.compareAndSet(true, false));

        // last content arrives before isReady processing
        HttpInput.Content contentLast =  new HttpInput.Content(BufferUtil.toBuffer("World"))
        {
            @Override
            public boolean isLast()
            {
                return true;
            }
        };
        assertFalse(_state.onContent(contentLast));

        // unhandle goes directly to read callback
        assertThat(_state.unhandle(), is(HttpChannelState.Action.READ_CALLBACK));
        contentOut = _state.nextContent(Mode.ASYNC);
        assertEquals(contentLast, contentOut);

        // We get the Async EOF
        contentOut = _state.nextContent(Mode.ASYNC);
        assertEquals(HttpInput.AEOF, contentOut);
        assertFalse(_state.onEofConsumed());

        // We get the EOF
        _state.onContent(HttpInput.EOF);
        contentOut = _state.nextContent(Mode.POLL);
        assertEquals(HttpInput.EOF, contentOut);
    }
}
