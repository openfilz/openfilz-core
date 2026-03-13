
# Building and Testing the Document Management System

This document provides instructions on how to build, configure, and test the application locally.

## Prerequisites

*   **Java 24**
*   **Maven 3.x**
*   For running MinIO locally: **Docker** and **Docker Compose** (optional, if not using local MinIO installation)
*   For running integration tests with Keycloak and PostgreSQL: **Docker** (Testcontainers will manage the containers)  

## Building the Application

The project is a multi-module Maven project. To build all modules, run the following command from the root directory:

```bash
mvn clean install
```

This will build the project modules.

### Generating the Docker Image

To generate the Docker image for the `openfilz-api`, you can use the `kube` Maven profile. This profile uses the `jib-maven-plugin` to build the image.

```bash
mvn clean install -Pkube -pl openfilz-api -am
```

This command will build the `openfilz-api` module and create a Docker image with the name `localhost:5000/snapshots/openfilz-api:1.0.0-SNAPSHOT`.

## Local Testing

For local testing, you will need to run MinIO if you are using it for storage. PostgreSQL and Keycloak are automatically managed by Testcontainers during integration tests.

### 1. Start Dependent Services (MinIO only, if applicable)

If you are using MinIO for storage, navigate to the `deploy/docker-compose` directory and run the following command:

```bash
docker-compose up -d minio
```

This will start the MinIO service.

### 2. Configure the Application

The application is configured via `application.yml` files located in the `src/main/resources` directory of each module.

#### `openfilz-api`

The `openfilz-api` module requires the following configuration:

*   **Database Connection**: The database connection details are configured in `application.yml`. For local development, you can configure it to connect to a local PostgreSQL instance or rely on Testcontainers for integration tests.
*   **Storage**: The storage can be configured to use either the local filesystem or MinIO. For local testing, you can use the local filesystem by setting `storage.type` to `local`.
*   **Keycloak** (optional): You can enable or disable the authentication by setting the value `(true|false)` to the parameter `openfilz.security.no-auth` (in `application.yml`). The Keycloak integration is configured in `application.yml`. For integration tests, Keycloak is managed by Testcontainers and configured via `realm-export.json` in `src/test/resources/keycloak`.

### 3. Run the Application

Once the dependent services are running and the application is configured, you can run the `openfilz-api`.

To run the `openfilz-api` module, navigate to the `openfilz-api` directory and run the following command:

```bash
mvn spring-boot:run
```

### 4. Accessing the Application

The `openfilz-api` service is available on port `8081`. The API documentation is available at `http://localhost:8081/swagger-ui.html`.

### 5. Running Tests

To run the unit and integration tests, run the following command from the root directory:

```bash
mvn test
```

Integration tests will automatically start PostgreSQL and Keycloak using Testcontainers.