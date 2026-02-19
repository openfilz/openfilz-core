#!/bin/sh

# cp ../../modules/collaboration/src/test/resources/keycloak/realm-export.json .

docker build -t yann78700/openfilz-ee:keycloak-26 .

# rm -f realm-export.json

docker push yann78700/openfilz-ee:keycloak-26

