package org.moxie.confer.proxy.crypto;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@ApplicationScoped
public class ImageToken {

  private final String token;

  public ImageToken() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public String get() {
    return token;
  }

  public boolean isValid(String candidate) {
    return MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8), this.token.getBytes(StandardCharsets.UTF_8));
  }
}
