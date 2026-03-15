FROM registry.access.redhat.com/ubi9 AS ubi-build
RUN mkdir -p /mnt/rootfs
RUN dnf install --installroot /mnt/rootfs gettext --releasever 9 --setopt install_weak_deps=false --nodocs -y && \
    dnf --installroot /mnt/rootfs clean all && \
    rpm --root /mnt/rootfs -e --nodeps setup

FROM quay.io/keycloak/keycloak:26.5

LABEL org.opencontainers.image.source="https://github.com/openfilz/openfilz-core"

# Copy envsubst binary from build stage (Keycloak image has no package manager)
COPY --from=ubi-build /mnt/rootfs /

ADD import/realm-export.json /opt/keycloak/data/import/
ADD themes/openfilz /opt/keycloak/themes/openfilz/

COPY --chmod=755 entrypoint.sh /opt/keycloak/

RUN /opt/keycloak/bin/kc.sh build

ENTRYPOINT ["/opt/keycloak/entrypoint.sh"]
