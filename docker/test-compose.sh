#!/bin/bash
# Test script for Docker Compose files
# Validates syntax and configuration without starting containers

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

PASSED=0
FAILED=0

# Test function
test_compose() {
    local name="$1"
    shift
    local files="$@"

    echo -n "Testing: $name... "

    if docker-compose $files config > /dev/null 2>&1; then
        echo -e "${GREEN}PASSED${NC}"
        ((PASSED++))
    else
        echo -e "${RED}FAILED${NC}"
        echo "  Error details:"
        docker-compose $files config 2>&1 | head -10 | sed 's/^/  /'
        ((FAILED++))
    fi
}

# Test function for service count
test_service_count() {
    local name="$1"
    local expected="$2"
    shift 2
    local files="$@"

    local count=$(docker-compose $files config --services 2>/dev/null | wc -l)

    echo -n "  Service count ($expected expected)... "
    if [ "$count" -eq "$expected" ]; then
        echo -e "${GREEN}PASSED${NC} ($count services)"
        ((PASSED++))
    else
        echo -e "${RED}FAILED${NC} (got $count, expected $expected)"
        ((FAILED++))
    fi
}

# Test function for required services
test_has_service() {
    local service="$1"
    shift
    local files="$@"

    echo -n "  Has service '$service'... "
    if docker-compose $files config --services 2>/dev/null | grep -q "^${service}$"; then
        echo -e "${GREEN}PASSED${NC}"
        ((PASSED++))
    else
        echo -e "${RED}FAILED${NC}"
        ((FAILED++))
    fi
}

# Test for specific environment variable in a service
test_env_var() {
    local service="$1"
    local var_name="$2"
    local expected_value="$3"
    shift 3
    local files="$@"

    echo -n "  $service has $var_name=$expected_value... "
    local config=$(docker-compose $files config 2>/dev/null)
    if echo "$config" | grep -A 100 "^  ${service}:" | grep -q "${var_name}.*${expected_value}"; then
        echo -e "${GREEN}PASSED${NC}"
        ((PASSED++))
    else
        echo -e "${YELLOW}SKIPPED${NC} (manual verification needed)"
    fi
}

echo "========================================"
echo "OpenFilz Docker Compose Validation Tests"
echo "========================================"
echo ""

# Check if docker-compose is available
if ! command -v docker-compose &> /dev/null; then
    echo -e "${RED}ERROR: docker-compose is not installed${NC}"
    exit 1
fi

echo "Docker Compose version: $(docker-compose --version)"
echo ""

# ============================================
# Test 1: Base file syntax validation
# ============================================
echo -e "${YELLOW}1. Base File Syntax Validation${NC}"
echo "----------------------------------------"
test_compose "docker-compose.yml (base)" "-f docker-compose.yml"
echo ""

# ============================================
# Test 2: Base configuration details
# ============================================
echo -e "${YELLOW}2. Base Configuration${NC}"
echo "----------------------------------------"
test_service_count "Base" 3 "-f docker-compose.yml"
test_has_service "postgres" "-f docker-compose.yml"
test_has_service "openfilz-api" "-f docker-compose.yml"
test_has_service "openfilz-web" "-f docker-compose.yml"
echo ""

# ============================================
# Test 3: Auth overlay
# ============================================
echo -e "${YELLOW}3. Auth Overlay (Base + Keycloak)${NC}"
echo "----------------------------------------"
test_compose "Base + Auth" "-f docker-compose.yml -f docker-compose.auth.yml"
test_service_count "Base + Auth" 4 "-f docker-compose.yml -f docker-compose.auth.yml"
test_has_service "keycloak" "-f docker-compose.yml -f docker-compose.auth.yml"
test_has_service "postgres" "-f docker-compose.yml -f docker-compose.auth.yml"
test_has_service "openfilz-api" "-f docker-compose.yml -f docker-compose.auth.yml"
test_has_service "openfilz-web" "-f docker-compose.yml -f docker-compose.auth.yml"
echo ""

# ============================================
# Test 4: MinIO overlay
# ============================================
echo -e "${YELLOW}4. MinIO Overlay (Base + MinIO)${NC}"
echo "----------------------------------------"
test_compose "Base + MinIO" "-f docker-compose.yml -f docker-compose.minio.yml"
test_service_count "Base + MinIO" 5 "-f docker-compose.yml -f docker-compose.minio.yml"
test_has_service "minio" "-f docker-compose.yml -f docker-compose.minio.yml"
test_has_service "minio-init" "-f docker-compose.yml -f docker-compose.minio.yml"
echo ""

