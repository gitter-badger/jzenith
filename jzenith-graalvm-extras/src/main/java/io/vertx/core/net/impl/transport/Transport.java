/**
 * Copyright © 2018 Marcus Thiesen (marcus@thiesen.org)
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
package io.vertx.core.net.impl.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.vertx.core.datagram.DatagramSocketOptions;
import io.vertx.core.net.ClientOptionsBase;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.impl.PartialPooledByteBufAllocator;

import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.concurrent.ThreadFactory;
import java.util.function.BiConsumer;

/**
 * The transport used by a {@link io.vertx.core.Vertx} instance.
 * <p/>
 *
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class Transport {

  /**
   * The JDK transport, always there.
   */
  public static final Transport JDK = new Transport();

  /**
   * The native transport, it may be {@code null} or failed.
   */
  public static Transport nativeTransport() {
    // Patched: I remove the native transport discovery. 
    // The imports would be picked up by substrate 
    // and cause further issues. 
    return null;
  }

  Transport() {
  }

  /**
   * @return true when the transport is available.
   */
  public boolean isAvailable() {
    return true;
  }

  /**
   * @return the error that cause the unavailability when {@link #isAvailable()} returns {@code null}.
   */
  public Throwable unavailabilityCause() {
    return null;
  }

  public SocketAddress convert(io.vertx.core.net.SocketAddress address, boolean resolved) {
    if (address.path() != null) {
      throw new IllegalArgumentException("Domain socket not supported by JDK transport");
    } else {
      if (resolved) {
        return new InetSocketAddress(address.host(), address.port());
      } else {
        return InetSocketAddress.createUnresolved(address.host(), address.port());
      }
    }
  }

  /**
   * Return a channel option for given {@code name} or null if that options does not exist
   * for this transport.
   *
   * @param name the option name
   * @return the channel option
   */
  ChannelOption<?> channelOption(String name) {
    return null;
  }

  /**
   * @return a new event loop group
   */
  public EventLoopGroup eventLoopGroup(int nThreads, ThreadFactory threadFactory, int ioRatio) {
    NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup(nThreads, threadFactory);
    eventLoopGroup.setIoRatio(ioRatio);
    return eventLoopGroup;
  }

  /**
   * @return a new datagram channel
   */
  public DatagramChannel datagramChannel() {
    return new NioDatagramChannel();
  }

  /**
   * @return a new datagram channel
   */
  public DatagramChannel datagramChannel(InternetProtocolFamily family) {
    switch (family) {
      case IPv4:
        return new NioDatagramChannel(InternetProtocolFamily.IPv4);
      case IPv6:
        return new NioDatagramChannel(InternetProtocolFamily.IPv6);
      default:
        throw new UnsupportedOperationException();
    }
  }

  /**
   * @return the type for channel
   * @param domain whether to create a unix domain channel or a socket channel
   */
  public Class<? extends Channel> channelType(boolean domain) {
    if (domain) {
      throw new IllegalArgumentException();
    }
    return NioSocketChannel.class;
  }

  /**
   * @return the type for server channel
   * @param domain whether to create a server unix domain channel or a regular server socket channel
   */
  public Class<? extends ServerChannel> serverChannelType(boolean domain) {
    if (domain) {
      throw new IllegalArgumentException();
    }
    return NioServerSocketChannel.class;
  }

  private void setOption(String name, Object value, BiConsumer<ChannelOption<Object>, Object> consumer) {
    @SuppressWarnings("unchecked")
    ChannelOption<Object> option = (ChannelOption<Object>) channelOption(name);
    if (option != null) {
      consumer.accept(option, value);
    }
  }

  public void configure(DatagramChannel channel, DatagramSocketOptions options) {
    channel.config().setAllocator(PartialPooledByteBufAllocator.INSTANCE);
    if (options.getSendBufferSize() != -1) {
      channel.config().setSendBufferSize(options.getSendBufferSize());
    }
    if (options.getReceiveBufferSize() != -1) {
      channel.config().setReceiveBufferSize(options.getReceiveBufferSize());
      channel.config().setRecvByteBufAllocator(new FixedRecvByteBufAllocator(options.getReceiveBufferSize()));
    }
    setOption("SO_REUSEPORT", options.isReusePort(), channel.config()::setOption);
    channel.config().setOption(ChannelOption.SO_REUSEADDR, options.isReuseAddress());
    if (options.getTrafficClass() != -1) {
      channel.config().setTrafficClass(options.getTrafficClass());
    }
    channel.config().setBroadcast(options.isBroadcast());
    if (this == Transport.JDK) {
      channel.config().setLoopbackModeDisabled(options.isLoopbackModeDisabled());
      if (options.getMulticastTimeToLive() != -1) {
        channel.config().setTimeToLive(options.getMulticastTimeToLive());
      }
      if (options.getMulticastNetworkInterface() != null) {
        try {
          channel.config().setNetworkInterface(NetworkInterface.getByName(options.getMulticastNetworkInterface()));
        } catch (SocketException e) {
          throw new IllegalArgumentException("Could not find network interface with name " + options.getMulticastNetworkInterface());
        }
      }
    }
  }

  public void configure(ClientOptionsBase options, Bootstrap bootstrap) {
    BiConsumer<ChannelOption<Object>, Object> setter = bootstrap::option;
    setOption("TCK_CORK", options.isTcpCork(), setter);
    setOption("TCK_QUICKACK", options.isTcpQuickAck(), setter);
    setOption("TCK_FASTOPEN", options.isTcpFastOpen(), setter);
    setOption("SO_REUSEPORT", options.isReusePort(), setter);
    if (options.getLocalAddress() != null) {
      bootstrap.localAddress(options.getLocalAddress(), 0);
    }
    bootstrap.option(ChannelOption.TCP_NODELAY, options.isTcpNoDelay());
    if (options.getSendBufferSize() != -1) {
      bootstrap.option(ChannelOption.SO_SNDBUF, options.getSendBufferSize());
    }
    if (options.getReceiveBufferSize() != -1) {
      bootstrap.option(ChannelOption.SO_RCVBUF, options.getReceiveBufferSize());
      bootstrap.option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(options.getReceiveBufferSize()));
    }
    if (options.getSoLinger() != -1) {
      bootstrap.option(ChannelOption.SO_LINGER, options.getSoLinger());
    }
    if (options.getTrafficClass() != -1) {
      bootstrap.option(ChannelOption.IP_TOS, options.getTrafficClass());
    }
    bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, options.getConnectTimeout());
    bootstrap.option(ChannelOption.ALLOCATOR, PartialPooledByteBufAllocator.INSTANCE);
    bootstrap.option(ChannelOption.SO_KEEPALIVE, options.isTcpKeepAlive());
    bootstrap.option(ChannelOption.SO_REUSEADDR, options.isReuseAddress());
  }

  public void configure(NetServerOptions options, ServerBootstrap bootstrap) {
    BiConsumer<ChannelOption<Object>, Object> setter = bootstrap::childOption;
    setOption("TCK_CORK", options.isTcpCork(), setter);
    setOption("TCK_QUICKACK", options.isTcpQuickAck(), setter);
    setOption("TCK_FASTOPEN", options.isTcpFastOpen(), setter);
    setOption("SO_REUSEPORT", options.isReusePort(), setter);
    bootstrap.childOption(ChannelOption.TCP_NODELAY, options.isTcpNoDelay());
    if (options.getSendBufferSize() != -1) {
      bootstrap.childOption(ChannelOption.SO_SNDBUF, options.getSendBufferSize());
    }
    if (options.getReceiveBufferSize() != -1) {
      bootstrap.childOption(ChannelOption.SO_RCVBUF, options.getReceiveBufferSize());
      bootstrap.childOption(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(options.getReceiveBufferSize()));
    }
    if (options.getSoLinger() != -1) {
      bootstrap.option(ChannelOption.SO_LINGER, options.getSoLinger());
    }
    if (options.getTrafficClass() != -1) {
      bootstrap.childOption(ChannelOption.IP_TOS, options.getTrafficClass());
    }
    bootstrap.childOption(ChannelOption.ALLOCATOR, PartialPooledByteBufAllocator.INSTANCE);
    bootstrap.childOption(ChannelOption.SO_KEEPALIVE, options.isTcpKeepAlive());
    bootstrap.option(ChannelOption.SO_REUSEADDR, options.isReuseAddress());
    if (options.getAcceptBacklog() != -1) {
      bootstrap.option(ChannelOption.SO_BACKLOG, options.getAcceptBacklog());
    }
  }
}
