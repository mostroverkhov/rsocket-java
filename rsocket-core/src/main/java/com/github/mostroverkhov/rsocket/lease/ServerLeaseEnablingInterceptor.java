/*
 * Copyright 2018 Maksym Ostroverkhov
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

package com.github.mostroverkhov.rsocket.lease;

import com.github.mostroverkhov.rsocket.DuplexConnection;
import com.github.mostroverkhov.rsocket.interceptors.DuplexConnectionInterceptor;

class ServerLeaseEnablingInterceptor implements DuplexConnectionInterceptor {
  private final LeaseContext leaseContext;

  public ServerLeaseEnablingInterceptor(LeaseContext leaseContext) {
    this.leaseContext = leaseContext;
  }

  @Override
  public DuplexConnection apply(Type type, DuplexConnection connection) {
    if (type == Type.INIT) {
      return new ServerLeaseEnablingConnection(connection, leaseContext);
    } else {
      return connection;
    }
  }
}
