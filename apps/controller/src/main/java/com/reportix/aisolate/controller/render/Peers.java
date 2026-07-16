package com.reportix.aisolate.controller.render;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IPBlockBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyEgressRule;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyEgressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeerBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPort;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPortBuilder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The peer schema of the documents and the values ({@code {namespaceSelector, podSelector, port}}
 * or {@code {cidr, port}}): the values are the allowlist, nothing here parses a URL. {@link #check}
 * refuses at start what no policy would bound.
 */
final class Peers {

  private static final Pattern CIDR = Pattern.compile("[0-9a-fA-F.:]+/(\\d{1,3})");

  /**
   * Refuses a peer the policy would not bound: a {@code cidr} shorter than /8 ({@code 0.0.0.0/0}
   * among them), a cidr next to selectors (Kubernetes rejects such a peer), neither, or, where the
   * rule names a port, none. An absent or empty peer passes: the identity provider outside the
   * cluster has none.
   */
  static void check(Map<String, Object> peer, String where, boolean needsPort) {
    if (peer == null || peer.isEmpty()) return;
    boolean cidr = peer.get("cidr") instanceof String;
    boolean selectors =
        peer.get("namespaceSelector") instanceof Map<?, ?>
            || peer.get("podSelector") instanceof Map<?, ?>;
    if (cidr && selectors) refuse(where, "a peer is a cidr or selectors, not both");
    if (!cidr && !selectors) refuse(where, "a peer needs a cidr or selectors");
    if (cidr) {
      Matcher block = CIDR.matcher((String) peer.get("cidr"));
      if (!block.matches()) refuse(where, "the cidr " + peer.get("cidr") + " is not ip/prefix");
      if (Integer.parseInt(block.group(1)) < 8) {
        refuse(where, "the cidr " + peer.get("cidr") + " is too wide: /8 or longer");
      }
    }
    if (needsPort) {
      int port;
      try {
        port = Integer.parseInt(String.valueOf(peer.get("port")));
      } catch (NumberFormatException notANumber) {
        port = 0;
      }
      if (port < 1 || port > 65535) refuse(where, "a peer needs a port from 1 to 65535");
    }
  }

  private static void refuse(String where, String why) {
    throw new IllegalArgumentException(where + ": " + why);
  }

  static NetworkPolicyPeer peer(Map<String, Object> peer) {
    NetworkPolicyPeerBuilder builder = new NetworkPolicyPeerBuilder();
    if (peer.get("cidr") instanceof String cidr) {
      builder.withIpBlock(new IPBlockBuilder().withCidr(cidr).build());
    }
    if (peer.get("namespaceSelector") instanceof Map<?, ?> labels) {
      builder.withNamespaceSelector(
          new LabelSelectorBuilder().withMatchLabels(strings(labels)).build());
    }
    if (peer.get("podSelector") instanceof Map<?, ?> labels) {
      builder.withPodSelector(new LabelSelectorBuilder().withMatchLabels(strings(labels)).build());
    }
    return builder.build();
  }

  /** One egress rule to the peer on its port; DNS gets UDP and TCP. */
  static NetworkPolicyEgressRule egress(Map<String, Object> peer, String... protocols) {
    List<NetworkPolicyPort> ports =
        peer.get("port") == null
            ? List.of()
            : Arrays.stream(protocols.length == 0 ? new String[] {"TCP"} : protocols)
                .map(protocol -> port(peer.get("port"), protocol))
                .toList();
    return new NetworkPolicyEgressRuleBuilder().withTo(peer(peer)).withPorts(ports).build();
  }

  static NetworkPolicyPort port(Object port, String protocol) {
    return new NetworkPolicyPortBuilder()
        .withProtocol(protocol)
        .withPort(new IntOrString(Integer.parseInt(String.valueOf(port))))
        .build();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> strings(Map<?, ?> labels) {
    return ((Map<String, Object>) labels)
        .entrySet().stream()
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    e -> String.valueOf(e.getValue()),
                    (first, second) -> second,
                    TreeMap::new));
  }

  private Peers() {}
}
