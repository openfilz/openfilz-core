# OpenFilz Docker Compose Makefile
#
# Usage:
#   make up            - Start base services (postgres, api, web)
#   make up-auth       - Start with Keycloak authentication
#   make up-minio      - Start with MinIO storage
#   make up-onlyoffice - Start with OnlyOffice document server
#   make up-fulltext   - Start with OpenSearch full-text search
#   make up-demo       - Start all CE features (no auth) for demo
#   make up-full       - Start all services
#   make down          - Stop all services
#   make logs          - View logs
#   make clean         - Stop and remove volumes
#
# Frontend configuration is automatically generated based on the selected features.

# Load .env file if it exists
ifneq (,$(wildcard ./.env))
    include .env
    export
endif

# Default values for frontend config
NG_APP_API_URL ?= http://localhost:8081/api/v1
NG_APP_GRAPHQL_URL ?= http://localhost:8081/graphql/v1
NG_APP_AUTHENTICATION_AUTHORITY ?= http://localhost:8180/realms/openfilz
NG_APP_AUTHENTICATION_CLIENT_ID ?= openfilz-web
NG_APP_ONLYOFFICE_URL ?= http://localhost:8080

# Base compose command
COMPOSE := docker-compose -p openfilz -f docker-compose.yml

# Overlay files
AUTH := -f docker-compose.auth.yml
MINIO := -f docker-compose.minio.yml
ONLYOFFICE := -f docker-compose.onlyoffice.yml
FULLTEXT := -f docker-compose.fulltext.yml
THUMBNAILS := -f docker-compose-thumbnails.yml

.PHONY: help generate-config up up-auth up-minio up-onlyoffice up-fulltext up-full \
        up-auth-minio up-auth-fulltext up-auth-onlyoffice up-demo down logs ps restart clean build pull

help:
	@echo "OpenFilz Docker Compose Commands:"
	@echo ""
	@echo "  make up              Start base services (postgres, api, web)"
	@echo "  make up-auth         Start with Keycloak authentication"
	@echo "  make up-minio        Start with MinIO storage"
	@echo "  make up-onlyoffice   Start with OnlyOffice document server"
	@echo "  make up-fulltext     Start with OpenSearch full-text search"
	@echo "  make up-demo         Start all CE features (no auth) for demo"
	@echo "  make up-full         Start all services"
	@echo ""
	@echo "  make down            Stop all services"
	@echo "  make logs            View logs (follow mode)"
	@echo "  make ps              Show running containers"
	@echo "  make restart         Restart all services"
	@echo "  make clean           Stop and remove volumes"
	@echo ""
	@echo "Combinations:"
	@echo "  make up-auth-minio       Start with auth + MinIO"
	@echo "  make up-auth-fulltext    Start with auth + OpenSearch"
	@echo "  make up-auth-onlyoffice  Start with auth + OnlyOffice"
	@echo ""
	@echo "Access URLs:"
	@echo "  Without auth: API on :8081, Web on :4200"
	@echo "  With auth:    API on :8081, Web on :4200, Keycloak on :8180"

# Frontend feature toggles passed to the web container, which writes ngx-env.js at
# startup from the NG_APP_* env. $(1)=auth enabled, $(2)=onlyoffice enabled. The other
# NG_APP_* values are exported from .env (see `export` above) and read by the web
# service's compose `environment:` block.
web_toggles = NG_APP_AUTHENTICATION_ENABLED=$(1) NG_APP_ONLYOFFICE_ENABLED=$(2)

# Base services only (no auth, no onlyoffice)
up:
	$(call web_toggles,false,false) $(COMPOSE) up -d

# With Keycloak authentication
up-auth:
	$(call web_toggles,true,false) $(COMPOSE) $(AUTH) up -d

# With MinIO storage
up-minio:
	$(call web_toggles,false,false) $(COMPOSE) $(MINIO) up -d

# With OnlyOffice
up-onlyoffice:
	$(call web_toggles,false,true) $(COMPOSE) $(ONLYOFFICE) up -d

# With OpenSearch full-text search
up-fulltext:
	$(call web_toggles,false,false) $(COMPOSE) $(FULLTEXT) up -d

# Common combinations
up-auth-minio:
	$(call web_toggles,true,false) $(COMPOSE) $(AUTH) $(MINIO) up -d

up-auth-fulltext:
	$(call web_toggles,true,false) $(COMPOSE) $(AUTH) $(FULLTEXT) up -d

up-auth-onlyoffice:
	$(call web_toggles,true,true) $(COMPOSE) $(AUTH) $(ONLYOFFICE) up -d

# Community Edition demo (all features, no auth)
up-demo:
	$(call web_toggles,false,true) OPENFILZ_CALCULATECHECKSUM=true $(COMPOSE) $(ONLYOFFICE) $(FULLTEXT) $(THUMBNAILS) --profile thumbnails up -d

# All services
up-full:
	$(call web_toggles,true,true) $(COMPOSE) $(AUTH) $(MINIO) $(ONLYOFFICE) $(FULLTEXT) $(THUMBNAILS) --profile thumbnails up -d

# Management commands
down:
	$(COMPOSE) $(AUTH) $(MINIO) $(ONLYOFFICE) $(FULLTEXT) $(THUMBNAILS) --profile thumbnails down

logs:
	$(COMPOSE) $(AUTH) $(MINIO) $(ONLYOFFICE) $(FULLTEXT) $(THUMBNAILS) --profile thumbnails logs -f

ps:
	docker-compose ps

restart: down up

clean:
	$(COMPOSE) $(AUTH) $(MINIO) $(ONLYOFFICE) $(FULLTEXT) $(THUMBNAILS) --profile thumbnails down -v

# Build images locally (if needed)
build:
	$(COMPOSE) build

# Pull latest images
pull:
	$(COMPOSE) $(AUTH) $(MINIO) $(ONLYOFFICE) $(FULLTEXT) $(THUMBNAILS) --profile thumbnails pull

# Show the frontend config the web container will generate (auth/onlyoffice off here)
generate-config:
	@echo "ngx-env.js is generated inside the web container at startup from the NG_APP_*"
	@echo "environment (see .env / .env.example). Effective base values:"
	@echo "  NG_APP_API_URL=$(NG_APP_API_URL)"
	@echo "  NG_APP_GRAPHQL_URL=$(NG_APP_GRAPHQL_URL)"
	@echo "  NG_APP_AUTHENTICATION_AUTHORITY=$(NG_APP_AUTHENTICATION_AUTHORITY)"
	@echo "  NG_APP_AUTHENTICATION_CLIENT_ID=$(NG_APP_AUTHENTICATION_CLIENT_ID)"
	@echo "  NG_APP_ONLYOFFICE_URL=$(NG_APP_ONLYOFFICE_URL)"