# ============================================
# Test 5: OnlyOffice overlay
# ============================================
echo -e "${YELLOW}5. OnlyOffice Overlay (Base + OnlyOffice)${NC}"
echo "----------------------------------------"
test_compose "Base + OnlyOffice" "-f docker-compose.yml -f docker-compose.onlyoffice.yml"
test_service_count "Base + OnlyOffice" 4 "-f docker-compose.yml -f docker-compose.onlyoffice.yml"
test_has_service "onlyoffice" "-f docker-compose.yml -f docker-compose.onlyoffice.yml"
echo ""

# ============================================
# Test 6: Fulltext overlay
# ============================================
echo -e "${YELLOW}6. Fulltext Overlay (Base + OpenSearch)${NC}"
echo "----------------------------------------"
test_compose "Base + Fulltext" "-f docker-compose.yml -f docker-compose.fulltext.yml"
test_service_count "Base + Fulltext" 5 "-f docker-compose.yml -f docker-compose.fulltext.yml"
test_has_service "opensearch" "-f docker-compose.yml -f docker-compose.fulltext.yml"
test_has_service "opensearch-dashboards" "-f docker-compose.yml -f docker-compose.fulltext.yml"
echo ""

# ============================================
# Test 7: Full stack (all overlays)
# ============================================
echo -e "${YELLOW}7. Full Stack (All Overlays)${NC}"
echo "----------------------------------------"
FULL_STACK="-f docker-compose.yml -f docker-compose.auth.yml -f docker-compose.minio.yml -f docker-compose.onlyoffice.yml -f docker-compose.fulltext.yml"
test_compose "Full Stack" "$FULL_STACK"
test_service_count "Full Stack" 9 "$FULL_STACK"
test_has_service "postgres" "$FULL_STACK"
test_has_service "openfilz-api" "$FULL_STACK"
test_has_service "openfilz-web" "$FULL_STACK"
test_has_service "keycloak" "$FULL_STACK"
test_has_service "minio" "$FULL_STACK"
test_has_service "minio-init" "$FULL_STACK"
test_has_service "onlyoffice" "$FULL_STACK"
test_has_service "opensearch" "$FULL_STACK"
test_has_service "opensearch-dashboards" "$FULL_STACK"
echo ""

# ============================================
# Test 8: Common combinations
# ============================================
echo -e "${YELLOW}8. Common Combinations${NC}"
echo "----------------------------------------"
test_compose "Auth + MinIO" "-f docker-compose.yml -f docker-compose.auth.yml -f docker-compose.minio.yml"
test_compose "Auth + Fulltext" "-f docker-compose.yml -f docker-compose.auth.yml -f docker-compose.fulltext.yml"
test_compose "Auth + OnlyOffice" "-f docker-compose.yml -f docker-compose.auth.yml -f docker-compose.onlyoffice.yml"
test_compose "MinIO + Fulltext" "-f docker-compose.yml -f docker-compose.minio.yml -f docker-compose.fulltext.yml"
test_compose "All except Auth" "-f docker-compose.yml -f docker-compose.minio.yml -f docker-compose.onlyoffice.yml -f docker-compose.fulltext.yml"
echo ""

# ============================================
# Test 9: Template and Config Files
# ============================================
echo -e "${YELLOW}9. Template and Config Files${NC}"
echo "----------------------------------------"

echo -n "ngx-env.template.js exists... "
if [ -f "ngx-env.template.js" ]; then
    echo -e "${GREEN}PASSED${NC}"
    ((PASSED++))
else
    echo -e "${RED}FAILED${NC}"
    ((FAILED++))
fi

echo -n ".env.example exists... "
if [ -f ".env.example" ]; then
    echo -e "${GREEN}PASSED${NC}"
    ((PASSED++))
else
    echo -e "${RED}FAILED${NC}"
    ((FAILED++))
fi

echo -n "Makefile exists... "
if [ -f "Makefile" ]; then
    echo -e "${GREEN}PASSED${NC}"
    ((PASSED++))
else
    echo -e "${RED}FAILED${NC}"
    ((FAILED++))
fi

echo -n "README.md exists... "
if [ -f "README.md" ]; then
    echo -e "${GREEN}PASSED${NC}"
    ((PASSED++))
else
    echo -e "${RED}FAILED${NC}"
    ((FAILED++))
fi
echo ""

# ============================================
# Test 10: Environment variable substitution
# ============================================
echo -e "${YELLOW}10. Environment Variable Handling${NC}"
echo "----------------------------------------"

# Test that compose files work without .env file
echo -n "Works without .env file... "
if docker-compose -f docker-compose.yml config > /dev/null 2>&1; then
    echo -e "${GREEN}PASSED${NC}"
    ((PASSED++))
else
    echo -e "${RED}FAILED${NC}"
    ((FAILED++))
fi

