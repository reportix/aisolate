package com.reportix.aisolate.controller.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** A peer the policy would not bound is refused at start, with the setting named. */
class PeersTest {

  @ParameterizedTest
  @MethodSource("refused")
  void givenAWidePortlessOrMixedPeer_whenChecked_thenRefusedNamingWhy(
      Map<String, Object> peer, String why) {
    // Arrange / Act
    Throwable thrown = catchThrowable(() -> Peers.check(peer, "egress.hosts[db]", true));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("egress.hosts[db]: " + why);
  }

  static Stream<Arguments> refused() {
    return Stream.of(
        Arguments.of(
            Map.of("cidr", "0.0.0.0/0", "port", 443),
            "the cidr 0.0.0.0/0 is too wide: /8 or longer"),
        Arguments.of(
            Map.of("cidr", "10.0.0.0/7", "port", 443),
            "the cidr 10.0.0.0/7 is too wide: /8 or longer"),
        Arguments.of(Map.of("cidr", "10.0.0.1", "port", 443), "the cidr 10.0.0.1 is not ip/prefix"),
        Arguments.of(Map.of("cidr", "10.0.0.1/32"), "a peer needs a port from 1 to 65535"),
        Arguments.of(
            Map.of("cidr", "10.0.0.1/32", "port", 70000), "a peer needs a port from 1 to 65535"),
        Arguments.of(
            Map.of("cidr", "10.0.0.1/32", "podSelector", Map.of("app", "db"), "port", 5432),
            "a peer is a cidr or selectors, not both"),
        Arguments.of(Map.of("port", 5432), "a peer needs a cidr or selectors"));
  }

  @ParameterizedTest
  @MethodSource("accepted")
  void givenABoundedPeer_whenChecked_thenAccepted(Map<String, Object> peer, boolean needsPort) {
    // Arrange / Act / Assert
    assertThatCode(() -> Peers.check(peer, "egress.dns", needsPort)).doesNotThrowAnyException();
  }

  static Stream<Arguments> accepted() {
    return Stream.of(
        Arguments.of(Map.of("cidr", "10.0.0.0/8", "port", 53), true),
        Arguments.of(Map.of("cidr", "192.168.49.1/32", "port", "11434"), true),
        Arguments.of(
            Map.of(
                "namespaceSelector", Map.of("kubernetes.io/metadata.name", "kube-system"),
                "podSelector", Map.of("k8s-app", "kube-dns"),
                "port", 53),
            true),
        Arguments.of(Map.of("namespaceSelector", Map.of("name", "ingress-nginx")), false),
        Arguments.of(Map.of(), true));
  }
}
