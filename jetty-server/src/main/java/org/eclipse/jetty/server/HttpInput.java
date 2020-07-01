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
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.HttpChannelState.Mode;
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

    static final Content EMPTY = new Content(BufferUtil.EMPTY_BUFFER)
    {
        @Override
        public String toString()
        {
            return "EMPTY";
        }
    };
    static final EofContent AEOF = new EofContent("ASYNC EOF");
    static final EofContent EOF = new EofContent("EOF");

    private final byte[] _oneByteBuffer = new byte[1];

    private final HttpChannelState _channelState;
    private final ContentProducer _contentProducer = new ContentProducer();

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

    public boolean consumeAll()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("consume all");
        return _contentProducer.consumeAll();
    }

    public boolean isAsyncIO()
    {
        return _readListener != null;
    }

    public boolean onIdleTimeout(Throwable x)
    {
        /* TODO
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

         */
        return false;
    }

    public boolean onContentError(Throwable x)
    {
        if (LOG.isDebugEnabled())
        {
            x.addSuppressed(new Throwable());
            LOG.debug("onContentError", x);
        }
        return _channelState.onContent(new ErrorContent(x));
    }

    /* ServletInputStream */

    @Override
    public boolean isFinished()
    {
        try
        {
            Content content = _contentProducer.nextNonEmptyContent(Mode.POLL);
            boolean finished = content != null && content.isEmpty() && content.isLast();
            if (LOG.isDebugEnabled())
                LOG.debug("isFinished {} c={} {}", finished, content, this);
            return finished;
        }
        catch (IOException e)
        {
            return true;
        }
    }

    @Override
    public boolean isReady()
    {
        try
        {
            Content content = _contentProducer.nextNonEmptyContent(Mode.ASYNC);
            if (LOG.isDebugEnabled())
                LOG.debug("isReady? {} {}", content, this);
            return content != null;
        }
        catch (IOException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("isReady", e);
            return true;
        }
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
            LOG.debug("setReadListener l={} {}", readListener, this);

        if (_channelState.onSetReadListener())
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
        boolean async = isAsyncIO();
        if (LOG.isDebugEnabled())
            LOG.debug("read({},{},{}){}", Long.toHexString(b.hashCode()), off, len, async ? " async" : "");

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

        Content content = _contentProducer.nextNonEmptyContent(async ? Mode.ASYNC : Mode.BLOCK);
        if (LOG.isDebugEnabled())
            LOG.debug("read c={}", content);
        if (content != null)
        {
            len = content.get(b, off, len);
            if (len < 0 && _channelState.onEofConsumed())
                scheduleReadListenerNotification();
            return len;
        }

        if (async)
            return 0;
        throw new IllegalStateException();
    }

    @Override
    public int available()
    {
        try
        {
            Content content = _contentProducer.nextNonEmptyContent(Mode.POLL);
            if (LOG.isDebugEnabled())
                LOG.debug("available = {}", content);
            return content == null ? 0 : content.remaining();
        }
        catch (IOException e)
        {
            LOG.debug("!available", e);
            return 0;
        }
    }

    /*
     * <p> This class is-a Runnable, but it should never be dispatched in it's own thread. It is a runnable only so that the calling thread can use {@link
     * ContextHandler#handle(Runnable)} to setup classloaders etc. </p>
     */
    @Override
    public void run()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("run {}", this);
        Content content;
        Throwable error;
        try
        {
            content = _contentProducer.nextNonEmptyContent(Mode.ASYNC);
            error = (content instanceof ErrorContent) ? ((ErrorContent)content)._error : null;
        }
        catch (IOException e)
        {
            content = new ErrorContent(e);
            _channelState.onContent(content);
            error = e;
        }

        if (content != null && error == null)
        {
            try
            {
                if (content.hasContent())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("onDataAvailable {} {} ", content, this);
                    _readListener.onDataAvailable();
                    content = _contentProducer.nextNonEmptyContent(Mode.POLL);
                    error = (content instanceof ErrorContent) ? ((ErrorContent)content)._error : null;
                }
                else if (content.isLast() && content == AEOF)
                {
                    _channelState.onContent(EOF);
                    if (LOG.isDebugEnabled())
                        LOG.debug("onAllDataRead {}", this);
                    _readListener.onAllDataRead();
                }
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("running ", x);
                error = x;
            }
        }

        if (error != null)
            _readListener.onError(error);
    }

    @Override
    public String toString()
    {
        return String.format("%s{%s}", super.toString(), _channelState);
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
            }
        }

        long getRawContentArrived()
        {
            synchronized (this)
            {
                return _rawContentArrived;
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

        boolean consumeAll()
        {
            synchronized (this)
            {
                try
                {
                    while (true)
                    {
                        Content content = nextNonEmptyContent(Mode.POLL);
                        if (LOG.isDebugEnabled())
                            LOG.debug("{} consumeAll {}", this, content);
                        if (content == null)
                            return false;
                        if (content.hasContent())
                            content.skip(content.remaining());
                        else if (content.isLast())
                            return !(content instanceof ErrorContent);
                    }
                }
                catch (IOException e)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("!ConsumedAll", e);
                    return false;
                }
            }
        }

        private Content nextNonEmptyContent(Mode mode) throws IOException
        {
            while (true)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("nextNonEmptyContent({}) rc={} tc={} {}", mode, _rawContent, _transformedContent, _channelState);

                try
                {
                    // Use any unconsumed transformed content
                    if (_transformedContent != null)
                    {
                        if (_transformedContent.hasContent())
                            return _transformedContent;

                        if (_transformedContent != _rawContent)
                            _transformedContent.succeeded();

                        if (_transformedContent.isLast())
                        {
                            // Always fetch fresh EOF to see any change in sentinel
                            _rawContent.succeeded();
                            _rawContent = _transformedContent = _channelState.nextContent(Mode.POLL);
                            return _transformedContent;
                        }

                        _transformedContent = null;
                    }

                    // Use any unconsumed raw content
                    if (_rawContent != null)
                    {
                        if (_rawContent.hasContent() || _rawContent.isLast())
                        {
                            _transformedContent = _interceptor == null ? _rawContent : _interceptor.readFrom(_rawContent);
                            continue;
                        }

                        _rawContent.succeeded();
                    }

                    _rawContent = _channelState.nextContent(mode);

                    if (_rawContent == null)
                    {
                        switch (mode)
                        {
                            case POLL:
                            case ASYNC:
                                return null;

                            default:
                                break;
                        }
                    }
                }
                catch (InterruptedException e)
                {
                    throw new InterruptedIOException()
                    {
                        {
                            initCause(e);
                        }
                    };
                }
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
        public Content readFrom(Content content) throws IOException
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
        Content readFrom(Content content) throws IOException;
    }

    public static class Content implements Callback
    {
        protected final ByteBuffer _content;

        public Content(ByteBuffer content)
        {
            Objects.requireNonNull(content);
            _content = content;
        }

        public Content(String content)
        {
            _content = BufferUtil.toBuffer(content);
        }

        public boolean isLast()
        {
            return false;
        }

        public Throwable getError()
        {
            return null;
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

        public int get(byte[] buffer, int offset, int length) throws IOException
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
            return String.format("%s@%x{%s}", getClass().getSimpleName(), hashCode(), BufferUtil.toDetailString(_content));
        }
    }

    public static class LastContent extends Content
    {
        final Content _delegate;

        public LastContent(Content content)
        {
            super(content._content);
            _delegate = content;
        }

        public LastContent(ByteBuffer content)
        {
            super(content);
            _delegate = null;
        }

        @Override
        public boolean isLast()
        {
            return true;
        }

        @Override
        public void succeeded()
        {
            if (_delegate != null)
                _delegate.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            if (_delegate != null)
                _delegate.failed(x);
        }

        @Override
        public int get(byte[] buffer, int offset, int length) throws IOException
        {
            int len = super.get(buffer, offset, length);
            return len > 0 ? len : -1;
        }
    }

    static class EofContent extends Content
    {
        private final String _name;

        EofContent(String name)
        {
            super(BufferUtil.EMPTY_BUFFER);
            _name = name;
        }

        @Override
        public boolean isLast()
        {
            return true;
        }

        @Override
        public int get(byte[] buffer, int offset, int length)
        {
            return -1;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x", _name, hashCode());
        }
    }

    public static class ErrorContent extends Content
    {
        final Throwable _error;

        public ErrorContent(Throwable error)
        {
            super(BufferUtil.EMPTY_BUFFER);
            _error = error;
        }

        @Override
        public boolean isLast()
        {
            return true;
        }

        @Override
        public Throwable getError()
        {
            return _error;
        }

        @Override
        public int get(byte[] buffer, int offset, int length) throws IOException
        {
            if (_error instanceof IOException)
                throw (IOException)_error;
            throw new IOException(_error);
        }

        @Override
        public String toString()
        {
            return String.format("%s{%s}", super.toString(), _error);
        }
    }

    /*
     * Early EOF exception.  Don't make a static instance of this as the stack trace
     * may contain useful info
     */
    static class EarlyEofErrorContent extends ErrorContent
    {
        EarlyEofErrorContent()
        {
            super(new EofException("Early EOF"));
        }
    }
}
