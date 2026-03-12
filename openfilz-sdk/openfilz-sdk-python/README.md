# OpenFilz Python SDK

Python client library for the OpenFilz Document Management REST API.

## Installation

```bash
pip install openfilz-sdk-python
```

## Quick Start

### Configuration

```python
import openfilz_sdk
from openfilz_sdk.api import (
    document_controller_api,
    folder_controller_api,
    file_controller_api,
    favorite_controller_api,
    dashboard_controller_api,
    audit_controller_api,
    recycle_bin_controller_api,
)

configuration = openfilz_sdk.Configuration(
    host="https://your-openfilz-instance.com"
)
configuration.access_token = "your-oauth2-access-token"
```

### Upload a Document

```python
from openfilz_sdk.api import document_controller_api

with openfilz_sdk.ApiClient(configuration) as api_client:
    document_api = document_controller_api.DocumentControllerApi(api_client)

    with open("/path/to/report.pdf", "rb") as f:
        response = document_api.upload_document(
            file=f,
            parent_folder_id=None,        # root folder
            allow_duplicate_file_names=True
        )

    print(f"Uploaded: {response.id} - {response.name}")
```

### Create a Folder

```python
from openfilz_sdk.api import folder_controller_api
from openfilz_sdk.model.create_folder_request import CreateFolderRequest

with openfilz_sdk.ApiClient(configuration) as api_client:
    folder_api = folder_controller_api.FolderControllerApi(api_client)

    folder = folder_api.create_folder(
        CreateFolderRequest(
            name="My Project Documents",
            parent_id=None  # root folder
        )
    )

    print(f"Created folder: {folder.id}")
```

### List Folder Contents

```python
from openfilz_sdk.api import folder_controller_api

with openfilz_sdk.ApiClient(configuration) as api_client:
    folder_api = folder_controller_api.FolderControllerApi(api_client)

    contents = folder_api.list_folder(
        folder_id=folder_id,  # None for root
        only_files=False,
        only_folders=False
    )

    for item in contents:
        print(f"{item.type}: {item.name}")
```

### Download a Document

```python
from openfilz_sdk.api import document_controller_api

with openfilz_sdk.ApiClient(configuration) as api_client:
    document_api = document_controller_api.DocumentControllerApi(api_client)

    file_data = document_api.download_document(document_id)

    with open("/desired/path/document.pdf", "wb") as f:
        f.write(file_data)
```

### Move Files

```python
from openfilz_sdk.api import file_controller_api
from openfilz_sdk.model.move_request import MoveRequest

with openfilz_sdk.ApiClient(configuration) as api_client:
    file_api = file_controller_api.FileControllerApi(api_client)

    file_api.move_files(
        MoveRequest(
            document_ids=[file_id_1, file_id_2],
            target_folder_id=target_folder_id,
            allow_duplicate_file_names=False
        )
    )
```

### Copy Files

```python
from openfilz_sdk.api import file_controller_api
from openfilz_sdk.model.copy_request import CopyRequest

with openfilz_sdk.ApiClient(configuration) as api_client:
    file_api = file_controller_api.FileControllerApi(api_client)

    copies = file_api.copy_files(
        CopyRequest(
            document_ids=[file_id],
            target_folder_id=target_folder_id
        )
    )

    for copy in copies:
        print(f"Original: {copy.original_id} -> Copy: {copy.copy_id}")
```

### Rename a File

```python
from openfilz_sdk.api import file_controller_api
from openfilz_sdk.model.rename_request import RenameRequest

with openfilz_sdk.ApiClient(configuration) as api_client:
    file_api = file_controller_api.FileControllerApi(api_client)

    file_api.rename_file(
        file_id,
        RenameRequest(new_name="renamed-document.pdf")
    )
```

### Delete Files

```python
from openfilz_sdk.api import file_controller_api
from openfilz_sdk.model.delete_request import DeleteRequest

with openfilz_sdk.ApiClient(configuration) as api_client:
    file_api = file_controller_api.FileControllerApi(api_client)

    file_api.delete_files(
        DeleteRequest(document_ids=[file_id_1, file_id_2])
    )
```

### Manage Favorites

```python
from openfilz_sdk.api import favorite_controller_api

with openfilz_sdk.ApiClient(configuration) as api_client:
    fav_api = favorite_controller_api.FavoriteControllerApi(api_client)

    # Toggle favorite
    is_favorite = fav_api.toggle_favorite(document_id)

    # Check status
    status = fav_api.is_favorite(document_id)
```

