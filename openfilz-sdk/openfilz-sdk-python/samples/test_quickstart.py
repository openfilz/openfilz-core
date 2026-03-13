"""
Integration tests for the Python SDK Quick Start sample.

Expects the OpenFilz API to be running at OPENFILZ_API_URL (default: http://localhost:18081).
In CI, the API is started before this test runs.
"""

import os
import tempfile

import pytest

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

API_URL = os.environ.get("OPENFILZ_API_URL", "http://localhost:18081")


@pytest.fixture(scope="module")
def api_client():
    configuration = openfilz_sdk.Configuration(host=API_URL)
    with openfilz_sdk.ApiClient(configuration) as client:
        yield client


@pytest.fixture(scope="module")
def apis(api_client):
    return {
        "folder": folder_controller_api.FolderControllerApi(api_client),
        "document": document_controller_api.DocumentControllerApi(api_client),
        "file": file_controller_api.FileControllerApi(api_client),
        "favorites": favorites_api.FavoritesApi(api_client),
        "dashboard": dashboard_api.DashboardApi(api_client),
        "audit": audit_controller_api.AuditControllerApi(api_client),
    }


class TestPythonSdkQuickStart:
    """Tests run in order, sharing state via class attributes."""

    folder_id = None
    target_folder_id = None
    file_id = None
    copy_file_id = None

    def test_01_create_folder(self, apis):
        folder = apis["folder"].create_folder(
            CreateFolderRequest(name=f"Py SDK Test {os.getpid()}")
        )
        assert folder.id is not None
        assert folder.name is not None
        TestPythonSdkQuickStart.folder_id = folder.id

    def test_02_upload_file(self, apis):
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".txt", delete=False
        ) as tmp:
            tmp.write("Python SDK test content")
            tmp_path = tmp.name

        try:
            resp = apis["document"].upload_document1(
                file=tmp_path,
                allow_duplicate_file_names=True,
                parent_folder_id=str(self.folder_id),
            )
            assert resp.id is not None
            assert resp.name is not None
            TestPythonSdkQuickStart.file_id = resp.id
        finally:
            os.unlink(tmp_path)

    def test_03_list_folder(self, apis):
        contents = apis["folder"].list_folder(
            folder_id=self.folder_id, only_files=False, only_folders=False
        )
        assert len(contents) > 0
        assert any(item.id == self.file_id for item in contents)

    def test_04_get_document_info(self, apis):
        info = apis["document"].get_document_info(self.file_id, with_metadata=True)
        assert info.name is not None
        assert info.content_type == "text/plain"

    def test_05_update_metadata(self, apis):
        result = apis["document"].update_document_metadata(
            self.file_id,
            UpdateMetadataRequest(metadata_to_update={"project": "Py Test"}),
        )
        assert result is not None

    def test_06_download_file(self, apis):
        downloaded = apis["document"].download_document(self.file_id)
        assert downloaded is not None

    def test_07_rename_file(self, apis):
        result = apis["file"].rename_file(
            self.file_id, RenameRequest(new_name="py-test-renamed.txt")
        )
        assert result.name == "py-test-renamed.txt"

    def test_08_copy_file(self, apis):
        target = apis["folder"].create_folder(
            CreateFolderRequest(name=f"Py SDK Target {os.getpid()}")
        )
        TestPythonSdkQuickStart.target_folder_id = target.id

        copies = apis["file"].copy_files(
            CopyRequest(
                document_ids=[self.file_id],
                target_folder_id=self.target_folder_id,
            )
        )
        assert len(copies) > 0
        TestPythonSdkQuickStart.copy_file_id = copies[0].copy_id

    def test_09_move_file(self, apis):
        apis["file"].move_files(
            MoveRequest(
                document_ids=[self.file_id],
                target_folder_id=self.target_folder_id,
                allow_duplicate_file_names=True,
            )
        )
        contents = apis["folder"].list_folder(
            folder_id=self.target_folder_id, only_files=True, only_folders=False
        )
        assert any(item.id == self.file_id for item in contents)

    def test_10_toggle_favorite(self, apis):
        result = apis["favorites"].toggle_favorite(self.file_id)
        assert result is True

    def test_11_dashboard_statistics(self, apis):
        stats = apis["dashboard"].get_dashboard_statistics()
        assert stats.total_files > 0

    def test_12_audit_trail(self, apis):
        trail = apis["audit"].get_audit_trail(self.file_id, sort="DESC")
        assert len(trail) > 0

    def test_13_cleanup(self, apis):
        apis["favorites"].remove_favorite(self.file_id)
        apis["file"].delete_files(
            DeleteRequest(document_ids=[self.file_id, self.copy_file_id])
        )
        apis["folder"].delete_folders(
            DeleteRequest(document_ids=[self.folder_id, self.target_folder_id])
        )
