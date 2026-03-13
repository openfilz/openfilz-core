// OpenFilz C# SDK Quick Start
//
// Demonstrates the core document management operations:
// - Folder creation, listing
// - File upload, download, rename, move, copy, delete
// - Favorites, metadata, dashboard, audit trail
//
// Prerequisites:
//   - A running OpenFilz API instance (default: http://localhost:8081)
//   - dotnet add package OpenFilz.Sdk
//
// The C# SDK uses Microsoft Dependency Injection (generichost pattern).
// All API classes are resolved from IServiceProvider and methods return
// IXxxApiResponse wrappers — use .Ok() to access the response data.

using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Client;
using OpenFilz.Sdk.Extensions;
using OpenFilz.Sdk.Model;

namespace OpenFilzSdkSamples;

public class Quickstart
{
    public static async Task RunAsync(string? apiUrl = null)
    {
        apiUrl ??= Environment.GetEnvironmentVariable("OPENFILZ_API_URL") ?? "http://localhost:8081";

        // ──── 1. Configure the SDK client via HostBuilder ───────────────────
        var host = Host.CreateDefaultBuilder()
            .ConfigureApi((context, services, options) =>
            {
                // For authenticated instances:
                // options.AddTokens(new BearerToken("your-oauth2-access-token"));

                options.AddApiHttpClients(client =>
                {
                    client.BaseAddress = new Uri(apiUrl);
                });
            })
            .Build();

        var folderApi = host.Services.GetRequiredService<IFolderControllerApi>();
        var documentApi = host.Services.GetRequiredService<IDocumentControllerApi>();
        var fileApi = host.Services.GetRequiredService<IFileControllerApi>();
        var favApi = host.Services.GetRequiredService<IFavoritesApi>();
        var dashboardApi = host.Services.GetRequiredService<IDashboardApi>();
        var auditApi = host.Services.GetRequiredService<IAuditControllerApi>();

        // ──── 2. Create a folder ────────────────────────────────────────────
        var folderResp = await folderApi.CreateFolderAsync(
            new CreateFolderRequest(name: "C# Quick Start"));
        var folder = folderResp.Ok()!;
        var folderId = folder.Id!.Value;
        Console.WriteLine($"Created folder: {folder.Name} (id={folderId})");

        // ──── 3. Upload a file ──────────────────────────────────────────────
        var tempFile = Path.GetTempFileName() + ".txt";
        await File.WriteAllTextAsync(tempFile, "Hello from OpenFilz C# SDK!");

        try
        {
            using var fileStream = File.OpenRead(tempFile);
            var uploadResp = await documentApi.UploadDocument1Async(
                file: fileStream,
                allowDuplicateFileNames: true,
                parentFolderId: folderId.ToString());
            var uploaded = uploadResp.Ok()!;
            var fileId = uploaded.Id!.Value;
            Console.WriteLine($"Uploaded: {uploaded.Name} (id={fileId})");

            // ──── 4. List folder contents ───────────────────────────────────────
            var listResp = await folderApi.ListFolderAsync(folderId: folderId, onlyFiles: false, onlyFolders: false);
            var contents = listResp.Ok()!;
            Console.WriteLine($"Folder contains {contents.Count} item(s):");
            foreach (var item in contents)
            {
                Console.WriteLine($"  {item.Type}: {item.Name}");
            }

            // ──── 5. Get document info ──────────────────────────────────────────
            var infoResp = await documentApi.GetDocumentInfoAsync(fileId, withMetadata: true);
            var info = infoResp.Ok()!;
            Console.WriteLine($"Document: name={info.Name}, type={info.ContentType}");

            // ──── 6. Update metadata ────────────────────────────────────────────
            await documentApi.UpdateDocumentMetadataAsync(fileId,
                new UpdateMetadataRequest(
                    metadataToUpdate: new Dictionary<string, object>
                    {
                        { "project", "C# Demo" },
                        { "version", 1 }
                    }));
            Console.WriteLine("Metadata updated");

            // ──── 7. Download the file ──────────────────────────────────────────
            var downloadResp = await documentApi.DownloadDocumentAsync(fileId);
            var downloadedStream = downloadResp.Ok()!;
            Console.WriteLine("File downloaded");

            // ──── 8. Rename the file ────────────────────────────────────────────
            var renameResp = await fileApi.RenameFileAsync(fileId,
                new RenameRequest(newName: "csharp-renamed.txt"));
            var renamed = renameResp.Ok()!;
            Console.WriteLine($"Renamed to: {renamed.Name}");

            // ──── 9. Create target folder and copy ──────────────────────────────
            var targetFolderResp = await folderApi.CreateFolderAsync(
                new CreateFolderRequest(name: "C# Quick Start Target"));
            var targetFolderId = targetFolderResp.Ok()!.Id!.Value;

            var copyResp = await fileApi.CopyFilesAsync(
                new CopyRequest(
                    documentIds: new List<Guid> { fileId },
                    targetFolderId: targetFolderId));
            var copies = copyResp.Ok()!;
            var copyId = copies[0].CopyId!.Value;
            Console.WriteLine($"Copied file, new id={copyId}");

            // ──── 10. Move the original file ────────────────────────────────────
            await fileApi.MoveFilesAsync(
                new MoveRequest(
                    documentIds: new List<Guid> { fileId },
                    targetFolderId: targetFolderId,
                    allowDuplicateFileNames: true));
            Console.WriteLine("Moved file to target folder");

            // ──── 11. Toggle favorite ───────────────────────────────────────────
            var favResp = await favApi.ToggleFavoriteAsync(fileId);
            var isFavorite = favResp.Ok()!;
            Console.WriteLine($"Favorite toggled: {isFavorite}");

            // ──── 12. Dashboard statistics ──────────────────────────────────────
            var statsResp = await dashboardApi.GetDashboardStatisticsAsync();
            var stats = statsResp.Ok()!;
            Console.WriteLine($"Dashboard: files={stats.TotalFiles}, folders={stats.TotalFolders}");

            // ──── 13. Audit trail ───────────────────────────────────────────────
            var trailResp = await auditApi.GetAuditTrailAsync(fileId, sort: "DESC");
            var trail = trailResp.Ok()!;
            Console.WriteLine($"Audit trail: {trail.Count} entries");

            // ──── 14. Cleanup ───────────────────────────────────────────────────
            await favApi.RemoveFavoriteAsync(fileId);
            await fileApi.DeleteFilesAsync(
                new DeleteRequest(documentIds: new List<Guid> { fileId, copyId }));
            await folderApi.DeleteFoldersAsync(
                new DeleteRequest(documentIds: new List<Guid> { folderId, targetFolderId }));
            Console.WriteLine("Cleanup complete");
        }
        finally
        {
            if (File.Exists(tempFile)) File.Delete(tempFile);
        }
    }
}
