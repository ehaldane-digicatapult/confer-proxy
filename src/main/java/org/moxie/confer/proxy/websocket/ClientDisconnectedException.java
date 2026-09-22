package org.moxie.confer.proxy.websocket;

import java.io.IOException;

public class ClientDisconnectedException extends IOException {

  public ClientDisconnectedException() {
    super("Client disconnected");
  }
}
