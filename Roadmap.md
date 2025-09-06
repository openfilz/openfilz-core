# Product Roadmap

This document outlines the major milestones we have in mind for improving the Document Management System.

## M1

*   **Automated Integration Tests with Testcontainers**: Implement a comprehensive suite of automated integration tests using Testcontainers. This will involve setting up a testing environment with Docker containers for PostgreSQL, MinIO, and Keycloak to ensure the application is tested in an environment that closely resembles production.
*   **Audit**: Implement custom audit trail per action, and let the users search for audi logs via API 
*   **Customizable Security**

## M2

*   **Anti-virus API**: Integrate an anti-virus scanning service to scan uploaded files for malware. This will involve adding a new API endpoint that can be used to scan files and a service that integrates with a third-party anti-virus engine.
*   **File Sharing**: Implement functionality to allow users to share files and folders with other users. This will include features for setting permissions (e.g., read-only, read-write) and generating shareable links.

## M3

*   **Front-End "Google Drive" Like**: Develop a modern, user-friendly web interface for the document management system that mimics the look and feel of Google Drive. This will provide users with an intuitive way to manage their files and folders.

## M4

*   **Conversational Search (AI)**: Implement an AI-powered conversational search feature that allows users to find documents using natural language queries. This will involve integrating with a large language model (LLM) to understand user intent and provide more relevant search results.

Many other amazing features can be implemented