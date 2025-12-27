import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { firstValueFrom } from 'rxjs';
import { environment } from '../../environments/environment';

export type UserRole = 'READER' | 'CONTRIBUTOR' | 'AUDITOR' | 'CLEANER';

const VALID_ROLES: UserRole[] = ['READER', 'CONTRIBUTOR', 'AUDITOR', 'CLEANER'];

@Injectable({ providedIn: 'root' })
export class RoleService {
  private userRoles: UserRole[] = [];
  private initialized = false;
  private oidcSecurityService = inject(OidcSecurityService);
  private router = inject(Router);

  /**
   * Check if the user has a specific role.
   * When authentication is disabled, always returns true.
   */
  hasRole(role: UserRole): boolean {
    if (!environment.authentication.enabled) {
      return true;
    }
    return this.userRoles.includes(role);
  }

  /**
   * Get all roles of the current user.
   */
  getRoles(): UserRole[] {
    if (!environment.authentication.enabled) {
      return [...VALID_ROLES];
    }
    return [...this.userRoles];
  }

  /**
   * Initialize roles from the JWT access token.
   * Should be called after successful authentication.
   * Returns true if at least one valid role was found, false otherwise.
   */
  async initializeRoles(): Promise<boolean> {
    if (!environment.authentication.enabled) {
      this.initialized = true;
      return true;
    }

    const accessToken = await firstValueFrom(this.oidcSecurityService.getAccessToken());
    if (!accessToken) {
      console.error('No access token available');
      return false;
    }

    const decodedToken = this.decodeToken(accessToken);
    if (!decodedToken) {
      console.error('Failed to decode access token');
      return false;
    }

    // First, try to extract roles from realm_access.roles
    const realmRoles = this.extractRealmAccessRoles(decodedToken);
    if (realmRoles.length > 0) {
      this.userRoles = realmRoles;
      this.initialized = true;
      console.log('Roles initialized from realm_access.roles:', this.userRoles);
      return true;
    }

    // If no roles found in realm_access.roles, try groups
    const groupRoles = this.extractGroupRoles(decodedToken);
    if (groupRoles.length > 0) {
      this.userRoles = groupRoles;
      this.initialized = true;
      console.log('Roles initialized from groups:', this.userRoles);
      return true;
    }

    // No valid roles found
    console.error('No valid roles found in token');
    this.initialized = true;
    return false;
  }

  /**
   * Check if roles have been initialized.
   */
  isInitialized(): boolean {
    return this.initialized;
  }

  /**
   * Handle the case when user has no valid roles.
   * Shows error message and logs out.
   */
  handleNoRoles(): void {
    alert('You do not have the required role to access this application');
    this.oidcSecurityService.logoff();
  }

  /**
   * Decode a JWT token.
   */
  private decodeToken(token: string): any {
    try {
      const parts = token.split('.');
      if (parts.length !== 3) {
        return null;
      }
      const payload = parts[1];
      const decoded = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
      return JSON.parse(decoded);
    } catch (e) {
      console.error('Error decoding token:', e);
      return null;
    }
  }

  /**
   * Extract valid roles from realm_access.roles in the token.
   */
  private extractRealmAccessRoles(decodedToken: any): UserRole[] {
    const realmAccess = decodedToken?.realm_access;
    if (!realmAccess || !Array.isArray(realmAccess.roles)) {
      return [];
    }

    return realmAccess.roles.filter((role: string) =>
      VALID_ROLES.includes(role as UserRole)
    ) as UserRole[];
  }

  /**
   * Extract valid roles from groups in the token.
   * Groups may be in format "/path/to/ROLE", in which case
   * we extract the part after the last "/".
   */
  private extractGroupRoles(decodedToken: any): UserRole[] {
    const groups = decodedToken?.groups;
    if (!Array.isArray(groups)) {
      return [];
    }

    const roles: UserRole[] = [];
    for (const group of groups) {
      if (typeof group !== 'string') {
        continue;
      }

      // If group contains "/", extract the part after the last "/"
      let roleName = group;
      const lastSlashIndex = group.lastIndexOf('/');
      if (lastSlashIndex !== -1) {
        roleName = group.substring(lastSlashIndex + 1);
      }

      if (VALID_ROLES.includes(roleName as UserRole)) {
        roles.push(roleName as UserRole);
      }
    }

    return roles;
  }
}
