/*
 * Copyright 2016 Netflix, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.rsocket;

import static io.rsocket.interceptors.DuplexConnectionInterceptor.Type.STREAM_ZERO;
import static io.rsocket.interceptors.InterceptorFactory.InterceptorSet;

import io.rsocket.exceptions.InvalidSetupException;
import io.rsocket.exceptions.SetupException;
import io.rsocket.fragmentation.FragmentationDuplexConnection;
import io.rsocket.frame.SetupFrameFlyweight;
import io.rsocket.frame.VersionFlyweight;
import io.rsocket.interceptors.*;
import io.rsocket.internal.ConnectionDemux;
import io.rsocket.internal.ZeroErrorHandlingConnection;
import io.rsocket.keepalive.CloseOnKeepAliveTimeout;
import io.rsocket.keepalive.KeepAliveRequesterConnection;
import io.rsocket.keepalive.KeepAliveResponderConnection;
import io.rsocket.keepalive.KeepAlives;
import io.rsocket.lease.LeaseConnectionRef;
import io.rsocket.lease.LeaseSupport;
import io.rsocket.transport.ClientTransport;
import io.rsocket.transport.ServerTransport;
import io.rsocket.util.PayloadImpl;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/** Factory for creating RSocket clients and servers. */
public class RSocketFactory {
  /**
   * Creates a factory that establishes client connections to other RSockets.
   *
   * @return a client factory
   */
  public static ClientRSocketFactory connect() {
    return new ClientRSocketFactory();
  }

  /**
   * Creates a factory that receives server connections from client RSockets.
   *
   * @return a server factory.
   */
  public static ServerRSocketFactory receive() {
    return new ServerRSocketFactory();
  }

  public interface Start<T extends Closeable> {
    Mono<T> start();
  }

  public interface Acceptor<T, A> {
    T acceptor(Supplier<A> acceptor);

    default T acceptor(A acceptor) {
      return acceptor(() -> acceptor);
    }
  }

  public interface ClientTransportAcceptor {
    Start<RSocket> transport(Supplier<ClientTransport> transport);

    default Start<RSocket> transport(ClientTransport transport) {
      return transport(() -> transport);
    }
  }

  public interface ServerTransportAcceptor {
    <T extends Closeable> Start<T> transport(Supplier<ServerTransport<T>> transport);

    default <T extends Closeable> Start<T> transport(ServerTransport<T> transport) {
      return transport(() -> transport);
    }
  }