# Test envsubst for template
echo -n "envsubst available... "
if command -v envsubst &> /dev/null; then
    echo -e "${GREEN}PASSED${NC}"
    ((PASSED++))

    # Test actual template generation
    echo -n "Template generation works... "
    export NG_APP_API_URL="http://test:8081/api/v1"
    export NG_APP_GRAPHQL_URL="http://test:8081/graphql/v1"
    export NG_APP_AUTHENTICATION_ENABLED="true"
    export NG_APP_AUTHENTICATION_AUTHORITY="http://test:8180/realms/test"
    export NG_APP_AUTHENTICATION_CLIENT_ID="test-client"
    export NG_APP_ONLYOFFICE_ENABLED="false"

    if envsubst < ngx-env.template.js > /tmp/ngx-env-test.js 2>/dev/null; then
        if grep -q '"NG_APP_AUTHENTICATION_ENABLED": "true"' /tmp/ngx-env-test.js; then
            echo -e "${GREEN}PASSED${NC}"
            ((PASSED++))
        else
            echo -e "${RED}FAILED${NC} (substitution not working)"
            ((FAILED++))
        fi
        rm -f /tmp/ngx-env-test.js
    else
        echo -e "${RED}FAILED${NC}"
        ((FAILED++))
    fi
else
    echo -e "${YELLOW}SKIPPED${NC} (envsubst not available)"
fi
echo ""

# ============================================
# Test 11: Port conflict detection
# ============================================
echo -e "${YELLOW}11. Port Configuration${NC}"
echo "----------------------------------------"

# Check that key ports are exposed
echo -n "API port (8081) configured... "
if docker-compose -f docker-compose.yml config 2>/dev/null | grep -q "8081:8081\|8081:"; then
    echo -e "${GREEN}PASSED${NC}"
    ((PASSED++))
else
    echo -e "${RED}FAILED${NC}"
    ((FAILED++))
fi

echo -n "Web port (4200) configured... "
if docker-compose -f docker-compose.yml config 2>/dev/null | grep -q "4200:"; then
    echo -e "${GREEN}PASSED${NC}"
    ((PASSED++))
else
    echo -e "${RED}FAILED${NC}"
    ((FAILED++))
fi

echo -n "DB port (5432) configured... "
if docker-compose -f docker-compose.yml config 2>/dev/null | grep -q "5432:5432\|5432:"; then
    echo -e "${GREEN}PASSED${NC}"
    ((PASSED++))
else
    echo -e "${RED}FAILED${NC}"
    ((FAILED++))
fi
echo ""

# ============================================
# Test 12: Network configuration
# ============================================
echo -e "${YELLOW}12. Network Configuration${NC}"
echo "----------------------------------------"

echo -n "openfilz-network defined... "
if docker-compose -f docker-compose.yml config 2>/dev/null | grep -q "openfilz-network"; then
    echo -e "${GREEN}PASSED${NC}"
    ((PASSED++))
else
    echo -e "${RED}FAILED${NC}"
    ((FAILED++))
fi
echo ""

# ============================================
# Test 13: Volume configuration
# ============================================
echo -e "${YELLOW}13. Volume Configuration${NC}"
echo "----------------------------------------"

echo -n "postgres-data volume defined... "
if docker-compose -f docker-compose.yml config 2>/dev/null | grep -q "postgres-data"; then
    echo -e "${GREEN}PASSED${NC}"
    ((PASSED++))
else
    echo -e "${RED}FAILED${NC}"
    ((FAILED++))
fi

echo -n "openfilz-storage volume defined... "
if docker-compose -f docker-compose.yml config 2>/dev/null | grep -q "openfilz-storage"; then
    echo -e "${GREEN}PASSED${NC}"
    ((PASSED++))
else
    echo -e "${RED}FAILED${NC}"
    ((FAILED++))
fi
echo ""

# ============================================
# Test 14: Healthcheck configuration
# ============================================
echo -e "${YELLOW}14. Healthcheck Configuration${NC}"
echo "----------------------------------------"

echo -n "postgres has healthcheck... "
if docker-compose -f docker-compose.yml config 2>/dev/null | grep -A 20 "postgres:" | grep -q "healthcheck"; then
    echo -e "${GREEN}PASSED${NC}"
    ((PASSED++))
else
    echo -e "${RED}FAILED${NC}"
    ((FAILED++))
fi

echo -n "openfilz-api has healthcheck... "
if docker-compose -f docker-compose.yml config 2>/dev/null | grep -A 30 "openfilz-api:" | grep -q "healthcheck"; then
    echo -e "${GREEN}PASSED${NC}"
    ((PASSED++))
else
    echo -e "${RED}FAILED${NC}"
    ((FAILED++))
fi
echo ""

# ============================================
# Summary
# ============================================
echo "========================================"
echo "Test Summary"
echo "========================================"
echo -e "Passed: ${GREEN}$PASSED${NC}"
echo -e "Failed: ${RED}$FAILED${NC}"
echo ""

if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}All tests passed!${NC}"
    exit 0
else
    echo -e "${RED}Some tests failed. Please review the errors above.${NC}"
    exit 1
fi
