/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.net;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

/**
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 */
public abstract class AsyncConnection implements AsynchronousByteChannel, AutoCloseable {

    protected Map<String, Object> attributes; //用于存储绑定在Connection上的对象集合

    protected Object subobject; //用于存储绑定在Connection上的对象， 同attributes， 只绑定单个对象时尽量使用subobject而非attributes

    public abstract boolean isTCP();

    public abstract SocketAddress getRemoteAddress();

    public abstract SocketAddress getLocalAddress();

    public abstract int getReadTimeoutSecond();

    public abstract int getWriteTimeoutSecond();

    public abstract void setReadTimeoutSecond(int readTimeoutSecond);

    public abstract void setWriteTimeoutSecond(int writeTimeoutSecond);

    public final <A> void write(ByteBuffer[] srcs, A attachment, CompletionHandler<Integer, ? super A> handler) {
        write(srcs, 0, srcs.length, attachment, handler);
    }

    protected abstract <A> void write(ByteBuffer[] srcs, int offset, int length, A attachment, CompletionHandler<Integer, ? super A> handler);

    public void dispose() {//同close， 只是去掉throws IOException
        try {
            this.close();
        } catch (IOException io) {
        }
    }

    @Override
    public void close() throws IOException {
        if (attributes == null) return;
        try {
            for (Object obj : attributes.values()) {
                if (obj instanceof AutoCloseable) ((AutoCloseable) obj).close();
            }
        } catch (Exception io) {
        }
    }

    @SuppressWarnings("unchecked")
    public final <T> T getSubobject() {
        return (T) this.subobject;
    }

    public void setSubobject(Object value) {
        this.subobject = value;
    }

    public void setAttribute(String name, Object value) {
        if (this.attributes == null) this.attributes = new HashMap<>();
        this.attributes.put(name, value);
    }

    @SuppressWarnings("unchecked")
    public final <T> T getAttribute(String name) {
        return (T) (this.attributes == null ? null : this.attributes.get(name));
    }

    public final void removeAttribute(String name) {
        if (this.attributes != null) this.attributes.remove(name);
    }

    public final Map<String, Object> getAttributes() {
        return this.attributes;
    }

    public final void clearAttribute() {
        if (this.attributes != null) this.attributes.clear();
    }

    //------------------------------------------------------------------------------------------------------------------------------
    public static AsyncConnection create(final String protocol, final AsynchronousChannelGroup group, final SocketAddress address) throws IOException {
        return create(protocol, group, address, 0, 0);
    }

    /**
     * 创建客户端连接
     *
     * @param protocol            连接类型 只能是TCP或UDP
     * @param address             连接点子
     * @param group               连接AsynchronousChannelGroup
     * @param readTimeoutSecond0  读取超时秒数
     * @param writeTimeoutSecond0 写入超时秒数
     * @return 连接
     * @throws java.io.IOException 异常
     */
    public static AsyncConnection create(final String protocol, final AsynchronousChannelGroup group, final SocketAddress address,
                                         final int readTimeoutSecond0, final int writeTimeoutSecond0) throws IOException {
        if ("TCP".equalsIgnoreCase(protocol)) {
            AsynchronousSocketChannel channel = AsynchronousSocketChannel.open(group);
            try {
                channel.connect(address).get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IOException("AsyncConnection connect " + address, e);
            }
            return create(channel, address, readTimeoutSecond0, writeTimeoutSecond0);
        } else if ("UDP".equalsIgnoreCase(protocol)) {
            DatagramChannel channel = DatagramChannel.open();
            channel.configureBlocking(true);
            channel.connect(address);
            return create(channel, address, true, readTimeoutSecond0, writeTimeoutSecond0);
        } else {
            throw new RuntimeException("AsyncConnection not support protocol " + protocol);
        }
    }

    private static class BIOUDPAsyncConnection extends AsyncConnection {

        private int readTimeoutSecond;

        private int writeTimeoutSecond;

        private final DatagramChannel channel;

        private final SocketAddress remoteAddress;

        private final boolean client;

        public BIOUDPAsyncConnection(final DatagramChannel ch, SocketAddress addr,
                                     final boolean client0, final int readTimeoutSecond0, final int writeTimeoutSecond0) {
            this.channel = ch;
            this.client = client0;
            this.readTimeoutSecond = readTimeoutSecond0;
            this.writeTimeoutSecond = writeTimeoutSecond0;
            this.remoteAddress = addr;
        }

        @Override
        public void setReadTimeoutSecond(int readTimeoutSecond) {
            this.readTimeoutSecond = readTimeoutSecond;
        }

        @Override
        public void setWriteTimeoutSecond(int writeTimeoutSecond) {
            this.writeTimeoutSecond = writeTimeoutSecond;
        }

        @Override
        public int getReadTimeoutSecond() {
            return this.readTimeoutSecond;
        }

        @Override
        public int getWriteTimeoutSecond() {
            return this.writeTimeoutSecond;
        }

        @Override
        public final SocketAddress getRemoteAddress() {
            return remoteAddress;
        }

