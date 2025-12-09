package org.openfilz.dms.controller.rest;

public interface ApiDescription {

    String ALLOW_DUPLICATE_FILE_NAME_PARAM_DESCRIPTION = "when true : if a file with the same name already exists in the target parent folder, " +
            "a 409 error is raised. When false : the file will be stored (but no existing file with same name will be overwritten)";

    String UPLOAD_MULTIPLE_DESCRIPTION = """
            Paste the JSON array here\
            <br>This array provides optional metadata and attributes for the uploaded files\
            <br>\
            <section>\
              <h3>Each object in the array must contain:</h3>
            
              <table>
                <thead>
                  <tr>
                    <th>Field</th>
                    <th>Type</th>
                    <th>Description</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td><code>filename</code></td>
                    <td><code>String</code></td>
                    <td>Must match the filename of one of the uploaded files. Filenames must be unique in the upload request.</td>
                  </tr>
                  <tr>
                    <td><code>fileAttributes</code></td>
                    <td><code>JSON Object</code></td>
                    <td>Container for metadata and other file-specific attributes.</td>
                  </tr>
                </tbody>
              </table>
            
              <h4>Inside <code>fileAttributes</code>:</h4>
              <table>
                <thead>
                  <tr>
                    <th>Field</th>
                    <th>Type</th>
                    <th>Description</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td><code>parentFolderId</code></td>
                    <td><code>UUID</code></td>
                    <td>UUID of the target folder to upload the file (if not provided : the file will be uploaded at the root level)</td>
                  </tr>
                  <tr>
                    <td><code>metadata</code></td>
                    <td><code>Map&lt;String, JSON Object&gt;</code></td>
                    <td>Arbitrary metadata to associate with the file (key/value pairs).</td>
                  </tr>
                </tbody>
              </table>
            
              <h4>Example of <code>parametersByFilename</code> JSON array :</h4>
              <pre><code>[
              {
                "filename": "file1.txt",
                "fileAttributes": {
                  "parentFolderId": "f8c3de3d-1fea-4d7c-a8b0-29f63c4c3454",
                  "metadata": {
                    "country": "UK",
                    "owner": {
                      "name": "Joe",
                      "owner_id": 1234568
                    }
                  }
                }
              },
              {
                "filename": "file2.md",
                "fileAttributes": {
                  "metadata": {
                    "key1": "value1",
                    "key2": "value2",
                    "key3": "value3"
                  }
                }
              }
            ]</code></pre>
            </section>""";
}
