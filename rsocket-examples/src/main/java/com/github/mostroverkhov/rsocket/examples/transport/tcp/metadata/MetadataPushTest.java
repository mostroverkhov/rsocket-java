package com.github.mostroverkhov.rsocket.examples.transport.tcp.metadata;

import static java.time.Duration.ofSeconds;

import com.github.mostroverkhov.rsocket.AbstractRSocket;
import com.github.mostroverkhov.rsocket.Payload;
import com.github.mostroverkhov.rsocket.RSocket;
import com.github.mostroverkhov.rsocket.RSocketFactory;
import com.github.mostroverkhov.rsocket.transport.netty.client.TcpClientTransport;
import com.github.mostroverkhov.rsocket.transport.netty.server.NettyContextCloseable;
import com.github.mostroverkhov.rsocket.transport.netty.server.TcpServerTransport;
import com.github.mostroverkhov.rsocket.util.PayloadImpl;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MetadataPushTest {

  private static final Logger LOGGER =
      LoggerFactory.getLogger("com.github.mostroverkhov.rsocket.examples.metadata_push");

  public static void main(String[] args) {

    NettyContextCloseable nettyContextCloseable =
        RSocketFactory.receive()
            .acceptor(
                (setup, reactiveSocket) ->
                    Mono.just(
                        new AbstractRSocket() {
                          @Override
                          public Mono<Payload> requestResponse(Payload payload) {
                            return Mono.just(new PayloadImpl("Server Response " + new Date()));
                          }

                          @Override
                          public Mono<Void> metadataPush(Payload payload) {
                            LOGGER.info(
                                new Date()
                                    + " server: metadata receive - "
                                    + payload.getMetadataUtf8());
                            return reactiveSocket.metadataPush(
                                new PayloadImpl("", "server metadata"));
                          }
                        }))
            .transport(TcpServerTransport.create("localhost", 7000))
            .start()
            .block();

    RSocket clientSocket =
        RSocketFactory.connect()
            .acceptor(
                () ->
                    rsocket ->
                        new AbstractRSocket() {
                          @Override
                          public Mono<Void> metadataPush(Payload payload) {
                            LOGGER.info(
                                new Date()
                                    + " client : metadata receive - "
                                    + payload.getMetadataUtf8());
                            return Mono.empty();
                          }
                        })
            .transport(TcpClientTransport.create("localhost", 7000))
            .start()
            .block();

    Flux.interval(ofSeconds(1))
        .flatMap(
            signal ->
                clientSocket
                    .metadataPush(new PayloadImpl("", "client metadata"))
                    .onErrorResume(
                        err ->
                            Mono.<Void>empty().doOnTerminate(() -> LOGGER.info("Error: " + err))))
        .subscribe();

    clientSocket.onClose().block();
  }
}
