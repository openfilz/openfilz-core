FROM quay.io/keycloak/keycloak:26.5

LABEL org.opencontainers.image.source="https://github.com/openfilz/openfilz-core"

ADD --chown=keycloak:keycloak import/realm-export.json /opt/keycloak/data/import/
ADD themes/openfilz /opt/keycloak/themes/openfilz/

COPY --chmod=755 entrypoint.sh /opt/keycloak/

RUN /opt/keycloak/bin/kc.sh build

ENTRYPOINT ["/opt/keycloak/entrypoint.sh"]
