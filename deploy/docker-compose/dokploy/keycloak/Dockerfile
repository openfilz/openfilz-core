# Stage 0 is the stock Keycloak server. It must stay the first FROM, written exactly like this
# (no "AS" alias): its tag is the single source of truth for the Keycloak version, and
# build-push-keycloak-image.sh, check-keycloak-version.sh and the build-keycloak workflow read it
# from this line. The final stage takes /opt/keycloak from it with COPY --from=0.
FROM quay.io/keycloak/keycloak:26.8

# The stock image runs on OpenJDK 21 (kept there upstream for FIPS mode, which OpenFilz does not
# use). Keycloak supports OpenJDK 25 since 26.6, so the image is put together again the way
# upstream builds its own (quarkus/container/Dockerfile in the Keycloak sources, with its
# ubi-null.sh), with one difference: java-25-openjdk-headless in place of
# java-21-openjdk-headless. The result holds one JDK only.
FROM registry.access.redhat.com/ubi9 AS rootfs
COPY ubi-null.sh /tmp/
RUN bash /tmp/ubi-null.sh java-25-openjdk-headless glibc-langpack-en findutils

FROM registry.access.redhat.com/ubi9-micro

LABEL org.opencontainers.image.source="https://github.com/openfilz/openfilz-core"

ENV LANG=en_US.UTF-8
ENV KC_RUN_IN_CONTAINER=true

COPY --from=rootfs /tmp/null/rootfs/ /
COPY --from=0 --chown=1000:0 /opt/keycloak /opt/keycloak

RUN echo "keycloak:x:0:root" >> /etc/group \
 && echo "keycloak:x:1000:0:keycloak user:/opt/keycloak:/sbin/nologin" >> /etc/passwd

USER 1000

EXPOSE 8080
EXPOSE 8443
EXPOSE 9000

ADD --chown=keycloak:keycloak import/realm-export.json /opt/keycloak/data/import/
ADD themes/openfilz /opt/keycloak/themes/openfilz/

COPY --chmod=755 entrypoint.sh /opt/keycloak/

RUN /opt/keycloak/bin/kc.sh build

ENTRYPOINT ["/opt/keycloak/entrypoint.sh"]
