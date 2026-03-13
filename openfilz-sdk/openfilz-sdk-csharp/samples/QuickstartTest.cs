// Integration tests for the C# SDK Quick Start sample.
//
// Expects the OpenFilz API to be running at OPENFILZ_API_URL (default: http://localhost:18081).
// In CI, the API is started before this test runs.

using System.Text.Json;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using OpenFilz.Sdk.Api;
using OpenFilz.Sdk.Client;
using OpenFilz.Sdk.Extensions;
using OpenFilz.Sdk.Model;
using Xunit;
using Xunit.Abstractions;
using Xunit.Sdk;

namespace OpenFilzSdkSamples;

[TestCaseOrderer("OpenFilzSdkSamples.PriorityOrderer", "OpenFilzSdkSamples")]
public class QuickstartTest
{
    private static readonly string ApiUrl =
        Environment.GetEnvironmentVariable("OPENFILZ_API_URL") ?? "http://localhost:18081";

    private static readonly IHost SdkHost = Host.CreateDefaultBuilder()
        .ConfigureApi((context, services, options) =>
        {
            options.AddTokens(new BearerToken("no-auth"));
            options.AddApiHttpClients(client =>
            {
                client.BaseAddress = new Uri(ApiUrl);
            });
        })
        .Build();

    private static readonly JsonSerializerOptions JsonOptions =
        SdkHost.Services.GetRequiredService<JsonSerializerOptionsProvider>().Options;

    private static Guid? _folderId;
    private static Guid? _targetFolderId;
    private static Guid? _fileId;
    private static Guid? _copyFileId;

    private IFolderControllerApi FolderApi => SdkHost.Services.GetRequiredService<IFolderControllerApi>();
    private IDocumentControllerApi DocumentApi => SdkHost.Services.GetRequiredService<IDocumentControllerApi>();
    private IFileControllerApi FileApi => SdkHost.Services.GetRequiredService<IFileControllerApi>();
    private IFavoritesApi FavApi => SdkHost.Services.GetRequiredService<IFavoritesApi>();
    private IDashboardApi DashboardApiClient => SdkHost.Services.GetRequiredService<IDashboardApi>();
    private IAuditControllerApi AuditApi => SdkHost.Services.GetRequiredService<IAuditControllerApi>();

    /// <summary>
    /// Deserialize a 2xx response body. The generated SDK's Ok() only handles 200;
    /// this helper also handles 201/204 responses from the OpenFilz API.
    /// </summary>
    private static T Deserialize2xx<T>(IApiResponse resp)
    {
        Assert.True(resp.IsSuccessStatusCode,
            $"Expected 2xx but got {resp.StatusCode}: {resp.RawContent}");
        return JsonSerializer.Deserialize<T>(resp.RawContent, JsonOptions)
               ?? throw new JsonException($"Deserialization returned null for: {resp.RawContent}");
    }

    /// <summary>Assert that the response is a 2xx success.</summary>
    private static void Assert2xx(IApiResponse resp)
    {
        Assert.True(resp.IsSuccessStatusCode,
            $"Expected 2xx but got {resp.StatusCode}: {resp.RawContent}");
    }

    [Fact, TestPriority(1)]
    public async Task Test01_CreateFolder()
    {
        var resp = await FolderApi.CreateFolderAsync(
            new CreateFolderRequest(name: $"C# SDK Test {Guid.NewGuid().ToString("N")[..8]}"));
        var folder = Deserialize2xx<FolderResponse>(resp);
        Assert.NotNull(folder.Id);
        _folderId = folder.Id;
    }

    [Fact, TestPriority(2)]
    public async Task Test02_UploadFile()
    {
        // Use HttpClient directly because the generated SDK has a multipart boundary bug
        // (Content-Type header set without boundary, form params added after encoding)
        var tempFile = Path.GetTempFileName() + ".txt";
        await File.WriteAllTextAsync(tempFile, "C# SDK test content");
        try
        {
            using var httpClient = new HttpClient { BaseAddress = new Uri(ApiUrl) };
            using var content = new MultipartFormDataContent();
            using var stream = File.OpenRead(tempFile);
            content.Add(new StreamContent(stream), "file", "test-upload.txt");
            content.Add(new StringContent("true"), "allowDuplicateFileNames");
            content.Add(new StringContent(_folderId!.Value.ToString()), "parentFolderId");

            var httpResp = await httpClient.PostAsync("/api/v1/documents/upload", content);
            httpResp.EnsureSuccessStatusCode();
            var json = await httpResp.Content.ReadAsStringAsync();
            var uploaded = JsonSerializer.Deserialize<UploadResponse>(json, JsonOptions)!;
            Assert.NotNull(uploaded.Id);
            _fileId = uploaded.Id;
        }
        finally
        {
            File.Delete(tempFile);
        }
    }

    [Fact, TestPriority(3)]
    public async Task Test03_ListFolder()
    {
        var resp = await FolderApi.ListFolderAsync(_folderId!.Value, false, false);
        var contents = resp.Ok()!;
        Assert.NotEmpty(contents);
        Assert.Contains(contents, item => item.Id == _fileId);
    }

