package org.moxie.confer.proxy.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * <pre>
 * File format:
 *
 * [Header: 21 bytes]
 *   version    : 1 byte  (0x01)
 *   chunk_size : 4 bytes (big-endian)
 *   file_id    : 16 bytes (random UUID)
 *
 * [Chunk N]
 *   iv         : 12 bytes
 *   ciphertext : chunk_size bytes (or less for the final chunk)
 *   auth_tag   : 16 bytes
 *
 * AAD per chunk:
 *   file_id (16) || sequence_number (4, big-endian) || is_final (1) || running_hash (32)
 *
 * Running hash chain:
 *   chunk 0: SHA-256(header)
 *   chunk N: SHA-256(prev_running_hash || ciphertext_with_tag_{N-1})
 * </pre>
 */
public class ChunkedCipher {

  private static final byte VERSION         = 0x01;
  private static final int  HEADER_SIZE     = 21;
  private static final int  FILE_ID_SIZE    = 16;
  private static final int  IV_SIZE         = 12;
  private static final int  TAG_SIZE        = 16;
  private static final int  TAG_BITS        = TAG_SIZE * 8;
  private static final int  HASH_SIZE       = 32;
  private static final int  MAX_CHUNK_SIZE  = 64 * 1024 * 1024;

  private final SecretKey key;

  private byte[] fileId;
  private int    encryptedChunkSize;
  private int    sequenceNumber;
  private byte[] runningHash;

  private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

  public ChunkedCipher(SecretKey key) {
    this.key = key;
  }

  public ChunkedCipher(String base64Key) {
    this(new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES"));
  }

  public byte[] update(byte[] data) throws GeneralSecurityException {
    return update(data, 0, data.length);
  }

  public byte[] update(byte[] data, int offset, int length) throws GeneralSecurityException {
    buf.write(data, offset, length);
    return drain(false);
  }

  public byte[] doFinal(byte[] data) throws GeneralSecurityException {
    if (data != null && data.length > 0) {
      buf.write(data, 0, data.length);
    }
    return drain(true);
  }

  public byte[] decrypt(byte[] data) throws GeneralSecurityException {
    return doFinal(data);
  }

  // ---------------------------------------------------------------------------

  private byte[] drain(boolean isFinal) throws GeneralSecurityException {
    ByteArrayOutputStream out     = new ByteArrayOutputStream();
    byte[]                pending = buf.toByteArray();
    int                   pos     = 0;

    if (fileId == null) {
      if (pending.length < HEADER_SIZE) {
        if (isFinal) throw new GeneralSecurityException("Invalid encrypted data: too short for header");
        return new byte[0];
      }

      ByteBuffer header  = ByteBuffer.wrap(pending, 0, HEADER_SIZE);
      byte       version = header.get();

      if (version != VERSION) {
        throw new GeneralSecurityException("Unsupported encryption version: " + version);
      }

      int chunkSize = header.getInt();

      if (chunkSize <= 0 || chunkSize > MAX_CHUNK_SIZE) {
        throw new GeneralSecurityException("Invalid chunk size: " + chunkSize);
      }

      encryptedChunkSize = IV_SIZE + chunkSize + TAG_SIZE;

      fileId = new byte[FILE_ID_SIZE];
      header.get(fileId);

      runningHash = sha256(pending, 0, HEADER_SIZE);
      pos = HEADER_SIZE;
    }

    while (pos + encryptedChunkSize <= pending.length) {
      boolean chunkIsFinal = isFinal && pos + encryptedChunkSize == pending.length;
      byte[]  pt           = decryptChunk(pending, pos, encryptedChunkSize, chunkIsFinal);

      out.write(pt, 0, pt.length);
      pos += encryptedChunkSize;

      if (chunkIsFinal) { resetBuffer(pending, pos); return out.toByteArray(); }
    }

    if (isFinal) {
      int remaining = pending.length - pos;
      if (remaining > 0) {
        if (remaining < IV_SIZE + TAG_SIZE) {
          throw new GeneralSecurityException("Invalid encrypted data: final chunk too short");
        }
        byte[] pt = decryptChunk(pending, pos, remaining, true);
        out.write(pt, 0, pt.length);
        pos += remaining;
      } else if (sequenceNumber == 0) {
        throw new GeneralSecurityException("Invalid encrypted data: no chunks found");
      }
    }

    resetBuffer(pending, pos);
    return out.toByteArray();
  }

  private byte[] decryptChunk(byte[] src, int offset, int length, boolean isFinal)
    throws GeneralSecurityException
  {
    byte[] aad = ByteBuffer.allocate(FILE_ID_SIZE + 4 + 1 + HASH_SIZE)
      .put(fileId)
      .putInt(sequenceNumber)
      .put(isFinal ? (byte) 1 : (byte) 0)
      .put(runningHash)
      .array();

    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, src, offset, IV_SIZE));
    cipher.updateAAD(aad);

    int    ciphertextOffset = offset + IV_SIZE;
    int    ciphertextLength = length - IV_SIZE;
    byte[] plaintext        = cipher.doFinal(src, ciphertextOffset, ciphertextLength);

    runningHash = sha256(runningHash, src, ciphertextOffset, ciphertextLength);
    sequenceNumber++;
    return plaintext;
  }

  private void resetBuffer(byte[] pending, int pos) {
    buf.reset();
    if (pos < pending.length) {
      buf.write(pending, pos, pending.length - pos);
    }
  }

  // ---------------------------------------------------------------------------

  private static byte[] sha256(byte[] data, int offset, int length)
    throws GeneralSecurityException
  {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    md.update(data, offset, length);
    return md.digest();
  }

  private static byte[] sha256(byte[] a, byte[] b, int offset, int length)
    throws GeneralSecurityException
  {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    md.update(a);
    md.update(b, offset, length);
    return md.digest();
  }
}