  public static class ClientRSocketFactory
      implements Acceptor<ClientTransportAcceptor, Function<RSocket, RSocket>>,
          ClientTransportAcceptor {

    protected Supplier<Function<RSocket, RSocket>> acceptor =
        () -> rSocket -> new AbstractRSocket() {};
    private Consumer<Throwable> errorConsumer = Throwable::printStackTrace;
    private int fragmentationMtu = 0;
    private final InterceptorFactory interceptorFactory = new InterceptorFactory();
    private int flags = SetupFrameFlyweight.FLAGS_STRICT_INTERPRETATION;

    private Payload setupPayload = PayloadImpl.EMPTY;

    private Duration keepAlivePeriod = Duration.ofMillis(500);
    private Optional<Consumer<LeaseConnectionRef>> leaseConsumer = Optional.empty();

    private int keepAlivePeriodsTimeout = 3;
    private Supplier<ByteBuffer> keepAlivePayloadSupplier = () -> Frame.NULL_BYTEBUFFER;
    private Consumer<KeepAlives> keepAlivesConsumer = new CloseOnKeepAliveTimeout(errorConsumer);

    private String metadataMimeType = "application/binary";
    private String dataMimeType = "application/binary";

    public ClientRSocketFactory addConnectionInterceptor(DuplexConnectionInterceptor interceptor) {
      this.interceptorFactory.addConnectionInterceptor(interceptor);
      return this;
    }

    public ClientRSocketFactory addRequesterInterceptor(RSocketInterceptor interceptor) {
      this.interceptorFactory.addRequesterInterceptor(interceptor);
      return this;
    }

    public ClientRSocketFactory addHandlerInterceptor(RSocketInterceptor interceptor) {
      this.interceptorFactory.addHandlerInterceptor(interceptor);
      return this;
    }

    public ClientRSocketFactory addInterceptorSet(Supplier<InterceptorSet> interceptor) {
      this.interceptorFactory.addInterceptorSet(interceptor);
      return this;
    }

    public ClientRSocketFactory keepAlive(
        Duration keepAlivePeriod,
        int keepAlivePeriodsTimeout,
        Supplier<ByteBuffer> keepAlivePayloadSupplier,
        Consumer<KeepAlives> keepAlivesConsumer) {
      this.keepAlivePeriod = keepAlivePeriod;
      this.keepAlivePeriodsTimeout = keepAlivePeriodsTimeout;
      this.keepAlivePayloadSupplier = keepAlivePayloadSupplier;
      this.keepAlivesConsumer = keepAlivesConsumer;
      return this;
    }

    public ClientRSocketFactory keepAlive(
        Duration period, int periodsTimeout, Consumer<KeepAlives> consumer) {
      return keepAlive(period, periodsTimeout, () -> Frame.NULL_BYTEBUFFER, consumer);
    }

    public ClientRSocketFactory enableLease(Consumer<LeaseConnectionRef> leaseControlConsumer) {
      this.leaseConsumer = Optional.of(leaseControlConsumer);
      flags |= SetupFrameFlyweight.FLAGS_WILL_HONOR_LEASE;
      return this;
    }

    public ClientRSocketFactory disableLease() {
      this.leaseConsumer = Optional.empty();
      flags &= ~SetupFrameFlyweight.FLAGS_WILL_HONOR_LEASE;
      return this;
    }

    public ClientRSocketFactory mimeType(String metadataMimeType, String dataMimeType) {
      this.dataMimeType = dataMimeType;
      this.metadataMimeType = metadataMimeType;
      return this;
    }

    public ClientRSocketFactory dataMimeType(String dataMimeType) {
      this.dataMimeType = dataMimeType;
      return this;
    }

    public ClientRSocketFactory metadataMimeType(String metadataMimeType) {
      this.metadataMimeType = metadataMimeType;
      return this;
    }

    @Override
    public Start<RSocket> transport(Supplier<ClientTransport> transportClient) {
      return new StartClient(transportClient);
    }

    @Override
    public ClientTransportAcceptor acceptor(Supplier<Function<RSocket, RSocket>> acceptor) {
      this.acceptor = acceptor;
      return StartClient::new;
    }

    public ClientRSocketFactory fragment(int mtu) {
      this.fragmentationMtu = mtu;
      return this;
    }

    public ClientRSocketFactory errorConsumer(Consumer<Throwable> errorConsumer) {
      this.errorConsumer = errorConsumer;
      return this;
    }

    public ClientRSocketFactory setupPayload(Payload payload) {
      this.setupPayload = payload;
      return this;
    }

    protected class StartClient implements Start<RSocket> {
      private final Supplier<ClientTransport> transportClient;
      private final InterceptorFactory interceptorFactory;

      StartClient(Supplier<ClientTransport> transportClient) {
        this.transportClient = transportClient;
        this.interceptorFactory = ClientRSocketFactory.this.interceptorFactory.copy();

        interceptorFactory.addConnectionInterceptor(
            new PerTypeDuplexConnectionInterceptor(STREAM_ZERO, KeepAliveResponderConnection::new));

        interceptorFactory.addConnectionInterceptor(
            new PerTypeDuplexConnectionInterceptor(
                STREAM_ZERO,
                conn -> {
                  KeepAliveRequesterConnection keepAliveRequesterConnection =
                      new KeepAliveRequesterConnection(
                          conn,
                          keepAlivePeriod,
                          keepAlivePeriodsTimeout,
                          keepAlivePayloadSupplier,
                          errorConsumer);
                  keepAlivesConsumer.accept(
                      new KeepAlives(
                          keepAliveRequesterConnection.keepAliveAvailable(),
                          keepAliveRequesterConnection.keepAliveMissing(),
                          keepAliveRequesterConnection.close()));

                  return keepAliveRequesterConnection;
                }));

        interceptorFactory.addConnectionInterceptor(
            new PerTypeDuplexConnectionInterceptor(STREAM_ZERO, ZeroErrorHandlingConnection::new));

        leaseConsumer.ifPresent(
            leaseConsumer ->
                interceptorFactory.addInterceptorSet(LeaseSupport.forClient(leaseConsumer)));
      }

      @Override
      public Mono<RSocket> start() {
        return transportClient
            .get()
            .connect()
            .flatMap(
                connection -> {
                  InterceptorRegistry interceptors = interceptorFactory.create();
                  Frame setupFrame =
                      Frame.Setup.from(
                          flags,
                          keepAlivePeriodsTimeout,
                          (int) keepAlivePeriod.toMillis() * keepAlivePeriodsTimeout,
                          metadataMimeType,
                          dataMimeType,
                          setupPayload);

                  if (fragmentationMtu > 0) {
                    connection = new FragmentationDuplexConnection(connection, fragmentationMtu);
                  }
                  ConnectionDemux connectionDemux = new ConnectionDemux(connection, interceptors);

                  RSocketRequester rSocketRequester =
                      new RSocketRequester(
                          connectionDemux.asClientConnection(),
                          errorConsumer,
                          StreamIdSupplier.clientSupplier());

                  Mono<RSocket> wrappedRSocketRequester =
                      Mono.just(rSocketRequester).map(interceptors::interceptRequester);

                  DuplexConnection finalConnection = connection;

                  return wrappedRSocketRequester.flatMap(
                      wrappedRequester -> {
                        RSocket handlerRSocket = acceptor.get().apply(wrappedRequester);

                        Mono<RSocket> wrappedHandlerRSocket =
                            Mono.just(handlerRSocket).map(interceptors::interceptHandler);

                        return wrappedHandlerRSocket
                            .doOnNext(
                                handler ->
                                    new RSocketResponder(
                                        connectionDemux.asZeroAndServerConnection(),
                                        handler,
                                        errorConsumer))
                            .then(finalConnection.sendOne(setupFrame))
                            .then(wrappedRSocketRequester);
                      });
                });
      }
    }
  }

