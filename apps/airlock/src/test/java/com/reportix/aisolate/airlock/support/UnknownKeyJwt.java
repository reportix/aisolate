package com.reportix.aisolate.airlock.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** A token that looks right in every claim but is signed by a key the IdP never published. */
public final class UnknownKeyJwt {

  public static String forged(String issuer) {
    try {
      RSAKey key = new RSAKeyGenerator(2048).keyID("forged").generate();
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .issuer(issuer)
              .subject("attacker")
              .audience("aisolate")
              .claim("azp", "acme-chat")
              .claim("email", "nora@example.com")
              .claim("realm_access", Map.of("roles", List.of("ceo")))
              .expirationTime(new Date(System.currentTimeMillis() + 300_000))
              .build();
      SignedJWT jwt =
          new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("forged").build(), claims);
      jwt.sign(new RSASSASigner(key));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private UnknownKeyJwt() {}
}