        @Override
        public SocketAddress getLocalAddress() {
            try {
                return channel.getLocalAddress();
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        protected <A> void write(ByteBuffer[] srcs, int offset, int length, A attachment, CompletionHandler<Integer, ? super A> handler) {
            try {
                int rs = 0;
                for (int i = offset; i < offset + length; i++) {
                    rs += channel.send(srcs[i], remoteAddress);
                    if (i != offset) Thread.sleep(10);
                }
                if (handler != null) handler.completed(rs, attachment);
            } catch (Exception e) {
                if (handler != null) handler.failed(e, attachment);
            }
        }

        @Override
        public <A> void read(ByteBuffer dst, A attachment, CompletionHandler<Integer, ? super A> handler) {
            try {
                int rs = channel.read(dst);
                if (handler != null) handler.completed(rs, attachment);
            } catch (IOException e) {
                if (handler != null) handler.failed(e, attachment);
            }
        }

        @Override
        public Future<Integer> read(ByteBuffer dst) {
            try {
                int rs = channel.read(dst);
                return new SimpleFuture(rs);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public <A> void write(ByteBuffer src, A attachment, CompletionHandler<Integer, ? super A> handler) {
            try {
                int rs = channel.send(src, remoteAddress);
                if (handler != null) handler.completed(rs, attachment);
            } catch (IOException e) {
                if (handler != null) handler.failed(e, attachment);
            }
        }

        @Override
        public Future<Integer> write(ByteBuffer src) {
            try {
                int rs = channel.send(src, remoteAddress);
                return new SimpleFuture(rs);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public final void close() throws IOException {
            super.close();
            if (client) channel.close();
        }

        @Override
        public final boolean isOpen() {
            return channel.isOpen();
        }

        @Override
        public final boolean isTCP() {
            return false;
        }
    }

    public static AsyncConnection create(final DatagramChannel ch, SocketAddress addr,
                                         final boolean client0, final int readTimeoutSecond0, final int writeTimeoutSecond0) {
        return new BIOUDPAsyncConnection(ch, addr, client0, readTimeoutSecond0, writeTimeoutSecond0);
    }

    private static class SimpleFuture implements Future<Integer> {

        private final int rs;

        public SimpleFuture(int rs) {
            this.rs = rs;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return true;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Integer get() throws InterruptedException, ExecutionException {
            return rs;
        }

        @Override
        public Integer get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return rs;
        }

    }

    private static class BIOTCPAsyncConnection extends AsyncConnection {

        private int readTimeoutSecond;

        private int writeTimeoutSecond;

        private final Socket socket;

        private final ReadableByteChannel readChannel;

        private final WritableByteChannel writeChannel;

        private final SocketAddress remoteAddress;

        public BIOTCPAsyncConnection(final Socket socket, final SocketAddress addr0, final int readTimeoutSecond0, final int writeTimeoutSecond0) {
            this.socket = socket;
            ReadableByteChannel rc = null;
            WritableByteChannel wc = null;
            try {
                socket.setSoTimeout(Math.max(readTimeoutSecond0, writeTimeoutSecond0));
                rc = Channels.newChannel(socket.getInputStream());
                wc = Channels.newChannel(socket.getOutputStream());
            } catch (IOException e) {
                e.printStackTrace();
            }
            this.readChannel = rc;
            this.writeChannel = wc;
            this.readTimeoutSecond = readTimeoutSecond0;
            this.writeTimeoutSecond = writeTimeoutSecond0;
            SocketAddress addr = addr0;
            if (addr == null) {
                try {
                    addr = socket.getRemoteSocketAddress();
                } catch (Exception e) {
                    //do nothing
                }
            }
            this.remoteAddress = addr;
        }

        @Override
        public boolean isTCP() {
            return true;
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return remoteAddress;
        }

        @Override
        public SocketAddress getLocalAddress() {
            return socket.getLocalSocketAddress();
        }

        @Override
        public int getReadTimeoutSecond() {
            return readTimeoutSecond;
        }

        @Override
        public int getWriteTimeoutSecond() {
            return writeTimeoutSecond;
        }

        @Override
        public void setReadTimeoutSecond(int readTimeoutSecond) {
            this.readTimeoutSecond = readTimeoutSecond;
        }

        @Override
        public void setWriteTimeoutSecond(int writeTimeoutSecond) {
            this.writeTimeoutSecond = writeTimeoutSecond;
        }

        @Override
        protected <A> void write(ByteBuffer[] srcs, int offset, int length, A attachment, CompletionHandler<Integer, ? super A> handler) {
            try {
                int rs = 0;
                for (int i = offset; i < offset + length; i++) {
                    rs += writeChannel.write(srcs[i]);
                }
                if (handler != null) handler.completed(rs, attachment);
            } catch (IOException e) {
                if (handler != null) handler.failed(e, attachment);
            }
        }

        @Override
        public <A> void read(ByteBuffer dst, A attachment, CompletionHandler<Integer, ? super A> handler) {
            try {
                int rs = readChannel.read(dst);
                if (handler != null) handler.completed(rs, attachment);
            } catch (IOException e) {
                if (handler != null) handler.failed(e, attachment);
            }
        }

        @Override
        public Future<Integer> read(ByteBuffer dst) {
            try {
                int rs = readChannel.read(dst);
                return new SimpleFuture(rs);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public <A> void write(ByteBuffer src, A attachment, CompletionHandler<Integer, ? super A> handler) {
            try {
                int rs = writeChannel.write(src);
                if (handler != null) handler.completed(rs, attachment);
            } catch (IOException e) {
                if (handler != null) handler.failed(e, attachment);
            }
        }

        @Override
        public Future<Integer> write(ByteBuffer src) {
            try {
                int rs = writeChannel.write(src);
                return new SimpleFuture(rs);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() throws IOException {
            super.close();
            this.socket.close();
        }

        @Override
        public boolean isOpen() {
            return !socket.isClosed();
        }
    }

    /**
     * 通常用于 ssl socket
     *
     * @param socket Socket对象
     * @return 连接对象
     */
    public static AsyncConnection create(final Socket socket) {
        return create(socket, null, 0, 0);
    }

    public static AsyncConnection create(final Socket socket, final SocketAddress addr0, final int readTimeoutSecond0, final int writeTimeoutSecond0) {
        return new BIOTCPAsyncConnection(socket, addr0, readTimeoutSecond0, writeTimeoutSecond0);
    }

    private static class AIOTCPAsyncConnection extends AsyncConnection {

        private int readTimeoutSecond;

        private int writeTimeoutSecond;

        private final AsynchronousSocketChannel channel;

        private final SocketAddress remoteAddress;

        public AIOTCPAsyncConnection(final AsynchronousSocketChannel ch, final SocketAddress addr0, final int readTimeoutSecond0, final int writeTimeoutSecond0) {
            this.channel = ch;
            this.readTimeoutSecond = readTimeoutSecond0;
            this.writeTimeoutSecond = writeTimeoutSecond0;
            SocketAddress addr = addr0;
            if (addr == null) {
                try {
                    addr = ch.getRemoteAddress();
                } catch (Exception e) {
                    //do nothing
                }
            }
            this.remoteAddress = addr;
        }

        @Override
        public <A> void read(ByteBuffer dst, A attachment, CompletionHandler<Integer, ? super A> handler) {
            if (readTimeoutSecond > 0) {
                channel.read(dst, readTimeoutSecond, TimeUnit.SECONDS, attachment, handler);
            } else {
                channel.read(dst, attachment, handler);
            }
        }

        @Override
        public <A> void write(ByteBuffer src, A attachment, CompletionHandler<Integer, ? super A> handler) {
            if (writeTimeoutSecond > 0) {
                channel.write(src, writeTimeoutSecond, TimeUnit.SECONDS, attachment, handler);
            } else {
                channel.write(src, attachment, handler);
            }
        }

        @Override
        public <A> void write(ByteBuffer[] srcs, int offset, int length, A attachment, final CompletionHandler<Integer, ? super A> handler) {
            channel.write(srcs, offset, length, writeTimeoutSecond > 0 ? writeTimeoutSecond : 60, TimeUnit.SECONDS,
                attachment, new CompletionHandler<Long, A>() {

                @Override
                public void completed(Long result, A attachment) {
                    handler.completed(result.intValue(), attachment);
                }

                @Override
                public void failed(Throwable exc, A attachment) {
                    handler.failed(exc, attachment);
                }

            });
        }

        @Override
        public void setReadTimeoutSecond(int readTimeoutSecond) {
            this.readTimeoutSecond = readTimeoutSecond;
        }

        @Override
        public void setWriteTimeoutSecond(int writeTimeoutSecond) {
            this.writeTimeoutSecond = writeTimeoutSecond;
        }

        @Override
        public int getReadTimeoutSecond() {
            return this.readTimeoutSecond;
        }

        @Override
        public int getWriteTimeoutSecond() {
            return this.writeTimeoutSecond;
        }

        @Override
        public final SocketAddress getRemoteAddress() {
            return remoteAddress;
        }

        @Override
        public SocketAddress getLocalAddress() {
            try {
                return channel.getLocalAddress();
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public final Future<Integer> read(ByteBuffer dst) {
            return channel.read(dst);
        }

        @Override
        public final Future<Integer> write(ByteBuffer src) {
            return channel.write(src);
        }

        @Override
        public final void close() throws IOException {
            super.close();
            channel.close();
        }

        @Override
        public final boolean isOpen() {
            return channel.isOpen();
        }

        @Override
        public final boolean isTCP() {
            return true;
        }

    }

    public static AsyncConnection create(final AsynchronousSocketChannel ch) {
        return create(ch, null, 0, 0);
    }

    public static AsyncConnection create(final AsynchronousSocketChannel ch, final SocketAddress addr0, final int readTimeoutSecond0, final int writeTimeoutSecond0) {
        return new AIOTCPAsyncConnection(ch, addr0, readTimeoutSecond0, writeTimeoutSecond0);
    }

}
