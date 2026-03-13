"""
OpenFilz Python SDK Quick Start

Demonstrates the core document management operations:
- Folder creation, listing
- File upload, download, rename, move, copy, delete
- Favorites, metadata, dashboard, audit trail

Prerequisites:
    - A running OpenFilz API instance (default: http://localhost:8081)
    - pip install openfilz-sdk-python
"""

import os
import sys
import tempfile

import openfilz_sdk
from openfilz_sdk.api import (
    document_controller_api,
    folder_controller_api,
    file_controller_api,
    favorites_api,
    dashboard_api,
    audit_controller_api,
)
from openfilz_sdk.models.create_folder_request import CreateFolderRequest
from openfilz_sdk.models.rename_request import RenameRequest
from openfilz_sdk.models.copy_request import CopyRequest
from openfilz_sdk.models.move_request import MoveRequest
from openfilz_sdk.models.delete_request import DeleteRequest
from openfilz_sdk.models.update_metadata_request import UpdateMetadataRequest


def run_quickstart():
    api_url = os.environ.get("OPENFILZ_API_URL", "http://localhost:8081")

    # ──── 1. Configure the SDK client ────────────────────────────────
    configuration = openfilz_sdk.Configuration(host=api_url)
    # For authenticated instances:
    # configuration.access_token = "your-oauth2-access-token"

    with openfilz_sdk.ApiClient(configuration) as api_client:
        folder_api = folder_controller_api.FolderControllerApi(api_client)
        document_api = document_controller_api.DocumentControllerApi(api_client)
        file_api = file_controller_api.FileControllerApi(api_client)
        fav_api = favorites_api.FavoritesApi(api_client)
        dashboard = dashboard_api.DashboardApi(api_client)
        audit_api = audit_controller_api.AuditControllerApi(api_client)

        # ──── 2. Create a folder ─────────────────────────────────────────
        folder = folder_api.create_folder(
            CreateFolderRequest(name="Python Quick Start")
        )
        folder_id = folder.id
        print(f"Created folder: {folder.name} (id={folder_id})")

        # ──── 3. Upload a file ───────────────────────────────────────────
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".txt", delete=False
        ) as tmp:
            tmp.write("Hello from OpenFilz Python SDK!")
            tmp_path = tmp.name

        try:
            uploaded = document_api.upload_document1(
                file=tmp_path,
                allow_duplicate_file_names=True,
                parent_folder_id=str(folder_id),
            )
            file_id = uploaded.id
            print(f"Uploaded: {uploaded.name} (id={file_id})")

            # ──── 4. List folder contents ────────────────────────────────────
            contents = folder_api.list_folder(
                folder_id=folder_id, only_files=False, only_folders=False
            )
            print(f"Folder contains {len(contents)} item(s):")
            for item in contents:
                print(f"  {item.type}: {item.name}")

            # ──── 5. Get document info ───────────────────────────────────────
            info = document_api.get_document_info(file_id, with_metadata=True)
            print(f"Document: name={info.name}, type={info.content_type}")

            # ──── 6. Update metadata ─────────────────────────────────────────
            document_api.update_document_metadata(
                file_id,
                UpdateMetadataRequest(
                    metadata_to_update={"project": "Python Demo", "version": 1}
                ),
            )
            print("Metadata updated")

            # ──── 7. Download the file ───────────────────────────────────────
            downloaded = document_api.download_document(file_id)
            print(f"Downloaded file: {downloaded}")

            # ──── 8. Rename the file ─────────────────────────────────────────
            renamed = file_api.rename_file(
                file_id, RenameRequest(new_name="py-renamed.txt")
            )
            print(f"Renamed to: {renamed.name}")

            # ──── 9. Create target folder and copy ───────────────────────────
            target_folder = folder_api.create_folder(
                CreateFolderRequest(name="Python Quick Start Target")
            )
            target_folder_id = target_folder.id

            copies = file_api.copy_files(
                CopyRequest(
                    document_ids=[file_id], target_folder_id=target_folder_id
                )
            )
            copy_id = copies[0].copy_id
            print(f"Copied file, new id={copy_id}")

            # ──── 10. Move the original file ─────────────────────────────────
            file_api.move_files(
                MoveRequest(
                    document_ids=[file_id],
                    target_folder_id=target_folder_id,
                    allow_duplicate_file_names=True,
                )
            )
            print("Moved file to target folder")

            # ──── 11. Toggle favorite ────────────────────────────────────────
            is_favorite = fav_api.toggle_favorite(file_id)
            print(f"Favorite toggled: {is_favorite}")

            # ──── 12. Dashboard statistics ───────────────────────────────────
            stats = dashboard.get_dashboard_statistics()
            print(
                f"Dashboard: files={stats.total_files}, folders={stats.total_folders}"
            )

            # ──── 13. Audit trail ────────────────────────────────────────────
            trail = audit_api.get_audit_trail(file_id, sort="DESC")
            print(f"Audit trail: {len(trail)} entries")

            # ──── 14. Cleanup ────────────────────────────────────────────────
            fav_api.remove_favorite(file_id)
            file_api.delete_files(DeleteRequest(document_ids=[file_id, copy_id]))
            folder_api.delete_folders(
                DeleteRequest(document_ids=[folder_id, target_folder_id])
            )
            print("Cleanup complete")

        finally:
            os.unlink(tmp_path)


if __name__ == "__main__":
    run_quickstart()
