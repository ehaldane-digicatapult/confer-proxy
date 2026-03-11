package org.moxie.confer.proxy.controllers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.crypto.ChunkedCipher;
import org.moxie.confer.proxy.crypto.ImageToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.InputStream;
import java.security.GeneralSecurityException;

/**
 * URL format:
 *   /v1/images?key={s3ObjectKey}&ek={base64EncryptionKey}&token={imageToken}
 */
@ApplicationScoped
@Path("/v1/images")
public class ImageController {

  private static final Logger log = LoggerFactory.getLogger(ImageController.class);

  @Inject
  S3Client s3;

  @Inject
  Config config;

  @Inject
  ImageToken imageToken;

  @GET
  public Response getImage(@QueryParam("key")   String objectKey,
                           @QueryParam("ek")    String encryptionKey,
                           @QueryParam("token") String token,
                           @QueryParam("type")  String mediaType)
  {
    if (token == null || !imageToken.isValid(token)) {
      throw new WebApplicationException(Response.Status.UNAUTHORIZED);
    }

    if (objectKey == null || objectKey.isBlank()) {
      throw new WebApplicationException("Missing 'key' parameter", 400);
    }

    if (encryptionKey == null || encryptionKey.isBlank()) {
      throw new WebApplicationException("Missing 'ek' parameter", 400);
    }

    String contentType = mediaType != null && !mediaType.isBlank() ? mediaType : "application/octet-stream";

    StreamingOutput stream = output -> {
      GetObjectRequest request = GetObjectRequest.builder()
                                                 .bucket(config.getS3Bucket())
                                                 .key(objectKey)
                                                 .build();

      try (InputStream s3Stream = s3.getObject(request)) {
        ChunkedCipher cipher = new ChunkedCipher(encryptionKey);
        byte[]        buf    = new byte[64 * 1024];
        int           read;

        while ((read = s3Stream.read(buf)) != -1) {
          byte[] decrypted = cipher.update(buf, 0, read);

          if (decrypted.length > 0) {
            output.write(decrypted);
          }
        }

        byte[] remaining = cipher.doFinal(null);

        if (remaining.length > 0) {
          output.write(remaining);
        }

        output.flush();
      } catch (GeneralSecurityException e) {
        log.error("Decryption failed for object: {}", objectKey, e);
        throw new WebApplicationException("Decryption failed", 500);
      }
    };

    return Response.ok(stream, contentType).build();
  }
}
