FROM quay.io/keycloak/keycloak:26.5

ADD import/realm-export.json /opt/keycloak/data/import/
ADD themes/openfilz /opt/keycloak/themes/openfilz/

RUN /opt/keycloak/bin/kc.sh build

