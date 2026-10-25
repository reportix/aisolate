#!/usr/bin/env bash
# Builds the runtime images and pushes them to docker-<channel>: the release component calls it with
# <version>-<sha10> on the default branch (channel internal) and with the tag on a release (channel
# public). The web images are not here: they bake their deployment's URLs at build time.
set -euo pipefail
version=$1
channel=$2
case "$channel" in
  internal | public) ;;
  *) echo "channel must be internal or public" >&2; exit 2 ;;
esac
registry="docker-$channel.sonatype.reportix.com/aisolate"

./gradlew ${GRADLE_OPTS:-} :apps:backend:bootJar :apps:airlock:bootJar :apps:tool-server:bootJar :apps:controller:bootJar

# The loops must match image_names in .gitlab-ci.yml, the list the release component verifies and records.
for app in backend airlock tool-server controller; do
  docker build -t "$registry/$app:$version" "apps/$app"
done
for app in airlock-python tool-server-python; do
  docker build -t "$registry/$app:$version" -f "apps/$app/Dockerfile" .
done
docker build -t "$registry/keycloak:$version" docker/keycloak

for app in backend airlock tool-server controller airlock-python tool-server-python keycloak; do
  docker push "$registry/$app:$version"
done
