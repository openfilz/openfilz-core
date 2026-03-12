#!/bin/bash
# Fix OpenAPI Generator C# bug: List<Stream> passed to StreamContent() for multi-file upload.
# Targets only the upload-multiple method using a sed line range.
FILE="$1"
if [ -f "$FILE" ]; then
    sed -i '/upload-multiple/,/documents\/upload"/{s/multipartContentLocalVar\.Add(new StreamContent(file));/foreach (var fileItem in file) { multipartContentLocalVar.Add(new StreamContent(fileItem)); }/}' "$FILE"
fi