  public static class ServerRSocketFactory
      implements Acceptor<ServerTransportAcceptor, SocketAcceptor> {

    private Supplier<SocketAcceptor> acceptor;
    private Consumer<Throwable> errorConsumer = Throwable::printStackTrace;
    private int mtu = 0;
    private final InterceptorFactory interceptorFactory = new InterceptorFactory();
    private Optional<Consumer<LeaseConnectionRef>> leaseControlConsumer = Optional.empty();

    public ServerRSocketFactory addConnectionInterceptor(DuplexConnectionInterceptor interceptor) {
      this.interceptorFactory.addConnectionInterceptor(interceptor);
      return this;
    }

    public ServerRSocketFactory addRequesterInterceptor(RSocketInterceptor interceptor) {
      this.interceptorFactory.addRequesterInterceptor(interceptor);
      return this;
    }

    public ServerRSocketFactory addHandlerInterceptor(RSocketInterceptor interceptor) {
      this.interceptorFactory.addHandlerInterceptor(interceptor);

      return this;
    }

    protected ServerRSocketFactory addInterceptor(Supplier<InterceptorSet> interceptor) {
      this.interceptorFactory.addInterceptorSet(interceptor);
      return this;
    }

    public ServerRSocketFactory enableLease(Consumer<LeaseConnectionRef> leaseControlConsumer) {
      this.leaseControlConsumer = Optional.of(leaseControlConsumer);
      return this;
    }

    public ServerRSocketFactory disableLease() {
      this.leaseControlConsumer = Optional.empty();
      return this;
    }

    @Override
    public ServerTransportAcceptor acceptor(Supplier<SocketAcceptor> acceptor) {
      this.acceptor = acceptor;
      return ServerStart::new;
    }

    public ServerRSocketFactory fragment(int mtu) {
      this.mtu = mtu;
      return this;
    }

    public ServerRSocketFactory errorConsumer(Consumer<Throwable> errorConsumer) {
      this.errorConsumer = errorConsumer;
      return this;
    }

    private class ServerStart<T extends Closeable> implements Start<T> {
      private final Supplier<ServerTransport<T>> transportServer;
      private final InterceptorFactory interceptorFactory;

      ServerStart(Supplier<ServerTransport<T>> transportServer) {
        this.transportServer = transportServer;
        this.interceptorFactory = ServerRSocketFactory.this.interceptorFactory.copy();

        interceptorFactory.addConnectionInterceptor(
            new PerTypeDuplexConnectionInterceptor(STREAM_ZERO, KeepAliveResponderConnection::new));
        interceptorFactory.addConnectionInterceptor(
            new PerTypeDuplexConnectionInterceptor(STREAM_ZERO, ZeroErrorHandlingConnection::new));

        Supplier<InterceptorSet> leaseInterceptor =
            leaseControlConsumer
                .map(LeaseSupport::forServer)
                .orElseGet(LeaseSupport::missingForServer);

        interceptorFactory.addInterceptorSet(leaseInterceptor);
      }

      @Override
      public Mono<T> start() {
        return transportServer
            .get()
            .start(
                conn -> {
                  if (mtu > 0) {
                    conn = new FragmentationDuplexConnection(conn, mtu);
                  }
                  InterceptorRegistry interceptors = interceptorFactory.create();
                  ConnectionDemux connectionDemux = new ConnectionDemux(conn, interceptors);

                  return connectionDemux
                      .asInitConnection()
                      .receive()
                      .next()
                      .flatMap(
                          setupFrame ->
                              processSetupFrame(interceptors, connectionDemux, setupFrame));
                });
      }

      private Mono<Void> processSetupFrame(
          InterceptorRegistry interceptors, ConnectionDemux multiplexer, Frame setupFrame) {

        int version = Frame.Setup.version(setupFrame);
        if (version != Frame.Setup.currentVersion()) {
          setupFrame.release();
          InvalidSetupException error =
              new InvalidSetupException(
                  "Unsupported version " + VersionFlyweight.toString(version));
          return setupError(multiplexer, error);
        }

        RSocketRequester rSocketRequester =
            new RSocketRequester(
                multiplexer.asServerConnection(), errorConsumer, StreamIdSupplier.serverSupplier());

        Mono<RSocket> wrappedRSocketRequester =
            Mono.just(rSocketRequester).map(interceptors::interceptRequester);

        ConnectionSetupPayload setupPayload = ConnectionSetupPayload.create(setupFrame);

        return wrappedRSocketRequester
            .flatMap(
                requester -> {
                  Mono<RSocket> wrappedHandler =
                      acceptor
                          .get()
                          .accept(setupPayload, requester)
                          .map(interceptors::interceptHandler);

                  return wrappedHandler;
                })
            .map(
                handler ->
                    new RSocketResponder(
                        multiplexer.asZeroAndClientConnection(), handler, errorConsumer))
            .then();
      }

      Mono<Void> setupError(ConnectionDemux multiplexer, SetupException error) {
        return multiplexer
            .asStreamZeroConnection()
            .sendOne(Frame.Error.from(0, error))
            .then(multiplexer.close());
      }
    }
  }
}
