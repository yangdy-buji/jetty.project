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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.Destroyable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p> While this class is-a Runnable, it should never be dispatched in it's own thread. It is a runnable only so that the calling thread can use {@link
 * ContextHandler#handle(Runnable)} to setup classloaders etc. </p>
 */
public class HttpInput extends ServletInputStream implements Runnable
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpInput.class);

    private final byte[] _oneByteBuffer = new byte[1];

    private final HttpChannelState _channelState;
    private final ContentProducer _contentProducer = new ContentProducer();

    private Eof _eof = Eof.NOT_YET;
    private Throwable _error;
    private ReadListener _readListener;
    private long _firstByteTimeStamp = Long.MIN_VALUE;

    public HttpInput(HttpChannelState state)
    {
        _channelState = state;
    }

    /* HttpInput */

    public void recycle()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("recycle");
        _contentProducer.recycle();
        _eof = Eof.NOT_YET;
        _error = null;
        _readListener = null;
        _firstByteTimeStamp = Long.MIN_VALUE;
    }

    /**
     * @return The current Interceptor, or null if none set
     */
    public Interceptor getInterceptor()
    {
        return _contentProducer.getInterceptor();
    }

    /**
     * Set the interceptor.
     *
     * @param interceptor The interceptor to use.
     */
    public void setInterceptor(Interceptor interceptor)
    {
        _contentProducer.setInterceptor(interceptor);
    }

    /**
     * Set the {@link Interceptor}, chaining it to the existing one if
     * an {@link Interceptor} is already set.
     *
     * @param interceptor the next {@link Interceptor} in a chain
     */
    public void addInterceptor(Interceptor interceptor)
    {
        Interceptor currentInterceptor = _contentProducer.getInterceptor();
        if (currentInterceptor == null)
            _contentProducer.setInterceptor(interceptor);
        else
            _contentProducer.setInterceptor(new ChainedInterceptor(currentInterceptor, interceptor));
    }

    public long getContentLength()
    {
        return _contentProducer.getRawContentArrived();
    }

    /**
     * This method should be called to signal that an EOF has been detected before all the expected content arrived.
     * <p>
     * Typically this will result in an EOFException being thrown from a subsequent read rather than a -1 return.
     *
     * @return true if content channel woken for read
     */
    public boolean earlyEOF()
    {
        // TODO investigate why this is only called for HTTP1? What about H2 resets?
        if (LOG.isDebugEnabled())
            LOG.debug("received early EOF");
        _eof = Eof.EARLY_EOF;
        if (isAsync())
            return _channelState.onRawContentAdded();
        unblock();
        return false;
    }

    /**
     * This method should be called to signal that all the expected content arrived.
     *
     * @return true if content channel woken for read
     */
    public boolean eof()
    {
        // TODO why not have isLast on HttpInput.Content and let it flow through
        // interceptors normally, and content can then change HttpChannelState.inputState
        if (LOG.isDebugEnabled())
            LOG.debug("received EOF");
        _eof = Eof.EOF;
        if (isAsync())
            return _channelState.onRawContentAdded();
        unblock();
        return false;
    }

    public boolean consumeAll()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("consume all");
        _contentProducer.consumeTransformedContent(this::failContent, new IOException("Unconsumed content"));
        if (_eof.isEof())
            _eof = Eof.CONSUMED_EOF;

        if (isFinished())
            return !isError();

        _eof = Eof.EARLY_EOF;
        return false;
    }

    public boolean isError()
    {
        return _error != null;
    }

    public boolean isAsync()
    {
        return _readListener != null;
    }

    public boolean onIdleTimeout(Throwable x)
    {
        boolean neverDispatched = _channelState.isIdle();
        boolean waitingForContent = available() == 0 && !_eof.isEof();
        if ((waitingForContent || neverDispatched) && !isError())
        {
            x.addSuppressed(new Throwable("HttpInput idle timeout"));
            _error = x;
            if (isAsync())
                return _channelState.onRawContentAdded();
            unblock();
        }
        return false;
    }

    public boolean failed(Throwable x)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failed " + x);
        if (_error != null && _error != x)
            _error.addSuppressed(x);
        else
            _error = x;

        if (isAsync())
            return _channelState.onRawContentAdded();
        unblock();
        return false;
    }

    /* ServletInputStream */

    @Override
    public boolean isFinished()
    {
        boolean finished = !_contentProducer.hasRawContent() && _eof.isConsumed();
        if (LOG.isDebugEnabled())
            LOG.debug("isFinished? {}", finished);
        return finished;
    }

    @Override
    public boolean isReady()
    {
        if (_eof.isEof())
            return true;
        Content content = _contentProducer.nextNonEmptyContent(HttpChannelState.Mode.ASYNC);
        if (LOG.isDebugEnabled())
            LOG.debug("isReady? {}", content);
        return content != null;
    }

    @Override
    public void setReadListener(ReadListener readListener)
    {
        if (_readListener != null)
            throw new IllegalStateException("ReadListener already set");
        _readListener = Objects.requireNonNull(readListener);
        //illegal if async not started
        if (!_channelState.isAsyncStarted())
            throw new IllegalStateException("Async not started");

        if (LOG.isDebugEnabled())
            LOG.debug("setReadListener error=" + _error + " eof=" + _eof + " " + _contentProducer);
        boolean woken;
        if (isError())
        {
            woken = _channelState.onReadReady();
        }
        else
        {
            woken = _eof.isEof()
                ? _channelState.onReadEof()
                : isReady();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("setReadListener woken=" + woken);
        if (woken)
            scheduleReadListenerNotification();
    }

    private void scheduleReadListenerNotification()
    {
        HttpChannel channel = _channelState.getHttpChannel();
        channel.execute(channel);
    }

    @Override
    public int read() throws IOException
    {
        int read = read(_oneByteBuffer, 0, 1);
        if (read == 0)
            throw new IOException("unready read=0");
        return read < 0 ? -1 : _oneByteBuffer[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        boolean async = isAsync();
        if (LOG.isDebugEnabled())
            LOG.debug("read async = {}", async);

        // Calculate minimum request rate for DOS protection
        long minRequestDataRate = _channelState.getHttpChannel().getHttpConfiguration().getMinRequestDataRate();
        if (minRequestDataRate > 0 && _firstByteTimeStamp != Long.MIN_VALUE)
        {
            long period = System.nanoTime() - _firstByteTimeStamp;
            if (period > 0)
            {
                long minimumData = minRequestDataRate * TimeUnit.NANOSECONDS.toMillis(period) / TimeUnit.SECONDS.toMillis(1);
                if (_contentProducer.getRawContentArrived() < minimumData)
                {
                    BadMessageException bad = new BadMessageException(HttpStatus.REQUEST_TIMEOUT_408,
                        String.format("Request content data rate < %d B/s", minRequestDataRate));
                    if (_channelState.isResponseCommitted())
                        _channelState.getHttpChannel().abort(bad);
                    throw bad;
                }
            }
        }

        Content content = _contentProducer.nextNonEmptyContent(async? HttpChannelState.Mode.ASYNC: HttpChannelState.Mode.BLOCK);
        if (LOG.isDebugEnabled())
            LOG.debug("read content {}", content);
        if (content != null)
            return content.get(b, off, len);

        if (LOG.isDebugEnabled())
            LOG.debug("read error = " + _error);
        if (_error != null)
            throw new IOException(_error);

        if (LOG.isDebugEnabled())
            LOG.debug("read EOF = {}", _eof);
        if (_eof.isEarly())
            throw new EofException("Early EOF");

        if (_eof.isEof())
        {
            _eof = Eof.CONSUMED_EOF;
            boolean wasInAsyncWait = isAsync() && _channelState.onReadEof();
            if (LOG.isDebugEnabled())
                LOG.debug("read on EOF. async={} wasWait={}", async, wasInAsyncWait);
            if (wasInAsyncWait)
                scheduleReadListenerNotification();
            return -1;
        }

        // TODO better handling here???
        if (async)
            return 0;
        throw new IllegalStateException();
    }

    @Override
    public int available()
    {
        Content content = _contentProducer.nextNonEmptyContent(HttpChannelState.Mode.POLL);
        if (LOG.isDebugEnabled())
            LOG.debug("available = {}", content);
        return content == null ? 0 : content.remaining();
    }

    /* Runnable */

    /*
     * <p> While this class is-a Runnable, it should never be dispatched in it's own thread. It is a runnable only so that the calling thread can use {@link
     * ContextHandler#handle(Runnable)} to setup classloaders etc. </p>
     */
    @Override
    public void run()
    {
        if (!_contentProducer.hasRawContent())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("running has no raw content; error: {}, EOF = {}", _error, _eof);
            if (_error != null || _eof.isEarly())
            {
                // TODO is this necessary to add here?
                _channelState.getHttpChannel().getResponse().getHttpFields().add(HttpConnection.CONNECTION_CLOSE);
                if (_error != null)
                    _readListener.onError(_error);
                else
                    _readListener.onError(new EofException("Early EOF"));
            }
            else if (_eof.isEof())
            {
                try
                {
                    _readListener.onAllDataRead();
                }
                catch (Throwable x)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("running failed onAllDataRead", x);
                    _readListener.onError(x);
                }
            }
            // else: !hasContent() && !error && !EOF -> no-op
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("running has raw content");
            try
            {
                _readListener.onDataAvailable();
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("running failed onDataAvailable", x);
                _readListener.onError(x);
            }
        }
    }

    private void failContent(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failContent {} - " + failure, _contentProducer);
        _channelState.getHttpChannel().failContent(failure);
    }

    private enum Eof
    {
        NOT_YET(false, false, false),
        EOF(true, false, false),
        CONSUMED_EOF(true, true, false),
        EARLY_EOF(true, false, true),
        ;

        private final boolean _eof;
        private final boolean _consumed;
        private final boolean _early;

        Eof(boolean eof, boolean consumed, boolean early)
        {
            _eof = eof;
            _consumed = consumed;
            _early = early;
        }

        boolean isEof()
        {
            return _eof;
        }

        boolean isConsumed()
        {
            return _consumed;
        }

        boolean isEarly()
        {
            return _early;
        }
    }

    // All methods of this class have to be synchronized because a HTTP2 reset can call consumeTransformedContent()
    // while nextNonEmptyContent() is executing, hence all accesses to _rawContent and _transformedContent must be
    // mutually excluded.
    // TODO: maybe the locking could be more fine grained, by only protecting the if (null|!null) blocks?
    private class ContentProducer
    {
        // Note: _rawContent can never be null for as long as _transformedContent is not null.
        private Content _rawContent;
        private Content _transformedContent;
        private long _rawContentArrived;
        private Interceptor _interceptor;
        private Throwable _consumeFailure;

        void recycle()
        {
            synchronized (this)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("recycle {}", this);
                if (_transformedContent == _rawContent)
                    _transformedContent = null;
                if (_transformedContent != null)
                    _transformedContent.failed(null);
                _transformedContent = null;
                if (_rawContent != null)
                    _rawContent.failed(null);
                _rawContent = null;
                _rawContentArrived = 0L;
                if (_interceptor instanceof Destroyable)
                    ((Destroyable)_interceptor).destroy();
                _interceptor = null;
                _consumeFailure = null;
            }
        }

        long getRawContentArrived()
        {
            synchronized (this)
            {
                return _rawContentArrived;
            }
        }

        boolean hasRawContent()
        {
            synchronized (this)
            {
                return _rawContent != null;
            }
        }

        Interceptor getInterceptor()
        {
            synchronized (this)
            {
                return _interceptor;
            }
        }

        void setInterceptor(Interceptor interceptor)
        {
            synchronized (this)
            {
                this._interceptor = interceptor;
            }
        }

        void addRawContent(Content content)
        {
            synchronized (this)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} addContent {}", this, content);
                if (content == null)
                    throw new AssertionError("Cannot add null content");
                if (_consumeFailure != null)
                {
                    content.failed(_consumeFailure);
                    return;
                }
                if (_rawContent != null)
                    throw new AssertionError("Cannot add new content while current one hasn't been processed");

                _rawContent = content;
                _rawContentArrived += content.remaining();
            }
        }

        void consumeTransformedContent(Consumer<Throwable> failRawContent, Throwable failure)
        {
            synchronized (this)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} consumeTransformedContent", this);
                // start by depleting the current _transformedContent
                if (_transformedContent != null)
                {
                    _transformedContent.skip(_transformedContent.remaining());
                    if (_transformedContent != _rawContent)
                        _transformedContent.failed(failure);
                    _transformedContent = null;
                }

                // don't bother transforming content, directly deplete the raw one
                if (_rawContent != null)
                {
                    _rawContent.skip(_rawContent.remaining());
                    _rawContent.failed(failure);
                    _rawContent = null;
                }

                // fail whatever other content the producer may have
                _consumeFailure = failure;
                failRawContent.accept(failure);
            }
        }

        private Content nextNonEmptyContent(HttpChannelState.Mode mode)
        {
            while (true)
            {
                // Use any unconsumed transformed content
                if (_transformedContent != null)
                {
                    if (_transformedContent.hasContent())
                        return _transformedContent;

                    if (_transformedContent.isEof())
                    {
                        if (_rawContent != null)
                        {
                            if (_transformedContent != _rawContent)
                                _transformedContent.succeeded();
                            _rawContent.succeeded();
                            _rawContent = null;
                        }
                        return _transformedContent;
                    }

                    if (_transformedContent != _rawContent)
                        _transformedContent.succeeded();
                    _transformedContent = null;
                }

                // Use any unconsumed raw content
                if (_rawContent != null)
                {
                    if (_rawContent.hasContent())
                    {
                        _transformedContent = _interceptor == null ? _rawContent : _interceptor.readFrom(_rawContent);
                        continue;
                    }

                    _rawContent.succeeded();
                }

                _rawContent = _channelState.nextContent(mode);
            }
        }

        @Override
        public String toString()
        {
            return getClass().getSimpleName() + "[i=" + _interceptor + ",b=" + _rawContentArrived +
                ",r=" + _rawContent + ",t=" + _transformedContent + "]";
        }
    }

    /**
     * An {@link Interceptor} that chains two other {@link Interceptor}s together.
     * The {@link Interceptor#readFrom(Content)} calls the previous {@link Interceptor}'s
     * {@link Interceptor#readFrom(Content)} and then passes any {@link Content} returned
     * to the next {@link Interceptor}.
     */
    private static class ChainedInterceptor implements Interceptor, Destroyable
    {
        private final Interceptor _prev;
        private final Interceptor _next;

        ChainedInterceptor(Interceptor prev, Interceptor next)
        {
            _prev = prev;
            _next = next;
        }

        Interceptor getPrev()
        {
            return _prev;
        }

        Interceptor getNext()
        {
            return _next;
        }

        @Override
        public Content readFrom(Content content)
        {
            Content c = getPrev().readFrom(content);
            if (c == null)
                return null;
            return getNext().readFrom(c);
        }

        @Override
        public void destroy()
        {
            if (_prev instanceof Destroyable)
                ((Destroyable)_prev).destroy();
            if (_next instanceof Destroyable)
                ((Destroyable)_next).destroy();
        }
    }

    public interface Interceptor
    {
        /**
         * @param content The content to be intercepted.
         * The content will be modified with any data the interceptor consumes, but there is no requirement
         * that all the data is consumed by the interceptor.
         * @return The intercepted content or null if interception is completed for that content.
         */
        Content readFrom(Content content);
    }

    public static class Content implements Callback
    {
        protected final ByteBuffer _content;

        public Content(ByteBuffer content)
        {
            _content = content;
        }

        public boolean isEof()
        {
            return false;
        }

        public boolean isEarlyEof()
        {
            return false;
        }

        public ByteBuffer getByteBuffer()
        {
            return _content;
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        public int get(byte[] buffer, int offset, int length)
        {
            length = Math.min(_content.remaining(), length);
            _content.get(buffer, offset, length);
            return length;
        }

        public int skip(int length)
        {
            length = Math.min(_content.remaining(), length);
            _content.position(_content.position() + length);
            return length;
        }

        public boolean hasContent()
        {
            return _content.hasRemaining();
        }

        public int remaining()
        {
            return _content.remaining();
        }

        public boolean isEmpty()
        {
            return !_content.hasRemaining();
        }

        @Override
        public String toString()
        {
            return String.format("Content@%x{%s}", hashCode(), BufferUtil.toDetailString(_content));
        }
    }

}
