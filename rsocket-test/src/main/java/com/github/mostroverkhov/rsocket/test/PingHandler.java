/*
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.mostroverkhov.rsocket.test;

import com.github.mostroverkhov.rsocket.AbstractRSocket;
import com.github.mostroverkhov.rsocket.ConnectionSetupPayload;
import com.github.mostroverkhov.rsocket.Payload;
import com.github.mostroverkhov.rsocket.RSocket;
import com.github.mostroverkhov.rsocket.SocketAcceptor;
import com.github.mostroverkhov.rsocket.util.PayloadImpl;
import java.util.concurrent.ThreadLocalRandom;
import reactor.core.publisher.Mono;

public class PingHandler implements SocketAcceptor {

  private final Payload pong;

  public PingHandler() {
    byte[] data = new byte[1024];
    ThreadLocalRandom.current().nextBytes(data);
    pong = new PayloadImpl(data);
  }

  public PingHandler(byte[] data) {
    pong = new PayloadImpl(data);
  }

  @Override
  public Mono<RSocket> accept(ConnectionSetupPayload setup, RSocket sendingSocket) {
    return Mono.just(
        new AbstractRSocket() {
          @Override
          public Mono<Payload> requestResponse(Payload payload) {
            return Mono.just(pong);
          }
        });
  }
}