### Get Document Info with Metadata

```python
from openfilz_sdk.api import document_controller_api

with openfilz_sdk.ApiClient(configuration) as api_client:
    document_api = document_controller_api.DocumentControllerApi(api_client)

    info = document_api.get_document_info(document_id, with_metadata=True)
    print(f"Name: {info.name}")
    print(f"Size: {info.size}")
    print(f"Content-Type: {info.content_type}")
    print(f"Metadata: {info.metadata}")
```

### Update Document Metadata

```python
from openfilz_sdk.api import document_controller_api
from openfilz_sdk.model.update_metadata_request import UpdateMetadataRequest

with openfilz_sdk.ApiClient(configuration) as api_client:
    document_api = document_controller_api.DocumentControllerApi(api_client)

    document_api.update_metadata(
        document_id,
        UpdateMetadataRequest(
            metadata_to_update={
                "project": "Alpha",
                "classification": "confidential",
                "version": 2,
            }
        )
    )
```

### Dashboard Statistics

```python
from openfilz_sdk.api import dashboard_controller_api

with openfilz_sdk.ApiClient(configuration) as api_client:
    dashboard_api = dashboard_controller_api.DashboardControllerApi(api_client)

    stats = dashboard_api.get_statistics()
    print(f"Total files: {stats.total_files}")
    print(f"Total folders: {stats.total_folders}")
    print(f"Storage used: {stats.total_storage_used} bytes")
```

### Audit Trail

```python
from openfilz_sdk.api import audit_controller_api

with openfilz_sdk.ApiClient(configuration) as api_client:
    audit_api = audit_controller_api.AuditControllerApi(api_client)

    trail = audit_api.get_audit_trail(document_id, sort="DESC")

    for entry in trail:
        print(f"{entry.action} by {entry.username} at {entry.timestamp}")
```

### Recycle Bin (Soft Delete)

```python
from openfilz_sdk.api import recycle_bin_controller_api
from openfilz_sdk.model.delete_request import DeleteRequest

with openfilz_sdk.ApiClient(configuration) as api_client:
    recycle_bin = recycle_bin_controller_api.RecycleBinControllerApi(api_client)

    # List deleted items
    deleted = recycle_bin.list_deleted_items()

    # Restore items
    recycle_bin.restore_items(
        DeleteRequest(document_ids=[deleted_item_id])
    )

    # Empty recycle bin
    recycle_bin.empty_recycle_bin()
```

## GraphQL Schema

The SDK includes bundled GraphQL schema files in the `openfilz_sdk/graphql/` package directory:

- `document.graphqls` - Core document queries (listFolder, count, favorites)
- `document-search.graphqls` - Full-text search queries

### GraphQL Example with gql

```python
from gql import gql, Client
from gql.transport.requests import RequestsHTTPTransport

transport = RequestsHTTPTransport(
    url="https://your-openfilz-instance.com/graphql/v1",
    headers={"Authorization": f"Bearer {access_token}"},
)

gql_client = Client(transport=transport, fetch_schema_from_transport=False)

LIST_FOLDER = gql("""
    query ListFolder($request: ListFolderRequest!) {
        listFolder(request: $request) {
            id
            type
            name
            contentType
            size
            createdAt
            updatedAt
            favorite
        }
    }
""")

result = gql_client.execute(
    LIST_FOLDER,
    variable_values={
        "request": {
            "id": folder_id,
            "pageInfo": {
                "pageNumber": 0,
                "pageSize": 20,
                "sortBy": "name",
                "sortOrder": "ASC",
            },
        }
    },
)

for item in result["listFolder"]:
    print(f"{item['type']}: {item['name']}")
```

### Full-Text Search Example

```python
SEARCH_DOCUMENTS = gql("""
    query SearchDocuments($query: String, $page: Int, $size: Int) {
        searchDocuments(query: $query, page: $page, size: $size) {
            totalHits
            documents {
                id
                name
                contentType
                size
                contentSnippet
                createdAt
            }
        }
    }
""")

result = gql_client.execute(
    SEARCH_DOCUMENTS,
    variable_values={"query": "quarterly report", "page": 0, "size": 10},
)

print(f"Found {result['searchDocuments']['totalHits']} documents")
for doc in result["searchDocuments"]["documents"]:
    print(f"{doc['name']}: {doc['contentSnippet']}")
```

## Requirements

- Python 3.8+
- urllib3 >= 1.25.3
