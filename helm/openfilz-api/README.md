# Document Management API Helm Chart

This Helm chart deploys the Document Management API to a Kubernetes or OpenShift cluster.

It follows modern Helm best practices, including dependency management with `bitnami/common`, conditional logic for environment-specific resources, and secure credential management.

-   **Source Code**: [https://github.com/openfilz/openfilz-core](https://github.com/openfilz/openfilz-core)

## Prerequisites

-   Helm 3+
-   Kubernetes or OpenShift Cluster
-   A default `StorageClass` for dynamic PV provisioning, or a pre-configured `hostPath` for manual provisioning.

## Installation

1.  **Add Chart Dependencies:**
    The chart depends on `bitnami/common`. Add the Bitnami repository before installation.

    ```sh
    helm repo add bitnami https://charts.bitnami.com/bitnami
    ```

2.  **Update Dependencies:**
    From inside the chart's directory, fetch the dependencies.

    ```sh
    helm dependency update
    ```

3.  **Install the Chart:**
    Install the chart into a target namespace. It is highly recommended to set sensitive values like passwords via the `--set` flag.

    **Scenario 1: Deploying to Standard Kubernetes (Default)**
    By default, the chart is configured for a standard Kubernetes environment using an `Ingress`. You may need to specify a `storageClass` if your cluster does not have a default one.

    ```sh
    helm install my-release . \
      --namespace my-namespace \
      --create-namespace \
      --set persistence.storageClass="your-storage-class" \
      --set database.createPassword="YOUR_SECURE_PASSWORD"
    ```

    **Scenario 2: Deploying to OpenShift**
    To deploy on OpenShift, you must enable the `openshift` and `openshift.route` flags. This will create a `Route` instead of an `Ingress`.

    ```sh
    helm install my-release . \
      --namespace my-namespace \
      --create-namespace \
      --set openshift.enabled=true \
      --set openshift.route.enabled=true \
      --set persistence.storageClass="your-openshift-storage-class" \
      --set database.createPassword="YOUR_SECURE_PASSWORD"
    ```

## Naming Convention

The names of the deployed resources are managed by the `bitnami/common` helper chart and can be controlled in the following ways, in order of precedence:

1.  **Release Name (Recommended):** This is the standard Helm approach. The name you provide during `helm install` is used as a prefix for all resources.
    *   **Command:** `helm install my-app .`
    *   **Resulting Name:** `my-app-openfilz-api`

2.  **`fullnameOverride`:** To set a single, exact name for all resources, ignoring the release name and chart name.
    *   **`values.yaml`:** `fullnameOverride: "document-api"`
    *   **Resulting Name:** `document-api`

3.  **`nameOverride`:** To customize only the application part of the name, keeping the release name prefix.
    *   **`values.yaml`:** `nameOverride: "doc-api"`
    *   **Command:** `helm install my-app .`
    *   **Resulting Name:** `my-app-doc-api`

## Configuration

The following table lists the configurable parameters of the Document Management API chart and their default values.

| Parameter | Description | Default |
| :--- | :--- | :--- |
| `nameOverride` | String to override the default chart name component. | `""` |
| `fullnameOverride` | String to fully override the name of all resources. | `""` |
| `openshift.enabled` | Enables OpenShift-specific resources and deployment logic. | `false` |
| `openshift.route.enabled` | Enables the OpenShift Route. Only used when `openshift.enabled` is true. | `false` |
| `openshift.route.hostname` | The hostname for the Route (e.g., api.example.com). If empty, OpenShift will generate one. | `""` |
| `openshift.route.tls.enabled` | Enable TLS termination for the Route. | `true` |
| `openshift.route.tls.termination` | TLS termination policy. Valid values: 'edge', 'passthrough', 'reencrypt'. | `"edge"` |
| `openshift.route.tls.insecureEdgeTerminationPolicy` | Policy for handling insecure traffic. Valid values: 'Allow', 'Disable', 'Redirect'. | `"Redirect"` |
| `openshift.route.tls.key` | The private key for the TLS certificate. | `""` |
| `openshift.route.tls.certificate` | The public TLS certificate. | `""` |
| `openshift.route.tls.caCertificate` | The CA certificate for the chain. | `""` |
| `openshift.route.tls.destinationCACertificate` | The CA cert to validate the destination server in 'reencrypt' deployments. | `""` |
| `replicaCount` | The number of replicas for the Deployment. | `1` |
| `image.registry` | The container image registry. | `"nexus.oddo-bhf.com:8443"` |
| `image.repository` | The container image repository. | `"snapshots/openfilz-api"` |
| `image.tag` | The container image tag. | `"1.0.0-SNAPSHOT"` |
| `image.pullPolicy` | The image pull policy. | `"Always"` |
| `image.pullSecrets` | Secrets for pulling images from a private registry. | `[]` |
| `api.port` | The container port the application listens on. | `9984` |
| `spring.activeProfile`| The active Spring profile (e.g., kube, dev). | `"kube"` |
| `database.host` | The hostname of the database server. | `"localhost"` |
| `database.port` | The port of the database server. | `5432` |
| `database.name` | The name of the database. | `"dms-db"` |
| `database.existingSecret`| Name of an existing Secret with DB credentials (keys: `user`, `password`). | `""` |
| `database.createUser` | The database username to create in the new Secret. | `"app"` |
| `database.createPassword`| The database password to create. If empty, a random one is generated. | `""` |
| `storage.type` | The type of storage backend (e.g., 'local' or 'minio'). | `"local"` |
| `storage.basePath` | The path within the container where the volume is mounted. | `"/var/data/ged"` |
| `service.type` | The type of Kubernetes Service. | `"ClusterIP"` |
| `service.port` | The port the Service will expose. | `80` |
| `ingress.enabled` | Enables the Ingress resource (only if `openshift.enabled` is false). | `true` |
| `ingress.ingressClassName`| The class of the Ingress controller. | `"nginx"` |
| `ingress.path` | The path for the Ingress rule. | `"/"` |
| `ingress.annotations`| Annotations for the Ingress resource. | (map of values) |
| `ingress.labels` | Custom labels for the Ingress resource. | `{}` |
| `persistence.enabled`| Enable persistence using a PersistentVolumeClaim. | `true` |
| `persistence.accessModes`| PVC access modes. | `["ReadWriteOnce"]` |
| `persistence.size` | PVC storage size. | `"10Mi"` |
| `persistence.storageClass`| PVC StorageClass. If `""`, the cluster's default is used. | `""` |
| `persistence.hostPath` | The host path for the manual PV (used only if `openshift.enabled` is false). | `"/tmp/kube-storage"` |
| `persistence.existingClaim`| Use an existing PVC instead of creating a new one. | `""` |