    [Fact, TestPriority(4)]
    public async Task Test04_GetDocumentInfo()
    {
        var resp = await DocumentApi.GetDocumentInfoAsync(_fileId!.Value, withMetadata: true);
        var info = resp.Ok()!;
        Assert.NotNull(info.Name);
        Assert.Equal("text/plain", info.ContentType);
    }

    [Fact, TestPriority(5)]
    public async Task Test05_UpdateMetadata()
    {
        var resp = await DocumentApi.UpdateDocumentMetadataAsync(
            _fileId!.Value,
            new UpdateMetadataRequest(
                metadataToUpdate: new Dictionary<string, object> { { "project", "C# Test" } }));
        Assert2xx(resp);
    }

    [Fact, TestPriority(6)]
    public async Task Test06_DownloadFile()
    {
        var resp = await DocumentApi.DownloadDocumentAsync(_fileId!.Value);
        Assert2xx(resp);
    }

    [Fact, TestPriority(7)]
    public async Task Test07_RenameFile()
    {
        var resp = await FileApi.RenameFileAsync(
            _fileId!.Value, new RenameRequest(newName: "cs-test-renamed.txt"));
        var renamed = resp.Ok()!;
        Assert.Equal("cs-test-renamed.txt", renamed.Name);
    }

    [Fact, TestPriority(8)]
    public async Task Test08_CopyFile()
    {
        var targetResp = await FolderApi.CreateFolderAsync(
            new CreateFolderRequest(name: $"C# SDK Target {Guid.NewGuid().ToString("N")[..8]}"));
        var targetFolder = Deserialize2xx<FolderResponse>(targetResp);
        _targetFolderId = targetFolder.Id;

        var copyResp = await FileApi.CopyFilesAsync(
            new CopyRequest(
                documentIds: new List<Guid> { _fileId!.Value },
                targetFolderId: _targetFolderId));
        var copies = copyResp.Ok()!;
        Assert.NotEmpty(copies);
        _copyFileId = copies[0].CopyId;
    }

    [Fact, TestPriority(9)]
    public async Task Test09_MoveFile()
    {
        var resp = await FileApi.MoveFilesAsync(
            new MoveRequest(
                documentIds: new List<Guid> { _fileId!.Value },
                targetFolderId: _targetFolderId,
                allowDuplicateFileNames: true));
        Assert2xx(resp);

        var listResp = await FolderApi.ListFolderAsync(_targetFolderId!.Value, true, false);
        var contents = listResp.Ok()!;
        Assert.Contains(contents, item => item.Id == _fileId);
    }

    [Fact, TestPriority(10)]
    public async Task Test10_ToggleFavorite()
    {
        var resp = await FavApi.ToggleFavoriteAsync(_fileId!.Value);
        Assert2xx(resp);
    }

    [Fact, TestPriority(11)]
    public async Task Test11_DashboardStatistics()
    {
        var resp = await DashboardApiClient.GetDashboardStatisticsAsync();
        var stats = resp.Ok()!;
        Assert.True(stats.TotalFiles > 0);
    }

    [Fact, TestPriority(12)]
    public async Task Test12_AuditTrail()
    {
        var resp = await AuditApi.GetAuditTrailAsync(_fileId!.Value, sort: "DESC");
        var trail = resp.Ok()!;
        Assert.NotEmpty(trail);
    }

    [Fact, TestPriority(13)]
    public async Task Test13_Cleanup()
    {
        Assert2xx(await FavApi.RemoveFavoriteAsync(_fileId!.Value));
        Assert2xx(await FileApi.DeleteFilesAsync(
            new DeleteRequest(documentIds: new List<Guid> { _fileId!.Value, _copyFileId!.Value })));
        Assert2xx(await FolderApi.DeleteFoldersAsync(
            new DeleteRequest(documentIds: new List<Guid> { _folderId!.Value, _targetFolderId!.Value })));
    }
}

// Test ordering infrastructure for xUnit
[AttributeUsage(AttributeTargets.Method)]
public class TestPriorityAttribute : Attribute
{
    public int Priority { get; }
    public TestPriorityAttribute(int priority) => Priority = priority;
}

public class PriorityOrderer : ITestCaseOrderer
{
    public IEnumerable<TTestCase> OrderTestCases<TTestCase>(
        IEnumerable<TTestCase> testCases) where TTestCase : ITestCase
    {
        var sorted = new SortedDictionary<int, List<TTestCase>>();
        foreach (var testCase in testCases)
        {
            var priority = testCase.TestMethod.Method
                .GetCustomAttributes(typeof(TestPriorityAttribute).AssemblyQualifiedName!)
                .FirstOrDefault()
                ?.GetNamedArgument<int>("Priority") ?? int.MaxValue;

            if (!sorted.ContainsKey(priority))
                sorted[priority] = new List<TTestCase>();
            sorted[priority].Add(testCase);
        }
        return sorted.SelectMany(pair => pair.Value);
    }
}
